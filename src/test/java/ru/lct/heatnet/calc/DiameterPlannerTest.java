package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.rules.Rules;

class DiameterPlannerTest {
    private static final double EPS = 1e-9;
    private final Rules rules = Rules.load();
    private final DiameterPlanner planner = new DiameterPlanner(rules);

    @Test
    void minimalDiameterAtCapacityBoundary() {
        List<TreeEdge> edge = List.of(new TreeEdge("e", "tie", "oks", 10));
        // DN50 пропускает 3,5 т/ч включительно, DN65 — 8,3 т/ч включительно
        assertEquals(List.of(new SizedPiece("e", 0, 10, 50)), planner.plan(edge, "tie", Map.of("e", 3.5)));
        assertEquals(List.of(new SizedPiece("e", 0, 10, 65)), planner.plan(edge, "tie", Map.of("e", 3.51)));
        assertEquals(List.of(new SizedPiece("e", 0, 10, 65)), planner.plan(edge, "tie", Map.of("e", 8.3)));
        assertEquals(List.of(new SizedPiece("e", 0, 10, 80)), planner.plan(edge, "tie", Map.of("e", 8.31)));
    }

    @Test
    void singleLineWithinLimitIsNotCut() {
        List<TreeEdge> edges = List.of(new TreeEdge("e", "tie", "oks", 150));
        List<SizedPiece> pieces = planner.plan(edges, "tie", Map.of("e", 2.0));

        // 150 м <= 181 м предела DN50
        assertEquals(List.of(new SizedPiece("e", 0, 150, 50)), pieces);
        assertGuarantees(edges, Map.of("e", 2.0), pieces);
    }

    @Test
    void longLineAlternatesMinimalAndNextDiameter() {
        List<TreeEdge> edges = List.of(new TreeEdge("e", "tie", "oks", 500));
        List<SizedPiece> pieces = planner.plan(edges, "tie", Map.of("e", 3.0));

        // от ОКС вверх: 181 - 0,5 = 180,5 м DN50 (500 - 180,5 = 319,5), 2 м DN65, ещё 180,5 м DN50
        // (317,5 - 180,5 = 137), 2 м DN65, остаток 500 - 2 × 180,5 - 2 × 2 = 135 м DN50
        assertEquals(List.of(
                new SizedPiece("e", 0, 135, 50),
                new SizedPiece("e", 135, 137, 65),
                new SizedPiece("e", 137, 317.5, 50),
                new SizedPiece("e", 317.5, 319.5, 65),
                new SizedPiece("e", 319.5, 500, 50)), pieces);
        assertGuarantees(edges, Map.of("e", 3.0), pieces);
    }

    @Test
    void twoBranchesMeetingInChamberFormOneChain() {
        // tie -t-> ch (6 т/ч, DN65); ch -a-> oksA и ch -b-> oksB по 120 м и 3 т/ч (DN50)
        List<TreeEdge> edges = List.of(
                new TreeEdge("t", "tie", "ch", 50),
                new TreeEdge("a", "ch", "oksA", 120),
                new TreeEdge("b", "ch", "oksB", 120));
        Map<String, Double> flows = Map.of("t", 6.0, "a", 3.0, "b", 3.0);
        List<SizedPiece> pieces = planner.plan(edges, "tie", flows);

        // a и b в камере дают 240 м > 181; у камеры от a остаётся 180,5 - 120 = 60,5 м DN50,
        // затем 2 м DN65 и 120 - 62,5 = 57,5 м DN50
        assertEquals(List.of(
                new SizedPiece("t", 0, 50, 65),
                new SizedPiece("a", 0, 60.5, 50),
                new SizedPiece("a", 60.5, 62.5, 65),
                new SizedPiece("a", 62.5, 120, 50),
                new SizedPiece("b", 0, 120, 50)), pieces);
        assertGuarantees(edges, flows, pieces);
    }

    @Test
    void branchesMergeIntoTrunkOfSameDiameter() {
        // та же развилка, но ствол тоже DN50: 1,5 + 1,5 = 3 т/ч
        List<TreeEdge> edges = List.of(
                new TreeEdge("t", "tie", "ch", 50),
                new TreeEdge("a", "ch", "oksA", 120),
                new TreeEdge("b", "ch", "oksB", 120));
        Map<String, Double> flows = Map.of("t", 3.0, "a", 1.5, "b", 1.5);
        List<SizedPiece> pieces = planner.plan(edges, "tie", flows);

        // в камере цепочка 60,5 + 120 = 180,5 м, поэтому ствол сразу у камеры получает 2 м DN65,
        // выше 50 - 2 = 48 м DN50
        assertEquals(List.of(
                new SizedPiece("t", 0, 48, 50),
                new SizedPiece("t", 48, 50, 65),
                new SizedPiece("a", 0, 60.5, 50),
                new SizedPiece("a", 60.5, 62.5, 65),
                new SizedPiece("a", 62.5, 120, 50),
                new SizedPiece("b", 0, 120, 50)), pieces);
        assertGuarantees(edges, flows, pieces);
    }

    @Test
    void shortTrunkAboveMergeShortensBranches() {
        // ствол 1,5 м DN50 над развилкой: разрезать его нельзя, поэтому ветки у камеры укорачиваются
        // до 181 - 0,5 - 1,5 = 179 м вместе
        List<TreeEdge> edges = List.of(
                new TreeEdge("t", "tie", "ch", 1.5),
                new TreeEdge("a", "ch", "oksA", 120),
                new TreeEdge("b", "ch", "oksB", 120));
        Map<String, Double> flows = Map.of("t", 3.0, "a", 1.5, "b", 1.5);
        List<SizedPiece> pieces = planner.plan(edges, "tie", flows);

        // сначала от a у камеры остаётся 60,5 м (60,5 + 120 = 180,5), со стволом 182 > 181;
        // затем b ограничивается 179 - 60,5 = 118,5 м: кусок DN65 [118, 120], цепочка 1,5 + 60,5 + 118 = 180 м
        assertEquals(List.of(
                new SizedPiece("t", 0, 1.5, 50),
                new SizedPiece("a", 0, 60.5, 50),
                new SizedPiece("a", 60.5, 62.5, 65),
                new SizedPiece("a", 62.5, 120, 50),
                new SizedPiece("b", 0, 118, 50),
                new SizedPiece("b", 118, 120, 65)), pieces);
        assertGuarantees(edges, flows, pieces);
    }

    @Test
    void cutMovesEarlierOutOfNoCutInterval() {
        List<TreeEdge> edges = List.of(new TreeEdge("e", "tie", "oks", 500));
        Map<String, List<double[]>> noCut = Map.of("e", List.of(new double[] {318, 322}));
        List<SizedPiece> pieces = planner.plan(edges, "tie", Map.of("e", 3.0), noCut);

        // разрез на 319,5 попал бы в [318, 322]; кусок DN65 встаёт на [322, 324], цепочка снизу
        // 500 - 324 = 176 м; дальше 322 - 180,5 = 141,5, кусок [139,5, 141,5], остаток 139,5 м
        assertEquals(List.of(
                new SizedPiece("e", 0, 139.5, 50),
                new SizedPiece("e", 139.5, 141.5, 65),
                new SizedPiece("e", 141.5, 322, 50),
                new SizedPiece("e", 322, 324, 65),
                new SizedPiece("e", 324, 500, 50)), pieces);
        assertGuarantees(edges, Map.of("e", 3.0), pieces);
    }

    @Test
    void noValidCutThrows() {
        List<TreeEdge> edges = List.of(new TreeEdge("e", "tie", "oks", 400));
        Map<String, List<double[]>> noCut = Map.of("e", List.of(new double[] {0, 400}));
        assertThrows(IllegalStateException.class, () -> planner.plan(edges, "tie", Map.of("e", 3.0), noCut));

        List<TreeEdge> shortEdges = new ArrayList<>();
        Map<String, Double> flows = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            shortEdges.add(new TreeEdge("e" + i, "n" + i, "n" + (i + 1), 1.9));
            flows.put("e" + i, 3.0);
        }
        // 100 × 1,9 = 190 м > 181, а в ребро 1,9 м кусок DN65 длиной 2 м не помещается
        assertThrows(IllegalStateException.class, () -> planner.plan(shortEdges, "n0", flows));
    }


    /** Независимая проверка гарантий плана: покрытие рёбер, диаметры, цепочки и обоснованность ступени выше. */
    private void assertGuarantees(List<TreeEdge> edges, Map<String, Double> flows, List<SizedPiece> pieces) {
        Map<String, TreeEdge> edgeById = new HashMap<>();
        for (TreeEdge edge : edges) {
            edgeById.put(edge.getId(), edge);
            double cursor = 0;
            for (SizedPiece piece : pieces) {
                if (piece.getEdgeId().equals(edge.getId())) {
                    assertEquals(cursor, piece.getFromM(), EPS);
                    cursor = piece.getToM();
                }
            }
            assertEquals(edge.getLength(), cursor, EPS);
        }
        int n = pieces.size();
        int[] chain = new int[n];
        for (int i = 0; i < n; i++) {
            chain[i] = i;
        }
        for (int pass = 0; pass < n; pass++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    boolean sameDn = pieces.get(i).getDn() == pieces.get(j).getDn();
                    if (sameDn && touch(edgeById, pieces.get(i), pieces.get(j))) {
                        chain[i] = Math.min(chain[i], chain[j]);
                    }
                }
            }
        }
        double[] chainLength = new double[n];
        for (int i = 0; i < n; i++) {
            chainLength[chain[i]] += CostCalculator.round2(pieces.get(i).getToM() - pieces.get(i).getFromM());
        }
        for (int i = 0; i < n; i++) {
            SizedPiece piece = pieces.get(i);
            int minDn = rules.diameterFor(flows.get(piece.getEdgeId())).getDn();
            assertTrue(piece.getToM() - piece.getFromM() >= 2 - EPS || edgeById.get(piece.getEdgeId()).getLength() < 2);
            assertTrue(chainLength[chain[i]] <= rules.diameter(piece.getDn()).getMaxLengthM(), "цепочка " + piece);
            if (piece.getDn() == minDn) {
                continue;
            }
            assertEquals(rules.nextDiameter(minDn).getDn(), piece.getDn());
            List<Integer> neighbourChains = new ArrayList<>();
            double merged = piece.getToM() - piece.getFromM();
            for (int j = 0; j < n; j++) {
                if (pieces.get(j).getDn() == minDn && touch(edgeById, piece, pieces.get(j))
                        && !neighbourChains.contains(chain[j])) {
                    neighbourChains.add(chain[j]);
                    merged += chainLength[chain[j]];
                }
            }
            assertTrue(merged > rules.diameter(minDn).getMaxLengthM(), "лишняя ступень " + piece);
        }
    }

    private static boolean touch(Map<String, TreeEdge> edgeById, SizedPiece a, SizedPiece b) {
        if (a == b) {
            return false;
        }
        if (a.getEdgeId().equals(b.getEdgeId()) && (a.getToM() == b.getFromM() || b.getToM() == a.getFromM())) {
            return true;
        }
        List<String> aNodes = endNodes(edgeById.get(a.getEdgeId()), a);
        for (String node : endNodes(edgeById.get(b.getEdgeId()), b)) {
            if (aNodes.contains(node)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> endNodes(TreeEdge edge, SizedPiece piece) {
        List<String> nodes = new ArrayList<>();
        if (piece.getFromM() == 0) {
            nodes.add(edge.getFromNode());
        }
        if (piece.getToM() == edge.getLength()) {
            nodes.add(edge.getToNode());
        }
        return nodes;
    }
}
