package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
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
 * массивам смежности от виртуального источника. Поворот в вершине не круче {@link #MAX_TURN_DEG}: ребро, которое
 * ломает путь к вершине сильнее, при релаксации пропускается (приложение 18.09, п. 2.1).
 */
public final class Router {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Router.class);
    /** Допустимый поворот 90° с запасом на округление координат выхода до 9 знаков градуса. */
    public static final double MAX_TURN_DEG = 89.9;
    private static final double MIN_TURN_DEG = 3;
    private static final double MIN_PIECE_M = 1;
    /** Излом после сдвига вершины с запасом к 3°: соседние вершины при сдвиге тоже меняют излом. */
    private static final double PUSH_TURN_DEG = 6;
    private static final double[] PUSH_STEPS_M = {0.5, 1, 2, 4, 8};
    private static final int MAX_PUSHES = 20;
    private static final double UNKNOWN = Double.NEGATIVE_INFINITY;
    /** Предел состояний точного поиска: дальше перебор считается безнадёжным и маршрут не ищется. */
    private static final int EXACT_STATES = 100_000;

    private final ObstacleSet obstacles;
    private final GeometryFactory factory = new GeometryFactory();
    private final List<Coordinate> nodes;
    private final int[][] adjacency;
    private final double[][] adjacencyWeight;
    // веса точек запроса и таблицы Дейкстры: одни и те же точки запрашиваются десятки и сотни раз за расчёт
    private final RouteCache cache;
    private final java.util.concurrent.atomic.AtomicLong tableRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong tableHits = new java.util.concurrent.atomic.AtomicLong();

    /** Таблица Дейкстры от точки запроса: расстояния до узлов и предшественники. */
    private static final class Table {
        final double[] dist;
        final int[] pred;

        Table(double[] dist, int[] pred) {
            this.dist = dist;
            this.pred = pred;
        }
    }

    public Router(InputData input, Rules rules, Envelope area, int dn) {
        this(new ObstacleIndex(input, rules), rules, area, dn);
    }

    public Router(ObstacleIndex index, Rules rules, Envelope area, int dn) {
        this(index, rules, area, dn, new RouteCache(RouteCache.DEFAULT_MB));
    }

    public Router(ObstacleIndex index, Rules rules, Envelope area, int dn, RouteCache cache) {
        this(index, rules, area, dn, cache, null);
    }

    /** С коридором: узлы и препятствия только внутри полигона corridor, см. {@link ObstacleSet}. */
    public Router(ObstacleIndex index, Rules rules, Envelope area, int dn, RouteCache cache, org.locationtech.jts.geom.Geometry corridor) {
        this.cache = cache;
        obstacles = new ObstacleSet(index, rules, area, dn, corridor);
        nodes = obstacles.nodes();
        int n = nodes.size();
        List<List<Integer>> to = new ArrayList<>();
        List<List<Double>> weight = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            to.add(new ArrayList<>());
            weight.add(new ArrayList<>());
        }
        // перебор O(n²) пар, но геометрия проверяется только у рёбер, касательных к зонам в обоих концах; пары
        // считаются параллельно по i, каждая нить пишет только свои списки, симметричные рёбра добавляются потом
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            for (int j = 0; j < i; j++) {
                if (!obstacles.tangent(i, nodes.get(j)) || !obstacles.tangent(j, nodes.get(i))) {
                    continue;
                }
                double w = obstacles.edgeWeight(nodes.get(i), nodes.get(j), Set.of(), true, true);
                if (!Double.isNaN(w)) {
                    to.get(i).add(j);
                    weight.get(i).add(w);
                }
            }
        });
        for (int i = 0; i < n; i++) {
            for (int k = 0, count = to.get(i).size(); k < count; k++) {
                int j = to.get(i).get(k);
                to.get(j).add(i);
                weight.get(j).add(weight.get(i).get(k));
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

    /**
     * Кратчайший маршрут до ближайшей по весу цели или {@code null}, если ни одна цель не достижима. Если в
     * {@code userData} цели лежит {@link Double}, это надбавка к её весу в метрах (например, стоимость камеры,
     * которую придётся построить в этой точке); вес маршрута возвращается с надбавкой выбранной цели. Вызывается
     * из нескольких нитей: зоны только читаются (JTS 1.20 готовит индексы под замком), кэш синхронизирован, а
     * ленивые веса до узлов при гонке пишутся одинаковыми.
     */
    public Route routeToAny(Point from, Collection<Point> targets, Set<String> ignored) {
        int n = nodes.size();
        Coordinate source = from.getCoordinate();
        Table table = table(source, ignored);
        double[] dist = table.dist;
        int[] pred = table.pred;
        double bestWeight = Double.POSITIVE_INFINITY;
        Coordinate bestTarget = null;
        int bestVia = -1;
        double bestExtra = 0;
        for (Point target : targets) {
            Coordinate t = target.getCoordinate();
            double extra = target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
            // вес не меньше расстояния по прямой: цель дальше уже найденного веса не может выиграть, её веса до узлов
            // не считаются (это самая дорогая часть: цели дерева меняются с каждым черновиком и в кэш не попадают)
            if (t.distance(source) + extra >= bestWeight) {
                continue;
            }
            double direct = obstacles.edgeWeight(t, source, ignored, false, false);
            double weight = Double.isNaN(direct) ? Double.POSITIVE_INFINITY : direct;
            int via = -1;
            double[] toNodes = partialWeights(t, ignored);
            for (int v = 0; v < n; v++) {
                // нижняя оценка через узел: до него по графу плюс по прямой; вес до узла считается только если она бьёт текущий
                if (dist[v] + nodes.get(v).distance(t) >= weight) {
                    continue;
                }
                if (toNodes[v] == UNKNOWN) {
                    toNodes[v] = obstacles.tangent(v, t) ? obstacles.edgeWeight(t, nodes.get(v), ignored, false, true) : Double.NaN;
                }
                if (!Double.isNaN(toNodes[v]) && dist[v] + toNodes[v] < weight
                        && deflectionDeg(pred[v] < 0 ? source : nodes.get(pred[v]), nodes.get(v), t) <= MAX_TURN_DEG) {
                    weight = dist[v] + toNodes[v];
                    via = v;
                }
            }
            if (weight + extra < bestWeight) {
                bestWeight = weight + extra;
                bestTarget = t;
                bestVia = via;
                bestExtra = extra;
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
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans) + bestExtra, spans);
    }

    /**
     * Маршрут с точным правилом поворотов: состояние поиска — узел и то, откуда в него пришли. Обычный поиск держит
     * один предшественник на узел и не находит путь, в который нужно войти с другой стороны. Перебор дороже, поэтому
     * вызывается только для точки, которая иначе остаётся без сети (п. 2.5). {@code null} — пути нет или перебор
     * дошёл до предела состояний.
     */
    public Route routeExact(Point start, Collection<Point> targets, Set<String> ignored, Coordinate incoming) {
        Coordinate source = start.getCoordinate();
        // состояния зависят только от точки выхода и направления, а кандидатов врезки у точки несколько
        Exact search = cache.computeIfAbsent(List.of(this, "exact", source.x, source.y, ignored, incoming.x, incoming.y),
                64L * nodes.size(), () -> exactStates(source, ignored, incoming));
        // даже без состояний цель бывает видна из точки выхода напрямую
        return exactBest(start, targets, ignored, incoming, search.dist, search.came);
    }

    /** Состояния точного поиска: вес до каждой пары «узел, предшественник» и путь к ней. */
    private static final class Exact {
        final Map<Long, Double> dist;
        final Map<Long, Long> came;

        Exact(Map<Long, Double> dist, Map<Long, Long> came) {
            this.dist = dist;
            this.came = came;
        }
    }

    private Exact exactStates(Coordinate source, Set<String> ignored, Coordinate incoming) {
        int n = nodes.size();
        double[] toNode = nodeWeights(source, ignored);
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> came = new HashMap<>();
        PriorityQueue<double[]> heap = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int v = 0; v < n; v++) {
            if (!Double.isNaN(toNode[v]) && deflectionDeg(incoming, source, nodes.get(v)) <= MAX_TURN_DEG) {
                dist.put(state(v, -1, n), toNode[v]);
                heap.add(new double[] {toNode[v], v, -1});
            }
        }
        for (int expanded = 0; !heap.isEmpty(); expanded++) {
            if (expanded > EXACT_STATES) {
                LOG.debug("routeExact: предел состояний {} при {} узлах", EXACT_STATES, n);
                return new Exact(Map.of(), Map.of());
            }
            double[] top = heap.poll();
            int v = (int) top[1];
            int p = (int) top[2];
            if (top[0] > dist.getOrDefault(state(v, p, n), Double.POSITIVE_INFINITY) + 1e-9) {
                continue;
            }
            Coordinate before = p < 0 ? source : nodes.get(p);
            for (int k = 0; k < adjacency[v].length; k++) {
                int w = adjacency[v][k];
                if (deflectionDeg(before, nodes.get(v), nodes.get(w)) > MAX_TURN_DEG) {
                    continue;
                }
                double weight = top[0] + adjacencyWeight[v][k];
                long next = state(w, v, n);
                if (weight < dist.getOrDefault(next, Double.POSITIVE_INFINITY) - 1e-9) {
                    dist.put(next, weight);
                    came.put(next, state(v, p, n));
                    heap.add(new double[] {weight, w, v});
                }
            }
        }
        LOG.debug("routeExact: узлов {}, состояний {}", n, dist.size());
        return new Exact(dist, came);
    }

    /** Лучшая цель по состояниям точного поиска и маршрут до неё. */
    private Route exactBest(Point start, Collection<Point> targets, Set<String> ignored, Coordinate incoming,
            Map<Long, Double> dist, Map<Long, Long> came) {
        int n = nodes.size();
        Coordinate source = start.getCoordinate();
        double bestWeight = Double.POSITIVE_INFINITY;
        Coordinate bestTarget = null;
        long bestState = -1;
        double bestExtra = 0;
        for (Point target : targets) {
            Coordinate t = target.getCoordinate();
            double extra = target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
            if (deflectionDeg(incoming, source, t) <= MAX_TURN_DEG) {
                double direct = obstacles.edgeWeight(t, source, ignored, false, false);
                if (!Double.isNaN(direct) && direct + extra < bestWeight) {
                    bestWeight = direct + extra;
                    bestTarget = t;
                    bestState = -1;
                    bestExtra = extra;
                }
            }
            double[] toNodes = partialWeights(t, ignored);
            for (Map.Entry<Long, Double> state : dist.entrySet()) {
                int v = (int) (state.getKey() / (n + 1));
                int p = (int) (state.getKey() % (n + 1));
                Coordinate before = p == n ? source : nodes.get(p);
                if (state.getValue() + nodes.get(v).distance(t) + extra >= bestWeight
                        || deflectionDeg(before, nodes.get(v), t) > MAX_TURN_DEG) {
                    continue;
                }
                if (toNodes[v] == UNKNOWN) {
                    toNodes[v] = obstacles.tangent(v, t) ? obstacles.edgeWeight(t, nodes.get(v), ignored, false, true)
                            : Double.NaN;
                }
                if (!Double.isNaN(toNodes[v]) && state.getValue() + toNodes[v] + extra < bestWeight) {
                    bestWeight = state.getValue() + toNodes[v] + extra;
                    bestTarget = t;
                    bestState = state.getKey();
                    bestExtra = extra;
                }
            }
        }
        if (bestTarget == null) {
            return null;
        }
        List<Coordinate> coords = new ArrayList<>();
        for (long state = bestState; state >= 0; state = came.getOrDefault(state, -1L)) {
            coords.add(0, nodes.get((int) (state / (n + 1))));
        }
        coords.add(0, source);
        coords.add(bestTarget);
        straighten(coords, ignored);
        LineString line = start.getFactory().createLineString(coords.toArray(new Coordinate[0]));
        List<SpecialSpan> spans = obstacles.spans(line, ignored);
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans) + bestExtra, spans);
    }

    /** Состояние точного поиска: узел и предшественник (n — пришли из точки запроса). */
    private static long state(int node, int pred, int n) {
        return (long) node * (n + 1) + (pred < 0 ? n : pred);
    }

    /** Таблица Дейкстры от точки запроса, из кэша по координате и набору пропускаемых объектов. */
    private Table table(Coordinate source, Set<String> ignored) {
        tableRequests.incrementAndGet();
        List<Object> key = List.of(this, "table", source.x, source.y, ignored);
        Table cached = cache.get(key);
        if (cached != null) {
            tableHits.incrementAndGet();
            return cached;
        }
        int n = nodes.size();
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
            Coordinate before = pred[v] < 0 ? source : nodes.get(pred[v]);
            for (int k = 0; k < adjacency[v].length; k++) {
                int w = adjacency[v][k];
                double candidate = dist[v] + adjacencyWeight[v][k];
                // ponytail: поворот считается по предшественнику узла, а не по состоянию (узел, направление): путь
                // может быть не кратчайшим среди путей с поворотами до 90°, зато таблица остаётся O(узлов)
                if (candidate < dist[w] && deflectionDeg(before, nodes.get(v), nodes.get(w)) <= MAX_TURN_DEG) {
                    dist[w] = candidate;
                    pred[w] = v;
                    heap.add(new double[] {candidate, w});
                }
            }
        }
        Table table = new Table(dist, pred);
        cache.put(key, table, 12L * n);
        return table;
    }

    /** Сколько таблиц Дейкстры запрошено и сколько из них взято из кэша. */
    public long[] tableStats() {
        return new long[] {tableRequests.get(), tableHits.get()};
    }

    /**
     * Веса от точки запроса до узлов графа, NaN — отрезок недопустим. Не зависят от других точек запроса, поэтому
     * кэшируются по координате и набору пропускаемых объектов. Считаются последовательно: параллельный расчёт над
     * общими геометриями JTS изредка давал разные трассы на одном входе.
     */
    /** Веса от цели до узлов, считаются лениво по мере надобности; UNKNOWN — ещё не считался. Кэш отдельный от полных. */
    private double[] partialWeights(Coordinate c, Set<String> ignored) {
        return cache.computeIfAbsent(List.of(this, "partial", c.x, c.y, ignored), 8L * nodes.size(), () -> {
            double[] weights = new double[nodes.size()];
            Arrays.fill(weights, UNKNOWN);
            return weights;
        });
    }

    private double[] nodeWeights(Coordinate c, Set<String> ignored) {
        return cache.computeIfAbsent(List.of(this, "weights", c.x, c.y, ignored), 8L * nodes.size(), () -> {
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

    /** Изменение направления в вершине b пути a–b–c, градусы; 0 — по прямой. */
    public static double deflectionDeg(Coordinate a, Coordinate b, Coordinate c) {
        if (a.distance(b) == 0 || b.distance(c) == 0) {
            return 0;
        }
        return 180 - Math.toDegrees(Angle.angleBetween(a, b, c));
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
