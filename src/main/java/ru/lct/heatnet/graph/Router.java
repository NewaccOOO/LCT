package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
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
    /** Срезка углов, см. cutPass: проходы, шаги поиска наибольшего среза и зазор хорды до других рёбер дерева. */
    private static final int CUT_PASSES = 2;
    private static final int CUT_STEPS = 6;
    private static final double CUT_APART_M = 0.5;
    /** Запас хорды к отступу, как у узлов графа: сборка может поднять Ду по длине на ступень, см. ObstacleSet#plain. */
    private static final double CUT_MARGIN_M = 0.15;
    /** Подотрезок после срезки не короче метра с запасом: check18 видит 1,00 м после округления координат как 0,999. */
    private static final double CUT_PIECE_M = 1.05;
    private static final double UNKNOWN = Double.NEGATIVE_INFINITY;
    /** Предел состояний точного поиска: дальше перебор считается безнадёжным и маршрут не ищется. */
    private static final int EXACT_STATES = 100_000;
    private static final double HYPOT_TOL = 1e-15;
    /** Запас предела перебора в routeToAny: на порядки больше ошибки округления суммы весов. */
    private static final double CAP_TOL = 1e-9;
    private static final double MAX_TURN_COS = Math.cos(Math.toRadians(MAX_TURN_DEG));
    private static final double TURN_COS_TOL = 1e-9;

    private final ObstacleSet obstacles;
    private final GeometryFactory factory = new GeometryFactory();
    private final List<Coordinate> nodes;
    /** Координаты узлов подряд (x, y): оценка через узел в routeToAny перебирает все узлы на каждую цель. */
    private final double[] nodeXY;
    private final int[][] adjacency;
    private final double[][] adjacencyWeight;
    /** Номер первого ребра узла в сквозной нумерации рёбер adjacency, последний элемент — число рёбер. */
    private final int[] edgeStart;
    // веса точек запроса и таблицы Дейкстры: одни и те же точки запрашиваются десятки и сотни раз за расчёт
    private final RouteCache cache;
    private final java.util.concurrent.atomic.AtomicLong tableRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong tableHits = new java.util.concurrent.atomic.AtomicLong();

    /** Таблица Дейкстры от точки запроса: расстояния до узлов, предшественники и узлы по возрастанию расстояния. */
    private static final class Table {
        final double[] dist;
        final int[] pred;
        final int[] order;

        Table(double[] dist, int[] pred, int[] order) {
            this.dist = dist;
            this.pred = pred;
            this.order = order;
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
        obstacles = new ObstacleSet(index, rules, area, dn, corridor, cache);
        nodes = obstacles.nodes();
        int n = nodes.size();
        nodeXY = new double[2 * n];
        for (int i = 0; i < n; i++) {
            nodeXY[2 * i] = nodes.get(i).x;
            nodeXY[2 * i + 1] = nodes.get(i).y;
        }
        List<List<Integer>> to = new ArrayList<>();
        List<List<Double>> weight = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            to.add(new ArrayList<>());
            weight.add(new ArrayList<>());
        }
        // перебор O(n²) пар, но геометрия проверяется только у рёбер, касательных к зонам в обоих концах; пары
        // считаются параллельно по i, каждая нить пишет только свои списки, симметричные рёбра добавляются потом
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            double x = nodeXY[2 * i];
            double y = nodeXY[2 * i + 1];
            for (int j = 0; j < i; j++) {
                if (!obstacles.tangent(i, nodeXY[2 * j], nodeXY[2 * j + 1]) || !obstacles.tangent(j, x, y)) {
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
        edgeStart = new int[n + 1];
        for (int i = 0; i < n; i++) {
            edgeStart[i + 1] = edgeStart[i] + adjacency[i].length;
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
        return routeToAny(from, targets, ignored, false);
    }

    /** То же; {@code cut} — срезать углы пути у вершин зон, см. {@link #cutPass}. */
    public Route routeToAny(Point from, Collection<Point> targets, Set<String> ignored, boolean cut) {
        Choice choice = choose(from.getCoordinate(), targets, ignored, Double.POSITIVE_INFINITY);
        return choice == null ? null : route(from, choice, ignored, cut);
    }

    /**
     * Цель, которую выбирает {@link #routeToAny}: точка цели, узел графа перед ней (-1 — прямой отрезок) и вес с
     * надбавкой. {@code tied} — другая цель списка дала тот же вес или могла его дать: при равенстве выбор зависит от
     * порядка целей.
     */
    public static final class Choice {
        final Coordinate target;
        final int via;
        final double weight;
        final double extra;
        final boolean tied;
        /** Предшественники таблицы Дейкстры, по которым строится путь до via. */
        final int[] pred;

        Choice(Coordinate target, int via, double weight, double extra, boolean tied, int[] pred) {
            this.target = target;
            this.via = via;
            this.weight = weight;
            this.extra = extra;
            this.tied = tied;
            this.pred = pred;
        }

        public Coordinate target() {
            return target;
        }

        public double weight() {
            return weight;
        }

        public double extra() {
            return extra;
        }

        public boolean tied() {
            return tied;
        }
    }

    /**
     * Цель с наименьшим весом с надбавкой среди тех, чей вес меньше bound, при равенстве — первая по порядку; null —
     * такой нет. Вес цели от других целей не зависит, поэтому выбор по части списка сравним с выбором по всему.
     */
    public Choice choose(Coordinate source, Collection<Point> targets, Set<String> ignored, double bound) {
        Table table = table(source, ignored);
        double[] dist = table.dist;
        int[] pred = table.pred;
        int[] order = table.order;
        double bestWeight = bound;
        Coordinate bestTarget = null;
        int bestVia = -1;
        double bestExtra = 0;
        boolean tied = false;
        for (Point target : targets) {
            Coordinate t = target.getCoordinate();
            double extra = target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
            // вес не меньше расстояния по прямой: цель дальше уже найденного веса не может выиграть, её веса до узлов
            // не считаются (это самая дорогая часть: цели дерева меняются с каждым черновиком и в кэш не попадают)
            double lower = t.distance(source) + extra;
            if (lower >= bestWeight) {
                tied |= lower == bestWeight && bestTarget != null;
                continue;
            }
            double direct = obstacles.edgeWeight(t, source, ignored, false, false);
            double weight = Double.isNaN(direct) ? Double.POSITIVE_INFINITY : direct;
            // цель выигрывает, только если её вес с надбавкой меньше лучшего, поэтому узлы дальше этого веса (с запасом
            // на округление) не перебираются: без предела цель без прямой видимости обходила все узлы графа. Узел и
            // цель выбираются те же, что без предела
            double cap = bestWeight - extra;
            cap += CAP_TOL * (1 + Math.abs(cap));
            boolean capped = weight > cap;
            if (capped) {
                weight = cap;
            }
            int via = -1;
            double[] toNodes = partialWeights(t, ignored);
            // узлы по возрастанию веса от источника: дальше текущего веса они не выиграют. Выбор тот же, что у
            // перебора по номерам: наименьший вес, при равенстве — прямой отрезок, затем меньший номер узла
            for (int v : order) {
                if (dist[v] > weight) {
                    break;
                }
                // нижняя оценка через узел: до него по графу плюс по прямой; вес до узла считается только если она не
                // хуже текущего
                if (beyond(dist[v], nodeXY[2 * v], nodeXY[2 * v + 1], t, weight)) {
                    continue;
                }
                if (toNodes[v] == UNKNOWN) {
                    toNodes[v] = obstacles.tangent(v, t) ? obstacles.edgeWeight(t, nodes.get(v), ignored, false, true) : Double.NaN;
                }
                double total = dist[v] + toNodes[v];
                if ((total < weight || total == weight && via >= 0 && v < via)
                        && turnAllowed(pred[v] < 0 ? source : nodes.get(pred[v]), nodes.get(v), t)) {
                    weight = total;
                    via = v;
                }
            }
            if (capped && via < 0) {
                continue;
            }
            if (weight + extra < bestWeight) {
                bestWeight = weight + extra;
                bestTarget = t;
                bestVia = via;
                bestExtra = extra;
                tied = false;
            } else if (weight + extra == bestWeight && bestTarget != null) {
                tied = true;
            }
        }
        return bestTarget == null ? null : new Choice(bestTarget, bestVia, bestWeight, bestExtra, tied, pred);
    }

    /** Маршрут до цели choice, выбранной {@link #choose} из точки from. */
    public Route route(Point from, Choice choice, Set<String> ignored, boolean cut) {
        Coordinate source = from.getCoordinate();
        List<Coordinate> coords = new ArrayList<>();
        for (int v = choice.via; v >= 0; v = choice.pred[v]) {
            coords.add(0, nodes.get(v));
        }
        coords.add(0, source);
        coords.add(choice.target);
        straighten(coords, ignored);
        if (cut) {
            cutCorners(coords, ignored);
        }
        LineString line = from.getFactory().createLineString(coords.toArray(new Coordinate[0]));
        List<SpecialSpan> spans = obstacles.spans(line, ignored);
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans) + choice.extra, spans);
    }

    /**
     * Маршрут с точным правилом поворотов: состояние поиска — узел и то, откуда в него пришли. Обычный поиск держит
     * один предшественник на узел и не находит путь, в который нужно войти с другой стороны. Перебор дороже, поэтому
     * вызывается только для точки, которая иначе остаётся без сети (п. 2.5). {@code null} — пути нет или перебор
     * дошёл до предела состояний.
     */
    public Route routeExact(Point start, Collection<Point> targets, Set<String> ignored, Coordinate incoming, boolean cut) {
        Coordinate source = start.getCoordinate();
        // состояния зависят только от точки выхода и направления, а кандидатов врезки у точки несколько
        Exact search = cache.computeIfAbsent(List.of(this, "exact", source.x, source.y, ignored, incoming.x, incoming.y),
                64L * nodes.size(), () -> exactStates(source, ignored, incoming));
        // даже без состояний цель бывает видна из точки выхода напрямую
        return exactBest(start, targets, ignored, incoming, search.dist, search.came, cut);
    }

    /**
     * Состояния точного поиска: вес до каждой пары «узел, предшественник» и путь к ней: came по номеру состояния
     * (см. {@link #stateIndex}) — ключ состояния, из которого пришли, -1 — из точки запроса.
     */
    private static final class Exact {
        final Map<Long, Double> dist;
        final long[] came;

        Exact(Map<Long, Double> dist, long[] came) {
            this.dist = dist;
            this.came = came;
        }
    }

    private Exact exactStates(Coordinate source, Set<String> ignored, Coordinate incoming) {
        int n = nodes.size();
        double[] toNode = nodeWeights(source, ignored);
        // dist отдаёт состояния exactBest в своём порядке обхода, а поиск только читает веса: чтение идёт из копии
        // в массиве, у HashMap<Long, Double> оно было половиной времени поиска. Состояние (w, v) — ребро графа v→w,
        // его номер edgeStart[v] + k; начальное состояние (v, -1) — номер edges + v
        Map<Long, Double> dist = new HashMap<>();
        int edges = edgeStart[n];
        double[] known = new double[edges + n];
        Arrays.fill(known, Double.POSITIVE_INFINITY);
        long[] came = new long[edges + n];
        Arrays.fill(came, -1);
        Heap heap = new Heap();
        for (int v = 0; v < n; v++) {
            if (!Double.isNaN(toNode[v]) && turnAllowed(incoming, source, nodes.get(v))) {
                dist.put(state(v, -1, n), toNode[v]);
                known[edges + v] = toNode[v];
                heap.add(toNode[v], v, -1, edges + v);
            }
        }
        for (int expanded = 0; !heap.isEmpty(); expanded++) {
            if (expanded > EXACT_STATES) {
                LOG.debug("routeExact: предел состояний {} при {} узлах", EXACT_STATES, n);
                return new Exact(Map.of(), new long[0]);
            }
            heap.poll();
            double reached = heap.key;
            int v = heap.node;
            int p = heap.pred;
            if (reached > known[heap.id] + 1e-9) {
                continue;
            }
            double beforeX = p < 0 ? source.x : nodeXY[2 * p];
            double beforeY = p < 0 ? source.y : nodeXY[2 * p + 1];
            for (int k = 0; k < adjacency[v].length; k++) {
                int w = adjacency[v][k];
                if (!turnAllowed(beforeX, beforeY, nodeXY[2 * v], nodeXY[2 * v + 1], nodeXY[2 * w], nodeXY[2 * w + 1])) {
                    continue;
                }
                double weight = reached + adjacencyWeight[v][k];
                int edge = edgeStart[v] + k;
                if (weight < known[edge] - 1e-9) {
                    long next = state(w, v, n);
                    dist.put(next, weight);
                    known[edge] = weight;
                    came[edge] = state(v, p, n);
                    heap.add(weight, w, v, edge);
                }
            }
        }
        LOG.debug("routeExact: узлов {}, состояний {}", n, dist.size());
        return new Exact(dist, came);
    }

    /** Лучшая цель по состояниям точного поиска и маршрут до неё. */
    private Route exactBest(Point start, Collection<Point> targets, Set<String> ignored, Coordinate incoming,
            Map<Long, Double> dist, long[] came, boolean cut) {
        int n = nodes.size();
        Coordinate source = start.getCoordinate();
        double bestWeight = Double.POSITIVE_INFINITY;
        Coordinate bestTarget = null;
        long bestState = -1;
        double bestExtra = 0;
        for (Point target : targets) {
            Coordinate t = target.getCoordinate();
            double extra = target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
            if (turnAllowed(incoming, source, t)) {
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
                        || !turnAllowed(before, nodes.get(v), t)) {
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
        for (long state = bestState; state >= 0; state = came[stateIndex(state, n)]) {
            coords.add(0, nodes.get((int) (state / (n + 1))));
        }
        coords.add(0, source);
        coords.add(bestTarget);
        straighten(coords, ignored);
        if (cut) {
            cutCorners(coords, ignored);
        }
        LineString line = start.getFactory().createLineString(coords.toArray(new Coordinate[0]));
        List<SpecialSpan> spans = obstacles.spans(line, ignored);
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans) + bestExtra, spans);
    }

    /**
     * Двоичная куча на массивах для Дейкстры и точного поиска: те же сравнения и перестановки, что у PriorityQueue с
     * компаратором по весу (JDK 11), поэтому порядок извлечения при равных весах тот же, но без массива на каждое
     * добавление. poll кладёт извлечённое в key, node, pred, id.
     */
    static final class Heap {
        private double[] keys = new double[64];
        /** По три числа на элемент: node, pred, id. */
        private int[] values = new int[3 * 64];
        private int size;
        double key;
        int node;
        int pred;
        int id;

        boolean isEmpty() {
            return size == 0;
        }

        void add(double key, int node, int pred, int id) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, 2 * size);
                values = Arrays.copyOf(values, 6 * size);
            }
            int at = size++;
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                if (Double.compare(key, keys[parent]) >= 0) {
                    break;
                }
                move(parent, at);
                at = parent;
            }
            set(at, key, node, pred, id);
        }

        void poll() {
            key = keys[0];
            node = values[0];
            pred = values[1];
            id = values[2];
            int n = --size;
            if (n == 0) {
                return;
            }
            double lastKey = keys[n];
            int lastNode = values[3 * n];
            int lastPred = values[3 * n + 1];
            int lastId = values[3 * n + 2];
            int at = 0;
            int half = n >>> 1;
            while (at < half) {
                int child = 2 * at + 1;
                if (child + 1 < n && Double.compare(keys[child], keys[child + 1]) > 0) {
                    child++;
                }
                if (Double.compare(lastKey, keys[child]) <= 0) {
                    break;
                }
                move(child, at);
                at = child;
            }
            set(at, lastKey, lastNode, lastPred, lastId);
        }

        private void move(int from, int to) {
            keys[to] = keys[from];
            System.arraycopy(values, 3 * from, values, 3 * to, 3);
        }

        private void set(int at, double key, int node, int pred, int id) {
            keys[at] = key;
            values[3 * at] = node;
            values[3 * at + 1] = pred;
            values[3 * at + 2] = id;
        }
    }

    /** Номер состояния с ключом key в сквозной нумерации рёбер: ребро p→v или edges + v у начального (v, -1). */
    private int stateIndex(long key, int n) {
        int v = (int) (key / (n + 1));
        int p = (int) (key % (n + 1));
        if (p == n) {
            return edgeStart[n] + v;
        }
        int k = 0;
        while (adjacency[p][k] != v) {
            k++;
        }
        return edgeStart[p] + k;
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
        int[] order = new int[n];
        int settled = 0;
        Heap heap = new Heap();
        for (int v = 0; v < n; v++) {
            if (!Double.isNaN(dist[v])) {
                heap.add(dist[v], v, 0, 0);
            } else {
                dist[v] = Double.POSITIVE_INFINITY;
            }
        }
        while (!heap.isEmpty()) {
            heap.poll();
            int v = heap.node;
            if (done[v] || heap.key > dist[v]) {
                continue;
            }
            done[v] = true;
            order[settled++] = v;
            double beforeX = pred[v] < 0 ? source.x : nodeXY[2 * pred[v]];
            double beforeY = pred[v] < 0 ? source.y : nodeXY[2 * pred[v] + 1];
            for (int k = 0; k < adjacency[v].length; k++) {
                int w = adjacency[v][k];
                double candidate = dist[v] + adjacencyWeight[v][k];
                // ponytail: поворот считается по предшественнику узла, а не по состоянию (узел, направление): путь
                // может быть не кратчайшим среди путей с поворотами до 90°, зато таблица остаётся O(узлов)
                if (candidate < dist[w]
                        && turnAllowed(beforeX, beforeY, nodeXY[2 * v], nodeXY[2 * v + 1], nodeXY[2 * w], nodeXY[2 * w + 1])) {
                    dist[w] = candidate;
                    pred[w] = v;
                    heap.add(candidate, w, 0, 0);
                }
            }
        }
        Table table = new Table(dist, pred, Arrays.copyOf(order, settled));
        cache.put(key, table, 16L * n);
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

    /** Срезка углов маршрута: до CUT_PASSES проходов {@link #cutPass}, второй срезает углы хорд первого. */
    private void cutCorners(List<Coordinate> coords, Set<String> ignored) {
        for (int pass = 0; pass < CUT_PASSES && cutPass(coords, ignored, null, null); pass++) {
            // проход уже заменил вершины в coords
        }
    }

    /**
     * Один проход срезки углов ломаной. Узлы графа — вершины зон с углами JOIN_MITRE: у угла здания узел стоит на
     * d·√2 от него, а отступ меряется до самого здания. Вершина заменяется хордой между точками на соседних
     * отрезках, самой далёкой от вершины, при которой хорда допустима и не пересекает объектов специального прохода.
     * Вершина берёт не больше половины соседних отрезков, поэтому срезки не перекрываются, а подотрезки остаются не
     * короче метра; вершины у специальных частей не трогаются: спецпроход — один прямой участок. {@code keep} —
     * вершина, которую не трогать, или null; {@code apart} — отрезки других рёбер дерева, к которым хорда, как и к
     * своей ломаной вне вершины, не ближе CUT_APART_M; без apart срез кэшируется по тройке вершин. true — что-то
     * срезано, coords заменены.
     */
    public boolean cutPass(List<Coordinate> coords, Set<String> ignored, Coordinate keep, List<LineSegment> apart) {
        if (coords.size() < 3) {
            return false;
        }
        List<SpecialSpan> spans = obstacles.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
        List<Coordinate> result = new ArrayList<>(List.of(coords.get(0)));
        double at = 0;
        for (int i = 1; i + 1 < coords.size(); i++) {
            Coordinate prev = coords.get(i - 1);
            Coordinate cur = coords.get(i);
            Coordinate next = coords.get(i + 1);
            double before = prev.distance(cur);
            double after = cur.distance(next);
            at += before;
            double turn = deflectionDeg(prev, cur, next);
            double room = Math.min(before, after) / 2 - CUT_PIECE_M / 2;
            double least = CUT_PIECE_M / 2 / Math.cos(Math.toRadians(turn / 2));
            double cut;
            if (cur == keep || turn < 2 * PUSH_TURN_DEG || room <= least
                    || overlapsSpan(spans, at - before - MIN_PIECE_M, at + after + MIN_PIECE_M)) {
                cut = 0;
            } else if (apart == null) {
                cut = cache.computeIfAbsent(List.of(this, "cut", prev.x, prev.y, cur.x, cur.y, next.x, next.y, ignored),
                        8, () -> longestCut(prev, cur, next, least, room, ignored, List.of()));
            } else {
                List<LineSegment> near = new ArrayList<>(apart);
                for (int k = 0; k + 1 < coords.size(); k++) {
                    if (k != i - 1 && k != i) {
                        near.add(new LineSegment(coords.get(k), coords.get(k + 1)));
                    }
                }
                cut = longestCut(prev, cur, next, least, room, ignored, near);
            }
            if (cut > 0) {
                result.add(toward(cur, prev, cut));
                result.add(toward(cur, next, cut));
            } else {
                result.add(cur);
            }
        }
        result.add(coords.get(coords.size() - 1));
        if (result.size() == coords.size()) {
            return false;
        }
        coords.clear();
        coords.addAll(result);
        return true;
    }

    /** Наибольший срез от least до room, при котором хорда годится; 0 — даже least не годится. */
    private double longestCut(Coordinate prev, Coordinate cur, Coordinate next, double least, double room, Set<String> ignored,
            List<LineSegment> apart) {
        if (chord(cur, prev, next, room, ignored, apart)) {
            return room;
        }
        if (!chord(cur, prev, next, least, ignored, apart)) {
            return 0.0;
        }
        double low = least;
        double high = room;
        for (int k = 0; k < CUT_STEPS; k++) {
            double mid = (low + high) / 2;
            if (chord(cur, prev, next, mid, ignored, apart)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Хорда среза cut у вершины cur обычная (без спецпрохода), держит отступы с запасом CUT_MARGIN_M и не ближе
     * CUT_APART_M к отрезкам apart.
     */
    private boolean chord(Coordinate cur, Coordinate prev, Coordinate next, double cut, Set<String> ignored, List<LineSegment> apart) {
        Coordinate a = toward(cur, prev, cut);
        Coordinate b = toward(cur, next, cut);
        if (!obstacles.plain(a, b, ignored, CUT_MARGIN_M)) {
            return false;
        }
        LineSegment chord = new LineSegment(a, b);
        for (LineSegment segment : apart) {
            if (chord.distance(segment) < CUT_APART_M) {
                return false;
            }
        }
        return true;
    }

    private static Coordinate toward(Coordinate from, Coordinate to, double distance) {
        double share = distance / from.distance(to);
        return new Coordinate(from.x + (to.x - from.x) * share, from.y + (to.y - from.y) * share);
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

    /**
     * {@code dist + node.distance(t) > weight} для узла (x, y) без Math.hypot в большинстве случаев: на Java 11 он
     * программный и был седьмой частью расчёта. Корень из суммы квадратов отличается от hypot не больше чем на
     * 4 ulp (если квадраты не уходят в денормалы), поэтому с запасом 1e-15 он решает сравнение так же; сложение
     * монотонно. Спорные случаи считаются через hypot.
     */
    static boolean beyond(double dist, double x, double y, Coordinate t, double weight) {
        double dx = x - t.x;
        double dy = y - t.y;
        double approx = Math.sqrt(dx * dx + dy * dy);
        if (dist + approx * (1 - HYPOT_TOL) > weight) {
            return true;
        }
        if (approx > 1e-100 && dist + approx * (1 + HYPOT_TOL) <= weight) {
            return false;
        }
        return dist + Math.hypot(dx, dy) > weight;
    }

    /**
     * {@code deflectionDeg(a, b, c) <= MAX_TURN_DEG} без двух atan2 (на Java 11 они программные) в большинстве
     * случаев: поворот сравнивается через косинус по скалярному произведению. Ошибка обоих способов меньше 1e-12,
     * поэтому при косинусе дальше TURN_COS_TOL от порога ответ тот же; ближе к порогу считает deflectionDeg.
     */
    static boolean turnAllowed(Coordinate a, Coordinate b, Coordinate c) {
        return turnAllowed(a.x, a.y, b.x, b.y, c.x, c.y);
    }

    /** {@link #turnAllowed(Coordinate, Coordinate, Coordinate)} для точек (ax, ay), (bx, by), (cx, cy). */
    private static boolean turnAllowed(double ax, double ay, double bx, double by, double cx, double cy) {
        double ux = bx - ax;
        double uy = by - ay;
        double vx = cx - bx;
        double vy = cy - by;
        double norm = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        if (norm > 1e-100) {
            double dot = ux * vx + uy * vy;
            if (dot >= (MAX_TURN_COS + TURN_COS_TOL) * norm) {
                return true;
            }
            if (dot <= (MAX_TURN_COS - TURN_COS_TOL) * norm) {
                return false;
            }
        }
        return deflectionDeg(new Coordinate(ax, ay), new Coordinate(bx, by), new Coordinate(cx, cy)) <= MAX_TURN_DEG;
    }

    /** Изменение направления в вершине b пути a–b–c, градусы; 0 — по прямой. */
    public static double deflectionDeg(Coordinate a, Coordinate b, Coordinate c) {
        if (a.equals2D(b) || b.equals2D(c)) {
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
