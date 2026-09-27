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
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.graph.ObstacleIndex;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.Route;
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
    private static final String OKS_EXISTING = "oks_existing";
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
    static final double TREES_APART_M = 0.5;
    /** Сдвиг врезки к стволу берётся, если ствол короче хотя бы на столько, см. slid. */
    private static final double SLIDE_MIN_M = 1.0;
    static final double SHARED_ROOT_CLIP_M = 0.15;
    static final double SHARED_ROOT_APART_M = 0.01;
    private static final int MAX_VARIANTS = 3;
    /** Сдвиги врезки вдоль трубы от проекции камеры ветвления, м, см. {@link #absorbed}. */
    private static final double[] ABSORB_SHIFTS_M = {0, -2, 2, -4, 4, -8, 8, -16, 16};
    /** Деревья кандидатов врезки считаются параллельно (heatnet.search.parallel); false — в одну нить, тот же выход. */
    private static final boolean PARALLEL = Boolean.parseBoolean(System.getProperty("heatnet.search.parallel", "true"));
    /** Рёбра выбранных вариантов прокладываются заново по графу своего Ду (heatnet.search.reroute), см. rerouted. */
    private static final boolean REROUTE = Boolean.parseBoolean(System.getProperty("heatnet.search.reroute", "true"));
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
    // 300 обрывали поиск на плотных сценах; при застое 100 обрывали и 600: «густо» 100 и 200 доходили до 600 сборок
    // с S 109,92 и 225,91, а с бюджетом 1000 сами останавливаются на 740 и 776 сборках с S 109,37 и 225,25.
    // Датасет организаторов останавливается на 100 сборках, сценарии S00–S14 — не дальше 28, им запас ничего не стоит
    private static final int SEARCH_BUDGET = Integer.getInteger("heatnet.search.budget", 1000);
    /**
     * Остановка после стольких сборок подряд без улучшения лучшего score (heatnet.search.stall). 0 — только budget.
     */
    // 50 останавливали «густо» 50/100/200 на S 54,08 / 110,13 / 225,95; со 100 выходит 53,19 / 109,37 / 225,25 за время
    // ×1,22 на «густо-200» и ×1,3 на датасете организаторов: там поиск перебирает 100 черновиков вместо 52, а S тот же
    // (прогоны подряд с застоем 50, 25.09.2026). S датасета организаторов и сценариев S00–S14 от застоя 50–200
    // не зависит. При 75 «густо-200» даёт 225,91 за ×1,11, при 150 — 224,99 за ×1,37
    private static final int SEARCH_STALL = Integer.getInteger("heatnet.search.stall", 100);
    /**
     * Застой в районах города (heatnet.city.stall). При 100 районы доходят до бюджета CITY_BUDGET: выход города
     * меняется, а районы считаются на 10 % дольше, поэтому здесь прежние 50.
     */
    private static final int CITY_STALL = Integer.getInteger("heatnet.city.stall", 50);
    /** Деревья подмножества строятся не на всех кандидатах врезки, а на лучших по грубой оценке стоимости. */
    private static final int CANDIDATE_LIMIT = 6;
    /** Запас к прямой до ближайшей врезки при выборе диаметра графа по предельной длине: трасса длиннее прямой. */
    private static final double LENGTH_DN_MARGIN = 1.2;
    /** Слияния и переносы пробуются только между блоками, ближайшими друг к другу по точкам подключения. */
    private static final int NEAREST_BLOCKS = 2;
    private static final double IMPROVE_EPS = 1e-6;
    /** Сдвиг врезки вдоль трубы, снимающий излом, не хуже по S с этим запасом на округление, см. {@link #retied}. */
    private static final double UNKINK_EPS = 1e-9;
    /** Сдвиги новой камеры врезки вдоль трубы, снимающие излом у неё: шаг и наибольший, см. {@link #retied}. */
    private static final double RETIE_STEP_M = 0.1;
    private static final double RETIE_MAX_M = 5;
    /**
     * Правка поворота в камере на пути точки к врезке, см. {@link #turned}: сдвиг камеры шагом TURN_STEP_M до
     * TURN_MAX_M. S узла врезки растёт не больше TURN_TOLERANCE_S за место, так же у переноса камеры, снимающего
     * излом, см. {@link #unkinked(List)}.
     */
    private static final double TURN_STEP_M = 0.1;
    private static final double TURN_MAX_M = 10;
    private static final double TURN_TOLERANCE_S = 0.002;
    /** Сдвиг камер ветвления в деревьях выбранных вариантов (heatnet.slide.junctions), см. {@link #shifted(Draft)}. */
    private static final boolean SLIDE_JUNCTIONS =
            Boolean.parseBoolean(System.getProperty("heatnet.slide.junctions", "true"));
    /** Сдвиг камеры собирается, только если по цене метров рёбер он дешевле хотя бы на столько рублей: ≈0,25 м ветки. */
    private static final double SLIDE_MIN_GAIN_RUB = 50_000;
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
     * в docs/research.md: запас области CITY_MARGIN_M вместо AREA_MARGIN_M, один диаметр графа на
     * район вместо диаметра по расходу каждого подмножества, без последней попытки на широкой области, поиск
     * на CITY_BUDGET черновиков.
     */
    private static final double CITY_REACH_M = 150;
    private static final double CITY_MARGIN_M = 60;
    private static final int CITY_BUDGET = Integer.getInteger("heatnet.city.budget", 60);
    /**
     * Сколько секунд от начала городского расчёта отводится районам (heatnet.city.deadline): районы идут от ближних
     * к сети к дальним, после срока оставшиеся не считаются, их точки остаются без сети. На синтетическом городе
     * десятки тысяч точек лежат в километрах от сети, и графы их районов считаются часами. После сетки зон и быстрых
     * проверок геометрии районы ближних точек досчитываются за ~110 с, последний район с деревом — за ~230 с;
     * срок 300 и 900 с даёт тот же выход (замеры 24.09.2026 в docs/performance.md). На медленной машине
     * до срока не успеют лишь дальние районы в 5–11 км от сети, где подключение дороже штрафа.
     */
    private static final long CITY_DEADLINE_S = Long.getLong("heatnet.city.deadline", 300);
    /** Считать только районы с этими номерами (свойство heatnet.city.only, через запятую): замеры и сверка отдельных районов. */
    private static final Set<String> CITY_ONLY = System.getProperty("heatnet.city.only") == null ? null
            : Set.of(System.getProperty("heatnet.city.only").split(","));

    private static final long CITY_CACHE_MB = 64;
    /**
     * Перенос камер ветвления в деревьях выбранных вариантов (heatnet.relay), см. {@link JunctionMover}: сколько лучших
     * по оценке точек пробуется сборкой у камеры, если общий перенос не удался, и сколько проходов по камерам дерева.
     * Перенос в лучших деревьях подмножеств внутри поиска давал датасет хуже, а сборок деревьев на 7 % больше.
     */
    private static final boolean RELAY = Boolean.parseBoolean(System.getProperty("heatnet.relay", "true"));
    private static final int RELAY_TRIES = 3;
    private static final int RELAY_PASSES = 3;
    /** Пул для деревьев подмножеств, см. {@link #prefetch}; нити демоны, как у пула чтения. */
    private static final ExecutorService PREFETCH_POOL = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), task -> {
                Thread thread = new Thread(task, "subset-trees");
                thread.setDaemon(true);
                return thread;
            });

    private final InputData input;
    private final Rules rules;
    private final TieInFinder finder;
    private final ObstacleIndex obstacleIndex;
    private final RouteCache routeCache;
    /** Расчёт одного района города: кандидаты врезки только в радиусе CITY_REACH_M. */
    private final boolean district;
    /**
     * Прямые подключения города для района парами «номер набора → дерево» (null вне районов): дерево района не
     * касается их и не переполняет их камеры, см. {@link #compatible}. Иначе сборка отбросила бы такое дерево района
     * вместе с его точками.
     */
    private STRtree direct;
    private final SpecialObjects specials;
    /** Полигон ОКС, в котором лежит точка подключения, по id точки. */
    private final Map<String, ExistingOks> buildingByConnection;
    private final TreeBuilder builder;
    private final JunctionMover mover;
    private NetworkAssembler assembler;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, FutureOks> oksById = new LinkedHashMap<>();
    private final Map<String, ConnectionPoint> connectionByOks = new LinkedHashMap<>();
    private final Map<String, Region> regionByConnection = new HashMap<>();

    private static final class Option {
        final Tree tree;
        final double score;
        /** score без округления, см. {@link #exact(Variant)}. */
        final double exact;
        /** В дереве есть ветка со звеном после выхода из здания, см. {@link #plain}. */
        boolean linked;
        /** Точка осталась без сети из-за поворота в точке выхода, см. {@link Tree#turnStuck}. */
        boolean turnStuck;
        /**
         * Дерево той же врезки, где такие точки присоединены со звеном после выхода; строится, только когда черновик
         * выбрал это дерево, см. {@link #draft}. null — строить нечего.
         */
        java.util.function.Supplier<Option> withLinks;
        private Option linkedOption;
        private boolean linkedBuilt;

        synchronized Option linkedOption() {
            if (!linkedBuilt) {
                linkedOption = withLinks.get();
                linkedBuilt = true;
            }
            return linkedOption;
        }

        Option(Tree tree, double score, double exact) {
            this.tree = tree;
            this.score = score;
            this.exact = exact;
        }
    }

    /**
     * Граф и Ду, на которых построено дерево подмножества, графы Ду веток для доводки формы ({@link TreeBuilder#cut})
     * и оценка новой формы дерева тем же {@link #option}.
     */
    private static final class Shaping {
        final Router router;
        final int dn;
        final TreeBuilder.Graphs graphs;
        final java.util.function.Function<Tree, Option> option;

        Shaping(Router router, int dn, TreeBuilder.Graphs graphs, java.util.function.Function<Tree, Option> option) {
            this.router = router;
            this.dn = dn;
            this.graphs = graphs;
            this.option = option;
        }
    }

    /** Как построено каждое дерево из {@link #options}: по нему сдвигаются камеры в деревьях вариантов. */
    private final Map<Tree, Shaping> shapings = new java.util.concurrent.ConcurrentHashMap<>();

    /** Работа проходов после поиска для лога: сборки одного дерева в {@link #option}, доводки формы, сборки черновика. */
    private final AtomicInteger optionCount = new AtomicInteger();
    private final AtomicInteger cutCount = new AtomicInteger();
    private final AtomicInteger draftCount = new AtomicInteger();

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
                    key -> new ObstacleSet(obstacleIndex, rules, obstaclesArea, obstaclesDn, null, routeCache));
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
        /** Трасса для сравнения по R-11, строится по требованию. */
        RouteBand band;

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
        this.mover = new JunctionMover(rules, specials, buildingByConnection, oksById);
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
        // сначала перенос камер, потом сдвиг: наоборот S на восьми наборах в сумме хуже, датасет 12,793 вместо 12,785
        if (RELAY) {
            resetWork();
            picked = relaid(picked);
            log.info("relay: {}", work());
        }
        if (SLIDE_JUNCTIONS) {
            resetWork();
            long started = System.nanoTime();
            // черновики независимы, а поиск уже закончен и ядра свободны; сдвиги могут поменять порядок вариантов
            picked = (PARALLEL ? picked.parallelStream() : picked.stream()).map(this::shifted)
                    .sorted(Comparator.comparingDouble(Draft::score)).collect(Collectors.toList());
            log.info("slide: {} elapsed={}ms", work(), (System.nanoTime() - started) / 1_000_000);
        }
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Draft draft = picked.get(i);
            Variant variant = null;
            if (REROUTE) {
                try {
                    variant = assembler.assemble(String.valueOf(i + 1), i + 1, unkinked(turned(rerouted(draft))),
                            draft.unconnected);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    // узлы врезки собирались по отдельности, а вместе нет: вариант как найден поиском
                    log.info("rerouted: вариант {} не собран: {}", i + 1, e.getMessage());
                }
            }
            if (variant == null) {
                try {
                    variant = assembler.assemble(String.valueOf(i + 1), i + 1, unkinked(turned(draft.trees)), draft.unconnected);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    log.info("unkinked: вариант {} не собран: {}", i + 1, e.getMessage());
                }
            }
            variants.add(variant != null ? variant
                    : assembler.assemble(String.valueOf(i + 1), i + 1, draft.trees, draft.unconnected));
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
        assembler = new NetworkAssembler(input, rules, specials, oksById);
        DirectTies direct = new DirectTies(input, rules, obstacleIndex, finder, buildingByConnection, assembler);
        List<ConnectionPoint> rest = new ArrayList<>();
        List<List<Tree>> directTrees = direct.connect(all, oksById, 2, rest);
        log.info("city: direct trees={} rest={} elapsed={}s", directTrees.get(0).size(), rest.size(),
                (System.nanoTime() - started) / 1_000_000_000L);
        if (direct.same(directTrees.get(0), directTrees.get(1))) {
            directTrees.remove(1);
        }
        // путь длиннее предельной длины наибольшего ДУ недопустим при любом диаметре: точка дальше этого от сети
        // остаётся без маршрута, и граф для неё не строится
        double reach = rules.diameters().get(rules.diameters().size() - 1).getMaxLengthM();
        // расстояния независимы, индексы сети после build только читаются; в карте только ближние точки
        double[] distances = new double[rest.size()];
        IntStream.range(0, rest.size()).parallel()
                .forEach(i -> distances[i] = direct.networkDistance(rest.get(i).getGeometry(), reach));
        List<ConnectionPoint> near = new ArrayList<>();
        Map<String, Double> toNetwork = new HashMap<>();
        for (int i = 0; i < rest.size(); i++) {
            if (distances[i] <= reach) {
                near.add(rest.get(i));
                toNetwork.put(rest.get(i).getId(), distances[i]);
            }
        }
        List<List<ConnectionPoint>> districts = districts(near);
        // деревья обоих наборов прямых подключений: черновик района идёт во все варианты
        STRtree directIndex = new STRtree();
        for (int k = 0; k < directTrees.size(); k++) {
            for (Tree tree : directTrees.get(k)) {
                directIndex.insert(tree.envelope(), Map.entry(k, tree));
            }
        }
        directIndex.build();
        // ближние к сети районы первыми: их графы меньше, и до срока успевает больше точек
        districts.sort(Comparator.comparingDouble(district -> district.stream()
                .mapToDouble(connection -> toNetwork.get(connection.getId())).min().orElse(0)));
        log.info("city: oks={} direct={} beyond {} m: {} districts={} elapsed={}s", all.size(), all.size() - rest.size(),
                reach, rest.size() - near.size(), districts.size(), (System.nanoTime() - started) / 1_000_000_000L);
        List<List<Draft>> results = districts.isEmpty() ? List.of()
                : solve(districts, directIndex, started + CITY_DEADLINE_S * 1_000_000_000L);
        int most = Math.max(directTrees.size(), results.stream().mapToInt(List::size).max().orElse(0));
        List<Variant> variants = new ArrayList<>();
        for (int k = 0; k < Math.min(MAX_VARIANTS, Math.max(most, 1)); k++) {
            List<Tree> trees = new ArrayList<>(directTrees.get(Math.min(k, directTrees.size() - 1)));
            List<Tree> districtTrees = joined(results, k, trees);
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
        // проход по 3 млн ОКС города параллельно, порядок входа сохраняется
        return input.getFutureOks().parallelStream().filter(oks -> !connected.contains(oks.getId()))
                .collect(Collectors.toList());
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
    private List<List<Draft>> solve(List<List<ConnectionPoint>> districts, STRtree directIndex, long deadlineNanos) {
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
                    if (CITY_ONLY != null && !CITY_ONLY.contains(String.valueOf(index))) {
                        return List.<Draft>of();
                    }
                    if (System.nanoTime() > deadlineNanos) {
                        skipped.incrementAndGet();
                        return List.<Draft>of();
                    }
                    long districtStarted = System.nanoTime();
                    VariantEnumerator district = district(connections);
                    district.direct = directIndex;
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

    /**
     * Деревья k-х черновиков районов, кроме задевающих уже принятые деревья соседних районов и прямые подключения
     * варианта {@code ties} или переполняющих общую с ними камеру: новые участки не пересекаются вне общего узла
     * (п. 5). Районы считаются без деревьев соседей, а место в камере у наборов прямых подключений разное.
     */
    private List<Tree> joined(List<List<Draft>> results, int k, List<Tree> ties) {
        List<Tree> accepted = new ArrayList<>();
        Quadtree index = new Quadtree();
        for (Tree tree : ties) {
            index.insert(geometry(tree).getEnvelopeInternal(), tree);
        }
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
            log.info("city: variant {} dropped {} trees touching other districts or direct ties", k + 1, dropped);
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
        assembler = new NetworkAssembler(input, rules, specials, oksById);
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
            // содержательно другие сети от лучшего черновика для вариантов 2 и 3
            others(picked.get(0), drafts, true);
            picked = pick(drafts);
        }
        if (picked.size() == 2 && !district) {
            // перенос врезки к стволу сводит другие врезки блока на ту же трубу у той же вершины, и трасса почти не
            // меняется: третий вариант ищется от блоков второго с врезками там, где их нашёл поиск кандидатов
            others(picked.get(1), drafts, false);
            picked = pick(drafts);
        }
        return picked;
    }

    /**
     * Черновики от блоков draft: другая врезка у каждого блока (до трёх отличных) и разбиение больших блоков пополам;
     * {@code slide} — как у {@link #draft}.
     */
    private void others(Draft draft, List<Draft> drafts, boolean slide) {
        List<List<ConnectionPoint>> blocks = blocksOf(draft);
        // крупные блоки первыми: их врезка меняет больше трассы; на сотнях блоков перебор всех утраивал время
        blocks.sort(Comparator.comparingInt((List<ConnectionPoint> block) -> -block.size()));
        for (int i = 0; i < Math.min(blocks.size(), EXTRA_VARIANT_BLOCKS); i++) {
            for (int rank = 1; rank <= 3; rank++) {
                List<List<ConnectionPoint>> same = copy(blocks);
                List<ConnectionPoint> alternative = same.get(i);
                int r = rank;
                drafts.add(draft(same, subset -> subset == alternative ? r : 0, slide));
            }
            if (blocks.get(i).size() >= 3) {
                List<List<ConnectionPoint>> parts = splitGroup(blocks.get(i));
                if (parts.size() == 2) {
                    List<List<ConnectionPoint>> halves = copy(blocks);
                    halves.remove(i);
                    halves.addAll(parts);
                    drafts.add(draft(halves, subset -> 0, slide));
                }
            }
        }
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
        int stall = district ? CITY_STALL : SEARCH_STALL;
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
        // соседей начального черновика перебираем всех: с врезками у ствола он бывает уже хорош, первые 50 соседей
        // хуже, и остановка по застою обрывала поиск до первого улучшения («густо-100»: 115,70 вместо 113,69)
        int firstScan = moves(blocks).size();
        while (spent < budget) {
            Move step = null;
            Draft stepDraft = null;
            for (Move move : moves(blocks)) {
                if (spent >= budget) {
                    break;
                }
                if (stall > 0 && sinceBestImprove >= stall && spent >= firstScan) {
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
                if (stall > 0 && sinceBestImprove >= stall && spent >= firstScan) {
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
            if (stall > 0 && sinceBestImprove >= stall && spent >= firstScan) {
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
                spent, String.format(Locale.ROOT, "%.2f", (System.nanoTime() - started) / 1e9), tables[0], tables[1], routers, nodes,
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
        return draft(partition, alternative, true);
    }

    /** То же; {@code slide} — деревья с врезкой, перенесённой к стволу, см. {@link #slid}. */
    private Draft draft(List<List<ConnectionPoint>> partition, java.util.function.ToIntFunction<List<ConnectionPoint>> alternative,
            boolean slide) {
        prefetch(partition, slide);
        List<List<ConnectionPoint>> queue = new ArrayList<>(partition);
        queue.sort(Comparator.comparingInt((List<ConnectionPoint> subset) -> -subset.size())
                .thenComparing(subset -> subset.get(0).getId()));
        List<Tree> accepted = new ArrayList<>();
        Set<String> unconnectedIds = new HashSet<>();
        for (int next = 0; next < queue.size(); next++) {
            List<ConnectionPoint> subset = queue.get(next);
            List<Option> options = options(subset, slide);
            int rank = alternative.applyAsInt(subset);
            int start = rank > 0 ? alternativeIndex(options, rank) : 0;
            Tree chosen = null;
            // с другой врезкой ищем от первого отличного дерева, а если все дальше несовместимы — с начала списка
            for (int k = 0; k < options.size() && chosen == null; k++) {
                Option option = options.get((start + k) % options.size());
                if (compatible(option.tree, accepted)) {
                    chosen = option.tree;
                    // точки, которые дерево оставило из-за поворота у выхода, подключаются поодиночке; дерево со
                    // звеном после выхода берётся, только если оно дешевле такого подключения
                    Option linked = option.withLinks == null ? null : option.linkedOption();
                    if (linked != null && compatible(linked.tree, accepted)
                            && separately(linked, slide) < separately(option, slide) - IMPROVE_EPS) {
                        chosen = linked.tree;
                    }
                }
            }
            if (chosen == null) {
                chosen = merged(subset, options, accepted, slide);
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

    /** Score дерева, если его неподключённые точки подключить поодиночке лучшим деревом каждой. */
    private double separately(Option option, boolean slide) {
        double score = option.score;
        for (ConnectionPoint left : option.tree.unconnected) {
            double penalty = rules.score(rules.penalty(oksById.get(left.getOksId()).getFlowTph()), 0);
            List<Option> single = options(List.of(left), slide);
            score += (single.isEmpty() ? penalty : Math.min(penalty, single.get(0).score)) - penalty;
        }
        return score;
    }

    /**
     * Все деревья подмножества пересекают уже принятые, поэтому деревья объединяются. Принятое дерево той же группы
     * ОКС, которое мешает лучшему дереву подмножества, убирается из accepted, а его ОКС вместе с подмножеством
     * строятся одним деревом. Возвращает это дерево (вызывающий добавит его в accepted) или null, если объединить
     * не удалось; тогда accepted остаётся прежним.
     */
    private Tree merged(List<ConnectionPoint> subset, List<Option> options, List<Tree> accepted, boolean slide) {
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
            for (Option option : options(union, slide)) {
                if (option.tree.unconnected.isEmpty() && compatible(option.tree, accepted)) {
                    return option.tree;
                }
            }
            accepted.add(blocker);
        }
        return null;
    }

    /**
     * Деревья подмножества ОКС по всем кандидатам врезки, от лучшего score отдельно собранного дерева; {@code slide} —
     * переносить врезку к стволу, см. {@link #slid}.
     */
    private List<Option> options(List<ConnectionPoint> subset, boolean slide) {
        Region region = regionByConnection.get(subset.get(0).getId());
        String key = optionsKey(subset, slide);
        List<Option> cached = region.options.get(key);
        if (cached != null) {
            return cached;
        }
        List<Option> options = computeOptions(region, subset, slide);
        region.options.put(key, options);
        return options;
    }

    private static String optionsKey(List<ConnectionPoint> subset, boolean slide) {
        return subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining("|")) + (slide ? "" : "#fixed");
    }

    /**
     * Деревья ещё не посчитанных подмножеств разбиения, все сразу и параллельно. Черновик берёт подмножества по
     * одному, и параллельно строились только деревья кандидатов одного подмножества (до шести), а ядер больше.
     * Деревья подмножества от других подмножеств не зависят, поэтому выход тот же. Подмножества считаются в своём
     * пуле, а не в общем: нить общего пула, которая строит граф внутри Region.router, могла бы взять задачу другого
     * подмножества и попросить тот же граф. В районе города нити заняты районами.
     */
    private void prefetch(List<List<ConnectionPoint>> partition, boolean slide) {
        if (district || !PARALLEL) {
            return;
        }
        Map<String, List<ConnectionPoint>> missing = new LinkedHashMap<>();
        for (List<ConnectionPoint> subset : partition) {
            String key = optionsKey(subset, slide);
            if (!regionByConnection.get(subset.get(0).getId()).options.containsKey(key)) {
                missing.putIfAbsent(key, subset);
            }
        }
        if (missing.size() < 2) {
            return;
        }
        List<Future<List<Option>>> computed = new ArrayList<>();
        for (List<ConnectionPoint> subset : missing.values()) {
            computed.add(PREFETCH_POOL.submit(() -> computeOptions(regionByConnection.get(subset.get(0).getId()), subset, slide)));
        }
        int i = 0;
        for (Map.Entry<String, List<ConnectionPoint>> subset : missing.entrySet()) {
            try {
                regionByConnection.get(subset.getValue().get(0).getId()).options.put(subset.getKey(), computed.get(i++).get());
            } catch (ExecutionException e) {
                // подмножество, на котором расчёт падает, упадёт так же при обычном вызове options
                log.debug("prefetch: {} не посчитано: {}", subset.getKey(), e.getCause().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Деревья подмножеств прерваны", e);
            }
        }
    }

    /** Деревья подмножества, см. {@link #options(List, boolean)}; кэш region.options не трогает. */
    private List<Option> computeOptions(Region region, List<ConnectionPoint> subset, boolean slide) {
        List<Point> points = points(subset);
        double flow = flow(subset);
        // граф по диаметру расхода подмножества со ступенью запаса, как у Region: отступы группы шире нужных ветке
        Diameter byFlow = rules.diameterFor(flow);
        Diameter step = rules.nextDiameter(byFlow.getDn());
        int blockDn = district ? region.dn : Math.min(region.dn, step != null ? step.getDn() : byFlow.getDn());
        List<TieCandidate> all = reach(finder.find(points, blockDn), points);
        List<Option> options = options(region, blockDn, region.area, subset, false, all, false, slide);
        // ОКС, не вошедшие в общее дерево, draft подключает по одному, поэтому повторы нужны только одиночным
        if (subset.size() == 1) {
            int ownDn = rules.diameterFor(oksById.get(subset.get(0).getOksId()).getFlowTph()).getDn();
            if (ownDn < blockDn) {
                List<TieCandidate> own = candidates(points, flow, ownDn);
                if (detour(plain(options), points.get(0), own)) {
                    // D-7: с запасом по диаметру маршрута нет или он в обход, а отступы для Ду по расходу меньше и
                    // могут пропустить короче: повтор с этим Ду, отступы и предельная длина — по фактическому Ду
                    options.addAll(options(region, ownDn, region.area, subset, true, own, false, slide));
                }
            }
            if (incomplete(plain(options)) && !district) {
                // обход может не поместиться в область вокруг ОКС и кандидатов: последняя попытка на широкой области
                options.addAll(options(region, blockDn, region.wideArea, subset, false,
                        candidates(points, flow, blockDn), false, slide));
            }
            if (incomplete(plain(options))) {
                // маршрута нет из-за поворота в точке выхода из здания круче 90°: трасса вдоль финального участка
                // длиннее, но неподключение при доступном маршруте запрещено (п. 2.5). Область широкая: обход
                // в тесной застройке выходит за прямоугольник вокруг точки и кандидатов
                Envelope wide = district ? region.area : region.wideArea;
                options.addAll(options(region, blockDn, wide, subset, false, candidates(points, flow, blockDn), true, slide));
            }
        }
        options.sort(Comparator.comparingDouble(option -> option.score));
        List<Option> plain = plain(options);
        if (!plain.isEmpty() && alternativeIndex(plain) == 0) {
            // все ближайшие кандидаты дают ту же врезку, а вариантов нужно не меньше двух (правило variants):
            // пробуется та же сеть дальше OTHER_TIE_M от лучшей врезки
            List<TieCandidate> along = finder.along(plain.get(0).tree.tie, blockDn, OTHER_TIE_M);
            options.addAll(options(region, blockDn, region.area, subset, false, along, false, slide));
            options.sort(Comparator.comparingDouble(option -> option.score));
            if (alternativeIndex(plain(options)) == 0) {
                // сдвиг к стволу вернул врезку к лучшей: другую врезку для второго варианта даёт дерево без сдвига
                options.addAll(options(region, blockDn, region.area, subset, false, along, false, false));
                options.sort(Comparator.comparingDouble(option -> option.score));
            }
        }
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

    /**
     * Деревья без звена после выхода. Нужны ли запасные попытки (граф Ду точки, широкая область, врезки дальше по
     * сети), решается по ним, как до звена: иначе дальнее дерево со звеном отменяло попытку, которая дала бы лучшее.
     */
    private static List<Option> plain(List<Option> options) {
        return options.stream().filter(option -> !option.linked).collect(Collectors.toList());
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
        return options(region, dn, area, subset, verify, candidates, false, true);
    }

    /** {@code slide} — переносить врезку к стволу, см. {@link #slid} и {@link #rerooted}. */
    private List<Option> options(Region region, int dn, Envelope area, List<ConnectionPoint> subset, boolean verify,
            List<TieCandidate> candidates, boolean fromPortalDirection, boolean slide) {
        String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
        // метр ветки стоит в S и как стоимость трубы, и как длина: разовый расход переводится в метры по обоим
        double metreRub = rules.diameter(dn).getNewRubM() + rules.lengthWorthRub();
        Router router = region.router(dn, area);
        List<TieCandidate> cheapest = cheapestCandidates(subset, candidates);
        TreeBuilder.Graphs graphs = graphs(region, area);
        // деревья кандидатов независимы, граф и кэш общие и потокобезопасны; порядок результатов — порядок
        // кандидатов, поэтому итог не зависит от расписания нитей. В районе города нити заняты районами.
        java.util.stream.Stream<TieCandidate> stream = district || !PARALLEL ? cheapest.stream() : cheapest.parallelStream();
        java.util.function.BiFunction<TieCandidate, Boolean, Option> shaped = (candidate, link) -> {
            // ветки к точкам, у которых по графу дерева закрыта ближняя сторона здания, идут по графу Ду своего
            // участка. Если сборка такое дерево отвергла, заново по графу дерева оно не строится: там выход ушёл бы
            // на дальнюю сторону при открытой ближней (п. 2.2)
            Tree tree = builder.build(router, dn, area, candidate, subset, rules.chamberCost(dn) / metreRub,
                    rules.tieInCost() / metreRub, fromPortalDirection, graphs, link);
            if (tree.edges.isEmpty()) {
                log.debug("options: subset={} tie={} нет дерева", label, candidate.nodeKey());
                return null;
            }
            // углы трассы срезаются по точному отступу, врезка переносится к стволу или ствол прокладывается к
            // врезке у первой камеры; из того, что соберётся, берётся лучшее по score, вплоть до дерева как построено
            Tree slid = slide ? slid(tree, router, dn, metreRub) : tree;
            Tree rerooted = slide ? rerooted(tree, router, dn, metreRub) : tree;
            // новый ствол подходит к своей трубе тоже наискось
            rerooted = rerooted == tree ? tree : slid(rerooted, router, dn, metreRub);
            Option best = null;
            for (Tree shape : new java.util.LinkedHashSet<>(List.of(slid, rerooted, tree))) {
                if (!district && shape == tree && slid != tree && best != null) {
                    // дерево как построено — запасной ход, когда формы с перенесённой врезкой не собрались: на восьми
                    // наборах выход с ним и без него один и тот же, а его сборка занимала до 4 % расчёта
                    continue;
                }
                // сначала дерево со срезанными углами; если оно не собралось — несрезанное с доведённой формой, и
                // только потом дерево как построено
                Tree cut = builder.cut(shape, router, dn, graphs, TreeBuilder.CUT_PASSES);
                Option option = cut == shape ? null
                        : option(cut, label, verify, area, dn, region, tree.narrow || cut.narrow);
                // срезка без изменений значит, что и доводка их не даст: она уже прошла с теми же рёбрами
                Tree sharp = option == null && cut != shape ? builder.cut(shape, router, dn, graphs, 0) : shape;
                option = option == null && sharp != shape
                        ? option(sharp, label, verify, area, dn, region, tree.narrow || sharp.narrow) : option;
                option = option != null ? option : option(shape, label, verify, area, dn, region, tree.narrow);
                if (option != null && (best == null || option.score < best.score)) {
                    best = option;
                }
            }
            Tree absorbed = best == null || !slide ? null : absorbed(best.tree, router, dn);
            if (absorbed != null && absorbed != best.tree) {
                // лучшая форма без первой камеры ветвления, если её ветки дешевле провести прямо из врезки. Прямые из
                // врезки — новая геометрия, поэтому форму доводит та же срезка; как построено дерево берётся, только
                // если доводке нечего менять: иначе в выход ушли бы лишние вершины или зигзаги (п. 5)
                boolean narrow = tree.narrow || absorbed.narrow;
                Tree cut = builder.cut(absorbed, router, dn, graphs, TreeBuilder.CUT_PASSES);
                Option option = cut == absorbed ? null
                        : option(cut, label, verify, area, dn, region, narrow || cut.narrow);
                Tree sharp = option != null ? null
                        : cut == absorbed ? absorbed : builder.cut(absorbed, router, dn, graphs, 0);
                option = option != null ? option : option(sharp, label, verify, area, dn, region, narrow || sharp.narrow);
                best = option != null && option.score < best.score ? option : best;
            }
            if (best != null) {
                best.linked = tree.linked;
                best.turnStuck = tree.turnStuck;
                if (!district) {
                    shapings.put(best.tree, new Shaping(router, dn, graphs,
                            shape -> option(shape, label, verify, area, dn, region, tree.narrow || shape.narrow)));
                }
            }
            return best;
        };
        // звено после выхода сразу — только у одиночной точки: в дереве нескольких точек оно присоединяло точки, которые
        // дешевле подключить отдельно, и поиск по разбиениям уходил от лучших черновиков («густо-200» хуже на 1,2)
        boolean link = subset.size() == 1;
        List<Option> options = stream.map(candidate -> {
            Option best = shaped.apply(candidate, link);
            if (best != null && best.turnStuck) {
                best.withLinks = () -> shaped.apply(candidate, true);
            }
            return best;
        }).filter(Objects::nonNull).collect(Collectors.toList());
        options.sort(Comparator.comparingDouble(option -> option.score));
        return options;
    }

    /** Ду веток по расходу и графы других Ду в области area группы region, см. {@link TreeBuilder.Graphs}. */
    private TreeBuilder.Graphs graphs(Region region, Envelope area) {
        return new TreeBuilder.Graphs() {
            @Override
            public int dn(List<ConnectionPoint> connections) {
                return rules.diameterFor(flow(connections)).getDn();
            }

            @Override
            public ObstacleSet obstacles(int otherDn) {
                return region.obstacles(otherDn, area);
            }

            @Override
            public Router router(int otherDn) {
                return region.router(otherDn, area);
            }
        };
    }

    /**
     * Выбранные варианты с перенесёнными камерами ветвления, по возрастанию score. Варианты независимы и считаются
     * параллельно. Перенос не берётся, если с ним вариант совпал бы по трассе с уже взятым, а без него не совпадал
     * (R-11).
     */
    private List<Draft> relaid(List<Draft> picked) {
        long started = System.nanoTime();
        List<Draft> moved = picked.parallelStream().map(this::relaid).collect(Collectors.toList());
        List<Draft> result = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Draft draft = moved.get(i);
            for (int j = 0; j < i && draft != picked.get(i); j++) {
                if (sameRoute(draft, result.get(j)) && !sameRoute(picked.get(i), picked.get(j))) {
                    draft = picked.get(i);
                }
            }
            result.add(draft);
        }
        log.info("relay: variants={} moved={} elapsed={}s", picked.size(), result.stream().filter(d -> !picked.contains(d)).count(),
                String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1e9));
        result.sort(Comparator.comparingDouble(Draft::score));
        return result;
    }

    /** Черновик с перенесёнными камерами ветвления у деревьев, если так он лучше по точному score; иначе прежний. */
    private Draft relaid(Draft draft) {
        List<Tree> trees = new ArrayList<>();
        for (Tree tree : draft.trees) {
            List<ConnectionPoint> subset = tree.connected();
            Option start = null;
            Region region = null;
            int dn = 0;
            String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
            if (!JunctionMover.junctions(tree).isEmpty()) {
                // дерево с камерами ветвления — от двух ОКС, его строит основной перебор computeOptions: граф по
                // расходу подмножества со ступенью запаса, область группы
                region = regionByConnection.get(subset.get(0).getId());
                Diameter byFlow = rules.diameterFor(flow(subset));
                Diameter step = rules.nextDiameter(byFlow.getDn());
                dn = Math.min(region.dn, step != null ? step.getDn() : byFlow.getDn());
                start = option(tree, label, false, region.area, dn, region, tree.narrow);
            }
            Tree moved = start == null ? tree : relaid(start, region.router(dn, region.area), dn, label, region).tree;
            Shaping shaping = shapings.get(tree);
            if (moved != tree && shaping != null) {
                // дерево с перенесёнными камерами сдвигается тем же графом и сборкой, что и дерево из перебора
                shapings.put(moved, shaping);
            }
            trees.add(moved);
        }
        for (int i = 0; i < trees.size(); i++) {
            List<Tree> others = new ArrayList<>(trees);
            others.remove(i);
            if (!compatible(trees.get(i), others)) {
                trees.set(i, draft.trees.get(i));
            }
        }
        try {
            draftCount.incrementAndGet();
            Variant variant = assembler.assemble("0", 0, trees, draft.unconnected);
            return exact(variant) < exact(draft.variant) ? new Draft(trees, draft.unconnected, variant) : draft;
        } catch (IllegalStateException | IllegalArgumentException e) {
            return draft;
        }
    }

    /**
     * Дерево с камерами ветвления, перенесёнными в окрестность ({@link JunctionMover}). Оценка переноса почти всегда
     * совпадает со сборкой, поэтому сначала все камеры переносятся в лучшие по оценке точки и дерево собирается один
     * раз. Если оно не собралось или не лучше, у каждой камеры сборкой пробуются до RELAY_TRIES точек и берётся
     * первая, где точный score меньше. Проходы по камерам повторяются, пока что-то переносится, но не больше
     * RELAY_PASSES.
     */
    private Option relaid(Option start, Router router, int dn, String label, Region region) {
        // перекладка рёбер даёт новые вершины у камеры: форма трассы доводится заново, как у дерева из перебора (п. 5)
        TreeBuilder.Graphs graphs = graphs(region, region.area);
        Tree tree = start.tree;
        // камера, которую не удалось перенести, пропускается, пока её рёбра прежние
        Map<Tree.Node, List<Tree.Edge>> stuck = new IdentityHashMap<>();
        for (int pass = 0; pass < RELAY_PASSES; pass++) {
            boolean moved = false;
            for (int j = 0; j < JunctionMover.junctions(tree).size(); j++) {
                Tree.Node junction = JunctionMover.junctions(tree).get(j);
                List<Tree.Edge> around = JunctionMover.incident(tree, junction);
                if (around.equals(stuck.get(junction))) {
                    continue;
                }
                List<Tree> moves = mover.moves(tree, junction, router.obstacles(), region.area, dn, 1);
                if (moves.isEmpty()) {
                    stuck.put(junction, around);
                } else {
                    cutCount.incrementAndGet();
                    tree = builder.cut(moves.get(0), router, dn, graphs, 0);
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        if (tree == start.tree) {
            return start;
        }
        Option all = option(tree, label, false, region.area, dn, region, tree.narrow);
        if (all != null && all.exact < start.exact - IMPROVE_EPS) {
            return all;
        }
        Option best = start;
        for (int pass = 0; pass < RELAY_PASSES; pass++) {
            boolean moved = false;
            for (int j = 0; j < JunctionMover.junctions(best.tree).size(); j++) {
                Tree.Node junction = JunctionMover.junctions(best.tree).get(j);
                for (Tree shifted : mover.moves(best.tree, junction, router.obstacles(), region.area, dn, RELAY_TRIES)) {
                    cutCount.incrementAndGet();
                    Tree candidate = builder.cut(shifted, router, dn, graphs, 0);
                    Option option = option(candidate, label, false, region.area, dn, region, candidate.narrow);
                    if (option != null && option.exact < best.exact - IMPROVE_EPS) {
                        best = option;
                        moved = true;
                        break;
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        return best;
    }

    /** S варианта без округления до трёх знаков: переносы камер меняют его в четвёртом знаке. */
    private double exact(Variant variant) {
        VariantSummary summary = variant.getSummary();
        return rules.score(summary.getCalculatedCost(), summary.getNewNetworkLength());
    }

    /**
     * Дерево с врезкой, перенесённой к вершине ствола: кандидаты врезки — проекции точек подключения, и ствол
     * подходит к сети наискось или тянется к дальней врезке мимо ближней трубы (гипотеза Q12). У каждой вершины
     * ствола пробуются проекция на трубу прежней врезки и кандидаты врезки самой вершины; берётся врезка, у которой
     * прямая до вершины вместе с остатком ствола и ценой узла врезки короче всего и хотя бы на SLIDE_MIN_M короче
     * прежнего. Прямая допустима, без спецпрохода, не идёт вдоль трубы, не ближе TREES_APART_M к другим отрезкам
     * дерева, а поворот в вершине не круче 90°. Переносится только врезка с одним ребром; иначе дерево прежнее.
     */
    private Tree slid(Tree tree, Router router, int dn, double metreRub) {
        if (tree.degree(tree.root) != 1) {
            return tree;
        }
        Tree.Edge trunk = tree.edges.stream().filter(edge -> edge.from == tree.root).findFirst().orElseThrow();
        List<LineSegment> others = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] coords = edge.line.getCoordinates();
            for (int i = 0; edge != trunk && i + 1 < coords.length; i++) {
                others.add(new LineSegment(coords[i], coords[i + 1]));
            }
        }
        Coordinate[] coords = trunk.line.getCoordinates();
        ObstacleSet obstacles = router.obstacles();
        double rest = trunk.line.getLength();
        double best = rest + tiePenalty(tree.tie, dn, metreRub) - SLIDE_MIN_M;
        TieCandidate tie = null;
        int from = 0;
        // проекция точки подключения на трубу и так среди кандидатов врезки
        int last = trunk.to.kind == Tree.Kind.CONNECTION ? coords.length - 1 : coords.length;
        for (int k = 1; k < last; k++) {
            rest -= coords[k - 1].distance(coords[k]);
            Point vertex = factory.createPoint(coords[k]);
            // врезки у самой вершины: ближайшие камеры и проекции на ближайшие участки; в районе города поиск по
            // всем камерам на каждую вершину дорог, там только своя труба
            List<TieCandidate> candidates = new ArrayList<>(district ? List.of() : finder.find(List.of(vertex), dn));
            TieCandidate same = tree.tie.isChamber() ? null : finder.onSamePipe(tree.tie, vertex, dn);
            if (same != null) {
                candidates.add(same);
            }
            for (TieCandidate candidate : candidates) {
                Coordinate at = candidate.getPoint().getCoordinate();
                double straight = at.distance(coords[k]);
                if (straight + rest + tiePenalty(candidate, dn, metreRub) >= best || straight < TreeBuilder.MIN_PIECE_M
                        || k + 1 < coords.length && Router.deflectionDeg(at, coords[k], coords[k + 1]) > Router.MAX_TURN_DEG
                        || !(obstacles.edgeWeight(at, coords[k], candidate.getIgnored()) <= straight + 1e-9)
                        || obstacles.alongIgnored(at, coords[k], candidate.getIgnored())) {
                    continue;
                }
                LineSegment segment = new LineSegment(at, coords[k]);
                boolean apart = true;
                for (LineSegment other : others) {
                    apart &= Router.apart(at, coords[k], other.p0, other.p1, TREES_APART_M)
                            || segment.distance(other) >= TREES_APART_M;
                }
                // отрезки ствола до вершины k уходят вместе с прежней врезкой, отрезок из k смежный
                for (int i = k + 1; i + 1 < coords.length; i++) {
                    apart &= Router.apart(at, coords[k], coords[i], coords[i + 1], TREES_APART_M)
                            || segment.distance(new LineSegment(coords[i], coords[i + 1])) >= TREES_APART_M;
                }
                if (apart) {
                    best = straight + rest + tiePenalty(candidate, dn, metreRub);
                    tie = candidate;
                    from = k;
                }
            }
        }
        if (tie == null) {
            return tree;
        }
        Tree result = new Tree(tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        Coordinate[] line = new Coordinate[coords.length - from + 1];
        line[0] = tie.getPoint().getCoordinate();
        System.arraycopy(coords, from, line, 1, coords.length - from);
        for (Tree.Edge edge : tree.edges) {
            result.edges.add(edge == trunk ? new Tree.Edge(result.root, trunk.to, factory.createLineString(line)) : edge);
        }
        return result;
    }

    /**
     * Дерево со стволом, проложенным заново от первой камеры ветвления к лучшей из врезок у неё: ближайших камер
     * и проекций на ближайшие участки. Кандидаты врезки дерева — проекции точек подключения, а ствол от камеры
     * ветвления до них бывает длиннее пути к трубе рядом с самой камерой. Маршрут берётся по графу, если с ценой
     * узла врезки он хотя бы на SLIDE_MIN_M короче прежнего ствола; остальное проверяет сборка.
     */
    private Tree rerooted(Tree tree, Router router, int dn, double metreRub) {
        if (district || tree.degree(tree.root) != 1) {
            return tree;
        }
        Tree.Edge trunk = tree.edges.stream().filter(edge -> edge.from == tree.root).findFirst().orElseThrow();
        if (trunk.to.kind != Tree.Kind.JUNCTION) {
            return tree;
        }
        Point junction = factory.createPoint(trunk.to.point);
        double best = trunk.line.getLength() + tiePenalty(tree.tie, dn, metreRub) - SLIDE_MIN_M;
        TieCandidate tie = null;
        Coordinate[] line = null;
        for (TieCandidate candidate : finder.find(List.of(junction), dn)) {
            // маршрут не короче прямой: дальние врезки не ищутся
            if (candidate.nodeKey().equals(tree.tie.nodeKey())
                    || junction.distance(candidate.getPoint()) + tiePenalty(candidate, dn, metreRub) >= best) {
                continue;
            }
            Route route = router.routeToAny(junction, List.of(candidate.getPoint()), candidate.getIgnored(), true);
            if (route == null || route.getWeight() + tiePenalty(candidate, dn, metreRub) >= best) {
                continue;
            }
            Coordinate[] coords = route.getGeometry().getCoordinates();
            Coordinate[] reversed = new Coordinate[coords.length];
            for (int i = 0; i < coords.length; i++) {
                reversed[i] = coords[coords.length - 1 - i];
            }
            if (router.obstacles().alongIgnored(reversed[0], reversed[1], candidate.getIgnored())) {
                continue;
            }
            best = route.getWeight() + tiePenalty(candidate, dn, metreRub);
            tie = candidate;
            line = reversed;
        }
        if (tie == null) {
            return tree;
        }
        line[0] = tie.getPoint().getCoordinate();
        line[line.length - 1] = trunk.to.point;
        Tree result = new Tree(tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        for (Tree.Edge edge : tree.edges) {
            result.edges.add(edge == trunk ? new Tree.Edge(result.root, trunk.to, factory.createLineString(line)) : edge);
        }
        return result;
    }

    /**
     * Дерево, в котором новая камера врезки на трубе заменяет первую камеру ветвления: к камере на трубе примыкают
     * две части трубы и два новых участка (п. 2.1), и обе ветки камеры ветвления идут прямо из врезки, а сама она не
     * строится. Новая камера ставится на трубе прежней врезки, а вместо врезки в существующую камеру — на ближайших к
     * камере ветвления трубах, у проекции камеры и со сдвигом вдоль оси. Ветка идёт прямой от врезки к одной из своих
     * вершин и дальше как была; финальный участок к точке подключения не меняется, прямая проверяется как у
     * {@link #slid}. Камера убирается, если по цене метра Ду расхода, камеры и врезки так дешевле; остальное проверяет
     * сборка.
     */
    private Tree absorbed(Tree tree, Router router, int dn) {
        if (district || tree.degree(tree.root) != 1) {
            return tree;
        }
        Tree.Edge trunk = tree.edges.stream().filter(edge -> edge.from == tree.root).findFirst().orElseThrow();
        List<Tree.Edge> children = tree.edges.stream().filter(edge -> edge.from == trunk.to).collect(Collectors.toList());
        if (trunk.to.kind != Tree.Kind.JUNCTION || children.size() != 2) {
            return tree;
        }
        Coordinate junction = trunk.to.point;
        List<TieCandidate> bases = !tree.tie.isChamber() ? List.of(tree.tie)
                : finder.find(List.of(factory.createPoint(junction)), dn);
        Map<String, TieCandidate> ties = new LinkedHashMap<>();
        ties.put(tree.tie.nodeKey(), tree.tie);
        for (TieCandidate base : bases) {
            for (double shift : ABSORB_SHIFTS_M) {
                TieCandidate tie = base.isChamber() ? null : finder.shifted(base, junction, shift, dn);
                if (tie != null) {
                    ties.putIfAbsent(tie.nodeKey(), tie);
                }
            }
        }
        List<LineSegment> others = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] coords = edge.line.getCoordinates();
            for (int i = 0; edge != trunk && !children.contains(edge) && i + 1 < coords.length; i++) {
                others.add(new LineSegment(coords[i], coords[i + 1]));
            }
        }
        double flow = flow(below(tree, trunk.to));
        int flowDn = rules.diameterFor(flow).getDn();
        double[] metreRub = new double[2];
        double best = tieCost(tree.tie, flowDn) + trunk.line.getLength() * metreRub(flow) + rules.chamberCost(flowDn);
        for (int c = 0; c < 2; c++) {
            metreRub[c] = metreRub(flow(below(tree, children.get(c).to)));
            best += children.get(c).line.getLength() * metreRub[c];
        }
        ObstacleSet obstacles = router.obstacles();
        TieCandidate bestTie = null;
        Coordinate[][] bestLines = null;
        for (TieCandidate tie : ties.values()) {
            if (tie.isChamber() || tie.getCapacity() < 2) {
                continue;
            }
            List<Coordinate[]> first = branches(children.get(0), tie);
            List<Coordinate[]> second = branches(children.get(1), tie);
            if (first.isEmpty() || second.isEmpty()) {
                continue;
            }
            // ветки по возрастанию длины: дорогая проверка прямой только у тех, что ещё могут быть дешевле лучшей
            double cheapestB = length(second.get(0)) * metreRub[1];
            Map<Coordinate[], Boolean> checked = new IdentityHashMap<>();
            for (Coordinate[] a : first) {
                double rubA = tieCost(tie, flowDn) + length(a) * metreRub[0];
                if (rubA + cheapestB >= best) {
                    break;
                }
                if (!checked.computeIfAbsent(a, line -> clear(line, tie, obstacles, others))) {
                    continue;
                }
                for (Coordinate[] b : second) {
                    double rub = rubA + length(b) * metreRub[1];
                    if (rub >= best) {
                        break;
                    }
                    if (checked.computeIfAbsent(b, line -> clear(line, tie, obstacles, others)) && apart(a, b)) {
                        best = rub;
                        bestTie = tie;
                        bestLines = new Coordinate[][] {a, b};
                    }
                }
            }
        }
        if (bestTie == null) {
            return tree;
        }
        Tree result = new Tree(bestTie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        for (Tree.Edge edge : tree.edges) {
            int c = children.indexOf(edge);
            if (c >= 0) {
                result.edges.add(new Tree.Edge(result.root, edge.to, factory.createLineString(bestLines[c])));
            } else if (edge != trunk) {
                result.edges.add(edge);
            }
        }
        return result;
    }

    private double metreRub(double flow) {
        return rules.diameterFor(flow).getNewRubM() + rules.lengthWorthRub();
    }

    /**
     * Ветки из врезки tie вместо ребра edge из камеры ветвления по возрастанию длины: прямая к вершине ребра, включая
     * саму камеру ветвления, и остаток ребра от этой вершины; поворот в вершине не круче 90°. Финальный участок к
     * точке подключения остаётся целым. Допустимость прямой проверяет {@link #clear}.
     */
    private static List<Coordinate[]> branches(Tree.Edge edge, TieCandidate tie) {
        Coordinate at = tie.getPoint().getCoordinate();
        Coordinate[] coords = edge.line.getCoordinates();
        int last = edge.to.kind == Tree.Kind.CONNECTION ? coords.length - 2 : coords.length - 1;
        List<Coordinate[]> result = new ArrayList<>();
        for (int k = 0; k <= last; k++) {
            if (at.distance(coords[k]) < TreeBuilder.MIN_PIECE_M) {
                continue;
            }
            if (k + 1 < coords.length) {
                double deflection = Router.deflectionDeg(at, coords[k], coords[k + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < TreeBuilder.MIN_TURN_DEG) {
                    continue;
                }
            }
            Coordinate[] line = new Coordinate[coords.length - k + 1];
            line[0] = at;
            System.arraycopy(coords, k, line, 1, coords.length - k);
            result.add(line);
        }
        result.sort(Comparator.comparingDouble(VariantEnumerator::length));
        return result;
    }

    /**
     * Первая прямая ветки из {@link #branches} допустима, как у {@link #slid}: без спецпрохода, не вдоль трубы врезки,
     * не ближе TREES_APART_M к отрезкам others и к остатку своей ветки.
     */
    private static boolean clear(Coordinate[] line, TieCandidate tie, ObstacleSet obstacles, List<LineSegment> others) {
        Set<String> ignored = tie.getIgnored();
        if (!(obstacles.edgeWeight(line[0], line[1], ignored) <= line[0].distance(line[1]) + 1e-9)
                || obstacles.alongIgnored(line[0], line[1], ignored)) {
            return false;
        }
        LineSegment segment = new LineSegment(line[0], line[1]);
        for (LineSegment other : others) {
            if (!Router.apart(line[0], line[1], other.p0, other.p1, TREES_APART_M)
                    && segment.distance(other) < TREES_APART_M) {
                return false;
            }
        }
        for (int i = 2; i + 1 < line.length; i++) {
            if (!Router.apart(line[0], line[1], line[i], line[i + 1], TREES_APART_M)
                    && segment.distance(new LineSegment(line[i], line[i + 1])) < TREES_APART_M) {
                return false;
            }
        }
        return true;
    }

    /** Ветки a и b из общей врезки не ближе TREES_APART_M друг к другу вне общей точки. */
    private static boolean apart(Coordinate[] a, Coordinate[] b) {
        LineSegment firstA = new LineSegment(a[0], a[1]);
        LineSegment firstB = new LineSegment(b[0], b[1]);
        if (firstA.distance(b[1]) < TREES_APART_M || firstB.distance(a[1]) < TREES_APART_M) {
            return false;
        }
        for (int i = 0; i + 1 < a.length; i++) {
            for (int j = i == 0 ? 1 : 0; j + 1 < b.length; j++) {
                if (!Router.apart(a[i], a[i + 1], b[j], b[j + 1], TREES_APART_M)
                        && new LineSegment(a[i], a[i + 1]).distance(new LineSegment(b[j], b[j + 1])) < TREES_APART_M) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double length(Coordinate[] line) {
        double length = 0;
        for (int i = 0; i + 1 < line.length; i++) {
            length += line[i].distance(line[i + 1]);
        }
        return length;
    }

    /** Точки подключения ниже узла node. */
    private static List<ConnectionPoint> below(Tree tree, Tree.Node node) {
        List<ConnectionPoint> result = new ArrayList<>();
        if (node.kind == Tree.Kind.CONNECTION) {
            result.add(node.connection);
        }
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == node) {
                result.addAll(below(tree, edge.to));
            }
        }
        return result;
    }

    /** Цена узла врезки в метрах ветки: врезка в камеру или новая камера на трубе. */
    private double tiePenalty(TieCandidate tie, int dn, double metreRub) {
        return (tie.isChamber() ? rules.tieInCost() : rules.chamberCost(dn)) / metreRub;
    }

    /** Цена узла врезки: врезка в камеру или новая камера на трубе по наибольшему Ду, включая саму трубу (п. 2.1). */
    private double tieCost(TieCandidate tie, int dn) {
        return tie.isChamber() ? rules.tieInCost() : rules.chamberCost(Math.max(dn, tie.getExistingDiameter()));
    }

    /**
     * Дерево, собранное отдельным вариантом, со своим score; null, если сборка его отбросила. Если фактический ДУ
     * участков (по предельной длине пути) больше ДУ графа, отступы проверяются заново для него; у дерева с веткой
     * по графу меньшего Ду ({@code narrow}) — по Ду каждого участка.
     */
    private Option option(Tree tree, String label, boolean verify, Envelope area, int graphDn, Region region, boolean narrow) {
        Set<String> ids = new HashSet<>();
        tree.unconnected.forEach(connection -> ids.add(connection.getOksId()));
        optionCount.incrementAndGet();
        try {
            Variant alone = assembler.assemble("0", 0, List.of(tree), unconnected(ids));
            log.debug("options: subset={} tie={} score={} unconnected={}", label, tree.tie.nodeKey(),
                    alone.getSummary().getScore(), tree.unconnected.size());
            int maxDn = alone.getSegments().stream().mapToInt(NewSegment::getDiameter).max().orElse(graphDn);
            boolean check = verify || maxDn > graphDn;
            boolean holds = narrow ? forbidClear(tree, alone, area, region) : !check || clearanceHolds(tree, alone, area, region);
            return holds && raysClear(tree, alone) ? new Option(tree, alone.getSummary().getScore(), exact(alone)) : null;
        } catch (IllegalStateException | IllegalArgumentException e) {
            // дерево нарушает правила при сборке (предельная длина, отступ участка, число поворотов): кандидат отбрасывается
            log.debug("options: subset={} tie={} отброшено: {}", label, tree.tie.nodeKey(), e.getMessage());
            return null;
        }
    }

    /**
     * Черновик, где в деревьях камеры ветвления сдвинуты вдоль рёбер ({@link TreeBuilder#slides}), пока сдвиг
     * снижает score дерева, собранного отдельно; сдвинутое дерево не касается других деревьев черновика. Берётся,
     * если весь черновик собирается и его score ниже; иначе черновик прежний.
     */
    private Draft shifted(Draft draft) {
        long started = System.nanoTime();
        List<Tree> trees = new ArrayList<>(draft.trees);
        boolean changed = false;
        for (int i = 0; i < trees.size(); i++) {
            Shaping shaping = shapings.get(trees.get(i));
            if (shaping == null) {
                continue;
            }
            List<Tree> others = new ArrayList<>(trees);
            others.remove(i);
            Tree tree = shifted(trees.get(i), shaping, others);
            changed |= tree != trees.get(i);
            trees.set(i, tree);
        }
        if (!changed) {
            return draft;
        }
        try {
            draftCount.incrementAndGet();
            Draft result = new Draft(trees, draft.unconnected, assembler.assemble("0", 0, trees, draft.unconnected));
            log.info("slide: score {} -> {} elapsed={}ms", draft.score(), result.score(),
                    (System.nanoTime() - started) / 1_000_000);
            return result.score() < draft.score() - IMPROVE_EPS ? result : draft;
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.debug("slide: черновик не собрался: {}", e.getMessage());
            return draft;
        }
    }

    /**
     * Сдвиги камер одного дерева: лучший по оценке сдвиг, который снижает score, принимается, и сдвиги ищутся на
     * новом дереве снова. Сдвиг, который score не снизил, больше не собирается.
     */
    private Tree shifted(Tree tree, Shaping shaping, List<Tree> others) {
        Map<List<Tree.Edge>, TreeBuilder.Slide> cache = new HashMap<>();
        Set<TreeBuilder.Slide> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
        Option current = null;
        while (true) {
            Tree shape = current == null ? tree : current.tree;
            List<TreeBuilder.Slide> slides = builder.slides(shape, shaping.router.obstacles(), shaping.dn, metreRub(shape),
                    SLIDE_MIN_GAIN_RUB, cache, PARALLEL);
            if (current == null && !slides.isEmpty()) {
                // score дерева нужен, только если есть что сдвигать
                current = shaping.option.apply(tree);
            }
            Option better = null;
            for (TreeBuilder.Slide slide : current == null ? List.<TreeBuilder.Slide>of() : slides) {
                if (rejected.contains(slide)) {
                    continue;
                }
                // новые рёбра камеры доводятся до строгой формы, как дерево в options: сдвиг меняет изломы и соседей
                cutCount.incrementAndGet();
                Tree moved = builder.cut(builder.moved(current.tree, slide), shaping.router, shaping.dn, shaping.graphs, 0);
                Option option = compatible(moved, others) ? shaping.option.apply(moved) : null;
                if (option != null && option.score < current.score - IMPROVE_EPS) {
                    better = option;
                    break;
                }
                rejected.add(slide);
            }
            if (better == null) {
                return current == null ? tree : current.tree;
            }
            current = better;
        }
    }

    private void resetWork() {
        optionCount.set(0);
        cutCount.set(0);
        draftCount.set(0);
    }

    private String work() {
        return "options=" + optionCount + " cuts=" + cutCount + " drafts=" + draftCount;
    }

    /** Цена метра каждого ребра в рублях: труба по расходу ниже ребра и метр длины по весам S. */
    private Map<Tree.Edge, Double> metreRub(Tree tree) {
        Map<Tree.Node, Double> flowBelow = new IdentityHashMap<>();
        Map<Tree.Edge, Double> result = new IdentityHashMap<>();
        for (Tree.Edge edge : tree.edges) {
            double flow = flowBelow(tree, edge.to, flowBelow);
            result.put(edge, rules.diameterFor(flow).getNewRubM() + rules.lengthWorthRub());
        }
        return result;
    }

    private double flowBelow(Tree tree, Tree.Node node, Map<Tree.Node, Double> memo) {
        Double known = memo.get(node);
        if (known != null) {
            return known;
        }
        double flow = node.kind == Tree.Kind.CONNECTION ? oksById.get(node.connection.getOksId()).getFlowTph() : 0;
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == node) {
                flow += flowBelow(tree, edge.to, memo);
            }
        }
        memo.put(node, flow);
        return flow;
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

    /**
     * Рёбра деревьев варианта, проложенные заново по графу фактического Ду ребра между теми же узлами: граф блока
     * строится по Ду ствола со ступенью запаса, и тонкая ветка обходит здания с отступом толстой трубы. Выход из
     * здания ставится по тому же Ду, ближе к стене. Рёбра дерева заменяются все сразу, а если так его узел врезки
     * не собирается, по одному. Новые рёбра доводятся до строгой формы, см. {@link #sharpened}. Замена принимается,
     * если S узла ниже, дерево не ближе TREES_APART_M к другим, а Ду рёбер не выше прежних: маршрут держит отступы
     * графа, по которому проложен.
     */
    private List<Tree> rerouted(Draft draft) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(draft.trees);
        Map<Tree.Edge, Integer> dnByEdge = assembler.diameters(result);
        List<int[]> edges = new ArrayList<>();
        for (int t = 0; t < result.size(); t++) {
            for (int e = 0; e < result.get(t).edges.size(); e++) {
                edges.add(new int[] {t, e});
            }
        }
        // рёбра независимы, графы только читаются; новые графы здесь не строятся, зоны Ду ребра для доводки формы
        // берутся из кэша области (обычно их уже построила срезка дерева)
        java.util.stream.Stream<int[]> stream = PARALLEL ? edges.parallelStream() : edges.stream();
        List<Fresh> lines = stream.map(at -> {
            Tree tree = draft.trees.get(at[0]);
            Tree.Edge edge = tree.edges.get(at[1]);
            return rerouted(tree, edge, dnByEdge.get(edge));
        }).collect(Collectors.toList());
        Map<String, Double> scoreByRoot = new HashMap<>();
        int taken = 0;
        for (int t = 0, k = 0; t < result.size(); k += result.get(t).edges.size(), t++) {
            Map<Integer, LineString> fresh = new LinkedHashMap<>();
            Map<Integer, Fresh> found = new HashMap<>();
            for (int e = 0; e < result.get(t).edges.size(); e++) {
                if (lines.get(k + e) != null) {
                    fresh.put(e, lines.get(k + e).line);
                    found.put(e, lines.get(k + e));
                }
            }
            List<Map<Integer, LineString>> changes = new ArrayList<>(fresh.isEmpty() ? List.of() : List.of(fresh));
            for (int e : fresh.size() > 1 ? fresh.keySet() : Set.<Integer>of()) {
                changes.add(Map.of(e, fresh.get(e)));
            }
            for (Map<Integer, LineString> change : changes) {
                Tree tree = result.get(t);
                Map<Integer, LineString> sharp = sharpened(tree, change, found, false);
                if (sharp == null || !apart(sharp.values(), result, tree)) {
                    continue;
                }
                Tree changed = replaced(tree, sharp);
                // деревья других узлов врезки на S и Ду узла не влияют
                List<Tree> unit = new ArrayList<>();
                List<Tree> attempt = new ArrayList<>();
                for (Tree other : result) {
                    if (other.root.key.equals(tree.root.key)) {
                        unit.add(other);
                        attempt.add(other == tree ? changed : other);
                    }
                }
                double before = scoreByRoot.computeIfAbsent(tree.root.key, key -> unitScore(unit));
                double after = unitScore(attempt);
                if (!(after < before - IMPROVE_EPS) || thicker(tree, unit, changed, attempt)) {
                    continue;
                }
                scoreByRoot.put(tree.root.key, after);
                result.set(t, changed);
                taken += change.size();
                if (change == fresh) {
                    break;
                }
            }
        }
        log.info("rerouted: edges={} elapsed={}ms", taken, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /** Новые рёбра дерева tree не ближе TREES_APART_M к другим деревьям варианта. */
    private static boolean apart(java.util.Collection<LineString> lines, List<Tree> trees, Tree tree) {
        for (LineString line : lines) {
            for (Tree other : trees) {
                // рамки дальше порога — геометрии тем более
                if (other != tree && near(line.getEnvelopeInternal(), other.envelope())
                        && geometry(other).distance(line) <= TREES_APART_M) {
                    return false;
                }
            }
        }
        return true;
    }

    /** S узла врезки из деревьев {@code unit} без штрафа; NaN — узел не собирается. */
    private double unitScore(List<Tree> unit) {
        try {
            VariantSummary summary = assembler.assemble("0", 0, unit, List.of()).getSummary();
            return rules.score(summary.getCalculatedCost(), summary.getNewNetworkLength());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.debug("rerouted: {} не собрано: {}", unit.get(0).root.key, e.getMessage());
            return Double.NaN;
        }
    }

    /** Ду какого-то ребра дерева {@code changed} в узле {@code after} выше, чем у {@code tree} в узле {@code before}. */
    private boolean thicker(Tree tree, List<Tree> before, Tree changed, List<Tree> after) {
        Map<Tree.Edge, Integer> dnBefore = assembler.diameters(before);
        Map<Tree.Edge, Integer> dnAfter = assembler.diameters(after);
        for (int e = 0; e < tree.edges.size(); e++) {
            if (dnAfter.get(changed.edges.get(e)) > dnBefore.get(tree.edges.get(e))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ребро по графу своего Ду {@code dn} или ближайшего большего из уже построенных для области дерева: новый граф
     * ради одного ребра не строится. null — такого графа нет, прямое ребро, маршрут не легче прежнего или от врезки
     * идёт вдоль её трубы.
     */
    private Fresh rerouted(Tree tree, Tree.Edge edge, int dn) {
        Coordinate[] old = edge.line.getCoordinates();
        Region region = regionByConnection.get(tree.connected().get(0).getId());
        Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
        if (old.length == 2 || !area.contains(tree.envelope())) {
            // прямое ребро короче любого маршрута, а графов вне области нет
            return null;
        }
        Router router = null;
        for (Diameter graph = rules.diameter(dn); router == null && graph != null;
                graph = rules.nextDiameter(graph.getDn())) {
            router = region.routers.get(graph.getDn() + "@" + area);
        }
        if (router == null) {
            return null;
        }
        Set<String> ignored = tree.tie.getIgnored();
        boolean portal = edge.to.kind == Tree.Kind.CONNECTION
                && buildingByConnection.containsKey(edge.to.connection.getId());
        double before = ObstacleSet.weight(edge.line.getLength(), router.obstacles().spans(edge.line, ignored));
        Coordinate end = portal ? old[old.length - 2] : old[old.length - 1];
        Coordinate closer = portal ? builder.exit(router, dn, area, tree.tie, edge.to.connection) : null;
        // выход ставится по зонам графа большего Ду, а из зоны отступа по Ду ребра луч тоже выходит один раз
        if (closer != null && !builder.leavesZoneOnce(buildingByConnection.get(edge.to.connection.getId()),
                old[old.length - 1], closer, oksClearance(dn))) {
            closer = null;
        }
        for (Coordinate exit : closer == null || closer.equals2D(end) ? List.of(end) : List.of(closer, end)) {
            Route route = router.routeToAny(factory.createPoint(old[0]), List.of(factory.createPoint(exit)), ignored,
                    true);
            double tail = portal ? exit.distance(old[old.length - 1]) : 0;
            if (route == null || route.getWeight() + tail >= before - IMPROVE_EPS) {
                continue;
            }
            Coordinate[] path = route.getGeometry().getCoordinates();
            Coordinate[] line = portal ? java.util.Arrays.copyOf(path, path.length + 1) : path;
            line[0] = old[0];
            line[path.length - 1] = exit;
            line[line.length - 1] = old[old.length - 1];
            if (edge.from == tree.root && !builder.leavesNetwork(new LineSegment(line[1], line[0]), ignored)) {
                continue;
            }
            return new Fresh(factory.createLineString(line), router, region.obstacles(dn, area));
        }
        return null;
    }

    /**
     * Новые рёбра change дерева tree в строгой форме, как у {@link TreeBuilder#cut}: {@link Router#sharpen} по зонам
     * фактического Ду ребра из found, новые куски не ближе 0,5 м к другим рёбрам дерева, выход из здания на месте
     * (п. 2.2). Пока форма меняется, новые рёбра сравниваются друг с другом в новом виде. null — ребро от врезки
     * после этого идёт вдоль её трубы или в ребре осталась вершина, которую без соседа можно убрать
     * ({@link Router#loose}). При {@code pathTurns} убранная вершина не делает поворот в камере ветвления на пути точки
     * к врезке круче MAX_TURN_DEG, см. {@link Router#sharpen(ObstacleSet, List, Set, Coordinate, List, Coordinate, List)}.
     */
    private Map<Integer, LineString> sharpened(Tree tree, Map<Integer, LineString> change, Map<Integer, Fresh> found,
            boolean pathTurns) {
        List<LineString> lines = new ArrayList<>();
        for (int e = 0; e < tree.edges.size(); e++) {
            lines.add(change.getOrDefault(e, tree.edges.get(e).line));
        }
        Set<String> ignored = tree.tie.getIgnored();
        Set<Integer> loose = new HashSet<>();
        boolean again = true;
        while (again) {
            // каждая замена убирает вершину, поэтому цикл конечен
            again = false;
            for (int e : change.keySet()) {
                List<LineSegment> others = new ArrayList<>();
                for (int o = 0; o < lines.size(); o++) {
                    Coordinate[] coords = lines.get(o).getCoordinates();
                    for (int i = 0; o != e && i + 1 < coords.length; i++) {
                        others.add(new LineSegment(coords[i], coords[i + 1]));
                    }
                }
                Tree.Edge edge = tree.edges.get(e);
                List<Coordinate> coords = new ArrayList<>(java.util.Arrays.asList(lines.get(e).getCoordinates()));
                Coordinate exit = edge.to.kind == Tree.Kind.CONNECTION
                        && buildingByConnection.containsKey(edge.to.connection.getId()) && coords.size() > 2
                        ? coords.get(coords.size() - 2) : null;
                Router router = found.get(e).router;
                Coordinate before = null;
                List<Coordinate> after = new ArrayList<>();
                for (int o = 0; pathTurns && o < lines.size(); o++) {
                    Tree.Edge other = tree.edges.get(o);
                    Coordinate[] c = lines.get(o).getCoordinates();
                    if (edge.from.kind == Tree.Kind.JUNCTION && other.to == edge.from) {
                        before = c[c.length - 2];
                    }
                    if (edge.to.kind == Tree.Kind.JUNCTION && other.from == edge.to) {
                        after.add(c[1]);
                    }
                }
                if (router.sharpen(found.get(e).zones, coords, ignored, exit, others, before, after)) {
                    lines.set(e, factory.createLineString(coords.toArray(new Coordinate[0])));
                    again = change.size() > 1 || pathTurns;
                }
                if (router.loose(found.get(e).zones, coords, ignored, exit, others, before, after)) {
                    loose.add(e);
                } else {
                    loose.remove(e);
                }
            }
        }
        if (!loose.isEmpty()) {
            return null;
        }
        Map<Integer, LineString> result = new LinkedHashMap<>();
        for (int e : change.keySet()) {
            Coordinate[] coords = lines.get(e).getCoordinates();
            if (tree.edges.get(e).from == tree.root
                    && !builder.leavesNetwork(new LineSegment(coords[1], coords[0]), ignored)) {
                return null;
            }
            result.put(e, lines.get(e));
        }
        return result;
    }

    /**
     * Ребро, проложенное заново по графу router; zones — зоны фактического Ду ребра: по ним форма доводится, как у
     * TreeBuilder#cut для ветки тоньше графа, и проверщик меряет отступы по Ду участка.
     */
    private static final class Fresh {
        final LineString line;
        final Router router;
        final ObstacleSet zones;

        Fresh(LineString line, Router router, ObstacleSet zones) {
            this.line = line;
            this.router = router;
            this.zones = zones;
        }
    }

    /**
     * Деревья варианта без изломов у камер ветвления, которые снимает перенос камеры в точку рядом
     * ({@link TreeBuilder#unkinks}, п. 5), и у новой камеры врезки на трубе, которые снимает её сдвиг вдоль трубы
     * ({@link #retied}). Рёбра камеры проверяются по зонам своего Ду, как в {@link #rerouted}, и доводятся до строгой
     * формы, где убранная вершина не делает поворот в камере круче ({@link #sharpened}). Перенос берётся, если S узла
     * врезки вырос не больше TURN_TOLERANCE_S, Ду рёбер не выросли, а дерево не касается других; дальше изломы
     * ищутся на новом дереве. Каждый перенос убирает вершину, поэтому цикл конечен.
     */
    private List<Tree> unkinked(List<Tree> trees) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        double maxGainRub = TURN_TOLERANCE_S / rules.score(1, 0);
        for (int t = 0; t < result.size(); t++) {
            Tree first = result.get(t);
            if (JunctionMover.junctions(first).isEmpty() && first.tie.isChamber()) {
                continue;
            }
            Region region = regionByConnection.get(first.connected().get(0).getId());
            Envelope area = region.area.contains(first.envelope()) ? region.area : region.wideArea;
            // перенос, который не взят, у той же камеры не пробуется снова, как и камера с теми же рёбрами без переноса
            Set<List<Double>> tried = new HashSet<>();
            Set<List<Double>> settled = new HashSet<>();
            boolean retieTried = false;
            for (boolean moved = area.contains(first.envelope()); moved; ) {
                moved = false;
                Tree tree = result.get(t);
                List<Tree> unit = new ArrayList<>();
                for (Tree other : result) {
                    if (other.root.key.equals(tree.root.key)) {
                        unit.add(other);
                    }
                }
                Map<Tree.Edge, Integer> dnByEdge;
                try {
                    dnByEdge = assembler.diameters(unit);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    break;
                }
                Map<Tree.Edge, Double> priceRub = new IdentityHashMap<>();
                dnByEdge.forEach((edge, dn) -> priceRub.put(edge, rules.diameter(dn).getNewRubM() + rules.lengthWorthRub()));
                // S узла собирается, только когда есть перенос, который проходит проверки: у большинства деревьев его нет
                double before = Double.NaN;
                for (Tree.Node junction : JunctionMover.junctions(tree)) {
                    List<Double> key = signature(tree, junction);
                    if (settled.contains(key)) {
                        continue;
                    }
                    for (TreeBuilder.Slide slide : builder.unkinks(tree, junction,
                            edge -> region.obstacles(dnByEdge.get(edge), area), dnByEdge, priceRub, maxGainRub)) {
                        if (!tried.add(List.of(junction.point.x, junction.point.y, slide.point.x, slide.point.y))) {
                            continue;
                        }
                        Tree changed = unkinked(tree, slide, tree.tie, dnByEdge, region, area, true);
                        List<Tree> others = new ArrayList<>(result);
                        others.remove(t);
                        if (changed == null || !compatible(changed, others)) {
                            continue;
                        }
                        List<Tree> attempt = new ArrayList<>();
                        for (Tree other : unit) {
                            attempt.add(other == tree ? changed : other);
                        }
                        before = Double.isNaN(before) ? unitScore(unit) : before;
                        if (!(unitScore(attempt) <= before + TURN_TOLERANCE_S) || thicker(tree, unit, changed, attempt)) {
                            continue;
                        }
                        result.set(t, changed);
                        taken++;
                        moved = true;
                        break;
                    }
                    if (moved) {
                        break;
                    }
                    settled.add(key);
                }
                // врезка сдвигается, когда у камер ветвления сдвигов больше нет: одна попытка на дерево
                if (!moved && !retieTried && unit.size() == 1) {
                    retieTried = true;
                    List<Tree> others = new ArrayList<>(result);
                    others.remove(t);
                    Tree changed = retied(tree, dnByEdge, priceRub, region, area, others);
                    if (changed != null) {
                        result.set(t, changed);
                        taken++;
                        moved = true;
                    }
                }
            }
        }
        log.info("unkinked: moves={} elapsed={}ms", taken, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /** Координаты рёбер камеры junction: камера с теми же рёбрами без переноса не пробуется снова, см. {@link #unkinked(List)}. */
    private static List<Double> signature(Tree tree, Tree.Node junction) {
        List<Double> key = new ArrayList<>();
        for (Tree.Edge edge : JunctionMover.incident(tree, junction)) {
            for (Coordinate c : edge.line.getCoordinates()) {
                key.add(c.x);
                key.add(c.y);
            }
        }
        return key;
    }

    /**
     * Деревья варианта без поворотов круче MAX_TURN_DEG в камерах ветвления на пути точки к врезке (п. 2.1, разъяснение
     * 5), которые снимает правка у камеры: сдвиг камеры вдоль первого звена одного из её рёбер
     * ({@link TreeBuilder#turnSlides}) или излом звена ребра у камеры ({@link TreeBuilder#bends}); правки пробуются по
     * возрастанию цены рёбер. Поиск таких поворотов не проверяет, проход правит готовые варианты. Рёбра камеры
     * доводятся до строгой формы, где убранная вершина не делает поворот в камере круче ({@link #sharpened}). Правка
     * берётся, если таких поворотов в дереве стало меньше, S узла врезки вырос не больше TURN_TOLERANCE_S, Ду рёбер не
     * выросли, а дерево не касается других; дальше повороты ищутся на новом дереве. Места, которые проход не снял,
     * пишутся в лог.
     */
    private List<Tree> turned(List<Tree> trees) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        double maxGainRub = TURN_TOLERANCE_S / rules.score(1, 0);
        for (int t = 0; t < result.size(); t++) {
            Tree first = result.get(t);
            Region region = regionByConnection.get(first.connected().get(0).getId());
            Envelope area = region.area.contains(first.envelope()) ? region.area : region.wideArea;
            for (int sharp = sharpTurns(first).size(); sharp > 0 && area.contains(first.envelope()); ) {
                Tree tree = result.get(t);
                List<Tree> unit = new ArrayList<>();
                for (Tree other : result) {
                    if (other.root.key.equals(tree.root.key)) {
                        unit.add(other);
                    }
                }
                Map<Tree.Edge, Integer> dnByEdge;
                try {
                    dnByEdge = assembler.diameters(unit);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    break;
                }
                Map<Tree.Edge, Double> priceRub = new IdentityHashMap<>();
                dnByEdge.forEach((edge, dn) -> priceRub.put(edge, rules.diameter(dn).getNewRubM() + rules.lengthWorthRub()));
                double before = unitScore(unit);
                List<Tree> others = new ArrayList<>(result);
                others.remove(t);
                Tree changed = null;
                for (Tree.Node junction : sharpTurns(tree)) {
                    java.util.function.Function<Tree.Edge, ObstacleSet> zones = edge -> region.obstacles(dnByEdge.get(edge), area);
                    List<TreeBuilder.Slide> slides = new ArrayList<>(builder.turnSlides(tree, junction, zones, dnByEdge,
                            priceRub, maxGainRub, TURN_STEP_M, TURN_MAX_M));
                    slides.addAll(builder.bends(tree, junction, zones, priceRub, maxGainRub));
                    slides.sort(Comparator.comparingDouble(slide -> slide.gain));
                    for (TreeBuilder.Slide slide : slides) {
                        Tree attempt = unkinked(tree, slide, tree.tie, dnByEdge, region, area, true);
                        if (attempt == null || sharpTurns(attempt).size() >= sharp || !compatible(attempt, others)) {
                            continue;
                        }
                        List<Tree> after = new ArrayList<>();
                        for (Tree other : unit) {
                            after.add(other == tree ? attempt : other);
                        }
                        if (unitScore(after) <= before + TURN_TOLERANCE_S && !thicker(tree, unit, attempt, after)) {
                            changed = attempt;
                            break;
                        }
                    }
                    if (changed != null) {
                        break;
                    }
                }
                if (changed == null) {
                    sharpTurns(tree).forEach(junction -> log.info("turned: поворот в камере {} не снят", junction.key));
                    break;
                }
                result.set(t, changed);
                taken++;
                sharp = sharpTurns(changed).size();
            }
        }
        log.info("turned: moves={} elapsed={}ms", taken, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /** Камеры ветвления дерева, где путь точки к врезке поворачивает круче MAX_TURN_DEG. */
    private static List<Tree.Node> sharpTurns(Tree tree) {
        List<Tree.Node> result = new ArrayList<>();
        for (Tree.Edge in : tree.edges) {
            if (in.to.kind != Tree.Kind.JUNCTION) {
                continue;
            }
            Coordinate[] c = in.line.getCoordinates();
            for (Tree.Edge out : tree.edges) {
                if (out.from == in.to && Router.deflectionDeg(c[c.length - 2], in.to.point,
                        out.line.getCoordinateN(1)) > Router.MAX_TURN_DEG) {
                    result.add(in.to);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Дерево, у которого новая камера врезки на трубе сдвинута вдоль трубы до RETIE_MAX_M с шагом RETIE_STEP_M так, что
     * уходит первая вершина её ребра с изломом ({@link TreeBuilder#retie}); null — такого сдвига нет. Излом снимается,
     * если эта вершина не нужна для отступов: прямая от врезки к следующей вершине обычная. Место врезки даёт
     * {@link TieInFinder#shifted}: у камеры не дальше 10 м врезка идёт в неё, и такой сдвиг не берётся. Сдвиги
     * пробуются по возрастанию цены рёбер и берутся, как у камер ветвления, если S узла из одного дерева tree не
     * растёт; others — деревья других узлов врезки.
     */
    private Tree retied(Tree tree, Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, Region region,
            Envelope area, List<Tree> others) {
        if (tree.tie.isChamber()) {
            return null;
        }
        int dn = 0;
        List<Tree.Edge> kinked = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] c = edge.line.getCoordinates();
            if (edge.from == tree.root) {
                dn = Math.max(dn, dnByEdge.get(edge));
                // вершина, без которой прямая от врезки задевает зоны, обоснована: места врезки не перебираются
                if (c.length > 2 && TreeBuilder.deflectionDeg(c[0], c[1], c[2]) >= TreeBuilder.MIN_TURN_DEG
                        && region.obstacles(dnByEdge.get(edge), area)
                                .plain(c[0], c[2], tree.tie.getIgnored(), Router.CUT_MARGIN_M)) {
                    kinked.add(edge);
                }
            }
        }
        List<Integer> steps = new ArrayList<>();
        List<TreeBuilder.Slide> slides = new ArrayList<>();
        LineString pipe = kinked.isEmpty() ? null : finder.pipe(tree.tie);
        LengthIndexedLine axis = pipe == null ? null : new LengthIndexedLine(pipe);
        double at = axis == null ? 0 : axis.project(tree.root.point);
        for (int step = -(int) (RETIE_MAX_M / RETIE_STEP_M); axis != null && step <= RETIE_MAX_M / RETIE_STEP_M; step++) {
            Coordinate point = axis.extractPoint(at + step * RETIE_STEP_M);
            for (Tree.Edge s : kinked) {
                TreeBuilder.Slide slide = step == 0 ? null : builder.retie(tree, s, point, priceRub, 0);
                if (slide != null) {
                    steps.add(step);
                    slides.add(slide);
                }
            }
        }
        Integer[] order = new Integer[slides.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble(k -> slides.get(k).gain));
        double before = Double.NaN;
        for (int k : order) {
            TreeBuilder.Slide slide = slides.get(k);
            // место врезки как у поиска: у концов трубы и у камеры не дальше 10 м оно другое, такой сдвиг не берётся
            TieCandidate tie = finder.shifted(tree.tie, tree.root.point, steps.get(k) * RETIE_STEP_M, dn);
            if (tie == null || tie.isChamber() || !tie.getExistingObjectId().equals(tree.tie.getExistingObjectId())
                    || tie.getPoint().getCoordinate().distance(slide.point) > 1e-6
                    || !builder.retieClear(tree, slide, tie.getIgnored(), edge -> region.obstacles(dnByEdge.get(edge), area))) {
                continue;
            }
            Tree changed = unkinked(tree, slide, tie, dnByEdge, region, area, false);
            if (changed == null || !compatible(changed, others)) {
                continue;
            }
            before = Double.isNaN(before) ? unitScore(List.of(tree)) : before;
            if (unitScore(List.of(changed)) <= before + UNKINK_EPS && !thicker(tree, List.of(tree), changed, List.of(changed))) {
                return changed;
            }
        }
        return null;
    }

    /**
     * Дерево со сдвигом slide камеры и врезкой tie, рёбра камеры в строгой форме (pathTurns — как у
     * {@link #sharpened}), или null, см. {@link #unkinked(List)}.
     */
    private Tree unkinked(Tree tree, TreeBuilder.Slide slide, TieCandidate tie, Map<Tree.Edge, Integer> dnByEdge,
            Region region, Envelope area, boolean pathTurns) {
        Tree moved = builder.moved(tree, slide, tie);
        Map<Integer, LineString> change = new LinkedHashMap<>();
        Map<Integer, Fresh> found = new HashMap<>();
        for (int e = 0; e < tree.edges.size(); e++) {
            if (!slide.lines.containsKey(tree.edges.get(e))) {
                continue;
            }
            int dn = dnByEdge.get(tree.edges.get(e));
            // форму доводят зоны Ду ребра, а граф только исполняет доводку: берётся уже построенный
            Router router = null;
            for (Diameter graph = rules.diameter(dn); router == null && graph != null; graph = rules.nextDiameter(graph.getDn())) {
                router = region.routers.get(graph.getDn() + "@" + area);
            }
            if (router == null) {
                return null;
            }
            LineString line = moved.edges.get(e).line;
            change.put(e, line);
            found.put(e, new Fresh(line, router, region.obstacles(dn, area)));
        }
        Map<Integer, LineString> sharp = sharpened(moved, change, found, pathTurns);
        return sharp == null ? null : replaced(moved, sharp);
    }

    /** Дерево с рёбрами, заменёнными по номеру; остальные рёбра те же. */
    private static Tree replaced(Tree tree, Map<Integer, LineString> lines) {
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        for (int e = 0; e < tree.edges.size(); e++) {
            Tree.Edge edge = tree.edges.get(e);
            Tree.Node from = edge.from == tree.root ? result.root : edge.from;
            LineString line = lines.getOrDefault(e, edge.line);
            result.edges.add(from == edge.from && line == edge.line ? edge : new Tree.Edge(from, edge.to, line));
        }
        return result;
    }

    /**
     * Участки не заходят в зоны запрета по своему фактическому Ду; финальный отрезок к точке подключения — без
     * отступа к её полигону. Отступы от объектов специального прохода по Ду участка проверяет сборка.
     */
    private boolean forbidClear(Tree tree, Variant alone, Envelope area, Region region) {
        Set<String> ignored = tree.tie.getIgnored();
        for (NewSegment segment : alone.getSegments()) {
            ObstacleSet obstacles = region.obstacles(segment.getDiameter(), area);
            Coordinate[] coords = segment.getGeometry().getCoordinates();
            ExistingOks building = buildingByConnection.get(segment.getEndNodeId());
            Set<String> last = ignored;
            if (building != null) {
                last = new HashSet<>(ignored);
                last.add(building.getId());
            }
            for (int i = 0; i + 1 < coords.length; i++) {
                if (obstacles.forbidden(coords[i], coords[i + 1], i + 2 == coords.length ? last : ignored)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Финальный отрезок к точке подключения выходит из зоны отступа её здания по Ду своего участка один раз
     * ({@link TreeBuilder#leavesZoneOnce}): выход ставится по зонам графа, а зона по Ду участка может быть другой.
     */
    private boolean raysClear(Tree tree, Variant alone) {
        Map<String, Integer> dnByEnd = new HashMap<>();
        alone.getSegments().forEach(segment -> dnByEnd.put(segment.getEndNodeId(), segment.getDiameter()));
        for (Tree.Edge edge : tree.edges) {
            ExistingOks building = edge.to.kind == Tree.Kind.CONNECTION ? buildingByConnection.get(edge.to.connection.getId()) : null;
            if (building == null) {
                continue;
            }
            int dn = dnByEnd.get(edge.to.connection.getId());
            Coordinate[] coords = edge.line.getCoordinates();
            if (!builder.leavesZoneOnce(building, coords[coords.length - 1], coords[coords.length - 2], oksClearance(dn))) {
                return false;
            }
        }
        return true;
    }

    /** Отступ оси участка Ду {@code dn} от полигона ОКС, как у зон графа того же Ду. */
    private double oksClearance(int dn) {
        return rules.restriction(OKS_EXISTING).clearanceM(dn) + rules.diameter(dn).getWidthM() / 2;
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
        if (direct != null) {
            int used = directDegree(tree, geometry);
            if (used < 0) {
                return false;
            }
            rootDegree += used;
        }
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
            } else if (near(geometry.getEnvelopeInternal(), other.envelope())
                    && geometry.distance(geometry(other)) <= TREES_APART_M) {
                // рамки дальше порога — геометрии тем более: точное расстояние JTS не считается
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

    /**
     * Участки прямых подключений в камере врезки дерева района, наибольшее по наборам; -1 — дерево ближе
     * TREES_APART_M к прямому подключению другой врезки или у общей врезки касается его вне круга SHARED_ROOT_CLIP_M.
     */
    private int directDegree(Tree tree, Geometry geometry) {
        Envelope around = new Envelope(geometry.getEnvelopeInternal());
        around.expandBy(TREES_APART_M);
        int[] degree = new int[2];
        for (Object item : direct.query(around)) {
            @SuppressWarnings("unchecked")
            Map.Entry<Integer, Tree> entry = (Map.Entry<Integer, Tree>) item;
            Tree other = entry.getValue();
            if (other.root.key.equals(tree.root.key)) {
                degree[entry.getKey()] += other.degree(other.root);
                Geometry clip = factory.createPoint(tree.root.point).buffer(SHARED_ROOT_CLIP_M);
                if (geometry.difference(clip).distance(other.geometry().difference(clip)) <= SHARED_ROOT_APART_M) {
                    return -1;
                }
            } else if (near(geometry.getEnvelopeInternal(), other.envelope())
                    && geometry.distance(other.geometry()) <= TREES_APART_M) {
                return -1;
            }
        }
        return Math.max(degree[0], degree[1]);
    }

    /**
     * Правило variants: другой existing_object_id, врезка дальше 20 м от всех врезок другого или другое разбиение.
     * Другой объект врезки ближе 20 м оставлен отличием: без него на 19 сценариях S10–S13 оставался один вариант при
     * подключённых ОКС, а docs/interpretation.md допускает один вариант только без врезок.
     */
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

    /** R-11: больше 90 % длины меньшего варианта совпадает с трассой другого. */
    private boolean sameRoute(Draft a, Draft b) {
        return RouteBand.same(band(a), band(b), SAME_ROUTE_SHARE);
    }

    private RouteBand band(Draft draft) {
        if (draft.band == null) {
            List<LineString> edges = draft.trees.stream().flatMap(tree -> tree.edges.stream()).map(edge -> edge.line)
                    .collect(Collectors.toList());
            draft.band = new RouteBand(edges, edges.size() + draft.trees.size(), SAME_ROUTE_M, factory);
        }
        return draft.band;
    }

    /**
     * Рамки не дальше TREES_APART_M; пустая рамка — не известно, считать точно. Рамки, разнесённые больше порога по
     * одной оси, отсекаются без расстояния: совместимость дерева проверяется со всеми принятыми, и hypot в
     * Envelope.distance был заметной ценой черновика.
     */
    private static boolean near(Envelope a, Envelope b) {
        if (a.isNull() || b.isNull()) {
            return true;
        }
        if (a.getMinX() - b.getMaxX() > TREES_APART_M || b.getMinX() - a.getMaxX() > TREES_APART_M
                || a.getMinY() - b.getMaxY() > TREES_APART_M || b.getMinY() - a.getMaxY() > TREES_APART_M) {
            return false;
        }
        return a.distance(b) <= TREES_APART_M;
    }

    private static Geometry geometry(Tree tree) {
        return tree.geometry();
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
        Coordinate[] xy = coordinates(connections);
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
            for (Integer j : hits) {
                if (j <= i) {
                    continue;
                }
                if (xy[i].distance(xy[j]) <= GROUP_DISTANCE_M) {
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
        Coordinate[] xy = coordinates(connections);
        Coordinate a = null;
        Coordinate b = null;
        for (Coordinate p : xy) {
            for (Coordinate q : xy) {
                if (a == null || p.distance(q) > a.distance(b)) {
                    a = p.copy();
                    b = q.copy();
                }
            }
        }
        List<ConnectionPoint> first = new ArrayList<>();
        List<ConnectionPoint> second = new ArrayList<>();
        for (int iteration = 0; iteration < KMEANS_ITERATIONS; iteration++) {
            first.clear();
            second.clear();
            for (int i = 0; i < xy.length; i++) {
                (xy[i].distance(a) <= xy[i].distance(b) ? first : second).add(connections.get(i));
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

    /**
     * Координаты точек один раз на группу: точка входа хранит их упакованными и создаёт Coordinate на каждый вызов,
     * а расстояние по координатам то же, что Point.distance, без DistanceOp на каждую пару.
     */
    private static Coordinate[] coordinates(List<ConnectionPoint> connections) {
        Coordinate[] xy = new Coordinate[connections.size()];
        for (int i = 0; i < xy.length; i++) {
            xy[i] = connections.get(i).getGeometry().getCoordinate();
        }
        return xy;
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
