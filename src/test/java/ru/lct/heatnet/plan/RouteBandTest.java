package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** Оценки длины трассы в полосе другой охватывают длину по буферу JTS, и сравнение R-11 отвечает как по буферу. */
class RouteBandTest {
    private static final double WIDTH = 1.0;
    private final GeometryFactory factory = new GeometryFactory();
    private final Random random = new Random(5);

    @Test
    void boundsEncloseJtsLengthAndSameMatchesBuffer() {
        int decided = 0;
        for (int round = 0; round < 300; round++) {
            List<LineString> base = polylines(1 + random.nextInt(6));
            // другая трасса: те же линии, сдвинутые у полосы, часть своих и часть новых
            List<LineString> other = new ArrayList<>();
            double shift = random.nextInt(4) == 0 ? 0 : 0.5 + random.nextDouble();
            for (LineString line : base) {
                if (random.nextInt(4) > 0) {
                    other.add(shifted(line, shift * random.nextDouble(), shift * random.nextDouble()));
                }
            }
            other.addAll(polylines(random.nextInt(3)));
            if (other.isEmpty()) {
                other.add(base.get(0));
            }
            RouteBand a = new RouteBand(base, base.size(), WIDTH, factory);
            RouteBand b = new RouteBand(other, other.size(), WIDTH, factory);
            double jts = b.lines.intersection(a.lines.buffer(WIDTH)).getLength();
            double[] bounds = b.bounds(a);
            assertTrue(bounds[0] <= jts && jts <= bounds[1], jts + " вне " + bounds[0] + ".." + bounds[1]);
            for (double share : new double[] {0.5, 0.9, 0.99}) {
                double limit = share * Math.min(a.lines.getLength(), b.lines.getLength());
                boolean expected = jts > limit && a.lines.intersection(b.lines.buffer(WIDTH)).getLength() > limit;
                assertEquals(expected, RouteBand.same(a, b, share));
                decided += bounds[0] > limit || bounds[1] <= limit ? 1 : 0;
            }
        }
        assertTrue(decided > 300, "оценки почти ничего не решают: " + decided);
    }

    @Test
    void spanMatchesDistanceToSegment() {
        for (int i = 0; i < 20_000; i++) {
            double x0 = random.nextInt(20);
            double y0 = random.nextInt(20);
            double angle = random.nextDouble() * 2 * Math.PI;
            double dx = Math.cos(angle);
            double dy = Math.sin(angle);
            Coordinate t0 = new Coordinate(random.nextInt(20), random.nextInt(20));
            Coordinate t1 = random.nextInt(5) == 0 ? t0 : new Coordinate(random.nextInt(20), random.nextInt(20));
            double r = 1 + random.nextInt(3);
            double[] span = RouteBand.span(x0, y0, dx, dy, t0.x, t0.y, t1.x, t1.y, r);
            for (double u = -40; u <= 40; u += 0.37) {
                Coordinate p = new Coordinate(x0 + u * dx, y0 + u * dy);
                double distance = org.locationtech.jts.algorithm.Distance.pointToSegment(p, t0, t1);
                boolean in = span != null && u >= span[0] && u <= span[1];
                if (Math.abs(distance - r) > 1e-6) {
                    assertEquals(distance < r, in, p + " " + t0 + " " + t1 + " r=" + r);
                }
            }
        }
    }

    private List<LineString> polylines(int count) {
        List<LineString> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = 2 + random.nextInt(5);
            Coordinate[] coords = new Coordinate[n];
            double x = random.nextDouble() * 200;
            double y = random.nextDouble() * 200;
            for (int k = 0; k < n; k++) {
                coords[k] = new Coordinate(x, y);
                x += random.nextDouble() * 60 - 30;
                y += random.nextDouble() * 60 - 30;
            }
            result.add(factory.createLineString(coords));
        }
        return result;
    }

    private LineString shifted(LineString line, double dx, double dy) {
        Coordinate[] coords = line.getCoordinates();
        Coordinate[] moved = new Coordinate[coords.length];
        for (int k = 0; k < coords.length; k++) {
            moved[k] = new Coordinate(coords[k].x + dx, coords[k].y + dy);
        }
        return factory.createLineString(moved);
    }
}
