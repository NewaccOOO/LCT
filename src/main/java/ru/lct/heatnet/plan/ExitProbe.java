package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.graph.ObstacleIndex;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

public final class ExitProbe {
    private static final double ALONG_STEP_M = 0.5;
    private static final double REACH_M = 30;
    private static final double CONTOUR_STEP_M = 1.0;
    private static final double CONTOUR_RADIUS_M = 30;
    private static final double EXTRA_M = 0.3;
    private static final int MAX_BLOCKERS = 12;
    private static final double TIE_STEP_M = 0.5;
    private static final double TIE_TOUCH_M = 0.5;
    private static final double MIN_PIECE_M = 1.0;
    private static final String OKS_EXISTING = "oks_existing";
    private static final String HEAT_NETWORK = "heat_network";
    private static final GeometryFactory FACTORY = new GeometryFactory();

    private ExitProbe() {
    }

    public static List<String> blockers(ObstacleSet zones, ObstacleIndex index, Rules rules, int dn, Geometry building,
            String buildingId, Coordinate cp, Map<String, Geometry> network) {
        Set<String> own = Set.of(buildingId);
        double clearance = zones.oksClearance();
        if (directTie(zones, index, rules, dn, building, buildingId, cp, network, clearance)) {
            return null;
        }
        List<Coordinate> sides = sides(building, cp);
        List<Coordinate> targets = new ArrayList<>(sides);
        targets.addAll(contour(building, cp));
        for (Coordinate target : targets) {
            double r = cp.distance(target);
            if (r < 1e-6) {
                continue;
            }
            double ux = (target.x - cp.x) / r;
            double uy = (target.y - cp.y) / r;
            for (double at = r + ALONG_STEP_M; at <= r + clearance + REACH_M; at += ALONG_STEP_M) {
                Coordinate exit = new Coordinate(cp.x + ux * at, cp.y + uy * at);
                if (!zones.insideForbid(exit) && TreeBuilder.leavesOnce(building, cp, exit, clearance)
                        && !Double.isNaN(zones.edgeWeight(cp, exit, own))) {
                    return null;
                }
            }
        }
        Set<String> found = new LinkedHashSet<>();
        for (Coordinate side : sides) {
            double r = cp.distance(side);
            if (r < 1e-6) {
                continue;
            }
            double ux = (side.x - cp.x) / r;
            double uy = (side.y - cp.y) / r;
            Coordinate exit = new Coordinate(cp.x + ux * (r + clearance + EXTRA_M), cp.y + uy * (r + clearance + EXTRA_M));
            String blocker = zones.blocker(cp, exit, own);
            if (blocker != null && !blocker.equals(buildingId)) {
                found.add(blocker);
            }
            if (found.size() >= MAX_BLOCKERS) {
                break;
            }
        }
        for (int i = 0; found.isEmpty() && i < sides.size(); i++) {
            Coordinate side = sides.get(i);
            double r = cp.distance(side);
            if (r < 1e-6) {
                continue;
            }
            double ux = (side.x - cp.x) / r;
            double uy = (side.y - cp.y) / r;
            for (double at = r + ALONG_STEP_M; at <= r + clearance + REACH_M; at += ALONG_STEP_M) {
                String blocker = zones.blocker(cp, new Coordinate(cp.x + ux * at, cp.y + uy * at), own);
                if (blocker != null && !blocker.equals(buildingId)) {
                    found.add(blocker);
                    break;
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static boolean directTie(ObstacleSet zones, ObstacleIndex index, Rules rules, int dn, Geometry building,
            String buildingId, Coordinate cp, Map<String, Geometry> network, double clearance) {
        for (Map.Entry<String, Geometry> item : network.entrySet()) {
            Geometry geometry = item.getValue();
            if (geometry.getEnvelopeInternal().distance(new Envelope(cp)) > CONTOUR_RADIUS_M) {
                continue;
            }
            Set<String> ignored = new HashSet<>(Set.of(buildingId, item.getKey()));
            for (Map.Entry<String, Geometry> other : network.entrySet()) {
                if (other.getValue().isWithinDistance(geometry, TIE_TOUCH_M)) {
                    ignored.add(other.getKey());
                }
            }
            for (Coordinate target : samples(geometry, cp)) {
                if (cp.distance(target) >= MIN_PIECE_M && TreeBuilder.leavesOnce(building, cp, target, clearance)
                        && (!Double.isNaN(zones.edgeWeight(cp, target, ignored))
                                || clear(index, rules, dn, cp, target, ignored))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean clear(ObstacleIndex index, Rules rules, int dn, Coordinate a, Coordinate b, Set<String> ignored) {
        LineString piece = FACTORY.createLineString(new Coordinate[] {a, b});
        Envelope envelope = piece.getEnvelopeInternal();
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        double oks = rules.restriction(OKS_EXISTING).clearanceM(dn) + halfWidth;
        for (ExistingOks other : index.existingOks(envelope)) {
            if (!ignored.contains(other.getId()) && other.getGeometry().distance(piece) < oks) {
                return false;
            }
        }
        for (Restriction restriction : index.restrictions(envelope)) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            double distance = rule.clearanceM(dn) + halfWidth;
            if (restriction.getGeometry().getDimension() < 2 && rule.getHalfWidthM() != null) {
                distance += rule.getHalfWidthM();
            }
            if (restriction.getGeometry().distance(piece) < distance) {
                return false;
            }
        }
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        for (NetworkSegment segment : index.segments(envelope)) {
            double distance = network.clearanceM(dn) + halfWidth + rules.diameter(segment.getDiameter()).getWidthM() / 2;
            if (!ignored.contains(segment.getId()) && segment.getGeometry().distance(piece) < distance) {
                return false;
            }
        }
        return true;
    }

    private static List<Coordinate> samples(Geometry geometry, Coordinate cp) {
        List<Coordinate> result = new ArrayList<>();
        Coordinate[] coords = geometry.getCoordinates();
        if (coords.length == 1) {
            result.add(coords[0]);
            return result;
        }
        for (int k = 0; k + 1 < coords.length; k++) {
            LineSegment piece = new LineSegment(coords[k], coords[k + 1]);
            if (piece.distance(cp) > CONTOUR_RADIUS_M) {
                continue;
            }
            result.add(piece.closestPoint(cp));
            int steps = (int) Math.ceil(piece.getLength() / TIE_STEP_M);
            for (int i = 0; i <= steps; i++) {
                Coordinate at = piece.pointAlong((double) i / Math.max(steps, 1));
                if (at.distance(cp) <= CONTOUR_RADIUS_M) {
                    result.add(at);
                }
            }
        }
        result.sort((a, b) -> Double.compare(cp.distance(a), cp.distance(b)));
        return result;
    }

    private static List<Coordinate> sides(Geometry building, Coordinate cp) {
        List<Coordinate> result = new ArrayList<>();
        for (int g = 0; g < building.getNumGeometries(); g++) {
            Coordinate[] ring = ((Polygon) building.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                result.add(new LineSegment(ring[k], ring[k + 1]).closestPoint(cp));
            }
        }
        result.sort((a, b) -> Double.compare(cp.distance(a), cp.distance(b)));
        return result;
    }

    private static List<Coordinate> contour(Geometry building, Coordinate cp) {
        List<Coordinate> result = new ArrayList<>();
        for (int g = 0; g < building.getNumGeometries(); g++) {
            Coordinate[] ring = ((Polygon) building.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                LineSegment side = new LineSegment(ring[k], ring[k + 1]);
                if (side.distance(cp) > CONTOUR_RADIUS_M) {
                    continue;
                }
                int steps = (int) Math.ceil(side.getLength() / CONTOUR_STEP_M);
                for (int i = 0; i <= steps; i++) {
                    Coordinate at = side.pointAlong((double) i / Math.max(steps, 1));
                    if (at.distance(cp) <= CONTOUR_RADIUS_M) {
                        result.add(at);
                    }
                }
            }
        }
        return result;
    }
}
