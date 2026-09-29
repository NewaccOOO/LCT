package ru.lct.heatnet.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile("!cli")
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Задачи", description = "Загрузка входного GeoJSON, статус расчёта и выгрузка результата")
public class JobController {
    private static final String GEO_JSON = "application/geo+json";
    private static final String JOB_ID_EXAMPLE = "3f0c6a52-2d4e-4b8e-9a41-6f1c2b7d9e10";
    private static final String NOT_FOUND_EXAMPLE = "{\"message\": \"Задача не найдена\", \"errors\": []}";

    private final JobRepository jobs;
    private final JobWorker worker;
    private final Path dataDir;

    public JobController(JobRepository jobs, JobWorker worker, @Value("${app.data-dir}") String dataDir) {
        this.jobs = jobs;
        this.worker = worker;
        this.dataDir = Path.of(dataDir).toAbsolutePath();
    }

    @lombok.Value
    @Schema(description = "Принятая задача")
    public static class JobCreated {
        @Schema(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE)
        UUID id;
        @Schema(description = "Статус сразу после приёма", example = "QUEUED")
        JobEntity.Status status;
    }

    @lombok.Value
    @Schema(description = "Задача в общем списке")
    public static class JobListItem {
        @Schema(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE)
        UUID id;
        @Schema(description = "Статус: QUEUED в очереди, RUNNING считается, DONE готово, FAILED ошибка", example = "DONE")
        JobEntity.Status status;
        @Schema(description = "Когда задача принята", example = "2026-09-15T10:00:00Z")
        Instant createdAt;
        @Schema(description = "Когда расчёт закончился; null, пока задача не завершена", example = "2026-09-15T10:00:42Z")
        Instant finishedAt;
    }

    @lombok.Value
    @Schema(description = "Подробности задачи")
    public static class JobDetails {
        @Schema(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE)
        UUID id;
        @Schema(description = "Статус: QUEUED в очереди, RUNNING считается, DONE готово, FAILED ошибка", example = "DONE")
        JobEntity.Status status;
        @Schema(description = "Когда задача принята", example = "2026-09-15T10:00:00Z")
        Instant createdAt;
        @Schema(description = "Когда начался расчёт; null, пока задача в очереди", example = "2026-09-15T10:00:01Z")
        Instant startedAt;
        @Schema(description = "Когда расчёт закончился; null, пока задача не завершена", example = "2026-09-15T10:00:42Z")
        Instant finishedAt;
        @Schema(description = "Ошибка при статусе FAILED, иначе null", implementation = ApiError.class, nullable = true)
        JsonNode error;
        @ArraySchema(arraySchema = @Schema(
                description = "Сводки вариантов (variant_summary из выходного файла) при статусе DONE, иначе null. "
                        + "В поле criteria каждой сводки — дополнительные критерии варианта, в выходном файле их нет",
                nullable = true))
        JsonNode summary;
    }

    @Operation(
            summary = "Загрузить входной GeoJSON и поставить задачу в очередь",
            description = "Тело запроса это сам файл GeoJSON FeatureCollection, не multipart. Файл пишется на диск потоком, "
                    + "поэтому размер не ограничен памятью сервиса. Проверка атрибутов идёт уже в задаче: "
                    + "ошибки входных данных видны в поле error при статусе FAILED.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Входной GeoJSON FeatureCollection в EPSG:4326",
                    content = {
                            // первым, чтобы Swagger UI по умолчанию показывал кнопку выбора файла
                            @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = GEO_JSON, schema = @Schema(type = "object"), examples = @ExampleObject(
                                    name = "Источник",
                                    summary = "Файл из одного источника",
                                    value = "{\"type\": \"FeatureCollection\", \"features\": [{\"type\": \"Feature\", "
                                            + "\"geometry\": {\"type\": \"Point\", \"coordinates\": [37.578354495, 55.767196407]}, "
                                            + "\"properties\": {\"id\": \"src-1\", \"object_type\": \"source\"}}]}")),
                            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object"))
                    }),
            responses = {
                    @ApiResponse(responseCode = "202", description = "Задача принята и стоит в очереди",
                            content = @Content(schema = @Schema(implementation = JobCreated.class), examples = @ExampleObject(
                                    value = "{\"id\": \"" + JOB_ID_EXAMPLE + "\", \"status\": \"QUEUED\"}"))),
                    @ApiResponse(responseCode = "400", description = "Тело пустое или не начинается с JSON-объекта",
                            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(
                                    value = "{\"message\": \"Тело запроса должно быть JSON-объектом GeoJSON FeatureCollection\", "
                                            + "\"errors\": []}"))),
                    @ApiResponse(responseCode = "415", description = "Content-Type не application/json, application/geo+json или application/octet-stream",
                            content = @Content(schema = @Schema(implementation = ApiError.class)))
            })
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, GEO_JSON, MediaType.APPLICATION_OCTET_STREAM_VALUE}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobCreated> create(HttpServletRequest request) throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = dataDir.resolve("jobs").resolve(id.toString());
        Path input = dir.resolve("input.geojson");
        JobEntity job = new JobEntity();
        job.setId(id);
        job.setStatus(JobEntity.Status.QUEUED);
        job.setInputPath(input.toString());
        Files.createDirectories(dir);
        try (InputStream body = request.getInputStream()) {
            Files.copy(body, input);
            int first = firstSignificantByte(input);
            if (first == -1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тело запроса пустое, ожидается GeoJSON FeatureCollection");
            }
            if (first != '{') {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тело запроса должно быть JSON-объектом GeoJSON FeatureCollection");
            }
            job.setCreatedAt(Instant.now());
            // save() коммитит свою транзакцию до возврата, поэтому воркер получает уже видимую запись.
            jobs.save(job);
        } catch (IOException | RuntimeException e) {
            FileSystemUtils.deleteRecursively(dir);
            throw e;
        }
        worker.submit(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new JobCreated(id, job.getStatus()));
    }

    @Operation(
            summary = "Список задач",
            description = "Все задачи, новые первыми.",
            responses = @ApiResponse(responseCode = "200", description = "Список задач", content = @Content(
                    array = @ArraySchema(schema = @Schema(implementation = JobListItem.class)), examples = @ExampleObject(
                    value = "[{\"id\": \"" + JOB_ID_EXAMPLE + "\", \"status\": \"DONE\", "
                            + "\"createdAt\": \"2026-09-15T10:00:00Z\", \"finishedAt\": \"2026-09-15T10:00:42Z\"}]"))))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<JobListItem> list() {
        List<JobListItem> items = new ArrayList<>();
        for (JobEntity job : jobs.findAllByOrderByCreatedAtDesc()) {
            items.add(new JobListItem(job.getId(), job.getStatus(), job.getCreatedAt(), job.getFinishedAt()));
        }
        return items;
    }

    @Operation(
            summary = "Статус и итоги задачи",
            description = "После DONE в summary лежат сводки всех вариантов, после FAILED в error причина и список проблем "
                    + "во входном файле.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Задача найдена",
                            content = @Content(schema = @Schema(implementation = JobDetails.class), examples = {
                                    @ExampleObject(name = "Готово", value = "{\"id\": \"" + JOB_ID_EXAMPLE + "\", \"status\": \"DONE\", "
                                            + "\"createdAt\": \"2026-09-15T10:00:00Z\", \"startedAt\": \"2026-09-15T10:00:01Z\", "
                                            + "\"finishedAt\": \"2026-09-15T10:00:42Z\", \"error\": null, \"summary\": [{"
                                            + "\"id\": \"summary_1\", \"object_type\": \"variant_summary\", \"variant_id\": \"1\", "
                                            + "\"rank\": 1, \"construction_cost\": 33000000.00, \"chamber_construction_cost\": 3000000.00, "
                                            + "\"existing_chamber_tie_in_count\": 1, \"existing_chamber_tie_in_cost\": 5000000.00, "
                                            + "\"unconnected_penalty\": 0.00, \"calculated_cost\": 33000000.00, "
                                            + "\"new_network_length\": 340.50, \"score\": 1.946, "
                                            + "\"unconnected_oks_ids\": [], \"criteria\": {\"connected_oks\": 1, "
                                            + "\"connected_flow_tph\": 5.000, \"unconnected_reasons\": [], \"existing_chamber_tie_ins\": 1, "
                                            + "\"new_chambers\": 1, \"technical_nodes\": 0, \"special_segments\": 0, "
                                            + "\"special_length_m\": 0.00, \"crossed_objects\": {}, \"turns\": 1, \"max_turn_deg\": 45.0, "
                                            + "\"surcharge_cost\": 0.00, \"cost_per_oks\": 33000000.00, \"cost_per_tph\": 6600000.00}}]}"),
                                    @ExampleObject(name = "Ошибка входа", value = "{\"id\": \"" + JOB_ID_EXAMPLE + "\", "
                                            + "\"status\": \"FAILED\", \"createdAt\": \"2026-09-15T10:00:00Z\", "
                                            + "\"startedAt\": \"2026-09-15T10:00:01Z\", \"finishedAt\": \"2026-09-15T10:00:02Z\", "
                                            + "\"error\": {\"message\": \"Входной файл не прошёл проверку, найдено ошибок: 1\", "
                                            + "\"errors\": [{\"featureId\": \"oks-1\", \"field\": \"flow_tph\", "
                                            + "\"problem\": \"нет обязательного атрибута\"}]}, \"summary\": null}")
                            })),
                    @ApiResponse(responseCode = "404", description = "Задачи с таким id нет",
                            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
            })
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public JobDetails get(@Parameter(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE) @PathVariable String id) {
        JobEntity job = find(id);
        return new JobDetails(job.getId(), job.getStatus(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(),
                json(job.getError()), json(job.getSummary()));
    }

    @Operation(
            summary = "Скачать результат",
            description = "Выходной GeoJSON со всеми вариантами. Доступен только при статусе DONE, отдаётся потоком.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Файл результата",
                            content = @Content(mediaType = GEO_JSON, schema = @Schema(type = "string", format = "binary"))),
                    @ApiResponse(responseCode = "404", description = "Задачи с таким id нет",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
                    @ApiResponse(responseCode = "409", description = "Задача ещё не в статусе DONE",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(
                                    value = "{\"message\": \"Результат недоступен: задача в статусе RUNNING\", \"errors\": []}")))
            })
    @GetMapping(path = "/{id}/result")
    public ResponseEntity<FileSystemResource> result(
            @Parameter(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE) @PathVariable String id) {
        JobEntity job = find(id);
        if (job.getStatus() != JobEntity.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Результат недоступен: задача в статусе " + job.getStatus());
        }
        return download(Path.of(job.getOutputPath()), "result-" + job.getId() + ".geojson");
    }

    @Operation(
            summary = "Скачать входной файл",
            description = "Файл, загруженный при создании задачи, байт в байт. Отдаётся потоком.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Входной файл",
                            content = @Content(mediaType = GEO_JSON, schema = @Schema(type = "string", format = "binary"))),
                    @ApiResponse(responseCode = "404", description = "Задачи с таким id нет",
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
            })
    @GetMapping(path = "/{id}/input")
    public ResponseEntity<FileSystemResource> input(
            @Parameter(description = "Идентификатор задачи", example = JOB_ID_EXAMPLE) @PathVariable String id) {
        JobEntity job = find(id);
        return download(Path.of(job.getInputPath()), "input-" + job.getId() + ".geojson");
    }

    private JobEntity find(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не найдена");
        }
        return jobs.findById(uuid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача не найдена"));
    }

    private static ResponseEntity<FileSystemResource> download(Path file, String filename) {
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл задачи не найден на диске");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(GEO_JSON))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(new FileSystemResource(file));
    }

    private static JsonNode json(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return JobWorker.JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("В базе испорчен JSON задачи", e);
        }
    }

    // Пробелы по RFC 8259: пробел, табуляция, перевод строки, возврат каретки.
    private static int firstSignificantByte(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            int b = in.read();
            while (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                b = in.read();
            }
            return b;
        }
    }
}
