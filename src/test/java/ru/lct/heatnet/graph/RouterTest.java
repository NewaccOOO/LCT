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
import org.locationtech.jts.geom.LineSegment;
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
    void plainCountsMarginFromRuleClearance() {
        // зона шире нормы на 0,05 м, а запас формы трассы 0,15 м отсчитывается от нормы, иначе вершины с запасом
        // прямой 0,15–0,2 м остаются лишними (п. 5)
        Geometry park = rect(-50, -20, 50, 20);
        ObstacleSet zones = router(List.of(new Restriction("park-1", park, "park")), List.of()).obstacles();
        double y = 20 + rules.restriction("park").clearanceM(DN) + halfWidth;

        assertTrue(zones.plain(new Coordinate(-60, y + 0.17), new Coordinate(60, y + 0.17), Set.of(), 0.15));
        assertFalse(zones.plain(new Coordinate(-60, y + 0.13), new Coordinate(60, y + 0.13), Set.of(), 0.15));
    }

    @Test
    void routePassesBetweenDiagonalCornersCloserThanMitreZones() {
        // углы двух парков по диагонали в 3,2 м: круглые зоны отступа не смыкаются, а зоны с углами JOIN_MITRE
        // смыкались и закрывали проход
        Geometry left = rect(-50, -50, 0, 0);
        Geometry right = rect(2.263, 2.263, 50, 50);
        Router router = router(List.of(new Restriction("park-1", left, "park"), new Restriction("park-2", right, "park")),
                List.of());

        Route route = router.route(point(-30, 32.263), point(32.263, -30), Set.of());

        assertNotNull(route);
        double clearance = rules.restriction("park").clearanceM(DN) + halfWidth;
        assertEquals(2, route.getGeometry().getNumPoints(), route.getGeometry().toString());
        assertTrue(route.getGeometry().distance(left) >= clearance, "до парка " + route.getGeometry().distance(left));
        assertTrue(route.getGeometry().distance(right) >= clearance, "до парка " + route.getGeometry().distance(right));
    }

    @Test
    void cutRouteAtParkCornerKeepsOneTurn() {
        // срезка у угла зоны даёт хорду короче 10 м: её два поворота форма заменяет прежним углом (п. 5)
        Geometry park = rect(-50, -50, 0, 0);
        Router router = router(List.of(new Restriction("park-1", park, "park")), List.of());

        Route plain = router.routeToAny(point(-40, 5), List.of(point(5, -40)), Set.of(), false);
        Route cut = router.routeToAny(point(-40, 5), List.of(point(5, -40)), Set.of(), true);

        double clearance = rules.restriction("park").clearanceM(DN) + halfWidth;
        assertTrue(cut.getLength() <= plain.getLength() + EPS, cut.getLength() + " против " + plain.getLength());
        assertEquals(3, cut.getGeometry().getNumPoints(), cut.getGeometry().toString());
        assertTrue(cut.getGeometry().distance(park) >= clearance, "до парка " + cut.getGeometry().distance(park));
        Coordinate[] coords = cut.getGeometry().getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            assertTrue(coords[i].distance(coords[i + 1]) >= 1, "звено " + i + ": " + cut.getGeometry());
        }
    }

    @Test
    void cutRouteAroundRoundParkHasNoDoubleTurns() {
        // обход скруглённого парка по вершинам зоны — цепочка мелких поворотов в одну сторону; после доводки формы
        // два поворота подряд со звеном короче 10 м остаются, только если одним поворотом их не заменить (больше 90°)
        Geometry park = point(0, 0).buffer(30, 8);
        Router router = router(List.of(new Restriction("park-1", park, "park")), List.of());

        Route plain = router.routeToAny(point(-40, 20), List.of(point(20, -40)), Set.of(), false);
        Route cut = router.routeToAny(point(-40, 20), List.of(point(20, -40)), Set.of(), true);

        List<Coordinate> before = List.of(plain.getGeometry().getCoordinates());
        assertFalse(Router.chains(before, Router.turns(before), null).isEmpty(), plain.getGeometry().toString());
        Coordinate[] coords = cut.getGeometry().getCoordinates();
        double[] turns = Router.turns(List.of(coords));
        for (int[] chain : Router.chains(List.of(coords), turns, null)) {
            for (int v = chain[0]; v < chain[1]; v++) {
                assertTrue(Math.abs(turns[v] + turns[v + 1]) > Router.MAX_TURN_DEG, cut.getGeometry().toString());
            }
        }
        double clearance = rules.restriction("park").clearanceM(DN) + halfWidth;
        assertTrue(cut.getGeometry().distance(park) >= clearance, "до парка " + cut.getGeometry().distance(park));
        for (int i = 1; i + 1 < coords.length; i++) {
            assertTrue(Router.deflectionDeg(coords[i - 1], coords[i], coords[i + 1]) <= Router.MAX_TURN_DEG, cut.getGeometry().toString());
            assertTrue(coords[i].distance(coords[i + 1]) >= 1, "звено " + i + ": " + cut.getGeometry());
        }
    }

    @Test
    void sharpenDropsVertexOnlyWhereNeighboursSeeEachOther() {
        // (А): вершину, соседей которой соединяет прямая с отступом, форма убирает; вершина у угла парка нужна
        Geometry park = rect(-50, -50, 0, 0);
        Router router = router(List.of(new Restriction("park-1", park, "park")), List.of());
        List<Coordinate> detour = new ArrayList<>(List.of(new Coordinate(-40, 30), new Coordinate(0, 40), new Coordinate(40, 30)));
        List<Coordinate> around = new ArrayList<>(List.of(new Coordinate(-40, 10), new Coordinate(10, 10), new Coordinate(15, -40)));

        assertTrue(router.sharpen(router.obstacles(), detour, Set.of(), null, null));
        assertEquals(2, detour.size(), detour.toString());
        assertFalse(router.sharpen(router.obstacles(), around, Set.of(), null, null), around.toString());
    }

    @Test
    void sharpenBendsReplaceTwoTurnsOnLongLinkWithOneVertex() {
        // (Г): два поворота по −18,4° над узким парком на звене 20 м; ни одну вершину не убрать, а одна вершина над
        // парком с запасом короче прежнего пути
        double clearance = rules.restriction("park").clearanceM(DN) + halfWidth;
        Router router = router(List.of(new Restriction("park-1", rect(-3, -20, 3, 10 - clearance - 0.3), "park")), List.of());
        List<Coordinate> path = new ArrayList<>(List.of(new Coordinate(-40, 0), new Coordinate(-10, 10),
                new Coordinate(10, 10), new Coordinate(40, 0)));
        double before = factory.createLineString(path.toArray(new Coordinate[0])).getLength();

        assertFalse(router.sharpen(router.obstacles(), path, Set.of(), null, null, null, List.of(), false), path.toString());
        assertTrue(router.sharpen(router.obstacles(), path, Set.of(), null, null, null, List.of(), true));

        assertEquals(3, path.size(), path.toString());
        assertTrue(factory.createLineString(path.toArray(new Coordinate[0])).getLength() < before, path.toString());
        for (int i = 0; i + 1 < path.size(); i++) {
            assertTrue(router.obstacles().plain(path.get(i), path.get(i + 1), Set.of(), 0.15), path.toString());
        }
    }

    @Test
    void looseFindsZigzagVertexThatSharpenKeeps() {
        // зигзаг +4° и −5° на 1,2 м над углом парка: без одной вершины излом другой меньше 3°, без обеих прямая ближе
        // отступа к парку — форма его не убирает, а проверщик считает вершину лишней
        Router router = router(List.of(new Restriction("park-1", rect(-5, -50, 0, 0), "park")), List.of());
        double a = rules.restriction("park").clearanceM(DN) + halfWidth + 0.4;
        List<Coordinate> zigzag = new ArrayList<>(List.of(new Coordinate(-40, a + 39.4 * Math.tan(Math.toRadians(4))),
                new Coordinate(-0.6, a), new Coordinate(0.6, a), new Coordinate(40, a - 39.4 * Math.tan(Math.toRadians(5)))));

        assertFalse(router.sharpen(router.obstacles(), zigzag, Set.of(), null, null), zigzag.toString());
        assertTrue(router.loose(router.obstacles(), zigzag, Set.of(), null, null));
    }

    @Test
    void routeAroundThinWallTurnsNoSteeperThanNinetyDegrees() {
        // тонкая стена между точкой и целью: обход её конца без ограничения дал бы разворот почти на 180°
        Geometry wall = rect(-1, -60, 1, 60);
        Router router = router(List.of(new Restriction("park-1", wall, "park")), List.of());

        Route route = router.route(point(-20, 0), point(20, 0), Set.of());

        assertNotNull(route);
        Coordinate[] coords = route.getGeometry().getCoordinates();
        for (int i = 1; i + 1 < coords.length; i++) {
            double deflection = 180 - Math.toDegrees(Angle.angleBetween(coords[i - 1], coords[i], coords[i + 1]));
            assertTrue(deflection <= Router.MAX_TURN_DEG, "излом " + deflection + "° в вершине " + i + ": " + route.getGeometry());
        }
        double clearance = rules.restriction("park").clearanceM(DN) + halfWidth;
        assertTrue(route.getGeometry().distance(wall) >= clearance - 1e-6, "расстояние " + route.getGeometry().distance(wall));
    }

    @Test
    void exactRouteFromPortalKeepsTurnAtExitWithinLimit() {
        // точка выхода из здания в (0, 0), финальный участок пришёл с юга: цель за спиной ближе, но поворот к ней
        // круче 90°, поэтому точный поиск идёт к дальней цели впереди
        Router router = router(List.of(), List.of());
        Point exit = point(0, 0);
        Point behind = point(-20, -5);
        Point ahead = point(40, 10);

        Route free = router.routeToAny(exit, List.of(behind, ahead), Set.of());
        Route limited = router.routeExact(exit, List.of(behind, ahead), Set.of(), new Coordinate(0, -30), false);

        assertEquals(behind.getCoordinate(), free.getGeometry().getCoordinateN(1));
        assertNotNull(limited);
        Coordinate first = limited.getGeometry().getCoordinateN(1);
        assertEquals(ahead.getCoordinate(), first);
        double deflection = Router.deflectionDeg(new Coordinate(0, -30), exit.getCoordinate(), first);
        assertTrue(deflection <= Router.MAX_TURN_DEG, "поворот в точке выхода " + deflection + "°");
    }

    @Test
    void apartByBoxesOnlyWhenDistanceIsBeyondLimit() {
        // координаты UTM: отсев по рамкам отвергает пару, только если JTS тоже насчитал бы больше порога
        Random random = new Random(1);
        int apart = 0;
        for (int k = 0; k < 100_000; k++) {
            double x = 400_000 + random.nextDouble() * 5;
            double y = 6_200_000 + random.nextDouble() * 5;
            Coordinate p = new Coordinate(x, y);
            Coordinate q = new Coordinate(x + random.nextGaussian(), y + random.nextGaussian());
            Coordinate r = new Coordinate(x + random.nextGaussian() * 3, y + random.nextGaussian() * 3);
            Coordinate s = random.nextBoolean() ? r : new Coordinate(r.x + random.nextGaussian(), r.y + random.nextGaussian());
            double limit = random.nextDouble();
            if (Router.apart(p, q, r, s, limit)) {
                apart++;
                assertTrue(new LineSegment(p, q).distance(new LineSegment(r, s)) > limit);
            }
        }
        assertTrue(apart > 10_000, "отсев почти не срабатывает: " + apart);
    }

    @Test
    void repeatedQueryUsesCachedDijkstraTableAndGivesSameRoute() {
        Geometry park = rect(-50, -20, 50, 20);
        Router router = router(List.of(new Restriction("park-1", park, "park")), List.of());

        Route first = router.routeToAny(point(-70, 0), List.of(point(70, 0)), Set.of());
        Route second = router.routeToAny(point(-70, 0), List.of(point(70, 0)), Set.of());

        assertEquals(first.getGeometry(), second.getGeometry());
        long[] stats = router.tableStats();
        assertEquals(2, stats[0]);
        assertEquals(1, stats[1]);
    }

    @Test
    void routeToTieDoesNotRunAlongTiePipe() {
        // врезка в камеру (0, 0) на трубе вдоль оси x; точка подключения в (-130, 60): прямая под 155° раскладывается
        // на запад и северо-запад, но идти по трубе от врезки нельзя — сначала диагональ, потом запад
        NetworkSegment west = new NetworkSegment("hn-1", line(-250, 0, 0, 0), DN, 10, "src");
        NetworkSegment east = new NetworkSegment("hn-2", line(0, 0, 100, 0), DN, 10, "hn-1");
        Router router = router(List.of(), List.of(west, east));

        Route route = router.routeToAny(point(-130, 60), List.of(point(0, 0)), Set.of("hn-1", "hn-2"));

        assertNotNull(route);
        Coordinate[] coords = route.getGeometry().getCoordinates();
        Coordinate tie = coords[coords.length - 1];
        Coordinate beforeTie = coords[coords.length - 2];
        assertEquals(0, tie.distance(new Coordinate(0, 0)), EPS);
        assertTrue(Math.abs(beforeTie.y) > 1, "отрезок у врезки идёт по трубе: " + route.getGeometry());
    }

    @Test
    void sharpenStraightensEdgeFromTieIntoOneSegment() {
        // ребро от врезки на трубе вдоль оси x: лишняя вершина убирается и тогда, когда остаётся одна прямая, которая
        // касается трубы врезки своим концом
        NetworkSegment pipe = new NetworkSegment("hn-1", line(-100, 0, 100, 0), DN, 10, "src");
        Router router = router(List.of(), List.of(pipe));
        List<Coordinate> edge = new ArrayList<>(List.of(new Coordinate(0, 0), new Coordinate(-20, 30), new Coordinate(-40, 51)));

        assertTrue(router.sharpen(router.obstacles(), edge, Set.of("hn-1"), null, null));
        assertEquals(2, edge.size(), edge.toString());
    }

    @Test
    void pointRestrictionsAreBypassedWithTheirClearance() {
        // Точка неизвестного типа и точка railway обходятся с отступом своего правила.
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
    void roadAxisCrossingAt30DegreesGoesAtAllowedAngle() {
        // дорога осью (разд. 1.1, разъяснение 6): угол к звену оси, узлы вдоль оси дают переход от 45°, а не обход
        LineString axis = line(-500, 0, 500, 0);
        Router router = router(List.of(new Restriction("road-1", axis, "road")), List.of());
        Coordinate from = polar(-80, 30);
        Coordinate to = polar(80, 30);

        assertTrue(Double.isNaN(router.obstacles().edgeWeight(from, to, Set.of())));

        Route route = router.route(factory.createPoint(from), factory.createPoint(to), Set.of());
        assertNotNull(route);
        assertTrue(route.getGeometry().getLength() < 200, "обход конца оси: " + route.getGeometry().getLength());
        assertEquals(1, route.getSpans().size());
        assertEquals(2 * rules.restriction("road").getMarginM(), route.getSpans().get(0).getToM()
                - route.getSpans().get(0).getFromM(), EPS);
        Coordinate[] coords = route.getGeometry().getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            if (factory.createLineString(new Coordinate[] {coords[i], coords[i + 1]}).intersects(axis)) {
                double angle = Math.toDegrees(Math.atan2(Math.abs(coords[i + 1].y - coords[i].y),
                        Math.abs(coords[i + 1].x - coords[i].x)));
                assertTrue(angle >= rules.restriction("road").getMinAngleDeg(), "угол " + angle);
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
        // спецчасть — полигон и margin_m вдоль отрезка за границей (ТП разд. 4): ширина / sin 60° + 2 × margin
        double halfSpecial = roadWidth / 2 / Math.sin(Math.toRadians(60)) + rule.getMarginM();
        assertEquals(40 - halfSpecial, span.getFromM(), EPS);
        assertEquals(40 + halfSpecial, span.getToM(), EPS);
        assertEquals("road-1", span.getObjectId());
        assertEquals(rule.getKSpecial(), span.getKSpecial(), EPS);
        // вес ребра графа — по полосе margin_m поперёк границы, оценка не меньше спецчасти:
        // (ширина + 2 × margin) / sin 60°
        double halfBand = (roadWidth / 2 + rule.getMarginM()) / Math.sin(Math.toRadians(60));
        assertEquals(80 + (rule.getKSpecial() - 1) * 2 * halfBand, weight, EPS);
    }

    @Test
    void evenDropsSmallKinkAtEndOfRoadCrossing() {
        // спецчасть через дорогу кончается в вершине (0, 13), за ней трасса уходит в сторону на 1,3°: вершину заменяет
        // одна прямая через дорогу, почти под прямым углом к ней
        Router router = router(List.of(new Restriction("road-1", rect(-500, -10, 500, 10), "road")), List.of());
        List<Coordinate> coords = new ArrayList<>(List.of(new Coordinate(0, -60), new Coordinate(0, 13), new Coordinate(2, 100)));

        assertTrue(Router.micro(coords.get(0), coords.get(1), coords.get(2)));
        assertTrue(router.even(router.obstacles(), coords, Set.of(), null, List.of(), null, List.of()));
        assertEquals(List.of(new Coordinate(0, -60), new Coordinate(2, 100)), coords);
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
    void hintKeepsEdgeWeights() {
        // объект, запретивший прошлое ребро строки, проверяется первым: запрет и вес те же, что без подсказки
        Router router = router(List.of(new Restriction("park-1", rect(-60, -40, -20, 0), "park"),
                new Restriction("park-2", rect(20, 10, 70, 40), "park"),
                new Restriction("road-1", rect(-200, 50, 200, 70), "road"),
                new Restriction("gas-1", line(-200, -80, 200, -60), "gas_pipeline")), List.of());
        ObstacleSet obstacles = router.obstacles();
        List<Coordinate> nodes = obstacles.nodes();
        for (Set<String> ignored : List.of(Set.<String>of(), Set.of("park-1", "gas-1"))) {
            for (int i = 0; i < nodes.size(); i++) {
                ObstacleSet.Hint hint = ObstacleSet.hint();
                for (int j = 0; j < i; j++) {
                    Coordinate a = nodes.get(i);
                    Coordinate b = nodes.get(j);
                    boolean aNode = ignored.isEmpty();
                    assertEquals(obstacles.edgeWeight(a, b, ignored, aNode, true),
                            obstacles.edgeWeight(a, b, ignored, aNode, true, hint), a + " " + b);
                }
            }
        }
    }

    @Test
    void graphNodeNearLineCrossingCarriesSpecialPartBeforeIt() {
        // узел в 1 м от оси газопровода: отсчёт margin_m 2 м от пересечения продолжается за узел ещё на 1 м
        Router router = router(List.of(new Restriction("gas-1", line(-100, 0, 100, 0), "gas_pipeline")), List.of());
        RestrictionRule rule = rules.restriction("gas_pipeline");
        Coordinate node = new Coordinate(0, -1);
        Coordinate far = new Coordinate(0, 40);

        double asPathEnd = router.obstacles().edgeWeight(node, far, Set.of());
        double asNode = router.obstacles().edgeWeight(node, far, Set.of(), true, false);

        assertEquals(41 + (rule.getKSpecial() - 1) * 3, asPathEnd, EPS);
        assertEquals((rule.getKSpecial() - 1) * (rule.getMarginM() - 1), asNode - asPathEnd, EPS);
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
    void lineCrossingKeepsNormBeyondCircleAroundCrossing() {
        // газопровод: норма 2 + 0,255 + 0,2 = 2,455 м, в круге 2,555 м у пересечения обычная трасса может быть ближе.
        // Сеть Ду 100: норма 1,51 м меньше спецчасти 2 м, пересекать её можно круче asin(1,51 / 2) ≈ 49°
        ObstacleSet gas = router(List.of(new Restriction("gas-1", line(-100, 0, 100, 0), "gas_pipeline")), List.of())
                .obstacles();
        ObstacleSet pipe = router(List.of(), List.of(new NetworkSegment("hn-1", line(-100, 0, 100, 0), DN, 10, "src")))
                .obstacles();

        assertFalse(Double.isNaN(gas.edgeWeight(new Coordinate(0, -30), new Coordinate(0, 30), Set.of())));
        assertTrue(Double.isNaN(gas.edgeWeight(polar(-30, 70), polar(30, 70), Set.of())));
        assertFalse(Double.isNaN(pipe.edgeWeight(polar(-30, 55), polar(30, 55), Set.of())));
        assertTrue(Double.isNaN(pipe.edgeWeight(polar(-30, 45), polar(30, 45), Set.of())));
    }

    @Test
    void routeCrossesGasPipelineAtRightAngleThroughNodePair() {
        // прямая под 20° к газопроводу недопустима: трасса поворачивает к паре узлов и пересекает его под 90°
        Router router = router(List.of(new Restriction("gas-1", line(-100, 0, 100, 0), "gas_pipeline")), List.of());

        Route route = router.route(factory.createPoint(polar(-60, 20)), factory.createPoint(polar(60, 20)), Set.of());

        assertNotNull(route);
        assertEquals(1, route.getSpans().size());
        assertTrue(route.getLength() < 1.1 * 120, "длина " + route.getLength());
        Coordinate[] coords = route.getGeometry().getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            if (coords[i].y * coords[i + 1].y < 0) {
                assertEquals(coords[i].x, coords[i + 1].x, 1e-6, "пересечение не под прямым углом");
            }
        }
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
        return new InputData(null, segments, List.of(), List.of(), List.of(), List.of(), restrictions, List.of(), List.of(), Set.of());
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
