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

/** Режим с глубиной (§5 приложения 18.09): профиль у газопровода поперёк трассы. */
class DepthProfilerTest {
    private final Rules rules = Rules.load();

    @Test
    void gasFarFromNodesIsPassedOverWithoutExtraCost() {
        // газ в 30 м от магистрали, ОКС в 80 м: «над» — h ≤ 2,8 − 0,2 − H, спуски по 6–7 м помещаются
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
    void gasNextToTieMovesTieDepth() {
        // газ в 3 м от врезки: ни «над» (≈6,5 м спуска), ни «под» (4 м) до врезки на 3,0 м не помещаются
        Variant variant = depth(PlanFixture.trunk().restriction("gas-1", PlanFixture.line(0, 5, 600, 5), "gas_pipeline")
                .oks("1", 300, 80, 20));
        checkProfile(variant);
    }

    private Variant depth(PlanFixture fixture) {
        VariantEnumerator enumerator = new VariantEnumerator(fixture.input(), rules);
        Result flat = enumerator.run();
        Result deep = enumerator.depth(flat);
        assertEquals(flat.getVariants().size(), deep.getVariants().size());
        return deep.getVariants().get(0);
    }

    /** Глубина у каждого участка, уклон ≤ 0,10, ≥ 0,7 м, одна глубина в узле, без перехода через 3,0 м внутри. */
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
