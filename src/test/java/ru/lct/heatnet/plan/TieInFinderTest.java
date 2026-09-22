package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.plan.PlanFixture.point;

import java.util.List;
import org.junit.jupiter.api.Test;
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
}
