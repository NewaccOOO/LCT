package ru.lct.heatnet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.io.GeoJsonStreamReader;
import ru.lct.heatnet.io.GeoJsonStreamWriter;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Result;

/** Сквозной прогон: пайплайн на синтетике из репозитория, выход проверяет независимый проверщик check18. */
class PipelineSampleIT {
    private static final Path SAMPLE = Path.of("data/samples/small-1.geojson");
    private static final Path OUTPUT = Path.of("target/pipeline-sample-small-1.geojson");
    private static final long VALIDATOR_TIMEOUT_MIN = 5;

    @Test
    void sampleOutputPassesValidator() throws Exception {
        InputData input = GeoJsonStreamReader.read(SAMPLE);
        assertTrue(input.getDiagnostics().isEmpty(), "диагностики входа: " + input.getDiagnostics());

        Result result = new PipelineImpl().run(input);
        Files.createDirectories(OUTPUT.getParent());
        GeoJsonStreamWriter.write(result, OUTPUT);

        Process process = new ProcessBuilder("uv", "run", "--project", "tools", "python", "tools/validator/check18.py",
                SAMPLE.toString(), OUTPUT.toString())
                .redirectErrorStream(true)
                .start();
        String report = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(VALIDATOR_TIMEOUT_MIN, TimeUnit.MINUTES), "валидатор не завершился");
        assertEquals(0, process.exitValue(), report);
        assertTrue(report.contains("CHECK18 OK"), report);
    }
}
