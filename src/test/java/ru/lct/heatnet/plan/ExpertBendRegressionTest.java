package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.rules.Rules;

class ExpertBendRegressionTest {
    private final Rules rules = Rules.load();

    @Test
    void pairWithInvalidExtensionCornerIsReplacedByOneVertex() {
        assertEquals(13.3153, score("e4-combined", 0), 1e-6);
    }

    @Test
    void pairOnEdgeWithGraphOnlyInWideAreaIsReplacedByOneVertex() {
        assertEquals(46.2128, score("e4-unreachable", 1), 1e-6);
    }

    private double score(String name, int variant) {
        Result result = new VariantEnumerator(VariantEnumerator.read(Path.of("data/samples/expert/" + name + ".geojson"), rules),
                rules).run();
        return result.getVariants().get(variant).getSummary().getScore();
    }
}
