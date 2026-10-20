package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.plan.PlanFixture.GEOMETRY;
import static ru.lct.heatnet.plan.PlanFixture.point;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.calc.CostCalculator;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class VariantEnumeratorTest {
    private final Rules rules = Rules.load();

    @Test
    void variantsAreRankedAndDifferByRoute() {
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
                        + " совпадают по трассе");
            }
        }
    }

    @Test
    void twoRunsGiveIdenticalResult() {
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 100, 150, 5).oks("o-2", 500, 150, 5).oks("o-3", 300, 120, 8);

        Result first = new VariantEnumerator(fixture.input(), rules).run();
        Result second = new VariantEnumerator(fixture.input(), rules).run();

        assertEquals(first.getVariants().size(), second.getVariants().size());
        for (int i = 0; i < first.getVariants().size(); i++) {
            assertEquals(first.getVariants().get(i).getSummary().getCalculatedCost(),
                    second.getVariants().get(i).getSummary().getCalculatedCost(), 1e-9);
            assertEquals(first.getVariants().get(i).getSummary().getNewNetworkLength(),
                    second.getVariants().get(i).getSummary().getNewNetworkLength(), 1e-9);
        }
    }

    @Test
    void groupOverLargestCapacityIsSplitInsteadOfFailing() {
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 100, 150, 9000).oks("o-2", 160, 150, 9000).oks("o-3", 220, 150, 9000);

        List<Variant> variants = new VariantEnumerator(fixture.input(), rules).run().getVariants();

        assertFalse(variants.isEmpty());
        assertTrue(variants.get(0).getSummary().getUnconnectedOksIds().isEmpty());
    }

    @Test
    void tieShiftedAlongSamePipeIsNotSecondVariant() {
        PlanFixture fixture = new PlanFixture().pipe("hn-1", 0, 0, 400, 0, 200, 100, "src").oks("o-1", 200, 60, 30);

        List<Variant> variants = new VariantEnumerator(fixture.input(), rules).run().getVariants();

        assertEquals(1, variants.size(), "вариантов " + variants.size());
        assertTrue(variants.get(0).getSummary().getUnconnectedOksIds().isEmpty());
    }

    @Test
    void cityTieShiftedAlongSamePipeIsNotSecondVariant() {
        PlanFixture fixture = new PlanFixture().pipe("hn-1", 0, 0, 400, 0, 200, 100, "src").oks("o-1", 200, 60, 30);
        fixture.existing.add(new ExistingOks("wall", PlanFixture.rect(170, 25, 230, 35)));

        List<Variant> variants = new VariantEnumerator(fixture.input(), rules).city().getVariants();

        assertEquals(1, variants.size(), "вариантов " + variants.size());
        assertEquals("1", variants.get(0).getId());
        assertTrue(variants.get(0).getSummary().getUnconnectedOksIds().isEmpty());
    }

    @Test
    void variantWithMoreUnconnectedThanBestIsNotIssued() {
        InputData input = VariantEnumerator.read(Path.of("data/samples/expert/e4-combined.geojson"), rules);

        List<Variant> variants = new VariantEnumerator(input, rules).run().getVariants();

        int best = variants.get(0).getSummary().getUnconnectedOksIds().size();
        for (Variant variant : variants) {
            assertEquals(best, variant.getSummary().getUnconnectedOksIds().size(), "вариант " + variant.getId());
        }
    }

    @Test
    void bestVariantTakesCheaperTreeOfAnotherVariant() {
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 100, 60, 5).oks("o-2", 500, 60, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        Tree near = tree(finder, fixture, "o-1", 0, 0);
        Tree far = tree(finder, fixture, "o-1", -60, 50);
        Tree bent = tree(finder, fixture, "o-2", 40, 30);
        Tree straight = tree(finder, fixture, "o-2", 0, 0);
        VariantEnumerator enumerator = new VariantEnumerator(input, rules);
        enumerator.run();
        NetworkAssembler assembler = new NetworkAssembler(input, rules, new SpecialObjects(input, rules),
                input.getFutureOks().stream().collect(Collectors.toMap(FutureOks::getId, oks -> oks)));
        java.util.function.Function<List<Tree>, Variant> assemble = trees -> assembler.assemble("0", 0, trees, List.of());
        List<List<Tree>> variants = List.of(List.of(near, bent), List.of(far, straight));
        double[] scores = variants.stream().map(assemble).mapToDouble(v -> rules.score(v.getSummary().getCalculatedCost(),
                v.getSummary().getNewNetworkLength())).toArray();

        List<List<Tree>> result = enumerator.recombined(variants, scores, assemble);

        assertTrue(result.get(0).contains(near) && result.get(0).contains(straight) && result.get(0).size() == 2,
                "вариант 1: " + result.get(0));
        assertTrue(result.get(1) == variants.get(1), "вариант 2 не меняется");
    }

    private static Tree tree(TieInFinder finder, PlanFixture fixture, String oksId, double dx, double dy) {
        ConnectionPoint connection = fixture.connection(oksId);
        Coordinate at = connection.getGeometry().getCoordinate();
        TieCandidate tie = finder.find(List.of(connection.getGeometry()), 100).get(0);
        Coordinate root = tie.getPoint().getCoordinate();
        Tree tree = new Tree(tie);
        List<Coordinate> line = new ArrayList<>(List.of(root, new Coordinate(root.x + Math.signum(at.x - root.x) / 2, root.y + 3)));
        if (dx != 0 || dy != 0) {
            line.add(new Coordinate(at.x + dx, root.y + dy / 2));
            line.add(new Coordinate(at.x + dx, at.y - 10));
        }
        line.add(at);
        tree.edges.add(new Tree.Edge(tree.root, Tree.Node.connection(connection), GEOMETRY.createLineString(line.toArray(new Coordinate[0]))));
        return tree;
    }

    @Test
    void chamberAtPipeEndAndPipeGiveTwoDifferentVariants() {
        PlanFixture fixture = new PlanFixture().pipe("hn-1", 0, 0, 300, 0, 150, 50, "src")
                .chamber("hc-1", 300, 0, 150, "hn-1").oks("o-1", 305, 80, 10);

        List<Variant> variants = new VariantEnumerator(fixture.input(), rules).run().getVariants();

        assertTrue(variants.size() >= 2, "вариантов " + variants.size());
        assertTrue(differ(variants.get(0), variants.get(1)), "варианты 1 и 2 не различаются");
        assertTrue(variants.stream().allMatch(v -> v.getSummary().getUnconnectedOksIds().isEmpty()));
    }

    @Test
    void secondVariantWhenAlternativeTieInsOnlySwapChambers() {
        List<Variant> variants;
        try {
            System.setProperty(VariantEnumerator.BALLAST_PROPERTY, "100");
            variants = new VariantEnumerator(swappedChambers().input(), rules).run().getVariants();
        } finally {
            System.clearProperty(VariantEnumerator.BALLAST_PROPERTY);
        }

        assertTrue(variants.size() >= 2, "вариантов " + variants.size());
        assertTrue(differ(variants.get(0), variants.get(1)), "варианты 1 и 2 не различаются");
        assertTrue(variants.stream().allMatch(v -> v.getSummary().getUnconnectedOksIds().isEmpty()));
    }

    @Test
    void variantsMuchCostlierThanBestAreNotShown() {
        List<Variant> variants = new VariantEnumerator(swappedChambers().input(), rules).run().getVariants();

        assertFalse(variants.isEmpty());
        double best = variants.get(0).getSummary().getScore();
        for (Variant variant : variants) {
            assertTrue(variant.getSummary().getScore() <= 1.3 * best, "вариант " + variant.getId() + ": S "
                    + variant.getSummary().getScore() + " при лучшем " + best);
        }
    }

    private static PlanFixture swappedChambers() {
        return new PlanFixture().pipe("hn-1", 0, 0, 400, 0, 200, 60, "src")
                .chamber("hc-1", 400, 0, 200, "hn-1").pipe("hn-2", 400, 0, 1000, 0, 150, 30, "hc-1")
                .chamber("hc-2", 1000, 0, 150, "hn-2").oks("o-1", 405, 60, 8).oks("o-2", 1000, -90, 6);
    }

    @Test
    void uncutPipeThroughChamberLeavesRoomForTwoNewSegments() {
        PlanFixture fixture = new PlanFixture().pipe("hn-1", 190, 0, 210, 0, 200, 60, "src")
                .chamber("hc-1", 200, 0, 200, "hn-1")
                .oks("o-1", 200, 200, 5).oks("o-2", -150, 150, 5).oks("o-3", 550, 150, 5);

        List<Variant> variants = new VariantEnumerator(fixture.input(), rules).run().getVariants();

        assertFalse(variants.isEmpty());
        for (Variant variant : variants) {
            long atChamber = variant.getSegments().stream()
                    .filter(s -> s.getStartNodeId().equals("hc-1") || s.getEndNodeId().equals("hc-1")).count();
            assertTrue(atChamber <= 2, "новых участков у hc-1 в варианте " + variant.getId() + ": " + atChamber);
        }
    }

    @Test
    void oksWithoutRouteIsUnconnectedWithPenaltyAndOtherOksKept() {
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
    void gapWideEnoughForOwnDiameterIsUsedInsteadOfLongDetour() {
        int dn = 400;
        double offset = rules.restriction("oks_existing").clearanceM(dn) + rules.diameter(dn).getWidthM() / 2;
        double gap = 2 * offset + 2;
        PlanFixture fixture = new PlanFixture()
                .pipe("hn-1", 0, 0, 400, 0, 600, 100, "src").chamber("hc-1", 400, 0, 600, "hn-1")
                .pipe("hn-2", 400, 0, 800, 0, 600, 100, "hc-1")
                .oks("o-1", 200, 130, 690);
        Geometry wall = PlanFixture.rect(40, 40, 200 - gap / 2, 55).union(PlanFixture.rect(200 + gap / 2, 40, 360, 55));
        fixture.existing.add(new ExistingOks("wall", wall));

        Variant best = new VariantEnumerator(fixture.input(), rules).run().getVariants().get(0);

        assertTrue(best.getSummary().getUnconnectedOksIds().isEmpty());
        assertTrue(best.getSummary().getNewNetworkLength() < 131, "длина " + best.getSummary().getNewNetworkLength());
        for (NewSegment segment : best.getSegments()) {
            double required = rules.restriction("oks_existing").clearanceM(segment.getDiameter())
                    + rules.diameter(segment.getDiameter()).getWidthM() / 2;
            assertTrue(segment.getGeometry().distance(wall) >= required, "участок " + segment.getId() + " ближе отступа");
        }
    }

    @Test
    void outputIdsNeverRepeatInputIds() {
        PlanFixture fixture = new PlanFixture().pipe("v1_seg_1", 0, 0, 500, 0, 150, 40, "src")
                .chamber("v2_ch_1", 500, 0, 150, "v1_seg_1").oks("v1_tie_1", 250, 90, 10)
                .restriction("summary_1", PlanFixture.rect(900, 900, 910, 910), "park");
        Set<String> inputIds = Set.of("src", "v1_seg_1", "v2_ch_1", "v1_tie_1", "cp-v1_tie_1", "summary_1");

        List<String> outputIds = new ArrayList<>();
        for (Variant variant : new VariantEnumerator(fixture.input(), rules).run().getVariants()) {
            variant.getSegments().forEach(o -> outputIds.add(o.getId()));
            variant.getChambers().forEach(o -> outputIds.add(o.getId()));
            variant.getNodes().forEach(o -> outputIds.add(o.getId()));
            outputIds.add(variant.getSummary().getId());
        }

        assertFalse(outputIds.isEmpty());
        assertEquals(outputIds.size(), new HashSet<>(outputIds).size(), "выходные ID повторяются: " + outputIds);
        assertTrue(outputIds.stream().noneMatch(inputIds::contains), "выходной ID совпал с входным: " + outputIds);
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

    @Test
    void pointLeavesByNextSideWhenNearSideLeadsNowhere() {
        Geometry building = PlanFixture.rect(140, 40, 180, 80);
        PlanFixture fixture = PlanFixture.trunk();
        fixture.oks.add(new FutureOks("o-1", building, 5, 1.0));
        fixture.connections.add(new ConnectionPoint("cp-o-1", point(160, 78), "o-1"));
        fixture.existing.add(new ExistingOks("b-1", building));
        fixture.restriction("rw-1", GEOMETRY.createMultiPolygon(new Polygon[] {(Polygon) PlanFixture.rect(130, 50, 142, 110),
                (Polygon) PlanFixture.rect(130, 100, 190, 110), (Polygon) PlanFixture.rect(178, 50, 190, 110)}), "railway");

        Variant best = new VariantEnumerator(fixture.input(), rules).run().getVariants().get(0);

        assertTrue(best.getSummary().getUnconnectedOksIds().isEmpty(), "без сети: " + best.getSummary().getUnconnectedOksIds());
        NewSegment last = best.getSegments().stream().filter(s -> s.getEndNodeId().equals("cp-o-1")).findFirst().orElseThrow();
        Coordinate[] c = last.getGeometry().getCoordinates();
        assertTrue(c[c.length - 2].y < 40, "финальный участок через южную сторону: " + last.getGeometry());
    }

    private static boolean differ(Variant a, Variant b) {
        Geometry lineA = lines(a);
        Geometry lineB = lines(b);
        double inside = Math.min(lineA.intersection(lineB.buffer(10)).getLength(), lineB.intersection(lineA.buffer(10)).getLength());
        return inside <= 0.9 * Math.min(lineA.getLength(), lineB.getLength());
    }

    private static Geometry lines(Variant variant) {
        return GEOMETRY.buildGeometry(variant.getSegments().stream().map(NewSegment::getGeometry).collect(Collectors.toList()));
    }

    private static LinearRing ring(double x1, double y1, double x2, double y2) {
        Geometry rect = PlanFixture.rect(x1, y1, x2, y2);
        return GEOMETRY.createLinearRing(((Polygon) rect).getExteriorRing().getCoordinates());
    }
}
