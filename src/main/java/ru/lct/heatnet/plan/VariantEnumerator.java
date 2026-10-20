package ru.lct.heatnet.plan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
import ru.lct.heatnet.graph.SpecialSpan;
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

public final class VariantEnumerator {
    private static final Logger log = LoggerFactory.getLogger(VariantEnumerator.class);
    private static final String OKS_EXISTING = "oks_existing";
    private static final double GROUP_DISTANCE_M = 300;
    private static final double AREA_MARGIN_M = 150;
    private static final double WIDE_AREA_MARGIN_M = 600;
    private static final int ALONG_FACTOR = 2;
    private static final double SAME_ROUTE_SHARE = 0.9;
    private static final double SAME_ROUTE_M = 10.0;
    static final String BALLAST_PROPERTY = "heatnet.variants.ratio";
    private static final double DRAFT_ROUTE_M = 1.0;
    private static final boolean SCAN = Boolean.parseBoolean(System.getProperty("heatnet.search.scan", "true"));
    private static final double SCAN_RADIUS_M = 150;
    private static final double SCAN_STEP_M = 2;
    private static final int SCAN_TRIES = 3;
    private static final double DETOUR_RATIO = 1.1;
    private static final double POOR_RATIO = 1.5;
    private static final double POOR_EXTRA_M = 20;
    static final double TREES_APART_M = 0.5;
    private static final double SWAP_GAIN = 1e-6;
    private static final double PAIR_MIN_DEG = 60;
    private static final double PAIR_MAX_DEG = 120;
    private static final double PAIR_LINK_M = 30;
    private static final double SLIDE_MIN_M = 1.0;
    static final double SHARED_ROOT_CLIP_M = 0.15;
    static final double SHARED_ROOT_APART_M = 0.01;
    private static final int MAX_VARIANTS = 3;
    private static final double FORM_RIGHT_DEG = 87;
    private static final double FORM_STEP_M = 20;
    private static final double[] ABSORB_SHIFTS_M = {0, -2, 2, -4, 4, -8, 8, -16, 16};
    private static final boolean PARALLEL = Boolean.parseBoolean(System.getProperty("heatnet.search.parallel", "true"));
    private static final boolean REROUTE = Boolean.parseBoolean(System.getProperty("heatnet.search.reroute", "true"));
    private static final boolean CORRIDOR = Boolean.parseBoolean(System.getProperty("heatnet.city.corridor", "true"));
    private static final double CORRIDOR_SHARE = Double.parseDouble(System.getProperty("heatnet.city.corridor.share", "0.25"));
    private static final int EXTRA_VARIANT_BLOCKS = Integer.getInteger("heatnet.search.variantblocks", 8);
    private static final double EXTENT_MARGIN_M = 2500;
    private static final int KMEANS_ITERATIONS = 20;
    private static final int KMEANS_MAX = Integer.getInteger("heatnet.scale.kmeans.max", 500);
    private static final int SINGLES_MAX = Integer.getInteger("heatnet.scale.singles.max", 500);
    private static final int SEARCH_BUDGET = Integer.getInteger("heatnet.search.budget", 1000);
    private static final int SEARCH_STALL = Integer.getInteger("heatnet.search.stall", 100);
    private static final int CITY_STALL = Integer.getInteger("heatnet.city.stall", 50);
    private static final int CANDIDATE_LIMIT = 6;
    private static final int CONTINUE_MAX = 4;
    private static final int JOIN_TREES = 3;
    private static final double LENGTH_DN_MARGIN = 1.2;
    private static final int NEAREST_BLOCKS = 2;
    private static final double IMPROVE_EPS = 1e-6;
    private static final double UNKINK_EPS = 1e-9;
    private static final double RETIE_STEP_M = 0.1;
    private static final double RETIE_MAX_M = 5;
    private static final double STRAIGHT_TIE_MIN_DEG = 70;
    private static final int STRAIGHT_TIE_TRIES = 12;
    private static final double TURN_STEP_M = 0.1;
    private static final double TURN_MAX_M = 10;
    private static final double TURN_TOLERANCE_S = 0.002;
    private static final boolean TURN_GRID = Boolean.parseBoolean(System.getProperty("heatnet.turn.grid", "true"));
    private static final boolean TURN_DETACH = Boolean.parseBoolean(System.getProperty("heatnet.turn.detach", "true"));
    private static final double EVEN_EPS_RUB = 1e-3;
    private static final boolean SQUARE = Boolean.parseBoolean(System.getProperty("heatnet.square", "true"));
    private static final double SQUARE_RATIO = Double.parseDouble(System.getProperty("heatnet.square.ratio", "1.3"));
    private static final double SQUARE_TURN_M = Double.parseDouble(System.getProperty("heatnet.square.turnM", "10"));
    private static final double AXIS_NEAR_M = 30;
    private static final double JUNCTION_MOVE_M = 30;
    private static final double JUNCTION_BEND_DEG = 30;
    private static final double JUNCTION_NEAR_M = 10;
    private static final double JUNCTION_GAIN = 0.75;
    private static final int JUNCTION_SPOTS = 40;
    private static final double JUNCTION_STEP_M = 2;
    private static final double OFF_AXIS_RAD = Math.toRadians(1);
    private static final double RIGHT_TOL_DEG = 2;
    private static final double AXIS_SIDE_M = 8;
    private static final int SQUARE_PASSES = 3;
    private static final int TURN_FIXES = 8;
    private static final boolean SLIDE_JUNCTIONS =
            Boolean.parseBoolean(System.getProperty("heatnet.slide.junctions", "true"));
    private static final double SLIDE_MIN_GAIN_RUB = 50_000;
    private static final double WALK_THRESHOLD = 0.5;
    private static final int CITY_MIN = Integer.getInteger("heatnet.city.min", 500);
    private static final int CITY_BLOCK = Integer.getInteger("heatnet.city.block", 16);
    private static final int CITY_THREADS = Integer.getInteger("heatnet.city.threads", Runtime.getRuntime().availableProcessors());
    private static final double CITY_REACH_M = 150;
    private static final double CITY_MARGIN_M = 60;
    private static final int CITY_BUDGET = Integer.getInteger("heatnet.city.budget", 60);
    private static final boolean CITY_RETRY = Boolean.parseBoolean(System.getProperty("heatnet.city.retry", "true"));
    private static final double RETRY_NEAR_M = 150;
    private static final int RETRY_TREES = 3;
    private static final long CITY_RETRY_S = Long.getLong("heatnet.city.retry.deadline", 0);
    private static final long CITY_DEADLINE_S = Long.getLong("heatnet.city.deadline", 0);

    private static final long CITY_CACHE_MB = 64;
    private static final boolean RELAY = Boolean.parseBoolean(System.getProperty("heatnet.relay", "true"));
    private static final boolean EXACT = Boolean.parseBoolean(System.getProperty("heatnet.exact", "true"));
    private static final int EXACT_MAX = Integer.getInteger("heatnet.exact.max", 16);
    private static final double EXACT_STEP_M = Double.parseDouble(System.getProperty("heatnet.exact.step", "20"));
    private static final double[] EXACT_STEPS = java.util.Arrays.stream(
            System.getProperty("heatnet.exact.steps", "20,21,22,23,24,25,26,27,28,29,30").split(","))
            .mapToDouble(x -> Double.parseDouble(x.trim())).toArray();
    private static final int EXACT_MULTI_MAX = Integer.getInteger("heatnet.exact.multiMax", 40);
    private static final int EXACT_GRIDS_AT_ONCE = Integer.getInteger("heatnet.exact.gridsAtOnce",
            Runtime.getRuntime().maxMemory() >= 3L << 30 ? 2 : 1);
    private static final double EXACT_REFINE_M = 10;
    private static final int EXACT_REFINE_SWEEPS = Integer.getInteger("heatnet.exact.sweeps", 3);
    private static final long EXACT_MAX_BYTES = Long.getLong("heatnet.exact.maxBytes", 256L << 20);
    private static final double EXACT_MARGIN_M = 30;
    private static final double EXACT_GUESS = Double.parseDouble(System.getProperty("heatnet.exact.guess", "1.03"));
    private static final int RIGHT_FREE_ROUNDS = 10;
    private static final int RELAY_TRIES = 3;
    private static final int RELAY_PASSES = 3;
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
    private final boolean district;
    private volatile boolean extended;
    private STRtree direct;
    private final SpecialObjects specials;
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
        final double exact;
        boolean linked;
        boolean turnStuck;
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

    private final Map<Tree, Shaping> shapings = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> rejectedDn = new ConcurrentHashMap<>();
    private final Map<List<Object>, Optional<Object[]>> skeletons = new ConcurrentHashMap<>();
    private final Map<String, Double> exactCosts = new ConcurrentHashMap<>();

    private final AtomicInteger optionCount = new AtomicInteger();
    private final AtomicInteger cutCount = new AtomicInteger();
    private final AtomicInteger draftCount = new AtomicInteger();

    private final class Region {
        final int dn;
        final Envelope area;
        final Envelope wideArea;
        final Map<String, List<Option>> options = new HashMap<>();
        final Map<String, Router> routers = new java.util.concurrent.ConcurrentHashMap<>();
        volatile Set<String> visible;

        Router built(String key) {
            Set<String> keys = visible;
            return keys == null || keys.contains(key) ? routers.get(key) : null;
        }
        final Map<String, ObstacleSet> obstacleSets = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<Point> points;
        private final List<TieCandidate> candidates;
        private Geometry corridor;
        private boolean corridorBuilt;

        Region(List<ConnectionPoint> connections) {
            Diameter byFlow = rules.diameterFor(Math.min(flow(connections), rules.maxCapacityTph()));
            Diameter above = rules.nextDiameter(byFlow.getDn());
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
            this.points = points;
            this.candidates = candidates;
            this.wideArea = new Envelope(envelope);
            envelope.expandBy(district ? CITY_MARGIN_M : AREA_MARGIN_M);
            wideArea.expandBy(WIDE_AREA_MARGIN_M);
            this.area = envelope;
        }

        synchronized Geometry corridor() {
            if (!corridorBuilt && district && CORRIDOR && !candidates.isEmpty()) {
                List<Geometry> strips = new ArrayList<>();
                for (Point point : points) {
                    for (TieCandidate candidate : candidates) {
                        LineString straight = factory.createLineString(new Coordinate[] {point.getCoordinate(), candidate.getPoint().getCoordinate()});
                        strips.add(straight.buffer(Math.max(CITY_MARGIN_M, CORRIDOR_SHARE * straight.getLength())));
                    }
                }
                corridor = factory.createGeometryCollection(strips.toArray(new Geometry[0])).union();
            }
            corridorBuilt = true;
            return corridor;
        }

        Router router(int routerDn, Envelope routerArea) {
            return routers.computeIfAbsent(routerDn + "@" + routerArea,
                    key -> new Router(obstacleIndex, rules, routerArea, routerDn, routeCache, corridor()));
        }

        ObstacleSet obstacles(int obstaclesDn, Envelope obstaclesArea) {
            Router router = built(obstaclesDn + "@" + obstaclesArea);
            if (router != null) {
                return router.obstacles();
            }
            return obstacleSets.computeIfAbsent(obstaclesDn + "@" + obstaclesArea,
                    key -> new ObstacleSet(obstacleIndex, rules, obstaclesArea, obstaclesDn, null, routeCache, false));
        }
    }

    private static final class Move {
        final List<List<ConnectionPoint>> blocks;
        final List<ConnectionPoint> alternative;
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

    private final class Draft {
        final List<Tree> trees;
        final List<FutureOks> unconnected;
        final Variant variant;
        RouteBand band;

        Draft(List<Tree> trees, List<FutureOks> unconnected, Variant variant) {
            this.trees = trees;
            this.unconnected = unconnected;
            this.variant = variant;
        }

        double score() {
            return searchScore(variant);
        }
    }

    public VariantEnumerator(InputData input, Rules rules) {
        this(input, rules, new SpecialObjects(input, rules));
    }

    private VariantEnumerator(InputData input, Rules rules, SpecialObjects specials) {
        this(input, rules, new TieInFinder(input, rules, specials), new ObstacleIndex(input, rules), specials,
                buildings(input), RouteCache.DEFAULT_MB, false);
    }

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
            FutureOks target = oksById.get(connection.getOksId());
            if (target != null && target.getFlowTph() <= rules.maxCapacityTph()) {
                connectionByOks.putIfAbsent(connection.getOksId(), connection);
            }
        }
        this.builder = new TreeBuilder(finder.nodeLimit(), networkById, specials, buildingByConnection);
        this.mover = new JunctionMover(rules, specials, buildingByConnection, oksById);
    }

    public static InputData read(Path path, Rules rules) {
        long started = System.nanoTime();
        InputData input = Boolean.getBoolean("heatnet.read.all") ? GeoJsonStreamReader.read(path)
                : GeoJsonStreamReader.read(path, partial -> obstacleExtent(partial, rules));
        log.info("read: oks={} restrictions={} existing={} elapsed={}s", input.getFutureOks().size(),
                input.getRestrictions().size(), input.getExistingOks().size(), (System.nanoTime() - started) / 1_000_000_000L);
        return input;
    }

    public static Envelope obstacleExtent(InputData input, Rules rules) {
        try {
            Set<String> futureOks = new HashSet<>();
            for (FutureOks oks : input.getFutureOks()) {
                futureOks.add(oks.getId());
            }
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
            return null;
        }
    }

    public Result depth(Result flat) {
        long started = System.nanoTime();
        Set<String> oks = new HashSet<>();
        connectionByOks.values().forEach(c -> {
            oks.add(c.getId());
            oks.add(c.getOksId());
        });
        DepthProfiler profiler = new DepthProfiler(rules, specials, oks);
        List<Variant> profiled = flat.getVariants().stream().map(profiler::profile).collect(Collectors.toList());
        List<Integer> order = Scorer.rank(profiled.stream().map(Variant::getSummary).collect(Collectors.toList()));
        List<Variant> ranked = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Variant variant = profiled.get(order.get(i));
            VariantSummary s = variant.getSummary();
            ranked.add(new Variant(variant.getId(), variant.getSegments(), variant.getChambers(), variant.getNodes(),
                    new VariantSummary(s.getId(), s.getVariantId(), i + 1, s.getConstructionCost(),
                            s.getChamberConstructionCost(), s.getExistingChamberTieInCount(),
                            s.getExistingChamberTieInCost(), s.getUnconnectedPenalty(), s.getCalculatedCost(),
                            s.getNewNetworkLength(), s.getScore(), s.getUnconnectedOksIds())));
        }
        profiler.conflicts.forEach((variant, chains) -> log.warn("depth: вариант {}: профиль с уклоном {} не помещается "
                + "на {} цепочках (ручная проработка), первые: {}", variant, rules.depth().getMaxSlope(), chains.size(),
                chains.subList(0, Math.min(5, chains.size()))));
        log.info("depth: {} вариантов elapsed={}ms", ranked.size(), (System.nanoTime() - started) / 1_000_000);
        return new Result(ranked, flat.getNumericIds());
    }

    public Result run() {
        if (connectionByOks.size() > CITY_MIN) {
            return city();
        }
        List<Draft> shaped = new ArrayList<>();
        List<Draft> found = picked();
        for (int i = 0; i < found.size(); i++) {
            found.set(i, rescued(found.get(i), i == 0));
        }
        List<Draft> candidates = EXACT ? exact(found) : List.of();
        List<Draft> picked = shaped(found, shaped);
        if (!candidates.isEmpty()) {
            Set<Draft> shown = shown(picked, shaped);
            Set<Draft> replaced = exacted(picked, shaped, candidates);
            for (int i = shaped.size() - 1; !replaced.isEmpty() && i >= 0; i--) {
                if (!replaced.contains(picked.get(i)) && !shown.contains(picked.get(i))) {
                    picked.remove(i);
                    shaped.remove(i);
                }
            }
        }
        if (TURN_DETACH) {
            reattached(picked, shaped);
        }
        List<List<Tree>> recombined = recombined(shaped.stream().map(draft -> draft.trees).collect(Collectors.toList()),
                shaped.stream().mapToDouble(draft -> exact(draft.variant)).toArray(),
                trees -> assembler.assemble("0", 0, trees, missing(trees)));
        for (int i = 0; i < shaped.size(); i++) {
            List<Tree> trees = recombined.get(i);
            if (trees != shaped.get(i).trees) {
                List<FutureOks> unconnected = missing(trees);
                try {
                    Variant variant = assembler.assemble(shaped.get(i).variant.getId(), i + 1, trees, unconnected);
                    shaped.set(i, new Draft(trees, unconnected, variant));
                    picked.set(i, shaped.get(i));
                } catch (IllegalStateException | IllegalArgumentException e) {
                    log.info("recombined: вариант {} не собран: {}", i + 1, e.getMessage());
                }
            }
        }
        for (int i = 0; i < shaped.size(); i++) {
            List<Tree> trees = entered(shaped.get(i).trees, true);
            if (!trees.equals(shaped.get(i).trees)) {
                List<FutureOks> unconnected = missing(trees);
                try {
                    Variant variant = assembler.assemble(shaped.get(i).variant.getId(), i + 1, trees, unconnected);
                    shaped.set(i, new Draft(trees, unconnected, variant));
                    picked.set(i, shaped.get(i));
                } catch (IllegalStateException | IllegalArgumentException e) {
                    log.info("entered corner: вариант {} не собран: {}", i + 1, e.getMessage());
                }
            }
        }
        List<Variant> variants = shaped.stream().map(draft -> draft.variant).collect(Collectors.toList());
        List<Integer> order = Scorer.rank(variants.stream().map(Variant::getSummary).collect(Collectors.toList()));
        return new Result(distinct(order.stream().map(variants::get).collect(Collectors.toList()),
                order.stream().map(i -> picked.get(i).trees).collect(Collectors.toList())), input.getNumericIds());
    }

    private Set<Draft> exacted(List<Draft> picked, List<Draft> shaped, List<Draft> candidates) {
        long started = System.nanoTime();
        boolean outer = frozen();
        List<Draft[]> results;
        try {
            results = (PARALLEL ? candidates.parallelStream() : candidates.stream()).map(candidate -> {
                List<Draft> candidateShaped = new ArrayList<>();
                try {
                    List<Draft> candidatePicked = shaped(new ArrayList<>(List.of(candidate)), candidateShaped);
                    return candidateShaped.isEmpty() ? null : new Draft[] {candidatePicked.get(0), candidateShaped.get(0)};
                } catch (IllegalStateException | IllegalArgumentException e) {
                    log.info("exact: черновик со скелетом не доведён: {}", e.getMessage());
                    return null;
                }
            }).collect(Collectors.toList());
        } finally {
            thawed(outer);
        }
        Map<Integer, double[]> bases = new HashMap<>();
        int[] baseOf = new int[results.size()];
        double[][] forms = new double[results.size()][];
        Map<Integer, Integer> loose = new HashMap<>();
        for (int a = 0; a < results.size(); a++) {
            baseOf[a] = -1;
            if (results.get(a) == null) {
                continue;
            }
            Set<String> layout = layout(candidates.get(a).trees);
            for (int i = 0; i < shaped.size() && baseOf[a] < 0; i++) {
                baseOf[a] = layout(shaped.get(i).trees).equals(layout) ? i : -1;
            }
        }
        List<Integer> baseIds = Arrays.stream(baseOf).filter(i -> i >= 0).distinct().boxed().collect(Collectors.toList());
        double[][] baseForms = new double[shaped.size()][];
        long forming = System.nanoTime();
        outer = frozen();
        try {
            (PARALLEL ? IntStream.range(0, results.size() + baseIds.size()).parallel()
                    : IntStream.range(0, results.size() + baseIds.size())).forEach(q -> {
                if (q >= results.size()) {
                    int i = baseIds.get(q - results.size());
                    baseForms[i] = form(shaped.get(i));
                } else if (baseOf[q] >= 0) {
                    forms[q] = form(results.get(q)[1]);
                }
            });
        } finally {
            thawed(outer);
        }
        for (int a = 0; a < results.size(); a++) {
            if (baseOf[a] < 0) {
                continue;
            }
            double[] was = bases.computeIfAbsent(baseOf[a], i -> baseForms[i]);
            int compared = forms[a].length - 1;
            if (forms[a][5] == 0 && dominates(Arrays.copyOf(forms[a], compared), Arrays.copyOf(was, compared))
                    && (!loose.containsKey(baseOf[a]) || forms[a][0] < forms[loose.get(baseOf[a])][0])) {
                loose.put(baseOf[a], a);
            }
        }
        int first = -1;
        double firstScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < shaped.size(); i++) {
            double score = loose.containsKey(i) ? forms[loose.get(i)][0] : shaped.get(i).variant.getSummary().getScore();
            if (score < firstScore) {
                firstScore = score;
                first = i;
            }
        }
        Map<Integer, Integer> strict = new HashMap<>();
        int firstBase = first;
        long fixing = System.nanoTime();
        outer = frozen();
        try {
            (PARALLEL ? IntStream.range(0, results.size()).parallel() : IntStream.range(0, results.size())).forEach(a -> {
                if (baseOf[a] >= 0 && baseOf[a] != firstBase && forms[a][5] > 0) {
                    Draft fixed = rightFreeFixed(results.get(a)[1]);
                    if (fixed != results.get(a)[1]) {
                        results.get(a)[1] = fixed;
                        forms[a] = form(fixed);
                    }
                }
            });
        } finally {
            thawed(outer);
        }
        int[] knees = new int[results.size()];
        for (int a = 0; a < results.size(); a++) {
            knees[a] = baseOf[a] >= 0 && baseOf[a] != first ? knees(results.get(a)[1].trees) : 0;
        }
        for (int a = 0; a < results.size(); a++) {
            int base = baseOf[a];
            if (base < 0 || base == first) {
                continue;
            }
            double[] form = forms[a];
            double[] was = bases.get(base);
            int baseKnees = knees(shaped.get(base).trees);
            int compared = form.length - 1;
            boolean fits = dominates(form, was) || knees[a] < baseKnees
                    && dominates(Arrays.copyOf(form, compared), Arrays.copyOf(was, compared));
            Integer best = strict.get(base);
            if (form[5] == 0 && knees[a] <= baseKnees && fits && (best == null || knees[a] < knees[best]
                    || knees[a] == knees[best] && form[0] < forms[best][0])) {
                strict.put(base, a);
            }
        }
        for (int a = 0; a < results.size(); a++) {
            if (baseOf[a] >= 0) {
                log.info("exact: скелет черновика {}: score {} → после формы {}, без сети, поворотов, пар, дефектов, переносов, "
                        + "острых, не по осям {}; без скелета {}", baseOf[a] + 1, candidates.get(a).score(), forms[a][0],
                        Arrays.toString(forms[a]), Arrays.toString(bases.get(baseOf[a])));
            }
        }
        Map<Integer, Draft[]> chosen = new HashMap<>();
        if (first >= 0 && loose.containsKey(first)) {
            chosen.put(first, results.get(loose.get(first)));
        }
        strict.forEach((base, a) -> chosen.put(base, results.get(a)));
        Set<Draft> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        chosen.forEach((base, done) -> {
            picked.set(base, done[0]);
            shaped.set(base, done[1]);
            replaced.add(done[0]);
        });
        log.info("exact: черновиков со скелетом {}, взято {} elapsed={}ms (формы {}ms, переносы {}ms)", candidates.size(),
                replaced.size(), (System.nanoTime() - started) / 1_000_000, (fixing - forming) / 1_000_000,
                (System.nanoTime() - fixing) / 1_000_000);
        return replaced;
    }

    private double[] form(Draft draft) {
        int[] shape = shape(draft.trees);
        boolean sharp = draft.trees.stream().anyMatch(tree -> sharpPairs(tree, null) > 0);
        return new double[] {draft.variant.getSummary().getScore(), draft.variant.getSummary().getUnconnectedOksIds().size(),
                shape[0], shape[1], formDefects(draft.trees), unkinkable(draft.trees), sharp ? 1 : 0, offAxis(draft.trees)};
    }

    private double offAxis(List<Tree> trees) {
        double off = 0;
        for (Tree tree : trees) {
            for (int e = 0; e < tree.edges.size(); e++) {
                double axis = axis(tree.edges.get(e).line.getEnvelopeInternal());
                if (!Double.isNaN(axis)) {
                    off += offAxis(tree, List.of(e), axis);
                }
            }
        }
        return Math.round(off * 10) / 10.0;
    }

    private static boolean dominates(double[] form, double[] was) {
        boolean better = false;
        for (int k = 0; k < form.length; k++) {
            if (form[k] > was[k]) {
                return false;
            }
            better |= form[k] < was[k];
        }
        return better;
    }

    private int unkinkable(List<Tree> trees) {
        int count = 0;
        for (int t = 0; t < trees.size(); t++) {
            count += rightFreeMoves(trees, t, false).size();
        }
        return count;
    }

    private Draft rightFreeFixed(Draft draft) {
        List<Tree> trees = new ArrayList<>(draft.trees);
        boolean changed = false;
        for (int round = 0; round < RIGHT_FREE_ROUNDS; round++) {
            boolean moved = false;
            for (int t = 0; t < trees.size() && !moved; t++) {
                List<Tree> moves = rightFreeMoves(trees, t, true);
                if (!moves.isEmpty()) {
                    trees.set(t, moves.get(0));
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
            changed = true;
        }
        if (!changed) {
            return draft;
        }
        try {
            return new Draft(trees, draft.unconnected, assembler.assemble(draft.variant.getId(), 0, trees, draft.unconnected));
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.info("exact: черновик после переноса камер не собрался: {}", e.getMessage());
            return draft;
        }
    }

    private List<Tree> rightFreeMoves(List<Tree> trees, int t, boolean first) {
        List<Tree> moves = new ArrayList<>();
        Tree tree = trees.get(t);
        if (tree.connected().isEmpty()) {
            return moves;
        }
        Region region = regionByConnection.get(tree.connected().get(0).getId());
        Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
        if (!area.contains(tree.envelope())) {
            return moves;
        }
        List<Tree> unit = new ArrayList<>();
        for (Tree other : trees) {
            if (other.root.key.equals(tree.root.key)) {
                unit.add(other);
            }
        }
        Map<Tree.Edge, Integer> dnByEdge;
        try {
            dnByEdge = diameters(unit);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return moves;
        }
        Map<Tree.Edge, Double> priceRub = new IdentityHashMap<>();
        dnByEdge.forEach((edge, dn) -> priceRub.put(edge, rules.diameter(dn).getNewRubM() + rules.lengthWorthRub()));
        double maxGainRub = TURN_TOLERANCE_S / rules.score(1, 0);
        List<Tree> others = new ArrayList<>(trees);
        others.remove(t);
        List<Tree.Node> junctions = JunctionMover.junctions(tree);
        if (junctions.isEmpty()) {
            return moves;
        }
        double before = unitScore(unit);
        List<Tree> found = (PARALLEL ? IntStream.range(0, junctions.size()).parallel() : IntStream.range(0, junctions.size()))
                .mapToObj(j -> rightFreeMove(tree, junctions.get(j), unit, others, region, area, dnByEdge, priceRub,
                        maxGainRub, before))
                .collect(Collectors.toList());
        for (Tree move : found) {
            if (move != null) {
                moves.add(move);
                if (first) {
                    break;
                }
            }
        }
        return moves;
    }

    private Tree rightFreeMove(Tree tree, Tree.Node junction, List<Tree> unit, List<Tree> others, Region region,
            Envelope area, Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub,
            double before) {
        List<TreeBuilder.Slide> slides = new ArrayList<>();
        for (boolean evensOnly : new boolean[] {false, true}) {
            slides.addAll(builder.rightFreeUnkinks(tree, junction, edge -> region.obstacles(dnByEdge.get(edge), area),
                    dnByEdge, priceRub, maxGainRub, evensOnly));
        }
        for (TreeBuilder.Slide slide : slides) {
            Tree changed = unkinked(tree, slide, tree.tie, dnByEdge, region, area, true);
            if (changed == null || !compatible(changed, others)) {
                continue;
            }
            List<Tree> attempt = new ArrayList<>();
            for (Tree other : unit) {
                attempt.add(other == tree ? changed : other);
            }
            if (unitScore(attempt) <= before + TURN_TOLERANCE_S && !worse(tree, unit, changed, attempt)) {
                return changed;
            }
        }
        return null;
    }

    private int formDefects(List<Tree> trees) {
        int count = 0;
        for (Tree tree : trees) {
            for (Tree.Edge edge : tree.edges) {
                Coordinate[] c = edge.line.getCoordinates();
                int exit = edge.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(edge.to.connection.getId())
                        ? c.length - 2 : -1;
                double[] turn = new double[c.length];
                for (int i = 1; i + 1 < c.length; i++) {
                    double deg = Router.deflectionDeg(c[i - 1], c[i], c[i + 1]);
                    double side = (c[i].x - c[i - 1].x) * (c[i + 1].y - c[i].y) - (c[i].y - c[i - 1].y) * (c[i + 1].x - c[i].x);
                    turn[i] = deg >= TreeBuilder.MIN_TURN_DEG ? Math.copySign(deg, side) : 0;
                }
                for (int i = 1; i + 2 < c.length; i++) {
                    double a = turn[i];
                    double b = turn[i + 1];
                    if (a == 0 || b == 0) {
                        continue;
                    }
                    if (a * b > 0 && Math.abs(a) < FORM_RIGHT_DEG && Math.abs(b) < FORM_RIGHT_DEG
                            && Math.abs(a + b) <= Router.MAX_TURN_DEG + 1e-6) {
                        count++;
                    } else if (a * b < 0 && c[i].distance(c[i + 1]) < FORM_STEP_M && i + 1 != exit) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static final class ExactJob {
        final int i;
        final Tree tree;
        final List<ConnectionPoint> subset;
        final Region region;
        final int blockDn;
        final Router router;
        final String label;
        final Option before;
        final double bound;
        final TreeBuilder.Graphs graphs;
        final List<Double> steps;
        List<Option> built;

        ExactJob(int i, Tree tree, List<ConnectionPoint> subset, Region region, int blockDn, Router router, String label,
                Option before, TreeBuilder.Graphs graphs, List<Double> steps) {
            this.i = i;
            this.tree = tree;
            this.subset = subset;
            this.region = region;
            this.blockDn = blockDn;
            this.router = router;
            this.label = label;
            this.before = before;
            this.bound = before == null ? Double.POSITIVE_INFINITY : before.score;
            this.graphs = graphs;
            this.steps = steps;
        }

        List<List<Object>> keys() {
            List<List<Object>> keys = new ArrayList<>();
            for (double placeStep : steps) {
                keys.add(skeletonKey(region, blockDn, tree.tie, subset, placeStep, bound));
            }
            return keys;
        }
    }

    private static List<Object> skeletonKey(Region region, int blockDn, TieCandidate tie, List<ConnectionPoint> subset,
            double placeStep, double bound) {
        return List.of(region, blockDn, tie, subset.stream().map(ConnectionPoint::getId).collect(Collectors.toList()),
                placeStep, bound);
    }

    private List<Draft> exact(List<Draft> found) {
        long started = System.nanoTime();
        List<List<ExactJob>> jobs = new ArrayList<>();
        List<ExactJob> first = new ArrayList<>();
        List<ExactJob> later = new ArrayList<>();
        Set<List<Object>> claimed = new HashSet<>();
        for (Draft draft : found) {
            List<ExactJob> draftJobs = exactJobs(draft);
            jobs.add(draftJobs);
            for (ExactJob job : draftJobs) {
                List<List<Object>> keys = job.keys();
                (keys.stream().anyMatch(claimed::contains) ? later : first).add(job);
                claimed.addAll(keys);
            }
        }
        (PARALLEL && !district && EXACT_GRIDS_AT_ONCE > 1 ? first.parallelStream() : first.stream()).forEach(this::built);
        later.forEach(this::built);
        List<Draft> candidates = new ArrayList<>();
        for (int d = 0; d < found.size(); d++) {
            Draft draft = found.get(d);
            List<Draft> others = new ArrayList<>();
            Draft exact = exact(draft, jobs.get(d), others);
            if (exact != draft) {
                candidates.add(exact);
            }
            candidates.addAll(others);
        }
        log.info("exact: черновиков со скелетом {} у {} черновиков elapsed={}ms", candidates.size(), found.size(),
                (System.nanoTime() - started) / 1_000_000);
        return candidates;
    }

    private int exactMulti(List<Tree> trees) {
        int multi = -1;
        for (int i = 0; SQUARE && !district && connectionByOks.size() <= EXACT_MULTI_MAX && i < trees.size(); i++) {
            int size = trees.get(i).connected().size();
            if (size >= 3 && size <= EXACT_MAX
                    && (multi < 0 || size > trees.get(multi).connected().size())) {
                multi = i;
            }
        }
        return multi;
    }

    private List<ExactJob> exactJobs(Draft draft) {
        List<Tree> trees = draft.trees;
        int multi = exactMulti(trees);
        List<ExactJob> jobs = new ArrayList<>();
        for (int i = 0; i < trees.size(); i++) {
            Tree tree = trees.get(i);
            List<ConnectionPoint> subset = tree.connected();
            if (subset.size() < 3 || subset.size() > EXACT_MAX) {
                continue;
            }
            Region region = regionByConnection.get(subset.get(0).getId());
            Diameter byFlow = rules.diameterFor(flow(subset));
            Diameter step = rules.nextDiameter(byFlow.getDn());
            int blockDn = Math.min(region.dn, step != null ? step.getDn() : byFlow.getDn());
            Router router = region.router(blockDn, region.area);
            String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
            Option before = option(tree, label, false, region.area, blockDn, region, tree.narrow);
            TreeBuilder.Graphs graphs = graphs(region, region.area);
            List<Double> steps = new ArrayList<>(List.of(EXACT_STEP_M));
            for (double placeStep : i == multi ? EXACT_STEPS : new double[0]) {
                if (!steps.contains(placeStep)) {
                    steps.add(placeStep);
                }
            }
            jobs.add(new ExactJob(i, tree, subset, region, blockDn, router, label, before, graphs, steps));
        }
        return jobs;
    }

    private void built(ExactJob job) {
        List<Option> built = new ArrayList<>();
        for (int from = 0; from < job.steps.size(); from += EXACT_GRIDS_AT_ONCE) {
            List<Double> batch = job.steps.subList(from, Math.min(job.steps.size(), from + EXACT_GRIDS_AT_ONCE));
            built.addAll((PARALLEL ? batch.parallelStream() : batch.stream())
                    .map(placeStep -> Optional.ofNullable(skeleton(job.tree, job.subset, job.region, job.blockDn,
                            job.router, job.label, placeStep, job.bound, job.graphs)))
                    .map(found -> found.orElse(null)).collect(Collectors.toList()));
        }
        job.built = built;
    }

    private Draft exact(Draft draft, List<ExactJob> jobs, List<Draft> alternates) {
        List<Tree> trees = new ArrayList<>(draft.trees);
        boolean changed = false;
        int altTree = -1;
        List<Option> altOptions = List.of();
        int multi = exactMulti(trees);
        for (ExactJob job : jobs) {
            int i = job.i;
            Option before = job.before;
            List<Tree> others = new ArrayList<>(trees);
            others.remove(i);
            Option primary = null;
            List<Option> extra = new ArrayList<>();
            for (int g = 0; g < job.steps.size(); g++) {
                double placeStep = job.steps.get(g);
                Option best = job.built.get(g);
                log.info("exact: дерево {} сетка {} м score {} → {}", job.label, placeStep,
                        before == null ? Double.NaN : before.score, best == null ? Double.NaN : best.score);
                boolean valid = best != null && (before == null || best.score < before.score) && compatible(best.tree, others);
                if (placeStep == EXACT_STEP_M) {
                    primary = valid ? best : null;
                } else if (valid && (primary == null || Math.abs(primary.exact - best.exact) >= 1e-9)
                        && extra.stream().noneMatch(o -> Math.abs(o.exact - best.exact) < 1e-9)) {
                    extra.add(best);
                }
            }
            extra.sort(Comparator.comparingDouble(o -> o.score));
            List<Option> options = new ArrayList<>(extra);
            if (primary != null) {
                options.add(0, primary);
            }
            Region region = job.region;
            int blockDn = job.blockDn;
            String label = job.label;
            for (Option option : options) {
                shapings.put(option.tree, new Shaping(job.router, blockDn, job.graphs,
                        shape -> option(shape, label, false, region.area, blockDn, region, option.tree.narrow || shape.narrow)));
            }
            if (i == multi && !extra.isEmpty()) {
                altTree = i;
                altOptions = extra;
            }
            if (primary == null) {
                continue;
            }
            trees.set(i, primary.tree);
            changed = true;
        }
        Draft exact = draft;
        if (changed) {
            try {
                exact = new Draft(trees, draft.unconnected, assembler.assemble("0", 0, trees, draft.unconnected));
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.info("exact: черновик не собрался: {}", e.getMessage());
                exact = draft;
            }
            if (exact.score() >= draft.score()) {
                exact = draft;
            }
        }
        if (exact == draft) {
            trees = new ArrayList<>(draft.trees);
        }
        for (Option option : alternates == null ? List.<Option>of() : altOptions) {
            List<Tree> alt = new ArrayList<>(trees);
            alt.set(altTree, option.tree);
            try {
                Draft other = new Draft(alt, draft.unconnected, assembler.assemble("0", 0, alt, draft.unconnected));
                if (other.score() < draft.score()) {
                    alternates.add(other);
                }
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.info("exact: другой скелет не собрался: {}", e.getMessage());
            }
        }
        return exact;
    }

    private Option skeleton(Tree tree, List<ConnectionPoint> subset, Region region, int blockDn, Router router,
            String label, double placeStep, double bound, TreeBuilder.Graphs graphs) {
        TieCandidate tie = tree.tie;
        List<Object> key = skeletonKey(region, blockDn, tie, subset, placeStep, bound);
        Optional<Object[]> found = skeletons.get(key);
        if (found == null) {
            found = Optional.ofNullable(skeletal(tie, subset, region, blockDn, router, label, placeStep, bound));
            skeletons.put(key, found);
        } else {
            log.info("exact: дерево {} сетка {} м: скелет уже посчитан", label, placeStep);
        }
        if (found.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Coordinate> junctions = ((List<Coordinate>) found.get()[0]).stream().map(Coordinate::copy)
                .collect(Collectors.toList());
        int[] parents = ((int[]) found.get()[1]).clone();
        double metreRub = rules.diameter(blockDn).getNewRubM() + rules.lengthWorthRub();
        Tree built = builder.buildOnSkeleton(router, blockDn, region.area, tie, subset, junctions, parents,
                rules.chamberCost(blockDn) / metreRub, rules.tieInCost() / metreRub, graphs);
        if (built == null || !built.unconnected.isEmpty()) {
            log.info("exact: дерево {} на скелете не построено", label);
            return null;
        }
        Option best = null;
        for (Tree shape : List.of(builder.cut(built, router, blockDn, graphs, TreeBuilder.CUT_PASSES),
                builder.cut(built, router, blockDn, graphs, 0), built)) {
            Option option = option(shape, label, false, region.area, blockDn, region, built.narrow || shape.narrow);
            if (option != null && (best == null || option.score < best.score)) {
                best = option;
            }
        }
        return best;
    }

    private Object[] skeletal(TieCandidate tie, List<ConnectionPoint> subset, Region region, int blockDn, Router router,
            String label, double placeStep, double bound) {
        List<Coordinate> places = new ArrayList<>();
        int k = subset.size();
        int[] leaf = new int[k];
        double[] leafCost = new double[k];
        double[] flows = new double[k];
        Envelope frame = new Envelope(tie.getPoint().getCoordinate());
        for (int t = 0; t < k; t++) {
            ConnectionPoint connection = subset.get(t);
            Coordinate at = connection.getGeometry().getCoordinate();
            Coordinate exit = builder.exit(router, blockDn, region.area, tie, connection);
            exit = exit != null ? exit : at;
            flows[t] = oksById.get(connection.getOksId()).getFlowTph();
            double length = exit.distance(at);
            leafCost[t] = rules.score(rules.diameterFor(flows[t]).getNewRubM() * length, length);
            leaf[t] = places.size();
            places.add(exit);
            frame.expandToInclude(exit);
        }
        int root = places.size();
        places.add(tie.getPoint().getCoordinate());
        frame.expandBy(EXACT_MARGIN_M);
        ObstacleSet zones = router.obstacles();
        long ix0 = (long) Math.floor(frame.getMinX() / placeStep);
        long iy0 = (long) Math.floor(frame.getMinY() / placeStep);
        for (long ix = ix0; ix * placeStep <= frame.getMaxX(); ix++) {
            for (long iy = iy0; iy * placeStep <= frame.getMaxY(); iy++) {
                Coordinate c = new Coordinate(ix * placeStep, iy * placeStep);
                if (region.area.contains(c) && !zones.insideForbid(c) && !zones.insideMargin(c)) {
                    places.add(c);
                }
            }
        }
        if ((1L << k) * places.size() * 8 > EXACT_MAX_BYTES) {
            log.info("exact: дерево {} пропущено: таблицы {} МБ", label, (1L << k) * places.size() * 8 >> 20);
            return null;
        }
        long started = System.nanoTime();
        double[][] weights = router.weights(places, tie.getIgnored());
        long weighed = System.nanoTime();
        java.util.function.DoubleUnaryOperator rate = f -> rules.score(rules.diameterFor(f).getNewRubM(), 1);
        java.util.function.DoubleUnaryOperator chamber = f -> rules.score(rules.chamberCost(rules.diameterFor(f).getDn()), 0);
        double extra = tie.isChamber() ? rules.score(rules.tieInCost(), 0) : 0;
        String guessKey = label + "|" + tie.nodeKey() + "|" + blockDn;
        Double seen = exactCosts.get(guessKey);
        double trial = seen == null ? bound : Math.min(bound, seen * EXACT_GUESS);
        ExactSteiner steiner = new ExactSteiner(weights, leaf, leafCost, flows, rate, chamber, root, bound, trial, true);
        ExactSteiner.Skeleton skeleton = steiner.tree(root, tie.getCapacity(), extra);
        if (Double.isFinite(trial) && (skeleton == null || skeleton.cost > trial)) {
            log.info("exact: дерево {} сетка {} м: оптимум выше границы {}, счёт заново без отсечения", label, placeStep, trial);
            steiner = null;
            skeleton = null;
            steiner = new ExactSteiner(weights, leaf, leafCost, flows, rate, chamber, root, bound, bound, false);
            skeleton = steiner.tree(root, tie.getCapacity(), extra);
        }
        if (skeleton != null) {
            exactCosts.merge(guessKey, skeleton.cost, Math::min);
        }
        log.info("exact: дерево {} мест {} веса {}ms динамика {}ms (слияния {}ms, продолжения {}ms по нитям, живых {}%) "
                + "камер {} оценка {}", label, places.size(), (weighed - started) / 1_000_000,
                (System.nanoTime() - weighed) / 1_000_000, steiner.mergeNanos.get() / 1_000_000,
                steiner.extendNanos.get() / 1_000_000, steiner.liveCount.get() * 100 / ((1L << k) * places.size()),
                skeleton == null ? 0 : skeleton.junctions.size(), skeleton == null ? Double.NaN : skeleton.cost);
        if (skeleton == null) {
            return null;
        }
        List<Coordinate> junctions = skeleton.junctions.stream().map(places::get).collect(Collectors.toList());
        int[] parents = skeleton.parents.stream().mapToInt(Integer::intValue).toArray();
        long refining = System.nanoTime();
        double moved = refined(router, tie, region.area, places, root, subset, flows, skeleton, junctions, rate);
        log.info("exact: доводка камер скелета {} м за {}ms", String.format(Locale.ROOT, "%.1f", moved),
                (System.nanoTime() - refining) / 1_000_000);
        return new Object[] {junctions.stream().map(Coordinate::copy).collect(Collectors.toList()), parents.clone()};
    }

    private double refined(Router router, TieCandidate tie, Envelope area, List<Coordinate> places, int root,
            List<ConnectionPoint> subset, double[] flows, ExactSteiner.Skeleton skeleton, List<Coordinate> junctions,
            java.util.function.DoubleUnaryOperator rate) {
        int count = junctions.size();
        double[] flowOf = new double[count];
        for (int j = 0; j < count; j++) {
            int mask = skeleton.masks.get(j);
            for (int t = 0; t < flows.length; t++) {
                if ((mask >> t & 1) == 1) {
                    flowOf[j] += flows[t];
                }
            }
        }
        ObstacleSet zones = router.obstacles();
        double moved = 0;
        for (int sweep = 0; sweep < EXACT_REFINE_SWEEPS; sweep++) {
            double sweepMoved = 0;
            for (int j = 0; j < count; j++) {
                List<Coordinate> neighbours = new ArrayList<>();
                List<Double> rates = new ArrayList<>();
                int parent = skeleton.parents.get(j);
                neighbours.add(parent < 0 ? places.get(root) : junctions.get(parent));
                rates.add(rate.applyAsDouble(flowOf[j]));
                for (int c = 0; c < count; c++) {
                    if (skeleton.parents.get(c) == j) {
                        neighbours.add(junctions.get(c));
                        rates.add(rate.applyAsDouble(flowOf[c]));
                    }
                }
                for (int t = 0; t < flows.length; t++) {
                    if (skeleton.leafParent[t] == j) {
                        neighbours.add(places.get(t));
                        rates.add(rate.applyAsDouble(flows[t]));
                    }
                }
                for (double[] grid : new double[][] {{EXACT_REFINE_M, 2}, {2, 0.5}}) {
                    Coordinate at = junctions.get(j);
                    List<Coordinate> candidates = new ArrayList<>();
                    candidates.add(at);
                    for (double dx = -grid[0]; dx <= grid[0] + 1e-9; dx += grid[1]) {
                        for (double dy = -grid[0]; dy <= grid[0] + 1e-9; dy += grid[1]) {
                            Coordinate c = new Coordinate(at.x + dx, at.y + dy);
                            if ((dx != 0 || dy != 0) && area.contains(c) && !zones.insideForbid(c) && !zones.insideMargin(c)) {
                                candidates.add(c);
                            }
                        }
                    }
                    double[][] w = router.weights(neighbours, candidates, tie.getIgnored());
                    int best = 0;
                    double bestCost = Double.POSITIVE_INFINITY;
                    for (int q = 0; q < candidates.size(); q++) {
                        double cost = 0;
                        for (int b = 0; b < neighbours.size(); b++) {
                            cost += rates.get(b) * w[b][q];
                        }
                        if (cost < bestCost - 1e-9) {
                            bestCost = cost;
                            best = q;
                        }
                    }
                    sweepMoved += candidates.get(best).distance(at);
                    junctions.set(j, candidates.get(best));
                }
            }
            moved += sweepMoved;
            if (sweepMoved < 0.1) {
                break;
            }
        }
        return moved;
    }

    private List<Draft> shaped(List<Draft> picked, List<Draft> shaped) {
        if (RELAY) {
            resetWork();
            picked = relaid(picked);
            log.info("relay: {}", work());
        }
        if (SLIDE_JUNCTIONS) {
            resetWork();
            long started = System.nanoTime();
            picked = (PARALLEL ? picked.parallelStream() : picked.stream()).map(this::shifted)
                    .sorted(Comparator.comparingDouble(Draft::score)).collect(Collectors.toList());
            log.info("slide: {} elapsed={}ms", work(), (System.nanoTime() - started) / 1_000_000);
        }
        List<Boolean> sharp = new ArrayList<>();
        List<Draft> drafts = picked;
        boolean outer = frozen();
        List<Draft> done;
        try {
            done = (PARALLEL ? IntStream.range(0, drafts.size()).parallel() : IntStream.range(0, drafts.size()))
                    .mapToObj(i -> polishedDraft(drafts.get(i), i)).collect(Collectors.toList());
        } finally {
            thawed(outer);
        }
        for (Draft draft : done) {
            shaped.add(draft);
            sharp.add(draft.trees.stream().anyMatch(tree -> sharpPairs(tree, null) > 0));
        }
        if (sharp.contains(false)) {
            for (int i = shaped.size() - 1; i >= 0; i--) {
                if (sharp.get(i)) {
                    log.warn("turned: вариант {} с поворотом круче 90° в камере не выдаётся", i + 1);
                    shaped.remove(i);
                    picked.remove(i);
                }
            }
        }
        return picked;
    }

    private boolean frozen() {
        boolean outer = false;
        for (Region region : new HashSet<>(regionByConnection.values())) {
            synchronized (region) {
                if (region.visible == null) {
                    region.visible = new HashSet<>(region.routers.keySet());
                    outer = true;
                }
            }
        }
        return outer;
    }

    private void thawed(boolean outer) {
        for (Region region : outer ? new HashSet<>(regionByConnection.values()) : Set.<Region>of()) {
            region.visible = null;
        }
    }

    private Draft polishedDraft(Draft draft, int i) {
        Variant variant = null;
        List<Tree> trees = draft.trees;
        if (REROUTE) {
            try {
                trees = polished(rerouted(draft));
                variant = assembler.assemble(String.valueOf(i + 1), i + 1, ranked(trees), unconnected(draft, trees));
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.info("rerouted: вариант {} не собран: {}", i + 1, e.getMessage());
            }
        }
        if (variant == null) {
            try {
                trees = polished(draft.trees);
                variant = assembler.assemble(String.valueOf(i + 1), i + 1, ranked(trees), unconnected(draft, trees));
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.info("unkinked: вариант {} не собран: {}", i + 1, e.getMessage());
            }
        }
        if (variant == null) {
            trees = draft.trees;
            variant = assembler.assemble(String.valueOf(i + 1), i + 1, trees, draft.unconnected);
        }
        return new Draft(trees, unconnected(draft, trees), variant);
    }

    private List<Tree> polished(List<Tree> trees) {
        List<Tree> unkinked = unkinked(turned(trees));
        Set<Tree> same = Collections.newSetFromMap(new IdentityHashMap<>());
        same.addAll(unkinked);
        List<Tree> squared = squared(unkinked(unkinked(evened(entered(bent(unkinked))), false, same), true));
        List<Tree> result = entered(unkinked(unkinked(evened(entered(unkinked(squared))), true), false, Set.of(), true));
        for (int t = 0; t < result.size(); t++) {
            Tree tree = result.get(t);
            if (tree != squared.get(t) && tree.unsquared == null && squared.get(t).unsquared != null) {
                tree.unsquared = squared.get(t).unsquared;
            }
        }
        return result;
    }

    private List<Variant> distinct(List<Variant> variants, List<List<Tree>> trees) {
        List<Variant> kept = new ArrayList<>();
        double ballast = Double.parseDouble(System.getProperty(BALLAST_PROPERTY, "1.3"));
        for (int i : distinctIndices(variants, trees)) {
            Variant variant = variants.get(i);
            if (!kept.isEmpty() && variant.getSummary().getScore() > ballast * kept.get(0).getSummary().getScore()) {
                log.info("variants: вариант {} с S {} дороже лучшего ({}) больше чем в {} раза и не выдаётся", variant.getId(),
                        variant.getSummary().getScore(), kept.get(0).getSummary().getScore(), ballast);
                continue;
            }
            String id = String.valueOf(kept.size() + 1);
            boolean same = variant.getId().equals(id) && variant.getSummary().getRank() == kept.size() + 1;
            kept.add(same ? variant : renumbered(variant, id, kept.size() + 1));
        }
        return kept;
    }

    private List<Integer> distinctIndices(List<Variant> variants, List<List<Tree>> trees) {
        List<Integer> kept = new ArrayList<>();
        List<RouteBand> bands = new ArrayList<>();
        List<Set<String>> layouts = new ArrayList<>();
        int fewest = variants.stream().mapToInt(v -> v.getSummary().getUnconnectedOksIds().size()).min().orElse(0);
        for (int i = 0; i < variants.size(); i++) {
            Variant variant = variants.get(i);
            int unconnected = variant.getSummary().getUnconnectedOksIds().size();
            if (unconnected > fewest) {
                log.info("variants: вариант {} оставляет без сети {} точек при наименьших {} и не выдаётся", variant.getId(),
                        unconnected, fewest);
                continue;
            }
            List<LineString> lines = variant.getSegments().stream().map(NewSegment::getGeometry).collect(Collectors.toList());
            RouteBand band = new RouteBand(lines, lines.size(), SAME_ROUTE_M, factory);
            Set<String> layout = layout(trees.get(i));
            if (layouts.contains(layout) || bands.stream().anyMatch(other -> RouteBand.same(other, band, SAME_ROUTE_SHARE))) {
                log.info("variants: вариант {} совпал с лучшим по устройству или трассе и не выдаётся", variant.getId());
                continue;
            }
            kept.add(i);
            bands.add(band);
            layouts.add(layout);
        }
        return kept;
    }

    private Set<Draft> shown(List<Draft> picked, List<Draft> shaped) {
        List<Variant> variants = shaped.stream().map(draft -> draft.variant).collect(Collectors.toList());
        List<Integer> order = Scorer.rank(variants.stream().map(Variant::getSummary).collect(Collectors.toList()));
        Set<Draft> shown = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int k : distinctIndices(order.stream().map(variants::get).collect(Collectors.toList()),
                order.stream().map(i -> picked.get(i).trees).collect(Collectors.toList()))) {
            shown.add(picked.get(order.get(k)));
        }
        return shown;
    }

    Result city() {
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
        Set<ConnectionPoint> detached = ConcurrentHashMap.newKeySet();
        Set<ConnectionPoint> restSet = new HashSet<>(rest);
        VariantEnumerator shaper = district(all.stream().filter(c -> !restSet.contains(c)).collect(Collectors.toList()));
        shaper.regions();
        for (int k = 0; k < directTrees.size() && rest.size() < all.size(); k++) {
            List<Tree> before = directTrees.get(k);
            directTrees.set(k, shaper.polished(before));
            lost(before, directTrees.get(k), detached);
        }
        log.info("city: direct trees shaped elapsed={}s", (System.nanoTime() - started) / 1_000_000_000L);
        double reach = rules.diameters().get(rules.diameters().size() - 1).getMaxLengthM();
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
        STRtree directIndex = new STRtree();
        for (int k = 0; k < directTrees.size(); k++) {
            for (Tree tree : directTrees.get(k)) {
                directIndex.insert(tree.envelope(), Map.entry(k, tree));
            }
        }
        directIndex.build();
        districts.sort(Comparator.comparingDouble(district -> district.stream()
                .mapToDouble(connection -> toNetwork.get(connection.getId())).min().orElse(0)));
        log.info("city: oks={} direct={} beyond {} m: {} districts={} elapsed={}s", all.size(), all.size() - rest.size(),
                reach, rest.size() - near.size(), districts.size(), (System.nanoTime() - started) / 1_000_000_000L);
        long deadline = deadline(started, CITY_DEADLINE_S);
        List<List<Draft>> results = districts.isEmpty() ? new ArrayList<>() : solve(districts, i -> directIndex, deadline, detached);
        if (!detached.isEmpty()) {
            List<List<ConnectionPoint>> singles = detached.stream().sorted(Comparator.comparing(ConnectionPoint::getId))
                    .map(List::of).collect(Collectors.toList());
            log.info("city: detached by shape passes {}, solved again one by one", singles.size());
            results.addAll(solve(singles, i -> directIndex, deadline, null));
        }
        int most = Math.max(directTrees.size(), results.stream().mapToInt(List::size).max().orElse(0));
        List<List<Tree>> candidates = new ArrayList<>();
        for (int k = 0; k < Math.min(MAX_VARIANTS, Math.max(most, 1)); k++) {
            List<Tree> trees = new ArrayList<>(directTrees.get(Math.min(k, directTrees.size() - 1)));
            trees.addAll(joined(results, k, trees));
            candidates.add(trees);
        }
        if (CITY_RETRY) {
            candidates = retried(candidates, near, deadline(System.nanoTime(), CITY_RETRY_S));
        }
        candidates.forEach(this::unsquaredWhereTouching);
        List<Variant> variants = new ArrayList<>();
        for (int k = 0; k < candidates.size(); k++) {
            List<Tree> trees = candidates.get(k);
            variants.add(assembleParts(String.valueOf(k + 1), k + 1, trees, missing(trees)));
        }
        List<List<Tree>> recombined = recombined(candidates, variants.stream().mapToDouble(this::exact).toArray(),
                trees -> assembleParts("0", 0, trees, missing(trees)));
        for (int k = 0; k < candidates.size(); k++) {
            List<Tree> trees = recombined.get(k);
            if (trees != candidates.get(k)) {
                candidates.set(k, trees);
                variants.set(k, assembleParts(String.valueOf(k + 1), k + 1, trees, missing(trees)));
            }
            Variant variant = variants.get(k);
            log.info("city: candidate {} trees={} score={} unconnected={} elapsed={}s", k + 1, trees.size(),
                    variant.getSummary().getScore(), variant.getSummary().getUnconnectedOksIds().size(),
                    (System.nanoTime() - started) / 1_000_000_000L);
        }
        List<Integer> order = Scorer.rank(variants.stream().map(Variant::getSummary).collect(Collectors.toList()));
        long distinctStarted = System.nanoTime();
        List<Variant> sorted = new ArrayList<>();
        List<List<Tree>> layouts = new ArrayList<>();
        for (int k : order) {
            sorted.add(variants.get(k));
            layouts.add(assembled(candidates.get(k), variants.get(k)));
        }
        List<Variant> kept = distinct(sorted, layouts);
        log.info("city: distinct variants={} of {} elapsed={}ms", kept.size(), variants.size(),
                (System.nanoTime() - distinctStarted) / 1_000_000);
        return new Result(kept, input.getNumericIds());
    }

    private void reattached(List<Draft> picked, List<Draft> shaped) {
        Set<ConnectionPoint> detached = new HashSet<>();
        for (int i = 0; i < shaped.size(); i++) {
            lost(picked.get(i).trees, shaped.get(i).trees, detached);
        }
        if (detached.isEmpty()) {
            return;
        }
        List<List<ConnectionPoint>> singles = detached.stream().sorted(Comparator.comparing(ConnectionPoint::getId))
                .map(List::of).collect(Collectors.toList());
        STRtree none = new STRtree();
        List<List<Draft>> results = solve(singles, i -> none, deadline(System.nanoTime(), CITY_DEADLINE_S), null);
        for (int i = 0; i < shaped.size(); i++) {
            Draft draft = shaped.get(i);
            List<Tree> added = joined(results, i, draft.trees);
            if (added.isEmpty()) {
                continue;
            }
            List<Tree> trees = new ArrayList<>(draft.trees);
            trees.addAll(added);
            try {
                List<FutureOks> unconnected = missing(trees);
                shaped.set(i, new Draft(trees, unconnected, assembler.assemble(draft.variant.getId(), i + 1, trees, unconnected)));
                log.info("turned: variant {} detached {} connected again {}", draft.variant.getId(), singles.size(), added.size());
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.info("turned: variant {} not assembled with detached points: {}", draft.variant.getId(), e.getMessage());
            }
        }
    }

    private List<Tree> ranked(List<Tree> trees) {
        return district ? basis(trees) : trees;
    }

    private static Tree basis(Tree tree) {
        return tree.unsquared != null ? tree.unsquared : tree;
    }

    private static List<Tree> basis(List<Tree> trees) {
        return trees.stream().map(VariantEnumerator::basis).collect(Collectors.toList());
    }

    private void unsquaredWhereTouching(List<Tree> trees) {
        int kept = 0;
        for (int i = 0; i < trees.size(); i++) {
            Tree tree = trees.get(i);
            if (tree.unsquared == null) {
                continue;
            }
            Envelope around = new Envelope(tree.envelope());
            around.expandBy(TREES_APART_M + 1);
            List<Tree> close = new ArrayList<>();
            for (int j = 0; j < trees.size(); j++) {
                if (j != i && trees.get(j).envelope().intersects(around)) {
                    close.add(trees.get(j));
                }
            }
            if (!compatible(tree, close)) {
                trees.set(i, tree.unsquared);
                kept++;
            }
        }
        log.info("city: squared trees kept unsquared (touching others) {}", kept);
    }

    private static List<Tree> assembled(List<Tree> trees, Variant variant) {
        Set<String> left = new HashSet<>(variant.getSummary().getUnconnectedOksIds());
        return trees.stream().filter(tree -> tree.connected().stream().noneMatch(c -> left.contains(c.getOksId())))
                .collect(Collectors.toList());
    }

    List<List<Tree>> recombined(List<List<Tree>> variants, double[] scores,
            java.util.function.Function<List<Tree>, Variant> assemble) {
        long started = System.nanoTime();
        int best = IntStream.range(0, scores.length).reduce((a, b) -> scores[b] < scores[a] ? b : a).orElse(0);
        List<List<Tree>> result = new ArrayList<>();
        for (int k = 0; k < variants.size(); k++) {
            List<Tree> trees = new ArrayList<>(variants.get(k));
            Quadtree index = new Quadtree();
            Map<String, List<Tree>> byUnit = new HashMap<>();
            for (Tree tree : trees) {
                index.insert(tree.envelope(), tree);
                byUnit.computeIfAbsent(tree.root.key, any -> new ArrayList<>()).add(tree);
            }
            int swaps = 0;
            for (int j = 0; j < variants.size(); j++) {
                if (j == k) {
                    continue;
                }
                for (List<List<Tree>> part : parts(trees, variants.get(j))) {
                    List<Tree> replaced = part.get(0);
                    List<Tree> offer = part.get(1);
                    if (offer.isEmpty() || k != best && !replaced.isEmpty()) {
                        continue;
                    }
                    Set<String> covered = new HashSet<>();
                    offer.forEach(tree -> tree.connected().forEach(c -> covered.add(c.getOksId())));
                    Set<String> had = new HashSet<>();
                    replaced.forEach(tree -> tree.connected().forEach(c -> had.add(c.getOksId())));
                    if (!covered.containsAll(had)) {
                        continue;
                    }
                    double penalties = 0;
                    for (String id : covered) {
                        if (!had.contains(id)) {
                            penalties += rules.score(rules.penalty(oksById.get(id).getFlowTph()), 0);
                        }
                    }
                    Set<String> units = new HashSet<>();
                    replaced.forEach(tree -> units.add(tree.root.key));
                    offer.forEach(tree -> units.add(tree.root.key));
                    Set<Tree> gone = Collections.newSetFromMap(new IdentityHashMap<>());
                    gone.addAll(replaced);
                    List<Tree> added = new ArrayList<>();
                    for (Tree tree : offer) {
                        Envelope around = new Envelope(tree.envelope());
                        around.expandBy(TREES_APART_M + 1);
                        List<Tree> close = new ArrayList<>();
                        for (Object item : index.query(around)) {
                            Tree t = (Tree) item;
                            if (!gone.contains(t) && t.envelope().intersects(around)) {
                                close.add(t);
                            }
                        }
                        added.stream().filter(t -> t.envelope().intersects(around)).forEach(close::add);
                        if (!compatible(tree, close)) {
                            added = null;
                            break;
                        }
                        added.add(tree);
                    }
                    if (added == null) {
                        continue;
                    }
                    List<Tree> before = units.stream().flatMap(unit -> byUnit.getOrDefault(unit, List.of()).stream())
                            .collect(Collectors.toList());
                    List<Tree> after = before.stream().filter(tree -> !gone.contains(tree)).collect(Collectors.toList());
                    after.addAll(added);
                    if (shape(after)[0] > shape(before)[0] || shape(after)[1] > shape(before)[1]
                            || !((before.isEmpty() ? 0 : unitScore(before)) - unitScore(after) + penalties > SWAP_GAIN)) {
                        continue;
                    }
                    trees.removeIf(gone::contains);
                    trees.addAll(added);
                    for (Tree tree : replaced) {
                        index.remove(tree.envelope(), tree);
                        byUnit.get(tree.root.key).remove(tree);
                    }
                    for (Tree tree : added) {
                        index.insert(tree.envelope(), tree);
                        byUnit.computeIfAbsent(tree.root.key, any -> new ArrayList<>()).add(tree);
                    }
                    swaps++;
                }
            }
            if (swaps > 0) {
                double after;
                try {
                    after = exact(assemble.apply(trees));
                } catch (IllegalStateException | IllegalArgumentException e) {
                    after = Double.NaN;
                }
                if (after < scores[k]) {
                    log.info("recombined: вариант {} заменил {} групп деревьев деревьями других вариантов, S {} вместо {}",
                            k + 1, swaps, after, scores[k]);
                } else {
                    log.info("recombined: вариант {}: деревья других вариантов не снижают S ({} против {})", k + 1, after,
                            scores[k]);
                    swaps = 0;
                }
            }
            result.add(swaps > 0 ? trees : variants.get(k));
        }
        log.info("recombined: elapsed={}ms", (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private static int[] shape(List<Tree> trees) {
        int turns = 0;
        int count = 0;
        for (Tree tree : trees) {
            for (Tree.Edge edge : tree.edges) {
                Coordinate[] c = edge.line.getCoordinates();
                double last = 0;
                Coordinate at = null;
                for (int i = 1; i + 1 < c.length; i++) {
                    double turn = Math.toDegrees(org.locationtech.jts.algorithm.Angle.normalize(
                            Math.atan2(c[i + 1].y - c[i].y, c[i + 1].x - c[i].x) - Math.atan2(c[i].y - c[i - 1].y, c[i].x - c[i - 1].x)));
                    if (Math.abs(turn) < TreeBuilder.MIN_TURN_DEG) {
                        continue;
                    }
                    turns++;
                    double sum = Math.abs(turn + last);
                    if (at != null && turn * last > 0 && sum >= PAIR_MIN_DEG && sum <= PAIR_MAX_DEG && at.distance(c[i]) < PAIR_LINK_M) {
                        count++;
                    }
                    last = turn;
                    at = c[i];
                }
            }
        }
        return new int[] {turns, count};
    }

    private static List<List<List<Tree>>> parts(List<Tree> own, List<Tree> other) {
        List<Tree> all = new ArrayList<>(own);
        Set<Tree> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        mine.addAll(own);
        other.stream().filter(tree -> !mine.contains(tree) && !tree.connected().isEmpty()).forEach(all::add);
        int[] parent = IntStream.range(0, all.size()).toArray();
        Map<String, Integer> first = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            for (ConnectionPoint c : all.get(i).connected()) {
                Integer seen = first.putIfAbsent(c.getOksId(), i);
                if (seen != null) {
                    parent[root(parent, i)] = root(parent, seen);
                }
            }
        }
        Map<Integer, List<List<Tree>>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            List<List<Tree>> part = byRoot.computeIfAbsent(root(parent, i), any -> List.of(new ArrayList<>(), new ArrayList<>()));
            part.get(i < own.size() ? 0 : 1).add(all.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void lost(List<Tree> before, List<Tree> after, Set<ConnectionPoint> lost) {
        Set<ConnectionPoint> kept = new HashSet<>();
        after.forEach(tree -> kept.addAll(tree.connected()));
        before.forEach(tree -> tree.connected().stream().filter(c -> !kept.contains(c)).forEach(lost::add));
    }

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

    private static Variant renumbered(Variant variant, String id, int rank) {
        String from = "v" + variant.getId() + "_";
        String to = "v" + id + "_";
        java.util.function.UnaryOperator<String> rename = value -> value.startsWith(from) ? to + value.substring(from.length()) : value;
        List<NewSegment> segments = new ArrayList<>();
        for (NewSegment s : variant.getSegments()) {
            segments.add(new NewSegment(rename.apply(s.getId()), id, s.getGeometry(), rename.apply(s.getStartNodeId()),
                    rename.apply(s.getEndNodeId()), s.getFlowTph(), s.getDiameter(), s.getLength(), s.getLayingMethod(),
                    s.getDepthStart(), s.getDepthEnd(), s.getCost(), s.getDepthCrossings(), s.isManualReview()));
        }
        List<NewChamber> chambers = new ArrayList<>();
        for (NewChamber c : variant.getChambers()) {
            chambers.add(new NewChamber(rename.apply(c.getId()), id, c.getGeometry(), c.getDiameter(), c.getCost()));
        }
        List<TechnicalNode> nodes = new ArrayList<>();
        for (TechnicalNode n : variant.getNodes()) {
            nodes.add(new TechnicalNode(rename.apply(n.getId()), id, n.getGeometry(), n.getReason()));
        }
        VariantSummary s = variant.getSummary();
        VariantSummary summary = new VariantSummary(rename.apply(s.getId()).replace("summary_" + variant.getId(), "summary_" + id),
                id, rank, s.getConstructionCost(), s.getChamberConstructionCost(), s.getExistingChamberTieInCount(),
                s.getExistingChamberTieInCost(), s.getUnconnectedPenalty(), s.getCalculatedCost(), s.getNewNetworkLength(),
                s.getScore(), s.getUnconnectedOksIds());
        return new Variant(id, segments, chambers, nodes, summary);
    }

    private List<FutureOks> unconnected(Draft draft, List<Tree> trees) {
        int before = draft.trees.stream().mapToInt(tree -> tree.connected().size()).sum();
        return trees.stream().mapToInt(tree -> tree.connected().size()).sum() < before ? missing(trees) : draft.unconnected;
    }

    private List<FutureOks> missing(List<Tree> trees) {
        Set<String> connected = new HashSet<>();
        trees.forEach(tree -> tree.connected().forEach(connection -> connected.add(connection.getOksId())));
        return input.getFutureOks().parallelStream().filter(oks -> !connected.contains(oks.getId()))
                .collect(Collectors.toList());
    }

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

    private static long deadline(long from, long seconds) {
        return seconds > 0 ? from + seconds * 1_000_000_000L : Long.MAX_VALUE;
    }

    private List<List<Draft>> solve(List<List<ConnectionPoint>> districts, java.util.function.IntFunction<STRtree> directIndex,
            long deadlineNanos, Set<ConnectionPoint> detached) {
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
                    district.direct = directIndex.apply(index);
                    List<Draft> drafts = new ArrayList<>();
                    List<Draft> found = district.picked();
                    List<Draft> candidates = EXACT ? district.exact(found) : List.of();
                    List<Draft> picked = district.shaped(found, drafts);
                    if (!candidates.isEmpty()) {
                        district.exacted(picked, drafts, candidates);
                    }
                    for (int d = 0; detached != null && d < drafts.size(); d++) {
                        lost(picked.get(d).trees, drafts.get(d).trees, detached);
                    }
                    drafts.sort(Comparator.comparingDouble(Draft::score));
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
                log.warn("city: {} районов из {} не начаты до срока, их точки остаются без сети", skipped.get(),
                        districts.size());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Расчёт районов прерван", e);
        } finally {
            pool.shutdownNow();
        }
    }

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

    private VariantEnumerator district(List<ConnectionPoint> connections) {
        List<FutureOks> oks = new ArrayList<>();
        connections.forEach(connection -> oks.add(oksById.get(connection.getOksId())));
        InputData part = new InputData(input.getSources(), input.getSegments(), input.getChambers(), oks, connections,
                List.of(), List.of(), List.of(), List.of(), input.getNumericIds());
        return new VariantEnumerator(part, rules, finder, obstacleIndex, specials, buildingByConnection, CITY_CACHE_MB, true);
    }

    private List<Tree> joined(List<List<Draft>> results, int k, List<Tree> ties) {
        List<Tree> accepted = new ArrayList<>();
        Quadtree index = new Quadtree();
        for (Tree tree : ties) {
            index.insert(geometry(basis(tree)).getEnvelopeInternal(), tree);
        }
        Set<String> connected = new HashSet<>();
        ties.forEach(tree -> tree.connected().forEach(c -> connected.add(c.getOksId())));
        int dropped = 0;
        for (List<Draft> drafts : results) {
            if (drafts.isEmpty()) {
                continue;
            }
            for (Tree tree : drafts.get(Math.min(k, drafts.size() - 1)).trees) {
                if (tree.connected().stream().anyMatch(c -> connected.contains(c.getOksId()))) {
                    continue;
                }
                Envelope envelope = geometry(basis(tree)).getEnvelopeInternal();
                Envelope around = new Envelope(envelope);
                around.expandBy(TREES_APART_M + 1);
                List<Tree> near = new ArrayList<>();
                for (Object item : index.query(around)) {
                    if (geometry(basis((Tree) item)).getEnvelopeInternal().intersects(around)) {
                        near.add((Tree) item);
                    }
                }
                if (compatible(basis(tree), basis(near))) {
                    accepted.add(tree);
                    tree.connected().forEach(c -> connected.add(c.getOksId()));
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


    private List<List<Tree>> retried(List<List<Tree>> variants, List<ConnectionPoint> near, long deadlineNanos) {
        long started = System.nanoTime();
        Map<String, Integer> byKey = new LinkedHashMap<>();
        List<List<ConnectionPoint>> subsets = new ArrayList<>();
        List<List<Tree>> neighbours = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        List<List<Map.Entry<List<Tree>, Integer>>> tasks = new ArrayList<>();
        for (int k = 0; k < variants.size(); k++) {
            List<Tree> trees = variants.get(k);
            List<Map.Entry<List<Tree>, Integer>> own = new ArrayList<>();
            int owner = k;
            tasks.add(own);
            Set<String> connected = new HashSet<>();
            trees.forEach(tree -> tree.connected().forEach(connection -> connected.add(connection.getOksId())));
            List<ConnectionPoint> left = near.stream().filter(connection -> !connected.contains(connection.getOksId()))
                    .collect(Collectors.toList());
            if (left.isEmpty()) {
                continue;
            }
            STRtree index = new STRtree();
            trees.forEach(tree -> index.insert(basis(tree).envelope(), tree));
            for (List<ConnectionPoint> group : districts(left)) {
                Envelope around = new Envelope();
                group.forEach(connection -> around.expandToInclude(connection.getGeometry().getCoordinate()));
                around.expandBy(RETRY_NEAR_M);
                Geometry points = factory.createMultiPoint(group.stream().map(ConnectionPoint::getGeometry).toArray(Point[]::new));
                List<Tree> close = new ArrayList<>();
                for (Object item : index.query(around)) {
                    Tree tree = (Tree) item;
                    if (!tree.connected().isEmpty() && geometry(basis(tree)).distance(points) <= RETRY_NEAR_M) {
                        close.add(tree);
                    }
                }
                if (close.isEmpty()) {
                    continue;
                }
                close.sort(Comparator.comparingDouble((Tree tree) -> geometry(basis(tree)).distance(points))
                        .thenComparing(tree -> tree.connected().get(0).getId()));
                List<Tree> taken = new ArrayList<>(close.subList(0, Math.min(RETRY_TREES, close.size())));
                List<ConnectionPoint> subset = new ArrayList<>(group);
                taken.forEach(tree -> subset.addAll(tree.connected()));
                String key = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","))
                        + taken.stream().map(tree -> "#" + System.identityHashCode(tree)).sorted().collect(Collectors.joining());
                int task = byKey.computeIfAbsent(key, any -> {
                    subsets.add(subset);
                    neighbours.add(taken);
                    owners.add(owner);
                    return subsets.size() - 1;
                });
                own.add(Map.entry(taken, task));
            }
        }
        List<List<Draft>> results = subsets.isEmpty() ? List.of() : solve(subsets, i -> {
            Set<Tree> replaced = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            replaced.addAll(neighbours.get(i));
            STRtree others = new STRtree();
            for (Tree tree : variants.get(owners.get(i))) {
                if (!replaced.contains(tree)) {
                    others.insert(tree.envelope(), Map.entry(0, tree));
                }
            }
            others.build();
            return others;
        }, deadlineNanos, null);
        List<List<Tree>> result = new ArrayList<>();
        for (int k = 0; k < variants.size(); k++) {
            List<Tree> trees = new ArrayList<>(variants.get(k));
            Set<Tree> present = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            present.addAll(trees);
            int improved = 0;
            int attached = 0;
            for (Map.Entry<List<Tree>, Integer> task : tasks.get(k)) {
                List<Draft> drafts = results.get(task.getValue());
                List<Tree> old = task.getKey();
                if (drafts.isEmpty() || !present.containsAll(old)) {
                    continue;
                }
                List<Tree> fresh = drafts.get(0).trees;
                int before = old.stream().mapToInt(tree -> tree.connected().size()).sum();
                int after = fresh.stream().mapToInt(tree -> tree.connected().size()).sum();
                if (after <= before) {
                    continue;
                }
                trees.removeIf(old::contains);
                List<Tree> added = new ArrayList<>();
                for (Tree tree : fresh) {
                    Envelope around = new Envelope(basis(tree).envelope());
                    around.expandBy(TREES_APART_M + 1);
                    List<Tree> close = trees.stream().filter(t -> basis(t).envelope().intersects(around))
                            .collect(Collectors.toList());
                    close.addAll(added);
                    if (!compatible(basis(tree), basis(close))) {
                        added = null;
                        break;
                    }
                    added.add(tree);
                }
                if (added == null) {
                    trees.addAll(old);
                    continue;
                }
                trees.addAll(added);
                present.removeAll(old);
                present.addAll(added);
                improved++;
                attached += after - before;
            }
            result.add(trees);
            log.info("city: retry variant {} groups={} improved={} connected+{}", k + 1, tasks.get(k).size(), improved,
                    attached);
        }
        log.info("city: retry tasks={} elapsed={}s", subsets.size(), (System.nanoTime() - started) / 1_000_000_000L);
        return result;
    }


    private List<List<ConnectionPoint>> regions() {
        List<List<ConnectionPoint>> groups = groups(new ArrayList<>(connectionByOks.values()));
        List<Region> built = groups.parallelStream().map(Region::new).collect(Collectors.toList());
        for (int i = 0; i < groups.size(); i++) {
            List<ConnectionPoint> group = groups.get(i);
            Region region = built.get(i);
            for (ConnectionPoint connection : group) {
                regionByConnection.put(connection.getId(), region);
            }
        }
        assembler = new NetworkAssembler(input, rules, specials, oksById);
        return groups;
    }

    private List<Draft> picked() {
        List<List<ConnectionPoint>> groups = regions();
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
        if (!drafts.isEmpty()) {
            drafts.add(search(drafts.get(0), district ? CITY_BUDGET : SEARCH_BUDGET));
        }
        List<Draft> picked = pick(drafts);
        if (picked.size() < 2) {
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
            others(picked.get(0), drafts, true);
            picked = pick(drafts);
        }
        if (picked.size() == 2 && !district) {
            others(picked.get(1), drafts, false);
            picked = pick(drafts);
        }
        return picked;
    }

    private void others(Draft draft, List<Draft> drafts, boolean slide) {
        List<List<ConnectionPoint>> blocks = blocksOf(draft);
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

    private List<Draft> pick(List<Draft> drafts) {
        drafts.removeIf(Objects::isNull);
        int fewest = drafts.stream().mapToInt(draft -> draft.unconnected.size()).min().orElse(0);
        drafts.removeIf(draft -> draft.unconnected.size() > fewest);
        drafts.sort(Comparator.comparingDouble(Draft::score));
        List<Draft> picked = new ArrayList<>();
        for (Draft draft : drafts) {
            if (picked.size() < MAX_VARIANTS && picked.stream().noneMatch(p -> sameRoute(p, draft))) {
                picked.add(draft);
            }
        }
        return picked;
    }

    private Draft draft(List<List<ConnectionPoint>> partition, java.util.function.ToIntFunction<List<ConnectionPoint>> alternative) {
        return draft(partition, alternative, true);
    }

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
            if (subset.size() > 1 && flow(subset) > rules.maxCapacityTph()) {
                subset.forEach(connection -> queue.add(List.of(connection)));
                continue;
            }
            List<Option> options = options(subset, slide);
            int rank = alternative.applyAsInt(subset);
            int start = rank > 0 ? alternativeIndex(options, rank, otherTieM()) : 0;
            Tree chosen = null;
            for (int k = 0; k < options.size() && chosen == null; k++) {
                Option option = options.get((start + k) % options.size());
                if (compatible(option.tree, accepted)) {
                    chosen = option.tree;
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
            return null;
        }
    }

    private double separately(Option option, boolean slide) {
        double score = option.score;
        for (ConnectionPoint left : option.tree.unconnected) {
            double penalty = rules.score(rules.penalty(oksById.get(left.getOksId()).getFlowTph()), 0);
            List<Option> single = options(List.of(left), slide);
            score += (single.isEmpty() ? penalty : Math.min(penalty, single.get(0).score)) - penalty;
        }
        return score;
    }

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

    private String optionsKey(List<ConnectionPoint> subset, boolean slide) {
        return subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining("|")) + (slide ? "" : "#fixed")
                + (extended ? "#wide" : "");
    }

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
                log.debug("prefetch: {} не посчитано: {}", subset.getKey(), e.getCause().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Деревья подмножеств прерваны", e);
            }
        }
    }

    private List<Option> computeOptions(Region region, List<ConnectionPoint> subset, boolean slide) {
        List<Point> points = points(subset);
        double flow = flow(subset);
        if (flow > rules.maxCapacityTph()) {
            return new ArrayList<>();
        }
        Diameter byFlow = rules.diameterFor(flow);
        Diameter step = rules.nextDiameter(byFlow.getDn());
        int blockDn = district ? region.dn : Math.min(region.dn, step != null ? step.getDn() : byFlow.getDn());
        List<TieCandidate> all = reach(finder.find(points, blockDn), points);
        List<Option> options = options(region, blockDn, region.area, subset, false, all, false, slide);
        if (subset.size() > 1 && !district) {
            String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
            for (int tried = blockDn; incomplete(plain(options)) && rejectedDn.getOrDefault(label, 0) > tried; ) {
                tried = rejectedDn.get(label);
                options.addAll(options(region, tried, region.area, subset, false, candidates(points, flow, tried), false, slide));
            }
        }
        if (subset.size() == 1) {
            if (extended && poor(plain(options), points.get(0), all)) {
                Set<String> known = all.stream().map(TieCandidate::nodeKey).collect(Collectors.toSet());
                List<TieCandidate> direct = finder.direct(points.get(0), blockDn).stream()
                        .filter(candidate -> !known.contains(candidate.nodeKey())).collect(Collectors.toList());
                if (!direct.isEmpty()) {
                    options.addAll(options(region, blockDn, region.area, subset, false, direct, false, slide));
                }
            }
            int ownDn = rules.diameterFor(oksById.get(subset.get(0).getOksId()).getFlowTph()).getDn();
            if (ownDn < blockDn) {
                List<TieCandidate> own = candidates(points, flow, ownDn);
                if (detour(plain(options), points.get(0), own)) {
                    options.addAll(options(region, ownDn, region.area, subset, true, own, false, slide));
                }
            }
            if (incomplete(plain(options)) && !district) {
                options.addAll(options(region, blockDn, region.wideArea, subset, false,
                        candidates(points, flow, blockDn), false, slide));
            }
            if (incomplete(plain(options))) {
                Envelope wide = district ? region.area : region.wideArea;
                options.addAll(options(region, blockDn, wide, subset, false, candidates(points, flow, blockDn), true, slide));
            }
            String label = subset.get(0).getId();
            for (int tried = blockDn; !district && incomplete(plain(options)) && rejectedDn.getOrDefault(label, 0) > tried; ) {
                tried = rejectedDn.get(label);
                options.addAll(options(region, tried, region.wideArea, subset, false, candidates(points, flow, tried), false, slide));
            }
            boolean none = incomplete(plain(options));
            if (SCAN && (none || extended && poor(plain(options), points.get(0), all))) {
                Envelope wide = district ? region.area : region.wideArea;
                double radius = (none || extended) && !district ? Math.max(SCAN_RADIUS_M, rules.diameter(blockDn).getMaxLengthM()) : SCAN_RADIUS_M;
                List<TieCandidate> scanned = scanned(region, blockDn, wide, subset.get(0), radius);
                if (!scanned.isEmpty()) {
                    options.addAll(options(region, blockDn, wide, subset, false, scanned, false, slide));
                }
            }
            if (incomplete(options)) {
                Envelope wide = district ? region.area : region.wideArea;
                options.addAll(options(region, blockDn, wide, subset, false, candidates(points, flow, blockDn), false,
                        slide, true));
            }
        }
        options.sort(Comparator.comparingDouble(option -> option.score));
        List<Option> plain = plain(options);
        if (!plain.isEmpty() && alternativeIndex(plain, 1, otherTieM()) == 0) {
            List<TieCandidate> along = finder.along(plain.get(0).tree.tie, blockDn, ALONG_FACTOR * otherTieM());
            options.addAll(options(region, blockDn, region.area, subset, false, along, false, slide));
            options.sort(Comparator.comparingDouble(option -> option.score));
            if (alternativeIndex(plain(options), 1, otherTieM()) == 0) {
                options.addAll(options(region, blockDn, region.area, subset, false, along, false, false));
                options.sort(Comparator.comparingDouble(option -> option.score));
            }
        }
        return options;
    }

    private List<TieCandidate> scanned(Region region, int dn, Envelope area, ConnectionPoint connection, double radius) {
        Router router = region.router(dn, area);
        List<TieCandidate> left = new ArrayList<>();
        Set<String> ignored = new HashSet<>();
        for (TieCandidate candidate : finder.around(connection.getGeometry(), dn, radius, SCAN_STEP_M)) {
            Coordinate at = candidate.getPoint().getCoordinate();
            if (area.contains(at) && (region.corridor() == null || region.corridor().covers(candidate.getPoint()))
                    && !router.obstacles().insideForbid(at)) {
                left.add(candidate);
                ignored.addAll(candidate.getIgnored());
            }
        }
        if (left.isEmpty()) {
            return List.of();
        }
        Coordinate exit = builder.exit(router, dn, area, left.get(0), connection);
        Coordinate start = exit != null ? exit : connection.getGeometry().getCoordinate();
        left.sort(Comparator.comparingDouble(candidate -> candidate.getPoint().getCoordinate().distance(start)));
        List<TieCandidate> best = new ArrayList<>();
        while (best.size() < SCAN_TRIES && !left.isEmpty()) {
            Router.Choice choice = router.choose(start,
                    left.stream().map(TieCandidate::getPoint).collect(Collectors.toList()), ignored, Double.POSITIVE_INFINITY);
            if (choice == null) {
                break;
            }
            TieCandidate picked = left.stream().filter(c -> c.getPoint().getCoordinate().equals2D(choice.target()))
                    .findFirst().orElseThrow();
            best.add(picked);
            left.removeIf(c -> c.getExistingObjectId().equals(picked.getExistingObjectId()));
        }
        log.info("scan: {} picked={}", connection.getId(), best.stream().map(TieCandidate::nodeKey).collect(Collectors.toList()));
        return best;
    }

    private List<TieCandidate> candidates(List<Point> points, double flow, int dn) {
        return reach(finder.find(points, dn), points);
    }

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

    private static List<Option> plain(List<Option> options) {
        return options.stream().filter(option -> !option.linked).collect(Collectors.toList());
    }

    private static boolean incomplete(List<Option> options) {
        return options.stream().allMatch(option -> !option.tree.unconnected.isEmpty());
    }

    private Draft rescued(Draft draft, boolean shorten) {
        if (district) {
            return draft;
        }
        List<Tree> trees = new ArrayList<>(draft.trees);
        boolean changed = false;
        extended = true;
        try {
            for (FutureOks oks : draft.unconnected) {
                ConnectionPoint connection = connectionByOks.get(oks.getId());
                Tree tree = connection == null ? null : compatibleTree(List.of(connection), trees);
                if (tree != null) {
                    trees.add(tree);
                    changed = true;
                } else if (connection != null) {
                    changed |= joined(connection, trees);
                }
            }
            for (int i = 0; shorten && i < trees.size(); i++) {
                Tree tree = trees.get(i);
                List<ConnectionPoint> connected = tree.connected();
                if (connected.size() != 1 || !tree.unconnected.isEmpty() || !poor(tree, connected.get(0))) {
                    continue;
                }
                List<Tree> others = new ArrayList<>(trees);
                others.remove(i);
                Tree better = compatibleTree(connected, others);
                if (better == null || better == tree) {
                    continue;
                }
                List<Tree> trial = new ArrayList<>(trees);
                trial.set(i, better);
                if (score(trial) < score(trees) - IMPROVE_EPS) {
                    trees = trial;
                    changed = true;
                }
            }
        } finally {
            extended = false;
        }
        if (!changed) {
            return draft;
        }
        List<FutureOks> unconnected = missing(trees);
        try {
            return new Draft(trees, unconnected, assembler.assemble(draft.variant.getId(), 0, trees, unconnected));
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.info("rescue: черновик не собран: {}", e.getMessage());
            return draft;
        }
    }

    private boolean joined(ConnectionPoint connection, List<Tree> trees) {
        List<Tree> near = new ArrayList<>(trees);
        near.sort(Comparator.comparingDouble(tree -> tree.geometry().distance(connection.getGeometry())));
        extended = false;
        try {
            for (Tree tree : near.subList(0, Math.min(JOIN_TREES, near.size()))) {
                List<ConnectionPoint> union = new ArrayList<>(tree.connected());
                union.add(connection);
                List<Tree> others = new ArrayList<>(trees);
                others.remove(tree);
                Tree joined = compatibleTree(union, others);
                if (joined != null) {
                    trees.set(trees.indexOf(tree), joined);
                    return true;
                }
            }
            return false;
        } finally {
            extended = true;
        }
    }

    private Tree compatibleTree(List<ConnectionPoint> subset, List<Tree> trees) {
        for (Option option : options(subset, true)) {
            if (option.tree.unconnected.isEmpty() && compatible(option.tree, trees)) {
                return option.tree;
            }
        }
        return null;
    }

    private double score(List<Tree> trees) {
        try {
            return assembler.assemble("0", 0, trees, missing(trees)).getSummary().getScore();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private boolean poor(Tree tree, ConnectionPoint connection) {
        Point point = connection.getGeometry();
        double straight = finder.find(List.of(point), rules.diameterFor(oksById.get(connection.getOksId()).getFlowTph()).getDn())
                .stream().mapToDouble(candidate -> candidate.getPoint().distance(point)).min().orElse(0);
        return tree.length() > POOR_RATIO * straight + POOR_EXTRA_M;
    }

    private static boolean poor(List<Option> options, Point point, List<TieCandidate> candidates) {
        double shortest = options.stream().filter(option -> option.tree.unconnected.isEmpty())
                .mapToDouble(option -> option.tree.length()).min().orElse(Double.POSITIVE_INFINITY);
        double straight = candidates.stream().mapToDouble(candidate -> candidate.getPoint().distance(point))
                .min().orElse(0);
        return shortest > POOR_RATIO * straight + POOR_EXTRA_M;
    }

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

    private List<Option> options(Region region, int dn, Envelope area, List<ConnectionPoint> subset, boolean verify,
            List<TieCandidate> candidates, boolean fromPortalDirection, boolean slide) {
        return options(region, dn, area, subset, verify, candidates, fromPortalDirection, slide, false);
    }

    private List<Option> options(Region region, int dn, Envelope area, List<ConnectionPoint> subset, boolean verify,
            List<TieCandidate> candidates, boolean fromPortalDirection, boolean slide, boolean anySide) {
        String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
        double metreRub = rules.diameter(dn).getNewRubM() + rules.lengthWorthRub();
        Router router = region.router(dn, area);
        List<TieCandidate> ranked = cheapestCandidates(subset, candidates);
        List<TieCandidate> cheapest = new ArrayList<>(ranked.subList(0, Math.min(CANDIDATE_LIMIT, ranked.size())));
        Set<TieCandidate> treeless = ConcurrentHashMap.newKeySet();
        TreeBuilder.Graphs graphs = graphs(region, area);
        java.util.function.BiFunction<TieCandidate, Boolean, Option> shaped = (candidate, link) -> {
            Tree tree = builder.build(router, dn, area, candidate, subset, rules.chamberCost(dn) / metreRub,
                    rules.tieInCost() / metreRub, fromPortalDirection, graphs, link, anySide);
            if (tree.edges.isEmpty()) {
                log.debug("options: subset={} tie={} нет дерева", label, candidate.nodeKey());
                treeless.add(candidate);
                return null;
            }
            Tree slid = slide ? slid(tree, router, dn, metreRub) : tree;
            Tree rerooted = slide ? rerooted(tree, router, dn, metreRub) : tree;
            rerooted = rerooted == tree ? tree : slid(rerooted, router, dn, metreRub);
            Option best = null;
            for (Tree shape : new java.util.LinkedHashSet<>(List.of(slid, rerooted, tree))) {
                if (!district && shape == tree && slid != tree && best != null) {
                    continue;
                }
                Tree cut = builder.cut(shape, router, dn, graphs, TreeBuilder.CUT_PASSES);
                Option option = cut == shape ? null
                        : option(cut, label, verify, area, dn, region, tree.narrow || cut.narrow);
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
        boolean link = subset.size() == 1;
        List<Option> options = new ArrayList<>();
        for (int next = cheapest.size(); !cheapest.isEmpty(); ) {
            java.util.stream.Stream<TieCandidate> stream = district || !PARALLEL ? cheapest.stream() : cheapest.parallelStream();
            options.addAll(stream.map(candidate -> {
                Option best = shaped.apply(candidate, link);
                if (best != null && best.turnStuck) {
                    best.withLinks = () -> shaped.apply(candidate, true);
                }
                return best;
            }).filter(Objects::nonNull).collect(Collectors.toList()));
            int crowded = (int) cheapest.stream().filter(candidate -> treeless.contains(candidate) && finder.crowded(candidate)).count();
            int more = (extended || subset.size() <= CONTINUE_MAX) && options.isEmpty() && next < 2 * CANDIDATE_LIMIT ? CANDIDATE_LIMIT : crowded;
            cheapest = new ArrayList<>(ranked.subList(next, Math.min(next + more, ranked.size())));
            next += cheapest.size();
        }
        options.sort(Comparator.comparingDouble(option -> option.score));
        return options;
    }

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

    private List<Draft> relaid(List<Draft> picked) {
        long started = System.nanoTime();
        List<Draft> moved = picked.parallelStream().map(this::relaid).collect(Collectors.toList());
        List<Draft> result = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Draft draft = moved.get(i);
            for (int j = 0; j < i && draft != picked.get(i); j++) {
                if (sameRoute(draft, result.get(j))) {
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

    private Draft relaid(Draft draft) {
        List<Tree> trees = new ArrayList<>();
        for (Tree tree : draft.trees) {
            List<ConnectionPoint> subset = tree.connected();
            Option start = null;
            Region region = null;
            int dn = 0;
            String label = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining(","));
            if (!JunctionMover.junctions(tree).isEmpty()) {
                region = regionByConnection.get(subset.get(0).getId());
                Diameter byFlow = rules.diameterFor(flow(subset));
                Diameter step = rules.nextDiameter(byFlow.getDn());
                dn = Math.min(region.dn, step != null ? step.getDn() : byFlow.getDn());
                start = option(tree, label, false, region.area, dn, region, tree.narrow);
            }
            Tree moved = start == null ? tree : relaid(start, region.router(dn, region.area), dn, label, region).tree;
            Shaping shaping = shapings.get(tree);
            if (moved != tree && shaping != null) {
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

    private Option relaid(Option start, Router router, int dn, String label, Region region) {
        TreeBuilder.Graphs graphs = graphs(region, region.area);
        Tree tree = start.tree;
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

    private double searchScore(Variant variant) {
        return Math.round(exact(variant) * 1000) / 1000.0;
    }

    private double exact(Variant variant) {
        VariantSummary summary = variant.getSummary();
        return rules.score(summary.getCalculatedCost(), summary.getNewNetworkLength());
    }

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
        int last = trunk.to.kind == Tree.Kind.CONNECTION ? coords.length - 1 : coords.length;
        for (int k = 1; k < last; k++) {
            rest -= coords[k - 1].distance(coords[k]);
            Point vertex = factory.createPoint(coords[k]);
            List<TieCandidate> candidates = new ArrayList<>(district ? List.of() : finder.find(List.of(vertex), dn));
            candidates.addAll(finder.ownPipes(tree.tie, vertex, dn));
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

    private double tiePenalty(TieCandidate tie, int dn, double metreRub) {
        return (tie.isChamber() ? rules.tieInCost() : rules.chamberCost(dn)) / metreRub;
    }

    private double tieCost(TieCandidate tie, int dn) {
        return tie.isChamber() ? rules.tieInCost() : rules.chamberCost(Math.max(dn, tie.getExistingDiameter()));
    }

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
            if (!holds && maxDn > graphDn && !district) {
                rejectedDn.merge(label, maxDn, Math::max);
            }
            return holds && raysClear(tree, alone) ? new Option(tree, searchScore(alone), exact(alone)) : null;
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.debug("options: subset={} tie={} отброшено: {}", label, tree.tie.nodeKey(), e.getMessage());
            Diameter byLength = rules.diameterForLength(tree.length());
            if (!district && byLength != null && byLength.getDn() > graphDn) {
                rejectedDn.merge(label, byLength.getDn(), Math::max);
            }
            return null;
        }
    }

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

    private Tree shifted(Tree tree, Shaping shaping, List<Tree> others) {
        Map<List<Tree.Edge>, TreeBuilder.Slide> cache = new HashMap<>();
        Set<TreeBuilder.Slide> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
        Option current = null;
        while (true) {
            Tree shape = current == null ? tree : current.tree;
            List<TreeBuilder.Slide> slides = builder.slides(shape, shaping.router.obstacles(), shaping.dn, metreRub(shape),
                    SLIDE_MIN_GAIN_RUB, cache, PARALLEL);
            if (current == null && !slides.isEmpty()) {
                current = shaping.option.apply(tree);
            }
            Option better = null;
            for (TreeBuilder.Slide slide : current == null ? List.<TreeBuilder.Slide>of() : slides) {
                if (rejected.contains(slide)) {
                    continue;
                }
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
        return sorted;
    }

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

    private List<Tree> rerouted(Draft draft) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(draft.trees);
        Map<Tree.Edge, Integer> dnByEdge = diameters(result);
        List<int[]> edges = new ArrayList<>();
        for (int t = 0; t < result.size(); t++) {
            for (int e = 0; e < result.get(t).edges.size(); e++) {
                edges.add(new int[] {t, e});
            }
        }
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

    private static boolean apart(java.util.Collection<LineString> lines, List<Tree> trees, Tree tree) {
        for (LineString line : lines) {
            for (Tree other : trees) {
                if (other != tree && near(line.getEnvelopeInternal(), other.envelope())
                        && geometry(other).distance(line) <= TREES_APART_M) {
                    return false;
                }
            }
        }
        return true;
    }

    private final Map<List<Tree>, Double> unitScores = memo();
    private final Map<List<Tree>, Map<Tree.Edge, Integer>> unitDiameters = memo();
    private static <K, V> Map<K, V> memo() {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > 4096;
            }
        });
    }

    private Map<Tree.Edge, Integer> diameters(List<Tree> trees) {
        Map<Tree.Edge, Integer> cached = unitDiameters.get(trees);
        if (cached == null) {
            cached = assembler.diameters(trees);
            unitDiameters.put(List.copyOf(trees), cached);
        }
        return cached;
    }

    private double unitScore(List<Tree> unit) {
        Double cached = unitScores.get(unit);
        if (cached == null) {
            cached = computedUnitScore(unit);
            unitScores.put(List.copyOf(unit), cached);
        }
        return cached;
    }

    private double computedUnitScore(List<Tree> unit) {
        try {
            VariantSummary summary = assembler.assemble("0", 0, unit, List.of()).getSummary();
            return rules.score(summary.getCalculatedCost(), summary.getNewNetworkLength());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.debug("rerouted: {} не собрано: {}", unit.get(0).root.key, e.getMessage());
            return Double.NaN;
        }
    }

    private boolean worse(Tree tree, List<Tree> before, Tree changed, List<Tree> after) {
        return thicker(tree, before, changed, after) || sharpPairs(changed, Set.of()) > sharpPairs(tree, Set.of());
    }

    private boolean thicker(Tree tree, List<Tree> before, Tree changed, List<Tree> after) {
        Map<Tree.Edge, Integer> dnBefore = diameters(before);
        Map<Tree.Edge, Integer> dnAfter = diameters(after);
        for (int e = 0; e < tree.edges.size(); e++) {
            if (dnAfter.get(changed.edges.get(e)) > dnBefore.get(tree.edges.get(e))) {
                return true;
            }
        }
        return false;
    }

    private Fresh rerouted(Tree tree, Tree.Edge edge, int dn) {
        Coordinate[] old = edge.line.getCoordinates();
        Region region = regionByConnection.get(tree.connected().get(0).getId());
        Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
        if (old.length == 2 || !area.contains(tree.envelope())) {
            return null;
        }
        Router router = null;
        for (Diameter graph = rules.diameter(dn); router == null && graph != null;
                graph = rules.nextDiameter(graph.getDn())) {
            router = region.built(graph.getDn() + "@" + area);
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

    private Map<Integer, LineString> sharpened(Tree tree, Map<Integer, LineString> change, Map<Integer, Fresh> found,
            boolean pathTurns) {
        return sharpened(tree, change, found, pathTurns, false);
    }

    private Map<Integer, LineString> sharpened(Tree tree, Map<Integer, LineString> change, Map<Integer, Fresh> found,
            boolean pathTurns, boolean bends) {
        List<LineString> lines = new ArrayList<>();
        for (int e = 0; e < tree.edges.size(); e++) {
            lines.add(change.getOrDefault(e, tree.edges.get(e).line));
        }
        Set<String> ignored = tree.tie.getIgnored();
        Set<Integer> loose = new HashSet<>();
        boolean again = true;
        while (again) {
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
                if (router.sharpen(found.get(e).zones, coords, ignored, exit, others, before, after, bends)) {
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

    private List<Tree> unkinked(List<Tree> trees) {
        return unkinked(trees, false);
    }

    private List<Tree> unkinked(List<Tree> trees, boolean evensOnly) {
        return unkinked(trees, evensOnly, Set.of());
    }

    private List<Tree> unkinked(List<Tree> trees, boolean evensOnly, Set<Tree> skip) {
        return unkinked(trees, evensOnly, skip, false);
    }

    private List<Tree> unkinked(List<Tree> trees, boolean evensOnly, Set<Tree> skip, boolean axes) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        double maxGainRub = TURN_TOLERANCE_S / rules.score(1, 0);
        for (int t = 0; t < result.size(); t++) {
            Tree first = result.get(t);
            if (JunctionMover.junctions(first).isEmpty() && first.tie.isChamber() || evensOnly && !microTurns(first)
                    || skip.contains(first)) {
                continue;
            }
            Region region = regionByConnection.get(first.connected().get(0).getId());
            Envelope area = region.area.contains(first.envelope()) ? region.area : region.wideArea;
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
                    dnByEdge = diameters(unit);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    break;
                }
                Map<Tree.Edge, Double> priceRub = new IdentityHashMap<>();
                dnByEdge.forEach((edge, dn) -> priceRub.put(edge, rules.diameter(dn).getNewRubM() + rules.lengthWorthRub()));
                double before = Double.NaN;
                for (Tree.Node junction : JunctionMover.junctions(tree)) {
                    List<Double> key = signature(tree, junction);
                    if (settled.contains(key)) {
                        continue;
                    }
                    for (TreeBuilder.Slide slide : unkinks(tree, junction, unit, region, area, dnByEdge, priceRub, maxGainRub,
                            evensOnly, axes)) {
                        if (!tried.add(List.of(junction.point.x, junction.point.y, slide.point.x, slide.point.y))) {
                            continue;
                        }
                        Tree changed = unkinked(tree, slide, tree.tie, dnByEdge, region, area, true);
                        List<Tree> others = new ArrayList<>(result);
                        others.remove(t);
                        if (changed == null || axes && !keepsAxes(tree, changed) || !compatible(changed, others)) {
                            continue;
                        }
                        List<Tree> attempt = new ArrayList<>();
                        for (Tree other : unit) {
                            attempt.add(other == tree ? changed : other);
                        }
                        before = Double.isNaN(before) ? unitScore(unit) : before;
                        if (!(unitScore(attempt) <= before + TURN_TOLERANCE_S) || worse(tree, unit, changed, attempt)) {
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
                if (!moved && !retieTried && unit.size() == 1 && !evensOnly && !axes) {
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
        log.info("unkinked: {}moves={} elapsed={}ms", evensOnly ? "evens " : axes ? "axes " : "", taken,
                (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private final Map<List<Object>, Object[]> unkinksMemo = memo();

    private List<TreeBuilder.Slide> unkinks(Tree tree, Tree.Node junction, List<Tree> unit, Region region, Envelope area,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, boolean evensOnly,
            boolean axes) {
        return slides(List.of("unkinks", junction.key, evensOnly, axes), tree, unit, () -> builder.unkinks(tree, junction,
                edge -> region.obstacles(dnByEdge.get(edge), area), dnByEdge, priceRub, maxGainRub, evensOnly,
                axes ? this::keepsAxes : null));
    }

    @SuppressWarnings("unchecked")
    private List<TreeBuilder.Slide> slides(List<Object> tag, Tree tree, List<Tree> unit,
            java.util.function.Supplier<List<TreeBuilder.Slide>> compute) {
        List<Object> key = new ArrayList<>(tag);
        key.add(unit.indexOf(tree));
        for (Tree other : unit) {
            key.add(other.tie);
            for (Tree.Edge edge : other.edges) {
                key.add(edge.from.key);
                key.add(edge.to.key);
                for (Coordinate c : edge.line.getCoordinates()) {
                    key.add(c.x);
                    key.add(c.y);
                }
            }
        }
        Object[] hit = unkinksMemo.get(key);
        if (hit == null) {
            List<TreeBuilder.Slide> slides = compute.get();
            unkinksMemo.put(key, new Object[] {tree, slides});
            return slides;
        }
        Tree was = (Tree) hit[0];
        List<TreeBuilder.Slide> slides = (List<TreeBuilder.Slide>) hit[1];
        if (was == tree) {
            return slides;
        }
        Map<Tree.Edge, Tree.Edge> edgeOf = new IdentityHashMap<>();
        Map<Tree.Node, Tree.Node> nodeOf = new IdentityHashMap<>();
        for (int e = 0; e < tree.edges.size(); e++) {
            edgeOf.put(was.edges.get(e), tree.edges.get(e));
            nodeOf.put(was.edges.get(e).from, tree.edges.get(e).from);
            nodeOf.put(was.edges.get(e).to, tree.edges.get(e).to);
        }
        List<TreeBuilder.Slide> result = new ArrayList<>();
        for (TreeBuilder.Slide slide : slides) {
            Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
            slide.lines.forEach((edge, line) -> lines.put(edgeOf.get(edge), line));
            if (lines.containsKey(null) || !nodeOf.containsKey(slide.junction)) {
                return compute.get();
            }
            result.add(new TreeBuilder.Slide(slide.gain, nodeOf.get(slide.junction), slide.point, lines));
        }
        return result;
    }

    private static boolean microTurns(Tree tree) {
        for (Tree.Edge in : tree.edges) {
            Coordinate[] c = in.line.getCoordinates();
            for (int v = 1; v + 1 < c.length; v++) {
                if (Router.micro(c[v - 1], c[v], c[v + 1])) {
                    return true;
                }
            }
            for (Tree.Edge out : tree.edges) {
                if (in.to.kind == Tree.Kind.JUNCTION && out.from == in.to
                        && Router.micro(c[c.length - 2], in.to.point, out.line.getCoordinateN(1))) {
                    return true;
                }
            }
        }
        return false;
    }

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

    private List<Tree> turned(List<Tree> trees) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        int forced = 0;
        double maxGainRub = TURN_TOLERANCE_S / rules.score(1, 0);
        for (int t = 0; t < result.size(); t++) {
            Tree first = result.get(t);
            Region region = regionByConnection.get(first.connected().get(0).getId());
            Envelope area = region.area.contains(first.envelope()) ? region.area : region.wideArea;
            Set<String> kneed = new HashSet<>();
            for (int sharp = sharpTurns(first, kneed).size(); sharp > 0 && area.contains(first.envelope()); ) {
                Tree tree = result.get(t);
                List<Tree> unit = new ArrayList<>();
                for (Tree other : result) {
                    if (other.root.key.equals(tree.root.key)) {
                        unit.add(other);
                    }
                }
                Map<Tree.Edge, Integer> dnByEdge;
                try {
                    dnByEdge = diameters(unit);
                } catch (IllegalStateException | IllegalArgumentException e) {
                    break;
                }
                Map<Tree.Edge, Double> priceRub = new IdentityHashMap<>();
                dnByEdge.forEach((edge, dn) -> priceRub.put(edge, rules.diameter(dn).getNewRubM() + rules.lengthWorthRub()));
                double before = unitScore(unit);
                List<Tree> others = new ArrayList<>(result);
                others.remove(t);
                Tree changed = null;
                for (Tree.Node junction : sharpTurns(tree, kneed)) {
                    java.util.function.Function<Tree.Edge, ObstacleSet> zones = edge -> region.obstacles(dnByEdge.get(edge), area);
                    List<TreeBuilder.Slide> slides = new ArrayList<>(slides(List.of("turnSlides", junction.key, maxGainRub),
                            tree, unit, () -> builder.turnSlides(tree, junction, zones, dnByEdge, priceRub, maxGainRub,
                                    TURN_STEP_M, TURN_MAX_M)));
                    if (TURN_GRID) {
                        slides.addAll(slides(List.of("turnMoves", junction.key, maxGainRub), tree, unit,
                                () -> builder.turnMoves(tree, junction, zones, dnByEdge, priceRub, maxGainRub)));
                    }
                    slides.sort(Comparator.comparingDouble(slide -> slide.gain));
                    for (TreeBuilder.Slide slide : slides) {
                        Tree attempt = unkinked(tree, slide, tree.tie, dnByEdge, region, area, true);
                        if (attempt == null || sharpTurns(attempt, kneed).size() >= sharp || !compatible(attempt, others)) {
                            continue;
                        }
                        List<Tree> after = new ArrayList<>();
                        for (Tree other : unit) {
                            after.add(other == tree ? attempt : other);
                        }
                        if (unitScore(after) <= before + TURN_TOLERANCE_S && !worse(tree, unit, attempt, after)) {
                            changed = attempt;
                            break;
                        }
                    }
                    if (changed != null) {
                        break;
                    }
                }
                if (changed == null) {
                    changed = unsharpened(tree, unit, others, dnByEdge, priceRub, region, area, kneed, false);
                    for (Tree.Node junction : changed == null ? sharpTurns(tree, kneed) : List.<Tree.Node>of()) {
                        Set<String> knee = new HashSet<>(kneed);
                        knee.add(junction.key);
                        changed = sharpTurns(tree, knee).size() < sharp ? tree
                                : unsharpened(tree, unit, others, dnByEdge, priceRub, region, area, knee, true);
                        if (changed != null) {
                            kneed = knee;
                            log.info("turned: у камеры {} оставлено колено, без него точки остались бы без сети", junction.key);
                            break;
                        }
                    }
                    if (changed == null && TURN_DETACH) {
                        changed = detached(tree, kneed, dnByEdge, region, area);
                        if (changed != null) {
                            log.warn("turned: поворот в камере {} не снят, без сети остаются {}", sharpTurns(tree, kneed).get(0).key,
                                    changed.unconnected.subList(tree.unconnected.size(), changed.unconnected.size()).stream().map(ConnectionPoint::getId).collect(Collectors.toList()));
                        }
                    }
                    if (changed == null) {
                        sharpTurns(tree, kneed).forEach(junction -> log.info("turned: поворот в камере {} не снят", junction.key));
                        break;
                    }
                    forced++;
                }
                result.set(t, changed);
                taken++;
                sharp = sharpTurns(changed, kneed).size();
            }
        }
        log.info("turned: moves={} forced={} elapsed={}ms", taken, forced, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private Tree unsharpened(Tree tree, List<Tree> unit, List<Tree> others, Map<Tree.Edge, Integer> dnByEdge,
            Map<Tree.Edge, Double> priceRub, Region region, Envelope area, Set<String> kneed, boolean knees) {
        java.util.function.Function<Tree.Edge, ObstacleSet> zones = edge -> region.obstacles(dnByEdge.get(edge), area);
        int sharp = sharpPairs(tree, kneed);
        List<TreeBuilder.Slide> slides = new ArrayList<>();
        for (Tree.Node junction : sharpTurns(tree, kneed)) {
            slides.addAll(slides(List.of("turnSlides", junction.key, Double.POSITIVE_INFINITY), tree, unit,
                    () -> builder.turnSlides(tree, junction, zones, dnByEdge, priceRub, Double.POSITIVE_INFINITY, TURN_STEP_M,
                            TURN_MAX_M)));
            if (knees) {
                slides.addAll(slides(List.of("bends", junction.key), tree, unit,
                        () -> builder.bends(tree, junction, zones, priceRub, Double.POSITIVE_INFINITY)));
                slides.addAll(slides(List.of("fans", junction.key), tree, unit, () -> builder.fans(tree, junction, zones, priceRub)));
            }
            if (TURN_GRID) {
                slides.addAll(slides(List.of("turnMoves", junction.key, Double.POSITIVE_INFINITY), tree, unit,
                        () -> builder.turnMoves(tree, junction, zones, dnByEdge, priceRub, Double.POSITIVE_INFINITY)));
            }
        }
        slides.sort(Comparator.comparingDouble(slide -> slide.gain));
        List<java.util.function.Supplier<Tree>> attempts = new ArrayList<>();
        for (Tree.Node junction : sharpTurns(tree, kneed)) {
            bypasses(tree, junction, dnByEdge, region, area, sharp, kneed).forEach(bypass -> attempts.add(() -> bypass));
        }
        slides.forEach(slide -> attempts.add(() -> unkinked(tree, slide, tree.tie, dnByEdge, region, area, true)));
        Tree best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int checked = 0;
        for (int k = 0; k < attempts.size() && checked < TURN_FIXES; k++) {
            Tree attempt = attempts.get(k).get();
            if (attempt == null || sharpPairs(attempt, kneed) >= sharp || !compatible(attempt, others)) {
                continue;
            }
            List<Tree> after = new ArrayList<>();
            for (Tree other : unit) {
                after.add(other == tree ? attempt : other);
            }
            double score = unitScore(after);
            if (Double.isNaN(score)) {
                continue;
            }
            checked++;
            if (score < bestScore) {
                bestScore = score;
                best = attempt;
            }
        }
        return best;
    }

    private Tree detached(Tree tree, Set<String> kneed, Map<Tree.Edge, Integer> dnByEdge, Region region, Envelope area) {
        Tree.Node junction = sharpTurns(tree, kneed).get(0);
        Tree.Edge up = tree.edges.stream().filter(edge -> edge.to == junction).findFirst().orElseThrow();
        Coordinate before = up.line.getCoordinateN(up.line.getNumPoints() - 2);
        Set<Tree.Node> gone = new HashSet<>();
        List<Tree.Edge> rest = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == junction) {
                if (Router.deflectionDeg(before, junction.point, edge.line.getCoordinateN(1)) > Router.MAX_TURN_DEG) {
                    gone.add(edge.to);
                } else {
                    rest.add(edge);
                }
            }
        }
        if (rest.isEmpty()) {
            return null;
        }
        for (Tree.Edge edge : tree.edges) {
            if (gone.contains(edge.from)) {
                gone.add(edge.to);
            }
        }
        Tree.Edge merged = rest.size() == 1 ? rest.get(0) : null;
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        Map<Integer, LineString> change = new LinkedHashMap<>();
        Map<Integer, Fresh> found = new HashMap<>();
        for (Tree.Edge edge : tree.edges) {
            if (gone.contains(edge.to)) {
                if (edge.to.kind == Tree.Kind.CONNECTION) {
                    result.unconnected.add(edge.to.connection);
                }
                continue;
            }
            if (edge == merged) {
                continue;
            }
            Tree.Node from = edge.from == tree.root ? result.root : edge.from;
            if (edge != up || merged == null) {
                result.edges.add(from == edge.from ? edge : new Tree.Edge(from, edge.to, edge.line));
                continue;
            }
            Coordinate[] a = up.line.getCoordinates();
            Coordinate[] b = merged.line.getCoordinates();
            Coordinate[] line = Arrays.copyOf(a, a.length + b.length - 1);
            System.arraycopy(b, 1, line, a.length, b.length - 1);
            int dn = Math.max(dnByEdge.get(up), dnByEdge.get(merged));
            Router router = shaper(region, area, dn);
            if (router == null) {
                return null;
            }
            Tree.Edge fresh = new Tree.Edge(from, merged.to, factory.createLineString(line));
            change.put(result.edges.size(), fresh.line);
            found.put(result.edges.size(), new Fresh(fresh.line, router, region.obstacles(dn, area)));
            result.edges.add(fresh);
        }
        if (change.isEmpty()) {
            return result;
        }
        Map<Integer, LineString> sharp = sharpened(result, change, found, true);
        return sharp == null ? null : replaced(result, sharp);
    }

    private List<Tree> bypasses(Tree tree, Tree.Node junction, Map<Tree.Edge, Integer> dnByEdge, Region region,
            Envelope area, int sharp, Set<String> kneed) {
        Tree.Edge up = tree.edges.stream().filter(edge -> edge.to == junction).findFirst().orElse(null);
        if (up == null) {
            return List.of();
        }
        Tree.Node parent = up.from;
        Coordinate[] upCoords = up.line.getCoordinates();
        Coordinate before = null;
        for (Tree.Edge edge : tree.edges) {
            if (edge.to == parent) {
                before = edge.line.getCoordinateN(edge.line.getNumPoints() - 2);
            }
        }
        List<Tree.Edge> children = tree.edges.stream().filter(edge -> edge.from == junction).collect(Collectors.toList());
        if (parent != tree.root && tree.degree(parent) >= rules.chamberRule().getMaxSegments()) {
            return List.of();
        }
        Set<String> ignored = tree.tie.getIgnored();
        List<Tree> result = new ArrayList<>();
        for (Tree.Edge child : children) {
            if (Router.deflectionDeg(upCoords[upCoords.length - 2], junction.point, child.line.getCoordinateN(1))
                    <= Router.MAX_TURN_DEG) {
                continue;
            }
            int dn = dnByEdge.get(child);
            Router router = shaper(region, area, dn);
            if (router == null) {
                continue;
            }
            ObstacleSet zones = region.obstacles(dn, area);
            Coordinate[] c = child.line.getCoordinates();
            boolean inBuilding = child.to.kind == Tree.Kind.CONNECTION
                    && buildingByConnection.containsKey(child.to.connection.getId());
            List<Double> positions = new ArrayList<>(TreeBuilder.vertexPositions(child.line));
            for (SpecialSpan span : zones.spans(child.line, ignored)) {
                positions.add(span.getToM());
            }
            positions.add(child.line.getLength());
            LengthIndexedLine indexed = new LengthIndexedLine(child.line);
            List<Coordinate[]> lines = new ArrayList<>();
            double length = child.line.getLength();
            for (double position : positions) {
                boolean end = position > length - 1e-6;
                if (position < 1e-6 || end && inBuilding) {
                    continue;
                }
                Coordinate[] rest = end ? new Coordinate[] {c[c.length - 1]}
                        : indexed.extractLine(position, length).getCoordinates();
                Coordinate[] line = new Coordinate[rest.length + 1];
                line[0] = parent.point;
                System.arraycopy(rest, 0, line, 1, rest.length);
                if (straightFrom(tree, line, parent, before, zones, ignored, child)) {
                    lines.add(line);
                }
            }
            lines.sort(Comparator.comparingDouble(VariantEnumerator::length));
            for (Coordinate[] line : lines) {
                Tree changed = bypassed(tree, junction, up, child, line, dnByEdge, region, area);
                if (changed != null && sharpPairs(changed, kneed) < sharp) {
                    result.add(changed);
                    break;
                }
            }
        }
        return result;
    }

    private boolean straightFrom(Tree tree, Coordinate[] line, Tree.Node parent, Coordinate before, ObstacleSet zones,
            Set<String> ignored, Tree.Edge child) {
        Coordinate a = line[0];
        Coordinate b = line[1];
        if (a.distance(b) < Router.CUT_PIECE_M
                || line.length > 2 && (Router.deflectionDeg(a, b, line[2]) > Router.MAX_TURN_DEG
                        || Router.deflectionDeg(a, b, line[2]) < TreeBuilder.MIN_TURN_DEG)
                || before != null && Router.deflectionDeg(before, a, b) > Router.MAX_TURN_DEG
                || !zones.covers(a, b) || !zones.plain(a, b, ignored, Router.CUT_MARGIN_M)) {
            return false;
        }
        if (parent == tree.root && (zones.alongIgnored(a, b, ignored)
                || !builder.leavesNetwork(new LineSegment(b, a), ignored))) {
            return false;
        }
        if (line.length == 2 && child.to.kind == Tree.Kind.JUNCTION) {
            for (Tree.Edge edge : tree.edges) {
                if (edge.from == child.to && Router.deflectionDeg(a, b, edge.line.getCoordinateN(1)) > Router.MAX_TURN_DEG) {
                    return false;
                }
            }
        }
        List<LineSegment> others = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] c = edge.line.getCoordinates();
            for (int i = 0; edge != child && i + 1 < c.length; i++) {
                others.add(new LineSegment(c[i], c[i + 1]));
            }
        }
        for (int i = 2; i + 1 < line.length; i++) {
            others.add(new LineSegment(line[i], line[i + 1]));
        }
        return Router.apart(new LineSegment(a, b), others);
    }

    private Tree bypassed(Tree tree, Tree.Node junction, Tree.Edge up, Tree.Edge child, Coordinate[] line,
            Map<Tree.Edge, Integer> dnByEdge, Region region, Envelope area) {
        List<Tree.Edge> rest = tree.edges.stream().filter(edge -> edge.from == junction && edge != child)
                .collect(Collectors.toList());
        Tree.Edge merged = null;
        Coordinate[] mergedLine = null;
        if (rest.size() == 1) {
            Coordinate[] a = up.line.getCoordinates();
            Coordinate[] b = rest.get(0).line.getCoordinates();
            if (Router.deflectionDeg(a[a.length - 2], junction.point, b[1]) > Router.MAX_TURN_DEG) {
                return null;
            }
            mergedLine = Arrays.copyOf(a, a.length + b.length - 1);
            System.arraycopy(b, 1, mergedLine, a.length, b.length - 1);
            merged = rest.get(0);
        }
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        Map<Integer, LineString> change = new LinkedHashMap<>();
        Map<Integer, Fresh> found = new HashMap<>();
        for (Tree.Edge edge : tree.edges) {
            if (edge == merged) {
                continue;
            }
            Tree.Node from = edge.from == tree.root ? result.root : edge.from;
            Tree.Edge fresh;
            int dn = dnByEdge.get(edge);
            if (edge == child) {
                Tree.Node parent = up.from == tree.root ? result.root : up.from;
                fresh = new Tree.Edge(parent, edge.to, factory.createLineString(line));
            } else if (edge == up && merged != null) {
                fresh = new Tree.Edge(from, merged.to, factory.createLineString(mergedLine));
                dn = Math.max(dn, dnByEdge.get(merged));
            } else {
                result.edges.add(from == edge.from ? edge : new Tree.Edge(from, edge.to, edge.line));
                continue;
            }
            Router router = shaper(region, area, dn);
            if (router == null) {
                return null;
            }
            change.put(result.edges.size(), fresh.line);
            found.put(result.edges.size(), new Fresh(fresh.line, router, region.obstacles(dn, area)));
            result.edges.add(fresh);
        }
        Map<Integer, LineString> sharp = sharpened(result, change, found, true);
        return sharp == null ? null : replaced(result, sharp);
    }

    private static int sharpPairs(Tree tree, Set<String> kneed) {
        int count = 0;
        for (Tree.Edge in : tree.edges) {
            if (in.to.kind != Tree.Kind.JUNCTION) {
                continue;
            }
            for (Tree.Edge out : tree.edges) {
                if (out.from == in.to && sharp(in, out, kneed)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int knees(List<Tree> trees) {
        int count = 0;
        for (Tree tree : trees) {
            count += sharpPairs(tree, Set.of()) - sharpPairs(tree, null);
        }
        return count;
    }

    private static boolean sharp(Tree.Edge in, Tree.Edge out, Set<String> kneed) {
        Coordinate[] c = in.line.getCoordinates();
        double turn = kneed == null || kneed.contains(in.to.key)
                ? Router.deflectionDeg(c[c.length - 2], in.to.point, out.line.getCoordinateN(1))
                : TreeBuilder.pathTurnDeg(c, out.line.getCoordinates());
        return turn > Router.MAX_TURN_DEG;
    }

    private List<Tree> bent(List<Tree> trees) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        for (int t = 0; t < result.size(); t++) {
            Tree tree = result.get(t);
            Region region = regionByConnection.get(tree.connected().get(0).getId());
            Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
            List<Integer> paired = new ArrayList<>();
            for (int e = 0; e < tree.edges.size(); e++) {
                if (Router.paired(tree.edges.get(e).line.getCoordinates())) {
                    paired.add(e);
                }
            }
            if (paired.isEmpty() || !area.contains(tree.envelope())) {
                continue;
            }
            List<List<Integer>> changes = new ArrayList<>(List.of(paired));
            for (int e : paired.size() > 1 ? paired : List.<Integer>of()) {
                changes.add(List.of(e));
            }
            for (List<Integer> change : changes) {
                Tree changed = bent(result.get(t), change, region, area, result);
                if (changed != null) {
                    result.set(t, changed);
                    taken += change.size();
                    if (change == paired) {
                        break;
                    }
                }
            }
        }
        log.info("bent: edges={} elapsed={}ms", taken, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private Tree bent(Tree tree, List<Integer> change, Region region, Envelope area, List<Tree> result) {
        List<Tree> unit = new ArrayList<>();
        List<Tree> others = new ArrayList<>();
        for (Tree other : result) {
            if (other != tree) {
                others.add(other);
            }
            if (other.root.key.equals(tree.root.key)) {
                unit.add(other);
            }
        }
        Map<Tree.Edge, Integer> dnByEdge;
        try {
            dnByEdge = diameters(unit);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return null;
        }
        Map<Integer, LineString> lines = new LinkedHashMap<>();
        Map<Integer, Fresh> found = new HashMap<>();
        for (int e : change) {
            int dn = dnByEdge.get(tree.edges.get(e));
            Envelope zone = shaper(region, area, dn) == null ? region.wideArea : area;
            Router router = shaper(region, zone, dn);
            if (router == null) {
                return null;
            }
            LineString line = tree.edges.get(e).line;
            lines.put(e, line);
            found.put(e, new Fresh(line, router, region.obstacles(dn, zone)));
        }
        Map<Integer, LineString> sharp = sharpened(tree, lines, found, true, true);
        if (sharp == null || sharp.entrySet().stream().allMatch(entry -> entry.getValue() == lines.get(entry.getKey()))) {
            return null;
        }
        Tree changed = replaced(tree, sharp);
        List<Tree> attempt = new ArrayList<>();
        for (Tree other : unit) {
            attempt.add(other == tree ? changed : other);
        }
        double addedRub = 0;
        double addedM = 0;
        for (Map.Entry<Integer, LineString> entry : sharp.entrySet()) {
            double added = Math.max(0, entry.getValue().getLength() - tree.edges.get(entry.getKey()).line.getLength());
            addedRub += added * rules.diameter(dnByEdge.get(tree.edges.get(entry.getKey()))).getNewRubM();
            addedM += added;
        }
        return compatible(changed, others)
                && unitScore(attempt) <= unitScore(unit) + rules.score(addedRub, addedM) + UNKINK_EPS
                && !worse(tree, unit, changed, attempt) ? changed : null;
    }

    private List<Tree> entered(List<Tree> trees) {
        return entered(trees, false);
    }

    private List<Tree> entered(List<Tree> trees, boolean corner) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        int tried = 0;
        Map<String, Map<Tree.Edge, Integer>> dnByRoot = new HashMap<>();
        for (int t = 0; t < result.size(); t++) {
            for (int e = 0; e < result.get(t).edges.size(); e++) {
                Tree tree = result.get(t);
                Tree.Edge edge = tree.edges.get(e);
                ExistingOks building = edge.to.kind == Tree.Kind.CONNECTION
                        ? buildingByConnection.get(edge.to.connection.getId()) : null;
                if (building == null || (corner ? edge.line.getNumPoints() < 4 : edge.line.getNumPoints() < 3
                        && Double.isNaN(TreeBuilder.farEntry(building.getGeometry(), edge.line.getCoordinates())))) {
                    continue;
                }
                tried++;
                List<Tree> unit = new ArrayList<>();
                for (Tree other : result) {
                    if (other.root.key.equals(tree.root.key)) {
                        unit.add(other);
                    }
                }
                Map<Tree.Edge, Integer> dnByEdge = dnByRoot.get(tree.root.key);
                try {
                    dnByEdge = dnByEdge != null ? dnByEdge : diameters(unit);
                } catch (IllegalStateException | IllegalArgumentException ex) {
                    break;
                }
                dnByRoot.put(tree.root.key, dnByEdge);
                Region region = regionByConnection.get(edge.to.connection.getId());
                Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
                int dn = dnByEdge.get(edge);
                ObstacleSet zones = region.obstacles(dn, area);
                Coordinate before = null;
                List<LineSegment> apart = new ArrayList<>();
                for (Tree.Edge other : tree.edges) {
                    Coordinate[] c = other.line.getCoordinates();
                    for (int i = 0; other != edge && i + 1 < c.length; i++) {
                        apart.add(new LineSegment(c[i], c[i + 1]));
                    }
                    if (edge.from.kind == Tree.Kind.JUNCTION && other.to == edge.from) {
                        before = c[c.length - 2];
                    }
                }
                Coordinate[] line;
                if (corner) {
                    line = builder.cornered(building, edge.line.getCoordinates(), zones, tree.tie.getIgnored(), before,
                            apart, edge.from == tree.root);
                } else {
                    line = builder.entered(building, edge.line.getCoordinates(), zones, tree.tie.getIgnored(),
                            before, apart, edge.from == tree.root);
                    Coordinate[] straight = builder.straightened(building, line != null ? line : edge.line.getCoordinates(),
                            zones, tree.tie.getIgnored(), before, apart, edge.from == tree.root);
                    line = straight != null ? straight : line;
                }
                if (line == null) {
                    continue;
                }
                LineString fresh = factory.createLineString(line);
                Router router = null;
                for (Diameter graph = rules.diameter(dn); router == null && graph != null; graph = rules.nextDiameter(graph.getDn())) {
                    router = region.built(graph.getDn() + "@" + area);
                }
                Map<Integer, LineString> sharp = router == null ? Map.of(e, fresh)
                        : sharpened(tree, Map.of(e, fresh), Map.of(e, new Fresh(fresh, router, zones)), true);
                Tree changed = sharp == null ? null : replaced(tree, sharp);
                List<Tree> others = new ArrayList<>(result);
                others.remove(t);
                List<Tree> attempt = new ArrayList<>();
                for (Tree other : unit) {
                    attempt.add(other == tree ? changed : other);
                }
                if (changed == null || !apart(sharp.values(), result, tree) || !compatible(changed, others)
                        || Double.isNaN(unitScore(attempt)) || worse(tree, unit, changed, attempt)
                        || corner && (changed.edges.get(e).line.getNumPoints() >= edge.line.getNumPoints()
                        || unitScore(attempt) > unitScore(unit) + UNKINK_EPS)) {
                    log.info("entered: вход точки {} не перенесён{}", edge.to.connection.getId(), corner ? " к углу" : "");
                    continue;
                }
                result.set(t, changed);
                dnByRoot.remove(tree.root.key);
                taken++;
            }
        }
        log.info("entered{}: tried={} moves={} elapsed={}ms", corner ? " corner" : "", tried, taken,
                (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private List<Tree> evened(List<Tree> trees) {
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        for (int t = 0; t < result.size(); t++) {
            for (int e = 0; e < result.get(t).edges.size(); e++) {
                Tree tree = result.get(t);
                Tree.Edge edge = tree.edges.get(e);
                Region region = regionByConnection.get(tree.connected().get(0).getId());
                Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
                Coordinate[] coords = edge.line.getCoordinates();
                boolean kinked = false;
                for (int v = 1; v + 1 < coords.length; v++) {
                    kinked |= Router.micro(coords[v - 1], coords[v], coords[v + 1]);
                }
                if (!kinked || !area.contains(tree.envelope())) {
                    continue;
                }
                List<Tree> unit = new ArrayList<>();
                for (Tree other : result) {
                    if (other.root.key.equals(tree.root.key)) {
                        unit.add(other);
                    }
                }
                Map<Tree.Edge, Integer> dnByEdge;
                try {
                    dnByEdge = diameters(unit);
                } catch (IllegalStateException | IllegalArgumentException ex) {
                    break;
                }
                int dn = dnByEdge.get(edge);
                Router router = shaper(region, area, dn);
                if (router == null) {
                    continue;
                }
                ObstacleSet zones = region.obstacles(dn, area);
                Set<String> ignored = tree.tie.getIgnored();
                Coordinate before = null;
                List<Coordinate> after = new ArrayList<>();
                List<LineSegment> apart = new ArrayList<>();
                for (Tree.Edge other : tree.edges) {
                    Coordinate[] c = other.line.getCoordinates();
                    for (int i = 0; other != edge && i + 1 < c.length; i++) {
                        apart.add(new LineSegment(c[i], c[i + 1]));
                    }
                    if (edge.from.kind == Tree.Kind.JUNCTION && other.to == edge.from) {
                        before = c[c.length - 2];
                    }
                    if (edge.to.kind == Tree.Kind.JUNCTION && other.from == edge.to) {
                        after.add(c[1]);
                    }
                }
                ExistingOks building = edge.to.kind == Tree.Kind.CONNECTION
                        ? buildingByConnection.get(edge.to.connection.getId()) : null;
                Coordinate exit = building != null ? coords[coords.length - 2] : null;
                List<Coordinate> line = new ArrayList<>(Arrays.asList(coords));
                router.even(zones, line, ignored, exit, apart, before, after);
                Coordinate[] fresh = line.toArray(new Coordinate[0]);
                Coordinate[] straight = building == null ? null
                        : builder.evened(building, fresh, zones, ignored, before, apart, edge.from == tree.root);
                fresh = straight != null ? straight : fresh;
                if (fresh.length == coords.length) {
                    continue;
                }
                LineString changedLine = factory.createLineString(fresh);
                double rub = rules.diameter(dn).getNewRubM()
                        * (ObstacleSet.weight(changedLine.getLength(), zones.spans(changedLine, ignored))
                        - ObstacleSet.weight(edge.line.getLength(), zones.spans(edge.line, ignored)))
                        + rules.lengthWorthRub() * (changedLine.getLength() - edge.line.getLength());
                Tree changed = replaced(tree, Map.of(e, changedLine));
                List<Tree> others = new ArrayList<>(result);
                others.remove(t);
                List<Tree> attempt = new ArrayList<>();
                for (Tree other : unit) {
                    attempt.add(other == tree ? changed : other);
                }
                if (rub > EVEN_EPS_RUB || !compatible(changed, others) || Double.isNaN(unitScore(attempt))
                        || worse(tree, unit, changed, attempt)) {
                    log.info("evened: излом у технического узла на ребре к {} не снят, {} руб.", edge.to.key, Math.round(rub));
                    continue;
                }
                result.set(t, changed);
                taken++;
            }
        }
        log.info("evened: edges={} elapsed={}ms", taken, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private List<Tree> squared(List<Tree> trees) {
        if (!SQUARE) {
            return trees;
        }
        long started = System.nanoTime();
        List<Tree> result = new ArrayList<>(trees);
        int taken = 0;
        for (int pass = 0, before = -1; pass < SQUARE_PASSES && taken > before; pass++) {
            before = taken;
            taken = squarePass(result, taken);
        }
        int moved = junctionPass(result);
        for (int pass = 0, before = -1; moved > 0 && pass < SQUARE_PASSES && taken > before; pass++) {
            before = taken;
            taken = squarePass(result, taken);
        }
        log.info("squared: edges={} junctions moved={} elapsed={}ms", taken, moved, (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private int squarePass(List<Tree> result, int taken) {
        for (int t = 0; t < result.size(); t++) {
            for (int e = result.get(t).edges.size() - 1; e >= 0; e--) {
                Tree tree = result.get(t);
                List<Tree> unit = unit(result, tree);
                Tree changed;
                try {
                    changed = squaredEdge(tree, e, diameters(unit), axis(tree.edges.get(e).line.getEnvelopeInternal()));
                } catch (IllegalStateException | IllegalArgumentException ex) {
                    break;
                }
                if (changed == null) {
                    continue;
                }
                if (!accepted(result, t, unit, changed)) {
                    log.info("squared: ребро к {} по осям не переложено", tree.edges.get(e).to.key);
                    continue;
                }
                changed.unsquared = tree.unsquared != null ? tree.unsquared : tree;
                result.set(t, changed);
                taken++;
            }
        }
        return taken;
    }

    private int junctionPass(List<Tree> result) {
        int moved = 0;
        for (int t = 0; t < result.size(); t++) {
            for (int e = 0; e < result.get(t).edges.size(); e++) {
                Tree tree = result.get(t);
                if (tree.edges.get(e).to.kind != Tree.Kind.JUNCTION) {
                    continue;
                }
                Tree better = squaredJunction(result, t, tree.edges.get(e).to);
                if (better != null) {
                    better.unsquared = tree.unsquared != null ? tree.unsquared : tree;
                    result.set(t, better);
                    moved++;
                }
            }
        }
        return moved;
    }

    private static int bends(Tree tree, List<Integer> incident) {
        int count = 0;
        for (int e : incident) {
            Coordinate[] c = tree.edges.get(e).line.getCoordinates();
            for (int v = 1; v + 1 < c.length; v++) {
                if (Router.deflectionDeg(c[v - 1], c[v], c[v + 1]) >= TreeBuilder.MIN_TURN_DEG) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int pairs(Tree tree, List<Integer> incident) {
        return incident.stream().mapToInt(e -> Router.pairs(tree.edges.get(e).line.getCoordinates())).sum();
    }

    private Tree squaredJunction(List<Tree> result, int t, Tree.Node junction) {
        Tree tree = result.get(t);
        List<Integer> incident = new ArrayList<>();
        Envelope envelope = new Envelope();
        for (int e = 0; e < tree.edges.size(); e++) {
            Tree.Edge edge = tree.edges.get(e);
            if (edge.from == junction || edge.to == junction) {
                incident.add(e);
                envelope.expandToInclude(edge.line.getEnvelopeInternal());
            }
        }
        double axis = axis(envelope);
        if (Double.isNaN(axis)) {
            return null;
        }
        double off = offAxis(tree, incident, axis);
        if (off < 1) {
            return null;
        }
        double length = 0;
        double c = Math.cos(axis);
        double s = Math.sin(axis);
        List<Coordinate> marks = new ArrayList<>();
        marks.add(junction.point);
        for (int e : incident) {
            Coordinate[] line = tree.edges.get(e).line.getCoordinates();
            length += tree.edges.get(e).line.getLength();
            boolean out = tree.edges.get(e).from == junction;
            marks.add(out ? line[1] : line[line.length - 2]);
            marks.add(out ? line[line.length - 1] : line[0]);
        }
        List<Coordinate> spots = new ArrayList<>();
        for (Coordinate a : marks) {
            for (Coordinate b : marks) {
                double u = a.x * c + a.y * s;
                double v = -b.x * s + b.y * c;
                Coordinate spot = new Coordinate(u * c - v * s, u * s + v * c);
                double d = spot.distance(junction.point);
                if (d > Router.CUT_PIECE_M && d <= JUNCTION_MOVE_M && spots.stream().noneMatch(x -> x.distance(spot) < 0.5)) {
                    spots.add(spot);
                }
            }
        }
        for (int e : incident) {
            Coordinate[] line = tree.edges.get(e).line.getCoordinates();
            boolean out = tree.edges.get(e).from == junction;
            Coordinate next = out ? line[1] : line[line.length - 2];
            double reach = Math.min(JUNCTION_MOVE_M, junction.point.distance(next) - Router.CUT_PIECE_M);
            double heading = Math.atan2(next.y - junction.point.y, next.x - junction.point.x);
            if (Math.abs(Math.IEEEremainder(heading - axis, Math.PI / 2)) > OFF_AXIS_RAD) {
                continue;
            }
            for (double d = JUNCTION_STEP_M; d <= reach; d += JUNCTION_STEP_M) {
                Coordinate spot = new Coordinate(junction.point.x + d * Math.cos(heading), junction.point.y + d * Math.sin(heading));
                if (spots.stream().noneMatch(x -> x.distance(spot) < 0.5)) {
                    spots.add(spot);
                }
            }
        }
        spots.sort(Comparator.comparingDouble(spot -> spot.distance(junction.point)));
        List<Tree> unit = unit(result, tree);
        Tree best = null;
        double bestOff = Math.min(off - 1, JUNCTION_GAIN * off);
        double bestLength = SQUARE_RATIO * length;
        List<Coordinate> tried = spots.subList(0, Math.min(JUNCTION_SPOTS, spots.size()));
        Tree[] works = new Tree[tried.size()];
        IntStream range = IntStream.range(0, tried.size());
        (PARALLEL ? range.parallel() : range)
                .forEach(i -> works[i] = squaredAt(tree, junction, tried.get(i), incident, unit, axis));
        for (int i = 0; i < tried.size(); i++) {
            Tree work = works[i];
            if (work == null) {
                continue;
            }
            double workOff = offAxis(work, incident, axis);
            double workLength = 0;
            for (int e : incident) {
                workLength += work.edges.get(e).line.getLength();
            }
            boolean better = workOff < bestOff - 1e-9 || Math.abs(workOff - bestOff) <= 1e-9 && workLength < bestLength;
            if (better && workLength <= SQUARE_RATIO * length && sharpPairs(work, Set.of()) <= sharpPairs(tree, Set.of())
                    && bends(work, incident) <= bends(tree, incident)
                    && pairs(work, incident) <= pairs(tree, incident)
                    && accepted(result, t, unit, work)) {
                best = work;
                bestOff = workOff;
                bestLength = workLength;
            }
        }
        return best;
    }

    private Tree squaredAt(Tree tree, Tree.Node junction, Coordinate spot, List<Integer> incident, List<Tree> unit, double axis) {
        Tree work = moved(tree, junction, spot);
        Tree.Node node = work.edges.get(incident.get(0)).from.key.equals(Tree.Node.junction(spot).key)
                ? work.edges.get(incident.get(0)).from : work.edges.get(incident.get(0)).to;
        try {
            for (int k = incident.size() - 1; k >= 0; k--) {
                int e = incident.get(k);
                Tree squared = squaredEdge(work, e, diameters(swapped(unit, tree, work)), axis);
                work = squared != null ? squared : work;
            }
            work = straightenedExits(work, incident, diameters(swapped(unit, tree, work)));
            work = droppedAtJunction(work, node.key, incident, diameters(swapped(unit, tree, work)), axis);
            if (diagonals(work, node.key, incident, axis) > diagonals(tree, junction.key, incident, axis)
                    || keptExitDiagonal(tree, work, junction, node.key, incident, axis)
                    || !junctionFits(work, node.key, incident, diameters(swapped(unit, tree, work)))) {
                return null;
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return null;
        }
        return work;
    }

    private Tree straightenedExits(Tree tree, List<Integer> incident, Map<Tree.Edge, Integer> dnByEdge) {
        Map<Integer, LineString> lines = new HashMap<>();
        for (int e : incident) {
            Tree.Edge edge = tree.edges.get(e);
            ExistingOks building = edge.to.kind == Tree.Kind.CONNECTION ? buildingByConnection.get(edge.to.connection.getId()) : null;
            EdgeShape shape = building == null ? null : shape(tree, e, dnByEdge);
            Coordinate[] line = shape == null ? null : builder.straightened(building, edge.line.getCoordinates(), shape.zones,
                    tree.tie.getIgnored(), shape.before, shape.apart, edge.from == tree.root);
            if (line != null) {
                lines.put(e, factory.createLineString(line));
            }
        }
        return lines.isEmpty() ? tree : replaced(tree, lines);
    }

    private Tree droppedAtJunction(Tree tree, String key, List<Integer> incident, Map<Tree.Edge, Integer> dnByEdge,
            double axis) {
        Map<Integer, LineString> lines = new HashMap<>();
        for (int e : incident) {
            Tree.Edge edge = tree.edges.get(e);
            List<Coordinate> line = new ArrayList<>(Arrays.asList(edge.line.getCoordinates()));
            int v = edge.from.key.equals(key) ? 1 : line.size() - 2;
            EdgeShape shape = line.size() > 2 ? shape(tree, e, dnByEdge) : null;
            Coordinate at = edge.from.key.equals(key) ? line.get(0) : line.get(line.size() - 1);
            if (shape == null || line.get(v) == shape.exit
                    || Math.abs(Math.IEEEremainder(Math.atan2(line.get(v).y - at.y, line.get(v).x - at.x) - axis, Math.PI / 2)) <= OFF_AXIS_RAD) {
                continue;
            }
            if (shape.router.removable(shape.zones, line, v, tree.tie.getIgnored(), shape.apart, shape.before, shape.after)) {
                line.remove(v);
                lines.put(e, factory.createLineString(line.toArray(new Coordinate[0])));
            }
        }
        return lines.isEmpty() ? tree : replaced(tree, lines);
    }

    private static boolean along(Coordinate a, Coordinate b, Coordinate spot) {
        return new LineSegment(a, b).distancePerpendicular(spot) < 0.05;
    }

    private static boolean keptExit(Tree.Edge edge, List<Coordinate> line, int v) {
        return edge.to.kind == Tree.Kind.CONNECTION && v == line.size() - 2;
    }

    private static List<Tree> swapped(List<Tree> unit, Tree before, Tree after) {
        List<Tree> result = new ArrayList<>();
        for (Tree tree : unit) {
            result.add(tree == before ? after : tree);
        }
        return result;
    }

    private static Tree moved(Tree tree, Tree.Node junction, Coordinate spot) {
        Tree.Node node = Tree.Node.junction(spot);
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        for (Tree.Edge edge : tree.edges) {
            Tree.Node from = edge.from == tree.root ? result.root : edge.from == junction ? node : edge.from;
            Tree.Node to = edge.to == junction ? node : edge.to;
            if (from == edge.from && to == edge.to) {
                result.edges.add(edge);
                continue;
            }
            List<Coordinate> line = new ArrayList<>(Arrays.asList(edge.line.getCoordinates()));
            if (from == node && edge.to.kind == Tree.Kind.CONNECTION && line.size() == 2) {
                line.add(0, spot);
            } else if (from == node) {
                line.set(0, spot);
                if (line.size() > 2 && !keptExit(edge, line, 1) && (line.get(1).distance(spot) < Router.CUT_PIECE_M
                        || !along(junction.point, line.get(1), spot) && line.get(1).distance(spot) < JUNCTION_NEAR_M
                        || Router.deflectionDeg(spot, line.get(1), line.get(2)) < JUNCTION_BEND_DEG)) {
                    line.remove(1);
                }
            }
            if (to == node) {
                line.set(line.size() - 1, spot);
                int v = line.size() - 2;
                if (line.size() > 2 && (line.get(v).distance(spot) < Router.CUT_PIECE_M
                        || !along(junction.point, line.get(v), spot) && line.get(v).distance(spot) < JUNCTION_NEAR_M
                        || Router.deflectionDeg(line.get(v - 1), line.get(v), spot) < JUNCTION_BEND_DEG)) {
                    line.remove(v);
                }
            }
            result.edges.add(new Tree.Edge(from, to, new GeometryFactory().createLineString(line.toArray(new Coordinate[0]))));
        }
        return result;
    }

    private boolean junctionFits(Tree tree, String key, List<Integer> incident, Map<Tree.Edge, Integer> dnByEdge) {
        Coordinate before = null;
        List<Coordinate> after = new ArrayList<>();
        Coordinate at = null;
        for (int e : incident) {
            Tree.Edge edge = tree.edges.get(e);
            EdgeShape shape = shape(tree, e, dnByEdge);
            if (shape == null) {
                return false;
            }
            List<Coordinate> line = Arrays.asList(edge.line.getCoordinates());
            boolean out = edge.from.key.equals(key);
            boolean last = out && line.size() == 2 && shape.exit != null;
            if (!last && !shape.router.pieceFitsAt(shape.zones, line, out ? 0 : line.size() - 2, tree.tie.getIgnored(), shape.apart)) {
                return false;
            }
            int v = out ? 1 : line.size() - 2;
            if (line.size() > 2 && Router.deflectionDeg(line.get(v - 1), line.get(v), line.get(v + 1)) < JUNCTION_BEND_DEG) {
                return false;
            }
            at = out ? line.get(0) : line.get(line.size() - 1);
            if (out) {
                after.add(line.get(1));
            } else {
                before = line.get(line.size() - 2);
            }
        }
        for (Coordinate next : after) {
            if (before != null && Router.deflectionDeg(before, at, next) > Router.MAX_TURN_DEG) {
                return false;
            }
        }
        return true;
    }

    private boolean keptExitDiagonal(Tree before, Tree after, Tree.Node junction, String key, List<Integer> incident,
            double axis) {
        for (int e : incident) {
            Tree.Edge was = before.edges.get(e);
            Coordinate[] c = after.edges.get(e).line.getCoordinates();
            if (was.from == junction && was.to.kind == Tree.Kind.CONNECTION && was.line.getNumPoints() == 2
                    && after.edges.get(e).from.key.equals(key) && c.length > 2
                    && Math.abs(Math.IEEEremainder(Math.atan2(c[1].y - c[0].y, c[1].x - c[0].x) - axis, Math.PI / 2)) > OFF_AXIS_RAD) {
                return true;
            }
        }
        return false;
    }

    private int diagonals(Tree tree, String key, List<Integer> incident, double axis) {
        int count = 0;
        for (int e : incident) {
            Tree.Edge edge = tree.edges.get(e);
            Coordinate[] c = edge.line.getCoordinates();
            boolean out = edge.from.key.equals(key);
            boolean last = out && c.length == 2 && edge.to.kind == Tree.Kind.CONNECTION
                    && buildingByConnection.containsKey(edge.to.connection.getId());
            Coordinate a = out ? c[0] : c[c.length - 2];
            Coordinate b = out ? c[1] : c[c.length - 1];
            if (!last && Math.abs(Math.IEEEremainder(Math.atan2(b.y - a.y, b.x - a.x) - axis, Math.PI / 2)) > OFF_AXIS_RAD) {
                count++;
            }
        }
        return count;
    }

    private double offAxis(Tree tree, List<Integer> incident, double axis) {
        double off = 0;
        for (int e : incident) {
            Tree.Edge edge = tree.edges.get(e);
            Coordinate[] c = edge.line.getCoordinates();
            int last = edge.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(edge.to.connection.getId())
                    ? c.length - 2 : c.length - 1;
            for (int i = 0; i < last; i++) {
                double heading = Math.atan2(c[i + 1].y - c[i].y, c[i + 1].x - c[i].x);
                if (Math.abs(Math.IEEEremainder(heading - axis, Math.PI / 2)) > OFF_AXIS_RAD) {
                    off += c[i].distance(c[i + 1]);
                }
            }
        }
        return off;
    }

    private boolean keepsAxes(Tree before, Tree after) {
        for (int e = 0; e < before.edges.size(); e++) {
            if (!keepsAxes(before.edges.get(e).line.getCoordinates(), after.edges.get(e).line.getCoordinates())) {
                return false;
            }
        }
        return true;
    }

    private boolean keepsAxes(Coordinate[] was, Coordinate[] now) {
        if (Arrays.equals(was, now)) {
            return true;
        }
        double axis = axis(factory.createLineString(was).getEnvelopeInternal());
        for (int i = 0; i + 1 < was.length; i++) {
            if ((onAxis(was[i], was[i + 1], axis) || right(was, i) || right(was, i + 1)) && !kept(was[i], was[i + 1], now)) {
                return false;
            }
        }
        return true;
    }

    private static boolean right(Coordinate[] line, int v) {
        return v > 0 && v + 1 < line.length && Math.abs(Router.deflectionDeg(line[v - 1], line[v], line[v + 1]) - 90) <= RIGHT_TOL_DEG;
    }

    private static boolean kept(Coordinate a, Coordinate b, Coordinate[] line) {
        double heading = Math.atan2(b.y - a.y, b.x - a.x);
        for (int j = 0; j + 1 < line.length; j++) {
            if (along(a, b, line[j]) && along(a, b, line[j + 1]) && onAxis(line[j], line[j + 1], heading)) {
                return true;
            }
        }
        return false;
    }

    private static boolean onAxis(Coordinate a, Coordinate b, double axis) {
        return Math.abs(Math.IEEEremainder(Math.atan2(b.y - a.y, b.x - a.x) - axis, Math.PI / 2)) <= Router.SQUARE_TOL_RAD;
    }

    private static List<Tree> unit(List<Tree> result, Tree tree) {
        List<Tree> unit = new ArrayList<>();
        for (Tree other : result) {
            if (other.root.key.equals(tree.root.key)) {
                unit.add(other);
            }
        }
        return unit;
    }

    private boolean accepted(List<Tree> result, int t, List<Tree> unit, Tree changed) {
        Tree tree = result.get(t);
        List<Tree> others = new ArrayList<>(result);
        others.remove(t);
        List<Tree> attempt = new ArrayList<>();
        for (Tree other : unit) {
            attempt.add(other == tree ? changed : other);
        }
        return compatible(changed, others) && !Double.isNaN(unitScore(attempt)) && !worse(tree, unit, changed, attempt);
    }

    private final class EdgeShape {
        Router router;
        ObstacleSet zones;
        Coordinate before;
        final List<Coordinate> after = new ArrayList<>();
        final List<LineSegment> apart = new ArrayList<>();
        Coordinate exit;
    }

    private EdgeShape shape(Tree tree, int e, Map<Tree.Edge, Integer> dnByEdge) {
        Tree.Edge edge = tree.edges.get(e);
        Region region = regionByConnection.get(tree.connected().get(0).getId());
        Envelope area = region.area.contains(tree.envelope()) ? region.area : region.wideArea;
        Integer dn = dnByEdge.get(edge);
        if (!area.contains(tree.envelope()) || dn == null) {
            return null;
        }
        EdgeShape shape = new EdgeShape();
        shape.router = shaper(region, area, dn);
        if (shape.router == null) {
            return null;
        }
        shape.zones = region.obstacles(dn, area);
        for (Tree.Edge other : tree.edges) {
            Coordinate[] c = other.line.getCoordinates();
            for (int i = 0; other != edge && i + 1 < c.length; i++) {
                shape.apart.add(new LineSegment(c[i], c[i + 1]));
            }
            if (edge.from.kind == Tree.Kind.JUNCTION && other.to == edge.from) {
                shape.before = c[c.length - 2];
            }
            if (edge.to.kind == Tree.Kind.JUNCTION && other.from == edge.to) {
                shape.after.add(c[1]);
            }
        }
        Coordinate[] coords = edge.line.getCoordinates();
        shape.exit = edge.to.kind == Tree.Kind.CONNECTION
                && buildingByConnection.containsKey(edge.to.connection.getId()) ? coords[coords.length - 2] : null;
        return shape;
    }

    private Tree squaredEdge(Tree tree, int e, Map<Tree.Edge, Integer> dnByEdge, double axis) {
        EdgeShape shape = Double.isNaN(axis) ? null : shape(tree, e, dnByEdge);
        if (shape == null) {
            return null;
        }
        List<Coordinate> line = new ArrayList<>(Arrays.asList(tree.edges.get(e).line.getCoordinates()));
        if (!shape.router.square(shape.zones, line, tree.tie.getIgnored(), shape.exit, shape.apart, shape.before,
                shape.after, axis, SQUARE_RATIO, SQUARE_TURN_M)) {
            return null;
        }
        return replaced(tree, Map.of(e, factory.createLineString(line.toArray(new Coordinate[0]))));
    }

    private double axis(Envelope envelope) {
        Envelope near = new Envelope(envelope);
        near.expandBy(AXIS_NEAR_M);
        List<Geometry> shapes = new ArrayList<>();
        obstacleIndex.existingOks(near).forEach(oks -> shapes.add(oks.getGeometry()));
        obstacleIndex.restrictions(near).forEach(restriction -> shapes.add(restriction.getGeometry()));
        double[] bins = new double[90];
        double[] degs = new double[90];
        double total = 0;
        for (Geometry shape : shapes) {
            for (Object part : org.locationtech.jts.geom.util.LinearComponentExtracter.getLines(shape)) {
                Coordinate[] c = ((LineString) part).getCoordinates();
                for (int i = 0; i + 1 < c.length; i++) {
                    double length = c[i].distance(c[i + 1]);
                    if (length < AXIS_SIDE_M || !near.intersects(c[i], c[i + 1])) {
                        continue;
                    }
                    double deg = (Math.toDegrees(Math.atan2(c[i + 1].y - c[i].y, c[i + 1].x - c[i].x)) % 90 + 90) % 90;
                    bins[(int) deg % 90] += length;
                    degs[(int) deg % 90] += length * deg;
                    total += length;
                }
            }
        }
        if (total == 0) {
            return Double.NaN;
        }
        int peak = 0;
        double best = -1;
        for (int k = 0; k < 90; k++) {
            double sum = bins[(k + 89) % 90] + bins[k] + bins[(k + 1) % 90];
            if (sum > best) {
                best = sum;
                peak = k;
            }
        }
        double weighted = 0;
        for (int k = -1; k <= 1; k++) {
            int bin = (peak + k + 90) % 90;
            weighted += degs[bin] + (peak + k < 0 ? -90 : peak + k > 89 ? 90 : 0) * bins[bin];
        }
        return Math.toRadians(weighted / best);
    }

    private static List<Tree.Node> sharpTurns(Tree tree, Set<String> kneed) {
        List<Tree.Node> result = new ArrayList<>();
        for (Tree.Edge in : tree.edges) {
            if (in.to.kind != Tree.Kind.JUNCTION) {
                continue;
            }
            for (Tree.Edge out : tree.edges) {
                if (out.from == in.to && sharp(in, out, kneed)) {
                    result.add(in.to);
                    break;
                }
            }
        }
        return result;
    }

    private Tree retied(Tree tree, Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, Region region,
            Envelope area, List<Tree> others) {
        int dn = 0;
        List<Tree.Edge> kinked = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == tree.root) {
                dn = Math.max(dn, dnByEdge.get(edge));
            }
        }
        Tree straight = straightTied(tree, dn, dnByEdge, priceRub, region, area, others);
        if (straight != null || tree.tie.isChamber()) {
            return straight;
        }
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] c = edge.line.getCoordinates();
            if (edge.from == tree.root) {
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
            if (unitScore(List.of(changed)) <= before + UNKINK_EPS && !worse(tree, List.of(tree), changed, List.of(changed))) {
                return changed;
            }
        }
        return null;
    }

    private Tree straightTied(Tree tree, int dn, Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub,
            Region region, Envelope area, List<Tree> others) {
        List<Object[]> shots = new ArrayList<>();
        for (Tree.Edge s : tree.edges) {
            if (s.from != tree.root) {
                continue;
            }
            Coordinate[] c = s.line.getCoordinates();
            boolean inside = s.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(s.to.connection.getId());
            for (int k = c.length - (inside ? 2 : 1); k >= 2; k--) {
                List<TieCandidate> ties = new ArrayList<>();
                for (TieCandidate tie : finder.direct(factory.createPoint(c[k]), dn)) {
                    LineString pipe = tie.isChamber() ? null : finder.pipe(tie);
                    if (pipe == null) {
                        continue;
                    }
                    ties.add(tie);
                    Coordinate hit = k + 1 < c.length ? rayHit(pipe, c[k], c[k + 1]) : null;
                    TieCandidate inline = hit == null ? null : finder.shifted(tie, hit, 0, dn);
                    if (inline != null && !inline.isChamber() && inline.getExistingObjectId().equals(tie.getExistingObjectId())) {
                        ties.add(inline);
                    }
                }
                for (TieCandidate tie : ties) {
                    LineString pipe = finder.pipe(tie);
                    if (!crosses(pipe, tie.getPoint().getCoordinate(), c[k])) {
                        continue;
                    }
                    TreeBuilder.Slide slide = builder.retie(tree, s, tie.getPoint().getCoordinate(), priceRub, 0, k, true);
                    if (slide != null) {
                        shots.add(new Object[] {k, slide, tie});
                    }
                }
            }
        }
        shots.sort(Comparator.<Object[]>comparingInt(o -> -(int) o[0]).thenComparingDouble(o -> ((TreeBuilder.Slide) o[1]).gain));
        double before = Double.NaN;
        int tried = 0;
        for (Object[] shot : shots) {
            if (tried >= STRAIGHT_TIE_TRIES) {
                break;
            }
            TreeBuilder.Slide slide = (TreeBuilder.Slide) shot[1];
            TieCandidate tie = (TieCandidate) shot[2];
            if (!builder.retieClear(tree, slide, tie.getIgnored(), edge -> region.obstacles(dnByEdge.get(edge), area))) {
                continue;
            }
            tried++;
            Tree changed = unkinked(tree, slide, tie, dnByEdge, region, area, false);
            if (changed == null || !compatible(changed, others) || vertices(changed) >= vertices(tree)) {
                continue;
            }
            before = Double.isNaN(before) ? unitScore(List.of(tree)) : before;
            if (unitScore(List.of(changed)) <= before + UNKINK_EPS && !worse(tree, List.of(tree), changed, List.of(changed))) {
                return changed;
            }
        }
        return null;
    }

    private Coordinate rayHit(LineString pipe, Coordinate from, Coordinate back) {
        double length = from.distance(back);
        if (length < 1e-9) {
            return null;
        }
        double reach = pipe.distance(factory.createPoint(from)) + pipe.getLength();
        Coordinate far = new Coordinate(from.x + (from.x - back.x) / length * reach, from.y + (from.y - back.y) / length * reach);
        org.locationtech.jts.geom.Geometry hits = pipe.intersection(factory.createLineString(new Coordinate[] {from, far}));
        Coordinate best = null;
        for (Coordinate hit : hits.getCoordinates()) {
            if (best == null || hit.distance(from) < best.distance(from)) {
                best = hit;
            }
        }
        return best;
    }

    private static boolean crosses(LineString pipe, Coordinate tie, Coordinate next) {
        LengthIndexedLine axis = new LengthIndexedLine(pipe);
        double at = axis.project(tie);
        Coordinate along = axis.extractPoint(Math.min(at + 1, pipe.getLength()));
        Coordinate back = axis.extractPoint(Math.max(at - 1, 0));
        double dx = along.x - back.x;
        double dy = along.y - back.y;
        double vx = next.x - tie.x;
        double vy = next.y - tie.y;
        double norm = Math.hypot(dx, dy) * Math.hypot(vx, vy);
        return norm > 1e-9 && Math.abs(dx * vx + dy * vy) / norm <= Math.cos(Math.toRadians(STRAIGHT_TIE_MIN_DEG));
    }

    private static int vertices(Tree tree) {
        int count = 0;
        for (Tree.Edge edge : tree.edges) {
            count += edge.line.getNumPoints() - 2;
        }
        return count;
    }

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
            Router router = shaper(region, area, dn);
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

    private Router shaper(Region region, Envelope area, int dn) {
        Router router = null;
        for (Diameter graph = rules.diameter(dn); router == null && graph != null; graph = rules.nextDiameter(graph.getDn())) {
            router = region.built(graph.getDn() + "@" + area);
        }
        return router;
    }

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

    private double oksClearance(int dn) {
        return rules.restriction(OKS_EXISTING).clearanceM(dn) + rules.diameter(dn).getWidthM() / 2;
    }

    private double otherTieM() {
        return rules.chamberRule().getMaxDistM();
    }

    private static int alternativeIndex(List<Option> options, int rank, double otherTieM) {
        List<TieCandidate> seen = new ArrayList<>();
        if (!options.isEmpty()) {
            seen.add(options.get(0).tree.tie);
        }
        for (int i = 1; i < options.size(); i++) {
            TieCandidate other = options.get(i).tree.tie;
            boolean distinct = seen.stream().allMatch(tie -> !tie.getExistingObjectId().equals(other.getExistingObjectId())
                    || tie.getPoint().distance(other.getPoint()) > otherTieM);
            if (distinct) {
                seen.add(other);
                if (seen.size() - 1 == rank) {
                    return i;
                }
            }
        }
        return 0;
    }

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

    private boolean sameRoute(Draft a, Draft b) {
        return RouteBand.same(band(a), band(b), SAME_ROUTE_SHARE);
    }

    private static Set<String> layout(List<Tree> trees) {
        Map<String, Set<String>> idsByRoot = new HashMap<>();
        Map<String, String> objectByRoot = new HashMap<>();
        for (Tree tree : trees) {
            objectByRoot.put(tree.root.key, tree.tie.getExistingObjectId());
            for (ConnectionPoint connection : tree.connected()) {
                idsByRoot.computeIfAbsent(tree.root.key, key -> new TreeSet<>()).add(connection.getId());
            }
        }
        Set<String> layout = new HashSet<>();
        idsByRoot.forEach((root, ids) -> layout.add(objectByRoot.get(root) + " " + ids));
        return layout;
    }

    private RouteBand band(Draft draft) {
        if (draft.band == null) {
            List<LineString> edges = draft.trees.stream().flatMap(tree -> tree.edges.stream()).map(edge -> edge.line)
                    .collect(Collectors.toList());
            draft.band = new RouteBand(edges, edges.size() + draft.trees.size(), DRAFT_ROUTE_M, factory);
        }
        return draft.band;
    }

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

    private List<Point> points(List<ConnectionPoint> connections) {
        List<Point> points = new ArrayList<>();
        connections.forEach(connection -> points.add(connection.getGeometry()));
        if (connections.size() > 1) {
            points.add(factory.createPoint(center(connections)));
        }
        return points;
    }

    private static List<List<ConnectionPoint>> groups(List<ConnectionPoint> connections) {
        return groupsFast(connections);
    }

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
