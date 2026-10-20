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
import ru.lct.heatnet.graph.ObstacleSet;
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

final class DirectTies {
    private static final Logger log = LoggerFactory.getLogger(DirectTies.class);
    private static final int NEAREST = 3;
    private static final double REACH_M = Double.parseDouble(System.getProperty("heatnet.city.reach", "300"));
    private static final int PORTAL_TRIES = 8;
    private static final int AHEAD = 32;
    private static final double PORTAL_EXTRA_M = 0.3;
    private static final double PORTAL_STEP_M = 0.5;
    private static final double PORTAL_MAX_M = 30;
    private static final double DIST_EPS_M = 0.001;
    private static final double ANGLE_EPS_DEG = 0.01;
    private static final double CLOSED_EPS_M = 0.01;
    private static final double NEAR_SIDE_M = 0.9;
    private static final double NEAR_PIPE_M = 3;
    private static final double DEPARTURE_EXTRA_M = 0.1;
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
    private final Envelope network = new Envelope();
    private final Envelope withinReach;
    private final Map<String, Integer> rejected = new java.util.TreeMap<>();

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

    private static final class Ledger {
        final List<Tree> trees = new ArrayList<>();
        final Map<String, Integer> used = new HashMap<>();
        final Quadtree created = new Quadtree();
        final Map<String, TieCandidate> createdByKey = new HashMap<>();
        final Quadtree placed = new Quadtree();
    }

    private static final class Shared {
        final int dn;
        final boolean far;
        final List<Option> options = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Map<String, Integer> rejected = new HashMap<>();
        final Map<TieCandidate, Tried> tried = new IdentityHashMap<>();
        Portal portal;

        Shared(int dn, boolean far) {
            this.dn = dn;
            this.far = far;
        }
    }

    private static final Shared FAR = new Shared(0, true);

    static {
        FAR.rejected.put("далеко", 1);
    }

    private static final class Portal {
        final Coordinate exit;
        final boolean nearest;

        Portal(Coordinate exit, boolean nearest) {
            this.exit = exit;
            this.nearest = nearest;
        }
    }

    private static final Portal NO_PORTAL = new Portal(null, false);

    private static final class Tried {
        final Option option;
        final Map<String, Integer> rejected;

        Tried(Option option, Map<String, Integer> rejected) {
            this.option = option;
            this.rejected = rejected;
        }
    }

    List<List<Tree>> connect(List<ConnectionPoint> connections, Map<String, FutureOks> oksById, int variants,
            List<ConnectionPoint> rest) {
        List<Ledger> ledgers = new ArrayList<>();
        for (int k = 0; k < variants; k++) {
            ledgers.add(new Ledger());
        }
        long started = System.nanoTime();
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
            for (TieCandidate tie : new TieCandidate[] {finder.pipeCandidate(segment, point, dn, false),
                    finder.endCandidate(segment, point, dn)}) {
                if (tie != null && shared.seen.add(tie.nodeKey())) {
                    add(shared.options, option(tie, connection, shared, nodeCost(tie, segment, dn), false, shared.rejected));
                }
            }
        }
        if (shared.options.isEmpty()) {
            for (Object item : nearPipes) {
                along(shared, (NetworkSegment) item, connection);
            }
        }
    }

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
                TieCandidate tie = finder.pipeCandidate(segment, at + sign * shift, dn, false);
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

    private double nodeCost(TieCandidate tie, NetworkSegment segment, int dn) {
        return tie.isChamber() ? rules.tieInCost() : rules.chamberCost(Math.max(dn, segment.getDiameter()));
    }

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

    private static boolean roomIn(TieCandidate tie, Map<String, Integer> used) {
        return used.getOrDefault(tie.nodeKey(), 0) < tie.getCapacity();
    }

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
            return departed(line, 0, tie, dn, own, crossPipes, reasons);
        }
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
            Coordinate[] straight = {tiePoint, cp};
            return nearSide(straight, building, shared, clearance, reasons)
                    ? departed(straight, 0, tie, dn, own, crossPipes, reasons) : null;
        }
        if (turn <= Router.MAX_TURN_DEG) {
            return departed(line, 1, tie, dn, own, crossPipes, reasons);
        }
        for (double length : TreeBuilder.EXIT_LINK ? TreeBuilder.EXIT_LINK_M : new double[0]) {
            Coordinate link = TreeBuilder.exitLink(new Coordinate[] {cp, exit, tiePoint}, length)[2];
            Coordinate[] linked = {tiePoint, link, exit, cp};
            double linkTurn = Router.deflectionDeg(tiePoint, link, exit);
            if (link.distance(tiePoint) >= TreeBuilder.MIN_PIECE_M
                    && linkTurn >= TreeBuilder.MIN_TURN_DEG && linkTurn <= Router.MAX_TURN_DEG) {
                Coordinate[] departed = departed(linked, 2, tie, dn, own, crossPipes, reasons);
                if (departed != null) {
                    return departed;
                }
            }
        }
        reject(reasons, "поворот у выхода");
        return null;
    }

    private boolean nearSide(Coordinate[] line, ExistingOks building, Shared shared, double clearance,
            Map<String, Integer> reasons) {
        Geometry polygon = building.getGeometry();
        Coordinate cp = line[line.length - 1];
        if (!TreeBuilder.leavesOnce(polygon, cp, line[0], clearance)) {
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
                    if (exit != null && TreeBuilder.leavesOnce(building.getGeometry(), cp, exit, clearance)
                            && !closed(cp, exit, shared.dn, building.getId())) {
                        shared.portal = new Portal(exit, tries == 1);
                        break;
                    }
                }
                List<Coordinate[]> rings = TreeBuilder.rings(building.getGeometry());
                for (Coordinate entry : shared.portal == NO_PORTAL ? TreeBuilder.entries(building.getGeometry(), cp,
                        Double.POSITIVE_INFINITY) : List.<Coordinate>of()) {
                    double r = cp.distance(entry);
                    if (r < 1e-6 || TreeBuilder.reach(rings, cp, entry, clearance) <= r + clearance) {
                        continue;
                    }
                    Coordinate exit = exit(cp, entry, building, clearance);
                    if (exit != null && TreeBuilder.leavesOnce(building.getGeometry(), cp, exit, clearance)
                            && !closed(cp, exit, shared.dn, building.getId())) {
                        shared.portal = new Portal(exit, false);
                        break;
                    }
                }
            }
            return shared.portal;
        }
    }

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

    private Coordinate[] departed(Coordinate[] line, int finalPiece, TieCandidate tie, int dn, String ownId,
            boolean crossPipes, Map<String, Integer> reasons) {
        if (valid(line, finalPiece, tie, dn, ownId, crossPipes, reasons)) {
            return line;
        }
        if (finalPiece == 0 && ownId != null || leaves(tie, line[0], line[1], dn)) {
            return null;
        }
        Coordinate[] best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (Coordinate d : departures(tie, dn)) {
            Coordinate[] candidate = new Coordinate[line.length + 1];
            candidate[0] = line[0];
            candidate[1] = d;
            System.arraycopy(line, 1, candidate, 2, line.length - 1);
            double turn = Router.deflectionDeg(line[0], d, line[1]);
            double length = line[0].distance(d) + d.distance(line[1]);
            if (length >= bestLength || d.distance(line[1]) < TreeBuilder.MIN_PIECE_M
                    || turn < TreeBuilder.MIN_TURN_DEG || turn > Router.MAX_TURN_DEG
                    || line.length > 2 && Router.deflectionDeg(d, line[1], line[2]) > Router.MAX_TURN_DEG
                    || !valid(candidate, finalPiece == 0 ? 0 : finalPiece + 1, tie, dn, ownId, crossPipes, new HashMap<>())) {
                continue;
            }
            best = candidate;
            bestLength = length;
        }
        return best;
    }

    private List<Coordinate> departures(TieCandidate tie, int dn) {
        Coordinate c = tie.getPoint().getCoordinate();
        Envelope envelope = new Envelope(c);
        envelope.expandBy(TieInFinder.TOUCH_M);
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        List<LineSegment> sides = new ArrayList<>();
        double reach = 0;
        for (NetworkSegment segment : index.segments(envelope)) {
            if (!tie.getIgnored().contains(segment.getId())) {
                continue;
            }
            Coordinate[] coords = segment.getGeometry().getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                LineSegment side = new LineSegment(coords[i], coords[i + 1]);
                if (side.distance(c) <= TieInFinder.TOUCH_M) {
                    sides.add(side);
                    reach = Math.max(reach, network.clearanceM(dn) + halfWidth
                            + rules.diameter(segment.getDiameter()).getWidthM() / 2 + ObstacleSet.CROSS_CIRCLE_M + DEPARTURE_EXTRA_M);
                }
            }
        }
        return ObstacleSet.departures(c, sides, reach);
    }

    private boolean leaves(TieCandidate tie, Coordinate tiePoint, Coordinate next, int dn) {
        LineString piece = factory.createLineString(new Coordinate[] {tiePoint, next});
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        for (NetworkSegment segment : index.segments(piece.getEnvelopeInternal())) {
            if (!tie.getIgnored().contains(segment.getId())) {
                continue;
            }
            double distance = network.clearanceM(dn) + halfWidth + rules.diameter(segment.getDiameter()).getWidthM() / 2;
            if (!leavesPipe(segment.getGeometry(), tiePoint, next, distance)) {
                return false;
            }
        }
        return true;
    }

    private static boolean leavesPipe(LineString pipe, Coordinate tiePoint, Coordinate next, double distance) {
        double skip = (distance + ObstacleSet.CROSS_CIRCLE_M) / tiePoint.distance(next);
        return skip >= 1 || pipeDistance(pipe, new LineSegment(tiePoint, next).pointAlong(skip), next) >= distance - DIST_EPS_M;
    }

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
                double gap = geometry.distance(piece);
                if (gap > 0 && gap >= distance - DIST_EPS_M) {
                    continue;
                }
                if (!rule.forbid() && geometry.getDimension() > 0 && geometry.intersects(piece)) {
                    if (!crossingAllowed(piece, geometry, rule)) {
                        return reject(reasons, "угол пересечения " + restriction.getType());
                    }
                    if (!holdsBeyond(piece, geometry, rule, distance)) {
                        return reject(reasons, "отступ за спецпроходом " + restriction.getType());
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
                    if (piece.getLength() <= TieInFinder.TOUCH_M) {
                        return reject(reasons, "короче 0,5 м");
                    }
                    if (!leavesPipe(segment.getGeometry(), line[0], line[1], distance)) {
                        return reject(reasons, "вдоль трубы врезки");
                    }
                } else if (pipeDistance(segment.getGeometry(), line[i], line[i + 1]) < distance - DIST_EPS_M
                        && !(crossPipes && segment.getGeometry().intersects(piece)
                                && crossingAllowed(piece, segment.getGeometry(), network)
                                && holdsBeyond(piece, segment.getGeometry(), network, distance))) {
                    return reject(reasons, "чужая труба");
                }
            }
        }
        return true;
    }

    private static boolean holdsBeyond(LineString piece, Geometry geometry, RestrictionRule rule, double distance) {
        Coordinate a = piece.getCoordinateN(0);
        Coordinate b = piece.getCoordinateN(1);
        double length = a.distance(b);
        double reach = geometry.getDimension() == 2 ? rule.getMarginM()
                : Math.max(rule.getMarginM(), distance + ObstacleSet.CROSS_CIRCLE_M);
        Geometry common = piece.intersection(geometry);
        List<double[]> free = new ArrayList<>();
        for (int g = 0; g < common.getNumGeometries(); g++) {
            Coordinate[] coords = common.getGeometryN(g).getCoordinates();
            double from = a.distance(coords[0]);
            double to = a.distance(coords[coords.length - 1]);
            free.add(new double[] {Math.min(from, to) - reach, Math.max(from, to) + reach});
        }
        free.sort(Comparator.comparingDouble(interval -> interval[0]));
        double from = 0;
        for (double[] part : free) {
            if (part[0] > from && closer(a, b, from / length, part[0] / length, geometry, distance)) {
                return false;
            }
            from = Math.max(from, part[1]);
        }
        return from >= length || !closer(a, b, from / length, 1, geometry, distance);
    }

    private static boolean closer(Coordinate a, Coordinate b, double from, double to, Geometry geometry, double distance) {
        LineSegment segment = new LineSegment(a, b);
        LineString part = new GeometryFactory().createLineString(
                new Coordinate[] {segment.pointAlong(from), segment.pointAlong(to)});
        return geometry.distance(part) < distance - DIST_EPS_M;
    }

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
