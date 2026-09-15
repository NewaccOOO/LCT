package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Source;
import ru.lct.heatnet.rules.Rules;

/**
 * Сеть вдоль оси X: источник S в (0, 0); s3 от 0 до 100 м (DN80, 12 т/ч, к S); камера ch1 в 100 м (DN80, к s3);
 * s2 от 100 до 300 м (DN80, 12 т/ч, к ch1); s1 от 300 до 400 м (DN65, 5 т/ч, к s2). DN80 пропускает 13,2 т/ч.
 */
class ReconstructionCalculatorTest {
    private static final double EPS = 1e-9;
    private static final double RECON_DN100 = 133694;
    private static final GeometryFactory GF = new GeometryFactory();
    private final Rules rules = Rules.load();

    @Test
    void tieInInsideSecondSegmentReconstructsPartToSourceAndWholeThird() {
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules,
                List.of(pipe("k", "s2", 200, 4.0)));

        // 12 + 4 = 16 т/ч > 13,2 -> DN100; s1 ниже врезки не меняется
        assertEquals(2, result.getParts().size());
        ReconPart s2 = result.getParts().get(1);
        assertEquals("s2", s2.getExistingObjectId());
        assertEquals(line(100, 200), s2.getGeometry());
        assertEquals(4.0, s2.getAddedFlowTph(), EPS);
        assertEquals(16.0, s2.getCalculatedFlowTph(), EPS);
        assertEquals(80, s2.getExistingDiameter());
        assertEquals(100, s2.getRequiredDiameter());
        assertEquals(100.0, s2.getLength(), EPS);
        assertEquals(100 * RECON_DN100, s2.getCost(), EPS); // 13 369 400
        ReconPart s3 = result.getParts().get(0);
        assertEquals("s3", s3.getExistingObjectId());
        assertEquals(line(0, 100), s3.getGeometry());
        assertEquals(100 * RECON_DN100, s3.getCost(), EPS);
        assertEquals(Map.of("k", 100), result.getRequiredDiameterByTieIn());
        assertEquals(Map.of("k", 80), result.getExistingDiameterByTieIn());
        assertEquals(65, result.diameterAfter("s1"));
        assertEquals(100, result.diameterAfter("s2"));
    }

    @Test
    void tieInsSumOnSharedSegment() {
        // по отдельности 12 + 0,7 = 12,7 <= 13,2, вместе на s3 12 + 1,4 = 13,4 > 13,2
        assertTrue(ReconstructionCalculator.calculate(network(false), rules, List.of(pipe("a", "s1", 350, 0.7)))
                .getParts().isEmpty());
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules,
                List.of(pipe("a", "s1", 350, 0.7), pipe("b", "s2", 200, 0.7)));

        assertEquals(2, result.getParts().size());
        ReconPart s3 = result.getParts().get(0);
        assertEquals("s3", s3.getExistingObjectId());
        assertEquals(0.7 + 0.7, s3.getAddedFlowTph(), EPS);
        assertEquals(100, s3.getRequiredDiameter());
        ReconPart s2 = result.getParts().get(1);
        assertEquals(line(100, 200), s2.getGeometry());
        assertEquals(1.4, s2.getAddedFlowTph(), EPS);
        assertEquals(Map.of("a", 65, "b", 100), result.getRequiredDiameterByTieIn());
    }

    @Test
    void twoTieInsInsideOneSegmentGiveSeparateParts() {
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules, twoTieInsInS2());

        // s2 от ch1: [100, 150] несёт 1,5 + 2 = 3,5 т/ч (15,5 -> DN100), [150, 250] только 1,5 (13,5 -> DN100)
        assertEquals(3, result.getParts().size());
        ReconPart near = result.getParts().get(1);
        assertEquals(line(100, 150), near.getGeometry());
        assertEquals(3.5, near.getAddedFlowTph(), EPS);
        assertEquals(50.0, near.getLength(), EPS);
        assertEquals(50 * RECON_DN100, near.getCost(), EPS); // 6 684 700
        ReconPart far = result.getParts().get(2);
        assertEquals(line(150, 250), far.getGeometry());
        assertEquals(1.5, far.getAddedFlowTph(), EPS);
        assertEquals(100 * RECON_DN100, far.getCost(), EPS);
        assertEquals(3.5, result.getParts().get(0).getAddedFlowTph(), EPS);
        assertEquals(Map.of("far", 100, "near", 100), result.getRequiredDiameterByTieIn());
    }

    @Test
    void addedFlowWithoutDiameterChangeGivesNoPart() {
        // 12 + 1 = 13 <= 13,2, DN80 остаётся
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules,
                List.of(pipe("k", "s2", 200, 1.0)));

        assertTrue(result.getParts().isEmpty());
        assertEquals(Map.of("k", 80), result.getRequiredDiameterByTieIn());
        assertEquals(80, result.diameterAfter("s3"));
    }

    @Test
    void lineOrientationDoesNotChangeResult() {
        List<ReconPart> forward = ReconstructionCalculator.calculate(network(false), rules, twoTieInsInS2()).getParts();
        List<ReconPart> reversed = ReconstructionCalculator.calculate(network(true), rules, twoTieInsInS2()).getParts();

        assertEquals(forward, reversed);
    }

    @Test
    void chamberWithTwoTieInsIsReconstructedOnce() {
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules,
                List.of(chamberTieIn("k1", 1.5), chamberTieIn("k2", 2.0)));

        // от ch1 к источнику только s3: 12 + 3,5 = 15,5 -> DN100; у ch1 новые DN65, s3 DN100, s2 DN80
        assertEquals(1, result.getParts().size());
        assertEquals(100, result.chamberRequiredDiameter("ch1", 65));
        List<ChamberRecon> chambers = result.chamberReconstructions(Map.of("ch1", 65));
        assertEquals(List.of(new ChamberRecon("ch1", point(100), 80, 100, 3_000_000)), chambers);
        assertEquals(Map.of("k1", 80, "k2", 80), result.getExistingDiameterByTieIn());
        assertFalse(result.getRequiredDiameterByTieIn().containsKey("k1"));
    }

    @Test
    void chamberWithoutDiameterGrowthIsNotReconstructed() {
        // 12 + 1 = 13 <= 13,2: s3 остаётся DN80, новые участки DN50, камера DN80
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules,
                List.of(chamberTieIn("k", 1.0)));

        assertEquals(80, result.chamberRequiredDiameter("ch1", 50));
        assertTrue(result.chamberReconstructions(Map.of("ch1", 50)).isEmpty());
    }

    @Test
    void chamberIgnoresReconstructedPartFarFromIt() {
        // s3: часть 0–10 м получает 12 + 1 + 4 = 17 т/ч -> DN100; часть 10–100 м у ch1 получает 13 т/ч -> DN80
        ReconstructionResult result = ReconstructionCalculator.calculate(network(false), rules,
                List.of(chamberTieIn("k", 1.0), pipe("far", "s3", 10, 4.0)));

        assertEquals(100, result.diameterAfter("s3"));
        assertEquals(80, result.chamberRequiredDiameter("ch1", 50));
        assertTrue(result.chamberReconstructions(Map.of("ch1", 50)).isEmpty());
    }

    private static List<TieInLoad> twoTieInsInS2() {
        return List.of(pipe("far", "s2", 250, 1.5), pipe("near", "s2", 150, 2.0));
    }

    private static InputData network(boolean reversed) {
        List<NetworkSegment> segments = List.of(
                new NetworkSegment("s3", orient(line(0, 100), reversed), 80, 12, "S"),
                new NetworkSegment("s2", orient(line(100, 300), reversed), 80, 12, "ch1"),
                new NetworkSegment("s1", orient(line(300, 400), reversed), 65, 5, "s2"));
        List<Chamber> chambers = List.of(new Chamber("ch1", point(100), 80, "s3"));
        return new InputData(new Source("S", point(0)), segments, chambers, List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    private static TieInLoad pipe(String key, String segmentId, double x, double flow) {
        return new TieInLoad(key, segmentId, "heat_network", point(x), flow);
    }

    private static TieInLoad chamberTieIn(String key, double flow) {
        return new TieInLoad(key, "ch1", "heat_chamber", point(100), flow);
    }

    private static LineString line(double fromX, double toX) {
        return GF.createLineString(new Coordinate[] {new Coordinate(fromX, 0), new Coordinate(toX, 0)});
    }

    private static LineString orient(LineString line, boolean reversed) {
        return reversed ? line.reverse() : line;
    }

    private static Point point(double x) {
        return GF.createPoint(new Coordinate(x, 0));
    }
}
