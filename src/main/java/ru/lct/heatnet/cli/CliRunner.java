package ru.lct.heatnet.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.core.Pipeline;
import ru.lct.heatnet.io.GeoJsonStreamReader;
import ru.lct.heatnet.io.GeoJsonStreamWriter;
import ru.lct.heatnet.model.Diagnostic;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;

/** Режим CLI (D-16): {@code java -jar heatnet.jar --cli <input> <output>}, без веба и базы. */
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
            System.err.println("Использование: java -jar heatnet.jar --cli <входной GeoJSON> <выходной GeoJSON>");
            return FAILED;
        }
        long started = System.nanoTime();
        try {
            InputData input = GeoJsonStreamReader.read(Path.of(files.get(0)));
            if (!input.getDiagnostics().isEmpty()) {
                for (Diagnostic diagnostic : input.getDiagnostics()) {
                    System.err.printf("%s %s: %s%n", diagnostic.getFeatureId(), diagnostic.getField(), diagnostic.getProblem());
                }
                return INVALID_INPUT;
            }
            Result result = pipeline.run(input);
            GeoJsonStreamWriter.write(result, Path.of(files.get(1)));
            double elapsed = (System.nanoTime() - started) / 1e9;
            System.out.printf(Locale.ROOT, "PIPELINE DONE variants=%d elapsed=%.1fs%n", result.getVariants().size(), elapsed);
            return OK;
        } catch (RuntimeException e) {
            System.err.println("Расчёт не выполнен: " + e);
            e.printStackTrace();
            return FAILED;
        }
    }
}
