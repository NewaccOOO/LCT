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

/** Быстрые проверки зон дают тот же ответ, что предикаты JTS, в том числе на касаниях и общих вершинах. */
class ObstacleGeometryTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final Random random = new Random(7);

    @Test
    void hitsBoxMatchesRectangleLineIntersector() {
        for (int i = 0; i < 50_000; i++) {
            Envelope box = new Envelope(lattice(), lattice(), lattice(), lattice());
            Coordinate a = new Coordinate(lattice(), lattice());
            Coordinate b = i % 3 == 0 ? new Coordinate(a) : new Coordinate(lattice(), lattice());
            assertEquals(new RectangleLineIntersector(box).intersects(a, b), ObstacleSet.hitsBox(box, a, b),
                    box + " " + a + " " + b);
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
    void zoneMatchesPreparedGeometry() {
        for (int i = 0; i < 150; i++) {
            Geometry zone = zone();
            ObstacleSet.Zone fast = new ObstacleSet.Zone("z", zone);
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
    void gridFindsSameZonesAsFullScan() {
        List<ObstacleSet.Zone> zones = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            zones.add(new ObstacleSet.Zone("z" + i, zone()));
        }
        ObstacleSet.ZoneGrid grid = new ObstacleSet.ZoneGrid(zones);
        for (int k = 0; k < 20_000; k++) {
            Coordinate a = new Coordinate(lattice() * 1.5 - 4, lattice() * 1.5 - 4);
            Coordinate b = k % 7 == 0 ? new Coordinate(a.x, lattice()) : new Coordinate(lattice() * 1.5 - 4, lattice() * 1.5 - 4);
            Set<String> ignored = k % 2 == 0 ? Set.of() : Set.of("z" + random.nextInt(60));
            boolean expected = false;
            for (ObstacleSet.Zone zone : zones) {
                expected |= !ignored.contains(zone.id) && zone.hitsBox(a, b) && zone.intersects(a, b);
            }
            assertEquals(expected, grid.hit(a, b, ignored), a + " " + b + " " + ignored);
        }
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

    /** Координата на решётке шагом 0,5 м: много общих вершин и коллинеарных случаев. */
    private double lattice() {
        return random.nextInt(81) * 0.5;
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
