package ru.lct.heatnet.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.proj4j.geodesic.Geodesic;

class ProjectorTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    @Test
    void roundTripKeepsMoscowPointsAndGeodesicDistance() {
        Point kremlin = GEOMETRY.createPoint(new Coordinate(37.62, 55.75));
        Point southEast = GEOMETRY.createPoint(new Coordinate(37.9, 55.6));

        Point kremlinUtm = (Point) Projector.toUtm(kremlin);
        Point southEastUtm = (Point) Projector.toUtm(southEast);
        assertTrue(kremlinUtm.getX() > 400_000 && kremlinUtm.getX() < 430_000, "восток в зоне 37N: " + kremlinUtm.getX());
        assertTrue(kremlinUtm.getY() > 6_170_000 && kremlinUtm.getY() < 6_190_000, "север в зоне 37N: " + kremlinUtm.getY());

        for (Point point : List.of(kremlin, southEast)) {
            Point utm = (Point) Projector.toUtm(point);
            Point back = (Point) Projector.toWgs(utm);
            assertEquals(point.getX(), back.getX(), 1e-8);
            assertEquals(point.getY(), back.getY(), 1e-8);
            assertTrue(utm.distance(Projector.toUtm(back)) < 0.001, "расхождение в UTM меньше 1 мм");
        }

        double geodesic = Geodesic.WGS84.Inverse(55.75, 37.62, 55.6, 37.9).s12;
        double planar = kremlinUtm.distance(southEastUtm);
        assertEquals(geodesic, planar, geodesic / 1000, "1 м на 1 км");
    }

    @Test
    void projectsEveryGeometryTypeWithoutMutatingInput() {
        LinearRing shell = GEOMETRY.createLinearRing(coords(37.60, 55.70, 37.62, 55.70, 37.62, 55.72, 37.60, 55.72, 37.60, 55.70));
        LinearRing hole = GEOMETRY.createLinearRing(coords(37.605, 55.705, 37.61, 55.705, 37.61, 55.71, 37.605, 55.705));
        Polygon polygon = GEOMETRY.createPolygon(shell, new LinearRing[] {hole});
        List<Geometry> geometries = List.of(
                GEOMETRY.createPoint(new Coordinate(37.6, 55.7)),
                GEOMETRY.createLineString(coords(37.6, 55.7, 37.7, 55.8)),
                polygon,
                GEOMETRY.createMultiPointFromCoords(coords(37.6, 55.7, 37.7, 55.8)),
                GEOMETRY.createMultiLineString(new LineString[] {GEOMETRY.createLineString(coords(37.6, 55.7, 37.7, 55.8))}),
                GEOMETRY.createMultiPolygon(new Polygon[] {polygon}),
                GEOMETRY.createGeometryCollection(new Geometry[] {polygon, GEOMETRY.createPoint(new Coordinate(37.5, 55.5))}));

        for (Geometry geometry : geometries) {
            Geometry original = geometry.copy();
            Geometry utm = Projector.toUtm(geometry);
            assertTrue(geometry.equalsExact(original), "исходник не изменён: " + geometry.getGeometryType());
            assertEquals(geometry.getGeometryType(), utm.getGeometryType());
            assertEquals(geometry.getNumPoints(), utm.getNumPoints());
            assertTrue(utm.getEnvelopeInternal().getMinX() > 100_000, "координаты в метрах: " + geometry.getGeometryType());
            assertTrue(Projector.toWgs(utm).equalsExact(original, 1e-8), "туда и обратно: " + geometry.getGeometryType());
        }
    }

    @Test
    void isThreadSafe() {
        List<Point> expected = IntStream.range(0, 2000)
                .mapToObj(i -> (Point) Projector.toUtm(point(i)))
                .collect(Collectors.toList());
        List<Point> parallel = IntStream.range(0, 2000).parallel()
                .mapToObj(i -> (Point) Projector.toUtm(point(i)))
                .collect(Collectors.toList());
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(expected.get(i).equalsExact(parallel.get(i)), "точка " + i);
        }
    }

    private static Point point(int i) {
        return GEOMETRY.createPoint(new Coordinate(37.3 + i * 0.0003, 55.5 + i * 0.0002));
    }

    private static Coordinate[] coords(double... xy) {
        Coordinate[] result = new Coordinate[xy.length / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        return result;
    }
}
