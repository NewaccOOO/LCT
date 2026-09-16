package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm.SingleSourcePaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.rules.Rules;

/**
 * Кратчайшие маршруты по visibility graph одной области и диаметра. Граф строится в конструкторе один раз,
 * точки запроса добавляются в него на время вызова и удаляются после.
 */
public final class Router {
    private static final double MIN_TURN_DEG = 3;
    private static final double MIN_PIECE_M = 1;
    /** Излом после сдвига вершины с запасом к 3°: соседние вершины при сдвиге тоже меняют излом. */
    private static final double PUSH_TURN_DEG = 6;
    private static final double[] PUSH_STEPS_M = {0.5, 1, 2, 4, 8};
    private static final int MAX_PUSHES = 20;

    private final ObstacleSet obstacles;
    private final GeometryFactory factory = new GeometryFactory();
    private final SimpleWeightedGraph<Integer, DefaultWeightedEdge> graph =
            new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
    private final List<Coordinate> vertices;

    public Router(InputData input, Rules rules, Envelope area, int dn) {
        obstacles = new ObstacleSet(input, rules, area, dn);
        vertices = new ArrayList<>(obstacles.nodes());
        // перебор O(n²) пар, но геометрия проверяется только у рёбер, касательных к зонам в обоих концах
        for (int i = 0; i < vertices.size(); i++) {
            graph.addVertex(i);
            connect(i, i, Set.of());
        }
    }

    public ObstacleSet obstacles() {
        return obstacles;
    }

    /** Кратчайший по весу маршрут или {@code null}, если пути нет. */
    public Route route(Point from, Point to, Set<String> ignored) {
        return routeToAny(from, List.of(to), ignored);
    }

    /** Кратчайший маршрут до ближайшей по весу цели или {@code null}, если ни одна цель не достижима. */
    public synchronized Route routeToAny(Point from, Collection<Point> targets, Set<String> ignored) {
        int base = vertices.size();
        try {
            vertices.add(from.getCoordinate());
            graph.addVertex(base);
            connect(base, base, ignored);
            for (Point target : targets) {
                int id = vertices.size();
                vertices.add(target.getCoordinate());
                graph.addVertex(id);
                connect(id, base + 1, ignored);
            }
            SingleSourcePaths<Integer, DefaultWeightedEdge> paths = new DijkstraShortestPath<>(graph).getPaths(base);
            int best = -1;
            for (int id = base + 1; id < vertices.size(); id++) {
                if (best < 0 || paths.getWeight(id) < paths.getWeight(best)) {
                    best = id;
                }
            }
            GraphPath<Integer, DefaultWeightedEdge> path = best < 0 ? null : paths.getPath(best);
            if (path == null) {
                return null;
            }
            List<Coordinate> coords = new ArrayList<>();
            for (int id : path.getVertexList()) {
                coords.add(vertices.get(id));
            }
            straighten(coords, ignored);
            LineString line = from.getFactory().createLineString(coords.toArray(new Coordinate[0]));
            List<SpecialSpan> spans = obstacles.spans(line, ignored);
            return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans), spans);
        } finally {
            for (int id = vertices.size() - 1; id >= base; id--) {
                graph.removeVertex(id);
                vertices.remove(id);
            }
        }
    }

    // Соединяет вершину id со всеми вершинами [0, count), к которым ведёт допустимый отрезок. Первые
    // obstacles.nodes().size() вершин — узлы графа, остальные — точки запроса, в которых путь начинается или кончается.
    private void connect(int id, int count, Set<String> ignored) {
        Coordinate c = vertices.get(id);
        int nodeCount = obstacles.nodes().size();
        // последовательно: параллельный расчёт над общими геометриями JTS изредка давал разные трассы на одном входе
        double[] weights = IntStream.range(0, count)
                .mapToDouble(other -> tangent(id, other, nodeCount)
                        ? obstacles.edgeWeight(c, vertices.get(other), ignored, id < nodeCount, other < nodeCount)
                        : Double.NaN)
                .toArray();
        for (int other = 0; other < count; other++) {
            if (!Double.isNaN(weights[other])) {
                graph.setEdgeWeight(graph.addEdge(id, other), weights[other]);
            }
        }
    }

    private boolean tangent(int a, int b, int nodeCount) {
        return (a >= nodeCount || obstacles.tangent(a, vertices.get(b)))
                && (b >= nodeCount || obstacles.tangent(b, vertices.get(a)));
    }

    // Убирает вершины с отклонением меньше 3° и подотрезки короче 1 м вне специальных частей, если спрямлённый
    // отрезок остаётся допустимым.
    private void straighten(List<Coordinate> coords, Set<String> ignored) {
        boolean changed = true;
        int pushes = 0;
        while (changed && coords.size() > 2) {
            changed = false;
            List<SpecialSpan> spans = obstacles.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
            double at = coords.get(0).distance(coords.get(1));
            for (int i = 1; i + 1 < coords.size() && !changed; i++) {
                Coordinate prev = coords.get(i - 1);
                Coordinate cur = coords.get(i);
                Coordinate next = coords.get(i + 1);
                double before = prev.distance(cur);
                double after = cur.distance(next);
                boolean flat = 180 - Math.toDegrees(Angle.angleBetween(prev, cur, next)) < MIN_TURN_DEG;
                boolean removable = flat
                        || before < MIN_PIECE_M && !overlapsSpan(spans, at - before, at)
                        || after < MIN_PIECE_M && !overlapsSpan(spans, at, at + after);
                if (removable && !Double.isNaN(obstacles.edgeWeight(prev, next, ignored))) {
                    coords.remove(i);
                    changed = true;
                } else if (flat && pushes < MAX_PUSHES && pushOut(coords, i, ignored)) {
                    pushes++;
                    changed = true;
                }
                at += after;
            }
        }
    }

    /**
     * Излом меньше 3° запрещён, а убрать вершину нельзя: хорда задевает зону. Так бывает у малого препятствия
     * на прямой (опора, точка), где обход по углам зоны почти прямой. Вершина сдвигается от хорды, пока излом
     * не станет не меньше PUSH_TURN_DEG, а оба отрезка допустимыми.
     */
    private boolean pushOut(List<Coordinate> coords, int i, Set<String> ignored) {
        Coordinate prev = coords.get(i - 1);
        Coordinate cur = coords.get(i);
        Coordinate next = coords.get(i + 1);
        LineSegment chord = new LineSegment(prev, next);
        Coordinate foot = chord.project(cur);
        double offset = foot.distance(cur);
        if (offset == 0 || chord.getLength() == 0) {
            return false;
        }
        for (double shift : PUSH_STEPS_M) {
            double scale = (offset + shift) / offset;
            Coordinate moved = new Coordinate(foot.x + (cur.x - foot.x) * scale, foot.y + (cur.y - foot.y) * scale);
            if (180 - Math.toDegrees(Angle.angleBetween(prev, moved, next)) >= PUSH_TURN_DEG
                    && !Double.isNaN(obstacles.edgeWeight(prev, moved, ignored))
                    && !Double.isNaN(obstacles.edgeWeight(moved, next, ignored))) {
                coords.set(i, moved);
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsSpan(List<SpecialSpan> spans, double fromM, double toM) {
        for (SpecialSpan span : spans) {
            if (fromM < span.getToM() && toM > span.getFromM()) {
                return true;
            }
        }
        return false;
    }
}
