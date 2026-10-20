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

public final class Router {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Router.class);
    public static final double MAX_TURN_DEG = 90 + 1e-6;
    private static final double MIN_TURN_DEG = 3;
    private static final double MIN_PIECE_M = 1;
    private static final double PUSH_TURN_DEG = 6;
    private static final double[] PUSH_STEPS_M = {0.5, 1, 2, 4, 8};
    private static final int MAX_PUSHES = 20;
    private static final int CUT_PASSES = 2;
    private static final int CUT_STEPS = 6;
    private static final double CUT_APART_M = 0.5;
    public static final double CUT_MARGIN_M = 0.15;
    private static final double CHORD_MARGIN_M = 0.2;
    public static final double CUT_PIECE_M = 1.05;
    private static final double SHORT_LINK_M = 10.05;
    private static final int SHARPEN_LINES = 3;
    private static final double SHARPEN_EPS = 1e-9;
    private static final double BEND_EPS_RAD = 1e-5;
    private static final int BEND_ROUNDS = 20;
    private static final int BEND_GRID = 20;
    private static final double BEND_GRID_MIN_M = 0.02;
    private static final double BEND_DESCENT_M = 0.002;
    private static final int BEND_DIRECTIONS = 16;
    private static final int BEND_SCAN_LIMIT = 400;
    private static final double BEND_EXTRA_M = 5;
    private static final double PAIR_MIN_DEG = 60;
    private static final double PAIR_MAX_DEG = 120;
    private static final double PAIR_LINK_M = 30;
    private static final double SPAN_EPS_M = 1e-3;
    private static final double CROSS_END_M = 0.01;
    public static final double MICRO_M = 0.001;
    private static final double UNKNOWN = Double.NEGATIVE_INFINITY;
    private static final double STUB_PLAIN_EPS_M = 1e-9;
    private static final int EXACT_STATES = 100_000;
    private static final double HYPOT_TOL = 1e-15;
    private static final double CAP_TOL = 1e-9;
    private static final double MAX_TURN_COS = Math.cos(Math.toRadians(MAX_TURN_DEG));
    private static final double TURN_COS_TOL = 1e-9;
    private static final double GAP_EPS_M = 1e-6;

    private final ObstacleSet obstacles;
    private final GeometryFactory factory = new GeometryFactory();
    private final List<Coordinate> nodes;
    private final double[] nodeXY;
    private final int[][] adjacency;
    private final double[][] adjacencyWeight;
    private final int[] edgeStart;
    private final RouteCache cache;
    private final java.util.concurrent.atomic.AtomicLong tableRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong tableHits = new java.util.concurrent.atomic.AtomicLong();

    private final class Table {
        final double[] dist;
        final int[] pred;
        final int[] order;
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

    public Router(ObstacleIndex index, Rules rules, Envelope area, int dn, RouteCache cache, org.locationtech.jts.geom.Geometry corridor) {
        this.cache = cache;
        long started = System.nanoTime();
        obstacles = new ObstacleSet(index, rules, area, dn, corridor, cache);
        long built = System.nanoTime();
        nodes = obstacles.nodes();
        int n = nodes.size();
        nodeXY = new double[2 * n];
        for (int i = 0; i < n; i++) {
            nodeXY[2 * i] = nodes.get(i).x;
            nodeXY[2 * i + 1] = nodes.get(i).y;
        }
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
        LOG.debug("router: dn={} nodes={} edges={} obstacles ms={} edges ms={}", dn, n, edgeStart[n] / 2,
                (built - started) / 1_000_000, (System.nanoTime() - built) / 1_000_000);
    }

    public ObstacleSet obstacles() {
        return obstacles;
    }

    public <T> T cached(List<Object> key, long bytes, java.util.function.Supplier<T> compute) {
        List<Object> own = new ArrayList<>(key);
        own.add(0, this);
        return cache.computeIfAbsent(own, bytes, compute);
    }

    public Route route(Point from, Point to, Set<String> ignored) {
        return routeToAny(from, List.of(to), ignored);
    }

    public Route routeToAny(Point from, Collection<Point> targets, Set<String> ignored) {
        return routeToAny(from, targets, ignored, false);
    }

    public Route routeToAny(Point from, Collection<Point> targets, Set<String> ignored, boolean cut) {
        Choice choice = choose(from.getCoordinate(), targets, ignored, Double.POSITIVE_INFINITY);
        return choice == null ? null : route(from, choice, ignored, cut);
    }

    public static final class Choice {
        final Coordinate target;
        final int via;
        final double weight;
        final double extra;
        final boolean tied;
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
            double lower = t.distance(source) + extra;
            if (lower >= bestWeight) {
                tied |= lower == bestWeight && bestTarget != null;
                continue;
            }
            double direct = direct(source, t, ignored);
            double weight = Double.isNaN(direct) ? Double.POSITIVE_INFINITY : direct;
            double cap = bestWeight - extra;
            cap += CAP_TOL * (1 + Math.abs(cap));
            boolean capped = weight > cap;
            if (capped) {
                weight = cap;
            }
            int via = -1;
            double[] toNodes = partialWeights(t, ignored);
            Stub[] stubs = stubs(t, ignored);
            ObstacleSet.Hint hint = ObstacleSet.hint();
            for (int i = 0; i < ready || (ready = table.settle(i + 1)) > i; i++) {
                int v = order[i];
                if (dist[v] > weight) {
                    break;
                }
                if (beyond(dist[v], nodeXY[2 * v], nodeXY[2 * v + 1], t, weight)) {
                    continue;
                }
                if (toNodes[v] == UNKNOWN) {
                    toNodes[v] = toNode(t, v, ignored, stubs, hint);
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

    private static final int SQUARE_PATHS_MAX = 100_000;
    private final Map<SquareKey, Coordinate[]> squarePaths = new java.util.concurrent.ConcurrentHashMap<>();

    public double[][] weights(List<Coordinate> points, Set<String> ignored) {
        int m = points.size();
        double[][] result = weights(points, points, ignored);
        for (int i = 0; i < m; i++) {
            result[i][i] = 0;
            for (int j = 0; j < i; j++) {
                double w = Math.min(result[i][j], result[j][i]);
                result[i][j] = w;
                result[j][i] = w;
            }
        }
        return result;
    }

    public double[][] weights(List<Coordinate> sources, List<Coordinate> targets, Set<String> ignored) {
        int m = targets.size();
        int n = nodes.size();
        double[][] toNodes = new double[m][];
        int[][] visible = new int[m][];
        for (int j = 0; j < m; j++) {
            double[] toNode = nodeWeights(targets.get(j), ignored);
            toNodes[j] = toNode;
            visible[j] = java.util.stream.IntStream.range(0, n).filter(v -> !Double.isNaN(toNode[v])).toArray();
        }
        double[][] result = new double[sources.size()][m];
        java.util.stream.IntStream.range(0, sources.size()).parallel().forEach(i -> {
            Coordinate source = sources.get(i);
            Table table = table(source, ignored);
            table.settle(n);
            ObstacleSet.Hint hint = ObstacleSet.hint();
            for (int j = 0; j < m; j++) {
                Coordinate t = targets.get(j);
                if (t.equals2D(source)) {
                    result[i][j] = 0;
                    continue;
                }
                double direct = direct(source, t, ignored);
                double weight = Double.isNaN(direct) ? Double.POSITIVE_INFINITY : direct;
                double[] w = toNodes[j];
                for (int v : visible[j]) {
                    if (table.dist[v] + w[v] >= weight) {
                        continue;
                    }
                    int p = table.pred[v];
                    if (turnAllowed(p < 0 ? source : nodes.get(p), nodes.get(v), t)) {
                        weight = table.dist[v] + w[v];
                    }
                }
                result[i][j] = weight;
            }
        });
        return result;
    }

    public Route route(Point from, Choice choice, Set<String> ignored, boolean cut) {
        List<Coordinate> path = new ArrayList<>();
        for (int v = choice.via; v >= 0; v = choice.pred[v]) {
            path.add(0, nodes.get(v));
        }
        path.add(0, from.getCoordinate());
        path.add(choice.target);
        return shaped(from, path, ignored, cut, choice.extra);
    }

    private static final class Shape {
        final Coordinate[] coords;
        final List<SpecialSpan> spans;

        Shape(Coordinate[] coords, List<SpecialSpan> spans) {
            this.coords = coords;
            this.spans = spans;
        }
    }

    private Route shaped(Point from, List<Coordinate> path, Set<String> ignored, boolean cut, double extra) {
        Shape shape = cache.computeIfAbsent(List.of(this, "route", path, ignored, cut), 96L * path.size(), () -> {
            List<Coordinate> coords = new ArrayList<>(path);
            unstub(coords, ignored);
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

    public Route routeExact(Point start, Collection<Point> targets, Set<String> ignored, Coordinate incoming, boolean cut) {
        Coordinate source = start.getCoordinate();
        Exact search = cache.computeIfAbsent(List.of(this, "exact", source.x, source.y, ignored, incoming.x, incoming.y),
                64L * nodes.size(), () -> exactStates(source, ignored, incoming));
        return exactBest(start, targets, ignored, incoming, search, cut);
    }

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
        int edges = edgeStart[n];
        double[] known = new double[edges + n];
        Arrays.fill(known, Double.POSITIVE_INFINITY);
        long[] came = new long[edges + n];
        Arrays.fill(came, -1);
        long[] keys = new long[edges + n];
        int[] found = new int[edges + n];
        int count = 0;
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

    private static int spread(long key) {
        int h = Long.hashCode(key);
        return h ^ (h >>> 16);
    }

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
                double direct = direct(source, t, ignored);
                if (!Double.isNaN(direct) && direct + extra < bestWeight) {
                    bestWeight = direct + extra;
                    bestTarget = t;
                    bestState = -1;
                    bestExtra = extra;
                }
            }
            double[] toNodes = partialWeights(t, ignored);
            Stub[] stubs = stubs(t, ignored);
            ObstacleSet.Hint hint = ObstacleSet.hint();
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
                    toNodes[v] = toNode(t, v, ignored, stubs, hint);
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

    static final class Heap {
        private double[] keys = new double[64];
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

    private static long state(int node, int pred, int n) {
        return (long) node * (n + 1) + (pred < 0 ? n : pred);
    }

    private Table table(Coordinate source, Set<String> ignored) {
        tableRequests.incrementAndGet();
        List<Object> key = List.of(this, "table", source.x, source.y, ignored);
        Table cached = cache.get(key);
        if (cached != null) {
            tableHits.incrementAndGet();
            return cached;
        }
        Table table = new Table(source, nodeWeights(source, ignored).clone());
        cache.put(key, table, 48L * nodes.size());
        return table;
    }

    public long[] tableStats() {
        return new long[] {tableRequests.get(), tableHits.get()};
    }

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
            Stub[] stubs = stubs(c, ignored);
            ObstacleSet.Hint hint = ObstacleSet.hint();
            for (int v = 0; v < weights.length; v++) {
                weights[v] = toNode(c, v, ignored, stubs, hint);
            }
            return weights;
        });
    }

    private static final class Stub {
        final Coordinate at;
        final double weight;

        Stub(Coordinate at, double weight) {
            this.at = at;
            this.weight = weight;
        }
    }

    private Stub[] stubs(Coordinate c, Set<String> ignored) {
        return cache.computeIfAbsent(List.of(this, "stubs", c.x, c.y, ignored), 64L, () -> {
            List<Stub> result = new ArrayList<>();
            for (Coordinate d : obstacles.departures(c, ignored)) {
                double weight = obstacles.edgeWeight(c, d, ignored, false, false);
                if (weight <= c.distance(d) + STUB_PLAIN_EPS_M) {
                    result.add(new Stub(d, weight));
                }
            }
            return result.toArray(new Stub[0]);
        });
    }

    private double toNode(Coordinate c, int v, Set<String> ignored, Stub[] stubs, ObstacleSet.Hint hint) {
        Coordinate node = nodes.get(v);
        double best = obstacles.tangent(v, c) ? obstacles.edgeWeight(c, node, ignored, false, true, hint) : Double.NaN;
        if (!Double.isNaN(best)) {
            return best;
        }
        for (Stub stub : stubs) {
            if (obstacles.tangent(v, stub.at) && turnAllowed(c, stub.at, node)) {
                double weight = stub.weight + obstacles.edgeWeight(stub.at, node, ignored, false, true, hint);
                if (Double.isNaN(best) || weight < best) {
                    best = weight;
                }
            }
        }
        return best;
    }

    private double direct(Coordinate source, Coordinate t, Set<String> ignored) {
        double best = obstacles.edgeWeight(t, source, ignored, false, false);
        if (!Double.isNaN(best)) {
            return best;
        }
        for (Stub stub : stubs(source, ignored)) {
            if (turnAllowed(source, stub.at, t)) {
                double weight = stub.weight + obstacles.edgeWeight(stub.at, t, ignored, false, false);
                if (Double.isNaN(best) || weight < best) {
                    best = weight;
                }
            }
        }
        for (Stub stub : stubs(t, ignored)) {
            if (turnAllowed(source, stub.at, t)) {
                double weight = obstacles.edgeWeight(source, stub.at, ignored, false, false) + stub.weight;
                if (Double.isNaN(best) || weight < best) {
                    best = weight;
                }
            }
        }
        return best;
    }

    private void unstub(List<Coordinate> coords, Set<String> ignored) {
        if (Double.isNaN(obstacles.edgeWeight(coords.get(0), coords.get(1), ignored))) {
            Coordinate next = coords.size() > 2 ? coords.get(2) : null;
            Coordinate d = bestStub(coords.get(0), coords.get(1), next, ignored, true);
            if (d != null) {
                coords.add(1, d);
            }
        }
        int n = coords.size();
        if (Double.isNaN(obstacles.edgeWeight(coords.get(n - 2), coords.get(n - 1), ignored))) {
            Coordinate before = n > 2 ? coords.get(n - 3) : null;
            Coordinate d = bestStub(coords.get(n - 1), coords.get(n - 2), before, ignored, false);
            if (d != null) {
                coords.add(n - 1, d);
            }
        }
    }

    private Coordinate bestStub(Coordinate end, Coordinate other, Coordinate beyond, Set<String> ignored, boolean start) {
        Coordinate best = null;
        double bestWeight = Double.POSITIVE_INFINITY;
        boolean bestTurns = false;
        for (Stub stub : stubs(end, ignored)) {
            if (!(start ? turnAllowed(end, stub.at, other) : turnAllowed(other, stub.at, end))) {
                continue;
            }
            double weight = stub.weight + obstacles.edgeWeight(stub.at, other, ignored);
            boolean turns = beyond == null
                    || (start ? turnAllowed(stub.at, other, beyond) : turnAllowed(beyond, other, stub.at));
            if (!Double.isNaN(weight) && (turns && !bestTurns || turns == bestTurns && weight < bestWeight)) {
                best = stub.at;
                bestWeight = weight;
                bestTurns = turns;
            }
        }
        return best;
    }

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

    private void cutCorners(List<Coordinate> coords, Set<String> ignored) {
        for (int pass = 0; pass < CUT_PASSES && cutPass(obstacles, coords, ignored, null, null); pass++) {
        }
        sharpen(obstacles, coords, ignored, null, null);
    }

    public boolean sharpen(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart) {
        return sharpen(zones, coords, ignored, keep, apart, null, List.of());
    }

    public boolean sharpen(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        return sharpen(zones, coords, ignored, keep, apart, before, after, false);
    }

    public boolean sharpen(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after, boolean bends) {
        List<LineSegment> others = apart == null ? List.of() : apart;
        boolean changed = false;
        while (coords.size() > 2) {
            List<SpecialSpan> spans = zones.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
            if (!drop(zones, coords, 1, keep, spans, ignored, others, before, after)
                    && !drop(zones, coords, 2, keep, spans, ignored, others, before, after)
                    && !merge(zones, coords, held(coords, keep, spans), spans, ignored, others)
                    && !(bends && bend(zones, coords, held(coords, keep, spans), spans, ignored, others, before, after,
                            BEND_EXTRA_M))) {
                break;
            }
            changed = true;
        }
        return changed;
    }

    public boolean loose(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart) {
        return loose(zones, coords, ignored, keep, apart, null, List.of());
    }

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

    private static boolean spanEnd(List<SpecialSpan> spans, double at) {
        for (SpecialSpan span : spans) {
            if (Math.abs(span.getFromM() - at) <= SPAN_EPS_M || Math.abs(span.getToM() - at) <= SPAN_EPS_M) {
                return true;
            }
        }
        return false;
    }

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
            boolean start = Math.abs(span.getFromM() - from) <= CROSS_END_M || span.getFromM() >= from + MIN_PIECE_M;
            boolean end = Math.abs(span.getToM() - to) <= CROSS_END_M || span.getToM() <= to - MIN_PIECE_M;
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

    private static boolean[] held(List<Coordinate> coords, Coordinate keep, List<SpecialSpan> spans) {
        boolean[] held = new boolean[coords.size()];
        double at = 0;
        for (int v = 1; v + 1 < coords.size(); v++) {
            at += coords.get(v - 1).distance(coords.get(v));
            held[v] = coords.get(v) == keep || overlapsSpan(spans, at - MIN_PIECE_M, at + MIN_PIECE_M);
        }
        return held;
    }

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

    private static Removal removal(List<Coordinate> coords, double[] at, List<SpecialSpan> spans, Coordinate keep,
            int lo, int hi) {
        int n = coords.size();
        while (true) {
            for (int v = lo; v <= hi; v++) {
                if (coords.get(v) == keep || overlapsSpan(spans, at[v] - SPAN_EPS_M, at[v] + SPAN_EPS_M)) {
                    return null;
                }
            }
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

    private static boolean startFits(List<Coordinate> shape, int join, Coordinate before) {
        return join != 0 || before == null || turnAllowed(before, shape.get(0), shape.get(1));
    }

    private static boolean endFits(List<Coordinate> shape, int join, List<Coordinate> after) {
        int n = shape.size();
        return join + 2 != n || after.stream().allMatch(c -> turnAllowed(shape.get(n - 2), shape.get(n - 1), c));
    }

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

    private static boolean along(ObstacleSet zones, List<Coordinate> shape, int join, Set<String> ignored) {
        int n = shape.size();
        Coordinate a = shape.get(join);
        Coordinate b = shape.get(join + 1);
        if (n == 2) {
            return zones.alongIgnored(a, b, ignored) && zones.alongIgnored(b, a, ignored);
        }
        return join == 0 && zones.alongIgnored(a, b, ignored) || join + 2 == n && zones.alongIgnored(b, a, ignored);
    }

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

    private boolean bend(ObstacleSet zones, List<Coordinate> coords, boolean[] held, List<SpecialSpan> spans,
            Set<String> ignored, List<LineSegment> apart, Coordinate before, List<Coordinate> after, double extraM) {
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
            boolean[] kept = {!start && j >= 2 && !overlapsSpan(spans, from - SPAN_EPS_M, from + SPAN_EPS_M),
                !end && j + 3 < n && !overlapsSpan(spans, to - SPAN_EPS_M, to + SPAN_EPS_M)};
            double total = Math.abs(turns[j] + turns[j + 1]);
            boolean pair = total >= PAIR_MIN_DEG && total <= PAIR_MAX_DEG && at[j + 1] - at[j] < PAIR_LINK_M;
            if (bent(zones, shape, x, coords.get(j), coords.get(j + 1), kept, total, to - from + (pair ? extraM : 0),
                    ignored, apart, before, after)) {
                coords.clear();
                coords.addAll(shape);
                return true;
            }
        }
        return false;
    }

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
        if (fits && ends[0].distance(at) + at.distance(ends[1]) <= length + SHARPEN_EPS) {
            return true;
        }
        return scanned(zones, shape, x, new LineSegment(p, q), kept, length, ignored, apart, before, after);
    }

    private boolean scanned(ObstacleSet zones, List<Coordinate> shape, int x, LineSegment pair, boolean[] kept,
            double length, Set<String> ignored, List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        Coordinate a = shape.get(x - 1);
        Coordinate b = shape.get(x + 1);
        double d = a.distance(b);
        double half = length / 2;
        double minor = Math.sqrt(Math.max(0, half * half - d * d / 4));
        if (d == 0 || minor == 0) {
            return false;
        }
        double ex = (b.x - a.x) / d;
        double ey = (b.y - a.y) / d;
        double step = Math.max(minor / BEND_GRID, BEND_GRID_MIN_M);
        List<Coordinate> grid = new ArrayList<>();
        for (double u = -half; u < half; u += step) {
            for (double v = -minor; v < minor; v += step) {
                if ((u / half) * (u / half) + (v / minor) * (v / minor) <= 1) {
                    grid.add(new Coordinate((a.x + b.x) / 2 + u * ex - v * ey, (a.y + b.y) / 2 + u * ey + v * ex));
                }
            }
        }
        grid.sort(Comparator.comparingDouble(pair::distance));
        int tried = 0;
        for (Coordinate c : grid) {
            if (tried >= BEND_SCAN_LIMIT) {
                break;
            }
            if (!(a.distance(c) + c.distance(b) <= length + SHARPEN_EPS) || !vertexFits(shape, x, c)) {
                continue;
            }
            tried++;
            if (!pointFits(zones, shape, x, c, kept, ignored, apart, before, after)) {
                continue;
            }
            Coordinate best = c;
            for (double h = step; h > BEND_DESCENT_M; ) {
                Coordinate next = null;
                double shortest = a.distance(best) + best.distance(b);
                for (int k = 0; k < BEND_DIRECTIONS; k++) {
                    double t = 2 * Math.PI * k / BEND_DIRECTIONS;
                    Coordinate probe = new Coordinate(best.x + h * Math.cos(t), best.y + h * Math.sin(t));
                    double len = a.distance(probe) + probe.distance(b);
                    if (len < shortest && vertexFits(shape, x, probe)
                            && pointFits(zones, shape, x, probe, kept, ignored, apart, before, after)) {
                        next = probe;
                        shortest = len;
                    }
                }
                if (next == null) {
                    h /= 2;
                } else {
                    best = next;
                }
            }
            shape.set(x, best);
            return true;
        }
        return false;
    }

    private static boolean vertexFits(List<Coordinate> shape, int x, Coordinate c) {
        Coordinate a = shape.get(x - 1);
        Coordinate b = shape.get(x + 1);
        return a.distance(c) >= CUT_PIECE_M && c.distance(b) >= CUT_PIECE_M && turnAllowed(a, c, b)
                && deflectionDeg(a, c, b) >= MIN_TURN_DEG;
    }

    private boolean pointFits(ObstacleSet zones, List<Coordinate> shape, int x, Coordinate c, boolean[] kept,
            Set<String> ignored, List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        shape.set(x, c);
        return linkFits(zones, shape, x, 0, kept, ignored, apart, before, after)
                && linkFits(zones, shape, x, 1, kept, ignored, apart, before, after);
    }

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
        return linkFits(zones, shape, x, side, kept, ignored, apart, before, after);
    }

    private boolean linkFits(ObstacleSet zones, List<Coordinate> shape, int x, int side, boolean[] kept,
            Set<String> ignored, List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        int join = x - 1 + side;
        boolean outer = side == 0 ? join < 1 || turnAllowed(shape.get(join - 1), shape.get(join), shape.get(x))
                && (!kept[0] || deflectionDeg(shape.get(join - 1), shape.get(join), shape.get(x)) >= MIN_TURN_DEG)
                : join + 2 >= shape.size() || turnAllowed(shape.get(x), shape.get(join + 1), shape.get(join + 2))
                && (!kept[1] || deflectionDeg(shape.get(x), shape.get(join + 1), shape.get(join + 2)) >= MIN_TURN_DEG);
        return outer && startFits(shape, join, before) && endFits(shape, join, after)
                && pieceFits(zones, shape, join, ignored, apart);
    }

    private static double heading(Coordinate a, Coordinate b) {
        return Math.atan2(b.y - a.y, b.x - a.x);
    }

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

    public static boolean paired(Coordinate[] coords) {
        double[] turns = turns(Arrays.asList(coords));
        for (int v = 1; v + 2 < coords.length; v++) {
            if (Math.abs(turns[v]) >= MIN_TURN_DEG && Math.abs(turns[v + 1]) >= MIN_TURN_DEG && turns[v] * turns[v + 1] > 0) {
                return true;
            }
        }
        return false;
    }

    public static int pairs(Coordinate[] coords) {
        double[] turns = turns(Arrays.asList(coords));
        int count = 0;
        for (int v = 1; v + 2 < coords.length; v++) {
            if (Math.abs(turns[v]) >= MIN_TURN_DEG && Math.abs(turns[v + 1]) >= MIN_TURN_DEG && turns[v] * turns[v + 1] > 0) {
                count++;
            }
        }
        return count;
    }

    static double[] turns(List<Coordinate> coords) {
        double[] turns = new double[coords.size()];
        for (int v = 1; v + 1 < coords.size(); v++) {
            turns[v] = turnDeg(coords.get(v - 1), coords.get(v), coords.get(v), coords.get(v + 1));
        }
        return turns;
    }

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

    private boolean fits(ObstacleSet zones, List<Coordinate> coords, List<Coordinate> shape, int i, int j, int[] kept,
            List<SpecialSpan> spans, Set<String> ignored, List<LineSegment> apart, Map<List<Integer>, Boolean> pieces) {
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

    public static boolean apart(LineSegment segment, List<LineSegment> others) {
        for (LineSegment other : others) {
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

    private static double turnDeg(Coordinate a0, Coordinate a1, Coordinate b0, Coordinate b1) {
        double ux = a1.x - a0.x;
        double uy = a1.y - a0.y;
        double vx = b1.x - b0.x;
        double vy = b1.y - b0.y;
        return Math.toDegrees(Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy));
    }

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

    static boolean turnAllowed(Coordinate a, Coordinate b, Coordinate c) {
        return turnAllowed(a.x, a.y, b.x, b.y, c.x, c.y);
    }

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

    public static boolean apart(Coordinate p, Coordinate q, Coordinate r, Coordinate s, double limit) {
        double gap = limit + GAP_EPS_M;
        return Math.min(r.x, s.x) - Math.max(p.x, q.x) > gap || Math.min(p.x, q.x) - Math.max(r.x, s.x) > gap
                || Math.min(r.y, s.y) - Math.max(p.y, q.y) > gap || Math.min(p.y, q.y) - Math.max(r.y, s.y) > gap;
    }

    public static boolean micro(Coordinate a, Coordinate b, Coordinate c) {
        double turn = deflectionDeg(a, b, c);
        return turn < MIN_TURN_DEG && Math.min(a.distance(b), b.distance(c)) * Math.sin(Math.toRadians(turn)) >= MICRO_M;
    }

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

    public static final double SQUARE_TOL_RAD = Math.toRadians(0.5);
    private static final double SQUARE_KEPT_DEG = 30;
    private static final double SQUARE_EXIT_DEG = 15;
    private static final double SQUARE_MERGE_M = 1.5;
    private static final double SQUARE_LEAVE_M = 1.5;
    private static final double SQUARE_PROBE_M = 4;
    private static final double SQUARE_LEAVE_TOL_M = 0.5;
    private static final double SQUARE_FAN_RAD = Math.toRadians(10);
    private static final double SQUARE_MARGIN_M = 3;

    public boolean square(ObstacleSet zones, List<Coordinate> coords, Set<String> ignored, Coordinate keep,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after, double axis, double ratio, double turnM) {
        int n = coords.size();
        List<SpecialSpan> spans = zones.spans(factory.createLineString(coords.toArray(new Coordinate[0])), ignored);
        double[] at = new double[n];
        for (int v = 1; v < n; v++) {
            at[v] = at[v - 1] + coords.get(v - 1).distance(coords.get(v));
        }
        boolean[] held = held(coords, keep, spans);
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int v = 1; v + 1 < n; v++) {
            if (held[v]) {
                anchors.add(v);
            }
        }
        anchors.add(n - 1);
        boolean changed = false;
        for (int k = anchors.size() - 2; k >= 0; k--) {
            int i0 = anchors.get(k);
            int j0 = anchors.get(k + 1);
            if (overlapsSpan(spans, at[i0] + SPAN_EPS_M, at[j0] - SPAN_EPS_M)) {
                continue;
            }
            int[][] tries = {{i0, j0}, {i0 + 1, j0}, {i0, j0 - 1}, {i0 + 1, j0 - 1}};
            for (int[] range : tries) {
                int i = range[0];
                int j = range[1];
                if (i >= j || squared(coords, i, j, axis)) {
                    continue;
                }
                List<Coordinate> path = squarePath(zones, coords, i, j, ignored, apart, before, after, axis, turnM,
                        keep != null && coords.get(j) == keep && j + 1 < coords.size());
                if (path == null) {
                    continue;
                }
                double length = 0;
                for (int v = 1; v < path.size(); v++) {
                    length += path.get(v - 1).distance(path.get(v));
                }
                if (length > ratio * (at[j] - at[i])) {
                    continue;
                }
                List<Coordinate> shape = new ArrayList<>(coords.subList(0, i));
                shape.addAll(path);
                shape.addAll(coords.subList(j + 1, coords.size()));
                int hi = i + path.size() - 1;
                if (!squareFits(zones, shape, i, hi, ignored, apart, before, after)
                        || i > i0 && deflectionDeg(shape.get(i - 1), shape.get(i), shape.get(i + 1)) < SQUARE_KEPT_DEG
                        || j < j0 && deflectionDeg(shape.get(hi - 1), shape.get(hi), shape.get(hi + 1)) < SQUARE_KEPT_DEG
                        || i > i0 && removable(zones, shape, i, ignored, apart, before, after)
                        || j < j0 && removable(zones, shape, hi, ignored, apart, before, after)) {
                    continue;
                }
                for (int v : new int[] {hi, i}) {
                    if (v > 0 && v + 1 < shape.size()
                            && deflectionDeg(shape.get(v - 1), shape.get(v), shape.get(v + 1)) < Math.toDegrees(SQUARE_TOL_RAD)) {
                        shape.remove(v);
                    }
                }
                if (shape.size() > coords.size() || pairs(shape.toArray(new Coordinate[0])) > pairs(coords.toArray(new Coordinate[0]))
                        || bendable(zones, shape, keep, ignored, apart, before, after)) {
                    continue;
                }
                coords.clear();
                coords.addAll(shape);
                changed = true;
                break;
            }
        }
        return changed;
    }

    public boolean pieceFitsAt(ObstacleSet zones, List<Coordinate> coords, int k, Set<String> ignored, List<LineSegment> apart) {
        int n = coords.size();
        for (int v = Math.max(1, k); v <= Math.min(n - 2, k + 1); v++) {
            Coordinate a = coords.get(v - 1);
            Coordinate b = coords.get(v);
            Coordinate c = coords.get(v + 1);
            if (!turnAllowed(a, b, c) || deflectionDeg(a, b, c) < MIN_TURN_DEG) {
                return false;
            }
        }
        return pieceFits(zones, coords, k, ignored, apart);
    }

    public boolean removable(ObstacleSet zones, List<Coordinate> shape, int v, Set<String> ignored, List<LineSegment> apart,
            Coordinate before, List<Coordinate> after) {
        if (v <= 0 || v + 1 >= shape.size()) {
            return false;
        }
        List<Coordinate> without = new ArrayList<>(shape);
        without.remove(v);
        return removalFits(zones, without, v - 1, ignored, apart, before, after);
    }

    private boolean bendable(ObstacleSet zones, List<Coordinate> shape, Coordinate keep, Set<String> ignored,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        List<Coordinate> probe = new ArrayList<>(shape);
        List<SpecialSpan> spans = zones.spans(factory.createLineString(probe.toArray(new Coordinate[0])), ignored);
        boolean[] held = held(probe, keep, spans);
        for (int v = 1; v + 1 < probe.size(); v++) {
            if (!held[v] && removable(zones, probe, v, ignored, apart, before, after)) {
                return true;
            }
        }
        return bend(zones, probe, held, spans, ignored, apart, before, after, 0);
    }

    private static boolean squared(List<Coordinate> coords, int i, int j, double axis) {
        for (int v = i; v < j; v++) {
            double off = Math.abs(Math.IEEEremainder(heading(coords.get(v), coords.get(v + 1)) - axis, Math.PI / 2));
            if (off > SQUARE_TOL_RAD) {
                return false;
            }
        }
        return true;
    }

    private List<Coordinate> squarePath(ObstacleSet zones, List<Coordinate> coords, int i, int j, Set<String> ignored,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after, double axis, double turnM, boolean exit) {
        Coordinate in = i > 0 ? coords.get(i - 1) : before;
        List<Coordinate> outs = j + 1 < coords.size() ? List.of(coords.get(j + 1)) : after;
        Envelope box = new Envelope();
        for (int v = i; v <= j; v++) {
            box.expandToInclude(coords.get(v));
        }
        Coordinate center = box.centre();
        double reach = (Math.hypot(box.getWidth() / 2 + SQUARE_MARGIN_M, box.getHeight() / 2 + SQUARE_MARGIN_M) + SQUARE_MARGIN_M)
                * Math.sqrt(2) + 2 * CUT_APART_M;
        List<LineSegment> near = new ArrayList<>();
        for (LineSegment segment : apart) {
            if (segment.distance(center) <= reach) {
                near.add(segment);
            }
        }
        double[] values = new double[2 * (j - i + 2 + outs.size() + 2 * near.size()) + 3];
        int k = 0;
        for (int v = i; v <= j; v++) {
            values[k++] = coords.get(v).x;
            values[k++] = coords.get(v).y;
        }
        values[k++] = in == null ? Double.NaN : in.x;
        values[k++] = in == null ? Double.NaN : in.y;
        for (Coordinate out : outs) {
            values[k++] = out.x;
            values[k++] = out.y;
        }
        for (LineSegment segment : near) {
            values[k++] = segment.p0.x;
            values[k++] = segment.p0.y;
            values[k++] = segment.p1.x;
            values[k++] = segment.p1.y;
        }
        values[k++] = axis;
        values[k++] = turnM;
        values[k] = j - i;
        SquareKey key = new SquareKey(zones, ignored, exit, values);
        Coordinate[] known = squarePaths.get(key);
        if (known == null) {
            List<Coordinate> path = searchSquarePath(zones, coords, i, j, ignored, apart, before, after, axis, turnM, exit);
            if (squarePaths.size() < SQUARE_PATHS_MAX) {
                squarePaths.putIfAbsent(key, path == null ? new Coordinate[0]
                        : path.stream().map(Coordinate::new).toArray(Coordinate[]::new));
            }
            return path;
        }
        if (known.length == 0) {
            return null;
        }
        List<Coordinate> path = new ArrayList<>(known.length);
        path.add(coords.get(i));
        for (int v = 1; v + 1 < known.length; v++) {
            path.add(new Coordinate(known[v]));
        }
        path.add(coords.get(j));
        return path;
    }

    private static final class SquareKey {
        final ObstacleSet zones;
        final Set<String> ignored;
        final boolean exit;
        final double[] values;
        final int hash;

        SquareKey(ObstacleSet zones, Set<String> ignored, boolean exit, double[] values) {
            this.zones = zones;
            this.ignored = ignored;
            this.exit = exit;
            this.values = values;
            this.hash = (System.identityHashCode(zones) * 31 + ignored.hashCode()) * 31 + Arrays.hashCode(values) + (exit ? 1 : 0);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SquareKey)) {
                return false;
            }
            SquareKey key = (SquareKey) other;
            return zones == key.zones && exit == key.exit && hash == key.hash && Arrays.equals(values, key.values)
                    && ignored.equals(key.ignored);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private List<Coordinate> searchSquarePath(ObstacleSet zones, List<Coordinate> coords, int i, int j, Set<String> ignored,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after, double axis, double turnM, boolean exit) {
        Coordinate p = coords.get(i);
        Coordinate q = coords.get(j);
        Coordinate in = i > 0 ? coords.get(i - 1) : before;
        List<Coordinate> outs = j + 1 < coords.size() ? List.of(coords.get(j + 1)) : after;
        List<Double> near = new ArrayList<>();
        List<Double> limit = new ArrayList<>();
        if (in != null) {
            near.add(heading(in, p));
            limit.add(Math.toRadians(MIN_TURN_DEG));
        }
        outs.forEach(out -> {
            near.add(heading(q, out));
            limit.add(Math.toRadians(MIN_TURN_DEG));
        });
        if (exit) {
            near.add(heading(q, outs.get(0)));
            limit.add(Math.toRadians(SQUARE_EXIT_DEG));
        }
        for (int k = 0; k < near.size(); k++) {
            double off = Math.IEEEremainder(near.get(k) - axis, Math.PI / 2);
            if (Math.abs(off) < limit.get(k)) {
                axis += off;
                break;
            }
        }
        double c = Math.cos(axis);
        double s = Math.sin(axis);
        double[] us = new double[j - i + 1];
        double[] vs = new double[j - i + 1];
        Envelope box = new Envelope();
        for (int v = i; v <= j; v++) {
            us[v - i] = coords.get(v).x * c + coords.get(v).y * s;
            vs[v - i] = -coords.get(v).x * s + coords.get(v).y * c;
            box.expandToInclude(coords.get(v));
        }
        box.expandBy(SQUARE_MARGIN_M);
        List<double[]> corners = new ArrayList<>();
        for (int k = 0; k < nodes.size(); k++) {
            if (box.contains(nodeXY[2 * k], nodeXY[2 * k + 1])) {
                corners.add(new double[] {nodeXY[2 * k] * c + nodeXY[2 * k + 1] * s, -nodeXY[2 * k] * s + nodeXY[2 * k + 1] * c});
            }
        }
        double[] u = lines(us, corners, 0);
        double[] w = lines(vs, corners, 1);
        int nu = u.length;
        int nw = w.length;
        int start = Arrays.binarySearch(u, us[0]) * nw + Arrays.binarySearch(w, vs[0]);
        int end = Arrays.binarySearch(u, us[j - i]) * nw + Arrays.binarySearch(w, vs[j - i]);
        if (start == end) {
            return null;
        }
        Coordinate[] node = new Coordinate[nu * nw];
        for (int a = 0; a < nu; a++) {
            for (int b = 0; b < nw; b++) {
                node[a * nw + b] = new Coordinate(u[a] * c - w[b] * s, u[a] * s + w[b] * c);
            }
        }
        node[start] = p;
        node[end] = q;
        List<LineSegment> far = new ArrayList<>();
        List<Coordinate[]> fan = new ArrayList<>();
        for (LineSegment other : apart) {
            for (Coordinate tip : new Coordinate[] {p, q}) {
                if (other.p0.equals2D(tip) || other.p1.equals2D(tip)) {
                    fan.add(new Coordinate[] {tip, other.p0.equals2D(tip) ? other.p1 : other.p0});
                }
            }
            if (!other.p0.equals2D(p) && !other.p1.equals2D(p) && !other.p0.equals2D(q) && !other.p1.equals2D(q)) {
                far.add(other);
            }
        }
        int states = 4 * nu * nw;
        double[] cost = new double[states];
        int[] pred = new int[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(pred, -1);
        Map<Long, Boolean> link = new HashMap<>();
        java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>(Comparator.comparingDouble(e -> e[0]));
        double[] lengths = new double[states];
        Arrays.fill(lengths, Double.NaN);
        int[] du = {1, 0, -1, 0};
        int[] dw = {0, 1, 0, -1};
        for (int d = 0; d < 4; d++) {
            int next = step(start, d, nu, nw, du, dw);
            if (next >= 0 && (in == null || turnAllowed(in, p, node[next]) && !micro(in, p, node[next]))
                    && open(zones, node[start], node[next], start, d, ignored, far, fan, p, q, link)) {
                int state = 4 * next + d;
                cost[state] = node[start].distance(node[next]);
                pred[state] = -1 - start;
                queue.add(new double[] {cost[state], state});
            }
        }
        int best = -1;
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int state = (int) top[1];
            if (top[0] > cost[state]) {
                continue;
            }
            int at = state / 4;
            int d = state % 4;
            if (at == end) {
                Coordinate from = node[pred[state] < 0 ? -1 - pred[state] : pred[state] / 4];
                boolean fits = true;
                for (Coordinate out : outs) {
                    fits &= turnAllowed(from, q, out) && (!micro(from, q, out)
                            || exit && deflectionDeg(from, q, out) < Math.toDegrees(SQUARE_TOL_RAD));
                }
                if (fits) {
                    best = state;
                    break;
                }
                continue;
            }
            for (int e = 0; e < 4; e++) {
                int next = e == (d + 2) % 4 ? -1 : step(at, e, nu, nw, du, dw);
                if (next < 0 || !open(zones, node[at], node[next], at, e, ignored, far, fan, p, q, link)) {
                    continue;
                }
                double fresh = cost[state] + length(lengths, node, at, e, next) + (e == d ? 0 : turnM);
                int to = 4 * next + e;
                if (fresh < cost[to]) {
                    cost[to] = fresh;
                    pred[to] = state;
                    queue.add(new double[] {fresh, to});
                }
            }
        }
        if (best < 0) {
            return null;
        }
        List<Coordinate> path = new ArrayList<>();
        path.add(q);
        for (int state = best; ; ) {
            int prev = pred[state];
            if (prev < 0) {
                path.add(p);
                break;
            }
            if (prev % 4 != state % 4) {
                path.add(node[prev / 4]);
            }
            state = prev;
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static double[] lines(double[] values, List<double[]> corners, int axis) {
        java.util.TreeSet<Double> raw = new java.util.TreeSet<>();
        for (double value : values) {
            raw.add(value);
        }
        corners.forEach(corner -> raw.add(corner[axis]));
        Double[] sorted = raw.toArray(new Double[0]);
        for (int k = 0; k + 1 < sorted.length; k++) {
            raw.add((sorted[k] + sorted[k + 1]) / 2);
        }
        raw.add(sorted[0] - SQUARE_MARGIN_M);
        raw.add(sorted[sorted.length - 1] + SQUARE_MARGIN_M);
        List<Double> kept = new ArrayList<>();
        for (double value : values) {
            kept.add(value);
        }
        for (double value : raw) {
            if (kept.stream().noneMatch(other -> Math.abs(other - value) < SQUARE_MERGE_M)) {
                kept.add(value);
            }
        }
        return kept.stream().mapToDouble(Double::doubleValue).distinct().sorted().toArray();
    }

    private static double length(double[] lengths, Coordinate[] node, int at, int e, int next) {
        double length = lengths[4 * at + e];
        if (Double.isNaN(length)) {
            length = node[at].distance(node[next]);
            lengths[4 * at + e] = length;
        }
        return length;
    }

    private static int step(int at, int d, int nu, int nw, int[] du, int[] dw) {
        int a = at / nw + du[d];
        int b = at % nw + dw[d];
        return a < 0 || a >= nu || b < 0 || b >= nw ? -1 : a * nw + b;
    }

    private boolean open(ObstacleSet zones, Coordinate a, Coordinate b, int at, int d, Set<String> ignored,
            List<LineSegment> apart, List<Coordinate[]> fan, Coordinate p, Coordinate q, Map<Long, Boolean> link) {
        long key = 4L * at + d;
        Boolean known = link.get(key);
        if (known == null) {
            known = zones.covers(a, b) && zones.plain(a, b, ignored, CUT_MARGIN_M) && apart(new LineSegment(a, b), apart);
            for (Coordinate from : new Coordinate[] {p, q}) {
                Coordinate to = from.equals2D(a) ? b : from.equals2D(b) ? a : null;
                if (known && to != null && !ignored.isEmpty()) {
                    double length = from.distance(to);
                    LineSegment ray = new LineSegment(from, to);
                    known = !zones.nearIgnored(ray.pointAlong(SQUARE_LEAVE_M / length), ray.pointAlong(SQUARE_PROBE_M / length),
                            ignored, SQUARE_LEAVE_TOL_M);
                }
            }
            for (Coordinate[] other : fan) {
                Coordinate from = other[0].equals2D(a) ? a : other[0].equals2D(b) ? b : null;
                Coordinate to = from == a ? b : a;
                known &= from == null || Math.abs(Angle.diff(heading(from, to), heading(from, other[1]))) >= SQUARE_FAN_RAD;
            }
            link.put(key, known);
        }
        return known;
    }

    private boolean squareFits(ObstacleSet zones, List<Coordinate> shape, int lo, int hi, Set<String> ignored,
            List<LineSegment> apart, Coordinate before, List<Coordinate> after) {
        int n = shape.size();
        for (int v = Math.max(1, lo); v <= Math.min(n - 2, hi); v++) {
            Coordinate a = shape.get(v - 1);
            Coordinate b = shape.get(v);
            Coordinate c = shape.get(v + 1);
            double turn = deflectionDeg(a, b, c);
            boolean through = (v == lo || v == hi) && turn < Math.toDegrees(SQUARE_TOL_RAD);
            if (!turnAllowed(a, b, c) || turn < MIN_TURN_DEG && !through) {
                return false;
            }
        }
        if (lo == 0 && before != null && (!startFits(shape, 0, before) || micro(before, shape.get(0), shape.get(1)))
                || hi == n - 1 && (!endFits(shape, n - 2, after)
                || after.stream().anyMatch(x -> micro(shape.get(n - 2), shape.get(n - 1), x)))) {
            return false;
        }
        for (int v = Math.max(1, lo); v + 1 <= Math.min(n - 2, hi + 1); v++) {
            if (v + 1 > n - 2 || v - 1 < 0) {
                continue;
            }
            double first = turnDeg(shape.get(v - 1), shape.get(v), shape.get(v), shape.get(v + 1));
            double second = turnDeg(shape.get(v), shape.get(v + 1), shape.get(v + 1), shape.get(v + 2));
            if (first * second < 0 && Math.abs(first) >= MIN_TURN_DEG && Math.abs(second) >= MIN_TURN_DEG
                    && shape.get(v).distance(shape.get(v + 1)) < SHORT_LINK_M) {
                return false;
            }
        }
        for (int k = lo; k < hi; k++) {
            if (!pieceFits(zones, shape, k, ignored, apart)) {
                return false;
            }
        }
        return true;
    }
}
