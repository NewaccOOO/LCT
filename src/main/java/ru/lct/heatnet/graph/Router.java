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
    /** Отрезок выровнен по сетке, если отклонён от её направления не больше чем на полдопуска угла из rules.json. */
    private static final double ALIGN_TOL_DEG = 0.5;
    /** Шаги сдвига соседней вершины вдоль её выровненного отрезка, м. */
    private static final double[] SLIDES_M = {1, 2, 3, 5, 8, 13, 21};
    // ponytail: точки запроса повторяются десятками раз (одна точка подключения на каждом шаге дерева, одни и те же
    // цели у всех ОКС шага), кэш их весов до узлов графа. 4096 записей по n double; при n в десятки тысяч ужать.
    private static final int WEIGHT_CACHE_SIZE = 4096;
    // ponytail: таблица Дейкстры от точки запроса зависит только от неё и набора пропускаемых объектов, а локальный
    // поиск запрашивает одни и те же точки подключения десятки раз. 256 записей по 2n чисел; при n в десятки тысяч ужать.
    private static final int TABLE_CACHE_SIZE = 256;
    private static final double UNKNOWN = Double.NEGATIVE_INFINITY;

    private final ObstacleSet obstacles;
    private final Rules rules;
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
    private final Map<List<Object>, double[]> partialCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, double[]> eldest) {
            return size() > WEIGHT_CACHE_SIZE;
        }
    };
    private final Map<List<Object>, Table> tableCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, Table> eldest) {
            return size() > TABLE_CACHE_SIZE;
        }
    };
    private long tableRequests;
    private long tableHits;

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
        obstacles = new ObstacleSet(index, rules, area, dn);
        this.rules = rules;
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

    /**
     * Кратчайший маршрут до ближайшей по весу цели или {@code null}, если ни одна цель не достижима. Если в
     * {@code userData} цели лежит {@link Double}, это надбавка к её весу в метрах (например, стоимость камеры,
     * которую придётся построить в этой точке); вес маршрута возвращается с надбавкой выбранной цели.
     */
    public synchronized Route routeToAny(Point from, Collection<Point> targets, Set<String> ignored) {
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
                if (!Double.isNaN(toNodes[v]) && dist[v] + toNodes[v] < weight) {
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
        octilinearize(coords, ignored);
        straighten(coords, ignored);
        LineString line = from.getFactory().createLineString(coords.toArray(new Coordinate[0]));
        List<SpecialSpan> spans = obstacles.spans(line, ignored);
        return new Route(line, line.getLength(), ObstacleSet.weight(line.getLength(), spans) + bestExtra, spans);
    }

    /** Таблица Дейкстры от точки запроса, из кэша по координате и набору пропускаемых объектов. */
    private Table table(Coordinate source, Set<String> ignored) {
        tableRequests++;
        Table cached = tableCache.get(List.of(source.x, source.y, ignored));
        if (cached != null) {
            tableHits++;
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
        Table table = new Table(dist, pred);
        tableCache.put(List.of(source.x, source.y, ignored), table);
        return table;
    }

    /** Сколько таблиц Дейкстры запрошено и сколько из них взято из кэша. */
    public synchronized long[] tableStats() {
        return new long[] {tableRequests, tableHits};
    }

    /**
     * Веса от точки запроса до узлов графа, NaN — отрезок недопустим. Не зависят от других точек запроса, поэтому
     * кэшируются по координате и набору пропускаемых объектов. Считаются последовательно: параллельный расчёт над
     * общими геометриями JTS изредка давал разные трассы на одном входе.
     */
    /** Веса от цели до узлов, считаются лениво по мере надобности; UNKNOWN — ещё не считался. Кэш отдельный от полных. */
    private double[] partialWeights(Coordinate c, Set<String> ignored) {
        return partialCache.computeIfAbsent(List.of(c.x, c.y, ignored), key -> {
            double[] weights = new double[nodes.size()];
            Arrays.fill(weights, UNKNOWN);
            return weights;
        });
    }

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
     * Приводит путь к направлениям сетки через 45° (протокол 16.09.2026 п. 9): тогда каждый излом равен 0°, 45°,
     * 90° или 135°, и первые три бесплатны. Сетка повёрнута по самому длинному отрезку пути: он и всё, что уже
     * под 45° или 90° к нему, не меняется, а прямая трасса без изломов остаётся прямой. Невыровненный отрезок
     * раскладывается на два по соседним направлениям сетки в любом из двух порядков. Чтобы новые отрезки прошли мимо зон, соседняя вершина может скользить по
     * своему выровненному отрезку: начало назад по входящему, конец вперёд по исходящему, на фиксированные шаги и
     * ровно до выравнивания отрезка целиком. Из допустимых вариантов берётся тот, где меньше нестандартных изломов,
     * потом поворотов, потом длины. Отрезок, для которого варианта нет, остаётся как был, и участок платит
     * k_nonstandard.
     */
    private void octilinearize(List<Coordinate> coords, Set<String> ignored) {
        double[][] frame = frame(coords);
        for (int i = 0; i + 1 < coords.size(); i++) {
            Coordinate u = coords.get(i);
            Coordinate v = coords.get(i + 1);
            if (u.distance(v) == 0 || frameIndex(frame, u, v) >= 0) {
                continue;
            }
            Coordinate before = i > 0 ? coords.get(i - 1) : null;
            Coordinate after = i + 2 < coords.size() ? coords.get(i + 2) : null;
            int inDir = before == null ? -1 : frameIndex(frame, before, u);
            int outDir = after == null ? -1 : frameIndex(frame, v, after);
            List<Coordinate[]> options = new ArrayList<>(splits(frame, u, v));
            if (inDir >= 0) {
                double[] back = {-frame[inDir][0], -frame[inDir][1]};
                for (double shift : slides(frame, u, v, back, before.distance(u))) {
                    options.addAll(splits(frame, new Coordinate(u.x + shift * back[0], u.y + shift * back[1]), v));
                }
            }
            if (outDir >= 0) {
                for (double shift : slides(frame, u, v, frame[outDir], v.distance(after))) {
                    options.addAll(splits(frame, u, new Coordinate(v.x + shift * frame[outDir][0], v.y + shift * frame[outDir][1])));
                }
            }
            Coordinate[] best = null;
            double[] bestScore = null;
            for (Coordinate[] option : options) {
                List<Coordinate> trial = new ArrayList<>(coords);
                trial.set(i, option[0]);
                trial.set(i + 1, option[2]);
                if (option[1] != null) {
                    trial.add(i + 1, option[1]);
                }
                int pieces = option[1] == null ? 1 : 2;
                double weight = feasible(trial, i, pieces, ignored);
                if (Double.isNaN(weight)) {
                    continue;
                }
                double[] score = score(trial, i, pieces, weight, inDir >= 0, outDir >= 0);
                if (best == null || Arrays.compare(score, bestScore) < 0) {
                    best = option;
                    bestScore = score;
                }
            }
            if (best != null) {
                coords.set(i, best[0]);
                coords.set(i + 1, best[2]);
                if (best[1] != null) {
                    coords.add(i + 1, best[1]);
                }
            }
        }
    }

    /** Восемь направлений через 45° от самого длинного отрезка пути; направление 0 — его собственное. */
    private static double[][] frame(List<Coordinate> coords) {
        int longest = 0;
        for (int i = 1; i + 1 < coords.size(); i++) {
            if (coords.get(i).distance(coords.get(i + 1)) > coords.get(longest).distance(coords.get(longest + 1))) {
                longest = i;
            }
        }
        double base = Math.atan2(coords.get(longest + 1).y - coords.get(longest).y,
                coords.get(longest + 1).x - coords.get(longest).x);
        double[][] frame = new double[8][];
        for (int k = 0; k < 8; k++) {
            frame[k] = new double[] {Math.cos(base + Math.toRadians(45 * k)), Math.sin(base + Math.toRadians(45 * k))};
        }
        return frame;
    }

    /** Угол отрезка от направления 0 сетки в градусах, [0, 360). */
    private static double frameDegrees(double[][] frame, double wx, double wy) {
        double degrees = Math.toDegrees(Math.atan2(wy, wx) - Math.atan2(frame[0][1], frame[0][0]));
        return degrees - 360 * Math.floor(degrees / 360);
    }

    /** Индекс направления сетки, с которым отрезок совпадает с точностью ALIGN_TOL_DEG, или −1. */
    private static int frameIndex(double[][] frame, Coordinate from, Coordinate to) {
        double degrees = frameDegrees(frame, to.x - from.x, to.y - from.y);
        int k = (int) Math.round(degrees / 45);
        return Math.abs(degrees - 45 * k) <= ALIGN_TOL_DEG ? k % 8 : -1;
    }

    /** Варианты {начало, вставка или null, конец}: отрезок как есть, если выровнен, иначе две раскладки. */
    private static List<Coordinate[]> splits(double[][] frame, Coordinate u, Coordinate v) {
        if (frameIndex(frame, u, v) >= 0) {
            return List.<Coordinate[]>of(new Coordinate[] {u, null, v});
        }
        double wx = v.x - u.x;
        double wy = v.y - u.y;
        int k = (int) Math.floor(frameDegrees(frame, wx, wy) / 45) % 8;
        double[] first = frame[k];
        double[] second = frame[(k + 1) % 8];
        double sin45 = first[0] * second[1] - first[1] * second[0];
        double a = (wx * second[1] - wy * second[0]) / sin45;
        double b = (first[0] * wy - first[1] * wx) / sin45;
        if (a < MIN_PIECE_M || b < MIN_PIECE_M) {
            return List.of();
        }
        return List.of(new Coordinate[] {u, new Coordinate(u.x + a * first[0], u.y + a * first[1]), v},
                new Coordinate[] {u, new Coordinate(u.x + b * second[0], u.y + b * second[1]), v});
    }

    /**
     * Сдвиги вершины вдоль направления {@code along}, не короче метра оставляющие её отрезок длиной {@code room}:
     * фиксированные шаги и точные сдвиги, после которых отрезок u–v ложится на направление сетки.
     */
    private static List<Double> slides(double[][] frame, Coordinate u, Coordinate v, double[] along, double room) {
        List<Double> result = new ArrayList<>();
        double limit = room - MIN_PIECE_M;
        for (double shift : SLIDES_M) {
            if (shift <= limit) {
                result.add(shift);
            }
        }
        double wx = v.x - u.x;
        double wy = v.y - u.y;
        for (double[] direction : frame) {
            double denominator = along[0] * direction[1] - along[1] * direction[0];
            if (denominator == 0) {
                continue;
            }
            double shift = -(wx * direction[1] - wy * direction[0]) / denominator;
            if (shift > 0 && shift <= limit) {
                result.add(shift);
            }
        }
        return result;
    }

    /**
     * Вес новых отрезков trial[i..i+pieces] или NaN, если они недопустимы, короче метра, режут остальной путь,
     * идут по трубе врезки или пересекают её: путь к врезке подходит к трубе один раз, в самой врезке.
     */
    private double feasible(List<Coordinate> trial, int i, int pieces, Set<String> ignored) {
        int last = trial.size() - 2;
        double weight = 0;
        for (int p = i; p < i + pieces; p++) {
            Coordinate a = trial.get(p);
            Coordinate b = trial.get(p + 1);
            double pieceWeight = obstacles.edgeWeight(a, b, ignored);
            if (a.distance(b) < MIN_PIECE_M || Double.isNaN(pieceWeight)
                    || obstacles.alongIgnored(p == last ? b : a, p == last ? a : b, ignored)) {
                return Double.NaN;
            }
            LineSegment piece = new LineSegment(a, b);
            for (int j = 0; j <= last; j++) {
                if (Math.abs(j - p) > 1 && piece.intersection(new LineSegment(trial.get(j), trial.get(j + 1))) != null) {
                    return Double.NaN;
                }
            }
            weight += pieceWeight;
        }
        return weight;
    }

    /**
     * Оценка варианта: нестандартные изломы, повороты, вес пути (длина с надбавкой за специальные проходы у новых
     * отрезков). Изломы считаются в вершинах новых отрезков против выровненных соседей; невыровненный сосед
     * раскладывается позже и здесь не считается.
     */
    private double[] score(List<Coordinate> trial, int i, int pieces, double weight, boolean inAligned, boolean outAligned) {
        int nonstandard = 0;
        int turns = 0;
        for (int k = i; k <= i + pieces; k++) {
            if (k == 0 || k + 1 >= trial.size() || k == i && !inAligned || k == i + pieces && !outAligned) {
                continue;
            }
            double deflection = 180 - Math.toDegrees(Angle.angleBetween(trial.get(k - 1), trial.get(k), trial.get(k + 1)));
            if (deflection >= MIN_TURN_DEG) {
                turns++;
                if (rules.kTurn(deflection) > 1) {
                    nonstandard++;
                }
            }
        }
        double total = weight;
        for (int k = 0; k + 1 < trial.size(); k++) {
            if (k < i || k >= i + pieces) {
                total += trial.get(k).distance(trial.get(k + 1));
            }
        }
        return new double[] {nonstandard, turns, total};
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
