package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.graph.ObstacleIndex;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

/**
 * Прямые подключения для города: точка подключения соединяется одним прямым участком (или двумя, через точку
 * выхода из своего ОКС) с ближайшей существующей камерой, новой камерой на ближайшей трубе или уже поставленной
 * новой камерой со свободным местом. Без графа: на миллионах точек у существующей сети перебор районов не нужен,
 * а прямой участок к камере в метре от точки и есть дешёвое подключение. Точки, у которых прямого допустимого
 * участка нет, остаются для расчёта районами.
 */
final class DirectTies {
    private static final Logger log = LoggerFactory.getLogger(DirectTies.class);
    private static final int NEAREST = 3;
    /** Дальше этого прямое подключение не пробуется (свойство heatnet.city.reach): там трасса с обходами, её строит граф. */
    private static final double REACH_M = Double.parseDouble(System.getProperty("heatnet.city.reach", "300"));
    /** Сколько точек контура пробуется как начало финального участка: ближайшая, затем ближайшие точки сторон. */
    private static final int PORTAL_TRIES = 8;
    /** Столько ближних точек подряд получают участки к уже поставленным новым камерам одной параллельной пачкой. */
    private static final int AHEAD = 32;
    private static final double PORTAL_EXTRA_M = 0.3;
    private static final double PORTAL_STEP_M = 0.5;
    private static final double PORTAL_MAX_M = 30;
    private static final double DIST_EPS_M = 0.001;
    private static final double ANGLE_EPS_DEG = 0.01;
    /**
     * Допуск, с которым луч выхода задевает чужую зону и сторона считается закрытой: 4,999 м — это 5 м (протокол
     * 16.09.2026), так же читает check18.py. Иначе сервис счёл бы закрытой сторону, которую проверщик считает открытой.
     */
    private static final double CLOSED_EPS_M = 0.01;
    /**
     * Прямой участок от врезки в зоне отступа своего здания длиннее внутри здания, чем от точки до ближайшей границы,
     * не больше чем на столько: у check18.py предел 1 м.
     */
    private static final double NEAR_SIDE_M = 0.9;
    /** Точка ближе этого к трубе стоит у самой трубы: без допустимого участка от проекции врезка сдвигается вдоль. */
    private static final double NEAR_PIPE_M = 3;
    /** Сдвиги врезки от проекции вдоль оси: частые рядом, реже дальше. */
    private static final double[] SHIFTS_M = {0.5, 1, 1.5, 2, 3, 4, 6, 8, 11, 15};
    private static final String OKS_EXISTING = "oks_existing";
    private static final String HEAT_NETWORK = "heat_network";

    private final Rules rules;
    private final ObstacleIndex index;
    private final TieInFinder finder;
    private final NetworkAssembler assembler;
    private final Map<String, ExistingOks> buildingByConnection;
    private final GeometryFactory factory = new GeometryFactory();
    private final STRtree chambers = new STRtree();
    private final STRtree pipes = new STRtree();
    /** Рамка существующей сети: труб и камер. */
    private final Envelope network = new Envelope();
    /** Рамка сети с запасом REACH_M: точка вне её дальше REACH_M от любой врезки, кандидаты у неё не считаются. */
    private final Envelope withinReach;
    /** Почему прямые участки отбрасывались, для лога: причина → число. */
    private final Map<String, Integer> rejected = new java.util.TreeMap<>();

    /** Одно прямое подключение: врезка, линия от неё до точки, стоимость по грубой оценке. */
    private static final class Option {
        final TieCandidate tie;
        final Coordinate[] line;
        final double cost;

        Option(TieCandidate tie, Coordinate[] line, double cost) {
            this.tie = tie;
            this.line = line;
            this.cost = cost;
        }
    }

    DirectTies(InputData input, Rules rules, ObstacleIndex index, TieInFinder finder,
            Map<String, ExistingOks> buildingByConnection, NetworkAssembler assembler) {
        this.rules = rules;
        this.index = index;
        this.finder = finder;
        this.assembler = assembler;
        this.buildingByConnection = buildingByConnection;
        for (Chamber chamber : input.getChambers()) {
            chambers.insert(chamber.getGeometry().getEnvelopeInternal(), chamber);
            network.expandToInclude(chamber.getGeometry().getEnvelopeInternal());
        }
        for (NetworkSegment segment : input.getSegments()) {
            pipes.insert(segment.getGeometry().getEnvelopeInternal(), segment);
            network.expandToInclude(segment.getGeometry().getEnvelopeInternal());
        }
        withinReach = new Envelope(network);
        withinReach.expandBy(REACH_M);
        chambers.build();
        pipes.build();
    }

    /** Учёт мест одного варианта: занятые места по узлам врезки и поставленные новые камеры. */
    private static final class Ledger {
        final List<Tree> trees = new ArrayList<>();
        final Map<String, Integer> used = new HashMap<>();
        /** Новые камеры парами «ключ узла → врезка»: ключ через String.format у каждой точки заметно тормозил. */
        final Quadtree created = new Quadtree();
        final Map<String, TieCandidate> createdByKey = new HashMap<>();
        /** Поставленные деревья по рамкам: новый участок их не касается, см. {@link #touches}. */
        final Quadtree placed = new Quadtree();
    }

    /** Общие для вариантов кандидаты точки: существующие камеры и трубы с допустимыми прямыми участками. */
    private static final class Shared {
        final int dn;
        final boolean far;
        final List<Option> options = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        /** Отказы, с которыми кандидаты считались: в общий счёт идут по порядку точек. */
        final Map<String, Integer> rejected = new HashMap<>();
        /** Участки к новым камерам, посчитанные заранее пачкой точек, см. {@link #ahead}. */
        final Map<TieCandidate, Tried> tried = new IdentityHashMap<>();
        /** Выход из своего здания, общий для всех врезок точки, см. {@link #portal}. */
        Portal portal;

        Shared(int dn, boolean far) {
            this.dn = dn;
            this.far = far;
        }
    }

    /** Точка вне рамки сети с запасом REACH_M: кандидатов у неё нет, один отказ «далеко». */
    private static final Shared FAR = new Shared(0, true);

    static {
        FAR.rejected.put("далеко", 1);
    }

    /**
     * Выход из своего здания у ближайшей открытой стороны: точка выхода (null — все стороны закрыты) и та ли это
     * сторона, что ближе всех.
     */
    private static final class Portal {
        final Coordinate exit;
        final boolean nearest;

        Portal(Coordinate exit, boolean nearest) {
            this.exit = exit;
            this.nearest = nearest;
        }
    }

    private static final Portal NO_PORTAL = new Portal(null, false);

    /** Прямой участок к новой камере: вариант подключения (null — участок недопустим) и отказы при его расчёте. */
    private static final class Tried {
        final Option option;
        final Map<String, Integer> rejected;

        Tried(Option option, Map<String, Integer> rejected) {
            this.option = option;
            this.rejected = rejected;
        }
    }

    /**
     * Деревья прямых подключений {@code variants} вариантов в порядке точек: вариант k берёт у точки k-й по стоимости
     * вариант (у кого их меньше — последний). Кандидаты и допустимость прямых участков считаются один раз на точку,
     * места в камерах — отдельно на вариант: у существующей камеры не больше четырёх участков, у новой камеры на
     * трубе — по два участка каждой проходящей трубы и новые в остаток до четырёх. Точки без допустимого варианта
     * попадают в {@code rest}.
     */
    List<List<Tree>> connect(List<ConnectionPoint> connections, Map<String, FutureOks> oksById, int variants,
            List<ConnectionPoint> rest) {
        List<Ledger> ledgers = new ArrayList<>();
        for (int k = 0; k < variants; k++) {
            ledgers.add(new Ledger());
        }
        long started = System.nanoTime();
        // общие кандидаты от учёта мест не зависят и считаются параллельно, места и новые камеры — по порядку точек.
        // На городе 98 % точек вне рамки сети с запасом REACH_M, любой кандидат у них дальше REACH_M; ближние идут
        // подряд в начале списка, поэтому параллельно считаются только они, иначе почти все достаются одной нити
        // расходы и рамка — проход по 3 млн объектов, параллельно; диаметр по порядку, чтобы ошибка расхода была прежней
        double[] flows = new double[connections.size()];
        boolean[] reachable = new boolean[connections.size()];
        IntStream.range(0, connections.size()).parallel().forEach(i -> {
            ConnectionPoint connection = connections.get(i);
            FutureOks oks = oksById.get(connection.getOksId());
            flows[i] = oks == null ? 0.0 : oks.getFlowTph();
            reachable[i] = withinReach.contains(connection.getGeometry().getCoordinate());
        });
        Shared[] prepared = new Shared[connections.size()];
        List<Integer> near = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            int dn = rules.diameterFor(flows[i]).getDn();
            if (reachable[i]) {
                prepared[i] = new Shared(dn, false);
                near.add(i);
            } else {
                prepared[i] = FAR;
            }
        }
        near.parallelStream().forEach(i -> shared(connections.get(i), prepared[i]));
        int placed = 0;
        for (int i = 0; i < connections.size(); i++) {
            if ((i + 1) % 500_000 == 0) {
                log.info("direct: {}/{} points, rest={} elapsed={}s", i + 1, connections.size(), rest.size(),
                        (System.nanoTime() - started) / 1_000_000_000L);
            }
            ConnectionPoint connection = connections.get(i);
            Shared shared = prepared[i];
            shared.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
            boolean connected = false;
            if (!shared.far) {
                if (placed % AHEAD == 0) {
                    ahead(connections, prepared, near.subList(placed, Math.min(near.size(), placed + AHEAD)), ledgers);
                }
                placed++;
                connected = place(connection, shared, ledgers);
            }
            if (!connected) {
                rest.add(connection);
                if (rest.size() <= 3) {
                    log.info("direct: {} без прямого подключения, отказы {}", connection.getId(), rejected);
                }
            }
        }
        List<List<Tree>> result = new ArrayList<>();
        for (Ledger ledger : ledgers) {
            result.add(ledger.trees);
        }
        log.info("direct: trees={} rest={} отказы {}", result.get(0).size(), rest.size(), rejected);
        return result;
    }

    /**
     * Общие кандидаты точки у сети: ближайшие существующие камеры и врезки в ближайшие трубы; если ни одного нет,
     * врезки, сдвинутые вдоль ближайших труб ({@link #along}). От учёта мест они не зависят и тоже общие.
     */
    private void shared(ConnectionPoint connection, Shared shared) {
        int dn = shared.dn;
        Point point = connection.getGeometry();
        for (Object item : chambers.isEmpty() ? List.of() : nearest(chambers, point)) {
            TieCandidate tie = finder.chamberCandidate((Chamber) item);
            if (tie != null && shared.seen.add(tie.nodeKey())) {
                add(shared.options, option(tie, connection, shared, rules.tieInCost(), false, shared.rejected));
            }
        }
        List<Object> nearPipes = pipes.isEmpty() ? List.of() : nearest(pipes, point);
        for (Object item : nearPipes) {
            NetworkSegment segment = (NetworkSegment) item;
            TieCandidate tie = finder.pipeCandidate(segment, point, dn);
            if (tie == null || !shared.seen.add(tie.nodeKey())) {
                continue;
            }
            add(shared.options, option(tie, connection, shared, nodeCost(tie, segment, dn), false, shared.rejected));
        }
        if (shared.options.isEmpty()) {
            for (Object item : nearPipes) {
                along(shared, (NetworkSegment) item, connection);
            }
        }
    }

    /**
     * Точка у самой трубы, у которой прямой участок от проекции недопустим: короче метра, упирается в дорогу, соседнюю
     * трубу или чужое здание. Врезка сдвигается вдоль оси в обе стороны на SHIFTS_M, пока остаётся в своём
     * здании или его зоне отступа (участок от неё один прямой, CONSTRAINTS.md раздел 19); в каждую сторону берётся
     * ближайшая, от которой прямой участок допустим. Участок может пересечь чужую трубу специальным проходом, поэтому
     * дерево ещё и собирается пробно.
     */
    private void along(Shared shared, NetworkSegment segment, ConnectionPoint connection) {
        int dn = shared.dn;
        LineString axis = segment.getGeometry();
        Point point = connection.getGeometry();
        if (!axis.isWithinDistance(point, NEAR_PIPE_M)) {
            return;
        }
        ExistingOks building = buildingByConnection.get(connection.getId());
        double clearance = rules.restriction(OKS_EXISTING).clearanceM(dn) + rules.diameter(dn).getWidthM() / 2;
        double at = new LengthIndexedLine(axis).project(point.getCoordinate());
        for (int sign : new int[] {-1, 1}) {
            for (double shift : SHIFTS_M) {
                TieCandidate tie = finder.pipeCandidate(segment, at + sign * shift, dn);
                if (tie == null || !shared.seen.add(tie.nodeKey())
                        || building != null && !building.getGeometry().isWithinDistance(tie.getPoint(), clearance)) {
                    continue;
                }
                Option option = option(tie, connection, shared, nodeCost(tie, segment, dn), true, shared.rejected);
                if (option != null && assembles(tree(option, connection), shared.rejected)) {
                    shared.options.add(option);
                    break;
                }
            }
        }
    }

    /**
     * Дерево собирается само по себе: зоны спецпроходов, отступы и форма участков как у итоговой сборки. Зоны
     * пересечения трубы и соседней дороги могут оставить между собой обычный кусок у трубы врезки, а он начинается не
     * во врезке, и отступ от её трубы для него не снимается.
     */
    private boolean assembles(Tree tree, Map<String, Integer> reasons) {
        try {
            assembler.assemble("0", 0, List.of(tree), List.of());
            return true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            return reject(reasons, "сборка");
        }
    }

    private Tree tree(Option option, ConnectionPoint connection) {
        Tree tree = new Tree(option.tie);
        Coordinate[] coords = option.line.clone();
        coords[0] = tree.root.point;
        tree.edges.add(new Tree.Edge(tree.root, Tree.Node.connection(connection), factory.createLineString(coords)));
        return tree;
    }

    /** Цена узла врезки: врезка в существующую камеру или новая камера на трубе. */
    private double nodeCost(TieCandidate tie, NetworkSegment segment, int dn) {
        return tie.isChamber() ? rules.tieInCost() : rules.chamberCost(Math.max(dn, segment.getDiameter()));
    }

    /**
     * Участки от следующих ближних точек ко всем новым камерам рядом, уже поставленным и со свободным местом, одной
     * параллельной пачкой: у точки таких камер в среднем 18, и параллельный расчёт по одной точке больше ждал нитей,
     * чем считал. Место в камере только убывает, поэтому лишними окажутся лишь камеры, заполненные внутри пачки;
     * камеры, поставленные точками пачки, досчитывает {@link #place}.
     */
    private void ahead(List<ConnectionPoint> connections, Shared[] prepared, List<Integer> points, List<Ledger> ledgers) {
        List<Integer> owners = new ArrayList<>();
        List<TieCandidate> ties = new ArrayList<>();
        for (int i : points) {
            Map<TieCandidate, Boolean> free = new IdentityHashMap<>();
            for (Ledger ledger : ledgers) {
                free(ledger, connections.get(i), prepared[i]).forEach(tie -> free.put(tie, true));
            }
            for (TieCandidate tie : free.keySet()) {
                owners.add(i);
                ties.add(tie);
            }
        }
        List<Tried> results = IntStream.range(0, ties.size()).parallel()
                .mapToObj(t -> tried(ties.get(t), connections.get(owners.get(t)), prepared[owners.get(t)]))
                .collect(Collectors.toList());
        for (int t = 0; t < ties.size(); t++) {
            prepared[owners.get(t)].tried.put(ties.get(t), results.get(t));
        }
    }

    /**
     * Подключение точки в каждом варианте: общие кандидаты со свободным местом и поставленные раньше новые камеры
     * рядом. Участки к новым камерам от варианта не зависят и считаются по разу на камеру; отказы учитываются по
     * вариантам, как при расчёте подряд. false — ни в одном варианте допустимого участка нет.
     */
    private boolean place(ConnectionPoint connection, Shared shared, List<Ledger> ledgers) {
        List<List<TieCandidate>> free = new ArrayList<>();
        List<TieCandidate> missing = new ArrayList<>();
        for (Ledger ledger : ledgers) {
            List<TieCandidate> ties = free(ledger, connection, shared);
            for (TieCandidate tie : ties) {
                if (!shared.tried.containsKey(tie)) {
                    shared.tried.put(tie, null);
                    missing.add(tie);
                }
            }
            free.add(ties);
        }
        List<Tried> results = missing.parallelStream().map(tie -> tried(tie, connection, shared))
                .collect(Collectors.toList());
        for (int t = 0; t < missing.size(); t++) {
            shared.tried.put(missing.get(t), results.get(t));
        }
        boolean connected = false;
        for (int k = 0; k < ledgers.size(); k++) {
            Ledger ledger = ledgers.get(k);
            List<Option> options = new ArrayList<>();
            for (Option option : shared.options) {
                if (roomIn(option.tie, ledger.used)) {
                    options.add(option);
                }
            }
            for (TieCandidate tie : free.get(k)) {
                Tried result = shared.tried.get(tie);
                result.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
                add(options, result.option);
            }
            options.sort(Comparator.comparingDouble(option -> option.cost));
            // k-й по стоимости из участков, которые не касаются поставленных раньше (у кого их меньше — последний)
            Option chosen = null;
            int apart = 0;
            for (Option option : options) {
                if (!touches(ledger, option)) {
                    chosen = option;
                    if (apart++ == k) {
                        break;
                    }
                }
            }
            if (chosen == null) {
                continue;
            }
            connected = true;
            String key = chosen.tie.nodeKey();
            ledger.used.merge(key, 1, Integer::sum);
            if (!chosen.tie.isChamber() && ledger.createdByKey.putIfAbsent(key, chosen.tie) == null) {
                ledger.created.insert(chosen.tie.getPoint().getEnvelopeInternal(), Map.entry(key, chosen.tie));
            }
            Tree tree = tree(chosen, connection);
            ledger.trees.add(tree);
            ledger.placed.insert(tree.envelope(), tree);
        }
        shared.tried.clear();
        return connected;
    }

    /**
     * Участок option ближе VariantEnumerator.TREES_APART_M к деревьям других врезок варианта или у общей врезки
     * касается её деревьев вне круга SHARED_ROOT_CLIP_M, как деревья районов (VariantEnumerator#compatible): новые
     * участки не пересекаются вне общего узла (п. 5), а сборка по врезкам друг с другом их не сверяет.
     */
    private boolean touches(Ledger ledger, Option option) {
        LineString line = factory.createLineString(option.line);
        Envelope around = new Envelope(line.getEnvelopeInternal());
        around.expandBy(VariantEnumerator.TREES_APART_M);
        for (Object item : ledger.placed.query(around)) {
            Tree other = (Tree) item;
            if (!other.envelope().intersects(around)) {
                continue;
            }
            if (other.root.key.equals(option.tie.nodeKey())) {
                Geometry clip = factory.createPoint(other.root.point).buffer(VariantEnumerator.SHARED_ROOT_CLIP_M);
                if (line.difference(clip).distance(other.geometry().difference(clip))
                        <= VariantEnumerator.SHARED_ROOT_APART_M) {
                    return true;
                }
            } else if (line.distance(other.geometry()) <= VariantEnumerator.TREES_APART_M) {
                return true;
            }
        }
        return false;
    }

    /** Новые камеры варианта у точки со свободным местом, кроме её общих кандидатов, в порядке индекса. */
    private static List<TieCandidate> free(Ledger ledger, ConnectionPoint connection, Shared shared) {
        Envelope around = new Envelope(connection.getGeometry().getCoordinate());
        around.expandBy(REACH_M);
        List<TieCandidate> ties = new ArrayList<>();
        for (Object item : ledger.created.query(around)) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, TieCandidate> created = (Map.Entry<String, TieCandidate>) item;
            String key = created.getKey();
            TieCandidate tie = created.getValue();
            if (ledger.used.getOrDefault(key, 0) < tie.getCapacity() && !shared.seen.contains(key)) {
                ties.add(tie);
            }
        }
        return ties;
    }

    private Tried tried(TieCandidate tie, ConnectionPoint connection, Shared shared) {
        Map<String, Integer> reasons = new HashMap<>();
        return new Tried(option(tie, connection, shared, 0, false, reasons), reasons);
    }

    private static boolean reject(Map<String, Integer> reasons, String reason) {
        reasons.merge(reason, 1, Integer::sum);
        return false;
    }

    private static void add(List<Option> options, Option option) {
        if (option != null) {
            options.add(option);
        }
    }

    /**
     * В узле врезки после уже сделанных подключений есть место ещё для одного участка. Место и у новой камеры на
     * трубе: точки приходят к ней и как к своему кандидату, и как к поставленной камере.
     */
    private static boolean roomIn(TieCandidate tie, Map<String, Integer> used) {
        return used.getOrDefault(tie.nodeKey(), 0) < tie.getCapacity();
    }

    /**
     * Расстояние от точки до ближайшего объекта существующей сети: трубы или камеры. Точка дальше {@code limit} от
     * рамки сети дальше limit и от самой сети: для неё +∞ без запросов к индексам (на городе таких 3 млн).
     */
    double networkDistance(Point point, double limit) {
        if (network.isNull() || network.distance(point.getEnvelopeInternal()) > limit) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        for (STRtree tree : List.of(pipes, chambers)) {
            if (tree.isEmpty()) {
                continue;
            }
            ItemDistance distance = (a, b) -> ((Geometry) geometry(a)).distance((Geometry) geometry(b));
            Object nearest = tree.nearestNeighbour(point.getEnvelopeInternal(), point, distance);
            best = Math.min(best, ((Geometry) geometry(new ItemBoundable(null, nearest))).distance(point));
        }
        return best;
    }

    /** Наборы деревьев одинаковы: те же узлы врезки у тех же точек. */
    boolean same(List<Tree> a, List<Tree> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).root.key.equals(b.get(i).root.key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Вариант подключения к врезке; null — прямой участок недопустим, причина в {@code reasons}. {@code crossPipes} —
     * участок может пересечь чужую трубу специальным проходом.
     */
    private Option option(TieCandidate tie, ConnectionPoint connection, Shared shared, double nodeCost,
            boolean crossPipes, Map<String, Integer> reasons) {
        int dn = shared.dn;
        Coordinate[] line = line(tie, connection, shared, crossPipes, reasons);
        if (line == null) {
            return null;
        }
        double length = 0;
        for (int i = 0; i + 1 < line.length; i++) {
            length += line[i].distance(line[i + 1]);
        }
        Diameter byLength = rules.diameterForLength(length);
        int actual = Math.max(dn, byLength == null ? dn : byLength.getDn());
        return new Option(tie, line, nodeCost + length * rules.diameter(actual).getNewRubM());
    }

    /**
     * Линия от врезки к точке: прямая, если врезка внутри полигона точки или в его зоне отступа (участок и так
     * финальный), иначе через точку выхода на луче «точка → ближайшая открытая граница» (приложение 18.09, п. 2.2).
     * null — участок нарушает отступы, идёт вдоль трубы врезки или входит в здание не с ближайшей открытой стороны.
     */
    private Coordinate[] line(TieCandidate tie, ConnectionPoint connection, Shared shared, boolean crossPipes,
            Map<String, Integer> reasons) {
        int dn = shared.dn;
        Coordinate cp = connection.getGeometry().getCoordinate();
        Coordinate tiePoint = tie.getPoint().getCoordinate();
        if (cp.distance(tiePoint) > REACH_M) {
            reject(reasons, "далеко");
            return null;
        }
        ExistingOks building = buildingByConnection.get(connection.getId());
        double clearance = rules.restriction(OKS_EXISTING).clearanceM(dn) + rules.diameter(dn).getWidthM() / 2;
        String own = building == null ? null : building.getId();
        if (building == null || building.getGeometry().distance(tie.getPoint()) <= clearance) {
            Coordinate[] line = {tiePoint, cp};
            if (cp.distance(tiePoint) < TreeBuilder.MIN_PIECE_M) {
                reject(reasons, "короче метра");
                return null;
            }
            if (building != null && !nearSide(line, building, shared, clearance, reasons)) {
                return null;
            }
            return valid(line, 0, tie, dn, own, crossPipes, reasons) ? line : null;
        }
        // выход только у ближайшей открытой стороны: дальняя допустима, лишь когда ближние закрыты (п. 2.2), поэтому
        // недопустимый участок от выхода к врезке не переводит точку на другую сторону, как и у веток дерева
        Coordinate exit = portal(cp, shared, building, clearance).exit;
        if (exit == null) {
            reject(reasons, "нет выхода");
            return null;
        }
        if (exit.distance(tiePoint) < TreeBuilder.MIN_PIECE_M) {
            reject(reasons, "поворот у выхода");
            return null;
        }
        Coordinate[] line = {tiePoint, exit, cp};
        double turn = Router.deflectionDeg(tiePoint, exit, cp);
        if (turn < TreeBuilder.MIN_TURN_DEG) {
            // врезка почти на продолжении луча: излом меньше 3° сборка не пропустит и отбросит весь узел врезки,
            // поэтому участок один прямой, как у ветки дерева после TreeBuilder#straighten
            Coordinate[] straight = {tiePoint, cp};
            return nearSide(straight, building, shared, clearance, reasons)
                    && valid(straight, 0, tie, dn, own, crossPipes, reasons) ? straight : null;
        }
        if (turn <= Router.MAX_TURN_DEG) {
            return valid(line, 1, tie, dn, own, crossPipes, reasons) ? line : null;
        }
        // врезка позади выхода, поворот в нём круче 90°: звено после выхода, как у ветки дерева (TreeBuilder#exitLink)
        for (double length : TreeBuilder.EXIT_LINK ? TreeBuilder.EXIT_LINK_M : new double[0]) {
            Coordinate link = TreeBuilder.exitLink(new Coordinate[] {cp, exit, tiePoint}, length)[2];
            Coordinate[] linked = {tiePoint, link, exit, cp};
            double linkTurn = Router.deflectionDeg(tiePoint, link, exit);
            if (link.distance(tiePoint) >= TreeBuilder.MIN_PIECE_M
                    && linkTurn >= TreeBuilder.MIN_TURN_DEG && linkTurn <= Router.MAX_TURN_DEG
                    && valid(linked, 2, tie, dn, own, crossPipes, reasons)) {
                return linked;
            }
        }
        reject(reasons, "поворот у выхода");
        return null;
    }

    /**
     * Прямой участок от врезки в зоне отступа своего здания выходит из здания один раз и, если ближняя сторона
     * открыта, входит в него у ближайшей к точке границы (приложение 18.09, п. 2.2). Врезка на трубе внутри своего
     * здания границу не пересекает, её участок — толкование CONSTRAINTS.md, раздел 19.
     */
    private boolean nearSide(Coordinate[] line, ExistingOks building, Shared shared, double clearance,
            Map<String, Integer> reasons) {
        Geometry polygon = building.getGeometry();
        Coordinate cp = line[line.length - 1];
        if (!TreeBuilder.leavesOnce(polygon, cp, line[0])) {
            return reject(reasons, "снова через своё здание");
        }
        if (polygon.intersects(factory.createPoint(line[0])) || !portal(cp, shared, building, clearance).nearest) {
            return true;
        }
        Point point = factory.createPoint(cp);
        double toEdge = Double.POSITIVE_INFINITY;
        for (int g = 0; g < polygon.getNumGeometries(); g++) {
            org.locationtech.jts.geom.Polygon part = (org.locationtech.jts.geom.Polygon) polygon.getGeometryN(g);
            toEdge = Math.min(toEdge, part.getExteriorRing().distance(point));
        }
        if (polygon.intersection(factory.createLineString(line)).getLength() <= toEdge + NEAR_SIDE_M) {
            return true;
        }
        return reject(reasons, "не от ближайшей границы");
    }

    /**
     * Выход у ближайшей открытой стороны своего здания: ближайшая точка внешнего контура, если луч через неё не
     * закрыт, иначе ближайшие точки других сторон по очереди. Сторона закрыта, если выхода на луче нет, луч снова
     * входит в своё здание или задевает зону чужого здания или запретного объекта (docs/interpretation.md, так же
     * судит check18.py). От врезки выход не зависит: у точки два десятка врезок, и поиск выхода с проверкой здания шёл
     * на каждую. Врезки точки считаются в разных нитях, поэтому выход ищется под замком точки.
     */
    private Portal portal(Coordinate cp, Shared shared, ExistingOks building, double clearance) {
        synchronized (shared) {
            if (shared.portal == null) {
                shared.portal = NO_PORTAL;
                Coordinate last = null;
                int tries = 0;
                for (Coordinate anchor : anchors(building.getGeometry(), cp)) {
                    if (last != null && anchor.distance(last) < TreeBuilder.MIN_PIECE_M) {
                        continue;
                    }
                    last = anchor;
                    if (tries++ >= PORTAL_TRIES) {
                        break;
                    }
                    Coordinate exit = exit(cp, anchor, building, clearance);
                    if (exit != null && TreeBuilder.leavesOnce(building.getGeometry(), cp, exit)
                            && !closed(cp, exit, shared.dn, building.getId())) {
                        shared.portal = new Portal(exit, tries == 1);
                        break;
                    }
                }
            }
            return shared.portal;
        }
    }

    /** Отрезок от точки до выхода ближе отступа к чужому зданию или запретному объекту: сторона закрыта. */
    private boolean closed(Coordinate cp, Coordinate exit, int dn, String ownId) {
        LineString ray = factory.createLineString(new Coordinate[] {cp, exit});
        Envelope envelope = ray.getEnvelopeInternal();
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        double oksClearance = rules.restriction(OKS_EXISTING).clearanceM(dn) + halfWidth;
        for (ExistingOks oks : index.existingOks(envelope)) {
            if (!oks.getId().equals(ownId) && oks.getGeometry().distance(ray) < oksClearance - CLOSED_EPS_M) {
                return true;
            }
        }
        for (Restriction restriction : index.restrictions(envelope)) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            if (rule.forbid()
                    && restriction.getGeometry().distance(ray) < rule.clearanceM(dn) + halfWidth - CLOSED_EPS_M) {
                return true;
            }
        }
        return false;
    }

    /** Ближайшие к cp точки сторон внешних контуров полигона по возрастанию расстояния. */
    private static List<Coordinate> anchors(Geometry building, Coordinate cp) {
        List<Coordinate> result = new ArrayList<>();
        for (int g = 0; g < building.getNumGeometries(); g++) {
            Coordinate[] ring = ((org.locationtech.jts.geom.Polygon) building.getGeometryN(g)).getExteriorRing().getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                result.add(new LineSegment(ring[k], ring[k + 1]).closestPoint(cp));
            }
        }
        result.sort(Comparator.comparingDouble(cp::distance));
        return result;
    }

    /** Точка выхода за зоной отступа своего полигона на луче через anchor, вне чужих зон запрета. */
    private Coordinate exit(Coordinate cp, Coordinate anchor, ExistingOks building, double clearance) {
        double dx = anchor.x - cp.x;
        double dy = anchor.y - cp.y;
        double length = Math.hypot(dx, dy);
        if (length < 1e-6) {
            Coordinate centroid = building.getGeometry().getCentroid().getCoordinate();
            dx = cp.x - centroid.x;
            dy = cp.y - centroid.y;
            length = Math.hypot(dx, dy);
            if (length < 1e-6) {
                return null;
            }
        }
        dx /= length;
        dy /= length;
        double along = cp.distance(anchor) + clearance + PORTAL_EXTRA_M;
        for (double extra = 0; extra <= PORTAL_MAX_M; extra += PORTAL_STEP_M) {
            Coordinate exit = new Coordinate(cp.x + dx * (along + extra), cp.y + dy * (along + extra));
            if (!insideForbid(exit, clearance, building.getId())) {
                return exit;
            }
        }
        return null;
    }

    private boolean insideForbid(Coordinate c, double oksClearance, String ownId) {
        Point point = factory.createPoint(c);
        Envelope envelope = new Envelope(c);
        for (ExistingOks oks : index.existingOks(envelope)) {
            if (!oks.getId().equals(ownId) && oks.getGeometry().distance(point) < oksClearance - CLOSED_EPS_M) {
                return true;
            }
        }
        return false;
    }

    /**
     * Отрезки линии не ближе отступа к полигонам ОКС (кроме своего у финального отрезка {@code finalPiece} и дальше),
     * запретным ограничениям и существующей сети, не пересекают объекты со специальным проходом и не идут вдоль
     * труб врезки. С {@code crossPipes} чужую трубу можно пересечь специальным проходом.
     */
    private boolean valid(Coordinate[] line, int finalPiece, TieCandidate tie, int dn, String ownId, boolean crossPipes,
            Map<String, Integer> reasons) {
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        double oksClearance = rules.restriction(OKS_EXISTING).clearanceM(dn) + halfWidth;
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        for (int i = 0; i + 1 < line.length; i++) {
            LineString piece = factory.createLineString(new Coordinate[] {line[i], line[i + 1]});
            Envelope envelope = piece.getEnvelopeInternal();
            String exempt = i >= finalPiece ? ownId : null;
            for (ExistingOks oks : index.existingOks(envelope)) {
                if (!oks.getId().equals(exempt) && oks.getGeometry().distance(piece) < oksClearance - DIST_EPS_M) {
                    return reject(reasons, "чужое здание");
                }
            }
            for (Restriction restriction : index.restrictions(envelope)) {
                RestrictionRule rule = rules.restriction(restriction.getType());
                Geometry geometry = restriction.getGeometry();
                double distance = rule.clearanceM(dn) + halfWidth;
                if (geometry.getDimension() < 2 && rule.getHalfWidthM() != null) {
                    distance += rule.getHalfWidthM();
                }
                // дальний объект отсекается расстоянием: оно дешевле пересечения, а город даёт десятки длинных дорог
                // на рамку отрезка
                double gap = geometry.distance(piece);
                if (gap > 0 && gap >= distance - DIST_EPS_M) {
                    continue;
                }
                if (!rule.forbid() && geometry.getDimension() > 0 && geometry.intersects(piece)) {
                    // специальный проход прямым отрезком: у дороги и путей — под углом не меньше заданного
                    if (!crossingAllowed(piece, geometry, rule)) {
                        return reject(reasons, "угол пересечения " + restriction.getType());
                    }
                    continue;
                }
                if (gap < distance - DIST_EPS_M) {
                    return reject(reasons, "ограничение " + restriction.getType());
                }
            }
            for (NetworkSegment segment : index.segments(envelope)) {
                double distance = network.clearanceM(dn) + halfWidth + rules.diameter(segment.getDiameter()).getWidthM() / 2;
                boolean atTie = tie.getIgnored().contains(segment.getId()) && i == 0;
                if (atTie) {
                    // от врезки отрезок уходит от трубы: дальше 0,5 м он её не касается
                    double skip = TieInFinder.TOUCH_M / piece.getLength();
                    if (skip >= 1) {
                        return reject(reasons, "короче 0,5 м");
                    }
                    LineString away = factory.createLineString(new Coordinate[] {
                            new LineSegment(line[0], line[1]).pointAlong(skip), line[1]});
                    if (segment.getGeometry().intersects(away)) {
                        return reject(reasons, "вдоль трубы врезки");
                    }
                } else if (pipeDistance(segment.getGeometry(), line[i], line[i + 1]) < distance - DIST_EPS_M
                        && !(crossPipes && segment.getGeometry().intersects(piece)
                                && crossingAllowed(piece, segment.getGeometry(), network))) {
                    return reject(reasons, "чужая труба");
                }
            }
        }
        return true;
    }

    /**
     * Отрезок пересекает стороны объекта под углом не меньше min_angle_deg и не идёт вдоль них. Отрезок целиком внутри
     * полигона (точка подключения внутри дороги) — тоже специальный проход: точки входа у него нет, угол не проверяется.
     */
    private static boolean crossingAllowed(LineString piece, Geometry geometry, RestrictionRule rule) {
        Coordinate a = piece.getCoordinateN(0);
        Coordinate b = piece.getCoordinateN(1);
        Geometry boundary = geometry.getDimension() == 2 ? geometry.getBoundary() : geometry;
        if (geometry.getDimension() == 2 && !piece.intersects(boundary)) {
            return true;
        }
        boolean crossed = false;
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] ring = boundary.getGeometryN(g).getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                if (!piece.intersects(piece.getFactory().createLineString(new Coordinate[] {ring[k], ring[k + 1]}))) {
                    continue;
                }
                double ux = b.x - a.x;
                double uy = b.y - a.y;
                double vx = ring[k + 1].x - ring[k].x;
                double vy = ring[k + 1].y - ring[k].y;
                double angle = Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), Math.abs(ux * vx + uy * vy)));
                if (angle < ANGLE_EPS_DEG || rule.getMinAngleDeg() != null && angle < rule.getMinAngleDeg() + ANGLE_EPS_DEG) {
                    return false;
                }
                crossed = true;
            }
        }
        return crossed;
    }

    /**
     * То же, что pipe.distance(отрезок a–b), без объектов DistanceOp: рамка отрезка у длинных труб города задевает
     * десятки участков, и это была главная цена проверки.
     */
    private static double pipeDistance(LineString pipe, Coordinate a, Coordinate b) {
        Coordinate[] coords = pipe.getCoordinates();
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < coords.length; i++) {
            best = Math.min(best, Distance.segmentToSegment(coords[i], coords[i + 1], a, b));
        }
        return best;
    }

    private static List<Object> nearest(STRtree tree, Point point) {
        ItemDistance distance = (a, b) -> ((Geometry) geometry(a)).distance((Geometry) geometry(b));
        Object[] found = tree.nearestNeighbour(point.getEnvelopeInternal(), point, distance, NEAREST);
        List<Object> result = new ArrayList<>();
        for (Object item : found) {
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    private static Object geometry(ItemBoundable boundable) {
        Object item = boundable.getItem();
        if (item instanceof Chamber) {
            return ((Chamber) item).getGeometry();
        }
        if (item instanceof NetworkSegment) {
            return ((NetworkSegment) item).getGeometry();
        }
        return item;
    }
}
