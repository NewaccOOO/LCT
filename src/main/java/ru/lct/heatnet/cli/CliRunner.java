package ru.lct.heatnet.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.calc.VariantCriteria;
import ru.lct.heatnet.core.Pipeline;
import ru.lct.heatnet.io.GeoJsonStreamWriter;
import ru.lct.heatnet.model.Diagnostic;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.plan.VariantEnumerator;
import ru.lct.heatnet.rules.Rules;

/**
 * Режим CLI (D-16): {@code java -jar heatnet.jar --cli [--rules=<файл правил>] <input> <output>}, без веба и базы.
 * Рядом с выходом пишется {@code <output>.criteria.json} с дополнительными критериями вариантов.
 */
@Component
@Profile("cli")
public class CliRunner implements ApplicationRunner {
    private static final int OK = 0;
    private static final int FAILED = 1;
    private static final int INVALID_INPUT = 2;

    private final Pipeline pipeline;
    private final ConfigurableApplicationContext context;

    public CliRunner(Pipeline pipeline, ConfigurableApplicationContext context) {
        this.pipeline = pipeline;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = execute(args.getNonOptionArgs());
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private int execute(List<String> files) {
        if (files.size() != 2) {
            System.err.println("Использование: java -jar heatnet.jar --cli [--rules=<файл правил>] <входной GeoJSON> <выходной GeoJSON>");
            return FAILED;
        }
        long started = System.nanoTime();
        try {
            InputData input = VariantEnumerator.read(Path.of(files.get(0)), Rules.load());
            input.getWarnings().forEach(System.err::println);
            if (!input.getDiagnostics().isEmpty()) {
                for (Diagnostic diagnostic : input.getDiagnostics()) {
                    System.err.printf("%s %s: %s%n", diagnostic.getFeatureId(), diagnostic.getField(), diagnostic.getProblem());
                }
                return INVALID_INPUT;
            }
            Result result = pipeline.run(input);
            Path output = Path.of(files.get(1));
            long writing = System.nanoTime();
            GeoJsonStreamWriter.write(result, output);
            long written = System.nanoTime();
            Path criteria = writeCriteria(result, input, output);
            System.out.printf(Locale.ROOT, "WRITE geojson=%.1fs criteria=%.1fs%n", (written - writing) / 1e9,
                    (System.nanoTime() - written) / 1e9);
            double elapsed = (System.nanoTime() - started) / 1e9;
            System.out.printf(Locale.ROOT, "PIPELINE DONE variants=%d elapsed=%.1fs%n", result.getVariants().size(), elapsed);
            System.out.println("CRITERIA " + criteria);
            return OK;
        } catch (RuntimeException e) {
            System.err.println("Расчёт не выполнен: " + e);
            e.printStackTrace();
            return FAILED;
        }
    }

    /** Критерии вариантов в {@code <имя выхода без .geojson>.criteria.json} рядом с выходом. */
    private static Path writeCriteria(Result result, InputData input, Path output) {
        String name = output.getFileName().toString().replaceFirst("\\.geojson$", "");
        Path path = output.resolveSibling(name + ".criteria.json");
        VariantCriteria criteria = new VariantCriteria(input, Rules.load());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Variant variant : result.getVariants()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("variant_id", variant.getId());
            row.put("rank", variant.getSummary().getRank());
            row.put("score", variant.getSummary().getScore());
            row.put("calculated_cost", variant.getSummary().getCalculatedCost());
            row.put("criteria", criteria.of(variant));
            rows.add(row);
        }
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось записать " + path, e);
        }
        return path;
    }
}
