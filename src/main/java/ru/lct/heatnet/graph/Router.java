package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
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
    /** Запас формы к отступу: сборка может поднять Ду по длине на ступень, см. ObstacleSet#plain. */
    public static final double CUT_MARGIN_M = 0.15;
    /** Запас хорды срезки к отступу, как у зоны узлов графа, см. {@link #chord}. */
    private static final double CHORD_MARGIN_M = 0.2;
    /** Подотрезок после срезки не короче метра с запасом: check18 видит 1,00 м после округления координат как 0,999. */
    public static final double CUT_PIECE_M = 1.05;
    /** Короткое звено между поворотами, см. {@link #sharpen}: 10 м с запасом на округление координат выхода. */
    private static final double SHORT_LINK_M = 10.05;
    /** Сколько средних прямых окна может остаться при замене поворотов, см. {@link #sharpenWindow}. */
    private static final int SHARPEN_LINES = 3;
    private static final double SHARPEN_EPS = 1e-9;
    /** Точность поворота лучей в {@link #bend}: 0,3 мм поперёк звена 30 м. */
    private static final double BEND_EPS_RAD = 1e-5;
    /** Предел поочерёдных поворотов лучей в {@link #bend}: они сходятся за несколько кругов. */
    private static final int BEND_ROUNDS = 20;
    /** Вершина ближе этого к границе специальной части стоит на ней, см. {@link #drop}. */
    private static final double SPAN_EPS_M = 1e-3;
    /**
     * Мелкий излом — поворот меньше MIN_TURN_DEG, у которого конец короткого плеча уходит с прямой хотя бы на MICRO_M:
     * так он не путается с округлением координат выхода до 9 знаков (десятые доли миллиметра), см. {@link #micro}.
     */
    public static final double MICRO_M = 0.001;
    private static final double UNKNOWN = Double.NEGATIVE_INFINITY;
    /** Предел состояний точного поиска: дальше перебор считается безнадёжным и маршрут не ищется. */
    private static final int EXACT_STATES = 100_000;
    private static final double HYPOT_TOL = 1e-15;
    /** Запас предела перебора в routeToAny: на порядки больше ошибки округления суммы весов. */
    private static final double CAP_TOL = 1e-9;
    private static final double MAX_TURN_COS = Math.cos(Math.toRadians(MAX_TURN_DEG));
    private static final double TURN_COS_TOL = 1e-9;
    /** Запас к порогу расстояния при отсеве пар отрезков по рамкам, см. apart. */
    private static final double GAP_EPS_M = 1e-6;

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

    /**
     * Таблица Дейкстры от точки запроса: расстояния до узлов, предшественники и узлы по возрастанию расстояния.
     * Считается по мере надобности, см. {@link #settle}: choose на наборах доходит до 20–40 % узлов. Шаги поиска те же,
     * что у полного расчёта, только с паузами, поэтому первые settled узлов в order, их dist и pred совпадают с полной
     * таблицей. Узел, попавший в order, больше не меняется: веса рёбер не отрицательны, и более короткого пути до него
     * поиск уже не найдёт.
     */
    private final class Table {
        final double[] dist;
        final int[] pred;
        final int[] order;
        // копия координат, а не ссылка на Coordinate: вызывающий может изменить точку, а поиск продолжится позже
        private final double sourceX;
        private final double sourceY;
        private int settled;
        private boolean[] done;
        private Heap heap = new Heap();

        Table(Coordinate source, double[] dist) {
            int n = dist.length;
            sourceX = source.x;
            sourceY = source.y;
            this.dist = dist;
            pred = new int[n];
            Arrays.fill(pred, -1);
            order = new int[n];
            done = new boolean[n];
            for (int v = 0; v < n; v++) {
                if (!Double.isNaN(dist[v])) {
                    heap.add(dist[v], v, 0, 0);
                } else {
                    dist[v] = Double.POSITIVE_INFINITY;
                }
            }
        }

        /** Продолжает поиск, пока узлов в order меньше count и есть куда идти; возвращает число узлов в order. */
        synchronized int settle(int count) {
            while (settled < count && heap != null) {
                if (heap.isEmpty()) {
                    heap = null;
                    done = null;
                    break;
                }
                heap.poll();
                int v = heap.node;
                if (done[v] || heap.key > dist[v]) {
                    continue;
                }
                done[v] = true;
                order[settled++] = v;
                double beforeX = pred[v] < 0 ? sourceX : nodeXY[2 * pred[v]];
                double beforeY = pred[v] < 0 ? sourceY : nodeXY[2 * pred[v] + 1];
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
            return settled;
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
        // перебор O(n²) пар, но геометрия проверяется только у рёбер, касательных к зонам в обоих концах; пары
        // считаются параллельно по i в ObstacleSet.PARTS, каждая нить пишет только свою строку рёбер к j < i,
        // симметричные рёбра добавляются потом
        int[][] rowTo = new int[n][];
        double[][] rowWeight = new double[n][];
        ObstacleSet.PARTS.submit(() -> java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            double x = nodeXY[2 * i];
            double y = nodeXY[2 * i + 1];
            int[] to = new int[i];
            double[] weight = new double[i];
            int count = 0;
            ObstacleSet.Hint hint = ObstacleSet.hint();
            for (int j = 0; j < i; j++) {
                if (!obstacles.tangent(i, nodeXY[2 * j], nodeXY[2 * j + 1]) || !obstacles.tangent(j, x, y)) {
                    continue;
                }
                double w = obstacles.edgeWeight(nodes.get(i), nodes.get(j), Set.of(), true, true, hint);
                if (!Double.isNaN(w)) {
                    to[count] = j;
                    weight[count++] = w;
                }
            }
            rowTo[i] = Arrays.copyOf(to, count);
            rowWeight[i] = Arrays.copyOf(weight, count);
        })).join();
        // у узла сначала рёбра к меньшим номерам по возрастанию, затем к большим по возрастанию
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            degree[i] += rowTo[i].length;
            for (int j : rowTo[i]) {
                degree[j]++;
            }
        }
        adjacency = new int[n][];
        adjacencyWeight = new double[n][];
        int[] filled = new int[n];
        for (int i = 0; i < n; i++) {
            adjacency[i] = Arrays.copyOf(rowTo[i], degree[i]);
            adjacencyWeight[i] = Arrays.copyOf(rowWeight[i], degree[i]);
            filled[i] = rowTo[i].length;
        }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < rowTo[i].length; k++) {
                int j = rowTo[i][k];
                adjacency[j][filled[j]] = i;
                adjacencyWeight[j][filled[j]++] = rowWeight[i][k];
            }
        }
        edgeStart = new int[n + 1];
        for (int i = 0; i < n; i++) {
            edgeStart[i + 1] = edgeStart[i] + adjacency[i].length;
        }
    }

    public ObstacleSet obstacles() {
        return obstacles;
    }

    /** Значение по ключу key этого маршрутизатора из общего кэша расчёта или посчитанное compute, см. {@link RouteCache}. */
    public <T> T cached(List<Object> key, long bytes, java.util.function.Supplier<T> compute) {
        List<Object> own = new ArrayList<>(key);
        own.add(0, this);
        return cache.computeIfAbsent(own, bytes, compute);
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
        int ready = 0;
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
            ObstacleSet.Hint hint = ObstacleSet.hint();
            // узлы по возрастанию веса от источника: дальше текущего веса они не выиграют. Выбор тот же, что у
            // перебора по номерам: наименьший вес, при равенстве — прямой отрезок, затем меньший номер узла. Таблица
            // досчитывается до очередного узла, только когда перебор до него дошёл
            for (int i = 0; i < ready || (ready = table.settle(i + 1)) > i; i++) {
                int v = order[i];
                if (dist[v] > weight) {
                    break;
                }
                // нижняя оценка через узел: до него по графу плюс по прямой; вес до узла считается только если она не
                // хуже текущего
                if (beyond(dist[v], nodeXY[2 * v], nodeXY[2 * v + 1], t, weight)) {
                    continue;
                }
                if (toNodes[v] == UNKNOWN) {
                    toNodes[v] = obstacles.tangent(v, t) ? obstacles.edgeWeight(t, nodes.get(v), ignored, false, true, hint) : Double.NaN;
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
        List<Coordinate> path = new ArrayList<>();
        for (int v = choice.via; v >= 0; v = choice.pred[v]) {
            path.add(0, nodes.get(v));
        }
        path.add(0, from.getCoordinate());
        path.add(choice.target);
        return shaped(from, path, ignored, cut, choice.extra);
    }

    /** Ломаная маршрута после спрямления и срезки углов и её специальные части. */
    private static final class Shape {
        final Coordinate[] coords;
        final List<SpecialSpan> spans;

        Shape(Coordinate[] coords, List<SpecialSpan> spans) {
            this.coords = coords;
            this.spans = spans;
        }
    }

    /**
     * Маршрут по пути path графа: спрямление, срезка углов при cut, специальные части. Форма зависит только от пути, а
     * путь до той же цели дерево запрашивает на каждом шаге, поэтому она берётся из кэша. Массив линии у каждого
     * маршрута свой: вызывающий может заменить в нём вершины.
     */
    private Route shaped(Point from, List<Coordinate> path, Set<String> ignored, boolean cut, double extra) {
        Shape shape = cache.computeIfAbsent(List.of(this, "route", path, ignored, cut), 96L * path.size(), () -> {
            List<Coordinate> coords = new ArrayList<>(path);
            straighten(coords, ignored);
            if (cut) {
                cutCorners(coords, ignored);
            }
            Coordinate[] line = coords.toArray(new Coordinate[0]);
            return new Shape(line, obstacles.spans(factory.createLineString(line), ignored));
        });
        LineString line = from.getFactory().createLineString(shape.coords.clone());
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), shape.spans) + extra, shape.spans);
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
        return exactBest(start, targets, ignored, incoming, search, cut);
    }

    /**
     * Состояния точного поиска: ключи пар «узел, предшественник» (см. {@link #state}) и веса до них в порядке, в
     * котором их обходила HashMap&lt;Long, Double&gt; прежнего поиска, и путь к ним: came по номеру состояния (см.
     * {@link #stateIndex}) — ключ состояния, из которого пришли, -1 — из точки запроса.
     */
    private static final class Exact {
        final long[] keys;
        final double[] weights;
        final long[] came;

        Exact(long[] keys, double[] weights, long[] came) {
            this.keys = keys;
            this.weights = weights;
            this.came = came;
        }
    }

    private Exact exactStates(Coordinate source, Set<String> ignored, Coordinate incoming) {
        int n = nodes.size();
        double[] toNode = nodeWeights(source, ignored);
        // веса в массиве по номеру состояния: (w, v) — ребро графа v→w, номер edgeStart[v] + k; начальное (v, -1) —
        // номер edges + v. exactBest перебирает состояния в порядке обхода HashMap, в которую их раньше клали по мере
        // нахождения (при равных весах выбор зависит от порядка), поэтому ключи пишутся в порядке первого веса, а
        // порядок обхода считается в конце, см. hashOrder: сама HashMap<Long, Double> была половиной времени поиска
        int edges = edgeStart[n];
        double[] known = new double[edges + n];
        Arrays.fill(known, Double.POSITIVE_INFINITY);
        long[] came = new long[edges + n];
        Arrays.fill(came, -1);
        long[] keys = new long[edges + n];
        int[] found = new int[edges + n];
        int count = 0;
        // ребро, уже достигнутое из узла, легче не станет: веса рёбер неотрицательны, и состояния узла приходят из кучи
        // по возрастанию веса. Поэтому у узла v перебираются только ещё не достигнутые рёбра, их k по возрастанию
        // лежат в pending с edgeStart[v], left[v] — сколько их
        int[] pending = new int[edges];
        int[] left = new int[n];
        for (int v = 0; v < n; v++) {
            left[v] = adjacency[v].length;
            for (int k = 0; k < left[v]; k++) {
                pending[edgeStart[v] + k] = k;
            }
        }
        Heap heap = new Heap();
        for (int v = 0; v < n; v++) {
            if (!Double.isNaN(toNode[v]) && turnAllowed(incoming, source, nodes.get(v))) {
                keys[count] = state(v, -1, n);
                found[count++] = edges + v;
                known[edges + v] = toNode[v];
                heap.add(toNode[v], v, -1, edges + v);
            }
        }
        for (int expanded = 0; !heap.isEmpty(); expanded++) {
            if (expanded > EXACT_STATES) {
                LOG.debug("routeExact: предел состояний {} при {} узлах", EXACT_STATES, n);
                return new Exact(new long[0], new double[0], new long[0]);
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
            int kept = 0;
            for (int i = 0; i < left[v]; i++) {
                int k = pending[edgeStart[v] + i];
                int w = adjacency[v][k];
                double weight = reached + adjacencyWeight[v][k];
                int edge = edgeStart[v] + k;
                if (weight < known[edge] - 1e-9
                        && turnAllowed(beforeX, beforeY, nodeXY[2 * v], nodeXY[2 * v + 1], nodeXY[2 * w], nodeXY[2 * w + 1])) {
                    keys[count] = state(w, v, n);
                    found[count++] = edge;
                    known[edge] = weight;
                    came[edge] = state(v, p, n);
                    heap.add(weight, w, v, edge);
                } else {
                    pending[edgeStart[v] + kept++] = k;
                }
            }
            left[v] = kept;
        }
        LOG.debug("routeExact: узлов {}, состояний {}", n, count);
        int[] order = hashOrder(keys, count);
        long[] ordered = new long[count];
        double[] weights = new double[count];
        for (int i = 0; i < count; i++) {
            ordered[i] = keys[order[i]];
            weights[i] = known[found[order[i]]];
        }
        return new Exact(ordered, weights, came);
    }

    /**
     * Порядок обхода HashMap&lt;Long, V&gt;, созданной без параметров, после вставки keys[0..count) по порядку без
     * удалений: номера ключей в keys. У HashMap JDK 8–21 это корзины hash(key) &amp; (ёмкость - 1) по возрастанию, в
     * корзине — порядок вставки: таблица с 16 корзин удваивается, когда ключей больше трёх четвертей ёмкости, и при
     * удвоении список корзины делится с сохранением порядка. Корзина, в которой список дорос бы до дерева
     * (TREEIFY_THRESHOLD = 8), обходится иначе, тогда порядок берётся у настоящей HashMap.
     */
    static int[] hashOrder(long[] keys, int count) {
        int capacity = 16;
        int[] load = new int[capacity];
        for (int i = 0; i < count; i++) {
            if (load[spread(keys[i]) & (capacity - 1)]++ >= 8) {
                Map<Long, Integer> map = new HashMap<>();
                for (int k = 0; k < count; k++) {
                    map.put(keys[k], k);
                }
                return map.values().stream().mapToInt(Integer::intValue).toArray();
            }
            if (i + 1 > capacity / 4 * 3) {
                capacity *= 2;
                load = new int[capacity];
                for (int k = 0; k <= i; k++) {
                    load[spread(keys[k]) & (capacity - 1)]++;
                }
            }
        }
        // устойчивая сортировка подсчётом по корзине
        int[] start = new int[capacity + 1];
        for (int i = 0; i < count; i++) {
            start[(spread(keys[i]) & (capacity - 1)) + 1]++;
        }
        for (int b = 0; b < capacity; b++) {
            start[b + 1] += start[b];
        }
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[start[spread(keys[i]) & (capacity - 1)]++] = i;
        }
        return order;
    }

    /** hash(key) у HashMap для Long: старшие биты hashCode подмешаны к младшим. */
    private static int spread(long key) {
        int h = Long.hashCode(key);
        return h ^ (h >>> 16);
    }

    /** Лучшая цель по состояниям точного поиска и маршрут до неё. */
    private Route exactBest(Point start, Collection<Point> targets, Set<String> ignored, Coordinate incoming,
            Exact search, boolean cut) {
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
            for (int i = 0; i < search.keys.length; i++) {
                long key = search.keys[i];
                double reached = search.weights[i];
                int v = (int) (key / (n + 1));
                int p = (int) (key % (n + 1));
                Coordinate before = p == n ? source : nodes.get(p);
                if (reached + nodes.get(v).distance(t) + extra >= bestWeight
                        || !turnAllowed(before, nodes.get(v), t)) {
                    continue;
                }
                if (toNodes[v] == UNKNOWN) {
                    toNodes[v] = obstacles.tangent(v, t) ? obstacles.edgeWeight(t, nodes.get(v), ignored, false, true)
                            : Double.NaN;
                }
                if (!Double.isNaN(toNodes[v]) && reached + toNodes[v] + extra < bestWeight) {
                    bestWeight = reached + toNodes[v] + extra;
                    bestTarget = t;
                    bestState = key;
                    bestExtra = extra;
                }
            }
        }
        if (bestTarget == null) {
            return null;
        }
        List<Coordinate> coords = new ArrayList<>();
        for (long state = bestState; state >= 0; state = search.came[stateIndex(state, n)]) {
            coords.add(0, nodes.get((int) (state / (n + 1))));
        }
        coords.add(0, source);
        coords.add(bestTarget);
        return shaped(start, coords, ignored, cut, bestExtra);
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
        Table table = new Table(source, nodeWeights(source, ignored).clone());
        // dist, pred и order — 16 байт на узел; куча приостановленного поиска на плотных наборах добавляет около 30
        cache.put(key, table, 48L * nodes.size());
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
            ObstacleSet.Hint hint = ObstacleSet.hint();
            for (int v = 0; v < weights.length; v++) {
                weights[v] = obstacles.tangent(v, c) ? obstacles.edgeWeight(c, nodes.get(v), ignored, false, true, hint) : Double.NaN;
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
     * Срезка углов маршрута: до CUT_PASSES проходов {@link #cutPass}, второй срезает углы хорд первого; затем форма
     * доводится {@link #sharpen}.
     */
    private void cutCorners(List<Coordinate> coords, Set<String> ignored) {
        for (int pass = 0; pass < CUT_PASSES && cutPass(obstacles, coords, ignored, null, null); pass++) {
            // проход уже заменил вершины в coords
        }
        // повторы того же пути отсекает кэш формы маршрута в shaped, своего кэша доводке не нужно
        sharpen(obstacles, coords, ignored, null, null);
    }

    /**
     * Строгая форма ломаной (приложение 18.09, разд. 5: прямые отрезки с поворотами, без необоснованных изломов,
     * зигзагов и ступенек). Пока что-то меняется: (А) убирается вершина, если её соседей по обычному участку можно
     * соединить прямой, см. {@link #drop}; (В) так же убираются две вершины со звеном короче SHORT_LINK_M между ними;
     * (Б) повороты подряд в одну сторону со звеньями короче SHORT_LINK_M заменяются наименьшим числом поворотов в
     * пересечениях продолженных отрезков, см. {@link #sharpenWindow}; (Г) у готовых вариантов два соседних поворота в
     * одну сторону при звене любой длины заменяет одна вершина, если путь не длиннее, см. {@link #bend}. Замена
     * проверяется по зонам zones (Ду графа или ребра дерева) как хорда {@link #cutPass}: область, где взяты
     * препятствия, отступы с запасом CUT_MARGIN_M, без спецпрохода, не ближе CUT_APART_M к apart (отрезкам других
     * рёбер дерева) и к своей ломаной, повороты до MAX_TURN_DEG, звенья от CUT_PIECE_M. Вершины убираются раньше
     * замены поворотов: так трасса короче. Вершина
     * {@code keep} (точка выхода из здания) остаётся на месте: финальный участок идёт от ближайшей границы;
     * спецпроход остаётся прежним прямым участком. Каждый шаг уменьшает число вершин вне границ специальных частей,
     * поэтому цикл конечен. true — coords заменены.
     */
    public boolean sharpen(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart) {
        return sharpen(zones, coords, ignored, keep, apart, null, List.of());
    }

    /**
     * {@link #sharpen} у ребра дерева: before — вершина перед началом ломаной на пути точки к врезке (null — нет),
     * after — вершины после её конца (пусто — нет). Убранная вершина не делает поворот в концах круче MAX_TURN_DEG
     * (п. 2.1, разъяснение 5), замена поворотов крайние отрезки не поворачивает.
     */
    public boolean sharpen(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        return sharpen(zones, coords, ignored, keep, apart, before, after, false);
    }

    /**
     * {@link #sharpen} у ребра дерева, при {@code bends} — и с шагом (Г), см. {@link #bend}. Шаг (Г) делает только
     * проход готовых вариантов: на каждом маршруте и дереве поиска он стоил бы времени, а длину только уменьшает.
     */
    public boolean sharpen(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after, boolean bends) {
        List<LineSegment> others = apart == null ? List.of() : apart;
        boolean changed = false;
        while (coords.size() > 2) {
            List<SpecialSpan> spans = zones.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
            if (!drop(zones, coords, 1, keep, spans, ignored, others, before, after)
                    && !drop(zones, coords, 2, keep, spans, ignored, others, before, after)
                    && !merge(zones, coords, held(coords, keep, spans), spans, ignored, others)
                    && !(bends && bend(zones, coords, held(coords, keep, spans), spans, ignored, others, before, after))) {
                break;
            }
            changed = true;
        }
        return changed;
    }

    /**
     * Есть вершина, которую {@link #sharpen} оставил, хотя без неё одной соседей можно соединить прямой по тем же
     * проверкам: тогда излом соседа меньше MIN_TURN_DEG, а без соседа прямая уже не проходит. Проверщик формы
     * считает такую вершину лишней. Аргументы — как у {@link #sharpen}; вершина keep и вершины в специальных частях
     * не проверяются.
     */
    public boolean loose(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart) {
        return loose(zones, coords, ignored, keep, apart, null, List.of());
    }

    /** {@link #loose} у ребра дерева с вершинами соседних рёбер before и after, как у {@link #sharpen}. */
    public boolean loose(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        List<LineSegment> others = apart == null ? List.of() : apart;
        List<SpecialSpan> spans = zones.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
        double at = 0;
        for (int v = 1; v + 1 < coords.size(); v++) {
            at += coords.get(v - 1).distance(coords.get(v));
            if (coords.get(v) == keep || overlapsSpan(spans, at - SPAN_EPS_M, at + SPAN_EPS_M)) {
                continue;
            }
            List<Coordinate> shape = new ArrayList<>(coords);
            shape.remove(v);
            if (removalFits(zones, shape, v - 1, ignored, others, before, after)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Убирает вершину на границе специальной части с изломом меньше MIN_TURN_DEG ({@link #micro}): {@link #sharpen}
     * такие вершины не трогает, специальная часть там остаётся прямой, а обычный участок за узлом чуть ломается. Соседи
     * соединяются прямой через объект специального прохода, если она пересекает его по правилам
     * ({@link ObstacleSet#crossable}); соседи с изломом меньше MIN_TURN_DEG после этого уходят тоже, кроме keep. Прямая
     * держит запасы строгой формы, как у {@link #sharpen}: звено от CUT_PIECE_M, повороты на концах, в том числе к
     * before и after, до MAX_TURN_DEG, CUT_APART_M до apart и своей ломаной; специальные части на ней целиком, от концов
     * не ближе MIN_PIECE_M или от самого конца. Изломов меньше MIN_TURN_DEG на ломаной и на её концах становится
     * меньше. Повторяется, пока что-то меняется; true — coords заменены.
     */
    public boolean even(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        boolean changed = false;
        for (boolean again = true; again && coords.size() > 2; ) {
            again = false;
            List<SpecialSpan> spans = zones.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
            int n = coords.size();
            double[] at = new double[n];
            for (int v = 1; v < n; v++) {
                at[v] = at[v - 1] + coords.get(v - 1).distance(coords.get(v));
            }
            for (int v = 1; v + 1 < n && !again; v++) {
                if (coords.get(v) == keep || !micro(coords.get(v - 1), coords.get(v), coords.get(v + 1))
                        || !spanEnd(spans, at[v])) {
                    continue;
                }
                int lo = v;
                int hi = v;
                while (lo >= 2 && coords.get(lo - 1) != keep
                        && deflectionDeg(coords.get(lo - 2), coords.get(lo - 1), coords.get(hi + 1)) < MIN_TURN_DEG) {
                    lo--;
                }
                while (hi + 2 < n && coords.get(hi + 1) != keep
                        && deflectionDeg(coords.get(lo - 1), coords.get(hi + 1), coords.get(hi + 2)) < MIN_TURN_DEG) {
                    hi++;
                }
                List<Coordinate> shape = new ArrayList<>(coords.subList(0, lo));
                shape.addAll(coords.subList(hi + 1, n));
                if (crossFits(zones, shape, lo - 1, ignored, apart, before, after)
                        && microCount(shape, before, after) < microCount(coords, before, after)) {
                    coords.clear();
                    coords.addAll(shape);
                    changed = again = true;
                }
            }
        }
        return changed;
    }

    /** На at начинается или кончается специальная часть. */
    private static boolean spanEnd(List<SpecialSpan> spans, double at) {
        for (SpecialSpan span : spans) {
            if (Math.abs(span.getFromM() - at) <= SPAN_EPS_M || Math.abs(span.getToM() - at) <= SPAN_EPS_M) {
                return true;
            }
        }
        return false;
    }

    /** Изломы меньше MIN_TURN_DEG ({@link #micro}) в вершинах ломаной и на её концах к before и after. */
    private static int microCount(List<Coordinate> coords, Coordinate before, List<Coordinate> after) {
        int n = coords.size();
        int count = before != null && micro(before, coords.get(0), coords.get(1)) ? 1 : 0;
        for (int v = 1; v + 1 < n; v++) {
            count += micro(coords.get(v - 1), coords.get(v), coords.get(v + 1)) ? 1 : 0;
        }
        for (Coordinate c : after) {
            count += micro(coords.get(n - 2), coords.get(n - 1), c) ? 1 : 0;
        }
        return count;
    }

    /** Новый отрезок join ломаной shape через специальную часть годится, см. {@link #even}. */
    private boolean crossFits(ObstacleSet zones, List<Coordinate> shape, int join, Set<String> ignored,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        int n = shape.size();
        Coordinate a = shape.get(join);
        Coordinate b = shape.get(join + 1);
        if (a.distance(b) < CUT_PIECE_M || join >= 1 && !turnAllowed(shape.get(join - 1), a, b)
                || join + 2 < n && !turnAllowed(a, b, shape.get(join + 2))
                || !startFits(shape, join, before) || !endFits(shape, join, after)
                || !zones.covers(a, b) || !zones.crossable(a, b, ignored, CUT_MARGIN_M) || along(zones, shape, join, ignored)) {
            return false;
        }
        double from = 0;
        for (int k = 0; k < join; k++) {
            from += shape.get(k).distance(shape.get(k + 1));
        }
        double to = from + a.distance(b);
        for (SpecialSpan span : zones.spans(factory.createLineString(shape.toArray(new Coordinate[0])), ignored)) {
            if (span.getFromM() >= to || span.getToM() <= from) {
                continue;
            }
            boolean start = Math.abs(span.getFromM() - from) <= SPAN_EPS_M || span.getFromM() >= from + MIN_PIECE_M;
            boolean end = Math.abs(span.getToM() - to) <= SPAN_EPS_M || span.getToM() <= to - MIN_PIECE_M;
            if (!start || !end) {
                return false;
            }
        }
        List<LineSegment> own = new ArrayList<>(apart);
        for (int k = 0; k + 1 < n; k++) {
            if (Math.abs(k - join) > 1) {
                own.add(new LineSegment(shape.get(k), shape.get(k + 1)));
            }
        }
        return apart(new LineSegment(a, b), own);
    }

    /** Вершины, которые замена поворотов не двигает: keep и вершины ближе MIN_PIECE_M к специальным частям. */
    private static boolean[] held(List<Coordinate> coords, Coordinate keep, List<SpecialSpan> spans) {
        boolean[] held = new boolean[coords.size()];
        double at = 0;
        for (int v = 1; v + 1 < coords.size(); v++) {
            at += coords.get(v - 1).distance(coords.get(v));
            held[v] = coords.get(v) == keep || overlapsSpan(spans, at - MIN_PIECE_M, at + MIN_PIECE_M);
        }
        return held;
    }

    /**
     * Убирает count вершин подряд (две — только со звеном короче SHORT_LINK_M между ними), соединяя прямой соседей
     * по обычному участку; из подходящих — с наибольшим выигрышем длины. Сосед — вершина или граница специальной
     * части на соседнем отрезке: там сборка ставит технический узел, граница становится вершиной, а специальная часть
     * остаётся на прежней прямой (узлы для пересечения дорог стоят в сантиметрах от полосы margin_m, и вершина у узла
     * иначе осталась бы лишней). Сосед, у которого после этого излом меньше MIN_TURN_DEG, убирается вместе с ними,
     * если он не на границе специальной части. Вершина keep и вершины в специальных частях и на их границах не
     * убираются. true — coords заменены.
     */
    private boolean drop(ObstacleSet zones, List<Coordinate> coords, int count, Coordinate keep,
            List<SpecialSpan> spans, Set<String> ignored, List<LineSegment> apart, Coordinate before,
            List<Coordinate> after) {
        int n = coords.size();
        double[] at = new double[n];
        for (int v = 1; v < n; v++) {
            at[v] = at[v - 1] + coords.get(v - 1).distance(coords.get(v));
        }
        List<Removal> removals = new ArrayList<>();
        for (int v = 1; v + count < n; v++) {
            Removal removal = count == 2 && coords.get(v).distance(coords.get(v + 1)) >= SHORT_LINK_M ? null
                    : removal(coords, at, spans, keep, v, v + count - 1);
            if (removal != null) {
                removals.add(removal);
            }
        }
        removals.sort(Comparator.comparingDouble(removal -> -removal.saving));
        for (Removal removal : removals) {
            if (removalFits(zones, removal.shape, removal.join, ignored, apart, before, after)) {
                coords.clear();
                coords.addAll(removal.shape);
                return true;
            }
        }
        return false;
    }

    /** Ломаная без убранных вершин: новый отрезок начинается в её вершине join, трасса короче на saving. */
    private static final class Removal {
        final List<Coordinate> shape;
        final int join;
        final double saving;

        Removal(List<Coordinate> shape, int join, double saving) {
            this.shape = shape;
            this.join = join;
            this.saving = saving;
        }
    }

    /** Ломаная без вершин lo..hi, см. {@link #drop}, или null — их убрать нельзя. */
    private static Removal removal(List<Coordinate> coords, double[] at, List<SpecialSpan> spans, Coordinate keep,
            int lo, int hi) {
        int n = coords.size();
        while (true) {
            for (int v = lo; v <= hi; v++) {
                if (coords.get(v) == keep || overlapsSpan(spans, at[v] - SPAN_EPS_M, at[v] + SPAN_EPS_M)) {
                    return null;
                }
            }
            // граница специальной части на отрезке перед lo и после hi
            double from = at[lo - 1];
            double to = at[hi + 1];
            for (SpecialSpan span : spans) {
                if (span.getToM() > from && span.getToM() < at[lo]) {
                    from = span.getToM();
                }
                if (span.getFromM() < to && span.getFromM() > at[hi]) {
                    to = span.getFromM();
                }
            }
            boolean start = from > at[lo - 1] + SPAN_EPS_M;
            boolean end = to < at[hi + 1] - SPAN_EPS_M;
            Coordinate a = start ? toward(coords.get(lo), coords.get(lo - 1), at[lo] - from) : coords.get(lo - 1);
            Coordinate b = end ? toward(coords.get(hi), coords.get(hi + 1), to - at[hi]) : coords.get(hi + 1);
            // сосед с изломом меньше MIN_TURN_DEG, кроме границы и узла на ней, уходит вместе с вершинами
            if (!start && lo >= 2 && !overlapsSpan(spans, at[lo - 1] - SPAN_EPS_M, at[lo - 1] + SPAN_EPS_M)
                    && deflectionDeg(coords.get(lo - 2), a, b) < MIN_TURN_DEG) {
                lo--;
                continue;
            }
            if (!end && hi + 2 < n && !overlapsSpan(spans, at[hi + 1] - SPAN_EPS_M, at[hi + 1] + SPAN_EPS_M)
                    && deflectionDeg(a, b, coords.get(hi + 2)) < MIN_TURN_DEG) {
                hi++;
                continue;
            }
            List<Coordinate> shape = new ArrayList<>(coords.subList(0, lo));
            if (start) {
                shape.add(a);
            }
            int join = shape.size() - 1;
            if (end) {
                shape.add(b);
            }
            shape.addAll(coords.subList(hi + 1, n));
            return new Removal(shape, join, to - from - a.distance(b));
        }
    }

    /**
     * Новый отрезок join ломаной shape годится, см. {@link #sharpen}: он обычный (специальные части ломаной до него
     * и после не заходят в него), а повороты на его концах, в том числе к вершинам соседних рёбер before и after, не
     * круче MAX_TURN_DEG.
     */
    private boolean removalFits(ObstacleSet zones, List<Coordinate> shape, int join, Set<String> ignored,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        int n = shape.size();
        Coordinate a = shape.get(join);
        Coordinate b = shape.get(join + 1);
        if (a.distance(b) < CUT_PIECE_M || join >= 1 && !turnAllowed(shape.get(join - 1), a, b)
                || join + 2 < n && !turnAllowed(a, b, shape.get(join + 2))
                || !startFits(shape, join, before) || !endFits(shape, join, after)) {
            return false;
        }
        return pieceFits(zones, shape, join, ignored, apart);
    }

    /** Поворот к вершине before соседнего ребра в начале нового отрезка join не круче MAX_TURN_DEG. */
    private static boolean startFits(List<Coordinate> shape, int join, Coordinate before) {
        return join != 0 || before == null || turnAllowed(before, shape.get(0), shape.get(1));
    }

    /** Повороты к вершинам after соседних рёбер в конце нового отрезка join не круче MAX_TURN_DEG. */
    private static boolean endFits(List<Coordinate> shape, int join, List<Coordinate> after) {
        int n = shape.size();
        return join + 2 != n || after.stream().allMatch(c -> turnAllowed(shape.get(n - 2), shape.get(n - 1), c));
    }

    /** Проверки нового отрезка join из {@link #removalFits} без поворотов. */
    private boolean pieceFits(ObstacleSet zones, List<Coordinate> shape, int join, Set<String> ignored,
            List<LineSegment> apart) {
        int n = shape.size();
        Coordinate a = shape.get(join);
        Coordinate b = shape.get(join + 1);
        if (a.distance(b) < CUT_PIECE_M || !zones.covers(a, b) || !zones.plain(a, b, ignored, CUT_MARGIN_M)
                || along(zones, shape, join, ignored)) {
            return false;
        }
        double from = 0;
        for (int k = 0; k < join; k++) {
            from += shape.get(k).distance(shape.get(k + 1));
        }
        double to = from + a.distance(b);
        for (SpecialSpan span : zones.spans(factory.createLineString(shape.toArray(new Coordinate[0])), ignored)) {
            if (span.getFromM() < to - SPAN_EPS_M && span.getToM() > from + SPAN_EPS_M) {
                return false;
            }
        }
        List<LineSegment> own = new ArrayList<>(apart);
        for (int k = 0; k + 1 < n; k++) {
            if (Math.abs(k - join) > 1) {
                own.add(new LineSegment(shape.get(k), shape.get(k + 1)));
            }
        }
        return apart(new LineSegment(a, b), own);
    }

    /**
     * Новый отрезок join ломаной shape у её конца идёт вдоль объекта врезки из ignored. Отрезок, который один
     * составляет ломаную, касается объекта врезки одним концом, и проверка от другого конца всегда находит касание:
     * вдоль он идёт, только если его находят проверки от обоих концов.
     */
    private static boolean along(ObstacleSet zones, List<Coordinate> shape, int join, Set<String> ignored) {
        int n = shape.size();
        Coordinate a = shape.get(join);
        Coordinate b = shape.get(join + 1);
        if (n == 2) {
            return zones.alongIgnored(a, b, ignored) && zones.alongIgnored(b, a, ignored);
        }
        return join == 0 && zones.alongIgnored(a, b, ignored) || join + 2 == n && zones.alongIgnored(b, a, ignored);
    }

    /**
     * Заменяет первую цепочку поворотов в одну сторону со звеньями короче SHORT_LINK_M, которую удаётся заменить
     * меньшим числом поворотов, см. {@link #sharpenWindow}; у длинной цепочки, где перебор прямых ограничен, — хотя
     * бы пару поворотов одним. Удерживаемые вершины цепочку разрывают. true — coords заменены.
     */
    private boolean merge(ObstacleSet zones, List<Coordinate> coords, boolean[] held, List<SpecialSpan> spans,
            Set<String> ignored, List<LineSegment> apart) {
        for (int[] chain : chains(coords, turns(coords), held)) {
            int v = chain[0];
            int w = chain[1];
            if (sharpenWindow(zones, coords, v, w, spans, ignored, apart)) {
                return true;
            }
            for (int u = v; w - v > SHARPEN_LINES && u < w; u++) {
                if (sharpenWindow(zones, coords, u, u + 1, spans, ignored, apart)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * (Г) Два соседних поворота в одну сторону при звене любой длины заменяет одна вершина в лучшей точке, если путь
     * с ней не длиннее прежнего (толкование п. 5): такой двойной поворот ничем не обоснован. Вершина — пересечение
     * лучей из соседей пары, которые поворачиваются от прежних отрезков к хорде между соседями: путь от этого только
     * короче, а звено луча, который стоит на месте, лишь укорачивается от своего конца, и его проверки
     * {@link #removalFits} не ломаются. Лучи поворачиваются по очереди до края допустимого, пока поворот растёт;
     * поворот в новой вершине — сумма пары без поворотов лучей, он проверяется в конце и не меньше MIN_TURN_DEG.
     * Сосед пары — вершина или граница специальной части, как у {@link #drop}. Удерживаемые вершины не заменяются,
     * пары у специальных частей не трогаются. true — coords заменены.
     */
    private boolean bend(ObstacleSet zones, List<Coordinate> coords, boolean[] held, List<SpecialSpan> spans,
            Set<String> ignored, List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        int n = coords.size();
        double[] turns = turns(coords);
        double[] at = new double[n];
        for (int v = 1; v < n; v++) {
            at[v] = at[v - 1] + coords.get(v - 1).distance(coords.get(v));
        }
        for (int j = 1; j + 2 < n; j++) {
            if (held[j] || held[j + 1] || Math.abs(turns[j]) < MIN_TURN_DEG || Math.abs(turns[j + 1]) < MIN_TURN_DEG
                    || turns[j] * turns[j + 1] <= 0) {
                continue;
            }
            // сосед пары — вершина или граница специальной части на соседнем отрезке, как у drop
            double from = at[j - 1];
            double to = at[j + 2];
            for (SpecialSpan span : spans) {
                if (span.getToM() > from && span.getToM() < at[j]) {
                    from = span.getToM();
                }
                if (span.getFromM() < to && span.getFromM() > at[j + 1]) {
                    to = span.getFromM();
                }
            }
            if (overlapsSpan(spans, from, to)) {
                continue;
            }
            List<Coordinate> shape = new ArrayList<>(coords.subList(0, j));
            boolean start = from > at[j - 1] + SPAN_EPS_M;
            boolean end = to < at[j + 2] - SPAN_EPS_M;
            if (start) {
                shape.add(toward(coords.get(j), coords.get(j - 1), at[j] - from));
            }
            int x = shape.size();
            shape.add(null);
            if (end) {
                shape.add(toward(coords.get(j + 1), coords.get(j + 2), to - at[j + 1]));
            }
            shape.addAll(coords.subList(j + 2, n));
            // сосед внутри участка остаётся поворотом от MIN_TURN_DEG; у узла и границы специальной части излом любой
            boolean[] kept = {!start && j >= 2 && !overlapsSpan(spans, from - SPAN_EPS_M, from + SPAN_EPS_M),
                !end && j + 3 < n && !overlapsSpan(spans, to - SPAN_EPS_M, to + SPAN_EPS_M)};
            if (bent(zones, shape, x, coords.get(j), coords.get(j + 1), kept, Math.abs(turns[j] + turns[j + 1]),
                    to - from, ignored, apart, before, after)) {
                coords.clear();
                coords.addAll(shape);
                return true;
            }
        }
        return false;
    }

    /**
     * Ставит в shape на место x (между соседями пары) вершину в лучшей точке, см. {@link #bend}; false — такой
     * вершины нет или путь с ней длиннее length (прежний путь между соседями через вершины пары p и q). kept — у
     * какого соседа излом остаётся не меньше MIN_TURN_DEG, total — сумма поворотов пары, градусы.
     */
    private boolean bent(ObstacleSet zones, List<Coordinate> shape, int x, Coordinate p, Coordinate q, boolean[] kept,
            double total, double length, Set<String> ignored, List<LineSegment> apart, Coordinate before,
            List<Coordinate> after) {
        Coordinate[] ends = {shape.get(x - 1), shape.get(x + 1)};
        double[] base = {heading(ends[0], p), heading(ends[1], q)};
        double[] room = {Angle.normalize(heading(ends[0], ends[1]) - base[0]),
            Angle.normalize(heading(ends[1], ends[0]) - base[1])};
        double spare = Math.toRadians(total - MIN_TURN_DEG);
        double[] turn = new double[2];
        boolean moved = true;
        for (int round = 0; moved && round < BEND_ROUNDS; round++) {
            moved = false;
            for (int side = 0; side < 2; side++) {
                if (!bendFits(zones, shape, x, side, kept, ends, base, room, turn, ignored, apart, before, after)) {
                    continue;
                }
                double was = turn[side];
                double lo = was;
                double hi = Math.min(Math.abs(room[side]), spare - turn[1 - side]);
                turn[side] = hi;
                if (bendFits(zones, shape, x, side, kept, ends, base, room, turn, ignored, apart, before, after)) {
                    lo = hi;
                }
                while (hi - lo > BEND_EPS_RAD) {
                    turn[side] = (lo + hi) / 2;
                    if (bendFits(zones, shape, x, side, kept, ends, base, room, turn, ignored, apart, before, after)) {
                        lo = turn[side];
                    } else {
                        hi = turn[side];
                    }
                }
                turn[side] = lo;
                moved |= lo > was + BEND_EPS_RAD;
            }
        }
        boolean fits = bendFits(zones, shape, x, 0, kept, ends, base, room, turn, ignored, apart, before, after)
                && bendFits(zones, shape, x, 1, kept, ends, base, room, turn, ignored, apart, before, after)
                && turnAllowed(ends[0], shape.get(x), ends[1]);
        Coordinate at = shape.get(x);
        return fits && ends[0].distance(at) + at.distance(ends[1]) <= length + SHARPEN_EPS;
    }

    /**
     * Вершина на пересечении лучей из ends с поворотами turn от направлений base к хорде (знак room) ставится в
     * shape на место x, и звено side (0 — от ends[0], 1 — к ends[1]) годится по {@link #removalFits}, а излом соседа
     * на его конце не меньше MIN_TURN_DEG, если так сказано в kept.
     */
    private boolean bendFits(ObstacleSet zones, List<Coordinate> shape, int x, int side, boolean[] kept, Coordinate[] ends,
            double[] base, double[] room, double[] turn, Set<String> ignored, List<LineSegment> apart,
            Coordinate before, List<Coordinate> after) {
        double a = base[0] + Math.signum(room[0]) * turn[0];
        double b = base[1] + Math.signum(room[1]) * turn[1];
        double ux = Math.cos(a);
        double uy = Math.sin(a);
        double vx = Math.cos(b);
        double vy = Math.sin(b);
        double den = ux * vy - uy * vx;
        double wx = ends[1].x - ends[0].x;
        double wy = ends[1].y - ends[0].y;
        double s = (wx * vy - wy * vx) / den;
        double r = (wx * uy - wy * ux) / den;
        if (!(s > 0 && r > 0)) {
            return false;
        }
        shape.set(x, new Coordinate(ends[0].x + s * ux, ends[0].y + s * uy));
        int join = x - 1 + side;
        // поворот в новой вершине убывает с поворотом лучей и проверяется в конце, см. bent
        boolean outer = side == 0 ? join < 1 || turnAllowed(shape.get(join - 1), shape.get(join), shape.get(x))
                && (!kept[0] || deflectionDeg(shape.get(join - 1), shape.get(join), shape.get(x)) >= MIN_TURN_DEG)
                : join + 2 >= shape.size() || turnAllowed(shape.get(x), shape.get(join + 1), shape.get(join + 2))
                && (!kept[1] || deflectionDeg(shape.get(x), shape.get(join + 1), shape.get(join + 2)) >= MIN_TURN_DEG);
        return outer && startFits(shape, join, before) && endFits(shape, join, after)
                && pieceFits(zones, shape, join, ignored, apart);
    }

    /** Направление от a к b, радианы. */
    private static double heading(Coordinate a, Coordinate b) {
        return Math.atan2(b.y - a.y, b.x - a.x);
    }

    /**
     * Цепочки {первая, последняя вершина} из двух и больше поворотов (от MIN_TURN_DEG) подряд в одну сторону со
     * звеньями короче SHORT_LINK_M между ними; held — вершины, которые цепочку разрывают (null — таких нет).
     */
    static List<int[]> chains(List<Coordinate> coords, double[] turns, boolean[] held) {
        int n = coords.size();
        List<int[]> chains = new ArrayList<>();
        for (int v = 1; v + 1 < n; ) {
            int w = v;
            while (w + 2 < n && Math.abs(turns[v]) >= MIN_TURN_DEG && Math.abs(turns[w + 1]) >= MIN_TURN_DEG
                    && turns[w + 1] * turns[v] > 0 && coords.get(w).distance(coords.get(w + 1)) < SHORT_LINK_M
                    && (held == null || !held[v] && !held[w + 1])) {
                w++;
            }
            if (w > v) {
                chains.add(new int[] {v, w});
            }
            v = w + 1;
        }
        return chains;
    }

    /** В ломаной есть два соседних поворота от MIN_TURN_DEG в одну сторону, см. {@link #bend}. */
    public static boolean paired(Coordinate[] coords) {
        double[] turns = turns(Arrays.asList(coords));
        for (int v = 1; v + 2 < coords.length; v++) {
            if (Math.abs(turns[v]) >= MIN_TURN_DEG && Math.abs(turns[v + 1]) >= MIN_TURN_DEG && turns[v] * turns[v + 1] > 0) {
                return true;
            }
        }
        return false;
    }

    /** Повороты ломаной в вершинах со знаком (плюс — влево), 0 у концов; для {@link #chains}. */
    static double[] turns(List<Coordinate> coords) {
        double[] turns = new double[coords.size()];
        for (int v = 1; v + 1 < coords.size(); v++) {
            turns[v] = turnDeg(coords.get(v - 1), coords.get(v), coords.get(v), coords.get(v + 1));
        }
        return turns;
    }

    /**
     * Замена поворотов окна вершин i..j меньшим числом: из прямых отрезков i-1 (вход), j (выход) и части средних
     * остаются продолжения до пересечения соседних, поворот в пересечении — сумма поворотов между ними. Один поворот —
     * пересечение входа и выхода, два — через продолжение одной средней прямой, и так до SHARPEN_LINES средних.
     * Варианты идут по числу поворотов, при равном — по длине. Вариант годится, если повороты от MIN_TURN_DEG до
     * MAX_TURN_DEG, звенья не короче CUT_PIECE_M, убранные части прежних отрезков не ближе MIN_PIECE_M к специальным
     * частям, а куски за пределами прежних отрезков лежат в области графа, обычные с запасом CUT_MARGIN_M и не ближе
     * CUT_APART_M к apart и к своей ломаной. true — coords заменены первым годным.
     */
    private boolean sharpenWindow(ObstacleSet zones, List<Coordinate> coords, int i, int j, List<SpecialSpan> spans,
            Set<String> ignored, List<LineSegment> apart) {
        for (int k = i - 1; k <= j; k++) {
            if (coords.get(k).equals2D(coords.get(k + 1))) {
                return false;
            }
        }
        int most = Math.min(SHARPEN_LINES, j - i - 1);
        Map<List<Integer>, Boolean> pieces = new HashMap<>();
        for (int count = 0; count <= most; count++) {
            List<int[]> sets = new ArrayList<>();
            int[] lines = new int[count + 2];
            lines[0] = i - 1;
            keptLines(i, j, lines, 1, sets);
            List<List<Coordinate>> shapes = new ArrayList<>();
            List<int[]> shapeLines = new ArrayList<>();
            List<Double> lengths = new ArrayList<>();
            for (int[] kept : sets) {
                List<Coordinate> shape = merged(coords, i, j, kept);
                double length = shape == null ? Double.NaN : windowLength(coords, shape, i, kept);
                if (!Double.isNaN(length)) {
                    shapes.add(shape);
                    shapeLines.add(kept);
                    lengths.add(length);
                }
            }
            Integer[] order = new Integer[shapes.size()];
            for (int k = 0; k < order.length; k++) {
                order[k] = k;
            }
            Arrays.sort(order, Comparator.comparingDouble(lengths::get));
            for (int k : order) {
                if (fits(zones, coords, shapes.get(k), i, j, shapeLines.get(k), spans, ignored, apart, pieces)) {
                    List<Coordinate> shape = shapes.get(k);
                    coords.clear();
                    coords.addAll(shape);
                    return true;
                }
            }
        }
        return false;
    }

    /** Наборы прямых окна: kept[0] — вход, дальше средние из next..j-1 по возрастанию, последним — выход j. */
    private static void keptLines(int next, int j, int[] kept, int at, List<int[]> out) {
        if (at == kept.length - 1) {
            kept[at] = j;
            out.add(kept.clone());
            return;
        }
        for (int k = next; k < j; k++) {
            kept[at] = k;
            keptLines(k + 1, j, kept, at + 1, out);
        }
    }

    /**
     * Ломаная с окном i..j, замененным пересечениями соседних прямых kept (у смежных прямых — их общая вершина), или
     * null — прямые параллельны или поворот вне MIN_TURN_DEG..MAX_TURN_DEG.
     */
    private static List<Coordinate> merged(List<Coordinate> coords, int i, int j, int[] kept) {
        List<Coordinate> shape = new ArrayList<>(coords.subList(0, i));
        for (int t = 0; t + 1 < kept.length; t++) {
            int a = kept[t];
            int b = kept[t + 1];
            double turn = Math.abs(turnDeg(coords.get(a), coords.get(a + 1), coords.get(b), coords.get(b + 1)));
            Coordinate x = b == a + 1 ? coords.get(b)
                    : new LineSegment(coords.get(a), coords.get(a + 1)).lineIntersection(new LineSegment(coords.get(b), coords.get(b + 1)));
            if (x == null || turn < MIN_TURN_DEG || turn > MAX_TURN_DEG) {
                return null;
            }
            shape.add(x);
        }
        shape.addAll(coords.subList(j + 1, coords.size()));
        return shape;
    }

    /**
     * Длина нового окна shape или NaN, если звено идёт против своей прямой или короче CUT_PIECE_M (крайнее — короче
     * прежнего, если прежнее было короче).
     */
    private static double windowLength(List<Coordinate> coords, List<Coordinate> shape, int i, int[] kept) {
        double length = 0;
        for (int t = 0; t < kept.length; t++) {
            int g = i - 1 + t;
            Coordinate p = coords.get(kept[t]);
            Coordinate q = coords.get(kept[t] + 1);
            double along = ((shape.get(g + 1).x - shape.get(g).x) * (q.x - p.x) + (shape.get(g + 1).y - shape.get(g).y) * (q.y - p.y))
                    / p.distance(q);
            boolean end = g == 0 || g == shape.size() - 2;
            if (along < (end ? Math.min(CUT_PIECE_M, p.distance(q)) : CUT_PIECE_M) || along <= 0) {
                return Double.NaN;
            }
            length += along;
        }
        return length;
    }

    /** Проверки окна shape, которые дороже формы: специальные части и новые куски, см. {@link #sharpenWindow}. */
    private boolean fits(ObstacleSet zones, List<Coordinate> coords, List<Coordinate> shape, int i, int j, int[] kept,
            List<SpecialSpan> spans, Set<String> ignored, List<LineSegment> apart, Map<List<Integer>, Boolean> pieces) {
        // убранные части прежних отрезков окна не ближе MIN_PIECE_M к специальным частям: те остаются прежними
        double at = 0;
        for (int k = 0; k + 1 < i; k++) {
            at += coords.get(k).distance(coords.get(k + 1));
        }
        for (int k = i - 1, t = 0; k <= j; k++) {
            Coordinate p = coords.get(k);
            Coordinate q = coords.get(k + 1);
            double length = p.distance(q);
            double start = length;
            double end = length;
            if (t < kept.length && kept[t] == k) {
                Coordinate a = shape.get(i - 1 + t);
                Coordinate b = shape.get(i + t);
                start = Math.max(0, ((a.x - p.x) * (q.x - p.x) + (a.y - p.y) * (q.y - p.y)) / length);
                end = Math.min(length, ((b.x - p.x) * (q.x - p.x) + (b.y - p.y) * (q.y - p.y)) / length);
                t++;
            }
            if (start >= end) {
                start = length;
                end = length;
            }
            if (start > SHARPEN_EPS && overlapsSpan(spans, at - MIN_PIECE_M, at + start + MIN_PIECE_M)
                    || end < length - SHARPEN_EPS && overlapsSpan(spans, at + end - MIN_PIECE_M, at + length + MIN_PIECE_M)) {
                return false;
            }
            at += length;
        }
        for (int t = 0; t < kept.length; t++) {
            int g = i - 1 + t;
            Coordinate a = shape.get(g);
            Coordinate b = shape.get(g + 1);
            List<Coordinate[]> parts = beyond(a, b, coords.get(kept[t]), coords.get(kept[t] + 1));
            if (parts.isEmpty()) {
                continue;
            }
            List<Integer> key = List.of(t == 0 ? -1 : kept[t - 1], kept[t], t + 1 < kept.length ? kept[t + 1] : -1);
            Boolean clear = pieces.get(key);
            if (clear == null) {
                clear = true;
                for (Coordinate[] part : parts) {
                    clear &= zones.covers(part[0], part[1]) && zones.plain(part[0], part[1], ignored, CUT_MARGIN_M)
                            && apart(new LineSegment(part[0], part[1]), apart == null ? List.of() : apart);
                }
                pieces.put(key, clear);
            }
            if (!clear) {
                return false;
            }
            List<LineSegment> own = new ArrayList<>();
            for (int h = 0; h + 1 < shape.size(); h++) {
                if (Math.abs(h - g) > 1) {
                    own.add(new LineSegment(shape.get(h), shape.get(h + 1)));
                }
            }
            for (Coordinate[] part : parts) {
                if (!apart(new LineSegment(part[0], part[1]), own)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Куски отрезка a–b на прямой отрезка p–q за пределами p–q: они новые, остальное — прежний отрезок. */
    private static List<Coordinate[]> beyond(Coordinate a, Coordinate b, Coordinate p, Coordinate q) {
        double length = p.distance(q);
        double dx = (q.x - p.x) / length;
        double dy = (q.y - p.y) / length;
        double start = (a.x - p.x) * dx + (a.y - p.y) * dy;
        double end = (b.x - p.x) * dx + (b.y - p.y) * dy;
        List<Coordinate[]> parts = new ArrayList<>();
        if (start < -SHARPEN_EPS) {
            parts.add(new Coordinate[] {a, end < 0 ? b : p});
        }
        if (end > length + SHARPEN_EPS) {
            parts.add(new Coordinate[] {start > length ? a : q, b});
        }
        return parts;
    }

    /**
     * Отрезок не ближе CUT_APART_M к others. Отрезки из общего конца (узла дерева) расходятся от него, поэтому у них
     * зазор проверяется у дальних концов.
     */
    public static boolean apart(LineSegment segment, List<LineSegment> others) {
        for (LineSegment other : others) {
            // у отрезков с общим концом рамки пересекаются, так что отсев не мешает проверке дальних концов
            if (apart(segment.p0, segment.p1, other.p0, other.p1, CUT_APART_M)) {
                continue;
            }
            Coordinate far = null;
            Coordinate otherFar = null;
            for (int k = 0; k < 2; k++) {
                for (int h = 0; h < 2; h++) {
                    if (segment.getCoordinate(k).equals2D(other.getCoordinate(h))) {
                        far = segment.getCoordinate(1 - k);
                        otherFar = other.getCoordinate(1 - h);
                    }
                }
            }
            if (far == null ? segment.distance(other) < CUT_APART_M
                    : other.distance(far) < CUT_APART_M || segment.distance(otherFar) < CUT_APART_M) {
                return false;
            }
        }
        return true;
    }

    /** Поворот от направления a0→a1 к направлению b0→b1, градусы со знаком: плюс — влево. */
    private static double turnDeg(Coordinate a0, Coordinate a1, Coordinate b0, Coordinate b1) {
        double ux = a1.x - a0.x;
        double uy = a1.y - a0.y;
        double vx = b1.x - b0.x;
        double vy = b1.y - b0.y;
        return Math.toDegrees(Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy));
    }

    /**
     * Один проход срезки углов ломаной. Узлы графа — вершины зон с углами JOIN_MITRE: у угла здания узел стоит на
     * d·√2 от него, а отступ меряется до самого здания. Вершина заменяется хордой между точками на соседних
     * отрезках, самой далёкой от вершины, при которой хорда допустима и не пересекает объектов специального прохода.
     * Вершина берёт не больше половины соседних отрезков, поэтому срезки не перекрываются, а подотрезки остаются не
     * короче метра; вершины у специальных частей не трогаются: спецпроход — один прямой участок. Два поворота у
     * короткой хорды {@link #sharpen} потом заменит одним, если он годится. {@code zones} — зоны Ду, по которым
     * проверяется хорда: графа или ребра дерева; {@code keep} — вершина, которую не трогать, или null; {@code apart} —
     * отрезки других рёбер дерева, к которым хорда, как и к своей ломаной вне вершины, не ближе CUT_APART_M; без
     * apart срез кэшируется по тройке вершин. true — что-то срезано, coords заменены.
     */
    public boolean cutPass(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart) {
        if (coords.size() < 3) {
            return false;
        }
        List<SpecialSpan> spans = zones.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
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
                cut = cache.computeIfAbsent(List.of(zones, "cut", prev.x, prev.y, cur.x, cur.y, next.x, next.y, ignored),
                        8, () -> longestCut(zones, prev, cur, next, least, room, ignored, List.of()));
            } else {
                List<LineSegment> near = new ArrayList<>(apart);
                for (int k = 0; k + 1 < coords.size(); k++) {
                    if (k != i - 1 && k != i) {
                        near.add(new LineSegment(coords.get(k), coords.get(k + 1)));
                    }
                }
                cut = longestCut(zones, prev, cur, next, least, room, ignored, near);
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
    private double longestCut(ObstacleSet zones, Coordinate prev, Coordinate cur, Coordinate next, double least,
            double room, Set<String> ignored, List<LineSegment> apart) {
        if (chord(zones, cur, prev, next, room, ignored, apart)) {
            return room;
        }
        if (!chord(zones, cur, prev, next, least, ignored, apart)) {
            return 0.0;
        }
        double low = least;
        double high = room;
        for (int k = 0; k < CUT_STEPS; k++) {
            double mid = (low + high) / 2;
            if (chord(zones, cur, prev, next, mid, ignored, apart)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Хорда среза cut у вершины cur обычная (без спецпрохода), держит отступы с запасом CHORD_MARGIN_M и не ближе
     * CUT_APART_M к отрезкам apart.
     */
    private boolean chord(ObstacleSet zones, Coordinate cur, Coordinate prev, Coordinate next, double cut,
            Set<String> ignored, List<LineSegment> apart) {
        Coordinate a = toward(cur, prev, cut);
        Coordinate b = toward(cur, next, cut);
        if (!zones.plain(a, b, ignored, CHORD_MARGIN_M)) {
            return false;
        }
        LineSegment chord = new LineSegment(a, b);
        for (LineSegment segment : apart) {
            if (!apart(a, b, segment.p0, segment.p1, CUT_APART_M) && chord.distance(segment) < CUT_APART_M) {
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

    /**
     * Рамки отрезков p–q и r–s разнесены по x или y больше чем на limit с запасом на округление: тогда и расстояние
     * между отрезками, и от конца одного до другого больше limit, и его не нужно считать. Проверки зазоров
     * перебирают все пары отрезков, а почти все пары далеко.
     */
    public static boolean apart(Coordinate p, Coordinate q, Coordinate r, Coordinate s, double limit) {
        double gap = limit + GAP_EPS_M;
        return Math.min(r.x, s.x) - Math.max(p.x, q.x) > gap || Math.min(p.x, q.x) - Math.max(r.x, s.x) > gap
                || Math.min(r.y, s.y) - Math.max(p.y, q.y) > gap || Math.min(p.y, q.y) - Math.max(r.y, s.y) > gap;
    }

    /** В b мелкий излом пути a–b–c: поворот меньше MIN_TURN_DEG, конец короткого плеча дальше MICRO_M от прямой. */
    public static boolean micro(Coordinate a, Coordinate b, Coordinate c) {
        double turn = deflectionDeg(a, b, c);
        return turn < MIN_TURN_DEG && Math.min(a.distance(b), b.distance(c)) * Math.sin(Math.toRadians(turn)) >= MICRO_M;
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
