package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.Route;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.graph.SpecialSpan;
import ru.lct.heatnet.model.ConnectionPoint;

/**
 * Дерево одной врезки эвристикой Такахаши–Мацуямы (D-10): на каждом шаге присоединяется ОКС с самым лёгким
 * маршрутом до уже построенного дерева. Маршрут обрезается в первой точке касания дерева со стороны ОКС, там
 * ставится камера ветвления; если в узле нет места, ответвление переносится на соседнюю точку ствола.
 */
final class TreeBuilder {
    /** Подотрезок и расстояние между узлами не короче метра (правило geometry). */
    static final double MIN_PIECE_M = 1.0;
    static final double MIN_TURN_DEG = 3.0;
    private static final double TOUCH_M = 0.05;
    /** Валидатор не проверяет касание участков в круге 0,15 м вокруг общего узла. */
    private static final double JUNCTION_CLIP_M = 0.15;
    private static final double APART_M = 0.005;
    private static final double SAMPLE_STEP_M = 10;
    private static final double CONNECTION_GAP_M = 3;
    private static final double[] SHIFTS_M = {3, 6, 12, 24};

    private final int nodeLimit;
    private final Map<String, LineString> networkById;
    private final SpecialObjects specials;
    private final GeometryFactory factory = new GeometryFactory();

    /** Место присоединения ветки: узел дерева или точка на ребре. */
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

        Attach(ConnectionPoint connection, Coordinate[] branch, Spot spot, double weight) {
            this.connection = connection;
            this.branch = branch;
            this.spot = spot;
            this.weight = weight;
        }
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

    TreeBuilder(int nodeLimit, Map<String, LineString> networkById, SpecialObjects specials) {
        this.nodeLimit = nodeLimit;
        this.networkById = networkById;
        this.specials = specials;
    }

    /**
     * Дерево от врезки ко всем точкам подключения по графу диаметра dn; маршрут за пределами area отбрасывается.
     * Недостижимые точки попадают в {@link Tree#unconnected}.
     */
    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections) {
        return new Run(router, dn, area, tie).build(connections);
    }

    private final class Run {
        final Router router;
        final ObstacleSet obstacles;
        final int dn;
        final Envelope area;
        final Tree tree;
        final Set<String> ignored;
        // ребро дерева не меняется после создания, а его специальные части и допустимые цели нужны на каждом шаге
        // для каждого оставшегося ОКС
        final Map<Tree.Edge, List<SpecialSpan>> spansByEdge = new IdentityHashMap<>();
        final Map<Tree.Edge, List<Point>> targetsByEdge = new IdentityHashMap<>();

        Run(Router router, int dn, Envelope area, TieCandidate tie) {
            this.router = router;
            this.obstacles = router.obstacles();
            this.dn = dn;
            this.area = area;
            this.tree = new Tree(tie);
            this.ignored = tie.getIgnored();
        }

        Tree build(List<ConnectionPoint> connections) {
            List<ConnectionPoint> remaining = new ArrayList<>(connections);
            while (!remaining.isEmpty()) {
                Attach best = null;
                for (ConnectionPoint connection : remaining) {
                    Attach attach = attach(connection);
                    if (attach != null && (best == null || attach.weight < best.weight)) {
                        best = attach;
                    }
                }
                if (best == null) {
                    tree.unconnected.addAll(remaining);
                    break;
                }
                apply(best);
                remaining.remove(best.connection);
            }
            return tree;
        }

        Attach attach(ConnectionPoint connection) {
            List<Point> targets = targets();
            if (targets.isEmpty()) {
                return null;
            }
            Route route = router.routeToAny(connection.getGeometry(), targets, ignored);
            if (route == null) {
                return null;
            }
            Coordinate[] coords = route.getGeometry().getCoordinates();
            for (Coordinate c : coords) {
                if (!area.contains(c)) {
                    return null;
                }
            }
            List<Piece> pieces = pieces();
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate touch = firstTouch(coords[i], coords[i + 1], pieces);
                if (touch == null) {
                    continue;
                }
                List<Coordinate> head = new ArrayList<>(Arrays.asList(coords).subList(0, i + 1));
                List<Spot> spots = spots(pieces, touch);
                Attach direct = attach(connection, head, spots, pieces, route.getWeight());
                double length = coords[i].distance(coords[i + 1]);
                if (direct != null || length == 0) {
                    // отрезок нулевой длины: точка подключения лежит на дереве, обойти касание не из чего
                    return direct;
                }
                // маршрут подошёл к дереву вдоль ребра, например через точку подключения другого ОКС на той же прямой:
                // прямая ветка к любому месту легла бы на ребро, поэтому ветка обходит точку касания сбоку
                double nx = (coords[i].y - coords[i + 1].y) / length;
                double ny = (coords[i + 1].x - coords[i].x) / length;
                for (double shift : SHIFTS_M) {
                    for (int side : new int[] {1, -1}) {
                        List<Coordinate> around = new ArrayList<>(head);
                        around.add(new Coordinate(touch.x + side * shift * nx, touch.y + side * shift * ny));
                        Attach aside = attach(connection, around, spots, pieces, route.getWeight());
                        if (aside != null) {
                            return aside;
                        }
                    }
                }
                return null;
            }
            return null;
        }

        Attach attach(ConnectionPoint connection, List<Coordinate> head, List<Spot> spots, List<Piece> pieces, double weight) {
            for (Spot spot : spots) {
                Coordinate[] branch = branch(head, spot.point);
                if (branch != null && valid(branch, pieces, spot)) {
                    return new Attach(connection, branch, spot, weight);
                }
            }
            return null;
        }

        /** Цели маршрута: свободные узлы дерева, вершины рёбер и точки вдоль рёбер, где можно поставить камеру. */
        List<Point> targets() {
            List<Point> targets = new ArrayList<>();
            if (tree.degree(tree.root) < tree.tie.getCapacity()) {
                targets.add(factory.createPoint(tree.root.point));
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
                    targets.add(factory.createPoint(indexed.extractPoint(at)));
                }
            }
            return targets;
        }

        List<SpecialSpan> spans(Tree.Edge edge) {
            return spansByEdge.computeIfAbsent(edge, e -> obstacles.spans(e.line, ignored));
        }

        /** Точка на отрезке p–q, где маршрут впервые подходит к дереву ближе TOUCH_M, или null. */
        Coordinate firstTouch(Coordinate p, Coordinate q, List<Piece> pieces) {
            LineSegment route = new LineSegment(p, q);
            double best = Double.POSITIVE_INFINITY;
            if (tree.edges.isEmpty() && route.distance(tree.root.point) <= TOUCH_M) {
                best = clamp(route.projectionFactor(tree.root.point));
            }
            for (Piece piece : pieces) {
                LineSegment segment = piece.segment;
                if (route.distance(segment) > TOUCH_M) {
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

        /** Места присоединения по порядку предпочтения: ближайшее к касанию, затем сдвиги вдоль ствола. */
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

        /**
         * Камера ветвления на ребре: не ближе метра к узлам и вершинам, не в специальной части и не в зоне сближения
         * с объектом специального прохода (узел там лишил бы участок права на отступ через специальный участок),
         * не у точки подключения.
         */
        boolean allowed(Tree.Edge edge, double position, List<SpecialSpan> spans) {
            double length = edge.line.getLength();
            if (position < MIN_PIECE_M || position > length - MIN_PIECE_M) {
                return false;
            }
            if (edge.to.kind == Tree.Kind.CONNECTION && position > length - CONNECTION_GAP_M) {
                return false;
            }
            for (double vertex : vertexPositions(edge.line)) {
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
            return !specials.near(new LengthIndexedLine(edge.line).extractPoint(position), dn);
        }

        Coordinate[] branch(List<Coordinate> head, Coordinate end) {
            List<Coordinate> coords = new ArrayList<>(head);
            while (!coords.isEmpty() && coords.get(coords.size() - 1).distance(end) < 1e-6) {
                coords.remove(coords.size() - 1);
            }
            coords.add(end);
            if (coords.size() < 2) {
                return null;
            }
            straighten(coords, obstacles, ignored);
            return coords.toArray(new Coordinate[0]);
        }

        boolean valid(Coordinate[] branch, List<Piece> pieces, Spot spot) {
            for (int i = 0; i + 1 < branch.length; i++) {
                if (Double.isNaN(obstacles.edgeWeight(branch[i], branch[i + 1], ignored))) {
                    return false;
                }
            }
            List<SpecialSpan> spans = obstacles.spans(factory.createLineString(branch), ignored);
            double at = 0;
            for (int i = 0; i + 1 < branch.length; i++) {
                double length = branch[i].distance(branch[i + 1]);
                if (length < MIN_PIECE_M && !overlaps(spans, at, at + length)) {
                    return false;
                }
                at += length;
            }
            for (int i = 1; i + 1 < branch.length; i++) {
                if (deflectionDeg(branch[i - 1], branch[i], branch[i + 1]) < MIN_TURN_DEG) {
                    return false;
                }
            }
            int last = branch.length - 1;
            LineSegment tail = new LineSegment(branch[last - 1], branch[last]);
            if (tail.getLength() <= JUNCTION_CLIP_M) {
                return false;
            }
            Coordinate[] clipped = branch.clone();
            clipped[last] = tail.pointAlong(1 - JUNCTION_CLIP_M / tail.getLength());
            for (int i = 0; i < last; i++) {
                LineSegment segment = new LineSegment(clipped[i], clipped[i + 1]);
                for (Piece piece : pieces) {
                    if (segment.distance(piece.segment) <= APART_M) {
                        return false;
                    }
                }
            }
            return spot.node != tree.root || leavesNetwork(tail);
        }

        /** Отрезок от врезки не идёт вдоль участков, которых врезка касается: дальше 0,5 м он их не пересекает. */
        boolean leavesNetwork(LineSegment tail) {
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

        void apply(Attach attach) {
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

    /** Часть полилинии; конец у камеры ветвления ставится ровно в её точку. */
    private LineString part(LineString line, double from, double to, Coordinate junction, boolean junctionAtStart) {
        Coordinate[] coords = new LengthIndexedLine(line).extractLine(from, to).getCoordinates();
        coords[junctionAtStart ? 0 : coords.length - 1] = junction;
        return factory.createLineString(coords);
    }

    /** Удаляет вершины с отклонением меньше 3° и у подотрезков короче метра, если спрямлённый отрезок допустим. */
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
