package ru.lct.heatnet.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.core.Pipeline;
import ru.lct.heatnet.io.GeoJsonStreamReader;
import ru.lct.heatnet.io.GeoJsonStreamWriter;
import ru.lct.heatnet.model.Diagnostic;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;

/** Выполняет задачи в пуле потоков; каждая смена статуса сохраняется отдельной короткой транзакцией. */
@Profile("!cli")
@Component
public class JobWorker {
    static final int MAX_STORED_ERRORS = 1000;
    // Точные десятичные: сводка в API совпадает с числами выходного файла, 12.30 не превращается в 12.3.
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobs;
    private final Pipeline pipeline;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public JobWorker(JobRepository jobs, Pipeline pipeline, @Value("${app.job-workers}") int workers) {
        this.jobs = jobs;
        this.pipeline = pipeline;
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setThreadNamePrefix("job-");
        executor.initialize();
    }

    // Выполняется до старта веб-сервера, поэтому не заденет задачи, принятые уже после рестарта.
    @PostConstruct
    void failInterrupted() {
        List<JobEntity> stale = jobs.findByStatusIn(List.of(JobEntity.Status.QUEUED, JobEntity.Status.RUNNING));
        for (JobEntity job : stale) {
            finishFailed(job, new ApiError("Задача прервана перезапуском сервиса", List.of()));
        }
        jobs.saveAll(stale);
        if (!stale.isEmpty()) {
            log.warn("jobs: {} unfinished jobs marked FAILED after restart", stale.size());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public void submit(UUID id) {
        executor.execute(() -> process(id));
    }

    private void process(UUID id) {
        JobEntity job = jobs.findById(id).orElseThrow();
        job.setStatus(JobEntity.Status.RUNNING);
        job.setStartedAt(Instant.now());
        jobs.save(job);
        try {
            Path input = Path.of(job.getInputPath());
            InputData data = GeoJsonStreamReader.read(input);
            List<Diagnostic> diagnostics = data.getDiagnostics();
            if (!diagnostics.isEmpty()) {
                String message = "Входной файл не прошёл проверку, найдено ошибок: " + diagnostics.size();
                if (diagnostics.size() > MAX_STORED_ERRORS) {
                    message += ", показаны первые " + MAX_STORED_ERRORS;
                }
                List<Diagnostic> shown = diagnostics.subList(0, Math.min(diagnostics.size(), MAX_STORED_ERRORS));
                finishFailed(job, new ApiError(message, shown));
                jobs.save(job);
                log.info("jobs: job {} failed input validation with {} diagnostics", id, diagnostics.size());
                return;
            }
            Result result = pipeline.run(data);
            Path output = input.resolveSibling("result.geojson");
            GeoJsonStreamWriter.write(result, output);
            job.setSummary(summaries(output));
            job.setOutputPath(output.toString());
            job.setStatus(JobEntity.Status.DONE);
            job.setFinishedAt(Instant.now());
            jobs.save(job);
            log.info("jobs: job {} done", id);
        } catch (Exception | OutOfMemoryError | StackOverflowError e) {
            // Ошибки JVM в расчёте тоже закрывают задачу, иначе она навсегда останется RUNNING.
            log.error("jobs: job {} failed", id, e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            finishFailed(job, new ApiError("Ошибка обработки задачи: " + reason, List.of()));
            jobs.save(job);
        }
    }

    private static void finishFailed(JobEntity job, ApiError error) {
        try {
            job.setError(JSON.writeValueAsString(error));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        job.setStatus(JobEntity.Status.FAILED);
        job.setFinishedAt(Instant.now());
    }

    // Сводки берутся из готового файла, чтобы API отдавал ровно те поля и числа, что записаны в результат.
    private static String summaries(Path output) throws IOException {
        ArrayNode summaries = JSON.createArrayNode();
        try (JsonParser parser = JSON.getFactory().createParser(output.toFile())) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME && "properties".equals(parser.getCurrentName())) {
                    parser.nextToken();
                    JsonNode properties = JSON.readTree(parser);
                    if ("variant_summary".equals(properties.path("object_type").textValue())) {
                        summaries.add(properties);
                    }
                }
            }
        }
        return JSON.writeValueAsString(summaries);
    }
}
