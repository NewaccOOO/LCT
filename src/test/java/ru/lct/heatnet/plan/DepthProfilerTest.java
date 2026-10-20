package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class DepthProfilerTest {
    private final Rules rules = Rules.load();

    @Test
    void gasFarFromNodesIsPassedOverWithoutExtraCost() {
        Variant variant = depth(PlanFixture.trunk().restriction("gas-1", PlanFixture.line(0, 30, 600, 30), "gas_pipeline")
                .oks("1", 300, 80, 20));
        checkProfile(variant);
        NewSegment plateau = variant.getSegments().stream().filter(s -> NetworkAssembler.SPECIAL.equals(s.getLayingMethod()))
                .findFirst().orElseThrow();
        double over = 2.8 - 0.2 - rules.diameter(plateau.getDiameter()).getHeightM();
        assertEquals(over, plateau.getDepthStart(), 0.0011, "над газом, ближе всего к 3,0 м");
        assertEquals(plateau.getDepthStart(), plateau.getDepthEnd(), 1e-9, "спецучасток на постоянной глубине");
    }

    @Test
    void sewerCrossingKeepsVerticalClearance() {
        Variant variant = depth(PlanFixture.trunk().restriction("sewer-1", PlanFixture.line(0, 30, 600, 30), "sewer")
                .oks("1", 300, 80, 20));
        checkProfile(variant);
        assertTrue(variant.getSegments().stream().anyMatch(s -> s.getDepthCrossings() != null
                && s.getDepthCrossings().stream().anyMatch(c -> "sewer".equals(c.getType()))), "пересечение с канализацией не учтено");
        for (NewSegment s : variant.getSegments()) {
            if (s.getDepthCrossings() != null && s.getDepthCrossings().stream().anyMatch(c -> "sewer".equals(c.getType()))) {
                double h = rules.diameter(s.getDiameter()).getHeightM();
                double top = s.getDepthStart();
                assertTrue(top + h <= 3.5 - 0.2 + 0.0011 || top >= 3.5 + 0.4 + 0.2 - 0.0011, s.getId() + " просвет до канализации");
            }
        }
    }

    @Test
    void gasNextToTieMovesTieDepth() {
        Variant variant = depth(PlanFixture.trunk().restriction("gas-1", PlanFixture.line(0, 5, 600, 5), "gas_pipeline")
                .oks("1", 300, 80, 20));
        checkProfile(variant);
        assertTrue(variant.getSegments().stream().allMatch(s -> s.getDepthStart() <= 3 + 1e-9 && s.getDepthEnd() <= 3 + 1e-9),
                "участок глубже 3,0 м");
    }

    @Test
    void chamberWithinToleranceLetsPipePassOverGas() {
        Variant variant = depth(PlanFixture.trunk().restriction("gas-1", PlanFixture.line(0, 7.5, 600, 7.5), "gas_pipeline")
                .oks("1", 300, 80, 20));
        checkProfile(variant);
        assertTrue(variant.getChambers().stream().allMatch(c -> Math.abs(c.getDepth() - 3) <= 0.05 + 1e-9),
                "камера вне допуска 3,0 ± 0,05 м");
        assertTrue(variant.getSegments().stream().allMatch(s -> s.getDepthStart() <= 3 + 1e-9 && s.getDepthEnd() <= 3 + 1e-9),
                "участок глубже 3,0 м: над газом не прошли");
    }

    private Variant depth(PlanFixture fixture) {
        VariantEnumerator enumerator = new VariantEnumerator(fixture.input(), rules);
        Result flat = enumerator.run();
        Result deep = enumerator.depth(flat);
        assertEquals(flat.getVariants().size(), deep.getVariants().size());
        return deep.getVariants().get(0);
    }

    private static void checkProfile(Variant variant) {
        Map<String, Double> atNode = new HashMap<>();
        for (NewSegment s : variant.getSegments()) {
            assertNotNull(s.getDepthStart(), s.getId());
            assertNotNull(s.getDepthEnd(), s.getId());
            assertTrue(Math.abs(s.getDepthEnd() - s.getDepthStart()) <= 0.1 * s.getLength() + 0.0015, s.getId() + " уклон");
            assertTrue(Math.min(s.getDepthStart(), s.getDepthEnd()) >= 0.7 - 1e-9, s.getId() + " мельче 0,7");
            assertTrue((s.getDepthStart() - 3) * (s.getDepthEnd() - 3) >= -1e-9, s.getId() + " через 3,0 м");
            for (Map.Entry<String, Double> end : List.of(Map.entry(s.getStartNodeId(), s.getDepthStart()),
                    Map.entry(s.getEndNodeId(), s.getDepthEnd()))) {
                Double other = atNode.putIfAbsent(end.getKey(), end.getValue());
                assertTrue(other == null || Math.abs(other - end.getValue()) < 1e-9, end.getKey() + " глубина в узле");
            }
        }
    }
}
