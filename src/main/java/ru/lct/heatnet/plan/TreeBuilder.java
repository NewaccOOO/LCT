package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.Route;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.graph.SpecialSpan;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;

final class TreeBuilder {
    private static final Logger log = LoggerFactory.getLogger(TreeBuilder.class);
    static final double MIN_PIECE_M = 1.0;
    static final double MIN_TURN_DEG = 3.0;
    private static final double BEND_STEP_DEG = 0.5;
    private static final double MAX_BEND_DEG = 90;
    static final double SPLIT_LINK_M = 2;
    private static final double TOUCH_M = 0.05;
    private static final double JUNCTION_CLIP_M = 0.15;
    private static final double APART_M = 0.005;
    private static final double SAMPLE_STEP_M = 10;
    private static final double CONNECTION_GAP_M = 3;
    private static final double[] SHIFTS_M = {3, 6, 12, 24};
    private static final double[] SLIDE_STEPS_M = {1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50};
    private static final double SHIFT_M = 6;
    private static final double RIGHT_TOL_DEG = 2;
    private static final double SHIFT_STEP_M = 0.5;
    private static final double LINE_TOL_M = 1e-6;
    private static final double ON_LINE_M = 1e-3;
    private static final double SMALL_BEND_DEG = 30;
    private static final int MOVES_PER_JUNCTION = 3;
    private static final double SPAN_TOUCH_M = 0.08;
    private static final double SPAN_SNAP_M = 0.03;
    private static final double PORTAL_EXTRA_M = 0.3;
    private static final double PORTAL_STEP_M = 0.5;
    private static final double PORTAL_MAX_M = 30;
    private static final int PORTAL_TRIES = 8;
    private static final double SIDE_APART_M = 2.0;
    private static final double ENTRY_STEP_M = 0.05;
    private static final double APPROACH_M = 0.04;
    private static final double ENTRY_TOL_M = 0.05;
    private static final double ENTRY_NEAR_M = 0.1;
    static final double CORNER_M = 1.0;
    private static final double CORNER_RIGHT_DEG = 80;
    private static final double SIMPLIFY_M = 0.05;
    static final int CUT_PASSES = 2;
    static final boolean EXIT_LINK = Boolean.parseBoolean(System.getProperty("heatnet.exitLink", "true"));
    private static final double EXIT_LINK_DEG = 80;
    static final double[] EXIT_LINK_M = {1.5, 3, 6};

    private final int nodeLimit;
    private final Map<String, LineString> networkById;
    private final SpecialObjects specials;
    private final Map<String, ExistingOks> buildingByConnection;
    private final Map<List<Object>, Boolean> leavesOnceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<Coordinate>> entriesByConnection = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<List<Object>, double[]> windowByEntry = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<List<Object>, Boolean> leavesZoneOnceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final GeometryFactory factory = GEOMETRY;

    private static final class Spot {
        final Tree.Node node;
        final Tree.Edge edge;
        final double position;
        final Coordinate point;

        Spot(Tree.Node node, Tree.Edge edge, double position, Coordinate point) {
            this.node = node;
            this.edge = edge;
            this.position = position;
            this.point = point;
        }
    }

    private static final class Attach {
        final ConnectionPoint connection;
        final Coordinate[] branch;
        final Spot spot;
        final double weight;
        final Coordinate target;
        final boolean narrow;
        boolean linked;

        Attach(ConnectionPoint connection, Coordinate[] branch, Spot spot, double weight, Coordinate target, boolean narrow) {
            this.connection = connection;
            this.branch = branch;
            this.spot = spot;
            this.weight = weight;
            this.target = target;
            this.narrow = narrow;
        }
    }

    private static final class Exit {
        final Coordinate point;
        final Router router;
        final int anchor;
        final boolean far;

        Exit(Coordinate point, Router router, int anchor, boolean far) {
            this.point = point;
            this.router = router;
            this.anchor = anchor;
            this.far = far;
        }
    }

    private static final Exit NO_EXIT = new Exit(null, null, Integer.MAX_VALUE, false);

    interface Graphs {
        int dn(List<ConnectionPoint> connections);

        ObstacleSet obstacles(int dn);

        Router router(int dn);
    }

    private static final class Piece {
        final LineSegment segment;
        final Tree.Edge edge;
        final double start;

        Piece(LineSegment segment, Tree.Edge edge, double start) {
            this.segment = segment;
            this.edge = edge;
            this.start = start;
        }
    }

    TreeBuilder(int nodeLimit, Map<String, LineString> networkById, SpecialObjects specials,
            Map<String, ExistingOks> buildingByConnection) {
        this.nodeLimit = nodeLimit;
        this.networkById = networkById;
        this.specials = specials;
        this.buildingByConnection = buildingByConnection;
    }

    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections) {
        return build(router, dn, area, tie, connections, 0, 0);
    }

    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            double chamberPenaltyM, double tieInPenaltyM) {
        return build(router, dn, area, tie, connections, chamberPenaltyM, tieInPenaltyM, false, null);
    }

    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            double chamberPenaltyM, double tieInPenaltyM, boolean fromPortalDirection, Graphs graphs) {
        return build(router, dn, area, tie, connections, chamberPenaltyM, tieInPenaltyM, fromPortalDirection, graphs, false, false);
    }

    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            double chamberPenaltyM, double tieInPenaltyM, boolean fromPortalDirection, Graphs graphs, boolean link,
            boolean anySide) {
        Run run = new Run(router, dn, area, tie, chamberPenaltyM, tieInPenaltyM);
        run.link = link;
        run.anySide = anySide;
        run.fromPortalDirection = fromPortalDirection;
        run.graphs = graphs;
        return run.build(connections);
    }

    Tree buildOnSkeleton(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            List<Coordinate> junctions, int[] junctionParent, double chamberPenaltyM, double tieInPenaltyM, Graphs graphs) {
        Run run = new Run(router, dn, area, tie, chamberPenaltyM, tieInPenaltyM);
        run.graphs = graphs;
        run.link = true;
        Tree tree = run.tree;
        List<Tree.Node> nodes = new ArrayList<>();
        for (int j = 0; j < junctions.size(); j++) {
            Tree.Node parent = junctionParent[j] < 0 ? tree.root : nodes.get(junctionParent[j]);
            Tree.Node node = Tree.Node.junction(junctions.get(j));
            Coordinate[] line = upward(router, junctions.get(j), parent.point, run.ignored);
            if (line == null) {
                log.warn("skeleton: нет маршрута от камеры {} к {}", junctions.get(j), parent.point);
                nodes.add(parent);
                continue;
            }
            tree.edges.add(new Tree.Edge(parent, node, factory.createLineString(line)));
            nodes.add(node);
        }
        run.build(connections);
        pruned(tree);
        return tree;
    }

    private void pruned(Tree tree) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = tree.edges.size() - 1; i >= 0; i--) {
                Tree.Edge edge = tree.edges.get(i);
                if (edge.to.kind == Tree.Kind.JUNCTION && tree.edges.stream().noneMatch(e -> e.from == edge.to)) {
                    tree.edges.remove(i);
                    changed = true;
                }
            }
            for (Tree.Edge up : new ArrayList<>(tree.edges)) {
                Tree.Node middle = up.to;
                if (middle.kind != Tree.Kind.JUNCTION) {
                    continue;
                }
                List<Tree.Edge> down = new ArrayList<>();
                for (Tree.Edge e : tree.edges) {
                    if (e.from == middle) {
                        down.add(e);
                    }
                }
                if (down.size() == 1) {
                    Coordinate[] a = up.line.getCoordinates();
                    Coordinate[] b = down.get(0).line.getCoordinates();
                    Coordinate[] joined = new Coordinate[a.length + b.length - 1];
                    System.arraycopy(a, 0, joined, 0, a.length);
                    System.arraycopy(b, 1, joined, a.length, b.length - 1);
                    int index = tree.edges.indexOf(up);
                    tree.edges.set(index, new Tree.Edge(up.from, down.get(0).to, factory.createLineString(joined)));
                    tree.edges.remove(down.get(0));
                    changed = true;
                    break;
                }
            }
        }
    }

    private Coordinate[] upward(Router router, Coordinate from, Coordinate to, Set<String> ignored) {
        Route route = router.routeToAny(factory.createPoint(from), List.of(factory.createPoint(to)), ignored, true);
        if (route == null) {
            return null;
        }
        Coordinate[] coords = route.getGeometry().getCoordinates();
        Coordinate[] reversed = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            reversed[i] = coords[coords.length - 1 - i];
        }
        reversed[0] = to;
        reversed[reversed.length - 1] = from;
        List<Coordinate> kept = new ArrayList<>(Arrays.asList(reversed));
        for (int i = 1; i + 1 < kept.size(); ) {
            if (kept.get(i).distance(kept.get(i - 1)) < Router.CUT_PIECE_M || kept.get(i).distance(kept.get(i + 1)) < Router.CUT_PIECE_M) {
                kept.remove(i);
            } else {
                i++;
            }
        }
        return kept.toArray(new Coordinate[0]);
    }

    private boolean leavesOnce(ExistingOks building, Coordinate cp, Coordinate exit, double clearance) {
        return leavesOnceCache.computeIfAbsent(List.of(building.getId(), cp.x, cp.y, exit.x, exit.y, clearance),
                key -> leavesOnce(building.getGeometry(), cp, exit, clearance));
    }

    boolean leavesZoneOnce(ExistingOks building, Coordinate cp, Coordinate exit, double clearance) {
        return leavesZoneOnceCache.computeIfAbsent(List.of(building.getId(), cp.x, cp.y, exit.x, exit.y, clearance),
                key -> leavesZoneOnce(building.getGeometry(), cp, exit, clearance));
    }

    Coordinate exit(Router router, int dn, Envelope area, TieCandidate tie, ConnectionPoint connection) {
        ExistingOks building = buildingByConnection.get(connection.getId());
        if (building == null) {
            return null;
        }
        Exit exit = new Run(router, dn, area, tie, 0, 0).exit(connection, building, router.obstacles(), router);
        return exit == NO_EXIT ? null : exit.point;
    }

    boolean leavesNetwork(LineSegment tail, Set<String> ignored) {
        double skip = TieInFinder.TOUCH_M / tail.getLength();
        if (skip >= 1) {
            return false;
        }
        LineString away = factory.createLineString(new Coordinate[] {tail.p0, tail.pointAlong(1 - skip)});
        for (String id : ignored) {
            LineString network = networkById.get(id);
            if (network != null && network.intersects(away)) {
                return false;
            }
        }
        return true;
    }

    static boolean leavesOnce(Geometry building, Coordinate cp, Coordinate exit, double clearance) {
        Geometry inside = building.intersection(GEOMETRY.createLineString(new Coordinate[] {cp, exit}));
        int pieces = 0;
        for (int i = 0; i < inside.getNumGeometries(); i++) {
            if (inside.getGeometryN(i).getLength() > TOUCH_M) {
                pieces++;
            }
        }
        boolean onWall = pieces == 0 && building.getBoundary().distance(GEOMETRY.createPoint(cp)) <= TOUCH_M;
        return (pieces == 1 || onWall) && leavesZoneOnce(building, cp, exit, clearance)
                && recedes(building, cp, exit, clearance);
    }

    static boolean leavesZoneOnce(Geometry building, Coordinate cp, Coordinate exit, double clearance) {
        List<double[]> spans = new ArrayList<>();
        Geometry boundary = building.getBoundary();
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] ring = boundary.getGeometryN(g).getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                double[] span = near(cp, exit, ring[k], ring[k + 1], clearance);
                if (span != null) {
                    spans.add(span);
                }
            }
        }
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        LineSegment line = new LineSegment(cp, exit);
        double reach = 0;
        for (double[] span : spans) {
            if (span[0] > reach) {
                Coordinate gap = line.pointAlong((reach + span[0]) / 2 / line.getLength());
                if (SimplePointInAreaLocator.locate(gap, building) == Location.EXTERIOR) {
                    return false;
                }
            }
            reach = Math.max(reach, span[1]);
        }
        return true;
    }

    static double[] near(Coordinate p, Coordinate q, Coordinate a, Coordinate b, double distance) {
        double length = p.distance(q);
        double ux = (q.x - p.x) / length;
        double uy = (q.y - p.y) / length;
        double from = Double.POSITIVE_INFINITY;
        double to = Double.NEGATIVE_INFINITY;
        for (Coordinate end : new Coordinate[] {a, b}) {
            double wx = p.x - end.x;
            double wy = p.y - end.y;
            double half = ux * wx + uy * wy;
            double disc = half * half - wx * wx - wy * wy + distance * distance;
            if (disc > 0) {
                from = Math.min(from, -half - Math.sqrt(disc));
                to = Math.max(to, -half + Math.sqrt(disc));
            }
        }
        double side = a.distance(b);
        if (side > 0) {
            double vx = (b.x - a.x) / side;
            double vy = (b.y - a.y) / side;
            double[] along = between((p.x - a.x) * vx + (p.y - a.y) * vy, ux * vx + uy * vy, 0, side);
            double[] across = between(vx * (p.y - a.y) - vy * (p.x - a.x), vx * uy - vy * ux, -distance, distance);
            if (along != null && across != null && Math.max(along[0], across[0]) < Math.min(along[1], across[1])) {
                from = Math.min(from, Math.max(along[0], across[0]));
                to = Math.max(to, Math.min(along[1], across[1]));
            }
        }
        from = Math.max(from, 0);
        to = Math.min(to, length);
        return from < to ? new double[] {from, to} : null;
    }

    private static double[] between(double start, double rate, double low, double high) {
        if (rate == 0) {
            return start >= low && start <= high ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        }
        double first = (low - start) / rate;
        double second = (high - start) / rate;
        return new double[] {Math.min(first, second), Math.max(first, second)};
    }

    static List<Coordinate> entries(Geometry building, Coordinate cp, double limit) {
        List<Coordinate> result = new ArrayList<>();
        for (int g = 0; g < building.getNumGeometries(); g++) {
            Coordinate[] ring = ((org.locationtech.jts.geom.Polygon) building.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                LineSegment side = new LineSegment(ring[k], ring[k + 1]);
                if (side.distance(cp) >= limit) {
                    continue;
                }
                result.add(side.closestPoint(cp));
                int steps = Math.max(1, (int) Math.ceil(side.getLength() / ENTRY_STEP_M));
                for (int i = 0; i <= steps; i++) {
                    result.add(side.pointAlong((double) i / steps));
                }
            }
        }
        result.removeIf(entry -> cp.distance(entry) >= limit);
        result.sort(Comparator.comparingDouble(cp::distance));
        return result;
    }

    static boolean recedes(Geometry building, Coordinate cp, Coordinate exit, double clearance) {
        List<LineSegment> sides = new ArrayList<>();
        Geometry boundary = building.getBoundary();
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] ring = boundary.getGeometryN(g).getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                if (near(cp, exit, ring[k], ring[k + 1], clearance) != null) {
                    sides.add(new LineSegment(ring[k], ring[k + 1]));
                }
            }
        }
        LineSegment line = new LineSegment(cp, exit);
        double length = line.getLength();
        double start = length;
        for (LineSegment side : sides) {
            Coordinate cross = line.intersection(side);
            if (cross != null) {
                start = Math.min(start, cp.distance(cross));
            }
        }
        double farthest = 0;
        for (double t = start; t <= length; t += ENTRY_STEP_M) {
            Coordinate at = line.pointAlong(t / length);
            double distance = Double.POSITIVE_INFINITY;
            for (LineSegment side : sides) {
                distance = Math.min(distance, side.distance(at));
            }
            if (distance >= clearance) {
                return true;
            }
            if (distance < farthest - APPROACH_M) {
                return false;
            }
            farthest = Math.max(farthest, distance);
        }
        return true;
    }

    Coordinate[] entered(ExistingOks building, Coordinate[] coords, ObstacleSet zones,
            Set<String> ignored, Coordinate before, List<LineSegment> apart, boolean fromRoot) {
        Geometry polygon = building.getGeometry();
        double inside = farEntry(polygon, coords);
        return Double.isNaN(inside) ? null : relinked(building, coords, zones, ignored, before, apart, fromRoot,
                entries(polygon, coords[coords.length - 1], inside - ENTRY_TOL_M), false, false);
    }

    Coordinate[] evened(ExistingOks building, Coordinate[] coords, ObstacleSet zones, Set<String> ignored,
            Coordinate before, List<LineSegment> apart, boolean fromRoot) {
        int n = coords.length;
        if (n < 3 || !Router.micro(coords[n - 3], coords[n - 2], coords[n - 1])) {
            return null;
        }
        double vertex = length(Arrays.copyOf(coords, n - 1));
        boolean border = false;
        for (SpecialSpan span : zones.spans(factory.createLineString(coords), ignored)) {
            border |= Math.abs(span.getToM() - vertex) <= SPAN_SNAP_M || Math.abs(span.getFromM() - vertex) <= SPAN_SNAP_M;
        }
        Coordinate entry = border ? straightEntry(building.getGeometry(), coords) : null;
        return entry == null ? null
                : relinked(building, coords, zones, ignored, before, apart, fromRoot, List.of(entry), true, true);
    }

    Coordinate[] straightened(ExistingOks building, Coordinate[] coords, ObstacleSet zones,
            Set<String> ignored, Coordinate before, List<LineSegment> apart, boolean fromRoot) {
        Coordinate[] result = null;
        while (coords.length > 2) {
            int n = coords.length;
            double at = length(Arrays.copyOf(coords, n - 2));
            double vertex = at + coords[n - 3].distance(coords[n - 2]);
            double end = 0;
            for (SpecialSpan span : zones.spans(factory.createLineString(coords), ignored)) {
                end = Math.max(end, span.getToM());
            }
            if (end > vertex - SPAN_SNAP_M) {
                return result;
            }
            Coordinate[] from = coords;
            if (end > at + SPAN_SNAP_M) {
                from = new Coordinate[n + 1];
                System.arraycopy(coords, 0, from, 0, n - 2);
                from[n - 2] = new LineSegment(coords[n - 3], coords[n - 2]).pointAlong((end - at) / (vertex - at));
                from[n - 1] = coords[n - 2];
                from[n] = coords[n - 1];
            }
            Coordinate entry = straightEntry(building.getGeometry(), from);
            Coordinate[] line = entry == null ? null
                    : relinked(building, from, zones, ignored, before, apart, fromRoot, List.of(entry), true, false);
            if (line == null) {
                break;
            }
            result = coords = line;
        }
        return result;
    }

    Coordinate[] cornered(ExistingOks building, Coordinate[] coords, ObstacleSet zones, Set<String> ignored,
            Coordinate before, List<LineSegment> apart, boolean fromRoot) {
        int n = coords.length;
        Coordinate cp = coords[n - 1];
        Geometry polygon = building.getGeometry();
        double limit = nearestWall(polygon, cp) + CORNER_M;
        List<Coordinate> entries = new ArrayList<>();
        for (int g = 0; g < polygon.getNumGeometries(); g++) {
            Coordinate[] ring = ((org.locationtech.jts.geom.Polygon) polygon.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                Coordinate entry = new LineSegment(ring[k], ring[k + 1]).closestPoint(cp);
                if (cp.distance(entry) < limit) {
                    entries.add(entry);
                }
            }
        }
        entries.sort(Comparator.comparingDouble(cp::distance));
        for (int keep = 1; keep <= n - 3; keep++) {
            boolean right = false;
            for (int v = Math.max(1, keep - 1); v < n - 1; v++) {
                right |= Router.deflectionDeg(coords[v - 1], coords[v], coords[v + 1]) >= CORNER_RIGHT_DEG;
            }
            Coordinate[] trimmed = Arrays.copyOf(coords, keep + 2);
            trimmed[keep] = coords[n - 2];
            trimmed[keep + 1] = cp;
            Coordinate[] line = relinked(building, trimmed, zones, ignored, before, apart, fromRoot, entries, false, false,
                    right ? CORNER_RIGHT_DEG : MIN_TURN_DEG);
            if (line != null) {
                return line;
            }
        }
        return null;
    }

    static Coordinate straightEntry(Geometry polygon, Coordinate[] coords) {
        int n = coords.length;
        if (n < 3) {
            return null;
        }
        Coordinate cp = coords[n - 1];
        Coordinate a = coords[n - 3];
        double length = a.distance(cp);
        double ux = (a.x - cp.x) / length;
        double uy = (a.y - cp.y) / length;
        double[] out = crossings(rings(polygon), cp, ux, uy, length);
        if (out.length == 0) {
            return null;
        }
        Coordinate nearest = null;
        double nearestM = Double.POSITIVE_INFINITY;
        for (int g = 0; g < polygon.getNumGeometries(); g++) {
            Coordinate[] ring = ((org.locationtech.jts.geom.Polygon) polygon.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                Coordinate q = new LineSegment(ring[k], ring[k + 1]).closestPoint(cp);
                double m = q.distance(cp);
                if (nearest == null || m < nearestM) {
                    nearest = q;
                    nearestM = m;
                }
            }
        }
        Coordinate entry = new Coordinate(cp.x + ux * out[0], cp.y + uy * out[0]);
        return entry.distance(nearest) <= ENTRY_NEAR_M ? entry : null;
    }

    private Coordinate[] relinked(ExistingOks building, Coordinate[] coords, ObstacleSet zones, Set<String> ignored,
            Coordinate before, List<LineSegment> apart, boolean fromRoot, List<Coordinate> entries, boolean straight,
            boolean across) {
        return relinked(building, coords, zones, ignored, before, apart, fromRoot, entries, straight, across, MIN_TURN_DEG);
    }

    private Coordinate[] relinked(ExistingOks building, Coordinate[] coords, ObstacleSet zones, Set<String> ignored,
            Coordinate before, List<LineSegment> apart, boolean fromRoot, List<Coordinate> entries, boolean straight,
            boolean across, double minTurn) {
        int n = coords.length;
        Coordinate cp = coords[n - 1];
        List<Coordinate[]> rings = rings(building.getGeometry());
        Coordinate[] head = Arrays.copyOf(coords, Math.max(1, n - 2));
        Coordinate a = head[head.length - 1];
        Coordinate pre = head.length > 1 ? head[head.length - 2] : before;
        List<LineSegment> others = new ArrayList<>(apart);
        for (int i = 0; i + 1 < head.length; i++) {
            others.add(new LineSegment(head[i], head[i + 1]));
        }
        Set<String> own = new HashSet<>(fromRoot && head.length == 1 && straight ? ignored : Set.of());
        own.add(building.getId());
        for (Coordinate entry : entries) {
            double r = cp.distance(entry);
            if (r < 1e-6) {
                continue;
            }
            double ux = (entry.x - cp.x) / r;
            double uy = (entry.y - cp.y) / r;
            double reach = (a.x - cp.x) * ux + (a.y - cp.y) * uy;
            double[] window = reach > r ? window(zones, building, rings, cp, entry) : new double[0];
            if (window.length == 0) {
                continue;
            }
            reach = Math.min(reach, window[1]);
            for (double t = window[0]; t < reach; t += ENTRY_STEP_M) {
                Coordinate exit = new Coordinate(cp.x + ux * t, cp.y + uy * t);
                if (zones.insideForbid(exit, false)) {
                    break;
                }
                double turn = Router.deflectionDeg(a, exit, cp);
                if (!straight && (a.distance(exit) < Router.CUT_PIECE_M || turn < minTurn || turn > Router.MAX_TURN_DEG)
                        || pre != null && Router.deflectionDeg(pre, a, exit) > Router.MAX_TURN_DEG) {
                    continue;
                }
                if (Double.isNaN(zones.edgeWeight(cp, exit, own))) {
                    break;
                }
                if (zones.covers(a, exit) && (across ? zones.crossable(a, exit, ignored, Router.CUT_MARGIN_M)
                        : zones.plain(a, exit, ignored, Router.CUT_MARGIN_M))
                        && (!fromRoot || head.length > 1 || leavesNetwork(new LineSegment(exit, a), ignored))
                        && (straight ? Router.apart(new LineSegment(a, cp), others)
                        : Router.apart(new LineSegment(a, exit), others) && Router.apart(new LineSegment(exit, cp), others))) {
                    Coordinate[] result = Arrays.copyOf(head, head.length + (straight ? 1 : 2));
                    result[result.length - 1] = cp;
                    if (!straight) {
                        result[head.length] = exit;
                    }
                    return result;
                }
            }
        }
        return null;
    }

    private double[] window(ObstacleSet zones, ExistingOks building, List<Coordinate[]> rings, Coordinate cp, Coordinate entry) {
        return windowByEntry.computeIfAbsent(List.of(zones, building.getId(), cp.x, cp.y, entry.x, entry.y), key -> {
            double r = cp.distance(entry);
            if (r < 1e-6) {
                return new double[0];
            }
            double ux = (entry.x - cp.x) / r;
            double uy = (entry.y - cp.y) / r;
            double clearance = zones.oksClearance();
            double far = reach(rings, cp, entry, clearance + SIMPLIFY_M, 3 * PORTAL_MAX_M);
            double t = r + ENTRY_STEP_M * Math.ceil((clearance + SIMPLIFY_M) / ENTRY_STEP_M);
            while (t < far && zones.insideForbid(new Coordinate(cp.x + ux * t, cp.y + uy * t), false)) {
                t += ENTRY_STEP_M;
            }
            double limit = Math.min(far, t + PORTAL_MAX_M);
            Coordinate first = new Coordinate(cp.x + ux * t, cp.y + uy * t);
            return t < limit && leavesOnce(building, cp, first, clearance)
                    ? new double[] {t, limit} : new double[0];
        });
    }

    static double reach(List<Coordinate[]> rings, Coordinate cp, Coordinate entry, double zone) {
        return reach(rings, cp, entry, zone, PORTAL_MAX_M);
    }

    static double reach(List<Coordinate[]> rings, Coordinate cp, Coordinate entry, double zone, double beyond) {
        double r = cp.distance(entry);
        double limit = r + zone + beyond;
        for (double hit : crossings(rings, cp, (entry.x - cp.x) / r, (entry.y - cp.y) / r, limit)) {
            if (hit > r + TOUCH_M) {
                return Math.min(limit, hit - zone);
            }
        }
        return limit;
    }

    static double farEntry(Geometry polygon, Coordinate[] coords) {
        int n = coords.length;
        Coordinate cp = coords[n - 1];
        double nearest = nearestWall(polygon, cp);
        double length = coords[n - 2].distance(cp);
        double[] out = crossings(rings(polygon), cp, (coords[n - 2].x - cp.x) / length, (coords[n - 2].y - cp.y) / length, length);
        return out.length > 0 && out[0] > nearest + ENTRY_TOL_M ? out[0] : Double.NaN;
    }

    static double[] outward(Geometry polygon, Coordinate cp, Coordinate anchor) {
        for (Coordinate[] ring : rings(polygon)) {
            for (int k = 0; k + 1 < ring.length; k++) {
                LineSegment side = new LineSegment(ring[k], ring[k + 1]);
                double length = side.getLength();
                if (length < 1e-9 || side.distance(anchor) > 1e-6) {
                    continue;
                }
                double nx = -(ring[k + 1].y - ring[k].y) / length;
                double ny = (ring[k + 1].x - ring[k].x) / length;
                Coordinate probe = new Coordinate(anchor.x + nx * TOUCH_M, anchor.y + ny * TOUCH_M);
                return polygon.covers(GEOMETRY.createPoint(probe)) ? new double[] {-nx, -ny} : new double[] {nx, ny};
            }
        }
        return null;
    }

    static double nearestWall(Geometry polygon, Coordinate cp) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int g = 0; g < polygon.getNumGeometries(); g++) {
            Coordinate[] ring = ((org.locationtech.jts.geom.Polygon) polygon.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                nearest = Math.min(nearest, new LineSegment(ring[k], ring[k + 1]).distance(cp));
            }
        }
        return nearest;
    }

    static List<Coordinate[]> rings(Geometry polygon) {
        List<Coordinate[]> rings = new ArrayList<>();
        for (int g = 0; g < polygon.getNumGeometries(); g++) {
            org.locationtech.jts.geom.Polygon part = (org.locationtech.jts.geom.Polygon) polygon.getGeometryN(g);
            rings.add(part.getExteriorRing().getCoordinates());
            for (int h = 0; h < part.getNumInteriorRing(); h++) {
                rings.add(part.getInteriorRingN(h).getCoordinates());
            }
        }
        return rings;
    }

    static double[] crossings(List<Coordinate[]> rings, Coordinate cp, double ux, double uy, double limit) {
        List<Double> found = new ArrayList<>();
        for (Coordinate[] ring : rings) {
            for (int k = 0; k + 1 < ring.length; k++) {
                double ex = ring[k + 1].x - ring[k].x;
                double ey = ring[k + 1].y - ring[k].y;
                double den = ux * ey - uy * ex;
                if (Math.abs(den) < 1e-12) {
                    continue;
                }
                double wx = ring[k].x - cp.x;
                double wy = ring[k].y - cp.y;
                double t = (wx * ey - wy * ex) / den;
                double side = (uy * wx - ux * wy) / den;
                if (side >= 0 && side <= 1 && t > 0 && t <= limit) {
                    found.add(t);
                }
            }
        }
        return found.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    }

    private final class Run {
        final Router router;
        final ObstacleSet obstacles;
        final int dn;
        final Envelope area;
        final Tree tree;
        final Set<String> ignored;
        final double chamberPenaltyM;
        final double tieInPenaltyM;
        final Map<Tree.Edge, List<SpecialSpan>> spansByEdge = new IdentityHashMap<>();
        final Map<Tree.Edge, List<Double>> verticesByEdge = new IdentityHashMap<>();
        final Map<Tree.Edge, List<Point>> targetsByEdge = new IdentityHashMap<>();
        final Map<String, Exit> exitByConnection = new HashMap<>();
        boolean fromPortalDirection;
        int step;
        Set<List<Double>> keys = Set.of();
        List<Point> added = List.of();
        final Map<String, Chosen> chosen = new HashMap<>();
        Graphs graphs;

        Run(Router router, int dn, Envelope area, TieCandidate tie, double chamberPenaltyM, double tieInPenaltyM) {
            this.router = router;
            this.obstacles = router.obstacles();
            this.dn = dn;
            this.area = area;
            this.tree = new Tree(tie);
            this.ignored = tie.getIgnored();
            this.chamberPenaltyM = chamberPenaltyM;
            this.tieInPenaltyM = tieInPenaltyM;
        }

        boolean link;
        boolean anySide;
        boolean turned;

        Tree build(List<ConnectionPoint> connections) {
            List<ConnectionPoint> remaining = new ArrayList<>(connections);
            while (!remaining.isEmpty()) {
                List<Point> targets = targets();
                Set<List<Double>> previous = keys;
                keys = new HashSet<>();
                added = new ArrayList<>();
                for (Point target : targets) {
                    List<Double> key = key(target.getCoordinate(), extra(target));
                    keys.add(key);
                    if (!previous.contains(key)) {
                        added.add(target);
                    }
                }
                step++;
                turned = false;
                Attach best = null;
                for (ConnectionPoint connection : remaining) {
                    Attach attach = attach(connection, targets, fromPortalDirection);
                    if (attach != null && (best == null || attach.weight < best.weight)) {
                        best = attach;
                    }
                }
                tree.turnStuck |= best == null && turned;
                if (best == null) {
                    tree.unconnected.addAll(remaining);
                    break;
                }
                apply(best);
                remaining.remove(best.connection);
            }
            return tree;
        }

        Exit portal(ConnectionPoint connection) {
            ExistingOks building = buildingByConnection.get(connection.getId());
            if (building == null) {
                return null;
            }
            return exitByConnection.computeIfAbsent(connection.getId(), id -> {
                Exit wide = exit(connection, building, obstacles, router);
                if (graphs == null || wide.anchor == 1) {
                    return wide;
                }
                int leaf = graphs.dn(List.of(connection));
                if (leaf >= dn) {
                    return wide;
                }
                Exit tight = exit(connection, building, graphs.obstacles(leaf), null);
                if (tight.anchor >= wide.anchor) {
                    return wide;
                }
                log.debug("portal: {} anchor {} by DN{} instead of {} by DN{}", id, tight.anchor, leaf,
                        wide == NO_EXIT ? "none" : wide.anchor, dn);
                return new Exit(tight.point, graphs.router(leaf), tight.anchor, tight.far);
            });
        }

        Exit exit(ConnectionPoint connection, ExistingOks building, ObstacleSet zones, Router branchRouter) {
            return exit(connection, building, zones, branchRouter, 0);
        }

        Exit exit(ConnectionPoint connection, ExistingOks building, ObstacleSet zones, Router branchRouter, int after) {
            Coordinate cp = connection.getGeometry().getCoordinate();
            List<Coordinate> anchors = anchors(building.getGeometry(), cp);
            Set<String> own = new HashSet<>(Set.of(building.getId()));
            double near = nearestWall(building.getGeometry(), cp) + CORNER_M;
            Exit far = null;
            for (boolean step : new boolean[] {false, true}) {
                int tries = 0;
                Coordinate last = null;
                for (Coordinate anchor : anchors) {
                    if (last != null && anchor.distance(last) < MIN_PIECE_M) {
                        continue;
                    }
                    last = anchor;
                    if (tries++ >= PORTAL_TRIES || far != null && tries >= far.anchor) {
                        break;
                    }
                    if (tries <= after) {
                        continue;
                    }
                    Coordinate exit = anchorExit(connection, building, zones, cp, anchor, own, tries, step);
                    if (exit == null) {
                        continue;
                    }
                    if (tries > 1) {
                        log.debug("portal: {} anchor {} of {}", connection.getId(), tries, anchors.size());
                    }
                    Exit found = new Exit(exit, branchRouter, tries, cp.distance(anchor) > near);
                    if (step || !found.far) {
                        return found;
                    }
                    far = found;
                    break;
                }
            }
            if (far != null) {
                return far;
            }
            List<Coordinate> entries = entriesByConnection.computeIfAbsent(connection.getId(),
                    id -> entries(building.getGeometry(), cp, Double.POSITIVE_INFINITY));
            List<Coordinate[]> rings = rings(building.getGeometry());
            for (int i = 0; i < entries.size(); i++) {
                Coordinate exit = PORTAL_TRIES + 1 + i <= after ? null : entryExit(zones, building, rings, cp, entries.get(i), own);
                if (exit != null) {
                    log.debug("portal: {} entry {} at {} m", connection.getId(), i + 1, cp.distance(entries.get(i)));
                    return new Exit(exit, branchRouter, PORTAL_TRIES + 1 + i, cp.distance(entries.get(i)) > near);
                }
            }
            return NO_EXIT;
        }

        private Coordinate anchorExit(ConnectionPoint connection, ExistingOks building, ObstacleSet zones, Coordinate cp,
                Coordinate anchor, Set<String> own, int tries, boolean stepped) {
            double dx = anchor.x - cp.x;
            double dy = anchor.y - cp.y;
            double length = Math.hypot(dx, dy);
            if (length < TOUCH_M) {
                double[] normal = outward(building.getGeometry(), cp, anchor);
                if (normal == null) {
                    return null;
                }
                dx = normal[0];
                dy = normal[1];
                length = 1;
            }
            dx /= length;
            dy /= length;
            double start = cp.distance(anchor) + zones.oksClearance() + PORTAL_EXTRA_M;
            double along = beyondSpecial(zones, cp, dx, dy, start, own);
            Coordinate exit = new Coordinate(cp.x + dx * along, cp.y + dy * along);
            for (double extra = 0; zones.insideForbid(exit) && extra < PORTAL_MAX_M; extra += PORTAL_STEP_M) {
                exit = new Coordinate(cp.x + dx * (along + extra), cp.y + dy * (along + extra));
            }
            boolean inForbid = zones.insideForbid(exit);
            boolean inArea = area.contains(exit);
            boolean once = !inForbid && inArea && leavesOnce(building, cp, exit, zones.oksClearance());
            double weight = !once ? Double.NaN : zones.edgeWeight(cp, exit, own);
            double limit = start - PORTAL_EXTRA_M + PORTAL_MAX_M;
            boolean step = stepped && once && Double.isNaN(weight) && clearOfSpecials(zones, cp, exit, own);
            for (double t = cp.distance(exit); step && Double.isNaN(weight); ) {
                t = beyondSpecial(zones, cp, dx, dy, t + PORTAL_STEP_M, own);
                Coordinate further = new Coordinate(cp.x + dx * t, cp.y + dy * t);
                if (t > limit || zones.insideForbid(further) || !area.contains(further)
                        || !leavesOnce(building, cp, further, zones.oksClearance())) {
                    break;
                }
                weight = zones.edgeWeight(cp, further, own);
                exit = further;
            }
            if (Double.isNaN(weight)) {
                log.debug("portal: {} anchor {} rejected: forbid={} area={} once={} along={}", connection.getId(),
                        tries, inForbid, inArea, once, along);
                return null;
            }
            return exit;
        }

        private Coordinate entryExit(ObstacleSet zones, ExistingOks building, List<Coordinate[]> rings, Coordinate cp,
                Coordinate entry, Set<String> own) {
            double[] window = window(zones, building, rings, cp, entry);
            if (window.length == 0) {
                return null;
            }
            double r = cp.distance(entry);
            double ux = (entry.x - cp.x) / r;
            double uy = (entry.y - cp.y) / r;
            Coordinate exit = new Coordinate(cp.x + ux * window[0], cp.y + uy * window[0]);
            if (!area.contains(exit) || Double.isNaN(zones.edgeWeight(cp, exit, own))) {
                return null;
            }
            double along = beyondSpecial(zones, cp, ux, uy, window[0], own);
            exit = new Coordinate(cp.x + ux * along, cp.y + uy * along);
            return along <= window[1] && area.contains(exit) && !Double.isNaN(zones.edgeWeight(cp, exit, own)) ? exit : null;
        }

        boolean clearOfSpecials(ObstacleSet zones, Coordinate cp, Coordinate exit, Set<String> own) {
            return zones.spans(factory.createLineString(new Coordinate[] {cp, exit}), own).isEmpty();
        }

        double beyondSpecial(ObstacleSet zones, Coordinate cp, double ux, double uy, double along, Set<String> own) {
            Coordinate exit = new Coordinate(cp.x + ux * along, cp.y + uy * along);
            List<SpecialSpan> before = zones.spans(factory.createLineString(new Coordinate[] {cp, exit}), own);
            if (before.isEmpty() || before.get(before.size() - 1).getToM() < cp.distance(exit) - TOUCH_M) {
                return along;
            }
            double reach = along + PORTAL_MAX_M;
            Coordinate far = new Coordinate(cp.x + ux * reach, cp.y + uy * reach);
            for (SpecialSpan span : zones.spans(factory.createLineString(new Coordinate[] {cp, far}), own)) {
                if (span.getFromM() <= along && along <= span.getToM()) {
                    along = span.getToM() + PORTAL_EXTRA_M;
                }
            }
            return along;
        }

        List<Coordinate> anchors(Geometry building, Coordinate cp) {
            List<Coordinate> shells = new ArrayList<>();
            List<Coordinate> holes = new ArrayList<>();
            for (int g = 0; g < building.getNumGeometries(); g++) {
                org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) building.getGeometryN(g);
                closest(polygon.getExteriorRing().getCoordinates(), cp, shells);
                for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                    closest(polygon.getInteriorRingN(h).getCoordinates(), cp, holes);
                }
            }
            shells.sort(Comparator.comparingDouble(cp::distance));
            holes.sort(Comparator.comparingDouble(cp::distance));
            shells.addAll(holes);
            return shells;
        }

        void closest(Coordinate[] ring, Coordinate cp, List<Coordinate> out) {
            for (int k = 0; k + 1 < ring.length; k++) {
                out.add(new LineSegment(ring[k], ring[k + 1]).closestPoint(cp));
            }
        }

        Set<String> firstIgnored(ConnectionPoint connection) {
            ExistingOks building = buildingByConnection.get(connection.getId());
            if (building == null) {
                return ignored;
            }
            Set<String> own = new HashSet<>(ignored);
            own.add(building.getId());
            return own;
        }

        Attach attach(ConnectionPoint connection, List<Point> targets, boolean fromPortalDirection) {
            if (targets.isEmpty()) {
                return null;
            }
            Exit portal = portal(connection);
            boolean far = portal != null && portal.far;
            Attach straight = far ? direct(connection, targets) : null;
            if (straight != null) {
                return straight;
            }
            Attach attach = portal == NO_EXIT ? null : portalAttach(connection, targets, fromPortalDirection, portal);
            return attach != null || far ? attach : direct(connection, targets);
        }

        Attach direct(ConnectionPoint connection, List<Point> targets) {
            ExistingOks building = buildingByConnection.get(connection.getId());
            if (building == null) {
                return null;
            }
            Coordinate cp = connection.getGeometry().getCoordinate();
            Set<String> firstIgnored = firstIgnored(connection);
            List<Piece> pieces = null;
            Attach best = null;
            for (Point target : targets) {
                Coordinate t = target.getCoordinate();
                if (cp.distance(t) < MIN_PIECE_M || !area.contains(t)) {
                    continue;
                }
                double extra = target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
                double weight = obstacles.edgeWeight(cp, t, firstIgnored);
                if (Double.isNaN(weight) || best != null && weight + extra >= best.weight
                        || !leavesOnce(building.getGeometry(), cp, t, obstacles.oksClearance())
                        || !Double.isNaN(farEntry(building.getGeometry(), new Coordinate[] {t, cp}))) {
                    continue;
                }
                pieces = pieces == null ? pieces() : pieces;
                Attach attach = attach(connection, new Coordinate[] {cp, t}, weight + extra, firstIgnored, pieces, router);
                if (attach != null) {
                    best = attach;
                }
            }
            return best;
        }

        Attach portalAttach(ConnectionPoint connection, List<Point> targets, boolean fromPortalDirection, Exit portal) {
            Attach attach = attach(connection, targets, fromPortalDirection, portal);
            if (attach != null || !anySide || portal == null) {
                return attach;
            }
            ExistingOks building = buildingByConnection.get(connection.getId());
            List<Coordinate> tried = new ArrayList<>(List.of(portal.point));
            for (Exit next = exit(connection, building, obstacles, router, portal.anchor);
                    next != NO_EXIT && attach == null && tried.size() <= PORTAL_TRIES;
                    next = exit(connection, building, obstacles, router, next.anchor)) {
                Coordinate at = next.point;
                if (tried.stream().anyMatch(c -> c.distance(at) < SIDE_APART_M)) {
                    continue;
                }
                tried.add(at);
                log.debug("portal: {} next side, anchor {}", connection.getId(), next.anchor);
                attach = attach(connection, targets, fromPortalDirection, next);
            }
            return attach;
        }

        Attach attach(ConnectionPoint connection, List<Point> targets, boolean fromPortalDirection, Exit portal) {
            Router branchRouter = portal == null ? router : portal.router;
            Point start = portal == null ? connection.getGeometry() : factory.createPoint(portal.point);
            Route route;
            if (portal == null || !fromPortalDirection) {
                Router.Choice choice = choice(connection, branchRouter, start.getCoordinate(), targets);
                route = choice == null ? null : branchRouter.route(start, choice, ignored, true);
            } else {
                route = branchRouter.routeExact(start, targets, ignored, connection.getGeometry().getCoordinate(), true);
            }
            if (route == null) {
                return null;
            }
            Coordinate[] coords = route.getGeometry().getCoordinates();
            if (portal != null) {
                Coordinate[] withPortal = new Coordinate[coords.length + 1];
                withPortal[0] = connection.getGeometry().getCoordinate();
                System.arraycopy(coords, 0, withPortal, 1, coords.length);
                coords = withPortal;
            }
            for (Coordinate c : coords) {
                if (!area.contains(c)) {
                    return null;
                }
            }
            Set<String> firstIgnored = firstIgnored(connection);
            List<Piece> pieces = pieces();
            Attach attach = attach(connection, coords, route.getWeight(), firstIgnored, pieces, branchRouter);
            if (attach != null || portal == null || !EXIT_LINK
                    || deflectionDeg(coords[0], coords[1], coords[2]) <= Router.MAX_TURN_DEG) {
                return attach;
            }
            if (!link) {
                turned = true;
                return null;
            }
            for (double length : EXIT_LINK_M) {
                Coordinate[] linked = exitLink(coords, length);
                if (linked == null || !area.contains(linked[2])) {
                    continue;
                }
                double extra = linked[1].distance(linked[2]) + linked[2].distance(linked[3]) - coords[1].distance(coords[2]);
                attach = attach(connection, linked, route.getWeight() + extra, firstIgnored, pieces, branchRouter);
                if (attach != null) {
                    attach.linked = true;
                    return attach;
                }
            }
            return null;
        }

        Attach attach(ConnectionPoint connection, Coordinate[] coords, double weight, Set<String> firstIgnored,
                List<Piece> pieces, Router branchRouter) {
            Coordinate target = coords[coords.length - 1];
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate touch = firstTouch(coords[i], coords[i + 1], pieces);
                if (touch == null) {
                    continue;
                }
                List<Coordinate> head = new ArrayList<>(Arrays.asList(coords).subList(0, i + 1));
                List<Spot> spots = spots(pieces, touch);
                Attach direct = attach(connection, head, spots, pieces, weight, target, firstIgnored, branchRouter);
                double length = coords[i].distance(coords[i + 1]);
                if (direct != null || length == 0) {
                    return direct;
                }
                double nx = (coords[i].y - coords[i + 1].y) / length;
                double ny = (coords[i + 1].x - coords[i].x) / length;
                for (double shift : SHIFTS_M) {
                    for (int side : new int[] {1, -1}) {
                        List<Coordinate> around = new ArrayList<>(head);
                        around.add(new Coordinate(touch.x + side * shift * nx, touch.y + side * shift * ny));
                        Attach aside = attach(connection, around, spots, pieces, weight, target, firstIgnored, branchRouter);
                        if (aside != null) {
                            return aside;
                        }
                    }
                }
                return null;
            }
            return null;
        }

        Router.Choice choice(ConnectionPoint connection, Router branchRouter, Coordinate start, List<Point> targets) {
            Chosen previous = chosen.get(connection.getId());
            Router.Choice choice;
            if (previous == null || previous.step != step - 1 || previous.choice != null
                    && (previous.choice.tied() || !keys.contains(key(previous.choice.target(), previous.choice.extra())))) {
                choice = branchRouter.choose(start, targets, ignored, Double.POSITIVE_INFINITY);
            } else {
                double bound = previous.choice == null ? Double.POSITIVE_INFINITY : Math.nextUp(previous.choice.weight());
                Router.Choice fresh = added.isEmpty() ? null : branchRouter.choose(start, added, ignored, bound);
                if (fresh == null) {
                    choice = previous.choice;
                } else if (previous.choice == null || fresh.weight() < previous.choice.weight()) {
                    choice = fresh;
                } else {
                    choice = branchRouter.choose(start, targets, ignored, Double.POSITIVE_INFINITY);
                }
            }
            chosen.put(connection.getId(), new Chosen(choice, step));
            return choice;
        }

        Attach attach(ConnectionPoint connection, List<Coordinate> head, List<Spot> spots, List<Piece> pieces, double weight,
                Coordinate target, Set<String> firstIgnored, Router branchRouter) {
            ObstacleSet zones = branchRouter.obstacles();
            for (Spot spot : spots) {
                Coordinate[] branch = throughExit(connection, branch(head, spot.point, zones), zones, spot);
                if (branch != null && valid(branch, pieces, spot, firstIgnored, zones)) {
                    return new Attach(connection, branch, spot, weight, target, branchRouter != router);
                }
            }
            return null;
        }

        List<Point> targets() {
            List<Point> targets = new ArrayList<>();
            if (tree.degree(tree.root) < tree.tie.getCapacity()) {
                Point root = factory.createPoint(tree.root.point);
                if (tree.tie.isChamber() && tree.degree(tree.root) >= 1) {
                    root.setUserData(tieInPenaltyM);
                }
                targets.add(root);
            }
            Set<Tree.Node> junctions = new LinkedHashSet<>();
            for (Tree.Edge edge : tree.edges) {
                junctions.add(edge.from);
                junctions.add(edge.to);
            }
            for (Tree.Node node : junctions) {
                if (node.kind == Tree.Kind.JUNCTION && tree.degree(node) < nodeLimit) {
                    targets.add(factory.createPoint(node.point));
                }
            }
            for (Tree.Edge edge : tree.edges) {
                targets.addAll(targetsByEdge.computeIfAbsent(edge, this::edgeTargets));
            }
            return targets;
        }

        List<Point> edgeTargets(Tree.Edge edge) {
            List<Point> targets = new ArrayList<>();
            List<SpecialSpan> spans = spans(edge);
            LengthIndexedLine indexed = new LengthIndexedLine(edge.line);
            List<Double> positions = vertexPositions(edge.line);
            for (double at = SAMPLE_STEP_M; at < edge.line.getLength(); at += SAMPLE_STEP_M) {
                positions.add(at);
            }
            for (double at : positions) {
                if (allowed(edge, at, spans)) {
                    Point target = factory.createPoint(indexed.extractPoint(at));
                    target.setUserData(chamberPenaltyM);
                    targets.add(target);
                }
            }
            return targets;
        }

        List<SpecialSpan> spans(Tree.Edge edge) {
            return spansByEdge.computeIfAbsent(edge, e -> obstacles.spans(e.line, ignored));
        }

        Coordinate firstTouch(Coordinate p, Coordinate q, List<Piece> pieces) {
            LineSegment route = new LineSegment(p, q);
            double best = Double.POSITIVE_INFINITY;
            if (tree.edges.isEmpty() && route.distance(tree.root.point) <= TOUCH_M) {
                best = clamp(route.projectionFactor(tree.root.point));
            }
            for (Piece piece : pieces) {
                LineSegment segment = piece.segment;
                if (Router.apart(p, q, segment.p0, segment.p1, TOUCH_M) || route.distance(segment) > TOUCH_M) {
                    continue;
                }
                List<Coordinate> hits = new ArrayList<>();
                Coordinate cross = route.intersection(segment);
                if (cross != null) {
                    hits.add(cross);
                }
                for (Coordinate c : List.of(p, q)) {
                    if (segment.distance(c) <= TOUCH_M) {
                        hits.add(c);
                    }
                }
                for (Coordinate c : List.of(segment.p0, segment.p1)) {
                    if (route.distance(c) <= TOUCH_M) {
                        hits.add(c);
                    }
                }
                for (Coordinate c : hits) {
                    best = Math.min(best, clamp(route.projectionFactor(c)));
                }
            }
            return best == Double.POSITIVE_INFINITY ? null : route.pointAlong(best);
        }

        List<Spot> spots(List<Piece> pieces, Coordinate touch) {
            List<Spot> spots = new ArrayList<>();
            if (tree.edges.isEmpty()) {
                spots.add(new Spot(tree.root, null, 0, tree.root.point));
                return spots;
            }
            Piece nearest = pieces.get(0);
            for (Piece piece : pieces) {
                if (piece.segment.distance(touch) < nearest.segment.distance(touch)) {
                    nearest = piece;
                }
            }
            Tree.Edge edge = nearest.edge;
            double length = edge.line.getLength();
            double position = nearest.start + clamp(nearest.segment.projectionFactor(touch)) * nearest.segment.getLength();
            Tree.Node node = position <= MIN_PIECE_M ? edge.from : position >= length - MIN_PIECE_M ? edge.to : null;
            if (node != null) {
                if (node.kind != Tree.Kind.CONNECTION && tree.degree(node) < limit(node)) {
                    spots.add(new Spot(node, null, 0, node.point));
                }
                for (double shift : SHIFTS_M) {
                    for (Tree.Edge incident : tree.edges) {
                        if (incident.from == node) {
                            addEdgeSpot(spots, incident, shift);
                        } else if (incident.to == node) {
                            addEdgeSpot(spots, incident, incident.line.getLength() - shift);
                        }
                    }
                }
                return spots;
            }
            for (double vertex : vertexPositions(edge.line)) {
                if (Math.abs(vertex - position) <= MIN_PIECE_M) {
                    position = vertex;
                }
            }
            addEdgeSpot(spots, edge, position);
            for (double shift : SHIFTS_M) {
                addEdgeSpot(spots, edge, position - shift);
                addEdgeSpot(spots, edge, position + shift);
            }
            return spots;
        }

        void addEdgeSpot(List<Spot> spots, Tree.Edge edge, double position) {
            if (allowed(edge, position, spans(edge))) {
                spots.add(new Spot(null, edge, position, new LengthIndexedLine(edge.line).extractPoint(position)));
            }
        }

        boolean allowed(Tree.Edge edge, double position, List<SpecialSpan> spans) {
            return spotAllowed(edge, position, verticesByEdge.computeIfAbsent(edge, e -> vertexPositions(e.line)), spans,
                    obstacles, dn);
        }

        Coordinate[] branch(List<Coordinate> head, Coordinate end, ObstacleSet zones) {
            List<Coordinate> coords = new ArrayList<>(head);
            while (!coords.isEmpty() && coords.get(coords.size() - 1).distance(end) < 1e-6) {
                coords.remove(coords.size() - 1);
            }
            coords.add(end);
            if (coords.size() < 2) {
                return null;
            }
            straighten(coords, zones, ignored);
            return coords.toArray(new Coordinate[0]);
        }

        Coordinate[] throughExit(ConnectionPoint connection, Coordinate[] branch, ObstacleSet zones, Spot spot) {
            ExistingOks building = buildingByConnection.get(connection.getId());
            if (branch == null || building == null || branch.length < 3
                    || deflectionDeg(branch[0], branch[1], branch[2]) >= MIN_TURN_DEG) {
                return branch;
            }
            if (new LineSegment(branch[0], branch[2]).distance(branch[1]) <= ON_LINE_M
                    && !Double.isNaN(zones.edgeWeight(branch[1], branch[2], ignored))) {
                Coordinate[] result = new Coordinate[branch.length - 1];
                result[0] = branch[0];
                System.arraycopy(branch, 2, result, 1, result.length - 1);
                return result;
            }
            Coordinate[] edge = reversed(branch);
            boolean fromRoot = spot.node == tree.root;
            Coordinate[] line = straightened(building, edge, zones, ignored, null, List.of(), fromRoot);
            Coordinate entry = line != null || zones.spans(factory.createLineString(new Coordinate[] {branch[0], branch[1]}), ignored).isEmpty()
                    ? null : straightEntry(building.getGeometry(), edge);
            if (entry != null) {
                line = relinked(building, edge, zones, ignored, null, List.of(), fromRoot, List.of(entry), true, true);
            }
            if (line == null) {
                Coordinate cp = branch[0];
                double length = cp.distance(branch[1]);
                double ux = (branch[1].x - cp.x) / length;
                double uy = (branch[1].y - cp.y) / length;
                double[] out = crossings(rings(building.getGeometry()), cp, ux, uy, length);
                line = out.length == 0 ? null : relinked(building, edge, zones, ignored, null, List.of(), fromRoot,
                        List.of(new Coordinate(cp.x + ux * out[0], cp.y + uy * out[0])), false, false);
            }
            return line == null ? branch : reversed(line);
        }

        boolean valid(Coordinate[] branch, List<Piece> pieces, Spot spot, Set<String> firstIgnored, ObstacleSet zones) {
            for (int i = 1; i + 1 < branch.length; i++) {
                double deflection = deflectionDeg(branch[i - 1], branch[i], branch[i + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < MIN_TURN_DEG) {
                    return false;
                }
            }
            int last = branch.length - 1;
            LineSegment tail = new LineSegment(branch[last - 1], branch[last]);
            if (tail.getLength() <= JUNCTION_CLIP_M) {
                return false;
            }
            for (int i = 0; i + 1 < branch.length; i++) {
                if (Double.isNaN(zones.edgeWeight(branch[i], branch[i + 1], i == 0 ? firstIgnored : ignored))) {
                    return false;
                }
            }
            List<SpecialSpan> spans = zones.spans(factory.createLineString(branch), ignored);
            double at = 0;
            for (int i = 0; i + 1 < branch.length; i++) {
                double length = branch[i].distance(branch[i + 1]);
                if (length < MIN_PIECE_M && !overlaps(spans, at, at + length)) {
                    return false;
                }
                at += length;
            }
            Coordinate[] clipped = branch.clone();
            clipped[last] = tail.pointAlong(1 - JUNCTION_CLIP_M / tail.getLength());
            for (int i = 0; i < last; i++) {
                LineSegment segment = new LineSegment(clipped[i], clipped[i + 1]);
                for (Piece piece : pieces) {
                    if (!Router.apart(clipped[i], clipped[i + 1], piece.segment.p0, piece.segment.p1, APART_M)
                            && segment.distance(piece.segment) <= APART_M) {
                        return false;
                    }
                }
            }
            return spot.node != tree.root || leavesNetwork(tail);
        }

        boolean leavesNetwork(LineSegment tail) {
            return TreeBuilder.this.leavesNetwork(tail, ignored);
        }

        void apply(Attach attach) {
            tree.narrow |= attach.narrow;
            tree.linked |= attach.linked;
            Spot spot = attach.spot;
            Tree.Node at = spot.node;
            if (at == null) {
                at = Tree.Node.junction(spot.point);
                Tree.Edge edge = spot.edge;
                int index = tree.edges.indexOf(edge);
                tree.edges.set(index, new Tree.Edge(edge.from, at, part(edge.line, 0, spot.position, spot.point, false)));
                tree.edges.add(index + 1, new Tree.Edge(at, edge.to,
                        part(edge.line, spot.position, edge.line.getLength(), spot.point, true)));
            }
            Coordinate[] reversed = new Coordinate[attach.branch.length];
            for (int i = 0; i < reversed.length; i++) {
                reversed[i] = attach.branch[attach.branch.length - 1 - i];
            }
            reversed[0] = at.point;
            tree.edges.add(new Tree.Edge(at, Tree.Node.connection(attach.connection), factory.createLineString(reversed)));
        }

        int limit(Tree.Node node) {
            return node == tree.root ? tree.tie.getCapacity() : nodeLimit;
        }

        List<Piece> pieces() {
            List<Piece> pieces = new ArrayList<>();
            for (Tree.Edge edge : tree.edges) {
                Coordinate[] coords = edge.line.getCoordinates();
                double start = 0;
                for (int i = 0; i + 1 < coords.length; i++) {
                    LineSegment segment = new LineSegment(coords[i], coords[i + 1]);
                    pieces.add(new Piece(segment, edge, start));
                    start += segment.getLength();
                }
            }
            return pieces;
        }
    }

    Tree cut(Tree tree, Router router, int dn, Graphs graphs, int passes) {
        Map<Tree.Node, List<ConnectionPoint>> below = new IdentityHashMap<>();
        List<LineString> lines = new ArrayList<>();
        List<Boolean> exits = new ArrayList<>();
        List<ObstacleSet> zones = new ArrayList<>();
        boolean[] narrow = new boolean[tree.edges.size()];
        long points = 0;
        for (Tree.Edge edge : tree.edges) {
            int edgeDn = graphs == null ? dn : graphs.dn(below(tree, edge.to, below));
            narrow[lines.size()] = edgeDn < dn;
            zones.add(edgeDn < dn ? graphs.obstacles(edgeDn) : router.obstacles());
            exits.add(edge.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(edge.to.connection.getId())
                    && edge.line.getNumPoints() > 2);
            lines.add(edge.line);
            points += edge.line.getNumPoints();
        }
        Set<String> ignored = tree.tie.getIgnored();
        Cut cut = router.cached(List.of("treeCut", passes, ignored, lines, exits, zones), 128 * points,
                () -> cut(router, lines, exits, zones, ignored, passes));
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        boolean changed = false;
        for (int e = 0; e < tree.edges.size(); e++) {
            Tree.Edge edge = tree.edges.get(e);
            result.narrow |= cut.changed[e] && narrow[e];
            changed |= cut.changed[e];
            Tree.Node from = edge.from == tree.root ? result.root : edge.from;
            result.edges.add(new Tree.Edge(from, edge.to, factory.createLineString(cut.lines[e].clone())));
        }
        return changed ? result : tree;
    }

    private static final class Cut {
        final Coordinate[][] lines;
        final boolean[] changed;

        Cut(Coordinate[][] lines, boolean[] changed) {
            this.lines = lines;
            this.changed = changed;
        }
    }

    private Cut cut(Router router, List<LineString> lines, List<Boolean> exits, List<ObstacleSet> zones,
            Set<String> ignored, int passes) {
        List<LineString> current = new ArrayList<>(lines);
        Coordinate[][] result = new Coordinate[lines.size()][];
        boolean[] changed = new boolean[lines.size()];
        for (int e = 0; e < lines.size(); e++) {
            List<LineSegment> others = new ArrayList<>();
            for (int o = 0; o < current.size(); o++) {
                Coordinate[] coords = current.get(o).getCoordinates();
                for (int i = 0; o != e && i + 1 < coords.length; i++) {
                    others.add(new LineSegment(coords[i], coords[i + 1]));
                }
            }
            List<Coordinate> coords = new ArrayList<>(Arrays.asList(lines.get(e).getCoordinates()));
            Coordinate exit = exits.get(e) ? coords.get(coords.size() - 2) : null;
            for (int pass = 0; pass < passes && router.cutPass(zones.get(e), coords, ignored, exit, others); pass++) {
                changed[e] = true;
            }
            changed[e] |= router.sharpen(zones.get(e), coords, ignored, exit, others);
            result[e] = coords.toArray(new Coordinate[0]);
            current.set(e, factory.createLineString(result[e]));
        }
        return new Cut(result, changed);
    }

    private static List<ConnectionPoint> below(Tree tree, Tree.Node node, Map<Tree.Node, List<ConnectionPoint>> memo) {
        List<ConnectionPoint> known = memo.get(node);
        if (known != null) {
            return known;
        }
        List<ConnectionPoint> result = new ArrayList<>();
        if (node.kind == Tree.Kind.CONNECTION) {
            result.add(node.connection);
        }
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == node) {
                result.addAll(below(tree, edge.to, memo));
            }
        }
        memo.put(node, result);
        return result;
    }

    private boolean spotAllowed(Tree.Edge edge, double position, List<Double> vertices, List<SpecialSpan> spans,
            ObstacleSet zones, int dn) {
        double length = edge.line.getLength();
        return position >= MIN_PIECE_M && position <= length - MIN_PIECE_M
                && placeAllowed(edge, position, vertices, spans, zones, dn);
    }

    private boolean placeAllowed(Tree.Edge edge, double position, List<Double> vertices, List<SpecialSpan> spans,
            ObstacleSet zones, int dn) {
        double length = edge.line.getLength();
        if (edge.to.kind == Tree.Kind.CONNECTION && position > length - CONNECTION_GAP_M) {
            return false;
        }
        for (double vertex : vertices) {
            double gap = Math.abs(vertex - position);
            if (gap > 1e-9 && gap < MIN_PIECE_M) {
                return false;
            }
        }
        for (SpecialSpan span : spans) {
            if (position > span.getFromM() - MIN_PIECE_M && position < span.getToM() + MIN_PIECE_M) {
                return false;
            }
        }
        Coordinate at = new LengthIndexedLine(edge.line).extractPoint(position);
        return !specials.near(at, dn) && !zones.insideForbid(at, false);
    }

    static final class Slide {
        final double gain;
        final Tree.Node junction;
        final Coordinate point;
        final Map<Tree.Edge, Coordinate[]> lines;

        Slide(double gain, Tree.Node junction, Coordinate point, Map<Tree.Edge, Coordinate[]> lines) {
            this.gain = gain;
            this.junction = junction;
            this.point = point;
            this.lines = lines;
        }
    }

    List<Slide> slides(Tree tree, ObstacleSet zones, int dn, Map<Tree.Edge, Double> priceRub, double minGainRub,
            Map<List<Tree.Edge>, Slide> cache, boolean parallel) {
        List<List<Tree.Edge>> junctions = new ArrayList<>();
        List<List<Tree.Edge>> missing = new ArrayList<>();
        for (Tree.Edge in : tree.edges) {
            if (in.to.kind != Tree.Kind.JUNCTION) {
                continue;
            }
            List<Tree.Edge> incident = new ArrayList<>(List.of(in));
            for (Tree.Edge edge : tree.edges) {
                if (edge.from == in.to) {
                    incident.add(edge);
                }
            }
            junctions.add(incident);
            if (!cache.containsKey(incident)) {
                missing.add(incident);
            }
        }
        java.util.stream.Stream<List<Tree.Edge>> stream = parallel ? missing.parallelStream() : missing.stream();
        List<Slide> computed = stream.map(incident -> slide(tree, incident, zones, dn, priceRub, minGainRub))
                .collect(java.util.stream.Collectors.toList());
        for (int i = 0; i < missing.size(); i++) {
            cache.put(missing.get(i), computed.get(i));
        }
        List<Slide> found = new ArrayList<>();
        for (List<Tree.Edge> incident : junctions) {
            if (cache.get(incident) != null) {
                found.add(cache.get(incident));
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    private Slide slide(Tree tree, List<Tree.Edge> incident, ObstacleSet zones, int dn, Map<Tree.Edge, Double> priceRub,
            double minGainRub) {
        Set<String> ignored = tree.tie.getIgnored();
        Tree.Edge in = incident.get(0);
        List<Tree.Edge> outs = incident.subList(1, incident.size());
        Slide best = null;
        for (Tree.Edge rail : incident) {
            List<Double> vertices = vertexPositions(rail.line);
            List<SpecialSpan> spans = null;
            double length = rail.line.getLength();
            LengthIndexedLine indexed = new LengthIndexedLine(rail.line);
            boolean toBuilding = rail.to.kind == Tree.Kind.CONNECTION
                    && buildingByConnection.containsKey(rail.to.connection.getId());
            double limit = toBuilding && !vertices.isEmpty() ? vertices.get(vertices.size() - 1) : length;
            for (double shift : shifts(vertices, length, rail == in)) {
                double at = rail == in ? length - shift : shift;
                if (at >= limit) {
                    continue;
                }
                Coordinate point = indexed.extractPoint(at);
                Map<Tree.Edge, Double> straight = new IdentityHashMap<>();
                double others = 0;
                for (Tree.Edge out : outs) {
                    straight.put(out, price(priceRub, out) * (shortest(out, point) - out.line.getLength()));
                    others += straight.get(out);
                }
                for (Tree.Edge through : rail == in ? outs : List.of(rail)) {
                    double bound = best == null ? -minGainRub : best.gain;
                    double optimistic = shift * (price(priceRub, through) - price(priceRub, in)) * (rail == in ? 1 : -1)
                            + others - straight.get(through);
                    if (optimistic >= bound) {
                        continue;
                    }
                    if (spans == null) {
                        spans = zones.spans(rail.line, ignored);
                    }
                    if (!spotAllowed(rail, at, vertices, spans, zones, dn)) {
                        break;
                    }
                    Slide slide = slide(in, rail, through, outs, at, point, zones, ignored, priceRub, straight, bound);
                    if (slide != null) {
                        best = slide;
                    }
                }
            }
        }
        return best;
    }

    private static List<Double> shifts(List<Double> vertices, double length, boolean fromEnd) {
        Set<Double> shifts = new java.util.TreeSet<>();
        for (double step : SLIDE_STEPS_M) {
            shifts.add(step);
        }
        for (double vertex : vertices) {
            shifts.add(fromEnd ? length - vertex : vertex);
        }
        return new ArrayList<>(shifts);
    }

    private Slide slide(Tree.Edge in, Tree.Edge rail, Tree.Edge through, List<Tree.Edge> outs, double at, Coordinate point,
            ObstacleSet zones, Set<String> ignored, Map<Tree.Edge, Double> priceRub, Map<Tree.Edge, Double> straight,
            double bound) {
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        List<Coordinate> joined;
        if (rail == in) {
            lines.put(in, part(in.line, 0, at, point, false).getCoordinates());
            joined = new ArrayList<>(Arrays.asList(part(in.line, at, in.line.getLength(), point, true).getCoordinates()));
            joined.addAll(Arrays.asList(through.line.getCoordinates()).subList(1, through.line.getNumPoints()));
        } else {
            lines.put(rail, part(rail.line, at, rail.line.getLength(), point, true).getCoordinates());
            joined = new ArrayList<>(Arrays.asList(in.line.getCoordinates()));
            Coordinate[] head = part(rail.line, 0, at, point, false).getCoordinates();
            joined.addAll(Arrays.asList(head).subList(1, head.length));
        }
        straighten(joined, zones, ignored);
        Coordinate[] joinedLine = joined.toArray(new Coordinate[0]);
        if (!shapeValid(joinedLine)) {
            return null;
        }
        lines.put(rail == in ? through : in, joinedLine);
        double gain = change(priceRub, in, lines) + change(priceRub, through, lines);
        double rest = -straight.get(through);
        for (Tree.Edge out : outs) {
            rest += straight.get(out);
        }
        for (Tree.Edge out : outs) {
            if (out == through) {
                continue;
            }
            if (gain + rest >= bound) {
                return null;
            }
            Coordinate[] line = shortcut(out, point, zones, ignored);
            if (line.length == 0) {
                return null;
            }
            lines.put(out, line);
            gain += change(priceRub, out, lines);
            rest -= straight.get(out);
        }
        return gain < bound ? new Slide(gain, in.to, point, lines) : null;
    }

    List<Slide> unkinks(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        return unkinks(tree, junction, zones, dnByEdge, priceRub, maxGainRub, false);
    }

    List<Slide> unkinks(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, boolean evensOnly) {
        return unkinks(tree, junction, zones, dnByEdge, priceRub, maxGainRub, evensOnly, null);
    }

    List<Slide> unkinks(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, boolean evensOnly,
            java.util.function.BiPredicate<Coordinate[], Coordinate[]> keeps) {
        return unkinks(tree, junction, zones, dnByEdge, priceRub, maxGainRub, evensOnly, false, keeps, false);
    }

    List<Slide> rightFreeUnkinks(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, boolean evensOnly) {
        return unkinks(tree, junction, zones, dnByEdge, priceRub, maxGainRub, evensOnly, false, null, true);
    }

    List<Slide> turnMoves(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        return unkinks(tree, junction, zones, dnByEdge, priceRub, maxGainRub, false, true, null, false);
    }

    private List<Slide> unkinks(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, boolean evensOnly,
            boolean turnsOnly, java.util.function.BiPredicate<Coordinate[], Coordinate[]> keeps, boolean rights) {
        Ends ends = ends(tree, junction, zones);
        int[] paths = ends == null ? null : paths(ends, ends.heads);
        int vertices = 0;
        for (int e = 0; ends != null && e < ends.heads.length; e++) {
            vertices += rights ? bent(ends.heads[e]) : ends.heads[e].length - 2;
        }
        if (ends == null || !turnsOnly && (evensOnly ? paths[2] == 0 : vertices == 0 && paths[1] == 0)) {
            return List.of();
        }
        int count = ends.heads.length;
        Coordinate[][] from = ends.heads;
        double[][] rest = new double[count][];
        double[] rub = new double[count];
        for (int e = 0; e < count; e++) {
            Coordinate[] c = from[e];
            rest[e] = new double[c.length];
            for (int k = c.length - 2; k >= 0; k--) {
                rest[e][k] = rest[e][k + 1] + c[k].distance(c[k + 1]);
            }
            rub[e] = price(priceRub, ends.incident.get(e));
        }
        Object[][] hints = new Object[count][1];
        Map<Object, Boolean> checked = new IdentityHashMap<>();
        List<Double> gains = new ArrayList<>();
        List<Coordinate[][]> layouts = new ArrayList<>();
        int[][] targets = new int[count][2];
        double[][] costs = new double[count][2];
        int[][] verdicts = new int[count][2];
        int[] sizes = new int[count];
        List<Coordinate> spots = spots(junction.point, from, ends.up, evensOnly ? ends.tails : null);
        for (int e = 0; keeps != null && e < count; e++) {
            double length = from[e][0].distance(from[e][1]);
            for (double d = -SHIFT_M; d <= SHIFT_M; d += SHIFT_STEP_M) {
                if (d != 0) {
                    spots.add(new Coordinate(from[e][0].x + d * (from[e][1].x - from[e][0].x) / length,
                            from[e][0].y + d * (from[e][1].y - from[e][0].y) / length));
                }
            }
        }
        for (Coordinate point : spots) {
            double bound = 0;
            int combos = 1;
            for (int e = 0; e < count; e++) {
                Coordinate[] c = from[e];
                sizes[e] = 0;
                double cheapest = Double.POSITIVE_INFINITY;
                for (int skip = 1, last = -1; skip <= Math.min(2, c.length - 1); skip++) {
                    int t = last >= skip ? last : target(point, c, skip);
                    if (t == last) {
                        continue;
                    }
                    last = t;
                    double reach = point.distance(c[t]);
                    if (reach < Router.CUT_PIECE_M
                            || t + 1 < c.length && turn(point, c[t], c[t + 1]) > Router.MAX_TURN_DEG
                            || sizes[e] > 0 && targets[e][0] == t) {
                        continue;
                    }
                    targets[e][sizes[e]] = t;
                    costs[e][sizes[e]] = rub[e] * (reach + rest[e][t] - rest[e][0]);
                    verdicts[e][sizes[e]] = 0;
                    cheapest = Math.min(cheapest, costs[e][sizes[e]]);
                    sizes[e]++;
                }
                bound += cheapest;
                combos *= sizes[e];
            }
            if (combos == 0 || !(bound <= maxGainRub)) {
                continue;
            }
            boolean shorter = false;
            for (int e = 0; e < count && !shorter; e++) {
                for (int o = 0; o < sizes[e] && !shorter; o++) {
                    if (targets[e][o] >= 2) {
                        shorter = lineAllowed(tree, ends, e, point, targets[e][o], hints[e]);
                        verdicts[e][o] = shorter ? 1 : -1;
                    }
                }
            }
            if (!shorter && paths[1] == 0 && !evensOnly && !turnsOnly && !rights) {
                continue;
            }
            Coordinate[][][] lines = new Coordinate[count][2][];
            for (int e = 0; e < count; e++) {
                for (int o = 0; o < sizes[e]; o++) {
                    lines[e][o] = line(point, from[e], targets[e][o]);
                    if (verdicts[e][o] != 0) {
                        checked.put(lines[e][o], verdicts[e][o] > 0);
                    }
                }
            }
            for (int combo = 0; combo < combos; combo++) {
                Coordinate[][] layout = new Coordinate[count][];
                int left = 0;
                double gain = 0;
                boolean bad = false;
                for (int e = 0, digits = combo; e < count; e++) {
                    int option = digits % sizes[e];
                    digits /= sizes[e];
                    layout[e] = lines[e][option];
                    left += rights ? bent(layout[e]) : layout[e].length - 2;
                    gain += costs[e][option];
                    bad |= verdicts[e][option] < 0;
                }
                boolean fits = turnsOnly || (evensOnly ? left == vertices && gain <= 0 && evens(ends, layout, paths)
                        : left < vertices || left == vertices && straightens(ends, layout, paths));
                if (!bad && fits && gain <= maxGainRub && turnsAllowed(layout, ends.up, point) && kept(from, layout, keeps)) {
                    gains.add(gain);
                    layouts.add(layout);
                }
            }
        }
        Integer[] order = new Integer[gains.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.comparingDouble(gains::get));
        List<Slide> found = new ArrayList<>();
        for (int k = 0; k < order.length && found.size() < MOVES_PER_JUNCTION; k++) {
            Slide slide = move(tree, ends, layouts.get(order[k]), dnByEdge, priceRub, checked, hints, evensOnly);
            if (slide != null) {
                found.add(slide);
            }
        }
        return found;
    }

    private static boolean kept(Coordinate[][] from, Coordinate[][] layout,
            java.util.function.BiPredicate<Coordinate[], Coordinate[]> keeps) {
        for (int e = 0; keeps != null && e < from.length; e++) {
            if (!keeps.test(from[e], layout[e])) {
                return false;
            }
        }
        return true;
    }

    private static final class Ends {
        final Tree.Node junction;
        final List<Tree.Edge> incident;
        final Coordinate[][] heads;
        final Coordinate[][] tails;
        final Coordinate[][] far;
        final boolean[] toPoint;
        final boolean[] inBuilding;
        final ObstacleSet[] zones;
        int vertices;
        int up = -1;

        Ends(Tree.Node junction, List<Tree.Edge> incident, ObstacleSet[] zones) {
            int count = incident.size();
            this.junction = junction;
            this.incident = incident;
            this.zones = zones;
            heads = new Coordinate[count][];
            tails = new Coordinate[count][];
            far = new Coordinate[count][];
            toPoint = new boolean[count];
            inBuilding = new boolean[count];
        }
    }

    private Ends ends(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones) {
        List<Tree.Edge> incident = JunctionMover.incident(tree, junction);
        ObstacleSet[] zonesOf = new ObstacleSet[incident.size()];
        for (int e = 0; e < incident.size(); e++) {
            zonesOf[e] = zones.apply(incident.get(e));
        }
        Ends ends = new Ends(junction, incident, zonesOf);
        Set<String> ignored = tree.tie.getIgnored();
        for (int e = 0; e < incident.size(); e++) {
            Tree.Edge edge = incident.get(e);
            Coordinate[] c = JunctionMover.fromJunction(edge, junction);
            double length = edge.line.getLength();
            double cut = Double.POSITIVE_INFINITY;
            for (SpecialSpan span : zonesOf[e].spans(edge.line, ignored)) {
                cut = Math.min(cut, edge.from == junction ? span.getFromM() : length - span.getToM());
            }
            if (cut <= SPAN_TOUCH_M) {
                return null;
            }
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            if (cut < length - SPAN_SNAP_M) {
                int k = 0;
                double at = 0;
                while (at + c[k].distance(c[k + 1]) < cut - SPAN_SNAP_M) {
                    at += c[k].distance(c[k + 1]);
                    k++;
                }
                boolean vertex = cut - at > c[k].distance(c[k + 1]) - SPAN_SNAP_M;
                Coordinate start = vertex ? c[k + 1] : new LineSegment(c[k], c[k + 1]).pointAlong((cut - at) / c[k].distance(c[k + 1]));
                ends.heads[e] = Arrays.copyOf(c, k + 2);
                ends.heads[e][k + 1] = start;
                ends.tails[e] = new Coordinate[c.length - k - (vertex ? 1 : 0)];
                ends.tails[e][0] = start;
                System.arraycopy(c, vertex ? k + 2 : k + 1, ends.tails[e], 1, ends.tails[e].length - 1);
                ends.far[e] = new Coordinate[] {ends.tails[e][1]};
            } else {
                ends.heads[e] = c;
                ends.toPoint[e] = far.kind == Tree.Kind.CONNECTION;
                ends.inBuilding[e] = ends.toPoint[e] && buildingByConnection.containsKey(far.connection.getId());
                ends.far[e] = farNeighbours(tree, edge, far);
            }
            ends.vertices += ends.heads[e].length - 2;
            ends.up = edge.to == junction ? e : ends.up;
        }
        return ends;
    }

    private static int[] paths(Ends ends, Coordinate[][] layout) {
        List<Coordinate[]> turns = new ArrayList<>();
        for (int e = 0; ends.up >= 0 && e < layout.length; e++) {
            if (e != ends.up) {
                turns.add(new Coordinate[] {layout[ends.up][1], layout[e][0], layout[e][1]});
            }
        }
        int[] result = new int[3];
        for (int e = 0; e < layout.length; e++) {
            Coordinate[] c = layout[e];
            for (Coordinate next : ends.tails[e] == null ? ends.far[e] : new Coordinate[0]) {
                turns.add(new Coordinate[] {c[c.length - 2], c[c.length - 1], next});
            }
            if (ends.tails[e] != null && Router.micro(c[c.length - 2], c[c.length - 1], ends.tails[e][1])) {
                result[2]++;
            }
        }
        for (Coordinate[] t : turns) {
            double turn = turn(t[0], t[1], t[2]);
            result[0] += turn < MIN_TURN_DEG ? 1 : 0;
            result[1] += turn >= MIN_TURN_DEG && turn < SMALL_BEND_DEG ? 1 : 0;
            result[2] += Router.micro(t[0], t[1], t[2]) ? 1 : 0;
        }
        return result;
    }

    private static boolean straightens(Ends ends, Coordinate[][] layout, int[] before) {
        int[] after = paths(ends, layout);
        return after[0] > before[0] && after[1] < before[1];
    }

    private static boolean evens(Ends ends, Coordinate[][] layout, int[] before) {
        int[] after = paths(ends, layout);
        return after[2] < before[2] && after[0] >= before[0];
    }

    private static List<Coordinate> spots(Coordinate at, Coordinate[][] from, int up, Coordinate[][] tails) {
        List<Coordinate> spots = new ArrayList<>();
        int steps = (int) Math.round(SHIFT_M / SHIFT_STEP_M);
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                if (i != 0 || j != 0) {
                    spots.add(new Coordinate(at.x + i * SHIFT_STEP_M, at.y + j * SHIFT_STEP_M));
                }
            }
        }
        List<LineSegment> links = new ArrayList<>();
        for (Coordinate[] c : from) {
            links.add(new LineSegment(c[0], c[1]));
            if (c.length > 2) {
                LineSegment second = new LineSegment(c[1], c[2]);
                links.add(second);
                spots.add(c[1]);
                spots.add(second.project(at));
            }
        }
        for (int e = 0; up >= 0 && e < from.length; e++) {
            if (e != up && !from[up][1].equals2D(from[e][1])) {
                spots.add(new LineSegment(from[up][1], from[e][1]).project(at));
            }
        }
        for (int a = 0; a < links.size(); a++) {
            for (int b = a + 1; b < links.size(); b++) {
                Coordinate cross = links.get(a).lineIntersection(links.get(b));
                if (cross != null && cross.distance(at) > LINE_TOL_M) {
                    spots.add(cross);
                }
            }
        }
        for (int e = 0; tails != null && e < tails.length; e++) {
            if (tails[e] != null) {
                spots.add(new LineSegment(tails[e][0], tails[e][1]).project(at));
            }
        }
        return spots;
    }

    private static int target(Coordinate point, Coordinate[] c, int skip) {
        while (skip + 1 < c.length && turn(point, c[skip], c[skip + 1]) < MIN_TURN_DEG) {
            skip++;
        }
        return skip;
    }

    private static Coordinate[] line(Coordinate point, Coordinate[] c, int t) {
        Coordinate[] line = new Coordinate[c.length - t + 1];
        line[0] = point;
        System.arraycopy(c, t, line, 1, c.length - t);
        return line;
    }

    private static boolean turnsAllowed(Coordinate[][] layout, int up, Coordinate point) {
        for (int e = 0; up >= 0 && e < layout.length; e++) {
            if (e != up && deflectionDeg(layout[up][1], point, layout[e][1]) > Router.MAX_TURN_DEG) {
                return false;
            }
        }
        return true;
    }

    private static int onLink(Coordinate point, Coordinate a, Coordinate b) {
        double length = a.distance(b);
        double ux = (b.x - a.x) / length;
        double uy = (b.y - a.y) / length;
        if (Math.abs(ux * (point.y - a.y) - uy * (point.x - a.x)) > LINE_TOL_M) {
            return -1;
        }
        double along = ux * (point.x - a.x) + uy * (point.y - a.y);
        return along >= length - LINE_TOL_M ? -1 : along >= -LINE_TOL_M ? 0 : 1;
    }

    private Slide move(Tree tree, Ends ends, Coordinate[][] layout, Map<Tree.Edge, Integer> dnByEdge,
            Map<Tree.Edge, Double> priceRub, Map<Object, Boolean> checked, Object[][] hints) {
        return move(tree, ends, layout, dnByEdge, priceRub, checked, hints, false);
    }

    private Slide move(Tree tree, Ends ends, Coordinate[][] layout, Map<Tree.Edge, Integer> dnByEdge,
            Map<Tree.Edge, Double> priceRub, Map<Object, Boolean> checked, Object[][] hints, boolean rotate) {
        List<Tree.Edge> incident = ends.incident;
        Coordinate point = layout[0][0];
        int dn = 0;
        int widest = 0;
        for (int e = 0; e < incident.size(); e++) {
            if (dnByEdge.get(incident.get(e)) > dn) {
                dn = dnByEdge.get(incident.get(e));
                widest = e;
            }
            int k = e;
            if (!checked.computeIfAbsent(layout[e], line -> lineAllowed(tree, ends, k, point,
                    ends.heads[k].length - layout[k].length + 1, hints[k], rotate))) {
                return null;
            }
        }
        int wide = dn;
        ObstacleSet wideZones = ends.zones[widest];
        if (!turnsAllowed(layout, ends.up, point) || !checked.computeIfAbsent(point,
                spot -> !specials.near(point, wide) && !wideZones.insideForbid(point, false))) {
            return null;
        }
        Map<Tree.Edge, Coordinate[]> fresh = new IdentityHashMap<>();
        for (int e = 0; e < incident.size(); e++) {
            Coordinate[] tail = ends.tails[e];
            Coordinate[] line = layout[e];
            if (tail != null) {
                line = Arrays.copyOf(layout[e], layout[e].length + tail.length - 1);
                System.arraycopy(tail, 1, line, layout[e].length, tail.length - 1);
            }
            fresh.put(incident.get(e), line);
        }
        for (int e = 0; e < incident.size(); e++) {
            Coordinate[] c = ends.heads[e];
            int t = c.length - layout[e].length + 1;
            if (onLink(point, c[t - 1], c[t]) == 0) {
                continue;
            }
            List<LineSegment> others = new ArrayList<>();
            for (Tree.Edge other : tree.edges) {
                Coordinate[] o = fresh.containsKey(other) ? fresh.get(other) : other.line.getCoordinates();
                for (int i = 0; other != incident.get(e) && i + 1 < o.length; i++) {
                    others.add(new LineSegment(o[i], o[i + 1]));
                }
            }
            if (!Router.apart(new LineSegment(point, layout[e][1]), others)) {
                return null;
            }
        }
        double gain = 0;
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        for (Tree.Edge edge : incident) {
            Coordinate[] line = fresh.get(edge);
            gain += price(priceRub, edge) * (length(line) - edge.line.getLength());
            lines.put(edge, edge.from == ends.junction ? line : JunctionMover.reversed(line));
        }
        return new Slide(gain, ends.junction, point, lines);
    }

    private boolean lineAllowed(Tree tree, Ends ends, int e, Coordinate point, int t, Object[] hint) {
        return lineAllowed(tree, ends, e, point, t, hint, false);
    }

    private boolean lineAllowed(Tree tree, Ends ends, int e, Coordinate point, int t, Object[] hint, boolean rotate) {
        Coordinate[] c = ends.heads[e];
        int part = onLink(point, c[t - 1], c[t]);
        boolean turned = ends.inBuilding[e] && t == c.length - 1 && part < 0;
        if (point.distance(c[t]) < Router.CUT_PIECE_M
                || ends.toPoint[e] && point.distance(c[t]) + length(Arrays.copyOfRange(c, t, c.length)) < CONNECTION_GAP_M
                || t + 1 < c.length && turn(point, c[t], c[t + 1]) > Router.MAX_TURN_DEG
                || turned && !(rotate && c.length == 2)) {
            return false;
        }
        if (turned) {
            Tree.Edge edge = ends.incident.get(e);
            Tree.Node far = edge.from == ends.junction ? edge.to : edge.from;
            ExistingOks building = buildingByConnection.get(far.connection.getId());
            Coordinate[] probe = {point, point, c[t]};
            Coordinate entry = straightEntry(building.getGeometry(), probe);
            return entry != null && relinked(building, probe, ends.zones[e], tree.tie.getIgnored(), null, List.of(),
                    false, List.of(entry), true, false) != null;
        }
        for (int k = 0; t == c.length - 1 && k < ends.far[e].length; k++) {
            if (deflectionDeg(point, c[t], ends.far[e][k]) > Router.MAX_TURN_DEG) {
                return false;
            }
        }
        Coordinate end = part > 0 ? c[t - 1] : c[t];
        return part == 0 || ends.zones[e].plain(point, end, tree.tie.getIgnored(), Router.CUT_MARGIN_M, hint)
                && ends.zones[e].covers(point, end);
    }

    private static Coordinate[] farNeighbours(Tree tree, Tree.Edge edge, Tree.Node far) {
        List<Coordinate> next = new ArrayList<>();
        for (Tree.Edge other : tree.edges) {
            if (far.kind == Tree.Kind.JUNCTION && edge.to == far && other.from == far) {
                next.add(other.line.getCoordinateN(1));
            } else if (far.kind == Tree.Kind.JUNCTION && edge.from == far && other.to == far) {
                next.add(other.line.getCoordinateN(other.line.getNumPoints() - 2));
            }
        }
        return next.toArray(new Coordinate[0]);
    }

    List<Slide> turnSlides(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, double step,
            double maxShift) {
        Ends ends = ends(tree, junction, zones);
        if (ends == null) {
            return List.of();
        }
        Set<String> ignored = tree.tie.getIgnored();
        int dn = 0;
        for (Tree.Edge edge : ends.incident) {
            dn = Math.max(dn, dnByEdge.get(edge));
        }
        List<Slide> found = new ArrayList<>();
        for (int r = 0; r < ends.incident.size(); r++) {
            Tree.Edge rail = ends.incident.get(r);
            LineSegment link = new LineSegment(ends.heads[r][0], ends.heads[r][1]);
            ObstacleSet railZones = ends.zones[r];
            List<Double> vertices = vertexPositions(rail.line);
            List<SpecialSpan> spans = railZones.spans(rail.line, ignored);
            for (double shift = step; shift <= Math.min(maxShift, link.getLength() - Router.CUT_PIECE_M); shift += step) {
                Coordinate point = link.pointAlong(shift / link.getLength());
                double position = rail.from == junction ? shift : rail.line.getLength() - shift;
                if (!placeAllowed(rail, position, vertices, spans, railZones, dn)) {
                    continue;
                }
                Coordinate[][] layout = new Coordinate[ends.incident.size()][];
                for (int e = 0; e < layout.length; e++) {
                    layout[e] = ends.heads[e].clone();
                    layout[e][0] = point;
                }
                Slide slide = move(tree, ends, layout, dnByEdge, priceRub, new IdentityHashMap<>(),
                        new Object[layout.length][1]);
                if (slide != null && slide.gain <= maxGainRub) {
                    found.add(slide);
                }
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    List<Slide> bends(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        List<Tree.Edge> incident = JunctionMover.incident(tree, junction);
        Map<Tree.Edge, Coordinate[]> from = new IdentityHashMap<>();
        incident.forEach(edge -> from.put(edge, JunctionMover.fromJunction(edge, junction)));
        List<Slide> found = new ArrayList<>();
        for (Tree.Edge edge : incident) {
            Coordinate[] c = from.get(edge);
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            if (c.length == 2 && far.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(far.connection.getId())) {
                continue;
            }
            double angle = Math.atan2(c[1].y - c[0].y, c[1].x - c[0].x);
            for (int sign = -1; sign <= 1; sign += 2) {
                Coordinate toward = null;
                for (double delta = MIN_TURN_DEG + BEND_STEP_DEG; toward == null && delta <= MAX_BEND_DEG; delta += BEND_STEP_DEG) {
                    double a = angle + sign * Math.toRadians(delta);
                    Coordinate unit = new Coordinate(c[0].x + Math.cos(a), c[0].y + Math.sin(a));
                    toward = turnsAllowed(junction, incident, from, edge, unit) ? unit : null;
                }
                for (double r = Router.CUT_PIECE_M; toward != null && r + Router.CUT_PIECE_M <= c[0].distance(c[1]); r *= 2) {
                    Coordinate[] line = new Coordinate[c.length + 1];
                    line[0] = c[0];
                    line[1] = new Coordinate(c[0].x + (toward.x - c[0].x) * r, c[0].y + (toward.y - c[0].y) * r);
                    System.arraycopy(c, 1, line, 2, c.length - 1);
                    Slide slide = bend(tree, junction, edge, line, zones.apply(edge), priceRub);
                    if (slide != null && slide.gain <= maxGainRub) {
                        found.add(slide);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    List<Slide> fans(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Double> priceRub) {
        List<Tree.Edge> incident = JunctionMover.incident(tree, junction);
        Map<Tree.Edge, Coordinate[]> from = new IdentityHashMap<>();
        incident.forEach(edge -> from.put(edge, JunctionMover.fromJunction(edge, junction)));
        Tree.Edge up = incident.stream().filter(edge -> edge.to == junction).findFirst().orElse(null);
        if (up == null) {
            return List.of();
        }
        Set<String> ignored = tree.tie.getIgnored();
        Map<Tree.Edge, Boolean> fixed = new IdentityHashMap<>();
        for (Tree.Edge edge : incident) {
            Coordinate[] c = from.get(edge);
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            double first = c[0].distance(c[1]);
            boolean special = false;
            for (SpecialSpan span : zones.apply(edge).spans(edge.line, ignored)) {
                double at = edge.from == junction ? span.getFromM() : edge.line.getLength() - span.getToM();
                special |= at < first;
            }
            fixed.put(edge, special || c.length == 2 && far.kind == Tree.Kind.CONNECTION
                    && buildingByConnection.containsKey(far.connection.getId()));
        }
        Coordinate at = junction.point;
        List<Slide> found = new ArrayList<>();
        for (double step = 0; found.size() < MOVES_PER_JUNCTION && step <= MAX_BEND_DEG; step += BEND_STEP_DEG) {
            if (step > 0 && step < MIN_TURN_DEG + BEND_STEP_DEG || step > 0 && fixed.get(up)) {
                continue;
            }
            for (int sign = step == 0 ? 1 : -1; sign <= 1 && found.size() < MOVES_PER_JUNCTION; sign += 2) {
                Coordinate in = rotated(at, from.get(up)[1], sign * step);
                Map<Tree.Edge, Coordinate> toward = new IdentityHashMap<>();
                if (step > 0) {
                    toward.put(up, in);
                }
                boolean possible = true;
                for (Tree.Edge edge : incident) {
                    Coordinate c1 = from.get(edge)[1];
                    if (edge == up || deflectionDeg(in, at, c1) <= Router.MAX_TURN_DEG) {
                        continue;
                    }
                    Coordinate unit = null;
                    for (double delta = MIN_TURN_DEG + BEND_STEP_DEG; !fixed.get(edge) && unit == null && delta <= MAX_BEND_DEG;
                            delta += BEND_STEP_DEG) {
                        for (int turn = -1; turn <= 1 && unit == null; turn += 2) {
                            Coordinate probe = rotated(at, c1, turn * delta);
                            unit = deflectionDeg(in, at, probe) <= Router.MAX_TURN_DEG ? probe : null;
                        }
                    }
                    possible &= unit != null;
                    toward.put(edge, unit);
                }
                if (!possible || toward.size() < 2) {
                    continue;
                }
                Slide slide = fan(tree, junction, from, toward, zones, priceRub);
                if (slide != null) {
                    found.add(slide);
                }
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    private static Coordinate rotated(Coordinate at, Coordinate point, double degrees) {
        double a = Math.atan2(point.y - at.y, point.x - at.x) + Math.toRadians(degrees);
        return new Coordinate(at.x + Math.cos(a), at.y + Math.sin(a));
    }

    private Slide fan(Tree tree, Tree.Node junction, Map<Tree.Edge, Coordinate[]> from, Map<Tree.Edge, Coordinate> toward,
            java.util.function.Function<Tree.Edge, ObstacleSet> zones, Map<Tree.Edge, Double> priceRub) {
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        double gain = 0;
        for (Map.Entry<Tree.Edge, Coordinate> entry : toward.entrySet()) {
            Tree.Edge edge = entry.getKey();
            Coordinate[] c = from.get(edge);
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            Coordinate[] nearFar = farNeighbours(tree, edge, far);
            Slide best = null;
            for (double r = Router.CUT_PIECE_M; best == null && r < c[0].distance(c[1]); r *= 2) {
                Coordinate[] line = new Coordinate[c.length + 1];
                line[0] = c[0];
                line[1] = new Coordinate(c[0].x + (entry.getValue().x - c[0].x) * r, c[0].y + (entry.getValue().y - c[0].y) * r);
                System.arraycopy(c, 1, line, 2, c.length - 1);
                boolean farAllowed = true;
                for (int k = 0; line.length == 3 && k < nearFar.length; k++) {
                    farAllowed &= deflectionDeg(line[1], line[2], nearFar[k]) <= Router.MAX_TURN_DEG;
                }
                best = farAllowed ? bend(tree, junction, edge, line, zones.apply(edge), priceRub) : null;
            }
            if (best == null) {
                return null;
            }
            lines.putAll(best.lines);
            gain += best.gain;
        }
        for (Tree.Edge a : lines.keySet()) {
            List<LineSegment> others = new ArrayList<>();
            for (Tree.Edge b : lines.keySet()) {
                Coordinate[] c = lines.get(b);
                for (int i = 0; b != a && i + 1 < c.length; i++) {
                    others.add(new LineSegment(c[i], c[i + 1]));
                }
            }
            Coordinate[] c = lines.get(a);
            for (int i = 0; i + 1 < c.length; i++) {
                if (!Router.apart(new LineSegment(c[i], c[i + 1]), others)) {
                    return null;
                }
            }
        }
        return new Slide(gain, junction, junction.point, lines);
    }

    private static boolean turnsAllowed(Tree.Node junction, List<Tree.Edge> incident, Map<Tree.Edge, Coordinate[]> from,
            Tree.Edge edge, Coordinate point) {
        for (Tree.Edge in : incident) {
            if (in.to != junction) {
                continue;
            }
            Coordinate before = in == edge ? point : from.get(in)[1];
            for (Tree.Edge out : incident) {
                if (out != in && deflectionDeg(before, junction.point, out == edge ? point : from.get(out)[1]) > Router.MAX_TURN_DEG) {
                    return false;
                }
            }
        }
        return true;
    }

    private Slide bend(Tree tree, Tree.Node junction, Tree.Edge edge, Coordinate[] line, ObstacleSet zones,
            Map<Tree.Edge, Double> priceRub) {
        Set<String> ignored = tree.tie.getIgnored();
        List<LineSegment> others = new ArrayList<>();
        for (Tree.Edge other : tree.edges) {
            Coordinate[] c = other.line.getCoordinates();
            for (int i = 0; other != edge && i + 1 < c.length; i++) {
                others.add(new LineSegment(c[i], c[i + 1]));
            }
        }
        for (int i = 2; i + 1 < line.length; i++) {
            others.add(new LineSegment(line[i], line[i + 1]));
        }
        for (int i = 0; i < 2; i++) {
            Coordinate a = line[i];
            Coordinate b = line[i + 1];
            double turn = i + 2 < line.length ? deflectionDeg(a, b, line[i + 2]) : MIN_TURN_DEG;
            if (a.distance(b) < Router.CUT_PIECE_M || turn > Router.MAX_TURN_DEG || turn < MIN_TURN_DEG
                    || !zones.covers(a, b) || !zones.plain(a, b, ignored, Router.CUT_MARGIN_M)
                    || !Router.apart(new LineSegment(a, b), others)) {
                return null;
            }
        }
        double gain = price(priceRub, edge) * (length(line) - edge.line.getLength());
        return new Slide(gain, junction, junction.point, Map.of(edge, edge.from == junction ? line : JunctionMover.reversed(line)));
    }

    Slide retie(Tree tree, Tree.Edge s, Coordinate point, Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        double gain = 0;
        for (Tree.Edge edge : tree.edges) {
            if (edge.from != tree.root) {
                continue;
            }
            Coordinate[] c = edge.line.getCoordinates();
            int skip = edge == s ? 2 : 1;
            boolean inside = edge.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(edge.to.connection.getId());
            if (c.length - skip < (inside ? 2 : 1)) {
                return null;
            }
            Coordinate[] line = new Coordinate[c.length - skip + 1];
            line[0] = point;
            System.arraycopy(c, skip, line, 1, c.length - skip);
            if (point.distance(line[1]) < Router.CUT_PIECE_M
                    || line.length > 2 && deflectionDeg(point, line[1], line[2]) > Router.MAX_TURN_DEG) {
                return null;
            }
            lines.put(edge, line);
            gain += price(priceRub, edge) * (length(line) - edge.line.getLength());
        }
        return gain <= maxGainRub ? new Slide(gain, tree.root, point, lines) : null;
    }

    boolean retieClear(Tree tree, Slide slide, Set<String> ignored, java.util.function.Function<Tree.Edge, ObstacleSet> zones) {
        Coordinate point = slide.point;
        for (Map.Entry<Tree.Edge, Coordinate[]> entry : slide.lines.entrySet()) {
            Coordinate next = entry.getValue()[1];
            ObstacleSet edgeZones = zones.apply(entry.getKey());
            if (!edgeZones.covers(point, next) || !edgeZones.plain(point, next, ignored, Router.CUT_MARGIN_M)
                    || !leavesNetwork(new LineSegment(next, point), ignored)) {
                return false;
            }
            List<LineSegment> others = new ArrayList<>();
            for (Tree.Edge other : tree.edges) {
                Coordinate[] c = slide.lines.containsKey(other) ? slide.lines.get(other) : other.line.getCoordinates();
                for (int i = 0; other != entry.getKey() && i + 1 < c.length; i++) {
                    others.add(new LineSegment(c[i], c[i + 1]));
                }
            }
            if (!Router.apart(new LineSegment(point, next), others)) {
                return false;
            }
        }
        return true;
    }

    private double shortest(Tree.Edge out, Coordinate point) {
        Coordinate[] c = out.line.getCoordinates();
        double best = Double.POSITIVE_INFINITY;
        double rest = 0;
        for (int k = c.length - 1; k >= 1; k--) {
            best = Math.min(best, point.distance(c[k]) + rest);
            rest += c[k - 1].distance(c[k]);
        }
        return best;
    }

    private Coordinate[] shortcut(Tree.Edge out, Coordinate point, ObstacleSet zones, Set<String> ignored) {
        Coordinate[] c = out.line.getCoordinates();
        int last = c.length - 1;
        if (out.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(out.to.connection.getId())) {
            last--;
        }
        double[] rest = new double[c.length];
        for (int k = c.length - 2; k >= 0; k--) {
            rest[k] = rest[k + 1] + c[k].distance(c[k + 1]);
        }
        List<Integer> order = new ArrayList<>();
        for (int k = 1; k <= last; k++) {
            order.add(k);
        }
        order.sort(Comparator.comparingDouble(k -> point.distance(c[k]) + rest[k]));
        for (int k : order) {
            double straight = point.distance(c[k]);
            if (straight < MIN_PIECE_M) {
                continue;
            }
            if (k + 1 < c.length) {
                double deflection = deflectionDeg(point, c[k], c[k + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < MIN_TURN_DEG) {
                    continue;
                }
            }
            if (!(zones.edgeWeight(point, c[k], ignored) <= straight + 1e-9) || zones.alongIgnored(point, c[k], ignored)) {
                continue;
            }
            Coordinate[] line = new Coordinate[c.length - k + 1];
            line[0] = point;
            System.arraycopy(c, k, line, 1, c.length - k);
            return line;
        }
        return new Coordinate[0];
    }

    private static boolean shapeValid(Coordinate[] line) {
        for (int i = 0; i + 1 < line.length; i++) {
            if (line[i].distance(line[i + 1]) < MIN_PIECE_M) {
                return false;
            }
            if (i > 0) {
                double deflection = deflectionDeg(line[i - 1], line[i], line[i + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < MIN_TURN_DEG) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double price(Map<Tree.Edge, Double> priceRub, Tree.Edge edge) {
        return priceRub.get(edge);
    }

    private static double change(Map<Tree.Edge, Double> priceRub, Tree.Edge edge, Map<Tree.Edge, Coordinate[]> lines) {
        return price(priceRub, edge) * (length(lines.get(edge)) - edge.line.getLength());
    }

    private static double length(Coordinate[] line) {
        double length = 0;
        for (int i = 0; i + 1 < line.length; i++) {
            length += line[i].distance(line[i + 1]);
        }
        return length;
    }

    Tree moved(Tree tree, Slide slide) {
        return moved(tree, slide, tree.tie);
    }

    Tree moved(Tree tree, Slide slide, TieCandidate tie) {
        Tree.Node junction = slide.junction;
        Tree result = new Tree(tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        Tree.Node at = junction == tree.root ? result.root : Tree.Node.junction(slide.point);
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] line = slide.lines.get(edge);
            Tree.Node from = edge.from == tree.root ? result.root : edge.from == junction ? at : edge.from;
            Tree.Node to = edge.to == junction ? at : edge.to;
            result.edges.add(line == null && from == edge.from && to == edge.to ? edge
                    : new Tree.Edge(from, to, line == null ? edge.line : factory.createLineString(line)));
        }
        return result;
    }

    private LineString part(LineString line, double from, double to, Coordinate junction, boolean junctionAtStart) {
        Coordinate[] coords = new LengthIndexedLine(line).extractLine(from, to).getCoordinates();
        coords[junctionAtStart ? 0 : coords.length - 1] = junction;
        return factory.createLineString(coords);
    }

    static Coordinate[] exitLink(Coordinate[] coords, double length) {
        Coordinate cp = coords[0];
        Coordinate exit = coords[1];
        Coordinate next = coords[2];
        double dx = exit.x - cp.x;
        double dy = exit.y - cp.y;
        double norm = Math.hypot(dx, dy);
        if (norm < 1e-9) {
            return null;
        }
        dx /= norm;
        dy /= norm;
        double side = dx * (next.y - exit.y) - dy * (next.x - exit.x) >= 0 ? 1 : -1;
        double angle = Math.toRadians(EXIT_LINK_DEG) * side;
        double ux = dx * Math.cos(angle) - dy * Math.sin(angle);
        double uy = dx * Math.sin(angle) + dy * Math.cos(angle);
        Coordinate[] linked = new Coordinate[coords.length + 1];
        linked[0] = cp;
        linked[1] = exit;
        linked[2] = new Coordinate(exit.x + ux * length, exit.y + uy * length);
        System.arraycopy(coords, 2, linked, 3, coords.length - 2);
        return linked;
    }

    static void straighten(List<Coordinate> coords, ObstacleSet obstacles, Set<String> ignored) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i + 1 < coords.size() && !changed; i++) {
                Coordinate prev = coords.get(i - 1);
                Coordinate cur = coords.get(i);
                Coordinate next = coords.get(i + 1);
                boolean removable = deflectionDeg(prev, cur, next) < MIN_TURN_DEG
                        || prev.distance(cur) < MIN_PIECE_M || cur.distance(next) < MIN_PIECE_M;
                if (removable && !Double.isNaN(obstacles.edgeWeight(prev, next, ignored))) {
                    coords.remove(i);
                    changed = true;
                }
            }
        }
    }

    private static Coordinate[] reversed(Coordinate[] coords) {
        Coordinate[] result = coords.clone();
        java.util.Collections.reverse(Arrays.asList(result));
        return result;
    }

    static List<Double> vertexPositions(LineString line) {
        List<Double> positions = new ArrayList<>();
        double at = 0;
        for (int i = 1; i + 1 < line.getNumPoints(); i++) {
            at += line.getCoordinateN(i - 1).distance(line.getCoordinateN(i));
            positions.add(at);
        }
        return positions;
    }

    static double deflectionDeg(Coordinate a, Coordinate b, Coordinate c) {
        return 180 - Math.toDegrees(Angle.angleBetween(a, b, c));
    }

    static double pathTurnDeg(Coordinate[] in, Coordinate[] out) {
        Coordinate[] c = new Coordinate[in.length + out.length - 1];
        System.arraycopy(in, 0, c, 0, in.length);
        System.arraycopy(out, 1, c, in.length, out.length - 1);
        int at = in.length - 1;
        double base = signedTurn(c[at - 1], c[at], c[at + 1]);
        double total = base;
        for (int i = at - 1; i >= 1 && c[i].distance(c[i + 1]) < SPLIT_LINK_M; i--) {
            double t = signedTurn(c[i - 1], c[i], c[i + 1]);
            if (t * base <= 0) {
                break;
            }
            total += t;
        }
        for (int i = at + 1; i + 1 < c.length && c[i - 1].distance(c[i]) < SPLIT_LINK_M; i++) {
            double t = signedTurn(c[i - 1], c[i], c[i + 1]);
            if (t * base <= 0) {
                break;
            }
            total += t;
        }
        return Math.abs(total);
    }

    private static double signedTurn(Coordinate a, Coordinate b, Coordinate c) {
        double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        return Math.copySign(turn(a, b, c), cross);
    }

    private static int bent(Coordinate[] c) {
        int count = 0;
        for (int i = 1; i + 1 < c.length; i++) {
            count += Math.abs(turn(c[i - 1], c[i], c[i + 1]) - 90) > RIGHT_TOL_DEG ? 1 : 0;
        }
        return count;
    }

    private static double turn(Coordinate a, Coordinate b, Coordinate c) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = c.x - b.x;
        double vy = c.y - b.y;
        return Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), ux * vx + uy * vy));
    }

    private static final class Chosen {
        final Router.Choice choice;
        final int step;

        Chosen(Router.Choice choice, int step) {
            this.choice = choice;
            this.step = step;
        }
    }

    private static List<Double> key(Coordinate target, double extra) {
        return List.of(target.x, target.y, extra);
    }

    private static double extra(Point target) {
        return target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
    }

    private static boolean overlaps(List<SpecialSpan> spans, double from, double to) {
        for (SpecialSpan span : spans) {
            if (from < span.getToM() && to > span.getFromM()) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double factor) {
        return Math.max(0, Math.min(1, factor));
    }
}
