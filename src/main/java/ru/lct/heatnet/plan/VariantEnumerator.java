package ru.lct.heatnet.plan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.graph.ObstacleIndex;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.RouteCache;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.calc.Scorer;
import ru.lct.heatnet.io.GeoJsonStreamReader;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

/**
 * Варианты подключения (D-11). Стратегии: каждый ОКС своей лучшей врезкой; группы близких ОКС общими деревьями;
 * те же разбиения с другой врезкой; группы из трёх и больше ОКС, разбитые k-means на две. Из собранных вариантов
 * выбираются до трёх лучших по score, попарно различающихся набором врезок или разбиением ОКС по деревьям.
 */
public final class VariantEnumerator {
    private static final Logger log = LoggerFactory.getLogger(VariantEnumerator.class);
    /** ОКС ближе этого по точкам подключения считаются близкими и пробуются общим деревом. */
    private static final double GROUP_DISTANCE_M = 300;
    /** Запас области графа вокруг ОКС и кандидатов врезки (D-6). */
    private static final double AREA_MARGIN_M = 150;
    private static final double WIDE_AREA_MARGIN_M = 600;
    /** Врезка дальше этого от всех врезок другого варианта делает варианты разными (правило variants). */
    private static final double OTHER_TIE_M = 20;
    /** R-11: варианты одинаковы, если больше этой доли длины меньшего лежит в полосе SAME_ROUTE_M от другого. */
    /**
     * 0,8 отсекало варианты с другой врезкой у одного из блоков (S 13,59 против 14,41 у следующего принятого),
     * а это по разделу 10 CONSTRAINTS.md другой вариант; при 0,9 остаётся только смещение той же трассы.
     */
    private static final double SAME_ROUTE_SHARE = 0.9;
    private static final double SAME_ROUTE_M = 1.0;
    /** Дерево ОКС идёт в обход, если оно длиннее прямой до ближайшего кандидата врезки больше чем во столько раз. */
    private static final double DETOUR_RATIO = 1.1;
    private static final double TREES_APART_M = 0.5;
    private static final double SHARED_ROOT_CLIP_M = 0.15;
    private static final double SHARED_ROOT_APART_M = 0.01;
    private static final int MAX_VARIANTS = 3;
    /** Деревья кандидатов врезки считаются параллельно (heatnet.search.parallel); false — в одну нить, тот же выход. */
    private static final boolean PARALLEL = Boolean.parseBoolean(System.getProperty("heatnet.search.parallel", "true"));
    /**
     * В районе города граф строится в коридоре (heatnet.city.corridor) — объединении полос вдоль прямых от точек
     * к кандидатам врезки шириной CORRIDOR_SHARE их длины, но не уже CITY_MARGIN_M с каждой стороны: у дальних
     * точек прямоугольник накрывал квадратные километры зданий.
     */
    private static final boolean CORRIDOR = Boolean.parseBoolean(System.getProperty("heatnet.city.corridor", "true"));
    private static final double CORRIDOR_SHARE = Double.parseDouble(System.getProperty("heatnet.city.corridor.share", "0.25"));
    /** У скольких самых крупных блоков лучшего черновика пробуются другие врезки и разбиение ради вариантов 2 и 3. */
    private static final int EXTRA_VARIANT_BLOCKS = Integer.getInteger("heatnet.search.variantblocks", 8);
    /**
     * Запас вокруг областей групп при отборе препятствий на чтении: отступы ObstacleSet — десятки метров, а причины
     * неподключения ищут кольцо зон в 2 км от точки подключения (VariantCriteria).
     */
    private static final double EXTENT_MARGIN_M = 2500;
    private static final int KMEANS_ITERATIONS = 20;
    /** Группы больше этого не режутся k-means (O(n²)), а двумя половинами по сетке в UTM. */
    private static final int KMEANS_MAX = Integer.getInteger("heatnet.scale.kmeans.max", 500);
    /** При большем числе ОКС partition «каждый отдельно» не строится — только groups и split. */
    private static final int SINGLES_MAX = Integer.getInteger("heatnet.scale.singles.max", 500);
    /**
     * Локальный поиск по разбиениям ОКС: сколько сборок черновика он может потратить (свойство heatnet.search.budget).
     * Предела по стенным часам нет: результат не зависит от скорости и загрузки машины.
     */
    private static final int SEARCH_BUDGET = Integer.getInteger("heatnet.search.budget", 300);
    /**
     * Остановка после стольких сборок подряд без улучшения лучшего score (heatnet.search.stall). 0 — только budget.
     */
    private static final int SEARCH_STALL = Integer.getInteger("heatnet.search.stall", 50);
    /** Деревья подмножества строятся не на всех кандидатах врезки, а на лучших по грубой оценке стоимости. */
    private static final int CANDIDATE_LIMIT = 6;
    /** Запас к прямой до ближайшей врезки при выборе диаметра графа по предельной длине: трасса длиннее прямой. */
    private static final double LENGTH_DN_MARGIN = 1.2;
    /** Слияния и переносы пробуются только между блоками, ближайшими друг к другу по точкам подключения. */
    private static final int NEAREST_BLOCKS = 2;
    private static final double IMPROVE_EPS = 1e-6;
    /** Из локального оптимума спуск продолжается с наименее плохого соседа, если он хуже не больше чем на столько S. */
    private static final double WALK_THRESHOLD = 0.5;
    /**
     * Больше стольких ОКС расчёт идёт по-городски (свойство heatnet.city.min): точки у существующей сети подключаются
     * прямыми участками ({@link DirectTies}), остальные режутся на районы до CITY_BLOCK ОКС, районы считаются прежним
     * перебором параллельно, деревья собираются в общий вариант. Перебор на всех ОКС сразу растёт быстрее n².
     */
    private static final int CITY_MIN = Integer.getInteger("heatnet.city.min", 500);
    private static final int CITY_BLOCK = Integer.getInteger("heatnet.city.block", 16);
    private static final int CITY_THREADS = Integer.getInteger("heatnet.city.threads", Runtime.getRuntime().availableProcessors());
    /**
     * В районе кандидаты врезки дальше ближайшего больше чем на столько отбрасываются. Три ближайшие камеры и врезки
     * выше реконструкции в городе лежат за километры, а граф области растёт с её площадью: у одного ОКС выходило
     * 5–14 тысяч узлов и минуты на граф. Остальные упрощения района — тоже ради числа и размера графов, замеры
     * в docs/testing/city-scale-run.md: запас области CITY_MARGIN_M вместо AREA_MARGIN_M, один диаметр графа на
     * район вместо диаметра по расходу каждого подмножества, без последней попытки на широкой области, поиск
     * на CITY_BUDGET черновиков.
     */
    private static final double CITY_REACH_M = 150;
    private static final double CITY_MARGIN_M = 60;
    private static final int CITY_BUDGET = Integer.getInteger("heatnet.city.budget", 60);
    /**
     * Сколько секунд от начала городского расчёта отводится районам (heatnet.city.deadline): районы идут от ближних
     * к сети к дальним, после срока оставшиеся не считаются, их точки остаются без сети. На синтетическом городе
     * десятки тысяч точек лежат в километрах от сети, и графы их районов считаются часами; все районы, которые
     * дают подключения, укладываются в 600 с (при 1800 с подключено столько же, при 300 с — на 5 точек меньше), но
     * граница шумит от загрузки машины: два прогона с 600 с дали 13 178 и 13 177 подключённых; 900 с — запас.
     */
    private static final long CITY_DEADLINE_S = Long.getLong("heatnet.city.deadline", 900);

    private static final long CITY_CACHE_MB = 64;

    private final InputData input;
    private final Rules rules;
    private final TieInFinder finder;
    private final ObstacleIndex obstacleIndex;
    private final RouteCache routeCache;
    /** Расчёт одного района города: кандидаты врезки только в радиусе CITY_REACH_M. */
    private final boolean district;
    private final SpecialObjects specials;
    /** Полигон ОКС, в котором лежит точка подключения, по id точки. */
    private final Map<String, ExistingOks> buildingByConnection;
    private final TreeBuilder builder;
    private NetworkAssembler assembler;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, FutureOks> oksById = new LinkedHashMap<>();
    private final Map<String, ConnectionPoint> connectionByOks = new LinkedHashMap<>();
    private final Map<String, Region> regionByConnection = new HashMap<>();

    private static final class Option {
        final Tree tree;
        final double score;

        Option(Tree tree, double score) {
            this.tree = tree;
            this.score = score;
        }
    }

    /** Группа близких ОКС: общий граф на её область и кэш деревьев по подмножествам. */
    private final class Region {
        final int dn;
        final Envelope area;
        final Envelope wideArea;
        final Map<String, List<Option>> options = new HashMap<>();
        final Map<String, Router> routers = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, ObstacleSet> obstacleSets = new java.util.concurrent.ConcurrentHashMap<>();
        /** Коридор графа района или null — весь прямоугольник. */
        final Geometry corridor;

        Region(List<ConnectionPoint> connections) {
            Diameter byFlow = rules.diameterFor(flow(connections));
            Diameter above = rules.nextDiameter(byFlow.getDn());
            // диаметр ствола с запасом на ступень выше и по предельной длине пути до ближайшей врезки: ДУ ветки
            // растёт с её длиной (п. 2.3), а отступы графа должны быть верны для фактического ДУ
            int graphDn = above != null ? above.getDn() : byFlow.getDn();
            Envelope envelope = new Envelope();
            List<Point> points = points(connections);
            for (Point point : points) {
                envelope.expandToInclude(point.getCoordinate());
            }
            List<TieCandidate> candidates = candidates(points, flow(connections), graphDn);
            double farthest = 0;
            for (ConnectionPoint connection : connections) {
                double nearest = candidates.stream()
                        .mapToDouble(candidate -> candidate.getPoint().distance(connection.getGeometry())).min().orElse(0);
                farthest = Math.max(farthest, nearest);
            }
            Diameter byLength = rules.diameterForLength(farthest * LENGTH_DN_MARGIN);
            this.dn = Math.max(graphDn, byLength == null ? graphDn : byLength.getDn());
            for (TieCandidate candidate : candidates) {
                envelope.expandToInclude(candidate.getPoint().getCoordinate());
            }
            Geometry lanes = null;
            if (district && CORRIDOR && !candidates.isEmpty()) {
                List<Geometry> strips = new ArrayList<>();
                for (Point point : points) {
                    for (TieCandidate candidate : candidates) {
                        LineString straight = factory.createLineString(new Coordinate[] {point.getCoordinate(), candidate.getPoint().getCoordinate()});
                        strips.add(straight.buffer(Math.max(CITY_MARGIN_M, CORRIDOR_SHARE * straight.getLength())));
                    }
                }
                lanes = factory.createGeometryCollection(strips.toArray(new Geometry[0])).union();
            }
            this.corridor = lanes;
            this.wideArea = new Envelope(envelope);
            envelope.expandBy(district ? CITY_MARGIN_M : AREA_MARGIN_M);
            wideArea.expandBy(WIDE_AREA_MARGIN_M);
            this.area = envelope;
        }

        Router router(int routerDn, Envelope routerArea) {
            return routers.computeIfAbsent(routerDn + "@" + routerArea,
                    key -> new Router(obstacleIndex, rules, routerArea, routerDn, routeCache, corridor));
        }

        /** Зоны для проверки отступов при фактическом Ду: буферы зон дороже самой проверки, поэтому по одному на Ду и область. */
        ObstacleSet obstacles(int obstaclesDn, Envelope obstaclesArea) {
            Router router = routers.get(obstaclesDn + "@" + obstaclesArea);
            if (router != null) {
                return router.obstacles();
            }
            return obstacleSets.computeIfAbsent(obstaclesDn + "@" + obstaclesArea,
                    key -> new ObstacleSet(obstacleIndex, rules, obstaclesArea, obstaclesDn));
        }
    }

    /** Соседнее разбиение; alternative — блок, который берёт дерево с другой врезкой, а не с лучшей. */
    private static final class Move {
        final List<List<ConnectionPoint>> blocks;
        final List<ConnectionPoint> alternative;
        /** Какая по счёту отличная врезка берётся у блока alternative: 1 — первая отличная от лучшей. */
        final int rank;

        Move(List<List<ConnectionPoint>> blocks, List<ConnectionPoint> alternative) {
            this(blocks, alternative, 1);
        }

        Move(List<List<ConnectionPoint>> blocks, List<ConnectionPoint> alternative, int rank) {
            this.blocks = blocks;
            this.alternative = alternative;
            this.rank = rank;
        }

        Draft realize(VariantEnumerator enumerator) {
            return enumerator.draft(blocks, subset -> subset == alternative ? rank : 0);
        }

        String key() {
            List<String> parts = new ArrayList<>();
            for (List<ConnectionPoint> block : blocks) {
                parts.add(block.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(",")));
            }
            parts.sort(Comparator.naturalOrder());
            return String.join("|", parts) + (alternative == null ? "" : "#" + alternative.get(0).getId() + "/" + rank);
        }
    }

    /** Собранный вариант до присвоения ранга. */
    private static final class Draft {
        final List<Tree> trees;
        final List<FutureOks> unconnected;
        final Variant variant;

        Draft(List<Tree> trees, List<FutureOks> unconnected, Variant variant) {
            this.trees = trees;
            this.unconnected = unconnected;
            this.variant = variant;
        }

        double score() {
            return variant.getSummary().getScore();
        }
    }

    public VariantEnumerator(InputData input, Rules rules) {
        this(input, rules, new TieInFinder(input, rules), new ObstacleIndex(input, rules), new SpecialObjects(input, rules),
                buildings(input), RouteCache.DEFAULT_MB, false);
    }

    /** Полигон ОКС каждой точки подключения: геометрия перспективного ОКС, если это полигон здания из входа. */
    static Map<String, ExistingOks> buildings(InputData input) {
        Map<Geometry, ExistingOks> byGeometry = new IdentityHashMap<>();
        for (ExistingOks oks : input.getExistingOks()) {
            byGeometry.put(oks.getGeometry(), oks);
        }
        Map<String, ExistingOks> result = new HashMap<>();
        Map<String, FutureOks> oks = new HashMap<>();
        input.getFutureOks().forEach(o -> oks.put(o.getId(), o));
        for (ConnectionPoint connection : input.getConnectionPoints()) {
            FutureOks future = oks.get(connection.getOksId());
            ExistingOks building = future == null ? null : byGeometry.get(future.getGeometry());
            if (building != null) {
                result.put(connection.getId(), building);
            }
        }
        return result;
    }

    private VariantEnumerator(InputData input, Rules rules, TieInFinder finder, ObstacleIndex obstacleIndex,
            SpecialObjects specials, Map<String, ExistingOks> buildingByConnection, long cacheMb, boolean district) {
        this.input = input;
        this.rules = rules;
        this.finder = finder;
        this.obstacleIndex = obstacleIndex;
        Map<String, LineString> networkById = new HashMap<>();
        for (NetworkSegment segment : input.getSegments()) {
            networkById.put(segment.getId(), segment.getGeometry());
        }
        this.specials = specials;
        this.buildingByConnection = buildingByConnection;
        this.routeCache = new RouteCache(cacheMb);
        this.district = district;
        for (FutureOks oks : input.getFutureOks()) {
            oksById.put(oks.getId(), oks);
        }
        for (ConnectionPoint connection : input.getConnectionPoints()) {
            if (oksById.containsKey(connection.getOksId())) {
                connectionByOks.putIfAbsent(connection.getOksId(), connection);
            }
        }
        this.builder = new TreeBuilder(finder.nodeLimit(), networkById, specials, buildingByConnection);
    }

    /**
     * Вход для расчёта: чтение без дальних препятствий, см. {@link #obstacleExtent}. Свойство
     * {@code heatnet.read.all=true} читает все препятствия, как раньше.
     */
    public static InputData read(Path path, Rules rules) {
        long started = System.nanoTime();
        InputData input = Boolean.getBoolean("heatnet.read.all") ? GeoJsonStreamReader.read(path)
                : GeoJsonStreamReader.read(path, partial -> obstacleExtent(partial, rules));
        log.info("read: oks={} restrictions={} existing={} elapsed={}s", input.getFutureOks().size(),
                input.getRestrictions().size(), input.getExistingOks().size(), (System.nanoTime() - started) / 1_000_000_000L);
        return input;
    }

    /**
     * Прямоугольник, вне которого здания и ограничения на расчёт не влияют: bbox всех точек подключения
     * перспективных ОКС с запасом {@link #EXTENT_MARGIN_M} (координаты EPSG:32637, метры). null — нет CP или
     * вход, на котором расчёт упадёт: читаются все препятствия.
     */
    public static Envelope obstacleExtent(InputData input, Rules rules) {
        try {
            Set<String> futureOks = new HashSet<>();
            for (FutureOks oks : input.getFutureOks()) {
                futureOks.add(oks.getId());
            }
            // точка дальше предельной длины наибольшего Ду от сети недостижима, её здание на расчёт не влияет;
            // рамка сети с этим запасом отсекает такие точки без расстояний до каждой
            Envelope network = new Envelope();
            input.getSegments().forEach(segment -> network.expandToInclude(segment.getGeometry().getEnvelopeInternal()));
            input.getChambers().forEach(chamber -> network.expandToInclude(chamber.getGeometry().getCoordinate()));
            if (!network.isNull()) {
                network.expandBy(rules.diameters().get(rules.diameters().size() - 1).getMaxLengthM());
            }
            Envelope extent = new Envelope();
            for (ConnectionPoint connection : input.getConnectionPoints()) {
                Coordinate at = connection.getGeometry().getCoordinate();
                if (futureOks.contains(connection.getOksId()) && (network.isNull() || network.contains(at))) {
                    extent.expandToInclude(at);
                }
            }
            if (extent.isNull()) {
                return null;
            }
            extent.expandBy(EXTENT_MARGIN_M);
            return extent;
        } catch (RuntimeException e) {
            // вход, на котором расчёт упадёт, читается целиком: ошибка будет та же, что без отбора
            return null;
        }
    }

    public Result run() {
        if (connectionByOks.size() > CITY_MIN) {
            return city();
        }
        List<Draft> picked = picked();
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Draft draft = picked.get(i);
            variants.add(assembler.assemble(String.valueOf(i + 1), i + 1, draft.trees, draft.unconnected));
        }
        return new Result(variants, input.getNumericIds());
    }

    /**
     * Город: точки у существующей сети подключаются прямыми участками ({@link DirectTies}), остальные — районами по
     * CITY_BLOCK ОКС параллельно (вариант k — k-й черновик каждого района). Прямые подключения собираются по узлам
     * врезки параллельно с общими счётчиками ID, деревья районов — одной сборкой; дерево района, задевающее уже
     * принятое дерево соседа, не берётся. Вариант 2 — вторые по стоимости прямые подключения, если они есть.
     */
    private Result city() {
        long started = System.nanoTime();
        List<ConnectionPoint> all = new ArrayList<>(connectionByOks.values());
        Map<String, Double> flowByOks = new HashMap<>();
        oksById.forEach((id, oks) -> flowByOks.put(id, oks.getFlowTph()));
        DirectTies direct = new DirectTies(input, rules, obstacleIndex, finder, buildingByConnection);
        List<ConnectionPoint> rest = new ArrayList<>();
        List<List<Tree>> directTrees = direct.connect(all, flowByOks, 2, rest);
        log.info("city: direct trees={} rest={} elapsed={}s", directTrees.get(0).size(), rest.size(),
                (System.nanoTime() - started) / 1_000_000_000L);
        if (direct.same(directTrees.get(0), directTrees.get(1))) {
            directTrees.remove(1);
        }
        // путь длиннее предельной длины наибольшего ДУ недопустим при любом диаметре: точка дальше этого от сети
        // остаётся без маршрута, и граф для неё не строится
        double reach = rules.diameters().get(rules.diameters().size() - 1).getMaxLengthM();
        List<ConnectionPoint> near = new ArrayList<>();
        // расстояния независимы, индексы сети после build только читаются; в карте только ближние точки
        Map<String, Double> toNetwork = new java.util.concurrent.ConcurrentHashMap<>();
        rest.parallelStream().forEach(connection -> {
            double distance = direct.networkDistance(connection.getGeometry());
            if (distance <= reach) {
                toNetwork.put(connection.getId(), distance);
            }
        });
        for (ConnectionPoint connection : rest) {
            if (toNetwork.containsKey(connection.getId())) {
                near.add(connection);
            }
        }
        List<List<ConnectionPoint>> districts = districts(near);
        // ближние к сети районы первыми: их графы меньше, и до срока успевает больше точек
        districts.sort(Comparator.comparingDouble(district -> district.stream()
                .mapToDouble(connection -> toNetwork.get(connection.getId())).min().orElse(0)));
        log.info("city: oks={} direct={} beyond {} m: {} districts={} elapsed={}s", all.size(), all.size() - rest.size(),
                reach, rest.size() - near.size(), districts.size(), (System.nanoTime() - started) / 1_000_000_000L);
        List<List<Draft>> results = districts.isEmpty() ? List.of()
                : solve(districts, started + CITY_DEADLINE_S * 1_000_000_000L);
        assembler = new NetworkAssembler(input, rules, specials);
        int most = Math.max(directTrees.size(), results.stream().mapToInt(List::size).max().orElse(0));
        List<Variant> variants = new ArrayList<>();
        for (int k = 0; k < Math.min(MAX_VARIANTS, Math.max(most, 1)); k++) {
            List<Tree> trees = new ArrayList<>(directTrees.get(Math.min(k, directTrees.size() - 1)));
            List<Tree> districtTrees = joined(results, k);
            trees.addAll(districtTrees);
            Variant variant = assembleParts(String.valueOf(k + 1), k + 1, trees, missing(trees));
            variants.add(variant);
            log.info("city: candidate {} trees={} score={} unconnected={} elapsed={}s", k + 1, trees.size(),
                    variant.getSummary().getScore(), variant.getSummary().getUnconnectedOksIds().size(),
                    (System.nanoTime() - started) / 1_000_000_000L);
        }
        List<Integer> order = Scorer.rank(variants.stream().map(Variant::getSummary).collect(Collectors.toList()));
        List<Variant> ranked = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Variant variant = variants.get(order.get(i));
            String id = String.valueOf(i + 1);
            if (variant.getId().equals(id)) {
                ranked.add(variant);
            } else {
                ranked.add(renumbered(variant, id, i + 1));
            }
        }
        return new Result(ranked, input.getNumericIds());
    }

    /** Вариант по частям: каждый узел врезки — своя сборка, параллельно, с общими счётчиками ID. */
    private Variant assembleParts(String variantId, int rank, List<Tree> trees, List<FutureOks> unconnected) {
        Map<String, List<Tree>> units = new LinkedHashMap<>();
        for (Tree tree : trees) {
            units.computeIfAbsent(tree.root.key, key -> new ArrayList<>()).add(tree);
        }
        NetworkAssembler.Counters counters = new NetworkAssembler.Counters();
        List<Variant> parts = new ArrayList<>(units.values()).parallelStream().map(unit -> {
            try {
                return assembler.assemble(variantId, rank, unit, List.of(), counters);
            } catch (IllegalStateException | IllegalArgumentException e) {
                // узел врезки, чьи деревья по отдельности собирались, а вместе нет: его точки остаются без сети
                log.debug("city: unit {} not assembled: {}", unit.get(0).root.key, e.getMessage());
                return null;
            }
        }).collect(Collectors.toList());
        List<NewSegment> segments = new ArrayList<>();
        List<NewChamber> chambers = new ArrayList<>();
        List<TechnicalNode> nodes = new ArrayList<>();
        int tieIns = 0;
        Set<String> connected = new HashSet<>();
        List<List<Tree>> unitList = new ArrayList<>(units.values());
        for (int i = 0; i < parts.size(); i++) {
            Variant part = parts.get(i);
            if (part == null) {
                continue;
            }
            segments.addAll(part.getSegments());
            chambers.addAll(part.getChambers());
            nodes.addAll(part.getNodes());
            tieIns += part.getSummary().getExistingChamberTieInCount();
            unitList.get(i).forEach(tree -> tree.connected().forEach(c -> connected.add(c.getOksId())));
        }
        List<FutureOks> left = new ArrayList<>(unconnected);
        for (Tree tree : trees) {
            for (ConnectionPoint connection : tree.connected()) {
                if (!connected.contains(connection.getOksId())) {
                    left.add(oksById.get(connection.getOksId()));
                }
            }
        }
        VariantSummary summary = assembler.summary(variantId, rank, segments, chambers, tieIns, left);
        return new Variant(variantId, segments, chambers, nodes, summary);
    }

    /** Тот же вариант под другим номером: ID объектов и ссылки переписываются с новым префиксом. */
    private static Variant renumbered(Variant variant, String id, int rank) {
        String from = "v" + variant.getId() + "_";
        String to = "v" + id + "_";
        java.util.function.UnaryOperator<String> rename = value -> value.startsWith(from) ? to + value.substring(from.length()) : value;
        List<NewSegment> segments = new ArrayList<>();
        for (NewSegment s : variant.getSegments()) {
            segments.add(new NewSegment(rename.apply(s.getId()), id, s.getGeometry(), rename.apply(s.getStartNodeId()),
                    rename.apply(s.getEndNodeId()), s.getFlowTph(), s.getDiameter(), s.getLength(), s.getLayingMethod(),
                    s.getDepthStart(), s.getDepthEnd(), s.getCost()));
        }
        List<NewChamber> chambers = new ArrayList<>();
        for (NewChamber c : variant.getChambers()) {
            chambers.add(new NewChamber(rename.apply(c.getId()), id, c.getGeometry(), c.getDiameter(), c.getCost()));
        }
        List<TechnicalNode> nodes = new ArrayList<>();
        for (TechnicalNode n : variant.getNodes()) {
            nodes.add(new TechnicalNode(rename.apply(n.getId()), id, n.getGeometry()));
        }
        VariantSummary s = variant.getSummary();
        VariantSummary summary = new VariantSummary(rename.apply(s.getId()).replace("summary_" + variant.getId(), "summary_" + id),
                id, rank, s.getConstructionCost(), s.getChamberConstructionCost(), s.getExistingChamberTieInCount(),
                s.getExistingChamberTieInCost(), s.getUnconnectedPenalty(), s.getCalculatedCost(), s.getNewNetworkLength(),
                s.getScore(), s.getUnconnectedOksIds());
        return new Variant(id, segments, chambers, nodes, summary);
    }

    /** ОКС входа, которых нет в деревьях. */
    private List<FutureOks> missing(List<Tree> trees) {
        Set<String> connected = new HashSet<>();
        trees.forEach(tree -> tree.connected().forEach(connection -> connected.add(connection.getOksId())));
        List<FutureOks> result = new ArrayList<>();
        for (FutureOks oks : input.getFutureOks()) {
            if (!connected.contains(oks.getId())) {
                result.add(oks);
            }
        }
        return result;
    }

    /** Районы: группы близких ОКС, крупные группы режутся пополам, пока не станут не больше CITY_BLOCK. */
    static List<List<ConnectionPoint>> districts(List<ConnectionPoint> connections) {
        List<List<ConnectionPoint>> result = new ArrayList<>();
        List<List<ConnectionPoint>> queue = new ArrayList<>(groupsFast(connections));
        for (int next = 0; next < queue.size(); next++) {
            List<ConnectionPoint> group = queue.get(next);
            List<List<ConnectionPoint>> parts = group.size() > CITY_BLOCK ? splitGroup(group) : List.of(group);
            if (parts.size() < 2) {
                result.add(group);
            } else {
                queue.addAll(parts);
            }
        }
        return result;
    }

    /**
     * Черновики районов по возрастанию score в порядке списка; район, расчёт которого упал или не начался до
     * {@code deadlineNanos}, остаётся без черновиков.
     */
    private List<List<Draft>> solve(List<List<ConnectionPoint>> districts, long deadlineNanos) {
        ExecutorService pool = Executors.newFixedThreadPool(CITY_THREADS);
        long started = System.nanoTime();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        try {
            List<Future<List<Draft>>> futures = new ArrayList<>(Collections.nCopies(districts.size(), null));
            for (int i = 0; i < districts.size(); i++) {
                List<ConnectionPoint> connections = districts.get(i);
                int index = i;
                futures.set(i, pool.submit(() -> {
                    if (System.nanoTime() > deadlineNanos) {
                        skipped.incrementAndGet();
                        return List.<Draft>of();
                    }
                    long districtStarted = System.nanoTime();
                    VariantEnumerator district = district(connections);
                    List<Draft> drafts = district.picked();
                    int trees = drafts.isEmpty() ? 0 : drafts.get(0).trees.size();
                    log.info("city: district {} oks={} trees={} {} elapsed={}s", index, connections.size(), trees,
                            district.graphs(), (System.nanoTime() - districtStarted) / 1_000_000_000L);
                    int count = done.incrementAndGet();
                    if (count % 10 == 0 || count == districts.size()) {
                        log.info("city: districts {}/{} elapsed={}s", count, districts.size(),
                                (System.nanoTime() - started) / 1_000_000_000L);
                    }
                    return drafts;
                }));
            }
            List<List<Draft>> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    log.warn("city: район {} не посчитан: {}", i, e.getCause().toString());
                    results.add(List.of());
                }
            }
            if (skipped.get() > 0) {
                log.warn("city: {} районов из {} не начаты до срока {} с, их точки остаются без сети", skipped.get(),
                        districts.size(), CITY_DEADLINE_S);
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Расчёт районов прерван", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Графы областей расчёта: сколько построено, самый большой по узлам и его область. */
    private String graphs() {
        int routers = 0;
        int nodes = 0;
        Envelope largest = new Envelope();
        for (Region region : new HashSet<>(regionByConnection.values())) {
            for (Router router : region.routers.values()) {
                routers++;
                if (router.obstacles().nodes().size() > nodes) {
                    nodes = router.obstacles().nodes().size();
                    largest = region.area;
                }
            }
        }
        return String.format(Locale.ROOT, "routers=%d nodes=%d area=%.0fx%.0f", routers, nodes, largest.getWidth(),
                largest.getHeight());
    }

    /** Расчёт района: те же сеть, препятствия и индексы, свои ОКС и кэш маршрутов. */
    private VariantEnumerator district(List<ConnectionPoint> connections) {
        List<FutureOks> oks = new ArrayList<>();
        connections.forEach(connection -> oks.add(oksById.get(connection.getOksId())));
        // препятствия району дают общие индексы; в самом входе района они нужны были бы только сборщику для ID входа,
        // а их сотни тысяч, и сборка каждого черновика перебирала их все
        InputData part = new InputData(input.getSource(), input.getSegments(), input.getChambers(), oks, connections,
                List.of(), List.of(), List.of(), List.of(), input.getNumericIds());
        return new VariantEnumerator(part, rules, finder, obstacleIndex, specials, buildingByConnection, CITY_CACHE_MB, true);
    }

    /** Деревья k-х черновиков районов, кроме задевающих уже принятые деревья соседних районов. */
    private List<Tree> joined(List<List<Draft>> results, int k) {
        List<Tree> accepted = new ArrayList<>();
        Quadtree index = new Quadtree();
        int dropped = 0;
        for (List<Draft> drafts : results) {
            if (drafts.isEmpty()) {
                continue;
            }
            for (Tree tree : drafts.get(Math.min(k, drafts.size() - 1)).trees) {
                Envelope envelope = geometry(tree).getEnvelopeInternal();
                Envelope around = new Envelope(envelope);
                around.expandBy(TREES_APART_M + 1);
                List<Tree> near = new ArrayList<>();
                for (Object item : index.query(around)) {
                    if (geometry((Tree) item).getEnvelopeInternal().intersects(around)) {
                        near.add((Tree) item);
                    }
                }
                if (compatible(tree, near)) {
                    accepted.add(tree);
                    index.insert(envelope, tree);
                } else {
                    dropped++;
                }
            }
        }
        if (dropped > 0) {
            log.info("city: variant {} dropped {} trees touching other districts", k + 1, dropped);
        }
        return accepted;
    }

    /** До трёх различных черновиков по возрастанию score; перед вызовом assembler не нужен, он создаётся здесь. */
    private List<Draft> picked() {
        List<List<ConnectionPoint>> groups = groups(new ArrayList<>(connectionByOks.values()));
        for (List<ConnectionPoint> group : groups) {
            Region region = new Region(group);
            for (ConnectionPoint connection : group) {
                regionByConnection.put(connection.getId(), region);
            }
        }
        assembler = new NetworkAssembler(input, rules, specials);
        List<List<ConnectionPoint>> singles = new ArrayList<>();
        for (ConnectionPoint connection : connectionByOks.values()) {
            singles.add(List.of(connection));
        }
        List<List<ConnectionPoint>> split = new ArrayList<>();
        boolean anyGroup = false;
        boolean anySplit = false;
        for (List<ConnectionPoint> group : groups) {
            anyGroup |= group.size() > 1;
            if (group.size() > 2) {
                anySplit = true;
                split.addAll(splitGroup(group));
            } else {
                split.add(group);
            }
        }

        List<List<List<ConnectionPoint>>> partitions = new ArrayList<>();
        if (connectionByOks.size() <= SINGLES_MAX) {
            partitions.add(singles);
        }
        if (anyGroup) {
            partitions.add(groups);
        }
        List<Draft> drafts = new ArrayList<>();
        for (List<List<ConnectionPoint>> partition : partitions) {
            drafts.add(draft(partition, subset -> 0));
            drafts.add(draft(partition, subset -> 1));
        }
        if (anySplit) {
            drafts.add(draft(split, subset -> 0));
        }
        drafts.removeIf(Objects::isNull);
        drafts.sort(Comparator.comparingDouble(Draft::score));
        // локальный поиск от лучшего черновика: слияния деревьев дают одну врезку и одну реконструкцию вместо нескольких
        if (!drafts.isEmpty()) {
            drafts.add(search(drafts.get(0), district ? CITY_BUDGET : SEARCH_BUDGET));
        }
        List<Draft> picked = pick(drafts);
        if (picked.size() < 2) {
            // другие врезки всех ОКС разом могут дать тот же набор врезок, например ОКС поменялись камерами местами:
            // пробуется другая врезка у одного подмножества при лучших у остальных
            for (List<List<ConnectionPoint>> partition : partitions) {
                if (partition.size() > 1) {
                    for (List<ConnectionPoint> one : partition) {
                        drafts.add(draft(partition, subset -> subset == one ? 1 : 0));
                    }
                }
            }
            picked = pick(drafts);
        }
        if (!picked.isEmpty()) {
            // содержательно другие сети от лучшего черновика для вариантов 2 и 3: другая врезка у каждого блока
            // (до трёх отличных) и разбиение больших блоков пополам двумя врезками
            List<List<ConnectionPoint>> blocks = blocksOf(picked.get(0));
            // крупные блоки первыми: их врезка меняет больше трассы; на сотнях блоков перебор всех утраивал время
            blocks.sort(Comparator.comparingInt((List<ConnectionPoint> block) -> -block.size()));
            for (int i = 0; i < Math.min(blocks.size(), EXTRA_VARIANT_BLOCKS); i++) {
                for (int rank = 1; rank <= 3; rank++) {
                    List<List<ConnectionPoint>> same = copy(blocks);
                    drafts.add(new Move(same, same.get(i), rank).realize(this));
                }
                if (blocks.get(i).size() >= 3) {
                    List<List<ConnectionPoint>> parts = splitGroup(blocks.get(i));
                    if (parts.size() == 2) {
                        List<List<ConnectionPoint>> halves = copy(blocks);
                        halves.remove(i);
                        halves.addAll(parts);
                        drafts.add(new Move(halves, null).realize(this));
                    }
                }
            }
            picked = pick(drafts);
        }
        return picked;
    }

    /** Блоки черновика: точки каждого дерева и по блоку на неподключённую точку. */
    private List<List<ConnectionPoint>> blocksOf(Draft draft) {
        List<List<ConnectionPoint>> blocks = new ArrayList<>();
        for (Tree tree : draft.trees) {
            blocks.add(new ArrayList<>(tree.connected()));
        }
        for (FutureOks oks : draft.unconnected) {
            ConnectionPoint connection = connectionByOks.get(oks.getId());
            if (connection != null) {
                blocks.add(new ArrayList<>(List.of(connection)));
            }
        }
        return blocks;
    }

    /**
     * Локальный поиск по разбиениям ОКС: спуск с первым улучшением по ходам {@link #moves}, а из локального оптимума —
     * шаг на наименее плохого ещё не посещённого соседа (не хуже текущего на WALK_THRESHOLD), и спуск снова. Когда
     * и такого соседа нет, приём walk: спуск перезапускается от лучшего найденного черновика по его наименее плохому
     * непосещённому соседу без порога, пока есть бюджет. Каждый шаг — новый черновик через {@link #draft}; budget —
     * их число, других пределов нет, поэтому результат детерминирован. Возвращает лучший найденный черновик.
     */
    private Draft search(Draft start, int budget) {
        List<List<ConnectionPoint>> blocks = new ArrayList<>();
        for (Tree tree : start.trees) {
            blocks.add(new ArrayList<>(tree.connected()));
        }
        for (FutureOks oks : start.unconnected) {
            ConnectionPoint connection = connectionByOks.get(oks.getId());
            if (connection != null) {
                blocks.add(new ArrayList<>(List.of(connection)));
            }
        }
        Draft best = start;
        List<List<ConnectionPoint>> bestBlocks = blocks;
        Draft current = start;
        Set<String> visited = new HashSet<>();
        visited.add(new Move(blocks, null).key());
        long started = System.nanoTime();
        int spent = 0;
        int sinceBestImprove = 0;
        while (spent < budget) {
            Move step = null;
            Draft stepDraft = null;
            for (Move move : moves(blocks)) {
                if (spent >= budget) {
                    break;
                }
                if (SEARCH_STALL > 0 && sinceBestImprove >= SEARCH_STALL) {
                    break;
                }
                String key = move.key();
                if (!visited.add(key)) {
                    continue;
                }
                spent++;
                sinceBestImprove++;
                Draft draft = move.realize(this);
                if (draft == null || draft.unconnected.size() > current.unconnected.size()) {
                    continue;
                }
                if (draft.score() < current.score() - IMPROVE_EPS) {
                    step = move;
                    stepDraft = draft;
                    break;
                }
                if (stepDraft == null || draft.score() < stepDraft.score()) {
                    step = move;
                    stepDraft = draft;
                }
            }
            if (step == null) {
                if (current == best) {
                    break;
                }
                if (SEARCH_STALL > 0 && sinceBestImprove >= SEARCH_STALL) {
                    break;
                }
                blocks = bestBlocks;
                current = best;
                continue;
            }
            if (stepDraft.score() > current.score() + WALK_THRESHOLD && current != best) {
                // walk: застряли — перезапуск от лучшего черновика; от него самого порог не действует
                blocks = bestBlocks;
                current = best;
                continue;
            }
            blocks = step.blocks;
            current = stepDraft;
            if (current.score() < best.score() - IMPROVE_EPS) {
                best = current;
                bestBlocks = blocks;
                sinceBestImprove = 0;
                log.info("search: score={} trees={} drafts={} elapsed={}s", best.score(), blocks.size(), spent,
                        (System.nanoTime() - started) / 1_000_000_000L);
            }
            if (SEARCH_STALL > 0 && sinceBestImprove >= SEARCH_STALL) {
                log.info("search: stall stop drafts={} sinceBestImprove={}", spent, sinceBestImprove);
                break;
            }
        }
        long[] tables = new long[2];
        int routers = 0;
        int nodes = 0;
        for (Region region : new HashSet<>(regionByConnection.values())) {
            for (Router router : region.routers.values()) {
                routers++;
                nodes = Math.max(nodes, router.obstacles().nodes().size());
                long[] stats = router.tableStats();
                tables[0] += stats[0];
                tables[1] += stats[1];
            }
        }
        log.info("search: done score={} drafts={} elapsed={}s dijkstra={} cached={} routers={} nodes={} {}", best.score(),
                spent, (System.nanoTime() - started) / 1_000_000_000L, tables[0], tables[1], routers, nodes,
                routeCache.stats());
        return best;
    }

    /** Соседние разбиения в порядке: слияния ближайших блоков, переносы ОКС, выделения, разбиения k-means, другая врезка. */
    private List<Move> moves(List<List<ConnectionPoint>> blocks) {
        List<Move> result = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            for (int j : nearestBlocks(blocks, blocks.get(i), i)) {
                if (j > i) {
                    List<List<ConnectionPoint>> fused = copy(blocks);
                    fused.get(i).addAll(fused.get(j));
                    fused.remove(j);
                    result.add(new Move(fused, null));
                }
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).size() < 2) {
                continue;
            }
            for (ConnectionPoint connection : blocks.get(i)) {
                for (int j : nearestBlocks(blocks, List.of(connection), i)) {
                    List<List<ConnectionPoint>> moved = copy(blocks);
                    moved.get(i).remove(connection);
                    moved.get(j).add(connection);
                    result.add(new Move(moved, null));
                }
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).size() < 2) {
                continue;
            }
            for (ConnectionPoint connection : blocks.get(i)) {
                List<List<ConnectionPoint>> alone = copy(blocks);
                alone.get(i).remove(connection);
                alone.add(new ArrayList<>(List.of(connection)));
                result.add(new Move(alone, null));
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).size() < 3) {
                continue;
            }
            List<List<ConnectionPoint>> parts = splitGroup(blocks.get(i));
            if (parts.size() == 2) {
                List<List<ConnectionPoint>> split = copy(blocks);
                split.remove(i);
                split.addAll(parts);
                result.add(new Move(split, null));
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            List<List<ConnectionPoint>> same = copy(blocks);
            result.add(new Move(same, same.get(i)));
        }
        return result;
    }

    /** Индексы NEAREST_BLOCKS блоков той же группы, ближайших к points по точкам подключения, кроме skip. */
    private List<Integer> nearestBlocks(List<List<ConnectionPoint>> blocks, List<ConnectionPoint> points, int skip) {
        Region region = regionByConnection.get(points.get(0).getId());
        List<double[]> distances = new ArrayList<>();
        for (int j = 0; j < blocks.size(); j++) {
            if (j == skip || blocks.get(j).isEmpty() || regionByConnection.get(blocks.get(j).get(0).getId()) != region) {
                continue;
            }
            double distance = Double.POSITIVE_INFINITY;
            for (ConnectionPoint p : points) {
                for (ConnectionPoint q : blocks.get(j)) {
                    distance = Math.min(distance, p.getGeometry().distance(q.getGeometry()));
                }
            }
            distances.add(new double[] {distance, j});
        }
        distances.sort(Comparator.comparingDouble(d -> d[0]));
        List<Integer> nearest = new ArrayList<>();
        for (int k = 0; k < Math.min(NEAREST_BLOCKS, distances.size()); k++) {
            nearest.add((int) distances.get(k)[1]);
        }
        return nearest;
    }

    private static List<List<ConnectionPoint>> copy(List<List<ConnectionPoint>> blocks) {
        List<List<ConnectionPoint>> result = new ArrayList<>();
        for (List<ConnectionPoint> block : blocks) {
            result.add(new ArrayList<>(block));
        }
        return result;
    }

    /**
     * До трёх лучших по score черновиков, попарно различных по правилу variants и по трассе (R-11). Черновик, где
     * без сети осталось больше точек, чем в лучшем, не берётся: намеренное неподключение запрещено (п. 2.5).
     */
    private List<Draft> pick(List<Draft> drafts) {
        drafts.removeIf(Objects::isNull);
        int fewest = drafts.stream().mapToInt(draft -> draft.unconnected.size()).min().orElse(0);
        drafts.removeIf(draft -> draft.unconnected.size() > fewest);
        drafts.sort(Comparator.comparingDouble(Draft::score));
        List<Draft> picked = new ArrayList<>();
        for (Draft draft : drafts) {
            if (picked.size() < MAX_VARIANTS && picked.stream().allMatch(p -> differ(p, draft) && !sameRoute(p, draft))) {
                picked.add(draft);
            }
        }
        if (picked.size() < 2) {
            // R-11 оставил меньше двух вариантов: добираем по правилу различия врезок и разбиения
            for (Draft draft : drafts) {
                if (picked.size() < MAX_VARIANTS && !picked.contains(draft) && picked.stream().allMatch(p -> differ(p, draft))) {
                    picked.add(draft);
                }
            }
            picked.sort(Comparator.comparingDouble(Draft::score));
        }
        return picked;
    }

    /**
     * Вариант разбиения: подмножества по убыванию размера берут лучшее совместимое дерево, а те, что отобраны
     * {@code alternative}, — первое с другой врезкой. ОКС, которые не вошли в общее дерево, подключаются отдельными
     * врезками; без маршрута — в штраф.
     */
    private Draft draft(List<List<ConnectionPoint>> partition, java.util.function.ToIntFunction<List<ConnectionPoint>> alternative) {
        List<List<ConnectionPoint>> queue = new ArrayList<>(partition);
        queue.sort(Comparator.comparingInt((List<ConnectionPoint> subset) -> -subset.size())
                .thenComparing(subset -> subset.get(0).getId()));
        List<Tree> accepted = new ArrayList<>();
        Set<String> unconnectedIds = new HashSet<>();
        for (int next = 0; next < queue.size(); next++) {
            List<ConnectionPoint> subset = queue.get(next);
            List<Option> options = options(subset);
            int rank = alternative.applyAsInt(subset);
            int start = rank > 0 ? alternativeIndex(options, rank) : 0;
            Tree chosen = null;
            // с другой врезкой ищем от первого отличного дерева, а если все дальше несовместимы — с начала списка
            for (int k = 0; k < options.size() && chosen == null; k++) {
                Tree tree = options.get((start + k) % options.size()).tree;
                if (compatible(tree, accepted)) {
                    chosen = tree;
                }
            }
            if (chosen == null) {
                chosen = merged(subset, options, accepted);
            }
            if (chosen == null) {
                subset.forEach(connection -> unconnectedIds.add(connection.getOksId()));
                continue;
            }
            accepted.add(chosen);
            for (ConnectionPoint left : chosen.unconnected) {
                if (subset.size() > 1) {
                    queue.add(List.of(left));
                } else {
                    unconnectedIds.add(left.getOksId());
                }
            }
        }
        List<FutureOks> unconnected = unconnected(unconnectedIds);
        try {
            return new Draft(accepted, unconnected, assembler.assemble("0", 0, accepted, unconnected));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // несколько деревьев в одной камере по отдельности собирались, а вместе нет: разбиение пропускается
            return null;
        }
    }

    /**
     * Все деревья подмножества пересекают уже принятые, поэтому деревья объединяются. Принятое дерево той же группы
     * ОКС, которое мешает лучшему дереву подмножества, убирается из accepted, а его ОКС вместе с подмножеством
     * строятся одним деревом. Возвращает это дерево (вызывающий добавит его в accepted) или null, если объединить
     * не удалось; тогда accepted остаётся прежним.
     */
    private Tree merged(List<ConnectionPoint> subset, List<Option> options, List<Tree> accepted) {
        if (options.isEmpty()) {
            return null;
        }
        Region region = regionByConnection.get(subset.get(0).getId());
        Geometry best = geometry(options.get(0).tree);
        for (Tree blocker : new ArrayList<>(accepted)) {
            List<ConnectionPoint> union = new ArrayList<>(blocker.connected());
            if (union.isEmpty() || regionByConnection.get(union.get(0).getId()) != region
                    || geometry(blocker).distance(best) > TREES_APART_M && !blocker.root.key.equals(options.get(0).tree.root.key)) {
                continue;
            }
            union.addAll(subset);
            accepted.remove(blocker);
            for (Option option : options(union)) {
                if (option.tree.unconnected.isEmpty() && compatible(option.tree, accepted)) {
                    return option.tree;
                }
            }
            accepted.add(blocker);
        }
        return null;
    }

    /** Деревья подмножества ОКС по всем кандидатам врезки, от лучшего score отдельно собранного дерева. */
    private List<Option> options(List<ConnectionPoint> subset) {
        Region region = regionByConnection.get(subset.get(0).getId());
        String key = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining("|"));
        List<Option> cached = region.options.get(key);
        if (cached != null) {
            return cached;
        }
        List<Point> points = points(subset);
        double flow = flow(subset);
        // граф по диаметру расхода подмножества со ступенью запаса, как у Region: отступы группы шире нужных ветке
        Diameter byFlow = rules.diameterFor(flow);
        Diameter step = rules.nextDiameter(byFlow.getDn());
        int blockDn = district ? region.dn : Math.min(region.dn, step != null ? step.getDn() : byFlow.getDn());
        List<TieCandidate> all = reach(finder.find(points, blockDn), points);
        List<Option> options = options(region, blockDn, region.area, subset, false, all);
        // ОКС, не вошедшие в общее дерево, draft подключает по одному, поэтому повторы нужны только одиночным
        if (subset.size() == 1) {
            int ownDn = rules.diameterFor(oksById.get(subset.get(0).getOksId()).getFlowTph()).getDn();
            if (ownDn < blockDn) {
                List<TieCandidate> own = candidates(points, flow, ownDn);
                if (detour(options, points.get(0), own)) {
                    // D-7: с запасом по диаметру маршрута нет или он в обход, а отступы для Ду по расходу меньше и
                    // могут пропустить короче: повтор с этим Ду, отступы и предельная длина — по фактическому Ду
                    options.addAll(options(region, ownDn, region.area, subset, true, own));
                }
            }
            if (incomplete(options) && !district) {
                // обход может не поместиться в область вокруг ОКС и кандидатов: последняя попытка на широкой области
                options.addAll(options(region, blockDn, region.wideArea, subset, false,
                        candidates(points, flow, blockDn)));
            }
            if (incomplete(options)) {
                // маршрута нет из-за поворота в точке выхода из здания круче 90°: трасса вдоль финального участка
                // длиннее, но неподключение при доступном маршруте запрещено (п. 2.5)
                options.addAll(options(region, blockDn, region.area, subset, false, all, true));
            }
        }
        options.sort(Comparator.comparingDouble(option -> option.score));
        if (!options.isEmpty() && alternativeIndex(options) == 0) {
            // все ближайшие кандидаты дают ту же врезку, а вариантов нужно не меньше двух (правило variants):
            // пробуется та же сеть дальше OTHER_TIE_M от лучшей врезки
            List<TieCandidate> along = finder.along(options.get(0).tree.tie, blockDn, OTHER_TIE_M);
            options.addAll(options(region, blockDn, region.area, subset, false, along));
            options.sort(Comparator.comparingDouble(option -> option.score));
        }
        region.options.put(key, options);
        return options;
    }

    /** Кандидаты врезки у точек. */
    private List<TieCandidate> candidates(List<Point> points, double flow, int dn) {
        return reach(finder.find(points, dn), points);
    }

    /** В районе города — кандидаты не дальше ближайшего к точкам больше чем на CITY_REACH_M; иначе все. */
    private List<TieCandidate> reach(List<TieCandidate> candidates, List<Point> points) {
        if (!district || candidates.isEmpty()) {
            return candidates;
        }
        Map<TieCandidate, Double> distance = new HashMap<>();
        for (TieCandidate candidate : candidates) {
            distance.put(candidate, points.stream().mapToDouble(p -> p.distance(candidate.getPoint())).min().orElseThrow());
        }
        double limit = distance.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow() + CITY_REACH_M;
        List<TieCandidate> result = new ArrayList<>();
        for (TieCandidate candidate : candidates) {
            if (distance.get(candidate) <= limit) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean incomplete(List<Option> options) {
        return options.stream().allMatch(option -> !option.tree.unconnected.isEmpty());
    }

    /**
     * Полного дерева нет или самое короткое длиннее прямой до ближайшего кандидата врезки больше чем в DETOUR_RATIO раз:
     * только тогда граф с меньшими отступами может дать трассу заметно короче, иначе повтор не окупает время.
     */
    private static boolean detour(List<Option> options, Point point, List<TieCandidate> candidates) {
        double shortest = options.stream().filter(option -> option.tree.unconnected.isEmpty())
                .mapToDouble(option -> option.tree.length()).min().orElse(Double.POSITIVE_INFINITY);
        double straight = candidates.stream().mapToDouble(candidate -> candidate.getPoint().distance(point))
                .min().orElse(Double.POSITIVE_INFINITY);
        return shortest > DETOUR_RATIO * straight;
    }

    private List<Option> options(Region region, int dn, Envelope area, List<ConnectionPoint> subset, boolean verify,
            List<TieCandidate> candidates) {
        return options(region, dn, area, subset, verify, candidates, false);
    }

    private List<Option> options(Region region, int dn, Envelope area, List<ConnectionPoint> subset, boolean verify,
            List<TieCandidate> candidates, boolean fromPortalDirection) {
        String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
        // метр ветки стоит в S и как стоимость трубы, и как длина: разовый расход переводится в метры по обоим
        double metreRub = rules.diameter(dn).getNewRubM() + rules.lengthWorthRub();
        Router router = region.router(dn, area);
        List<TieCandidate> cheapest = cheapestCandidates(subset, candidates);
        // деревья кандидатов независимы, граф и кэш общие и потокобезопасны; порядок результатов — порядок
        // кандидатов, поэтому итог не зависит от расписания нитей. В районе города нити заняты районами.
        java.util.stream.Stream<TieCandidate> stream = district || !PARALLEL ? cheapest.stream() : cheapest.parallelStream();
        List<Option> options = stream.map(candidate -> {
            Tree tree = builder.build(router, dn, area, candidate, subset, rules.chamberCost(dn) / metreRub,
                    rules.tieInCost() / metreRub, fromPortalDirection);
            if (tree.edges.isEmpty()) {
                log.debug("options: subset={} tie={} нет дерева", label, candidate.nodeKey());
                return null;
            }
            return option(tree, label, verify, area, dn, region);
        }).filter(Objects::nonNull).collect(Collectors.toList());
        options.sort(Comparator.comparingDouble(option -> option.score));
        return options;
    }

    /**
     * Дерево, собранное отдельным вариантом, со своим score; null, если сборка его отбросила. Если фактический ДУ
     * участков (по предельной длине пути) больше ДУ графа, отступы проверяются заново для него.
     */
    private Option option(Tree tree, String label, boolean verify, Envelope area, int graphDn, Region region) {
        Set<String> ids = new HashSet<>();
        tree.unconnected.forEach(connection -> ids.add(connection.getOksId()));
        try {
            Variant alone = assembler.assemble("0", 0, List.of(tree), unconnected(ids));
            log.debug("options: subset={} tie={} score={} unconnected={}", label, tree.tie.nodeKey(),
                    alone.getSummary().getScore(), tree.unconnected.size());
            int maxDn = alone.getSegments().stream().mapToInt(NewSegment::getDiameter).max().orElse(graphDn);
            boolean check = verify || maxDn > graphDn;
            return !check || clearanceHolds(tree, alone, area, region) ? new Option(tree, alone.getSummary().getScore()) : null;
        } catch (IllegalStateException | IllegalArgumentException e) {
            // дерево нарушает правила при сборке (предельная длина, отступ участка, число поворотов): кандидат отбрасывается
            log.debug("options: subset={} tie={} отброшено: {}", label, tree.tie.nodeKey(), e.getMessage());
            return null;
        }
    }

    /**
     * До CANDIDATE_LIMIT кандидатов с наименьшей грубой оценкой: прямые от точек подключения по цене трубы диаметра
     * блока, камера, если врезка в трубу, врезка — если в существующую камеру.
     */
    private List<TieCandidate> cheapestCandidates(List<ConnectionPoint> subset, List<TieCandidate> candidates) {
        double flow = flow(subset);
        int dn = rules.diameterFor(flow).getDn();
        double pricePerM = rules.diameter(dn).getNewRubM();
        Map<TieCandidate, Double> estimate = new HashMap<>();
        for (TieCandidate candidate : candidates) {
            double rub = candidate.isChamber() ? rules.tieInCost() : rules.chamberCost(dn);
            for (ConnectionPoint connection : subset) {
                rub += connection.getGeometry().distance(candidate.getPoint()) * pricePerM;
            }
            estimate.put(candidate, rub);
        }
        List<TieCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(estimate::get));
        return new ArrayList<>(sorted.subList(0, Math.min(CANDIDATE_LIMIT, sorted.size())));
    }

    /**
     * Отступы дерева, построенного по графу другого диаметра, проверяются для наибольшего фактического диаметра;
     * финальный отрезок к точке подключения проверяется без отступа к её полигону.
     */
    private boolean clearanceHolds(Tree tree, Variant alone, Envelope area, Region region) {
        int dn = alone.getSegments().stream().mapToInt(NewSegment::getDiameter).max().orElseThrow();
        ObstacleSet obstacles = region.obstacles(dn, area);
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] coords = edge.line.getCoordinates();
            Set<String> ignored = tree.tie.getIgnored();
            Set<String> last = ignored;
            ExistingOks building = edge.to.kind == Tree.Kind.CONNECTION ? buildingByConnection.get(edge.to.connection.getId()) : null;
            if (building != null) {
                last = new HashSet<>(ignored);
                last.add(building.getId());
            }
            for (int i = 0; i + 1 < coords.length; i++) {
                if (Double.isNaN(obstacles.edgeWeight(coords[i], coords[i + 1], i + 2 == coords.length ? last : ignored))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Первый вариант дерева с другой врезкой: другой объект или точка дальше OTHER_TIE_M. */
    private static int alternativeIndex(List<Option> options) {
        return alternativeIndex(options, 1);
    }

    /** Индекс rank-го варианта дерева с врезкой, отличной от всех предыдущих отличных; 0, если столько нет. */
    private static int alternativeIndex(List<Option> options, int rank) {
        List<TieCandidate> seen = new ArrayList<>();
        if (!options.isEmpty()) {
            seen.add(options.get(0).tree.tie);
        }
        for (int i = 1; i < options.size(); i++) {
            TieCandidate other = options.get(i).tree.tie;
            boolean distinct = seen.stream().allMatch(tie -> !tie.getExistingObjectId().equals(other.getExistingObjectId())
                    || tie.getPoint().distance(other.getPoint()) > OTHER_TIE_M);
            if (distinct) {
                seen.add(other);
                if (seen.size() - 1 == rank) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * Дерево не касается уже принятых. Деревья с общей камерой врезки не переполняют её и собираются вместе:
     * цепочки одного диаметра у них общие через камеру.
     */
    private boolean compatible(Tree tree, List<Tree> accepted) {
        Geometry geometry = geometry(tree);
        int rootDegree = tree.degree(tree.root);
        for (Tree other : accepted) {
            if (other.root.key.equals(tree.root.key)) {
                rootDegree += other.degree(other.root);
            }
        }
        if (rootDegree > tree.tie.getCapacity()) {
            return false;
        }
        List<Tree> sharing = new ArrayList<>();
        for (Tree other : accepted) {
            if (other.root.key.equals(tree.root.key)) {
                Geometry clip = factory.createPoint(tree.root.point).buffer(SHARED_ROOT_CLIP_M);
                if (geometry.difference(clip).distance(geometry(other).difference(clip)) <= SHARED_ROOT_APART_M) {
                    return false;
                }
                sharing.add(other);
            } else if (geometry.distance(geometry(other)) <= TREES_APART_M) {
                return false;
            }
        }
        if (sharing.isEmpty()) {
            return true;
        }
        sharing.add(tree);
        try {
            assembler.assemble("0", 0, sharing, List.of());
            return true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Правило variants: другой existing_object_id, врезка дальше 20 м от всех врезок другого или другое разбиение. */
    private boolean differ(Draft a, Draft b) {
        Set<String> idsA = new HashSet<>();
        Set<String> idsB = new HashSet<>();
        a.trees.forEach(tree -> idsA.add(tree.tie.getExistingObjectId()));
        b.trees.forEach(tree -> idsB.add(tree.tie.getExistingObjectId()));
        return !idsA.equals(idsB) || farTie(a, b) || farTie(b, a) || !partition(a).equals(partition(b));
    }

    private static boolean farTie(Draft mine, Draft others) {
        for (Tree tree : mine.trees) {
            boolean far = true;
            for (Tree other : others.trees) {
                far &= tree.tie.getPoint().distance(other.tie.getPoint()) > OTHER_TIE_M;
            }
            if (far) {
                return true;
            }
        }
        return false;
    }

    private static Set<Set<String>> partition(Draft draft) {
        Map<String, Set<String>> byRoot = new HashMap<>();
        for (Tree tree : draft.trees) {
            Set<String> oks = byRoot.computeIfAbsent(tree.root.key, key -> new HashSet<>());
            tree.connected().forEach(connection -> oks.add(connection.getOksId()));
        }
        return new HashSet<>(byRoot.values());
    }

    /** R-11: больше 80 % длины меньшего варианта совпадает с трассой другого. */
    private boolean sameRoute(Draft a, Draft b) {
        Geometry lineA = lines(a);
        Geometry lineB = lines(b);
        double shorter = Math.min(lineA.getLength(), lineB.getLength());
        if (shorter == 0) {
            return lineA.getLength() == lineB.getLength();
        }
        double common = Math.min(lineA.intersection(lineB.buffer(SAME_ROUTE_M)).getLength(),
                lineB.intersection(lineA.buffer(SAME_ROUTE_M)).getLength());
        return common > SAME_ROUTE_SHARE * shorter;
    }

    private Geometry lines(Draft draft) {
        return factory.createMultiLineString(draft.trees.stream().flatMap(tree -> tree.edges.stream())
                .map(edge -> edge.line).toArray(LineString[]::new));
    }

    private Geometry geometry(Tree tree) {
        return factory.createMultiLineString(tree.edges.stream().map(edge -> edge.line).toArray(LineString[]::new));
    }

    private List<FutureOks> unconnected(Set<String> oksIds) {
        List<FutureOks> result = new ArrayList<>();
        for (FutureOks oks : input.getFutureOks()) {
            if (oksIds.contains(oks.getId()) || !connectionByOks.containsKey(oks.getId())) {
                result.add(oks);
            }
        }
        return result;
    }

    private double flow(List<ConnectionPoint> connections) {
        double flow = 0;
        for (ConnectionPoint connection : connections) {
            flow += oksById.get(connection.getOksId()).getFlowTph();
        }
        return flow;
    }

    /** Точки подключения и, для нескольких ОКС, их центр. */
    private List<Point> points(List<ConnectionPoint> connections) {
        List<Point> points = new ArrayList<>();
        connections.forEach(connection -> points.add(connection.getGeometry()));
        if (connections.size() > 1) {
            points.add(factory.createPoint(center(connections)));
        }
        return points;
    }

    /** Одиночная связь: ОКС в одной группе, если цепочка точек подключения с шагом не больше GROUP_DISTANCE_M. */
    private static List<List<ConnectionPoint>> groups(List<ConnectionPoint> connections) {
        return groupsFast(connections);
    }

    /**
     * Медленная O(n²) кластеризация для проверки эквивалентности ({@link #groupsFast}); координаты в метрах
     * (EPSG:32637).
     */
    static List<List<ConnectionPoint>> groupsNaive(List<ConnectionPoint> connections) {
        int n = connections.size();
        int[] root = new int[n];
        for (int i = 0; i < n; i++) {
            root[i] = i;
        }
        for (int i = 0; i < n; i++) {
            Point pi = connections.get(i).getGeometry();
            for (int j = i + 1; j < n; j++) {
                if (pi.distance(connections.get(j).getGeometry()) <= GROUP_DISTANCE_M) {
                    root[find(root, i)] = find(root, j);
                }
            }
        }
        return groupsByRoot(connections, root);
    }

    /** Union-find по соседям в радиусе GROUP_DISTANCE_M через STRtree (метрическое расстояние в UTM). */
    static List<List<ConnectionPoint>> groupsFast(List<ConnectionPoint> connections) {
        int n = connections.size();
        if (n == 0) {
            return List.of();
        }
        int[] root = new int[n];
        for (int i = 0; i < n; i++) {
            root[i] = i;
        }
        STRtree index = new STRtree();
        for (int i = 0; i < n; i++) {
            Envelope env = connections.get(i).getGeometry().getEnvelopeInternal();
            env = new Envelope(env);
            env.expandBy(GROUP_DISTANCE_M);
            index.insert(env, i);
        }
        index.build();
        for (int i = 0; i < n; i++) {
            Envelope query = connections.get(i).getGeometry().getEnvelopeInternal();
            query = new Envelope(query);
            query.expandBy(GROUP_DISTANCE_M);
            @SuppressWarnings("unchecked")
            List<Integer> hits = index.query(query);
            Point pi = connections.get(i).getGeometry();
            for (Integer j : hits) {
                if (j <= i) {
                    continue;
                }
                if (pi.distance(connections.get(j).getGeometry()) <= GROUP_DISTANCE_M) {
                    root[find(root, i)] = find(root, j);
                }
            }
        }
        return groupsByRoot(connections, root);
    }

    private static List<List<ConnectionPoint>> groupsByRoot(List<ConnectionPoint> connections, int[] root) {
        Map<Integer, List<ConnectionPoint>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < connections.size(); i++) {
            byRoot.computeIfAbsent(find(root, i), key -> new ArrayList<>()).add(connections.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    /** k-means для малых групп; для {@code size > KMEANS_MAX} — разрез bbox пополам по длинной оси UTM. */
    static List<List<ConnectionPoint>> splitGroup(List<ConnectionPoint> connections) {
        if (connections.size() > KMEANS_MAX) {
            return spatialGridSplit(connections);
        }
        return kMeans(connections);
    }

    private static List<List<ConnectionPoint>> spatialGridSplit(List<ConnectionPoint> connections) {
        Envelope envelope = new Envelope();
        for (ConnectionPoint connection : connections) {
            envelope.expandToInclude(connection.getGeometry().getCoordinate());
        }
        boolean alongX = envelope.getWidth() >= envelope.getHeight();
        double mid = alongX ? envelope.centre().x : envelope.centre().y;
        List<ConnectionPoint> first = new ArrayList<>();
        List<ConnectionPoint> second = new ArrayList<>();
        for (ConnectionPoint connection : connections) {
            Coordinate coordinate = connection.getGeometry().getCoordinate();
            double value = alongX ? coordinate.x : coordinate.y;
            if (value < mid) {
                first.add(connection);
            } else {
                second.add(connection);
            }
        }
        if (first.isEmpty() || second.isEmpty()) {
            return kMeans(connections);
        }
        return List.of(first, second);
    }

    /** k-means с k = 2, старт с двух самых далёких точек. */
    static List<List<ConnectionPoint>> kMeans(List<ConnectionPoint> connections) {
        Coordinate a = null;
        Coordinate b = null;
        for (ConnectionPoint p : connections) {
            for (ConnectionPoint q : connections) {
                if (a == null || p.getGeometry().distance(q.getGeometry()) > a.distance(b)) {
                    a = p.getGeometry().getCoordinate().copy();
                    b = q.getGeometry().getCoordinate().copy();
                }
            }
        }
        List<ConnectionPoint> first = new ArrayList<>();
        List<ConnectionPoint> second = new ArrayList<>();
        for (int iteration = 0; iteration < KMEANS_ITERATIONS; iteration++) {
            first.clear();
            second.clear();
            for (ConnectionPoint connection : connections) {
                Coordinate c = connection.getGeometry().getCoordinate();
                (c.distance(a) <= c.distance(b) ? first : second).add(connection);
            }
            if (first.isEmpty() || second.isEmpty()) {
                break;
            }
            a = center(first);
            b = center(second);
        }
        List<List<ConnectionPoint>> result = new ArrayList<>();
        for (List<ConnectionPoint> part : List.of(first, second)) {
            if (!part.isEmpty()) {
                result.add(new ArrayList<>(part));
            }
        }
        return result;
    }

    private static Coordinate center(List<ConnectionPoint> connections) {
        Coordinate center = new Coordinate(0, 0);
        for (ConnectionPoint connection : connections) {
            center.x += connection.getGeometry().getX() / connections.size();
            center.y += connection.getGeometry().getY() / connections.size();
        }
        return center;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) {
            root[i] = root[root[i]];
            i = root[i];
        }
        return i;
    }
}
