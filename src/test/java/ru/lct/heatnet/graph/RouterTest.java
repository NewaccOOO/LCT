package ru.lct.heatnet.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

class RouterTest {
    private static final int DN = 100;
    private static final double EPS = 1e-6;
    private static final Envelope AREA = new Envelope(-1000, 1000, -1000, 1000);

    private final Rules rules = Rules.load();
    private final GeometryFactory factory = new GeometryFactory();
    private final double halfWidth = rules.diameter(DN).getWidthM() / 2;

    @Test
    void routeBendsAroundParkWithClearance() {
        Geometry park = rect(-50, -20, 50, 20);
        Router router = router(List.of(new Restriction("park-1", park, "park")), List.of());

        Route route = router.route(point(-70, 0), point(70, 0), Set.of());

        assertNotNull(route);
        double clearance = rules.restriction("park").clearanceM(DN) + halfWidth;
        assertTrue(route.getGeometry().distance(park) >= clearance, "расстояние " + route.getGeometry().distance(park));
        double orthogonalDetour = 2 * (20 + clearance) + 140;
        assertTrue(route.getLength() < 0.9 * orthogonalDetour, "длина " + route.getLength());
        assertTrue(route.getSpans().isEmpty());
        assertEquals(route.getLength(), route.getWeight(), EPS);
    }

    @Test
    void pointRestrictionsAreBypassedWithTheirClearance() {
        // Точка railway, у которого правило special, тоже обходится: пересечь точку специальным участком нельзя.
        List<Restriction> restrictions = List.of(new Restriction("pls-1", point(0, 0), "power_line_support"),
                new Restriction("rw-1", point(40, 1), "railway"), new Restriction("x-1", rect(-45, -3, -41, 3), "depot_xyz"));
        Router router = router(restrictions, List.of());

        Route route = router.route(point(-70, 0), point(70, 0), Set.of());

        assertNotNull(route);
        assertTrue(route.getSpans().isEmpty());
        for (Restriction restriction : restrictions) {
            double distance = route.getGeometry().distance(restriction.getGeometry());
            double clearance = rules.restriction(restriction.getType()).clearanceM(DN) + halfWidth;
            assertTrue(distance >= clearance, restriction.getId() + ": расстояние " + distance + ", нужно " + clearance);
        }
    }

    @Test
    void detourAroundSmallObstacleHasNoFlatVertices() {
        // обход опоры на прямой длиной 120 м по углам её зоны даёт изломы около 2,5°, а меньше 3° запрещено
        Router router = router(List.of(new Restriction("pls-1", point(0, 0), "power_line_support")), List.of());

        Route route = router.route(point(0, -60), point(0, 60), Set.of());

        assertNotNull(route);
        Coordinate[] coords = route.getGeometry().getCoordinates();
        for (int i = 1; i + 1 < coords.length; i++) {
            double deflection = 180 - Math.toDegrees(Angle.angleBetween(coords[i - 1], coords[i], coords[i + 1]));
            assertTrue(deflection >= 3, "излом " + deflection + "° в вершине " + i);
        }
        double clearance = rules.restriction("power_line_support").clearanceM(DN) + halfWidth;
        assertTrue(route.getGeometry().distance(point(0, 0)) >= clearance);
    }

    @Test
    void roadCrossingAt30DegreesIsRejected() {
        Geometry road = rect(-100, -10, 100, 10);
        Router router = router(List.of(new Restriction("road-1", road, "road")), List.of());
        Coordinate from = polar(-80, 30);
        Coordinate to = polar(80, 30);

        assertTrue(Double.isNaN(router.obstacles().edgeWeight(from, to, Set.of())));

        Route route = router.route(factory.createPoint(from), factory.createPoint(to), Set.of());
        assertNotNull(route);
        assertTrue(route.getGeometry().intersects(road), "маршрут должен перейти дорогу, а не обходить её");
        assertEquals(1, route.getSpans().size());
        double minAngle = rules.restriction("road").getMinAngleDeg();
        Coordinate[] coords = route.getGeometry().getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            LineString piece = factory.createLineString(new Coordinate[] {coords[i], coords[i + 1]});
            if (piece.intersects(road)) {
                double angle = Math.toDegrees(Math.atan2(Math.abs(coords[i + 1].y - coords[i].y),
                        Math.abs(coords[i + 1].x - coords[i].x)));
                assertTrue(angle >= minAngle, "угол " + angle);
            }
        }
    }

    @Test
    void roadCrossingAt60DegreesGetsSpecialWeight() {
        double roadWidth = 20;
        Geometry road = rect(-500, -roadWidth / 2, 500, roadWidth / 2);
        ObstacleSet obstacles = router(List.of(new Restriction("road-1", road, "road")), List.of()).obstacles();
        RestrictionRule rule = rules.restriction("road");
        Coordinate from = polar(-40, 60);
        Coordinate to = polar(40, 60);

        double weight = obstacles.edgeWeight(from, to, Set.of());
        List<SpecialSpan> spans = obstacles.spans(factory.createLineString(new Coordinate[] {from, to}), Set.of());

        assertEquals(1, spans.size());
        SpecialSpan span = spans.get(0);
        // полигон, буферизованный на margin_m, вдоль отрезка под 60° даёт (ширина + 2 × margin) / sin 60°
        double halfSpecial = (roadWidth / 2 + rule.getMarginM()) / Math.sin(Math.toRadians(60));
        assertEquals(40 - halfSpecial, span.getFromM(), EPS);
        assertEquals(40 + halfSpecial, span.getToM(), EPS);
        assertEquals("road-1", span.getObjectId());
        assertEquals(rule.getKSpecial(), span.getKSpecial(), EPS);
        assertEquals(80 + (rule.getKSpecial() - 1) * 2 * halfSpecial, weight, EPS);
    }

    @Test
    void perpendicularTramCrossingStaysStraight() {
        // Узлы вдоль путей лежат в полосе margin_m: переход между ними со сдвигом вбок не должен быть легче прямого.
        double tramWidth = 8;
        Router router = router(List.of(new Restriction("tram-1", rect(-500, 66, 500, 66 + tramWidth), "tram_tracks")),
                List.of());
        RestrictionRule rule = rules.restriction("tram_tracks");

        Route route = router.route(point(0, 0), point(0, 150), Set.of());

        assertNotNull(route);
        assertEquals(2, route.getGeometry().getNumPoints(), "трасса " + route.getGeometry());
        assertEquals(150, route.getLength(), EPS);
        assertEquals(150 + (rule.getKSpecial() - 1) * (tramWidth + 2 * rule.getMarginM()), route.getWeight(), EPS);
    }

    @Test
    void graphNodeNearLineCrossingCarriesSpecialPartBeforeIt() {
        // узел в 6 м от оси: отсчёт margin_m 10 м от пересечения продолжается за узел ещё на 4 м
        Router router = router(List.of(new Restriction("rw-1", line(-100, 0, 100, 0), "railway")), List.of());
        RestrictionRule rule = rules.restriction("railway");
        Coordinate node = new Coordinate(0, -6);
        Coordinate far = new Coordinate(0, 40);

        double asPathEnd = router.obstacles().edgeWeight(node, far, Set.of());
        double asNode = router.obstacles().edgeWeight(node, far, Set.of(), true, false);

        assertEquals(46 + (rule.getKSpecial() - 1) * 16, asPathEnd, EPS);
        assertEquals((rule.getKSpecial() - 1) * (rule.getMarginM() - 6), asNode - asPathEnd, EPS);
    }

    @Test
    void gasPipelineCrossingGivesFourMetreSpan() {
        LineString gas = line(-100, 0, 100, 0);
        Router router = router(List.of(new Restriction("gas-1", gas, "gas_pipeline")), List.of());
        RestrictionRule rule = rules.restriction("gas_pipeline");

        Route route = router.route(point(0, -30), point(0, 30), Set.of());

        assertNotNull(route);
        assertEquals(1, route.getSpans().size());
        SpecialSpan span = route.getSpans().get(0);
        assertEquals(4, span.getToM() - span.getFromM(), EPS);
        assertEquals(28, span.getFromM(), EPS);
        assertEquals(1.25, span.getKSpecial(), EPS);
        assertEquals("gas_pipeline", span.getType());
        assertEquals(60 + (rule.getKSpecial() - 1) * 4, route.getWeight(), EPS);
    }

    @Test
    void ignoredTieInSegmentGivesNoSpecialSpan() {
        NetworkSegment segment = new NetworkSegment("hn-1", line(-100, 0, 100, 0), DN, 10, "src");
        Router router = router(List.of(), List.of(segment));

        Route ignoredRoute = router.route(point(0, 0), point(0, 30), Set.of("hn-1"));
        Route plainRoute = router.route(point(0, 0), point(0, 30), Set.of());

        assertNotNull(ignoredRoute);
        assertTrue(ignoredRoute.getSpans().isEmpty());
        assertEquals(30, ignoredRoute.getWeight(), EPS);
        assertNotNull(plainRoute);
        assertEquals(1, plainRoute.getSpans().size());
        assertEquals("hn-1", plainRoute.getSpans().get(0).getObjectId());
        // отрезок, который не начинается на участке врезки, по-прежнему держит отступ от него
        Coordinate[] alongSegment = {new Coordinate(-50, 1), new Coordinate(50, 1)};
        assertTrue(Double.isNaN(router.obstacles().edgeWeight(alongSegment[0], alongSegment[1], Set.of("hn-1"))));
    }

    @Test
    void segmentParallelToGasPipelineOneMetreAwayIsRejected() {
        Router router = router(List.of(new Restriction("gas-1", line(-100, 0, 100, 0), "gas_pipeline")), List.of());

        assertTrue(Double.isNaN(router.obstacles().edgeWeight(new Coordinate(-50, 1), new Coordinate(50, 1), Set.of())));
        assertFalse(Double.isNaN(router.obstacles().edgeWeight(new Coordinate(-50, 5), new Coordinate(50, 5), Set.of())));
    }

    @Test
    void targetInsideClosedRingOfParksIsUnreachable() {
        List<Restriction> ring = List.of(
                new Restriction("p-bottom", rect(-60, -60, 60, -50), "park"),
                new Restriction("p-top", rect(-60, 50, 60, 60), "park"),
                new Restriction("p-left", rect(-60, -60, -50, 60), "park"),
                new Restriction("p-right", rect(50, -60, 60, 60), "park"));
        Router router = router(ring, List.of());

        assertNull(router.route(point(200, 0), point(0, 0), Set.of()));
        assertNull(router.routeToAny(point(200, 0), List.of(point(0, 0), point(10, 10)), Set.of()));
        assertNotNull(router.route(point(200, 0), point(-200, 0), Set.of()));
    }

    @Test
    void routeToAnyPicksTargetNearestByWeight() {
        Router router = router(List.of(new Restriction("park-1", rect(20, -30, 30, 30), "park")), List.of());
        Point behindPark = point(40, 0);
        Point open = point(0, -60);

        Route route = router.routeToAny(point(0, 0), List.of(behindPark, open), Set.of());

        assertNotNull(route);
        Coordinate[] coords = route.getGeometry().getCoordinates();
        assertTrue(coords[coords.length - 1].equals2D(open.getCoordinate()));
        assertEquals(60, route.getWeight(), EPS);
        assertTrue(router.route(point(0, 0), behindPark, Set.of()).getWeight() > 60);
    }

    @Test
    void graphOn200RectanglesAnswers100QueriesInTime() {
        Random random = new Random(42);
        double x0 = 410_000;
        double y0 = 6_180_000;
        List<Restriction> rects = new ArrayList<>();
        List<Envelope> placed = new ArrayList<>();
        while (rects.size() < 200) {
            double w = 20 + 40 * random.nextDouble();
            double h = 20 + 40 * random.nextDouble();
            Envelope candidate = new Envelope(0, w, 0, h);
            candidate.translate(x0 + random.nextDouble() * (2000 - w), y0 + random.nextDouble() * (2000 - h));
            if (placed.stream().noneMatch(e -> e.intersects(candidate))) {
                placed.add(candidate);
                rects.add(new Restriction("r-" + rects.size(),
                        rect(candidate.getMinX(), candidate.getMinY(), candidate.getMaxX(), candidate.getMaxY()), "park"));
            }
        }
        List<Point> free = new ArrayList<>();
        while (free.size() < 200) {
            Coordinate c = new Coordinate(x0 + 2000 * random.nextDouble(), y0 + 2000 * random.nextDouble());
            Envelope around = new Envelope(c);
            around.expandBy(5);
            if (placed.stream().noneMatch(e -> e.intersects(around))) {
                free.add(factory.createPoint(c));
            }
        }

        long start = System.nanoTime();
        Router router = new Router(input(rects, List.of()), rules, new Envelope(x0, x0 + 2000, y0, y0 + 2000), DN);
        long built = System.nanoTime();
        int found = 0;
        for (int i = 0; i < 100; i++) {
            if (router.route(free.get(2 * i), free.get(2 * i + 1), Set.of()) != null) {
                found++;
            }
        }
        long done = System.nanoTime();

        double buildS = (built - start) / 1e9;
        double queriesS = (done - built) / 1e9;
        System.out.printf("graph perf: nodes=%d build=%.2fs queries=%.2fs found=%d%n",
                router.obstacles().nodes().size(), buildS, queriesS, found);
        assertTrue(buildS + queriesS < 20, "построение и запросы заняли " + (buildS + queriesS) + " с");
        assertEquals(100, found);
    }

    private Router router(List<Restriction> restrictions, List<NetworkSegment> segments) {
        return new Router(input(restrictions, segments), rules, AREA, DN);
    }

    private static InputData input(List<Restriction> restrictions, List<NetworkSegment> segments) {
        return new InputData(null, segments, List.of(), List.of(), List.of(), List.of(), restrictions, List.of(), List.of());
    }

    private Geometry rect(double minX, double minY, double maxX, double maxY) {
        return factory.toGeometry(new Envelope(minX, maxX, minY, maxY));
    }

    private LineString line(double x0, double y0, double x1, double y1) {
        return factory.createLineString(new Coordinate[] {new Coordinate(x0, y0), new Coordinate(x1, y1)});
    }

    private Point point(double x, double y) {
        return factory.createPoint(new Coordinate(x, y));
    }

    private static Coordinate polar(double radius, double angleDeg) {
        double a = Math.toRadians(angleDeg);
        return new Coordinate(radius * Math.cos(a), radius * Math.sin(a));
    }
}
