package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.Source;

/** Ручной вход в метрах EPSG:32637 для тестов планировщика. */
final class PlanFixture {
    static final GeometryFactory GEOMETRY = new GeometryFactory();

    final List<NetworkSegment> segments = new ArrayList<>();
    final List<Chamber> chambers = new ArrayList<>();
    final List<FutureOks> oks = new ArrayList<>();
    final List<ConnectionPoint> connections = new ArrayList<>();
    final List<Restriction> restrictions = new ArrayList<>();
    final List<ExistingOks> existing = new ArrayList<>();

    PlanFixture pipe(String id, double x1, double y1, double x2, double y2, int dn, double flow, String upstream) {
        segments.add(new NetworkSegment(id, line(x1, y1, x2, y2), dn, flow, upstream));
        return this;
    }

    PlanFixture chamber(String id, double x, double y, int dn, String upstream) {
        chambers.add(new Chamber(id, point(x, y), dn, upstream));
        return this;
    }

    /** ОКС квадратом 20 м за точкой подключения (x, y) в сторону от сети (вверх по y). */
    PlanFixture oks(String id, double x, double y, double flow) {
        oks.add(new FutureOks(id, rect(x - 10, y, x + 10, y + 20), flow, 1.0));
        connections.add(new ConnectionPoint("cp-" + id, point(x, y), id));
        return this;
    }

    PlanFixture restriction(String id, Geometry geometry, String type) {
        restrictions.add(new Restriction(id, geometry, type));
        return this;
    }

    InputData input() {
        return new InputData(new Source("src", point(0, 0)), segments, chambers, oks, connections, existing, restrictions,
                List.of());
    }

    ConnectionPoint connection(String oksId) {
        return connections.stream().filter(c -> c.getOksId().equals(oksId)).findFirst().orElseThrow();
    }

    /** Магистраль вдоль оси x от источника: hn-1 до камеры hc-1 в (200, 0), дальше hn-2 и hn-3 до (600, 0). */
    static PlanFixture trunk() {
        return new PlanFixture()
                .pipe("hn-1", 0, 0, 200, 0, 200, 60, "src")
                .chamber("hc-1", 200, 0, 200, "hn-1")
                .pipe("hn-2", 200, 0, 400, 0, 200, 50, "hc-1")
                .pipe("hn-3", 400, 0, 600, 0, 150, 40, "hn-2");
    }

    static Point point(double x, double y) {
        return GEOMETRY.createPoint(new Coordinate(x, y));
    }

    static LineString line(double x1, double y1, double x2, double y2) {
        return GEOMETRY.createLineString(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    static Geometry rect(double x1, double y1, double x2, double y2) {
        return GEOMETRY.createPolygon(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y1),
                new Coordinate(x2, y2), new Coordinate(x1, y2), new Coordinate(x1, y1)});
    }
}
