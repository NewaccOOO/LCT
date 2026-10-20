package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.CGAlgorithmsDD;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RayCrossingCounter;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

public final class ObstacleSet {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ObstacleSet.class);
    private static final String OKS_EXISTING = "oks_existing";
    private static final String HEAT_NETWORK = "heat_network";
    private static final double SIMPLIFY_M = 0.05;
    private static final double NODE_OFFSET_M = 0.05;
    private static final double ALONG_SKIP_M = 0.5;
    private static final double ALONG_TOL_M = 0.01;
    private static final double SPAN_JOIN_M = 1e-6;
    private static final double TIE_IN_TOUCH_M = 0.5;
    private static final double DEPARTURE_RAY_M = 0.01;
    private static final double DEPARTURE_GAP_RAD = Math.toRadians(120);
    private static final double DEPARTURE_SAME_RAD = Math.toRadians(1);
    private static final double CROSSING_DEPARTURE_STEP_M = 0.25;
    private static final double CROSSING_DEPARTURE_MAX_M = 40;
    private static final double[] CROSSING_DEPARTURE_TURNS_DEG = {0, -15, 15, -30, 30};
    private static final double ANGLE_MARGIN_DEG = 0.01;
    private static final double CROSSING_STEP_M = 20;
    private static final int MARGIN_QUADRANT_SEGMENTS = 16;
    private static final double TANGENT_EPS = 1e-9;
    private static final double ORIENTATION_TOL = 1e-15;
    private static final double MARGIN_NODE_INSET_M = 0.1;
    public static final double CROSS_CIRCLE_M = 0.1;
    private static final double NORM_EXTRA_M = 0.002;
    private static final double GATE_STEP_M = 20;
    private static final double GATE_OFFSET_M = 0.08;
    private static final double GATE_BEYOND_M = 0.05;
    private static final double GATE_SHIFT_M = 0.5;
    private static final double GATE_REACH_M = 40;
    private static final String ZONE_KEY = "zone";
    private static final double HALVES_MIN_TURN_DEG = 20;
    private static final double HALVES_MAX_TURN_DEG = 120;
    private static final int CHUNK = 8;
    private static final long ZONE_BYTES = 48;
    static final java.util.concurrent.ForkJoinPool PARTS = new java.util.concurrent.ForkJoinPool();
    private static final BufferParameters ZONE_BUFFER = new BufferParameters(
            BufferParameters.DEFAULT_QUADRANT_SEGMENTS, BufferParameters.CAP_SQUARE,
            BufferParameters.JOIN_MITRE, BufferParameters.DEFAULT_MITRE_LIMIT);

    private final GeometryFactory factory = new GeometryFactory();
    private final STRtree forbidZones = new STRtree();
    private final ZoneGrid forbidGrid;
    private final RouteCache cache;
    private final STRtree specials = new STRtree();
    private final STRtree margins = new STRtree();
    private final Special[] specialOrder;
    private final double[] specialBoxes;
    private final double oksClearance;
    private final List<Coordinate> nodes = new ArrayList<>();
    private final boolean withNodes;
    private final Envelope area;
    private final PreparedGeometry inside;
    private final double[] around;
    private final ThreadLocal<Object[]> lastSpans = new ThreadLocal<>();

    static final class Zone {
        final String id;
        final Geometry geometry;
        final double distance;
        final boolean polygon;
        final Envelope core;
        final Envelope box;
        final double centerX;
        final double centerY;
        final double radius;
        final double[] sides;
        final double[] chunks;
        volatile Area mitre;

        Zone(String id, Geometry geometry, double distance) {
            this.id = id;
            this.geometry = geometry;
            this.distance = distance + SIMPLIFY_M;
            this.polygon = geometry.getDimension() == 2;
            this.core = geometry.getEnvelopeInternal();
            this.box = new Envelope(core);
            box.expandBy(this.distance);
            Coordinate center = core.centre();
            this.centerX = center == null ? 0 : center.x;
            this.centerY = center == null ? 0 : center.y;
            this.radius = Math.hypot(core.getWidth(), core.getHeight()) / 2;
            LineSegment[] segments = geometry.getDimension() == 0 ? points(geometry)
                    : segments(polygon ? geometry.getBoundary() : geometry);
            this.sides = sides(segments);
            this.chunks = chunks(segments);
        }

        boolean hitsBox(Coordinate a, Coordinate b) {
            return ObstacleSet.hitsBox(box, a, b);
        }

        boolean intersects(Coordinate a, Coordinate b, double extra, boolean outside) {
            double reach = distance + extra;
            double minX = Math.min(a.x, b.x) - reach;
            double maxX = Math.max(a.x, b.x) + reach;
            double minY = Math.min(a.y, b.y) - reach;
            double maxY = Math.max(a.y, b.y) + reach;
            double limit = reach * reach;
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double length2 = dx * dx + dy * dy;
            if (squared(centerX, centerY, a, dx, dy, length2) > (radius + reach) * (radius + reach)) {
                return false;
            }
            double far = reach * Math.sqrt(length2);
            for (int c = 0; c < chunks.length; c += 4) {
                if (chunks[c] > maxX || chunks[c + 2] < minX || chunks[c + 1] > maxY || chunks[c + 3] < minY) {
                    continue;
                }
                for (int k = c * CHUNK, end = Math.min(sides.length, k + 4 * CHUNK); k < end; k += 4) {
                    double x0 = sides[k];
                    double y0 = sides[k + 1];
                    double x1 = sides[k + 2];
                    double y1 = sides[k + 3];
                    if (Math.max(x0, x1) < minX || Math.min(x0, x1) > maxX || Math.max(y0, y1) < minY || Math.min(y0, y1) > maxY) {
                        continue;
                    }
                    double c0 = dx * (y0 - a.y) - dy * (x0 - a.x);
                    double c1 = dx * (y1 - a.y) - dy * (x1 - a.x);
                    if (c0 > far && c1 > far || c0 < -far && c1 < -far) {
                        continue;
                    }
                    double sx = x1 - x0;
                    double sy = y1 - y0;
                    double side2 = sx * sx + sy * sy;
                    if (squared(a.x, a.y, x0, y0, sx, sy, side2) <= limit || squared(b.x, b.y, x0, y0, sx, sy, side2) <= limit
                            || squared(x0, y0, a, dx, dy, length2) <= limit || squared(x1, y1, a, dx, dy, length2) <= limit) {
                        return true;
                    }
                    double t0 = sx * (a.y - y0) - sy * (a.x - x0);
                    double t1 = sx * (a.y + dy - y0) - sy * (a.x + dx - x0);
                    if ((c0 < 0) != (c1 < 0) && (t0 < 0) != (t1 < 0)) {
                        return true;
                    }
                }
            }
            return polygon && !outside && core.contains(a) && core.contains(b) && inside(a);
        }

        boolean covers(Coordinate c) {
            return box.contains(c) && intersects(c, c, 0, false);
        }

        private boolean inside(Coordinate p) {
            boolean inside = false;
            for (int c = 0; c < chunks.length; c += 4) {
                if (chunks[c + 1] > p.y || chunks[c + 3] < p.y || chunks[c + 2] < p.x) {
                    continue;
                }
                for (int k = c * CHUNK, end = Math.min(sides.length, k + 4 * CHUNK); k < end; k += 4) {
                    double x0 = sides[k];
                    double y0 = sides[k + 1];
                    double x1 = sides[k + 2];
                    double y1 = sides[k + 3];
                    if (y0 > p.y != y1 > p.y && p.x < x0 + (p.y - y0) * (x1 - x0) / (y1 - y0)) {
                        inside = !inside;
                    }
                }
            }
            return inside;
        }
    }

    private static double squared(double x, double y, Coordinate a, double dx, double dy, double length2) {
        return squared(x, y, a.x, a.y, dx, dy, length2);
    }

    private static double squared(double x, double y, double x0, double y0, double dx, double dy, double length2) {
        double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / length2));
        double ex = x0 + t * dx - x;
        double ey = y0 + t * dy - y;
        return ex * ex + ey * ey;
    }

    static final class Area {
        final Envelope box;
        final double[] sides;
        final double[] chunks;

        Area(Geometry geometry) {
            this.box = geometry.getEnvelopeInternal();
            LineSegment[] segments = segments(geometry.getBoundary());
            this.sides = sides(segments);
            this.chunks = chunks(segments);
        }

        boolean hitsBox(Coordinate a, Coordinate b) {
            return ObstacleSet.hitsBox(box, a, b);
        }

        double[] cuts(Coordinate p0, Coordinate p1, LineIntersector intersector) {
            double[] out = new double[8];
            out[1] = p0.distance(p1);
            int count = 2;
            double minX = Math.min(p0.x, p1.x);
            double maxX = Math.max(p0.x, p1.x);
            double minY = Math.min(p0.y, p1.y);
            double maxY = Math.max(p0.y, p1.y);
            for (int c = 0; c < chunks.length; c += 4) {
                if (chunks[c] > maxX || chunks[c + 2] < minX || chunks[c + 1] > maxY || chunks[c + 3] < minY
                        || !ObstacleSet.hitsBox(chunks[c], chunks[c + 1], chunks[c + 2], chunks[c + 3], p0, p1)) {
                    continue;
                }
                for (int k = c * CHUNK, end = Math.min(sides.length, k + 4 * CHUNK); k < end; k += 4) {
                    if (!meet(p0, p1, sides[k], sides[k + 1], sides[k + 2], sides[k + 3])) {
                        continue;
                    }
                    intersector.computeIntersection(p0, p1, new Coordinate(sides[k], sides[k + 1]),
                            new Coordinate(sides[k + 2], sides[k + 3]));
                    for (int i = 0; i < intersector.getIntersectionNum(); i++) {
                        if (count == out.length) {
                            out = Arrays.copyOf(out, 2 * count);
                        }
                        out[count++] = p0.distance(intersector.getIntersection(i));
                    }
                }
            }
            out = Arrays.copyOf(out, count);
            Arrays.sort(out);
            return out;
        }

        boolean contains(Coordinate p) {
            return intersects(p, null);
        }

        boolean intersects(Coordinate a, Coordinate b) {
            double minX = b == null ? a.x : Math.min(a.x, b.x);
            double maxX = b == null ? a.x : Math.max(a.x, b.x);
            double minY = b == null ? a.y : Math.min(a.y, b.y);
            double maxY = b == null ? a.y : Math.max(a.y, b.y);
            int crossings = 0;
            for (int c = 0; c < chunks.length; c += 4) {
                boolean touch = b != null && chunks[c] <= maxX && chunks[c + 2] >= minX && chunks[c + 1] <= maxY
                        && chunks[c + 3] >= minY && ObstacleSet.hitsBox(chunks[c], chunks[c + 1], chunks[c + 2], chunks[c + 3], a, b);
                boolean ray = chunks[c + 1] <= a.y && chunks[c + 3] >= a.y && chunks[c + 2] >= a.x;
                if (!touch && !ray) {
                    continue;
                }
                for (int k = c * CHUNK, end = Math.min(sides.length, k + 4 * CHUNK); k < end; k += 4) {
                    double x1 = sides[k];
                    double y1 = sides[k + 1];
                    double x2 = sides[k + 2];
                    double y2 = sides[k + 3];
                    if (touch && meet(a, b, x1, y1, x2, y2)) {
                        return true;
                    }
                    if (!ray || x1 < a.x && x2 < a.x) {
                        continue;
                    }
                    if (a.x == x2 && a.y == y2) {
                        return true;
                    }
                    if (y1 == a.y && y2 == a.y) {
                        if (a.x >= Math.min(x1, x2) && a.x <= Math.max(x1, x2)) {
                            return true;
                        }
                        continue;
                    }
                    if (y1 > a.y && y2 <= a.y || y2 > a.y && y1 <= a.y) {
                        int orient = CGAlgorithmsDD.orientationIndex(x1, y1, x2, y2, a.x, a.y);
                        if (orient == Orientation.COLLINEAR) {
                            return true;
                        }
                        if ((y2 < y1 ? -orient : orient) == Orientation.LEFT) {
                            crossings++;
                        }
                    }
                }
            }
            return crossings % 2 == 1;
        }
    }

    private static double[] boxes(Zone[] zones) {
        double[] boxes = new double[4 * zones.length];
        for (int k = 0; k < zones.length; k++) {
            boxes[4 * k] = zones[k].box.getMinX();
            boxes[4 * k + 1] = zones[k].box.getMinY();
            boxes[4 * k + 2] = zones[k].box.getMaxX();
            boxes[4 * k + 3] = zones[k].box.getMaxY();
        }
        return boxes;
    }

    private static double[] sides(LineSegment[] segments) {
        double[] sides = new double[4 * segments.length];
        for (int k = 0; k < segments.length; k++) {
            sides[4 * k] = segments[k].p0.x;
            sides[4 * k + 1] = segments[k].p0.y;
            sides[4 * k + 2] = segments[k].p1.x;
            sides[4 * k + 3] = segments[k].p1.y;
        }
        return sides;
    }

    private static double[] chunks(LineSegment[] segments) {
        double[] chunks = new double[4 * ((segments.length + CHUNK - 1) / CHUNK)];
        for (int k = 0; k < segments.length; k++) {
            int c = k / CHUNK * 4;
            Envelope side = new Envelope(segments[k].p0, segments[k].p1);
            boolean first = k % CHUNK == 0;
            chunks[c] = first ? side.getMinX() : Math.min(chunks[c], side.getMinX());
            chunks[c + 1] = first ? side.getMinY() : Math.min(chunks[c + 1], side.getMinY());
            chunks[c + 2] = first ? side.getMaxX() : Math.max(chunks[c + 2], side.getMaxX());
            chunks[c + 3] = first ? side.getMaxY() : Math.max(chunks[c + 3], side.getMaxY());
        }
        return chunks;
    }

    private static boolean meet(Coordinate a, Coordinate b, double x1, double y1, double x2, double y2) {
        if (Math.max(a.x, b.x) < Math.min(x1, x2) || Math.min(a.x, b.x) > Math.max(x1, x2)
                || Math.max(a.y, b.y) < Math.min(y1, y2) || Math.min(a.y, b.y) > Math.max(y1, y2)) {
            return false;
        }
        int pq1 = CGAlgorithmsDD.orientationIndex(a.x, a.y, b.x, b.y, x1, y1);
        int pq2 = CGAlgorithmsDD.orientationIndex(a.x, a.y, b.x, b.y, x2, y2);
        if (pq1 > 0 && pq2 > 0 || pq1 < 0 && pq2 < 0) {
            return false;
        }
        int qp1 = CGAlgorithmsDD.orientationIndex(x1, y1, x2, y2, a.x, a.y);
        int qp2 = CGAlgorithmsDD.orientationIndex(x1, y1, x2, y2, b.x, b.y);
        if (qp1 > 0 && qp2 > 0 || qp1 < 0 && qp2 < 0) {
            return false;
        }
        return pq1 != 0 || pq2 != 0 || qp1 != 0 || qp2 != 0 || meet(a, b, new Coordinate(x1, y1), new Coordinate(x2, y2));
    }

    static boolean hitsBox(Envelope box, Coordinate a, Coordinate b) {
        return hitsBox(box.getMinX(), box.getMinY(), box.getMaxX(), box.getMaxY(), a, b);
    }

    static boolean hitsBox(double minX, double minY, double maxX, double maxY, Coordinate a, Coordinate b) {
        if (Math.max(a.x, b.x) < minX || Math.min(a.x, b.x) > maxX || Math.max(a.y, b.y) < minY || Math.min(a.y, b.y) > maxY) {
            return false;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        if (dx == 0 || dy == 0) {
            return true;
        }
        boolean rising = dx > 0 == dy > 0;
        double firstY = rising ? maxY : minY;
        double secondY = rising ? minY : maxY;
        double p1 = dx * (firstY - a.y);
        double q1 = dy * (minX - a.x);
        double p2 = dx * (secondY - a.y);
        double q2 = dy * (maxX - a.x);
        if (Math.abs(p1 - q1) > ORIENTATION_TOL * (Math.abs(p1) + Math.abs(q1))
                && Math.abs(p2 - q2) > ORIENTATION_TOL * (Math.abs(p2) + Math.abs(q2))) {
            return p1 - q1 > 0 != p2 - q2 > 0;
        }
        return CGAlgorithmsDD.orientationIndex(a.x, a.y, b.x, b.y, minX, firstY)
                * CGAlgorithmsDD.orientationIndex(a.x, a.y, b.x, b.y, maxX, secondY) <= 0;
    }

    static final class ZoneGrid {
        private static final double EDGE_EPS_M = 1e-6;
        private static final int MAX_CELLS = 1 << 22;
        private static final ThreadLocal<Marks> MARKS = ThreadLocal.withInitial(Marks::new);

        final Zone[] zones;
        final double[] boxes;
        final double x0;
        final double y0;
        final double cell;
        final int cols;
        final int rows;
        final int[] start;
        final int[] items;

        ZoneGrid(List<Zone> list) {
            zones = list.stream().filter(zone -> !zone.box.isNull()).toArray(Zone[]::new);
            boxes = boxes(zones);
            Envelope bounds = new Envelope();
            for (Zone zone : zones) {
                bounds.expandToInclude(zone.box);
            }
            x0 = bounds.isNull() ? 0 : bounds.getMinX();
            y0 = bounds.isNull() ? 0 : bounds.getMinY();
            double side = Math.sqrt(Math.max(bounds.getArea(), 1) / Math.max(zones.length, 1));
            side = Math.max(side, Math.sqrt(Math.max(bounds.getArea(), 1) / MAX_CELLS));
            cell = Math.max(side, 1);
            cols = bounds.isNull() ? 1 : index(bounds.getMaxX(), x0) + 1;
            rows = bounds.isNull() ? 1 : index(bounds.getMaxY(), y0) + 1;
            start = new int[cols * rows + 1];
            for (Zone zone : zones) {
                forCells(zone.box, c -> start[c + 1]++);
            }
            for (int c = 0; c < cols * rows; c++) {
                start[c + 1] += start[c];
            }
            items = new int[start[cols * rows]];
            int[] fill = Arrays.copyOf(start, cols * rows);
            for (int k = 0; k < zones.length; k++) {
                int zone = k;
                forCells(zones[k].box, c -> items[fill[c]++] = zone);
            }
        }

        private void forCells(Envelope box, java.util.function.IntConsumer action) {
            for (int j = index(box.getMinY(), y0); j <= index(box.getMaxY(), y0); j++) {
                for (int i = index(box.getMinX(), x0); i <= index(box.getMaxX(), x0); i++) {
                    action.accept(j * cols + i);
                }
            }
        }

        int index(double value, double origin) {
            double at = (value - origin) / cell;
            int i = (int) at;
            return at < i && i != Integer.MIN_VALUE ? i - 1 : i;
        }

        boolean hit(Coordinate a, Coordinate b, Set<String> ignored, boolean outside) {
            return zoneHit(a, b, ignored, outside, MARKS.get()) >= 0;
        }

        int zoneHit(Coordinate a, Coordinate b, Set<String> ignored, boolean outside, Marks seen) {
            boolean alongX = Math.abs(b.x - a.x) >= Math.abs(b.y - a.y);
            double au = alongX ? a.x : a.y;
            double av = alongX ? a.y : a.x;
            double bu = alongX ? b.x : b.y;
            double bv = alongX ? b.y : b.x;
            double u0 = alongX ? x0 : y0;
            double v0 = alongX ? y0 : x0;
            int nu = alongX ? cols : rows;
            int nv = alongX ? rows : cols;
            int strideU = alongX ? 1 : cols;
            int strideV = alongX ? cols : 1;
            double uMin = Math.min(au, bu);
            double uMax = Math.max(au, bu);
            int first = index(uMin, u0);
            int last = index(uMax, u0);
            if (last < 0 || first >= nu || zones.length == 0) {
                return -1;
            }
            first = Math.max(first, 0);
            last = Math.min(last, nu - 1);
            double slope = bu == au ? 0 : (bv - av) / (bu - au);
            double minX = Math.min(a.x, b.x);
            double maxX = Math.max(a.x, b.x);
            double minY = Math.min(a.y, b.y);
            double maxY = Math.max(a.y, b.y);
            if (seen.zones.length < zones.length) {
                seen.zones = new int[zones.length];
            }
            int[] marks = seen.zones;
            if (++seen.round == Integer.MAX_VALUE) {
                Arrays.fill(marks, 0);
                seen.round = 1;
            }
            int round = seen.round;
            boolean forward = bu >= au;
            for (int step = 0; step <= last - first; step++) {
                int i = forward ? first + step : last - step;
                double from = Math.max(uMin, u0 + i * cell);
                double to = Math.min(uMax, u0 + (i + 1) * cell);
                double vFrom = av + (from - au) * slope;
                double vTo = av + (to - au) * slope;
                int low = Math.max(index(Math.min(vFrom, vTo) - EDGE_EPS_M, v0), 0);
                int high = Math.min(index(Math.max(vFrom, vTo) + EDGE_EPS_M, v0), nv - 1);
                for (int k = 0; k <= high - low; k++) {
                    int c = i * strideU + (bv >= av ? low + k : high - k) * strideV;
                    for (int p = start[c]; p < start[c + 1]; p++) {
                        int z = items[p];
                        if (marks[z] == round) {
                            continue;
                        }
                        marks[z] = round;
                        int at = 4 * z;
                        if (boxes[at] > maxX || boxes[at + 2] < minX || boxes[at + 1] > maxY || boxes[at + 3] < minY) {
                            continue;
                        }
                        if (blocks(z, a, b, ignored, outside)) {
                            return z;
                        }
                    }
                }
            }
            return -1;
        }

        boolean covers(Coordinate c) {
            int i = index(c.x, x0);
            int j = index(c.y, y0);
            if (zones.length == 0 || i < 0 || i >= cols || j < 0 || j >= rows) {
                return false;
            }
            for (int p = start[j * cols + i]; p < start[j * cols + i + 1]; p++) {
                if (zones[items[p]].covers(c)) {
                    return true;
                }
            }
            return false;
        }

        boolean blocks(int z, Coordinate a, Coordinate b, Set<String> ignored, boolean outside) {
            int at = 4 * z;
            return hitsBox(boxes[at], boxes[at + 1], boxes[at + 2], boxes[at + 3], a, b)
                    && (ignored.isEmpty() || !ignored.contains(zones[z].id)) && zones[z].intersects(a, b, 0, outside);
        }

        static final class Marks {
            int[] zones = new int[0];
            int round;
        }
    }

    private static boolean crosses(List<LineSegment> sides, Coordinate a, Coordinate b) {
        for (LineSegment side : sides) {
            if (meet(a, b, side.p0, side.p1)) {
                return true;
            }
        }
        return false;
    }

    public static boolean meet(Coordinate p1, Coordinate p2, Coordinate q1, Coordinate q2) {
        return intersectionNum(p1, p2, q1, q2) != LineIntersector.NO_INTERSECTION;
    }

    static int intersectionNum(Coordinate p1, Coordinate p2, Coordinate q1, Coordinate q2) {
        if (!Envelope.intersects(p1, p2, q1, q2)) {
            return LineIntersector.NO_INTERSECTION;
        }
        int pq1 = Orientation.index(p1, p2, q1);
        int pq2 = Orientation.index(p1, p2, q2);
        if (pq1 > 0 && pq2 > 0 || pq1 < 0 && pq2 < 0) {
            return LineIntersector.NO_INTERSECTION;
        }
        int qp1 = Orientation.index(q1, q2, p1);
        int qp2 = Orientation.index(q1, q2, p2);
        if (qp1 > 0 && qp2 > 0 || qp1 < 0 && qp2 < 0) {
            return LineIntersector.NO_INTERSECTION;
        }
        if (pq1 == 0 && pq2 == 0 && qp1 == 0 && qp2 == 0) {
            LineIntersector intersector = new RobustLineIntersector();
            intersector.computeIntersection(p1, p2, q1, q2);
            return intersector.getIntersectionNum();
        }
        return LineIntersector.POINT_INTERSECTION;
    }

    static final class Special {
        final String id;
        final String type;
        final RestrictionRule rule;
        final boolean polygon;
        final PreparedGeometry object;
        final Zone zone;
        final Area margin;
        final Area shape;
        final LineSegment[] sides;
        final double[] chunks;
        final Map<Coordinate, Double> beyond = new java.util.concurrent.ConcurrentHashMap<>();

        Special(String id, String type, RestrictionRule rule, Geometry geometry, double zoneDistance) {
            this.id = id;
            this.type = type;
            this.rule = rule;
            this.polygon = geometry.getDimension() == 2;
            this.object = PreparedGeometryFactory.prepare(geometry);
            this.zone = new Zone(id, geometry, zoneDistance);
            this.margin = polygon ? new Area(geometry.buffer(rule.getMarginM(), MARGIN_QUADRANT_SEGMENTS)) : null;
            this.shape = polygon ? new Area(geometry) : null;
            this.sides = segments(polygon ? geometry.getBoundary() : geometry);
            this.chunks = chunks(sides);
        }

        boolean near(Coordinate a, Coordinate b, double distance) {
            Envelope envelope = new Envelope(a, b);
            if (object.getGeometry().getEnvelopeInternal().distance(envelope) > distance) {
                return false;
            }
            if (polygon) {
                RayCrossingCounter counter = new RayCrossingCounter(a);
                for (LineSegment side : sides) {
                    counter.countSegment(side.p0, side.p1);
                }
                if (counter.getLocation() != Location.EXTERIOR) {
                    return true;
                }
            }
            for (LineSegment side : sides) {
                if (new Envelope(side.p0, side.p1).distance(envelope) <= distance
                        && Distance.segmentToSegment(a, b, side.p0, side.p1) <= distance) {
                    return true;
                }
            }
            return false;
        }

        boolean crossedBy(Coordinate a, Coordinate b) {
            return polygon ? shape.hitsBox(a, b) && shape.intersects(a, b) : crosses(sidesNear(a, b), a, b);
        }

        List<LineSegment> sidesNear(Coordinate a, Coordinate b) {
            double minX = Math.min(a.x, b.x);
            double maxX = Math.max(a.x, b.x);
            double minY = Math.min(a.y, b.y);
            double maxY = Math.max(a.y, b.y);
            int most = 0;
            for (int c = 0; c < chunks.length; c += 4) {
                if (!(chunks[c] > maxX || chunks[c + 2] < minX || chunks[c + 1] > maxY || chunks[c + 3] < minY)) {
                    most += CHUNK;
                }
            }
            List<LineSegment> result = new ArrayList<>(most);
            for (int c = 0; c < chunks.length && most > 0; c += 4) {
                if (chunks[c] > maxX || chunks[c + 2] < minX || chunks[c + 1] > maxY || chunks[c + 3] < minY
                        || !hitsBox(chunks[c], chunks[c + 1], chunks[c + 2], chunks[c + 3], a, b)) {
                    continue;
                }
                for (int k = c / 4 * CHUNK, end = Math.min(sides.length, k + CHUNK); k < end; k++) {
                    LineSegment side = sides[k];
                    if (Math.max(side.p0.x, side.p1.x) >= minX && Math.min(side.p0.x, side.p1.x) <= maxX
                            && Math.max(side.p0.y, side.p1.y) >= minY && Math.min(side.p0.y, side.p1.y) <= maxY) {
                        result.add(side);
                    }
                }
            }
            return result;
        }
    }

    public ObstacleSet(InputData input, Rules rules, Envelope area, int dn) {
        this(new ObstacleIndex(input, rules), rules, area, dn);
    }

    public ObstacleSet(ObstacleIndex index, Rules rules, Envelope area, int dn) {
        this(index, rules, area, dn, null);
    }

    public ObstacleSet(ObstacleIndex index, Rules rules, Envelope area, int dn, Geometry corridor) {
        this(index, rules, area, dn, corridor, null);
    }

    public ObstacleSet(ObstacleIndex index, Rules rules, Envelope area, int dn, Geometry corridor, RouteCache cache) {
        this(index, rules, area, dn, corridor, cache, true);
    }

    public ObstacleSet(ObstacleIndex index, Rules rules, Envelope area, int dn, Geometry corridor, RouteCache cache,
            boolean withNodes) {
        long started = System.nanoTime();
        this.withNodes = withNodes;
        this.cache = cache;
        this.area = area;
        inside = corridor == null ? null : PreparedGeometryFactory.prepare(corridor);
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        List<Zone> forbid = new ArrayList<>();
        List<Geometry> nodeObjects = new ArrayList<>();
        List<Double> nodeDistances = new ArrayList<>();
        List<Double> nodeOffsets = new ArrayList<>();
        List<Geometry> crossingObjects = new ArrayList<>();
        List<Double> crossingDistances = new ArrayList<>();
        List<Geometry> marginZones = new ArrayList<>();
        List<Special> specialList = new ArrayList<>();

        double oksDistance = rules.restriction(OKS_EXISTING).clearanceM(dn) + halfWidth;
        oksClearance = oksDistance;
        for (ExistingOks oks : index.existingOks(area)) {
            if (near(oks.getGeometry(), oksDistance, area) && (inside == null || inside.intersects(oks.getGeometry()))) {
                forbid.add(new Zone(oks.getId(), oks.getGeometry(), oksDistance));
                nodeObjects.add(oks.getGeometry());
                nodeDistances.add(oksDistance);
                nodeOffsets.add(nodeOffset(oksDistance));
            }
        }
        for (Restriction restriction : index.restrictions(area)) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            Geometry geometry = restriction.getGeometry();
            double distance = rule.clearanceM(dn) + halfWidth;
            if (geometry.getDimension() < 2 && rule.getHalfWidthM() != null) {
                distance += rule.getHalfWidthM();
            }
            if (!near(geometry, distance, area) || inside != null && !inside.intersects(geometry)) {
                continue;
            }
            nodeObjects.add(geometry);
            nodeDistances.add(distance);
            nodeOffsets.add(nodeOffset(distance));
            if (rule.forbid() || geometry.getDimension() == 0) {
                forbid.add(new Zone(restriction.getId(), geometry, distance));
            } else {
                Special special = new Special(restriction.getId(), restriction.getType(), rule, geometry, distance);
                specialList.add(special);
                if (withNodes && (special.polygon || rule.getMinAngleDeg() != null)) {
                    marginZones.add(geometry.buffer(rule.getMarginM() - MARGIN_NODE_INSET_M, MARGIN_QUADRANT_SEGMENTS));
                    crossingObjects.add(geometry);
                    crossingDistances.add(Math.max(distance, rule.getMarginM() - 2 * SIMPLIFY_M - NODE_OFFSET_M));
                }
            }
        }
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        for (NetworkSegment segment : index.segments(area)) {
            double distance = network.clearanceM(dn) + halfWidth + rules.diameter(segment.getDiameter()).getWidthM() / 2;
            if (near(segment.getGeometry(), distance, area) && (inside == null || inside.intersects(segment.getGeometry()))) {
                nodeObjects.add(segment.getGeometry());
                nodeDistances.add(distance);
                nodeOffsets.add(nodeOffset(distance));
                specialList.add(new Special(segment.getId(), HEAT_NETWORK, network, segment.getGeometry(), distance));
            }
        }

        for (Zone zone : forbid) {
            forbidZones.insert(zone.box, zone);
        }
        forbidGrid = new ZoneGrid(forbid);
        for (Special special : specialList) {
            specials.insert(special.zone.box, special);
        }
        forbidZones.build();
        specials.build();
        Envelope all = new Envelope();
        for (Special special : specialList) {
            all.expandToInclude(special.zone.box);
        }
        specialOrder = ((List<?>) specials.query(all)).toArray(new Special[0]);
        specialBoxes = boxes(Arrays.stream(specialOrder).map(special -> special.zone).toArray(Zone[]::new));

        List<Geometry> nodeZones = withNodes ? nodeZones(nodeObjects, nodeDistances) : List.of();
        List<Geometry> crossingNodeZones = withNodes ? nodeZones(crossingObjects, crossingDistances) : List.of();
        Map<Coordinate, Coordinate[]> candidates = new LinkedHashMap<>();
        Map<Coordinate, Double> offsets = new java.util.HashMap<>();
        for (int z = 0; z < nodeZones.size(); z++) {
            Geometry nodeZone = nodeZones.get(z);
            for (int i = 0; i < nodeZone.getNumGeometries(); i++) {
                Polygon polygon = (Polygon) nodeZone.getGeometryN(i);
                addConvexVertices(polygon.getExteriorRing(), true, nodeOffsets.get(z), candidates, offsets);
                for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                    addConvexVertices(polygon.getInteriorRingN(h), false, nodeOffsets.get(z), candidates, offsets);
                }
            }
        }
        int zoneCandidates = candidates.size();
        for (Geometry nodeZone : crossingNodeZones) {
            for (Coordinate c : Densifier.densify(nodeZone.getBoundary(), CROSSING_STEP_M).getCoordinates()) {
                candidates.putIfAbsent(c, null);
            }
        }
        for (Geometry marginZone : marginZones) {
            margins.insert(marginZone.getEnvelopeInternal(), PreparedGeometryFactory.prepare(marginZone));
        }
        margins.build();
        List<Coordinate[]> rings = new ArrayList<>();
        for (Map.Entry<Coordinate, Coordinate[]> candidate : candidates.entrySet()) {
            Coordinate c = candidate.getKey();
            Coordinate[] ring = candidate.getValue();
            if (!area.contains(c) || inside != null && !inside.intersects(factory.createPoint(c)) || insideAny(margins, c)) {
                continue;
            }
            if (!insideAnyZone(c)) {
                nodes.add(c);
                rings.add(ring);
                continue;
            }
            Coordinate[] halves = ring == null ? null : halves(c, ring, offsets.get(c));
            if (halves != null) {
                addNode(halves[0], new Coordinate[] {ring[0], halves[1]}, area, inside, margins, rings);
                addNode(halves[1], new Coordinate[] {halves[0], ring[1]}, area, inside, margins, rings);
            }
        }
        int beforeGates = nodes.size();
        for (Special special : specialList) {
            if (withNodes && !special.polygon && !HEAT_NETWORK.equals(special.type)) {
                for (Coordinate[] gate : gates(special, area, margins)) {
                    nodes.add(gate[0]);
                    rings.add(new Coordinate[] {gate[1], null});
                    nodes.add(gate[1]);
                    rings.add(new Coordinate[] {gate[0], null});
                }
            }
        }
        around = new double[6 * nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            Coordinate[] ring = rings.get(i);
            around[6 * i] = nodes.get(i).x;
            around[6 * i + 1] = nodes.get(i).y;
            around[6 * i + 2] = ring == null ? Double.NaN : ring[0].x;
            around[6 * i + 3] = ring == null ? Double.NaN : ring[0].y;
            around[6 * i + 4] = ring == null || ring[1] == null ? Double.NaN : ring[1].x;
            around[6 * i + 5] = ring == null || ring[1] == null ? Double.NaN : ring[1].y;
        }
        LOG.debug("obstacles: dn={} area={}x{} forbid={} specials={} margins={} candidates zone={} crossing={} nodes={} gates={} ms={}",
                dn, Math.round(area.getWidth()), Math.round(area.getHeight()), forbid.size(), specialList.size(), marginZones.size(),
                zoneCandidates, candidates.size() - zoneCandidates, nodes.size(), nodes.size() - beforeGates,
                (System.nanoTime() - started) / 1_000_000);
    }

    private List<Coordinate[]> gates(Special special, Envelope area, STRtree margins) {
        List<Coordinate[]> out = new ArrayList<>();
        double offset = Math.max(special.zone.distance - SIMPLIFY_M + GATE_OFFSET_M,
                special.rule.getMarginM() + GATE_BEYOND_M);
        for (LineSegment side : special.sides) {
            double length = side.getLength();
            if (length == 0) {
                continue;
            }
            int count = Math.max(1, (int) Math.round(length / GATE_STEP_M));
            double nx = -(side.p1.y - side.p0.y) / length;
            double ny = (side.p1.x - side.p0.x) / length;
            for (int k = 0; k < count; k++) {
                Coordinate at = side.pointAlong((k + 0.5) / count);
                Coordinate left = beyond(at, nx, ny, offset, area, margins);
                Coordinate right = left == null ? null : beyond(at, -nx, -ny, offset, area, margins);
                if (right != null) {
                    out.add(new Coordinate[] {left, right});
                }
            }
        }
        return out;
    }

    private Coordinate beyond(Coordinate at, double nx, double ny, double offset, Envelope area,
            STRtree margins) {
        for (double t = offset; t <= offset + GATE_REACH_M; t += GATE_SHIFT_M) {
            Coordinate c = new Coordinate(at.x + nx * t, at.y + ny * t);
            if (!area.contains(c) || inside != null && !inside.intersects(factory.createPoint(c)) || forbidGrid.covers(c)) {
                return null;
            }
            if (!insideAny(margins, c) && !insideSpecialZone(c)) {
                return c;
            }
        }
        return null;
    }

    private void addNode(Coordinate c, Coordinate[] ring, Envelope area, PreparedGeometry inside, STRtree margins,
            List<Coordinate[]> rings) {
        if (area.contains(c) && (inside == null || inside.intersects(factory.createPoint(c))) && !insideAnyZone(c)
                && !insideAny(margins, c)) {
            nodes.add(c);
            rings.add(ring);
        }
    }

    private static Coordinate[] halves(Coordinate v, Coordinate[] ring, double offset) {
        double turn = Math.toRadians(Router.deflectionDeg(ring[0], v, ring[1]));
        double shift = offset * (Math.tan(turn / 2) - Math.tan(turn / 4));
        if (turn < Math.toRadians(HALVES_MIN_TURN_DEG) || turn > Math.toRadians(HALVES_MAX_TURN_DEG)
                || shift >= v.distance(ring[0]) || shift >= v.distance(ring[1])) {
            return null;
        }
        return new Coordinate[] {toward(v, ring[0], shift), toward(v, ring[1], shift)};
    }

    private static Coordinate toward(Coordinate from, Coordinate to, double distance) {
        double share = distance / from.distance(to);
        return new Coordinate(from.x + (to.x - from.x) * share, from.y + (to.y - from.y) * share);
    }

    private boolean insideAny(STRtree polygons, Coordinate c) {
        Geometry point = factory.createPoint(c);
        for (Object polygon : polygons.query(new Envelope(c))) {
            if (((PreparedGeometry) polygon).intersects(point)) {
                return true;
            }
        }
        return false;
    }

    public double oksClearance() {
        return oksClearance;
    }

    public boolean insideForbid(Coordinate c) {
        return insideForbid(c, true);
    }

    public boolean insideMargin(Coordinate c) {
        return insideAny(margins, c);
    }

    public boolean insideForbid(Coordinate c, boolean mitre) {
        for (Object item : forbidZones.query(new Envelope(c))) {
            Zone zone = (Zone) item;
            if (mitre ? zone.box.contains(c) && mitre(zone).contains(c) : zone.covers(c)) {
                return true;
            }
        }
        return false;
    }

    public boolean covers(Coordinate a, Coordinate b) {
        return area.contains(a) && area.contains(b)
                && (inside == null || inside.covers(factory.createLineString(new Coordinate[] {a, b})));
    }

    public boolean forbidden(Coordinate a, Coordinate b, Set<String> ignored) {
        return forbidGrid.hit(a, b, ignored, false);
    }

    private Area mitre(Zone zone) {
        Area area = zone.mitre;
        if (area == null) {
            area = new Area(zone(zone.geometry, zone.distance - SIMPLIFY_M));
            zone.mitre = area;
        }
        return area;
    }

    public List<Coordinate> nodes() {
        if (!withNodes) {
            throw new IllegalStateException("набор зон построен без узлов графа");
        }
        return nodes;
    }

    public boolean tangent(int node, Coordinate other) {
        return tangent(node, other.x, other.y);
    }

    boolean tangent(int node, double x, double y) {
        int k = 6 * node;
        if (Double.isNaN(around[k + 2])) {
            return true;
        }
        if (Double.isNaN(around[k + 4])) {
            double px = around[k + 2];
            double py = around[k + 3];
            return x == px && y == py
                    || (x - around[k]) * (px - around[k]) + (y - around[k + 1]) * (py - around[k + 1]) <= 0;
        }
        double dx = x - around[k];
        double dy = y - around[k + 1];
        double prev = dx * (around[k + 3] - around[k + 1]) - dy * (around[k + 2] - around[k]);
        double next = dx * (around[k + 5] - around[k + 1]) - dy * (around[k + 4] - around[k]);
        return prev * next >= -TANGENT_EPS;
    }

    public double edgeWeight(Coordinate a, Coordinate b, Set<String> ignored) {
        return edgeWeight(a, b, ignored, false, false);
    }

    public double edgeWeight(Coordinate a, Coordinate b, Set<String> ignored, boolean aNode, boolean bNode) {
        return edgeWeight(a, b, ignored, aNode, bNode, hint());
    }

    static final class Hint {
        final ZoneGrid.Marks marks = ZoneGrid.MARKS.get();
        int zone = -1;
        int special = -1;
    }

    static Hint hint() {
        return new Hint();
    }

    double edgeWeight(Coordinate a, Coordinate b, Set<String> ignored, boolean aNode, boolean bNode, Hint hint) {
        boolean outside = aNode || bNode;
        if (hint.zone >= 0 && forbidGrid.blocks(hint.zone, a, b, ignored, outside)
                || hint.special >= 0 && blocks(specialOrder[hint.special], a, b, ignored, outside)) {
            return Double.NaN;
        }
        int zone = forbidGrid.zoneHit(a, b, ignored, outside, hint.marks);
        if (zone >= 0) {
            hint.zone = zone;
            return Double.NaN;
        }
        List<Special> crossed = new ArrayList<>();
        List<List<LineSegment>> crossedSides = new ArrayList<>();
        double minX = Math.min(a.x, b.x);
        double maxX = Math.max(a.x, b.x);
        double minY = Math.min(a.y, b.y);
        double maxY = Math.max(a.y, b.y);
        for (int k = 0; k < specialOrder.length; k++) {
            int c = 4 * k;
            if (specialBoxes[c] > maxX || specialBoxes[c + 2] < minX || specialBoxes[c + 1] > maxY || specialBoxes[c + 3] < minY
                    || !hitsBox(specialBoxes[c], specialBoxes[c + 1], specialBoxes[c + 2], specialBoxes[c + 3], a, b)) {
                continue;
            }
            Special special = specialOrder[k];
            if (ignored.contains(special.id) && touches(special, a, b)) {
                if (tieClose(special, a, b, NORM_EXTRA_M - SIMPLIFY_M)) {
                    hint.special = k;
                    return Double.NaN;
                }
                continue;
            }
            List<LineSegment> sides = special.polygon ? null : special.sidesNear(a, b);
            if (special.polygon ? !special.crossedBy(a, b) : !crosses(sides, a, b)) {
                if (special.zone.intersects(a, b, 0, outside)) {
                    hint.special = k;
                    return Double.NaN;
                }
                continue;
            }
            if (sides == null) {
                sides = special.sidesNear(a, b);
            }
            if (!crossingAllowed(special, sides, a, b) || closeBeyond(special, sides, a, b)) {
                hint.special = k;
                return Double.NaN;
            }
            crossed.add(special);
            crossedSides.add(sides);
        }
        if (crossed.isEmpty()) {
            return a.distance(b);
        }
        double length = a.distance(b);
        List<SpecialSpan> raw = new ArrayList<>();
        LineIntersector intersector = new RobustLineIntersector();
        double beforeA = 0;
        double beforeB = 0;
        for (int s = 0; s < crossed.size(); s++) {
            Special special = crossed.get(s);
            List<LineSegment> sides = crossedSides.get(s);
            double extra = special.rule.getKSpecial() - 1;
            double margin = special.rule.getMarginM();
            if (special.polygon) {
                if (aNode) {
                    beforeA = Math.max(beforeA, extra * polygonBeyond(special, a));
                }
                if (bNode) {
                    beforeB = Math.max(beforeB, extra * polygonBeyond(special, b));
                }
                if (length != 0) {
                    for (double[] piece : inside(a, b, special.margin, intersector)) {
                        raw.add(span(piece[0], piece[1], special));
                    }
                }
                continue;
            }
            double nearestA = Double.POSITIVE_INFINITY;
            double nearestB = Double.POSITIVE_INFINITY;
            boolean proper = length != 0;
            if (length != 0) {
                for (LineSegment side : sides) {
                    intersector.computeIntersection(a, b, side.p0, side.p1);
                    for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                        Coordinate point = intersector.getIntersection(k);
                        double at = a.distance(point);
                        nearestA = Math.min(nearestA, at);
                        nearestB = Math.min(nearestB, b.distance(point));
                        proper &= intersector.isProper() && !point.equals2D(a) && !point.equals2D(b)
                                && !point.equals2D(side.p0) && !point.equals2D(side.p1);
                        raw.add(span(Math.max(0, at - margin), Math.min(length, at + margin), special));
                    }
                }
            }
            if (aNode) {
                beforeA = Math.max(beforeA, extra * (length != 0 ? Math.max(0, margin - nearestA) : lineBeyond(special, sides, a, b)));
            }
            if (bNode) {
                beforeB = Math.max(beforeB, extra * (proper ? Math.max(0, margin - nearestB) : lineBeyond(special, sides, b, a)));
            }
        }
        return weight(length, merge(raw)) + beforeA + beforeB;
    }

    private boolean blocks(Special special, Coordinate a, Coordinate b, Set<String> ignored, boolean outside) {
        if (!special.zone.hitsBox(a, b)) {
            return false;
        }
        if (ignored.contains(special.id) && touches(special, a, b)) {
            return tieClose(special, a, b, NORM_EXTRA_M - SIMPLIFY_M);
        }
        List<LineSegment> sides = special.polygon ? null : special.sidesNear(a, b);
        if (special.polygon ? !special.crossedBy(a, b) : !crosses(sides, a, b)) {
            return special.zone.intersects(a, b, 0, outside);
        }
        sides = sides == null ? special.sidesNear(a, b) : sides;
        return !crossingAllowed(special, sides, a, b) || closeBeyond(special, sides, a, b);
    }

    private static boolean closeBeyond(Special special, List<LineSegment> sides, Coordinate a, Coordinate b) {
        double length = a.distance(b);
        if (length == 0) {
            return false;
        }
        double norm = special.zone.distance - SIMPLIFY_M;
        double reach = special.polygon ? special.rule.getMarginM()
                : Math.max(special.rule.getMarginM(), norm + CROSS_CIRCLE_M);
        LineIntersector intersector = new RobustLineIntersector();
        List<double[]> free = special.polygon ? inside(a, b, special.shape, intersector) : new ArrayList<>();
        for (LineSegment side : sides) {
            intersector.computeIntersection(a, b, side.p0, side.p1);
            for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                double at = a.distance(intersector.getIntersection(k));
                free.add(new double[] {at - reach, at + reach});
            }
        }
        free.sort(Comparator.comparingDouble(piece -> piece[0]));
        double from = 0;
        for (double[] piece : free) {
            if (piece[0] > from && closePart(special, a, b, from / length, piece[0] / length)) {
                return true;
            }
            from = Math.max(from, piece[1]);
        }
        return from < length && closePart(special, a, b, from / length, 1);
    }

    private static boolean closePart(Special special, Coordinate a, Coordinate b, double from, double to) {
        Coordinate p = new Coordinate(a.x + (b.x - a.x) * from, a.y + (b.y - a.y) * from);
        Coordinate q = new Coordinate(a.x + (b.x - a.x) * to, a.y + (b.y - a.y) * to);
        return special.zone.intersects(p, q, NORM_EXTRA_M - SIMPLIFY_M, true);
    }

    public boolean plain(Coordinate a, Coordinate b, Set<String> ignored, double margin) {
        return plain(a, b, ignored, margin, new Object[1]);
    }

    public boolean plain(Coordinate a, Coordinate b, Set<String> ignored, double margin, Object[] hint) {
        double extra = margin - SIMPLIFY_M;
        if (hint[0] != null && blocksPlain(hint[0], a, b, ignored, extra)) {
            return false;
        }
        Envelope envelope = new Envelope(a, b);
        envelope.expandBy(margin);
        for (Object item : forbidZones.query(envelope)) {
            if (blocksPlain(item, a, b, ignored, extra)) {
                hint[0] = item;
                return false;
            }
        }
        for (Object item : specials.query(envelope)) {
            if (blocksPlain(item, a, b, ignored, extra)) {
                hint[0] = item;
                return false;
            }
        }
        return true;
    }

    public boolean crossable(Coordinate a, Coordinate b, Set<String> ignored, double margin) {
        double extra = margin - SIMPLIFY_M;
        Envelope envelope = new Envelope(a, b);
        envelope.expandBy(margin);
        for (Object item : forbidZones.query(envelope)) {
            if (blocksPlain(item, a, b, ignored, extra)) {
                return false;
            }
        }
        for (Object item : specials.query(envelope)) {
            Special special = (Special) item;
            if (ignored.contains(special.id) && touches(special, a, b)) {
                if (tieClose(special, a, b, extra)) {
                    return false;
                }
                continue;
            }
            List<LineSegment> sides = special.sidesNear(a, b);
            boolean crossed = special.polygon ? special.crossedBy(a, b) : crosses(sides, a, b);
            if (crossed ? !crossingAllowed(special, sides, a, b) : special.zone.intersects(a, b, extra, false)) {
                return false;
            }
        }
        return true;
    }

    private boolean blocksPlain(Object item, Coordinate a, Coordinate b, Set<String> ignored, double extra) {
        if (item instanceof Zone) {
            Zone zone = (Zone) item;
            return !ignored.contains(zone.id) && zone.intersects(a, b, extra, false);
        }
        Special special = (Special) item;
        if (ignored.contains(special.id) && touches(special, a, b)) {
            return tieClose(special, a, b, extra);
        }
        return special.crossedBy(a, b) || special.zone.intersects(a, b, extra, false);
    }

    private double polygonBeyond(Special special, Coordinate end) {
        Double known = special.beyond.get(end);
        if (known == null) {
            known = Math.max(0, special.rule.getMarginM() - polygonDistance(special, end));
            special.beyond.put(end, known);
        }
        return known;
    }

    private double polygonDistance(Special special, Coordinate end) {
        double margin = special.rule.getMarginM();
        Geometry geometry = special.object.getGeometry();
        Envelope box = geometry.getEnvelopeInternal();
        if (Router.apart(end, end, new Coordinate(box.getMinX(), box.getMinY()),
                new Coordinate(box.getMaxX(), box.getMaxY()), margin)) {
            return Double.POSITIVE_INFINITY;
        }
        if (!(geometry instanceof Polygon) || ((Polygon) geometry).getNumInteriorRing() != 0) {
            return geometry.distance(factory.createPoint(end));
        }
        if (special.shape.contains(end)) {
            return 0;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (LineSegment side : special.sides) {
            if (!Router.apart(end, end, side.p0, side.p1, margin)) {
                nearest = Math.min(nearest, Distance.pointToSegment(end, side.p0, side.p1));
            }
        }
        return nearest;
    }

    private static double lineBeyond(Special special, List<LineSegment> sides, Coordinate end, Coordinate other) {
        double nearest = Double.POSITIVE_INFINITY;
        LineIntersector intersector = new RobustLineIntersector();
        for (LineSegment side : sides) {
            intersector.computeIntersection(end, other, side.p0, side.p1);
            for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                nearest = Math.min(nearest, end.distance(intersector.getIntersection(k)));
            }
        }
        return Math.max(0, special.rule.getMarginM() - nearest);
    }

    public boolean nearIgnored(Coordinate a, Coordinate b, Set<String> ignored, double tol) {
        Envelope envelope = new Envelope(a, b);
        envelope.expandBy(tol);
        for (Object item : specials.query(envelope)) {
            Special special = (Special) item;
            if (ignored.contains(special.id) && special.near(a, b, tol)) {
                return true;
            }
        }
        return false;
    }

    public List<Coordinate> departures(Coordinate c, Set<String> ignored) {
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate d : rays(c, ignored)) {
            if (!insideAnyZone(d) && !insideAny(margins, d)) {
                result.add(d);
            }
        }
        return result;
    }

    public List<Coordinate> crossingDepartures(Coordinate c, Set<String> ignored) {
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate d : rays(c, ignored)) {
            if (!insideAnyZone(d) && !insideAny(margins, d)) {
                continue;
            }
            double start = c.distance(d);
            double heading = Math.atan2(d.y - c.y, d.x - c.x);
            for (double turn : CROSSING_DEPARTURE_TURNS_DEG) {
                double ux = Math.cos(heading + Math.toRadians(turn));
                double uy = Math.sin(heading + Math.toRadians(turn));
                for (double along = start + CROSSING_DEPARTURE_STEP_M; along <= CROSSING_DEPARTURE_MAX_M;
                        along += CROSSING_DEPARTURE_STEP_M) {
                    Coordinate p = new Coordinate(c.x + ux * along, c.y + uy * along);
                    if (!area.contains(p) || forbidGrid.covers(p)) {
                        break;
                    }
                    if (!insideSpecialZone(p) && !insideAny(margins, p)) {
                        result.add(p);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private List<Coordinate> rays(Coordinate c, Set<String> ignored) {
        if (ignored.isEmpty()) {
            return List.of();
        }
        Envelope envelope = new Envelope(c);
        envelope.expandBy(TIE_IN_TOUCH_M);
        List<LineSegment> sides = new ArrayList<>();
        double reach = 0;
        for (Object item : specials.query(envelope)) {
            Special special = (Special) item;
            if (special.polygon || !ignored.contains(special.id)) {
                continue;
            }
            for (LineSegment side : special.sides) {
                if (side.distance(c) <= TIE_IN_TOUCH_M) {
                    sides.add(side);
                    reach = Math.max(reach, special.zone.distance + CROSS_CIRCLE_M + NODE_OFFSET_M);
                }
            }
        }
        return departures(c, sides, reach);
    }

    public static List<Coordinate> departures(Coordinate c, List<LineSegment> sides, double reach) {
        List<Double> rays = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        double off = 0;
        for (LineSegment side : sides) {
            Coordinate foot = side.closestPoint(c);
            off = Math.max(off, foot.distance(c));
            double heading = side.angle();
            angles.add(heading + Math.PI / 2);
            angles.add(heading - Math.PI / 2);
            for (Coordinate end : new Coordinate[] {side.p0, side.p1}) {
                if (end.distance(foot) > DEPARTURE_RAY_M) {
                    rays.add(Math.atan2(end.y - foot.y, end.x - foot.x));
                }
            }
        }
        java.util.Collections.sort(rays);
        for (int k = 0; k < rays.size(); k++) {
            double from = rays.get(k);
            double gap = (k + 1 < rays.size() ? rays.get(k + 1) : rays.get(0) + 2 * Math.PI) - from;
            if (gap >= DEPARTURE_GAP_RAD) {
                angles.add(from + gap / 2);
            }
        }
        List<Coordinate> result = new ArrayList<>();
        List<Double> kept = new ArrayList<>();
        double length = reach + off;
        for (double angle : angles) {
            if (kept.stream().noneMatch(other -> Math.abs(Angle.diff(other, angle)) < DEPARTURE_SAME_RAD)) {
                kept.add(angle);
                result.add(new Coordinate(c.x + Math.cos(angle) * length, c.y + Math.sin(angle) * length));
            }
        }
        return result;
    }

    public boolean alongIgnored(Coordinate a, Coordinate b, Set<String> ignored) {
        double length = a.distance(b);
        if (ignored.isEmpty() || length <= ALONG_SKIP_M) {
            return false;
        }
        LineString away = factory.createLineString(new Coordinate[] {new LineSegment(a, b).pointAlong(ALONG_SKIP_M / length), b});
        for (Object item : specials.query(away.getEnvelopeInternal())) {
            Special special = (Special) item;
            if (ignored.contains(special.id) && special.near(away.getCoordinateN(0), b, ALONG_TOL_M)) {
                return true;
            }
        }
        return false;
    }

    public List<SpecialSpan> spans(LineString line, Set<String> ignored) {
        Coordinate[] coords = line.getCoordinates();
        Object[] last = lastSpans.get();
        if (last != null && Arrays.equals(coords, (Coordinate[]) last[0]) && ignored.equals(last[1])) {
            @SuppressWarnings("unchecked")
            List<SpecialSpan> known = (List<SpecialSpan>) last[2];
            return known;
        }
        List<Special> crossed = new ArrayList<>();
        for (Object item : specials.query(line.getEnvelopeInternal())) {
            Special special = (Special) item;
            if (special.object.intersects(line)) {
                crossed.add(special);
            }
        }
        List<SpecialSpan> result = spans(line, crossed, ignored);
        lastSpans.set(new Object[] {coords.clone(), new java.util.HashSet<>(ignored), result});
        return result;
    }

    public static double weight(double length, List<SpecialSpan> spans) {
        double weight = length;
        for (SpecialSpan span : spans) {
            weight += (span.getKSpecial() - 1) * (span.getToM() - span.getFromM());
        }
        return weight;
    }

    private List<SpecialSpan> spans(LineString line, List<Special> crossed, Set<String> ignored) {
        List<SpecialSpan> raw = new ArrayList<>();
        Coordinate[] coords = line.getCoordinates();
        double total = line.getLength();
        LineIntersector intersector = new RobustLineIntersector();
        for (Special special : crossed) {
            boolean skipAtEnds = ignored.contains(special.id);
            if (skipAtEnds && special.polygon && touches(special, coords[0], coords[coords.length - 1])) {
                continue;
            }
            double margin = special.rule.getMarginM();
            double start = 0;
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate p0 = coords[i];
                Coordinate p1 = coords[i + 1];
                double length = p0.distance(p1);
                if (length == 0) {
                    continue;
                }
                if (special.polygon) {
                    for (double[] piece : inside(p0, p1, special.shape, intersector)) {
                        raw.add(span(start + piece[0], start + piece[1], special));
                    }
                }
                for (LineSegment side : special.sidesNear(p0, p1)) {
                    intersector.computeIntersection(p0, p1, side.p0, side.p1);
                    for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                        double at = start + p0.distance(intersector.getIntersection(k));
                        if (skipAtEnds && !special.polygon && (at <= TIE_IN_TOUCH_M || at >= total - TIE_IN_TOUCH_M)) {
                            continue;
                        }
                        raw.add(span(Math.max(0, at - margin), Math.min(total, at + margin), special));
                    }
                }
                start += length;
            }
        }
        return merge(raw);
    }

    private static List<double[]> inside(Coordinate p0, Coordinate p1, Area polygon, LineIntersector intersector) {
        double[] cuts = polygon.cuts(p0, p1, intersector);
        List<double[]> result = new ArrayList<>();
        double length = p0.distance(p1);
        for (int k = 1; k < cuts.length; k++) {
            double from = cuts[k - 1];
            double to = cuts[k];
            if (to > from) {
                double mid = (from + to) / 2 / length;
                Coordinate middle = new Coordinate(p0.x + (p1.x - p0.x) * mid, p0.y + (p1.y - p0.y) * mid);
                if (polygon.contains(middle)) {
                    if (!result.isEmpty() && result.get(result.size() - 1)[1] == from) {
                        result.get(result.size() - 1)[1] = to;
                    } else {
                        result.add(new double[] {from, to});
                    }
                }
            }
        }
        return result;
    }

    private static SpecialSpan span(double fromM, double toM, Special special) {
        return new SpecialSpan(fromM, toM, special.id, special.type, special.rule.getKSpecial());
    }

    private static List<SpecialSpan> merge(List<SpecialSpan> raw) {
        raw.sort(Comparator.comparingDouble(SpecialSpan::getFromM));
        List<SpecialSpan> merged = new ArrayList<>();
        SpecialSpan current = null;
        for (SpecialSpan span : raw) {
            if (current != null && span.getFromM() <= current.getToM() + SPAN_JOIN_M) {
                SpecialSpan top = span.getKSpecial() > current.getKSpecial() ? span : current;
                current = new SpecialSpan(current.getFromM(), Math.max(current.getToM(), span.getToM()),
                        top.getObjectId(), top.getType(), top.getKSpecial());
            } else {
                if (current != null) {
                    merged.add(current);
                }
                current = span;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }

    private static boolean crossingAllowed(Special special, List<LineSegment> sides, Coordinate a, Coordinate b) {
        Double minAngleDeg = special.rule.getMinAngleDeg();
        boolean sideCrossed = false;
        for (LineSegment side : sides) {
            int found = intersectionNum(a, b, side.p0, side.p1);
            if (found == LineIntersector.NO_INTERSECTION) {
                continue;
            }
            if (found == LineIntersector.COLLINEAR_INTERSECTION) {
                return false;
            }
            if (minAngleDeg != null && acuteAngleDeg(a, b, side) < minAngleDeg + ANGLE_MARGIN_DEG) {
                return false;
            }
            sideCrossed = true;
        }
        return sideCrossed;
    }

    private static double acuteAngleDeg(Coordinate a, Coordinate b, LineSegment side) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = side.p1.x - side.p0.x;
        double vy = side.p1.y - side.p0.y;
        return Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), Math.abs(ux * vx + uy * vy)));
    }

    private boolean touches(Special special, Coordinate a, Coordinate b) {
        return special.near(a, a, TIE_IN_TOUCH_M) || special.near(b, b, TIE_IN_TOUCH_M);
    }

    private static boolean tieClose(Special special, Coordinate a, Coordinate b, double extra) {
        double length = a.distance(b);
        if (special.polygon || length == 0) {
            return false;
        }
        double free = (special.zone.distance + extra + CROSS_CIRCLE_M) / length;
        double from = special.near(a, a, TIE_IN_TOUCH_M) ? free : 0;
        double to = special.near(b, b, TIE_IN_TOUCH_M) ? 1 - free : 1;
        if (from >= to) {
            return false;
        }
        Coordinate p = new Coordinate(a.x + (b.x - a.x) * from, a.y + (b.y - a.y) * from);
        Coordinate q = new Coordinate(a.x + (b.x - a.x) * to, a.y + (b.y - a.y) * to);
        return special.zone.intersects(p, q, extra, true);
    }

    private boolean insideAnyZone(Coordinate c) {
        return forbidGrid.covers(c) || insideSpecialZone(c);
    }

    private boolean insideSpecialZone(Coordinate c) {
        Envelope envelope = new Envelope(c);
        for (Object item : specials.query(envelope)) {
            if (((Special) item).zone.covers(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(Geometry geometry, double distance, Envelope area) {
        Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
        envelope.expandBy(distance);
        return envelope.intersects(area);
    }

    private Geometry zone(Geometry geometry, double distance) {
        java.util.function.Supplier<Geometry> build = () -> TopologyPreservingSimplifier.simplify(
                BufferOp.bufferOp(geometry, distance + SIMPLIFY_M, ZONE_BUFFER), SIMPLIFY_M);
        return cache == null ? build.get()
                : cache.computeIfAbsent(List.of(ZONE_KEY, geometry, distance), ZONE_BYTES * geometry.getNumPoints(), build);
    }

    private Geometry nodeZone(Geometry geometry, double distance) {
        return zone(geometry, distance + 2 * SIMPLIFY_M + NODE_OFFSET_M);
    }

    private List<Geometry> nodeZones(List<Geometry> objects, List<Double> distances) {
        return PARTS.submit(() -> java.util.stream.IntStream.range(0, objects.size()).parallel()
                .mapToObj(i -> nodeZone(objects.get(i), distances.get(i)))
                .collect(java.util.stream.Collectors.toList())).join();
    }

    private static double nodeOffset(double distance) {
        return distance + 3 * SIMPLIFY_M + NODE_OFFSET_M;
    }

    private static void addConvexVertices(LinearRing ring, boolean shell, double offset, Map<Coordinate, Coordinate[]> out,
            Map<Coordinate, Double> offsets) {
        Coordinate[] coords = ring.getCoordinates();
        int n = coords.length - 1;
        if (n < 3) {
            return;
        }
        int obstacleTurn = shell == Orientation.isCCW(coords) ? Orientation.COUNTERCLOCKWISE : Orientation.CLOCKWISE;
        for (int i = 0; i < n; i++) {
            Coordinate prev = coords[(i + n - 1) % n];
            if (Orientation.index(prev, coords[i], coords[i + 1]) == obstacleTurn
                    && out.putIfAbsent(coords[i], new Coordinate[] {prev, coords[i + 1]}) == null) {
                offsets.put(coords[i], offset);
            }
        }
    }

    private static LineSegment[] points(Geometry puntal) {
        Coordinate[] coords = puntal.getCoordinates();
        LineSegment[] result = new LineSegment[coords.length];
        for (int i = 0; i < coords.length; i++) {
            result[i] = new LineSegment(coords[i], coords[i]);
        }
        return result;
    }

    private static LineSegment[] segments(Geometry lineal) {
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
