package ru.lct.heatnet.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.RectangleLineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/** Быстрые проверки зон дают тот же ответ, что предикаты и расстояния JTS, в том числе на касаниях и общих вершинах. */
class ObstacleGeometryTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final Random random = new Random(7);

    @Test
    void hitsBoxMatchesRectangleLineIntersector() {
        // та же решётка в координатах города: там знак произведения в double чаще спорный, и отвечает точный путь
        for (double shift : new double[] {0, 6_180_000.3}) {
            for (int i = 0; i < 50_000; i++) {
                Envelope box = new Envelope(shift + lattice(), shift + lattice(), shift + lattice(), shift + lattice());
                Coordinate a = new Coordinate(shift + lattice(), shift + lattice());
                Coordinate b = i % 3 == 0 ? new Coordinate(a) : new Coordinate(shift + lattice(), shift + lattice());
                assertEquals(new RectangleLineIntersector(box).intersects(a, b), ObstacleSet.hitsBox(box, a, b),
                        box + " " + a + " " + b);
            }
        }
    }

    @Test
    void intersectionNumMatchesLineIntersector() {
        RobustLineIntersector intersector = new RobustLineIntersector();
        for (int i = 0; i < 100_000; i++) {
            // короткая решётка: много общих концов и коллинеарных отрезков
            Coordinate p1 = new Coordinate(random.nextInt(6), random.nextInt(6));
            Coordinate p2 = new Coordinate(random.nextInt(6), random.nextInt(6));
            Coordinate q1 = new Coordinate(random.nextInt(6), random.nextInt(6));
            Coordinate q2 = new Coordinate(random.nextInt(6), random.nextInt(6));
            intersector.computeIntersection(p1, p2, q1, q2);
            assertEquals(intersector.getIntersectionNum(), ObstacleSet.intersectionNum(p1, p2, q1, q2), p1 + " " + p2 + " " + q1 + " " + q2);
        }
    }

    @Test
    void areaMatchesPreparedGeometry() {
        for (int i = 0; i < 150; i++) {
            Geometry zone = zone();
            ObstacleSet.Area fast = new ObstacleSet.Area(zone);
            PreparedGeometry prepared = PreparedGeometryFactory.prepare(zone);
            Coordinate[] vertices = zone.getCoordinates();
            for (int k = 0; k < 200; k++) {
                // концы на вершинах зоны и на решётке дают касания, коллинеарные стороны и точки на границе
                Coordinate a = k % 4 == 0 ? vertices[random.nextInt(vertices.length)] : new Coordinate(lattice(), lattice());
                Coordinate b = k % 5 == 0 ? vertices[random.nextInt(vertices.length)] : new Coordinate(lattice(), lattice());
                LineString edge = factory.createLineString(new Coordinate[] {a, b});
                assertEquals(prepared.intersects(edge), fast.hitsBox(a, b) && fast.intersects(a, b), zone + " " + edge);
                assertEquals(prepared.intersects(factory.createPoint(a)), fast.contains(a), zone + " " + a);
            }
        }
    }

    @Test
    void zoneMatchesDistanceToObject() {
        for (int i = 0; i < 150; i++) {
            Geometry object = object();
            double distance = 0.5 + random.nextInt(4);
            ObstacleSet.Zone fast = new ObstacleSet.Zone("z", object, distance);
            for (int k = 0; k < 200; k++) {
                Coordinate a = new Coordinate(lattice(), lattice());
                Coordinate b = k % 5 == 0 ? new Coordinate(a) : new Coordinate(lattice(), lattice());
                LineString edge = factory.createLineString(new Coordinate[] {a, b});
                // зона берёт отступ с запасом 0,05 м; у самой границы расстояния арифметика разная, такие пары не сверяются
                double gap = object.distance(edge) - (distance + 0.05);
                if (Math.abs(gap) > 1e-6) {
                    assertEquals(gap <= 0, fast.hitsBox(a, b) && fast.intersects(a, b, 0, false), object + " " + edge);
                }
                double pointGap = object.distance(factory.createPoint(a)) - (distance + 0.05);
                if (Math.abs(pointGap) > 1e-6) {
                    assertEquals(pointGap <= 0, fast.covers(a), object + " " + a);
                }
            }
        }
    }

    @Test
    void gridFindsSameZonesAsFullScan() {
        List<ObstacleSet.Zone> zones = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            zones.add(new ObstacleSet.Zone("z" + i, object(), 0.5 + random.nextInt(4)));
        }
        ObstacleSet.ZoneGrid grid = new ObstacleSet.ZoneGrid(zones);
        for (int k = 0; k < 20_000; k++) {
            Coordinate a = new Coordinate(lattice() * 1.5 - 4, lattice() * 1.5 - 4);
            Coordinate b = k % 7 == 0 ? new Coordinate(a.x, lattice()) : new Coordinate(lattice() * 1.5 - 4, lattice() * 1.5 - 4);
            Set<String> ignored = k % 2 == 0 ? Set.of() : Set.of("z" + random.nextInt(60));
            boolean expected = false;
            for (ObstacleSet.Zone zone : zones) {
                expected |= !ignored.contains(zone.id) && zone.hitsBox(a, b) && zone.intersects(a, b, 0, false);
            }
            assertEquals(expected, grid.hit(a, b, ignored, false), a + " " + b + " " + ignored);
        }
    }

    @Test
    void gridSkipsEmptyZone() {
        ObstacleSet.ZoneGrid grid = new ObstacleSet.ZoneGrid(List.of(new ObstacleSet.Zone("z", object(), 1),
                new ObstacleSet.Zone("empty", factory.createPolygon(), 1)));
        assertEquals(1, grid.zones.length);
        grid.hit(new Coordinate(0, 0), new Coordinate(40, 40), Set.of(), false);
    }

    @Test
    void turnAllowedMatchesDeflection() {
        Coordinate b = new Coordinate(412_345.678, 6_171_234.567);
        for (int i = 0; i < 200_000; i++) {
            // повороты у порога 89,9° и решётка с прямыми углами и нулевыми отрезками
            double angle = Math.toRadians(i % 2 == 0 ? Router.MAX_TURN_DEG + (random.nextDouble() - 0.5) * 1e-6 : random.nextDouble() * 360);
            double heading = random.nextDouble() * 2 * Math.PI;
            double length = 0.1 + random.nextDouble() * 500;
            Coordinate a = new Coordinate(b.x - length * Math.cos(heading), b.y - length * Math.sin(heading));
            Coordinate c = new Coordinate(b.x + length * Math.cos(heading + angle), b.y + length * Math.sin(heading + angle));
            if (i % 5 == 0) {
                a = new Coordinate(lattice(), lattice());
                c = new Coordinate(lattice(), lattice());
                b = new Coordinate(lattice(), lattice());
            }
            assertEquals(Router.deflectionDeg(a, b, c) <= Router.MAX_TURN_DEG, Router.turnAllowed(a, b, c), a + " " + b + " " + c);
            b = new Coordinate(412_345.678, 6_171_234.567);
        }
    }

    @Test
    void beyondMatchesHypotSum() {
        for (int i = 0; i < 200_000; i++) {
            Coordinate node = new Coordinate(412_000 + random.nextDouble() * 1000, 6_171_000 + random.nextDouble() * 1000);
            Coordinate t = i % 3 == 0 ? new Coordinate(node) : new Coordinate(412_000 + random.nextDouble() * 1000, 6_171_000 + random.nextDouble() * 1000);
            double dist = i % 7 == 0 ? Double.POSITIVE_INFINITY : random.nextDouble() * 2000;
            double exact = dist + node.distance(t);
            // вес ровно на сумме, на соседних double и случайный
            double weight = i % 4 == 0 ? exact : i % 4 == 1 ? Math.nextUp(exact) : i % 4 == 2 ? Math.nextDown(exact) : random.nextDouble() * 3000;
            assertEquals(exact > weight, Router.beyond(dist, node.x, node.y, t, weight), dist + " " + node + " " + t + " " + weight);
        }
    }

    @Test
    void heapPollsInPriorityQueueOrder() {
        Router.Heap heap = new Router.Heap();
        java.util.PriorityQueue<double[]> expected = new java.util.PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int i = 0; i < 200_000; i++) {
            if (expected.isEmpty() || random.nextInt(3) > 0) {
                // веса из короткого списка: много равных, порядок при них задаёт устройство кучи
                double key = random.nextInt(20) * 0.5;
                heap.add(key, i, i + 1, i + 2);
                expected.add(new double[] {key, i, i + 1, i + 2});
            } else {
                double[] top = expected.poll();
                heap.poll();
                assertEquals(top[0], heap.key);
                assertEquals((int) top[1], heap.node);
                assertEquals((int) top[2], heap.pred);
                assertEquals((int) top[3], heap.id);
            }
            assertEquals(expected.isEmpty(), heap.isEmpty());
        }
    }

    @Test
    void gridIndexMatchesFloor() {
        ObstacleSet.ZoneGrid grid = new ObstacleSet.ZoneGrid(List.of(new ObstacleSet.Zone("z", object(), 1)));
        double[] values = {0, -0.0, 0.5, -0.5, -1, -1e-300, 1e-300, 3e9, -3e9, -2147483648.5, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < 100_000; i++) {
            double value = i < values.length ? values[i] : (random.nextDouble() - 0.5) * Math.pow(10, random.nextInt(12));
            assertEquals((int) Math.floor((value - grid.x0) / grid.cell), grid.index(value, grid.x0), "" + value);
        }
    }

    /** Координата на решётке шагом 0,5 м: много общих вершин и коллинеарных случаев. */
    private double lattice() {
        return random.nextInt(81) * 0.5;
    }

    /** Объект зоны отступа: точка, ломаная или полигон с дыркой и второй частью. */
    private Geometry object() {
        double x = lattice();
        double y = lattice();
        switch (random.nextInt(3)) {
            case 0:
                return factory.createPoint(new Coordinate(x, y));
            case 1:
                return factory.createLineString(new Coordinate[] {new Coordinate(x, y), new Coordinate(lattice(), lattice()),
                    new Coordinate(lattice(), lattice())});
            default:
                return zone();
        }
    }

    /** Буфер точки, отрезка или пары прямоугольников: многоугольники с дугами, дырками и несколькими частями. */
    private Geometry zone() {
        double x = lattice();
        double y = lattice();
        switch (random.nextInt(3)) {
            case 0:
                return factory.createPoint(new Coordinate(x, y)).buffer(1 + random.nextInt(6), 1 + random.nextInt(8));
            case 1:
                return factory.createLineString(new Coordinate[] {new Coordinate(x, y), new Coordinate(lattice(), lattice())})
                        .buffer(0.5 + random.nextInt(3));
            default:
                Geometry outer = factory.toGeometry(new Envelope(x, x + 8, y, y + 6));
                Geometry hole = factory.toGeometry(new Envelope(x + 2, x + 5, y + 2, y + 4));
                Geometry other = factory.toGeometry(new Envelope(x + 10, x + 12, y, y + 1));
                return outer.difference(hole).union(other);
        }
    }
}
