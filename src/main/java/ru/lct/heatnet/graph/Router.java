package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
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
 * Кратчайшие маршруты по visibility graph одной области и диаметра. Граф узлов строится в конструкторе один раз;
 * точки запроса в граф не добавляются: их веса до узлов считаются (и кэшируются) отдельно, а Дейкстра идёт по
 * массивам смежности от виртуального источника.
 */
public final class Router {
    private static final double MIN_TURN_DEG = 3;
    private static final double MIN_PIECE_M = 1;
    /** Излом после сдвига вершины с запасом к 3°: соседние вершины при сдвиге тоже меняют излом. */
    private static final double PUSH_TURN_DEG = 6;
    private static final double[] PUSH_STEPS_M = {0.5, 1, 2, 4, 8};
    private static final int MAX_PUSHES = 20;
    // ponytail: точки запроса повторяются десятками раз (одна точка подключения на каждом шаге дерева, одни и те же
    // цели у всех ОКС шага), кэш их весов до узлов графа. 4096 записей по n double; при n в десятки тысяч ужать.
    private static final int WEIGHT_CACHE_SIZE = 4096;

    private final ObstacleSet obstacles;
    private final GeometryFactory factory = new GeometryFactory();
    private final List<Coordinate> nodes;
    private final int[][] adjacency;
    private final double[][] adjacencyWeight;
    private final Map<List<Object>, double[]> weightCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, double[]> eldest) {
            return size() > WEIGHT_CACHE_SIZE;
        }
    };

    public Router(InputData input, Rules rules, Envelope area, int dn) {
        obstacles = new ObstacleSet(input, rules, area, dn);
        nodes = obstacles.nodes();
        int n = nodes.size();
        List<List<Integer>> to = new ArrayList<>();
        List<List<Double>> weight = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            to.add(new ArrayList<>());
            weight.add(new ArrayList<>());
        }
        // перебор O(n²) пар, но геометрия проверяется только у рёбер, касательных к зонам в обоих концах
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < i; j++) {
                if (!obstacles.tangent(i, nodes.get(j)) || !obstacles.tangent(j, nodes.get(i))) {
                    continue;
                }
                double w = obstacles.edgeWeight(nodes.get(i), nodes.get(j), Set.of(), true, true);
                if (!Double.isNaN(w)) {
                    to.get(i).add(j);
                    weight.get(i).add(w);
                    to.get(j).add(i);
                    weight.get(j).add(w);
                }
            }
        }
        adjacency = new int[n][];
        adjacencyWeight = new double[n][];
        for (int i = 0; i < n; i++) {
            adjacency[i] = to.get(i).stream().mapToInt(Integer::intValue).toArray();
            adjacencyWeight[i] = weight.get(i).stream().mapToDouble(Double::doubleValue).toArray();
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
        int n = nodes.size();
        Coordinate source = from.getCoordinate();
        double[] dist = nodeWeights(source, ignored).clone();
        int[] pred = new int[n];
        Arrays.fill(pred, -1);
        boolean[] done = new boolean[n];
        PriorityQueue<double[]> heap = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int v = 0; v < n; v++) {
            if (!Double.isNaN(dist[v])) {
                heap.add(new double[] {dist[v], v});
            } else {
                dist[v] = Double.POSITIVE_INFINITY;
            }
        }
        while (!heap.isEmpty()) {
            double[] top = heap.poll();
            int v = (int) top[1];
            if (done[v] || top[0] > dist[v]) {
                continue;
            }
            done[v] = true;
            for (int k = 0; k < adjacency[v].length; k++) {
                int w = adjacency[v][k];
                double candidate = dist[v] + adjacencyWeight[v][k];
                if (candidate < dist[w]) {
                    dist[w] = candidate;
                    pred[w] = v;
                    heap.add(new double[] {candidate, w});
                }
            }
        }
        double bestWeight = Double.POSITIVE_INFINITY;
        Coordinate bestTarget = null;
        int bestVia = -1;
        for (Point target : targets) {
            Coordinate t = target.getCoordinate();
            double direct = obstacles.edgeWeight(t, source, ignored, false, false);
            double weight = Double.isNaN(direct) ? Double.POSITIVE_INFINITY : direct;
            int via = -1;
            double[] toNodes = nodeWeights(t, ignored);
            for (int v = 0; v < n; v++) {
                if (!Double.isNaN(toNodes[v]) && dist[v] + toNodes[v] < weight) {
                    weight = dist[v] + toNodes[v];
                    via = v;
                }
            }
            if (weight < bestWeight) {
                bestWeight = weight;
                bestTarget = t;
                bestVia = via;
            }
        }
        if (bestTarget == null) {
            return null;
        }
        List<Coordinate> coords = new ArrayList<>();
        for (int v = bestVia; v >= 0; v = pred[v]) {
            coords.add(0, nodes.get(v));
        }
        coords.add(0, source);
        coords.add(bestTarget);
        straighten(coords, ignored);
        LineString line = from.getFactory().createLineString(coords.toArray(new Coordinate[0]));
        List<SpecialSpan> spans = obstacles.spans(line, ignored);
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans), spans);
    }

    /**
     * Веса от точки запроса до узлов графа, NaN — отрезок недопустим. Не зависят от других точек запроса, поэтому
     * кэшируются по координате и набору пропускаемых объектов. Считаются последовательно: параллельный расчёт над
     * общими геометриями JTS изредка давал разные трассы на одном входе.
     */
    private double[] nodeWeights(Coordinate c, Set<String> ignored) {
        return weightCache.computeIfAbsent(List.of(c.x, c.y, ignored), key -> {
            double[] weights = new double[nodes.size()];
            for (int v = 0; v < weights.length; v++) {
                weights[v] = obstacles.tangent(v, c) ? obstacles.edgeWeight(c, nodes.get(v), ignored, false, true) : Double.NaN;
            }
            return weights;
        });
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
