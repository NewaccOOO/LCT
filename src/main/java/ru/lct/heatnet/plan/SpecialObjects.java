package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.RayCrossingCounter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

final class SpecialObjects {
    private static final double NEAR_EXTRA_M = 0.1;
    private static final int CHUNK = 8;

    static final class Special {
        final Geometry geometry;
        final boolean polygon;
        final boolean band;
        final boolean network;
        final RestrictionRule rule;
        final double halfWidth;
        final double height;
        final PreparedGeometry prepared;
        final LineSegment[] sides;
        private final double[] chunks;
        final Map<Double, Geometry> buffers = new java.util.concurrent.ConcurrentHashMap<>();

        Special(Geometry geometry, boolean network, RestrictionRule rule, double halfWidth) {
            this(geometry, network, rule, halfWidth, 0);
        }

        Special(Geometry geometry, boolean network, RestrictionRule rule, double halfWidth, double height) {
            this.geometry = geometry;
            this.height = height;
            this.polygon = geometry.getDimension() == 2;
            this.band = polygon || rule.getMinAngleDeg() != null;
            this.network = network;
            this.rule = rule;
            this.halfWidth = halfWidth;
            this.prepared = band ? PreparedGeometryFactory.prepare(geometry) : null;
            this.sides = sides(polygon ? geometry.getBoundary() : geometry);
            this.chunks = new double[4 * ((sides.length + CHUNK - 1) / CHUNK)];
            for (int k = 0; k < sides.length; k++) {
                int c = k / CHUNK * 4;
                boolean first = k % CHUNK == 0;
                LineSegment side = sides[k];
                chunks[c] = first ? side.minX() : Math.min(chunks[c], side.minX());
                chunks[c + 1] = first ? side.minY() : Math.min(chunks[c + 1], side.minY());
                chunks[c + 2] = first ? side.maxX() : Math.max(chunks[c + 2], side.maxX());
                chunks[c + 3] = first ? side.maxY() : Math.max(chunks[c + 3], side.maxY());
            }
        }

        private Envelope chunk(int c) {
            return new Envelope(chunks[c], chunks[c + 2], chunks[c + 1], chunks[c + 3]);
        }

        boolean within(Point point, double distance) {
            return within(point, distance, sides);
        }

        boolean within(Point point, double distance, LineSegment[] near) {
            if (geometry.getEnvelopeInternal().distance(point.getEnvelopeInternal()) > distance) {
                return false;
            }
            Coordinate c = point.getCoordinate();
            if (polygon && inside(c)) {
                return true;
            }
            for (LineSegment side : near) {
                double dx = Math.max(Math.min(side.p0.x, side.p1.x) - c.x, c.x - Math.max(side.p0.x, side.p1.x));
                double dy = Math.max(Math.min(side.p0.y, side.p1.y) - c.y, c.y - Math.max(side.p0.y, side.p1.y));
                if (dx <= distance && dy <= distance && side.distance(c) <= distance) {
                    return true;
                }
            }
            return false;
        }

        boolean inside(Coordinate c) {
            RayCrossingCounter counter = new RayCrossingCounter(c);
            for (LineSegment side : sides) {
                counter.countSegment(side.p0, side.p1);
                if (counter.isOnSegment()) {
                    return true;
                }
            }
            return counter.getLocation() != Location.EXTERIOR;
        }

        boolean closer(LineString line, double limit) {
            if (line.getEnvelopeInternal().distance(geometry.getEnvelopeInternal()) >= limit) {
                return false;
            }
            if (polygon && inside(line.getCoordinateN(0))) {
                return 0 < limit;
            }
            Coordinate[] coords = line.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                Envelope piece = new Envelope(coords[i], coords[i + 1]);
                for (int c = 0; c < chunks.length; c += 4) {
                    if (piece.distance(chunk(c)) >= limit) {
                        continue;
                    }
                    for (int k = c / 4 * CHUNK, end = Math.min(sides.length, k + CHUNK); k < end; k++) {
                        LineSegment side = sides[k];
                        if (piece.distance(new Envelope(side.p0, side.p1)) < limit
                                && Distance.segmentToSegment(coords[i], coords[i + 1], side.p0, side.p1) < limit) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        boolean crossedBy(LineString line) {
            Coordinate[] coords = line.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                Envelope piece = new Envelope(coords[i], coords[i + 1]);
                for (int c = 0; c < chunks.length; c += 4) {
                    if (!piece.intersects(chunk(c))) {
                        continue;
                    }
                    for (int k = c / 4 * CHUNK, end = Math.min(sides.length, k + CHUNK); k < end; k++) {
                        if (ObstacleSet.meet(coords[i], coords[i + 1], sides[k].p0, sides[k].p1)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static LineSegment[] sides(Geometry lineal) {
            List<LineSegment> result = new ArrayList<>();
            for (int i = 0; i < lineal.getNumGeometries(); i++) {
                Coordinate[] coords = lineal.getGeometryN(i).getCoordinates();
                for (int k = 0; k + 1 < coords.length; k++) {
                    result.add(new LineSegment(coords[k], coords[k + 1]));
                }
            }
            return result.toArray(new LineSegment[0]);
        }
    }

    final List<Special> all = new ArrayList<>();
    private final STRtree index = new STRtree();
    private final Set<RestrictionRule> kinds = new HashSet<>();
    private final Rules rules;
    private final double maxHalfWidth;
    private final GeometryFactory factory = new GeometryFactory();

    SpecialObjects(InputData input, Rules rules) {
        this.rules = rules;
        RestrictionRule network = rules.restriction(TieCandidate.HEAT_NETWORK);
        for (NetworkSegment segment : input.getSegments()) {
            Diameter dn = rules.diameter(segment.getDiameter());
            all.add(new Special(segment.getGeometry(), true, network, dn.getWidthM() / 2, dn.getHeightM()));
        }
        for (Restriction restriction : input.getRestrictions()) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            if (!rule.forbid() && restriction.getGeometry().getDimension() > 0) {
                double halfWidth = restriction.getGeometry().getDimension() < 2 && rule.getHalfWidthM() != null
                        ? rule.getHalfWidthM() : 0;
                all.add(new Special(restriction.getGeometry(), false, rule, halfWidth));
            }
        }
        double widest = 0;
        for (Special special : all) {
            index.insert(special.geometry.getEnvelopeInternal(), special);
            widest = Math.max(widest, special.halfWidth);
            kinds.add(special.rule);
        }
        maxHalfWidth = widest;
        index.build();
    }

    boolean near(Coordinate c, int dn) {
        Point point = factory.createPoint(c);
        for (Special special : around(point, dn)) {
            if (special.within(point, clearance(special, dn) + NEAR_EXTRA_M)) {
                return true;
            }
        }
        return false;
    }

    List<Special> zonesNear(Envelope envelope) {
        List<Special> result = new ArrayList<>();
        for (Object item : index.query(envelope)) {
            result.add((Special) item);
        }
        return result;
    }

    List<Special> around(Geometry geometry, int dn) {
        Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
        envelope.expandBy(maxClearance(dn) + rules.diameter(dn).getWidthM() / 2 + maxHalfWidth + NEAR_EXTRA_M);
        List<Special> result = new ArrayList<>();
        for (Object item : index.query(envelope)) {
            result.add((Special) item);
        }
        return result;
    }

    double clearance(Special special, int dn) {
        return special.rule.clearanceM(dn) + rules.diameter(dn).getWidthM() / 2 + special.halfWidth;
    }

    private double maxClearance(int dn) {
        double max = 0;
        for (RestrictionRule rule : kinds) {
            max = Math.max(max, rule.clearanceM(dn));
        }
        return max;
    }
}
