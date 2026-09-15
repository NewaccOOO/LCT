package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.plan.PlanFixture.GEOMETRY;
import static ru.lct.heatnet.plan.PlanFixture.point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.calc.CostCalculator;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.TieIn;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class VariantEnumeratorTest {
    /** Как в правиле variants валидатора: врезка дальше этого от всех врезок другого варианта делает их разными. */
    private static final double OTHER_TIE_M = 20;

    private final Rules rules = Rules.load();

    @Test
    void variantsAreRankedAndDifferByTieInsOrPartition() {
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 100, 150, 5).oks("o-2", 500, 150, 5);

        Result result = new VariantEnumerator(fixture.input(), rules).run();

        List<Variant> variants = result.getVariants();
        assertTrue(variants.size() >= 2 && variants.size() <= 3, "вариантов " + variants.size());
        for (int i = 0; i < variants.size(); i++) {
            Variant variant = variants.get(i);
            assertEquals(String.valueOf(i + 1), variant.getId());
            assertEquals(i + 1, variant.getSummary().getRank());
            assertTrue(variant.getSummary().getUnconnectedOksIds().isEmpty());
            if (i > 0) {
                assertTrue(variants.get(i - 1).getSummary().getScore() <= variant.getSummary().getScore());
            }
            for (int j = 0; j < i; j++) {
                assertTrue(differ(variants.get(j), variant), "варианты " + variants.get(j).getId() + " и " + variant.getId()
                        + " не различаются врезками и разбиением");
            }
        }
    }

    @Test
    void oksWithoutRouteIsUnconnectedWithPenaltyAndOtherOksKept() {
        // o-2 внутри кольца водоёма: выхода к сети нет ни при каком диаметре
        Polygon water = GEOMETRY.createPolygon(ring(420, 170, 580, 330), new LinearRing[] {ring(470, 220, 530, 280)});
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 100, 150, 5).oks("o-2", 500, 240, 7)
                .restriction("water-1", water, "water");

        Result result = new VariantEnumerator(fixture.input(), rules).run();

        assertFalse(result.getVariants().isEmpty());
        for (Variant variant : result.getVariants()) {
            assertEquals(List.of("o-2"), variant.getSummary().getUnconnectedOksIds());
            assertEquals(CostCalculator.round2(rules.penalty(7)), variant.getSummary().getUnconnectedPenalty(), 1e-6);
            assertTrue(variant.getSegments().stream().anyMatch(s -> s.getEndNodeId().equals("cp-o-1")),
                    "o-1 подключён в варианте " + variant.getId());
            for (NewSegment segment : variant.getSegments()) {
                assertFalse(segment.getEndNodeId().equals("cp-o-2") || segment.getGeometry().intersects(water));
            }
        }
    }

    @Test
    void kMeansSplitsTwoRemoteClusters() {
        List<ConnectionPoint> connections = List.of(
                new ConnectionPoint("a", point(0, 0), "a"), new ConnectionPoint("b", point(10, 0), "b"),
                new ConnectionPoint("c", point(500, 0), "c"), new ConnectionPoint("d", point(510, 5), "d"),
                new ConnectionPoint("e", point(505, -5), "e"));

        List<List<ConnectionPoint>> parts = VariantEnumerator.kMeans(connections);

        assertEquals(2, parts.size());
        Set<Set<String>> ids = new HashSet<>();
        for (List<ConnectionPoint> part : parts) {
            Set<String> set = new HashSet<>();
            part.forEach(c -> set.add(c.getId()));
            ids.add(set);
        }
        assertEquals(Set.of(Set.of("a", "b"), Set.of("c", "d", "e")), ids);
    }

    private static boolean differ(Variant a, Variant b) {
        Set<String> idsA = new HashSet<>();
        Set<String> idsB = new HashSet<>();
        a.getTieIns().forEach(t -> idsA.add(t.getExistingObjectId()));
        b.getTieIns().forEach(t -> idsB.add(t.getExistingObjectId()));
        return !idsA.equals(idsB) || farTie(a, b) || farTie(b, a) || !partition(a).equals(partition(b));
    }

    private static boolean farTie(Variant mine, Variant other) {
        for (TieIn tie : mine.getTieIns()) {
            if (other.getTieIns().stream().allMatch(t -> t.getGeometry().distance(tie.getGeometry()) > OTHER_TIE_M)) {
                return true;
            }
        }
        return false;
    }

    /** Точки подключения, достижимые от каждой врезки. */
    private static Set<Set<String>> partition(Variant variant) {
        Map<String, List<NewSegment>> outgoing = new HashMap<>();
        variant.getSegments().forEach(s -> outgoing.computeIfAbsent(s.getStartNodeId(), k -> new ArrayList<>()).add(s));
        Set<Set<String>> result = new HashSet<>();
        for (TieIn tie : variant.getTieIns()) {
            Set<String> reached = new HashSet<>();
            List<String> queue = new ArrayList<>(List.of(tie.getId()));
            for (int i = 0; i < queue.size(); i++) {
                for (NewSegment segment : outgoing.getOrDefault(queue.get(i), List.of())) {
                    queue.add(segment.getEndNodeId());
                    if (segment.getEndNodeId().startsWith("cp-")) {
                        reached.add(segment.getEndNodeId());
                    }
                }
            }
            result.add(reached);
        }
        return result;
    }

    private static LinearRing ring(double x1, double y1, double x2, double y2) {
        Geometry rect = PlanFixture.rect(x1, y1, x2, y2);
        return GEOMETRY.createLinearRing(((Polygon) rect).getExteriorRing().getCoordinates());
    }
}
