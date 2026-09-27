package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.plan.PlanFixture.point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.rules.Rules;

class TieInFinderTest {
    private static final int DN = 100;

    private final Rules rules = Rules.load();

    @Test
    void projectionWithinTenMetresOfChamberWithRoomBecomesChamberTieIn() {
        // hc-1 в (200, 0) с двумя участками; проекция на hn-2 в (205, 0) в 5 м от неё
        TieInFinder finder = new TieInFinder(PlanFixture.trunk().input(), rules);

        List<TieCandidate> candidates = finder.find(List.of(point(205, 40)), DN);

        TieCandidate chamber = candidates.stream().filter(TieCandidate::isChamber).findFirst().orElseThrow();
        assertEquals("hc-1", chamber.getExistingObjectId());
        assertEquals(rules.chamberRule().getMaxSegments() - 2, chamber.getCapacity());
        assertTrue(chamber.getIgnored().containsAll(List.of("hn-1", "hn-2")));
        double maxDist = rules.chamberRule().getMaxDistM();
        assertTrue(candidates.stream().noneMatch(c -> !c.isChamber() && c.getPoint().distance(point(200, 0)) <= maxDist),
                "врезка в трубу рядом с камерой, где есть место: " + candidates);
    }

    @Test
    void chamberTieAlsoGetsNewChamberJustBeyondMaxDistOnItsPipes() {
        // проекция (205, 40) на hn-2 в 5 м от hc-1 отдаётся камере; на трубах камеры пробуется и новая камера за
        // max_dist_m от неё: п. 2.4 обязывает врезаться в камеру только ближе, а новая камера дешевле врезки
        TieInFinder finder = new TieInFinder(PlanFixture.trunk().input(), rules);
        TieCandidate chamber = finder.find(List.of(point(205, 40)), DN).stream().filter(TieCandidate::isChamber)
                .findFirst().orElseThrow();

        List<TieCandidate> own = finder.ownPipes(chamber, point(205, 40), DN);

        double beyond = rules.chamberRule().getMaxDistM() + 0.2;
        assertTrue(own.stream().anyMatch(c -> !c.isChamber() && c.getExistingObjectId().equals("hn-2")
                && c.getPoint().distance(point(200 + beyond, 0)) < 1e-6), "новая камера на hn-2: " + own);
    }

    @Test
    void chamberWithFourSegmentsKeepsPipeTieInNearIt() {
        PlanFixture fixture = PlanFixture.trunk()
                .pipe("hn-4", 200, 0, 200, 150, DN, 10, "hc-1")
                .pipe("hn-5", 200, 0, 200, -150, DN, 10, "hc-1");
        TieInFinder finder = new TieInFinder(fixture.input(), rules);

        List<TieCandidate> candidates = finder.find(List.of(point(206, 40)), DN);

        assertTrue(candidates.stream().noneMatch(TieCandidate::isChamber), "камера hc-1 уже с четырьмя участками");
        TieCandidate pipe = candidates.stream().filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        assertEquals(TieCandidate.HEAT_NETWORK, pipe.getExistingObjectType());
        assertEquals(206, pipe.getPoint().getX(), 1e-6);
        assertEquals(0, pipe.getPoint().getY(), 1e-6);
        assertEquals(2, pipe.getCapacity());
        assertEquals(List.of("hn-2"), List.copyOf(pipe.getIgnored()));
    }

    @Test
    void pointAtPipeGetsTieInFartherThanMetre() {
        // точка в 1 м от hn-3 (400..600, 0): врезка по перпендикуляру дала бы отрезок короче метра с округлением
        TieInFinder finder = new TieInFinder(PlanFixture.trunk().input(), rules);

        TieCandidate pipe = finder.find(List.of(point(450, 1)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-3")).findFirst().orElseThrow();

        assertEquals(0, pipe.getPoint().getY(), 1e-6);
        assertEquals(1.2, pipe.getPoint().distance(point(450, 1)), 1e-6);
    }

    @Test
    void amongChambersWithinTenMetresNearestWins() {
        TieInFinder finder = new TieInFinder(PlanFixture.trunk().chamber("hc-2", 206, 0, 200, "hn-1").input(), rules);

        assertEquals("hc-2", chamberNearTwoHundredNine(finder).getExistingObjectId());
    }

    /** Кандидат вдоль hn-2 на 91 м от врезки в (300, 0), то есть в (209, 0), где в 10 м две камеры. */
    private TieCandidate chamberNearTwoHundredNine(TieInFinder finder) {
        TieCandidate tie = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> !c.isChamber() && c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        List<TieCandidate> along = finder.along(tie, DN, 90);
        return along.stream().filter(TieCandidate::isChamber).findFirst().orElseThrow();
    }

    @Test
    void projectionFarFromChambersIsPipeTieInAndNotAtPipeEnd() {
        TieInFinder finder = new TieInFinder(PlanFixture.trunk().input(), rules);

        List<TieCandidate> candidates = finder.find(List.of(point(300, 60), point(700, 30)), DN);

        assertTrue(candidates.stream().anyMatch(c -> c.getExistingObjectId().equals("hn-2")
                && c.getPoint().distance(point(300, 0)) < 1e-6), "нет проекции на hn-2: " + candidates);
        TieCandidate end = candidates.stream()
                .filter(c -> c.getExistingObjectId().equals("hn-3") && c.getPoint().getX() > 590).findFirst().orElseThrow();
        assertTrue(end.getPoint().getX() < 600 - 1, "точка врезки у конца участка: " + end.getPoint());
    }

    @Test
    void pipeTieInLeavesRoadBandTowardNearestEdge() {
        // дорога x 295..310 поперёк hn-2, её полоса 3 м — x 292..313; проекция (300, 60) в полосе
        TieInFinder finder = new TieInFinder(PlanFixture.trunk()
                .restriction("road-1", PlanFixture.rect(295, -20, 310, 20), "road").input(), rules);

        TieCandidate pipe = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();

        assertEquals(291.95, pipe.getPoint().getX(), 1e-6);
        assertEquals(0, pipe.getPoint().getY(), 1e-6);
    }

    @Test
    void pipeTieInStaysInBandWithoutRoomOutside() {
        TieInFinder finder = new TieInFinder(PlanFixture.trunk()
                .restriction("road-1", PlanFixture.rect(190, -20, 410, 20), "road").input(), rules);

        TieCandidate pipe = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();

        assertEquals(300, pipe.getPoint().getX(), 1e-6);
    }

    @Test
    void nearestByWindowMatchesFullSort() {
        // решётка даёт равные расстояния, их порядок — порядок входа; точки и линии вперемешку, далеко и близко
        Random random = new Random(11);
        for (int round = 0; round < 200; round++) {
            List<Geometry> items = new ArrayList<>();
            int count = random.nextInt(40);
            for (int i = 0; i < count; i++) {
                double x = random.nextInt(30) * 50.0;
                double y = random.nextInt(30) * 50.0;
                double dx = random.nextInt(5) * 50.0;
                double dy = random.nextInt(5) * 50.0;
                items.add(i % 2 == 0 ? point(x, y) : PlanFixture.line(x, y, x + dx, y + dy));
            }
            STRtree index = new STRtree();
            Envelope extent = new Envelope();
            for (int i = 0; i < items.size(); i++) {
                index.insert(items.get(i).getEnvelopeInternal(), i);
                extent.expandToInclude(items.get(i).getEnvelopeInternal());
            }
            index.build();
            for (int k = 0; k < 50; k++) {
                Point at = point(random.nextInt(80) * 25.0 - 250, random.nextInt(80) * 25.0 - 250);
                // номера, а не сами геометрии: на решётке бывают одинаковые объекты с разными номерами
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    order.add(i);
                }
                order.sort(Comparator.comparingDouble((Integer i) -> items.get(i).distance(at)).thenComparingInt(i -> i));
                List<Integer> found = new ArrayList<>();
                for (Geometry g : TieInFinder.nearest(items, index, extent, at, g -> g.distance(at),
                        g -> g.getEnvelopeInternal().distance(at.getEnvelopeInternal()))) {
                    int n = 0;
                    while (items.get(n) != g) {
                        n++;
                    }
                    found.add(n);
                }
                assertEquals(order.subList(0, Math.min(TieInFinder.NEAREST, order.size())), found, at.toString());
            }
        }
    }
}
