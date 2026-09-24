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
    private static final String OKS_EXISTING = "oks_existing";
    private static final String HEAT_NETWORK = "heat_network";

    private final Rules rules;
    private final ObstacleIndex index;
    private final TieInFinder finder;
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
            Map<String, ExistingOks> buildingByConnection) {
        this.rules = rules;
        this.index = index;
        this.finder = finder;
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
        /** Выходы из своего здания, общие для всех врезок точки, см. {@link #portals}. */
        List<Portal> portals;

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
     * Выход из своего здания на луче через сторону контура: точка выхода (null — выхода нет) и пересекает ли отрезок
     * от точки подключения до выхода здание один раз.
     */
    private static final class Portal {
        final Coordinate exit;
        final boolean leavesOnce;

        Portal(Coordinate exit, boolean leavesOnce) {
            this.exit = exit;
            this.leavesOnce = leavesOnce;
        }
    }

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

    /** Общие кандидаты точки у сети: ближайшие существующие камеры и врезки в ближайшие трубы. */
    private void shared(ConnectionPoint connection, Shared shared) {
        int dn = shared.dn;
        Point point = connection.getGeometry();
        for (Object item : chambers.isEmpty() ? List.of() : nearest(chambers, point)) {
            TieCandidate tie = finder.chamberCandidate((Chamber) item);
            if (tie != null && shared.seen.add(tie.nodeKey())) {
                add(shared.options, option(tie, connection, shared, rules.tieInCost(), shared.rejected));
            }
        }
        for (Object item : pipes.isEmpty() ? List.of() : nearest(pipes, point)) {
            NetworkSegment segment = (NetworkSegment) item;
            TieCandidate tie = finder.pipeCandidate(segment, point, dn);
            if (tie == null || !shared.seen.add(tie.nodeKey())) {
                continue;
            }
            add(shared.options, option(tie, connection, shared, tie.isChamber() ? rules.tieInCost()
                    : rules.chamberCost(Math.max(dn, segment.getDiameter())), shared.rejected));
        }
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
                if (!option.tie.isChamber() || roomInChamber(option.tie, ledger.used)) {
                    options.add(option);
                }
            }
            for (TieCandidate tie : free.get(k)) {
                Tried result = shared.tried.get(tie);
                result.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
                add(options, result.option);
            }
            options.sort(Comparator.comparingDouble(option -> option.cost));
            if (options.isEmpty()) {
                continue;
            }
            connected = true;
            Option chosen = options.get(Math.min(k, options.size() - 1));
            String key = chosen.tie.nodeKey();
            ledger.used.merge(key, 1, Integer::sum);
            if (!chosen.tie.isChamber() && ledger.createdByKey.putIfAbsent(key, chosen.tie) == null) {
                ledger.created.insert(chosen.tie.getPoint().getEnvelopeInternal(), Map.entry(key, chosen.tie));
            }
            Tree tree = new Tree(chosen.tie);
            Coordinate[] coords = chosen.line.clone();
            coords[0] = tree.root.point;
            tree.edges.add(new Tree.Edge(tree.root, Tree.Node.connection(connection), factory.createLineString(coords)));
            ledger.trees.add(tree);
        }
        shared.tried.clear();
        return connected;
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
        return new Tried(option(tie, connection, shared, 0, reasons), reasons);
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

    /** В существующей камере после уже сделанных подключений есть место ещё для одного участка. */
    private boolean roomInChamber(TieCandidate tie, Map<String, Integer> used) {
        return finder.links(tie.getExistingObjectId()) + used.getOrDefault(tie.nodeKey(), 0) < rules.chamberRule().getMaxSegments();
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

    /** Вариант подключения к врезке; null — прямой участок недопустим, причина в {@code reasons}. */
    private Option option(TieCandidate tie, ConnectionPoint connection, Shared shared, double nodeCost,
            Map<String, Integer> reasons) {
        int dn = shared.dn;
        Coordinate[] line = line(tie, connection, shared, reasons);
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
     * финальный), иначе через точку выхода на луче «точка → ближайшая граница» (приложение 18.09, п. 2.2). null —
     * участок нарушает отступы или идёт вдоль трубы врезки.
     */
    private Coordinate[] line(TieCandidate tie, ConnectionPoint connection, Shared shared, Map<String, Integer> reasons) {
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
            if (building != null && !TreeBuilder.leavesOnce(building.getGeometry(), cp, tiePoint)) {
                reject(reasons, "снова через своё здание");
                return null;
            }
            return valid(line, 0, tie, dn, own, reasons) ? line : null;
        }
        for (Portal portal : portals(cp, shared, building, clearance)) {
            if (portal.exit == null) {
                reject(reasons, "нет выхода");
                continue;
            }
            if (!portal.leavesOnce) {
                reject(reasons, "снова через своё здание");
                continue;
            }
            Coordinate exit = portal.exit;
            Coordinate[] line = {tiePoint, exit, cp};
            if (Router.deflectionDeg(tiePoint, exit, cp) > Router.MAX_TURN_DEG || exit.distance(tiePoint) < TreeBuilder.MIN_PIECE_M) {
                reject(reasons, "поворот у выхода");
                continue;
            }
            if (valid(line, 1, tie, dn, own, reasons)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Выходы из своего здания по порядку попыток: ближайшая точка внешнего контура, затем ближайшие точки других
     * сторон — луч через ближайшую может упираться в зону соседа или давать поворот круче 90° к врезке. От врезки
     * выходы не зависят: у точки два десятка врезок, и поиск выхода с проверкой здания шёл на каждую. Врезки точки
     * считаются в разных нитях, поэтому выходы строятся под замком точки.
     */
    private List<Portal> portals(Coordinate cp, Shared shared, ExistingOks building, double clearance) {
        synchronized (shared) {
            if (shared.portals == null) {
                List<Portal> portals = new ArrayList<>();
                Coordinate last = null;
                for (Coordinate anchor : anchors(building.getGeometry(), cp)) {
                    if (last != null && anchor.distance(last) < TreeBuilder.MIN_PIECE_M) {
                        continue;
                    }
                    last = anchor;
                    if (portals.size() >= PORTAL_TRIES) {
                        break;
                    }
                    Coordinate exit = exit(cp, anchor, building, clearance);
                    portals.add(new Portal(exit, exit != null && TreeBuilder.leavesOnce(building.getGeometry(), cp, exit)));
                }
                shared.portals = portals;
            }
            return shared.portals;
        }
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
            if (!oks.getId().equals(ownId) && oks.getGeometry().distance(point) < oksClearance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Отрезки линии не ближе отступа к полигонам ОКС (кроме своего у финального отрезка {@code finalPiece} и дальше),
     * запретным ограничениям и существующей сети, не пересекают объекты со специальным проходом и не идут вдоль
     * труб врезки.
     */
    private boolean valid(Coordinate[] line, int finalPiece, TieCandidate tie, int dn, String ownId,
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
                if (!rule.forbid() && geometry.getDimension() > 0 && geometry.intersects(piece)) {
                    // специальный проход прямым отрезком: у дороги и путей — под углом не меньше заданного
                    if (!crossingAllowed(piece, geometry, rule)) {
                        return reject(reasons, "угол пересечения " + restriction.getType());
                    }
                    continue;
                }
                if (geometry.distance(piece) < distance - DIST_EPS_M) {
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
                } else if (segment.getGeometry().distance(piece) < distance - DIST_EPS_M) {
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
