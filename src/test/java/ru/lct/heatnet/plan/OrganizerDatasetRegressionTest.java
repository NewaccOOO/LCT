package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

/**
 * Эталон S rank=1 на {@code data/real/dataset.geojson} с коммита до city-scale оптимизаций (baseline 12.596).
 * Менять константу только при намеренной смене качества (версия Y).
 */
class OrganizerDatasetRegressionTest {
    private static final double ORGANIZER_RANK1_SCORE = 12.596;

    @Test
    void rank1ScoreMatchesBaseline() throws Exception {
        Path dataset = Path.of("data/real/dataset.geojson");
        assumeTrue(Files.isRegularFile(dataset));
        Rules rules = Rules.load();
        Result result = new VariantEnumerator(VariantEnumerator.read(dataset, rules), rules).run();
        Variant best = result.getVariants().get(0);
        assertEquals(1, best.getSummary().getRank());
        assertEquals(ORGANIZER_RANK1_SCORE, best.getSummary().getScore(), 1e-6);
    }
}
