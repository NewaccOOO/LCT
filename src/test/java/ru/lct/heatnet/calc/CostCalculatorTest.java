package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.model.ChamberReconstruction;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Reconstruction;
import ru.lct.heatnet.model.TieIn;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Rules;

class CostCalculatorTest {
    private static final double EPS = 1e-6;
    private final CostCalculator cost = new CostCalculator(Rules.load());

    @Test
    void segmentCostOrdinaryAndSpecial() {
        // длина сначала округляется до 0,01: 100,004 -> 100,00; 100 × 78 631 × 1
        assertEquals(7_863_100.0, cost.segmentCost(100.004, 65, 1.0), EPS);
        // 120,5 × 74 023 × 1,6 = 14 271 634,4
        assertEquals(14_271_634.4, cost.segmentCost(120.5, 50, 1.6), EPS);
        // 33,333 -> 33,33; 33,33 × 120 275 × 1,05 = 4 008 765,75 × 1,05 = 4 209 204,0375 -> 4 209 204,04
        assertEquals(4_209_204.04, cost.segmentCost(33.333, 200, 1.05), EPS);
    }

    @Test
    void chamberCostCoversAllFourRangesAndTieInCost() {
        assertEquals(3_000_000, cost.chamberCost(50), EPS);
        assertEquals(3_000_000, cost.chamberCost(200), EPS);
        assertEquals(5_000_000, cost.chamberCost(250), EPS);
        assertEquals(5_000_000, cost.chamberCost(500), EPS);
        assertEquals(8_000_000, cost.chamberCost(600), EPS);
        assertEquals(8_000_000, cost.chamberCost(1000), EPS);
        assertEquals(12_000_000, cost.chamberCost(1200), EPS);
        assertEquals(12_000_000, cost.chamberCost(1400), EPS);
        assertEquals(5_000_000, cost.tieInCost(), EPS);
    }

    @Test
    void penaltySumsOverUnconnectedOks() {
        // (100 000 000 + 500 000 × 2,5) + (100 000 000 + 500 000 × 4) = 101 250 000 + 102 000 000
        assertEquals(203_250_000, cost.penalty(List.of(oks("o1", 2.5), oks("o2", 4.0))), EPS);
        assertEquals(0, cost.penalty(List.of()), EPS);
    }

    @Test
    void summarySumsCostsAndComputesScore() {
        List<NewSegment> segments = List.of(
                new NewSegment("s1", "1", null, "t", "c", 3, 50, 120.5, "special", null, null, 14_271_634.4),
                new NewSegment("s2", "1", null, "c", "o", 7, 65, 100, "base", null, null, 7_863_100));
        List<NewChamber> chambers = List.of(new NewChamber("ch", "1", null, 65, 3_000_000));
        List<TieIn> tieIns = List.of(new TieIn("t", "1", null, "n1", "heat_network", 80, 100, 5_000_000));
        List<Reconstruction> recons = List.of(
                new Reconstruction("r", "1", null, "n1", 12, 3, 15, 80, 100, 100, 13_369_400));
        List<ChamberReconstruction> chamberRecons = List.of(
                new ChamberReconstruction("cr", "1", null, "k1", 80, 100, 3_000_000));

        VariantSummary summary = cost.summary("summary_1", "1", segments, chambers, tieIns, recons, chamberRecons,
                List.of(oks("o9", 2.5)));

        assertEquals("summary_1", summary.getId());
        assertEquals("1", summary.getVariantId());
        assertEquals(0, summary.getRank());
        assertEquals(22_134_734.4, summary.getConstructionCost(), EPS); // 14 271 634,4 + 7 863 100
        assertEquals(3_000_000, summary.getChamberConstructionCost(), EPS);
        assertEquals(5_000_000, summary.getTieInCost(), EPS);
        assertEquals(13_369_400, summary.getReconstructionCost(), EPS);
        assertEquals(3_000_000, summary.getChamberReconstructionCost(), EPS);
        assertEquals(101_250_000, summary.getUnconnectedPenalty(), EPS); // 100 000 000 + 500 000 × 2,5
        // 22 134 734,4 + 3 000 000 + 5 000 000 + 13 369 400 + 3 000 000 + 101 250 000
        assertEquals(147_754_134.4, summary.getCalculatedCost(), EPS);
        assertEquals(220.5, summary.getNewNetworkLength(), EPS);
        assertEquals(100, summary.getReconstructionLength(), EPS);
        assertEquals(320.5, summary.getLength(), EPS);
        // 0,7 × 147 754 134,4 / 25 000 000 + 0,3 × 320,5 / 100 = 4,1371157632 + 0,9615 = 5,0986… -> 5,099
        assertEquals(5.099, summary.getScore(), EPS);
        assertEquals(List.of("o9"), summary.getUnconnectedOksIds());
    }

    @Test
    void rankOrdersByScoreThenCost() {
        List<VariantSummary> summaries = List.of(summary(2.0, 10), summary(1.0, 50), summary(1.0, 30));

        // score 1,0 у второго и третьего, дешевле третий
        assertEquals(List.of(2, 1, 0), Scorer.rank(summaries));
    }

    private static VariantSummary summary(double score, double calculatedCost) {
        return new VariantSummary("s", "v", 0, 0, 0, 0, 0, 0, 0, calculatedCost, 0, 0, 0, score, List.of());
    }

    private static FutureOks oks(String id, double flow) {
        return new FutureOks(id, null, flow, null);
    }
}
