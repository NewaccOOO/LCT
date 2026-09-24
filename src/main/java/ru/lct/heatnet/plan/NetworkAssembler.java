package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.calc.CostCalculator;
import ru.lct.heatnet.calc.DiameterPlanner;
import ru.lct.heatnet.calc.FlowCalculator;
import ru.lct.heatnet.calc.TreeEdge;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Rules;

/**
 * Сборка варианта из деревьев по техническому приложению от 18.09.2026: расходы, специальные части, диаметры,
 * участки с узлами, камеры врезки, стоимость и сводка. Врезка в трубу — новая камера в точке врезки, врезка в
 * существующую камеру — участок заканчивается в ней и стоит 5 млн (п. 2.4, 3.2). Специальные части считаются по
 * всей трассе варианта: зона линии продолжается через узлы во все ветви, зона дороги — вся связная часть трассы в
 * полосе margin_m, пересекающая полигон.
 */
final class NetworkAssembler {
    static final String BASE = "base";
    static final String SPECIAL = "special";
    /** Границы ближе этого сливаются: проверка считает узлы в 0,05 м одним узлом. */
    private static final double MERGE_M = 0.08;
    /** Зона перехода расширяется на 0,05 м, участок считается в зоне при перекрытии больше 0,1 м. */
    private static final double ZONE_TOL_M = 0.05;
    private static final double ZONE_OVERLAP_M = 0.1;
    /**
     * Специальная часть шире зоны на 2 см с каждой стороны: обычный участок между двумя зонами одного объекта иначе
     * перекрыл бы расширенные зоны ровно на 0,1 м, на границе порога.
     */
    private static final double ZONE_GROW_M = 0.02;
    /** Разрез ближе этого к вершине ребра переносится в вершину; сдвиг вместе с ZONE_GROW_M не выходит за ZONE_TOL_M. */
    private static final double VERTEX_SNAP_M = ZONE_TOL_M - ZONE_GROW_M;
    private static final double DIST_EPS_M = 0.001;
    private static final double MIN_TURN_DEG = 3;
    /** Узлы ближе 0,05 м считаются одним узлом, касание участков видно ближе 0,001 м за вырезом 0,15 м у узла. */
    private static final double NODE_APART_M = 0.06;
    private static final double TOUCH_APART_M = 0.002;
    private static final double SHARED_CLIP_M = 0.14;
    /** Участок в зоне считается при перекрытии больше 0,1 м: специальный кусок короче не распознать. */
    private static final double MIN_SPECIAL_M = 0.12;
    private static final double MIN_SPLIT_DEG = 30.5;

    private final InputData input;
    private final Rules rules;
    private final SpecialObjects specials;
    private final CostCalculator costs;
    private final DiameterPlanner planner;
    private final Map<String, Double> flowByOks = new HashMap<>();
    /** ID источника входа; остальные ID берутся из списков входа, см. {@link #inputIds}. */
    private final String sourceId;
    /** Проверки ID входа по префиксу и целиком: выходные ID с ID входа не совпадают. */
    private final Map<String, Boolean> startsByPrefix = new HashMap<>();
    private final Map<String, Boolean> knownIds = new HashMap<>();
    private final GeometryFactory factory = new GeometryFactory();

    /** Счётчики выходных ID; общие на несколько сборок, когда один вариант собирается по частям (город). */
    static final class Counters {
        final AtomicInteger segments = new AtomicInteger();
        final AtomicInteger chambers = new AtomicInteger();
        final AtomicInteger nodes = new AtomicInteger();
    }

    /** Часть ребра в специальной зоне одного объекта. */
    private static final class Zone {
        final int edge;
        final double from;
        final double to;
        final SpecialObjects.Special special;

        Zone(int edge, double from, double to, SpecialObjects.Special special) {
            this.edge = edge;
            this.from = from;
            this.to = to;
            this.special = special;
        }

        /** Перекрытие с частью ребра так, как считает проверка: зона расширена на ZONE_TOL_M. */
        double overlap(double a, double b) {
            return Math.min(b, to + ZONE_TOL_M) - Math.max(a, from - ZONE_TOL_M);
        }
    }

    private static final class Edge {
        final String id;
        final Tree tree;
        final Tree.Edge source;
        final double length;

        Edge(String id, Tree tree, Tree.Edge source) {
            this.id = id;
            this.tree = tree;
            this.source = source;
            this.length = source.line.getLength();
        }

        String from() {
            return source.from.key;
        }

        String to() {
            return source.to.key;
        }
    }

    NetworkAssembler(InputData input, Rules rules, SpecialObjects specials) {
        this.input = input;
        this.rules = rules;
        this.specials = specials;
        this.costs = new CostCalculator(rules);
        this.planner = new DiameterPlanner(rules);
        this.sourceId = input.getSource().getId();
        for (FutureOks oks : input.getFutureOks()) {
            flowByOks.put(oks.getId(), oks.getFlowTph());
        }
    }

    private boolean startsInputId(String prefix) {
        // вход города — миллионы ID, а префиксов за расчёт единицы: каждый проверяется один раз
        synchronized (startsByPrefix) {
            return startsByPrefix.computeIfAbsent(prefix, key -> inputIds().anyMatch(id -> id.startsWith(key)));
        }
    }

    private boolean isInputId(String id) {
        synchronized (knownIds) {
            return knownIds.computeIfAbsent(id, key -> inputIds().anyMatch(key::equals));
        }
    }

    /** ID входа по спискам: множество из шести миллионов ID города строилось ради считанных проверок. */
    private Stream<String> inputIds() {
        return Stream.of(Stream.of(sourceId), input.getSegments().stream().map(NetworkSegment::getId),
                input.getChambers().stream().map(Chamber::getId),
                input.getConnectionPoints().stream().map(ConnectionPoint::getId),
                input.getFutureOks().stream().map(FutureOks::getId),
                input.getExistingOks().stream().map(ExistingOks::getId),
                input.getRestrictions().stream().map(Restriction::getId)).flatMap(ids -> ids);
    }

    /**
     * Вариант из деревьев; деревья с общей точкой врезки собираются в один узел. Бросает
     * {@link IllegalStateException}, если диаметры не укладываются в предельную длину или собранная сеть нарушает
     * отступ от объектов специального прохода, предел поворота или форму участков: такое дерево не выдаётся.
     */
    Variant assemble(String variantId, int rank, List<Tree> trees, List<FutureOks> unconnected) {
        return assemble(variantId, rank, trees, unconnected, new Counters());
    }

    Variant assemble(String variantId, int rank, List<Tree> trees, List<FutureOks> unconnected, Counters counters) {
        return new Build(variantId, rank, trees, unconnected, counters).run();
    }

    /** Сводка по частям варианта, собранным отдельно с общими счётчиками. */
    VariantSummary summary(String variantId, int rank, List<NewSegment> segments, List<NewChamber> chambers,
            int existingTieIns, List<FutureOks> unconnected) {
        String summaryId = isInputId("summary_" + variantId) ? prefix(variantId) + "summary" : "summary_" + variantId;
        VariantSummary draft = costs.summary(summaryId, variantId, segments, chambers, existingTieIns, unconnected);
        return new VariantSummary(draft.getId(), variantId, rank, draft.getConstructionCost(),
                draft.getChamberConstructionCost(), draft.getExistingChamberTieInCount(),
                draft.getExistingChamberTieInCost(), draft.getUnconnectedPenalty(), draft.getCalculatedCost(),
                draft.getNewNetworkLength(), draft.getScore(), draft.getUnconnectedOksIds());
    }

    private String prefix(String variantId) {
        // вход может содержать ID вида v1_seg_1: префикс удлиняется, пока с него не начинается ни один ID входа
        String free = "v" + variantId + "_";
        while (startsInputId(free)) {
            free = "v" + free;
        }
        return free;
    }

    /** Состояние одной сборки. */
    private final class Build {
        final String variantId;
        final int rank;
        final String prefix;
        final List<FutureOks> unconnected;
        final Counters counters;
        final Map<String, List<Tree>> units = new LinkedHashMap<>();
        final List<Edge> edges = new ArrayList<>();
        final Map<String, List<Integer>> edgesByUnit = new HashMap<>();
        final STRtree edgeIndex = new STRtree();
        final Map<String, List<Integer>> incident = new HashMap<>();
        final Map<String, Double> flowByEdge = new HashMap<>();
        final Map<String, Integer> dnByEdge = new HashMap<>();
        final Map<String, String> nodeIds = new HashMap<>();
        /** Узлы врезки: новые камеры на трубе и существующие камеры. */
        final Set<String> tieNodeIds = new HashSet<>();
        final Map<String, Integer> maxDnByNode = new HashMap<>();
        final Map<String, Set<SpecialObjects.Special>> exemptByNode = new HashMap<>();
        final Map<String, NewSegment> incomingByNode = new HashMap<>();
        final List<NewChamber> chambers = new ArrayList<>();
        final List<TechnicalNode> nodes = new ArrayList<>();
        final List<NewSegment> segments = new ArrayList<>();
        List<List<Zone>> zonesByEdge;
        List<List<double[]>> specialByEdge;
        int existingTieIns;

        Build(String variantId, int rank, List<Tree> trees, List<FutureOks> unconnected, Counters counters) {
            this.variantId = variantId;
            this.rank = rank;
            this.prefix = prefix(variantId);
            this.unconnected = unconnected;
            this.counters = counters;
            for (Tree tree : trees) {
                if (!tree.edges.isEmpty()) {
                    units.computeIfAbsent(tree.root.key, key -> new ArrayList<>()).add(tree);
                }
            }
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                for (Tree tree : unit.getValue()) {
                    for (Tree.Edge source : tree.edges) {
                        Edge edge = new Edge("e" + edges.size(), tree, source);
                        incident.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edges.size());
                        incident.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edges.size());
                        edgesByUnit.computeIfAbsent(unit.getKey(), key -> new ArrayList<>()).add(edges.size());
                        edgeIndex.insert(source.line.getEnvelopeInternal(), edges.size());
                        edges.add(edge);
                    }
                }
            }
            edgeIndex.build();
        }

        Variant run() {
            zonesByEdge = zones();
            specialByEdge = mergedZones();
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                plan(unit.getKey());
            }
            nameNodes();
            for (int i = 0; i < edges.size(); i++) {
                cut(i);
            }
            checkClearance();
            checkApart();
            checkShape();
            for (Edge edge : edges) {
                Tree.Node node = edge.source.to;
                if (node.kind == Tree.Kind.JUNCTION) {
                    String id = nodeIds.get(node.key);
                    int dn = maxDnByNode.get(id);
                    chambers.add(new NewChamber(id, variantId, factory.createPoint(node.point), dn, costs.chamberCost(dn)));
                }
            }
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                TieCandidate tie = unit.getValue().get(0).tie;
                String tieId = nodeIds.get(unit.getKey());
                if (tie.isChamber()) {
                    existingTieIns += edgesByUnit.get(unit.getKey()).stream()
                            .filter(i -> edges.get(i).from().equals(unit.getKey())).count();
                } else {
                    // ДУ камеры — наибольший из примыкающих участков, включая обе части разрезанной ею трубы (п. 2.1, 3.2)
                    int dn = Math.max(maxDnByNode.get(tieId), tie.getExistingDiameter());
                    chambers.add(new NewChamber(tieId, variantId, tie.getPoint(), dn, costs.chamberCost(dn)));
                }
            }
            VariantSummary summary = summary(variantId, rank, segments, chambers, existingTieIns, unconnected);
            return new Variant(variantId, segments, chambers, nodes, summary);
        }

        /** Расходы и диаметры рёбер одного узла врезки. */
        void plan(String root) {
            List<TreeEdge> treeEdges = new ArrayList<>();
            Map<String, Double> oksFlowByNode = new HashMap<>();
            for (int i : edgesByUnit.get(root)) {
                Edge edge = edges.get(i);
                treeEdges.add(new TreeEdge(edge.id, edge.from(), edge.to(), edge.length));
                if (edge.source.to.kind == Tree.Kind.CONNECTION) {
                    oksFlowByNode.put(edge.to(), flowByOks.getOrDefault(edge.source.to.connection.getOksId(), 0.0));
                }
            }
            Map<String, Double> flows = FlowCalculator.flows(treeEdges, root, oksFlowByNode);
            flowByEdge.putAll(flows);
            dnByEdge.putAll(planner.plan(treeEdges, root, flows));
        }

        void nameNodes() {
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                TieCandidate tie = unit.getValue().get(0).tie;
                String id = tie.isChamber() ? tie.getExistingObjectId() : prefix + "ch_" + counters.chambers.incrementAndGet();
                nodeIds.put(unit.getKey(), id);
                tieNodeIds.add(id);
            }
            for (Edge edge : edges) {
                for (Tree.Node node : List.of(edge.source.from, edge.source.to)) {
                    if (node.kind == Tree.Kind.JUNCTION && !nodeIds.containsKey(node.key)) {
                        nodeIds.put(node.key, prefix + "ch_" + counters.chambers.incrementAndGet());
                    } else if (node.kind == Tree.Kind.CONNECTION) {
                        nodeIds.put(node.key, node.connection.getId());
                    }
                }
            }
        }

        /** Режет ребро на участки в границах специальных частей. */
        void cut(int index) {
            Edge edge = edges.get(index);
            List<double[]> special = specialByEdge.get(index);
            int dn = dnByEdge.get(edge.id);
            TreeMap<Double, String> cuts = new TreeMap<>();
            cuts.put(0.0, nodeIds.get(edge.from()));
            cuts.put(edge.length, nodeIds.get(edge.to()));
            List<Double> candidates = new ArrayList<>();
            for (double[] interval : special) {
                candidates.add(interval[0]);
                candidates.add(interval[1]);
            }
            LengthIndexedLine indexed = new LengthIndexedLine(edge.source.line);
            Map<Double, Coordinate> points = new HashMap<>();
            points.put(0.0, edge.source.from.point);
            points.put(edge.length, edge.source.to.point);
            List<Double> vertices = TreeBuilder.vertexPositions(edge.source.line);
            for (double candidate : candidates) {
                double at = candidate;
                for (double vertex : vertices) {
                    if (Math.abs(vertex - candidate) <= VERTEX_SNAP_M) {
                        at = vertex;
                    }
                }
                Double floor = cuts.floorKey(at);
                Double ceiling = cuts.ceilingKey(at);
                if ((floor == null || at - floor > MERGE_M) && (ceiling == null || ceiling - at > MERGE_M)) {
                    Coordinate point = indexed.extractPoint(at);
                    String id = prefix + "node_" + counters.nodes.incrementAndGet();
                    nodes.add(new TechnicalNode(id, variantId, factory.createPoint(point)));
                    cuts.put(at, id);
                    points.put(at, point);
                }
            }
            Double from = null;
            for (double to : cuts.keySet()) {
                if (from != null) {
                    double mid = (from + to) / 2;
                    boolean isSpecial = inside(special, mid);
                    if (!isSpecial) {
                        for (Zone zone : zonesByEdge.get(index)) {
                            // хвост зоны за разрезом, слитым с соседним ближе MERGE_M: обычный участок в зоне перехода
                            if (zone.overlap(from, to) > ZONE_OVERLAP_M) {
                                throw new IllegalStateException("Обычный участок ребра " + edge.id + " лежит в зоне "
                                        + "спецперехода больше чем на " + ZONE_OVERLAP_M + " м");
                            }
                        }
                    }
                    Coordinate[] coords = indexed.extractLine(from, to).getCoordinates();
                    coords[0] = points.get(from);
                    coords[coords.length - 1] = points.get(to);
                    if (isSpecial && coords.length > 2) {
                        throw new IllegalStateException("Специальный участок ребра " + edge.id + " не прямой");
                    }
                    LineString line = factory.createLineString(coords);
                    double length = CostCalculator.round2(line.getLength());
                    String startId = cuts.get(from);
                    String endId = cuts.get(to);
                    double k = 1;
                    if (isSpecial) {
                        List<SpecialObjects.Special> crossed = crossed(index, from, to);
                        exemptByNode.computeIfAbsent(startId, id -> new HashSet<>()).addAll(crossed);
                        exemptByNode.computeIfAbsent(endId, id -> new HashSet<>()).addAll(crossed);
                        k = crossed.stream().mapToDouble(s -> s.rule.getKSpecial()).max().orElse(1);
                    }
                    NewSegment segment = new NewSegment(prefix + "seg_" + counters.segments.incrementAndGet(), variantId,
                            line, startId, endId, flowByEdge.get(edge.id), dn, length, isSpecial ? SPECIAL : BASE, null,
                            null, costs.segmentCost(length, dn, k));
                    segments.add(segment);
                    incomingByNode.put(endId, segment);
                    maxDnByNode.merge(startId, dn, Math::max);
                    maxDnByNode.merge(endId, dn, Math::max);
                }
                from = to;
            }
        }

        /** Объекты, чьи зоны засчитаются специальному участку: перекрытие больше ZONE_OVERLAP_M. */
        List<SpecialObjects.Special> crossed(int edge, double from, double to) {
            List<SpecialObjects.Special> result = new ArrayList<>();
            for (Zone zone : zonesByEdge.get(edge)) {
                if (zone.overlap(from, to) > ZONE_OVERLAP_M && !result.contains(zone.special)) {
                    result.add(zone.special);
                }
            }
            return result;
        }

        /**
         * Отступ обычного участка от объектов со специальным проходом: объекты, через которые проходит смежный
         * специальный участок, и сеть у врезки, с которой участок начинается, не проверяются. Маршрут проверен по
         * рёбрам графа целиком, а здесь проверяется каждый участок.
         */
        void checkClearance() {
            for (NewSegment segment : segments) {
                if (SPECIAL.equals(segment.getLayingMethod())) {
                    continue;
                }
                Set<SpecialObjects.Special> exempt = new HashSet<>(exemptByNode.getOrDefault(segment.getStartNodeId(), Set.of()));
                exempt.addAll(exemptByNode.getOrDefault(segment.getEndNodeId(), Set.of()));
                boolean fromTie = tieNodeIds.contains(segment.getStartNodeId());
                for (SpecialObjects.Special special : specials.around(segment.getGeometry(), segment.getDiameter())) {
                    if (exempt.contains(special)
                            || fromTie && special.network && special.geometry.isWithinDistance(
                                    factory.createPoint(segment.getGeometry().getCoordinateN(0)), TieInFinder.TOUCH_M)) {
                        continue;
                    }
                    double need = specials.clearance(special, segment.getDiameter());
                    if (special.closer(segment.getGeometry(), need - DIST_EPS_M)) {
                        throw new IllegalStateException("Участок " + segment.getId() + " ближе отступа к объекту "
                                + "со специальным проходом вне специального участка");
                    }
                }
            }
        }

        /**
         * Узлы разных участков не ближе NODE_APART_M, а участки не касаются вне общих узлов (узлы в 0,05 м
         * склеиваются, касание — 0,001 м за вырезом 0,15 м у общего узла), с запасом на округление. Иначе ветка,
         * ушедшая от развилки почти назад вдоль ствола, давала склеенные узлы, цикл и касание. Несколько участков
         * в одну существующую камеру начинаются в одной точке законно.
         */
        void checkApart() {
            Map<String, Coordinate> points = new LinkedHashMap<>();
            for (NewSegment segment : segments) {
                Coordinate[] coords = segment.getGeometry().getCoordinates();
                points.putIfAbsent(segment.getStartNodeId(), coords[0]);
                points.putIfAbsent(segment.getEndNodeId(), coords[coords.length - 1]);
            }
            List<String> ids = new ArrayList<>(points.keySet());
            Map<String, String> group = new HashMap<>();
            ids.forEach(id -> group.put(id, id));
            STRtree index = new STRtree();
            for (String id : ids) {
                index.insert(new Envelope(points.get(id)), id);
            }
            for (String id : ids) {
                Envelope near = new Envelope(points.get(id));
                near.expandBy(NODE_APART_M);
                for (Object item : index.query(near)) {
                    String other = (String) item;
                    if (other.equals(id) || points.get(id).distance(points.get(other)) > NODE_APART_M) {
                        continue;
                    }
                    throw new IllegalStateException("Узлы " + id + " и " + other + " ближе "
                            + NODE_APART_M + " м: проверка считает их одним узлом");
                }
            }
            for (NewSegment segment : segments) {
                Coordinate[] coords = segment.getGeometry().getCoordinates();
                if (SPECIAL.equals(segment.getLayingMethod()) && segment.getGeometry().getLength() <= MIN_SPECIAL_M) {
                    throw new IllegalStateException("Специальный участок " + segment.getId() + " короче " + MIN_SPECIAL_M
                            + " м: проверка не видит у него перехода");
                }
                for (int i = 0; i + 1 < coords.length; i++) {
                    for (int j = i + 2; j + 1 < coords.length; j++) {
                        if (new LineSegment(coords[i], coords[i + 1]).distance(new LineSegment(coords[j], coords[j + 1])) <= TOUCH_APART_M) {
                            throw new IllegalStateException("Участок " + segment.getId() + " касается сам себя");
                        }
                    }
                }
            }
            // обычная и специальная ветки одного узла под острым углом: зона специальной, раздутая проверкой
            // на 0,05 м, накрывает обычную на 0,05 / sin угла, больше 0,1 м при угле меньше 30°
            Map<String, List<NewSegment>> byNode = new HashMap<>();
            for (NewSegment segment : segments) {
                byNode.computeIfAbsent(group.get(segment.getStartNodeId()), key -> new ArrayList<>()).add(segment);
                byNode.computeIfAbsent(group.get(segment.getEndNodeId()), key -> new ArrayList<>()).add(segment);
            }
            for (Map.Entry<String, List<NewSegment>> node : byNode.entrySet()) {
                Coordinate at = points.get(node.getKey());
                for (NewSegment base : node.getValue()) {
                    if (SPECIAL.equals(base.getLayingMethod())) {
                        continue;
                    }
                    for (NewSegment special : node.getValue()) {
                        if (SPECIAL.equals(special.getLayingMethod())
                                && Angle.toDegrees(Angle.angleBetween(awayFrom(base, at), at, awayFrom(special, at))) < MIN_SPLIT_DEG) {
                            throw new IllegalStateException("Обычный " + base.getId() + " и специальный " + special.getId()
                                    + " расходятся из узла под углом меньше " + MIN_SPLIT_DEG + "°");
                        }
                    }
                }
            }
            STRtree lines = new STRtree();
            for (NewSegment segment : segments) {
                lines.insert(segment.getGeometry().getEnvelopeInternal(), segment);
            }
            for (NewSegment a : segments) {
                Envelope around = new Envelope(a.getGeometry().getEnvelopeInternal());
                around.expandBy(NODE_APART_M);
                for (Object item : lines.query(around)) {
                    NewSegment b = (NewSegment) item;
                    if (a.getId().compareTo(b.getId()) >= 0 || a.getGeometry().distance(b.getGeometry()) > NODE_APART_M) {
                        continue;
                    }
                    Set<String> shared = new HashSet<>(List.of(group.get(a.getStartNodeId()), group.get(a.getEndNodeId())));
                    shared.retainAll(List.of(group.get(b.getStartNodeId()), group.get(b.getEndNodeId())));
                    // зона спецперехода у проверки раздута на 0,05 м: обычный участок рядом со специальным другой
                    // ветки оказывается в ней
                    if (shared.isEmpty() && !a.getLayingMethod().equals(b.getLayingMethod())) {
                        throw new IllegalStateException("Участки " + a.getId() + " и " + b.getId()
                                + " ближе " + NODE_APART_M + " м, один из них специальный");
                    }
                    if (a.getGeometry().distance(b.getGeometry()) > TOUCH_APART_M) {
                        continue;
                    }
                    LineString restA = rest(a, shared, group);
                    LineString restB = rest(b, shared, group);
                    if (restA != null && restB != null && restA.distance(restB) <= TOUCH_APART_M) {
                        throw new IllegalStateException("Участки " + a.getId() + " и " + b.getId()
                                + " касаются вне общего узла");
                    }
                }
            }
        }

        /** Участок без SHARED_CLIP_M у концов в общих узлах; null — от него ничего не осталось. */
        LineString rest(NewSegment segment, Set<String> shared, Map<String, String> group) {
            double length = segment.getGeometry().getLength();
            double from = shared.contains(group.get(segment.getStartNodeId())) ? SHARED_CLIP_M : 0;
            double to = shared.contains(group.get(segment.getEndNodeId())) ? length - SHARED_CLIP_M : length;
            return to - from <= 0 ? null : (LineString) new LengthIndexedLine(segment.getGeometry()).extractLine(from, to);
        }

        /** Вторая точка участка от конца в узле at: направление, в котором участок уходит из узла. */
        Coordinate awayFrom(NewSegment segment, Coordinate at) {
            Coordinate[] coords = segment.getGeometry().getCoordinates();
            return coords[0].distance(at) <= coords[coords.length - 1].distance(at) ? coords[1] : coords[coords.length - 2];
        }

        /**
         * Форма участков: внутри нет вершин с отклонением меньше 3° и подотрезков короче метра, кроме первого и
         * последнего подотрезка у узла, общего со специальным участком; поворот в вершине и в техническом узле не
         * круче {@link Router#MAX_TURN_DEG} (приложение 18.09, п. 2.1).
         */
        void checkShape() {
            Set<String> specialNodes = new HashSet<>();
            for (NewSegment segment : segments) {
                if (SPECIAL.equals(segment.getLayingMethod())) {
                    specialNodes.add(segment.getStartNodeId());
                    specialNodes.add(segment.getEndNodeId());
                }
            }
            Set<String> technical = new HashSet<>();
            for (TechnicalNode node : nodes) {
                technical.add(node.getId());
            }
            for (NewSegment segment : segments) {
                Coordinate[] coords = segment.getGeometry().getCoordinates();
                int last = coords.length - 2;
                for (int i = 0; i <= last; i++) {
                    boolean nearSpecial = i == 0 && specialNodes.contains(segment.getStartNodeId())
                            || i == last && specialNodes.contains(segment.getEndNodeId());
                    if (coords[i].distance(coords[i + 1]) < TreeBuilder.MIN_PIECE_M && !nearSpecial) {
                        throw new IllegalStateException("Участок " + segment.getId() + ": подотрезок короче метра");
                    }
                    if (i > 0) {
                        double deflection = TreeBuilder.deflectionDeg(coords[i - 1], coords[i], coords[i + 1]);
                        if (deflection < MIN_TURN_DEG) {
                            throw new IllegalStateException("Участок " + segment.getId() + ": излом меньше 3°");
                        }
                        if (deflection > Router.MAX_TURN_DEG) {
                            throw new IllegalStateException("Участок " + segment.getId() + ": поворот круче 90°");
                        }
                    }
                }
                NewSegment before = incomingByNode.get(segment.getStartNodeId());
                if (before != null && technical.contains(segment.getStartNodeId())) {
                    Coordinate[] prev = before.getGeometry().getCoordinates();
                    if (TreeBuilder.deflectionDeg(prev[prev.length - 2], coords[0], coords[1]) > Router.MAX_TURN_DEG) {
                        throw new IllegalStateException("Участок " + segment.getId() + ": поворот в техническом узле круче 90°");
                    }
                }
            }
        }

        /** Специальные зоны всех объектов по трассе варианта, по рёбрам. */
        List<List<Zone>> zones() {
            List<List<Zone>> result = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                result.add(new ArrayList<>());
            }
            if (edges.isEmpty()) {
                return result;
            }
            Envelope trace = new Envelope();
            for (Edge edge : edges) {
                trace.expandToInclude(edge.source.line.getEnvelopeInternal());
            }
            List<Zone> zones = new ArrayList<>();
            for (SpecialObjects.Special special : specials.within(trace)) {
                Envelope envelope = (special.polygon ? special.buffered : special.geometry).getEnvelopeInternal();
                if (!envelope.intersects(trace)) {
                    continue;
                }
                if (special.polygon) {
                    polygonZone(special, envelope, zones);
                } else {
                    lineZone(special, envelope, zones);
                }
            }
            for (Zone zone : zones) {
                result.get(zone.edge).add(zone);
            }
            return result;
        }

        /** Рёбра, чья рамка пересекает envelope, по возрастанию индекса. */
        List<Integer> edgesNear(Envelope envelope) {
            TreeSet<Integer> result = new TreeSet<>();
            for (Object item : edgeIndex.query(envelope)) {
                result.add((Integer) item);
            }
            return new ArrayList<>(result);
        }

        /** Связные части трассы в буфере полигона, которые пересекают сам полигон. */
        void polygonZone(SpecialObjects.Special special, Envelope envelope, List<Zone> result) {
            List<Zone> pieces = new ArrayList<>();
            List<Geometry> parts = new ArrayList<>();
            for (int i : edgesNear(envelope)) {
                LineString line = edges.get(i).source.line;
                Geometry inside = line.intersection(special.buffered);
                LengthIndexedLine indexed = new LengthIndexedLine(line);
                for (int g = 0; g < inside.getNumGeometries(); g++) {
                    Geometry part = inside.getGeometryN(g);
                    if (!(part instanceof LineString) || part.getLength() == 0) {
                        continue;
                    }
                    Coordinate[] coords = part.getCoordinates();
                    double a = indexed.project(coords[0]);
                    double b = indexed.project(coords[coords.length - 1]);
                    pieces.add(new Zone(i, Math.min(a, b), Math.max(a, b), special));
                    parts.add(part);
                }
            }
            int[] cluster = new int[parts.size()];
            for (int i = 0; i < cluster.length; i++) {
                cluster[i] = i;
            }
            for (int i = 0; i < parts.size(); i++) {
                for (int j = i + 1; j < parts.size(); j++) {
                    if (parts.get(i).intersects(parts.get(j))) {
                        cluster[find(cluster, i)] = find(cluster, j);
                    }
                }
            }
            boolean[] crossing = new boolean[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                if (special.prepared.intersects(parts.get(i))) {
                    crossing[find(cluster, i)] = true;
                }
            }
            for (int i = 0; i < parts.size(); i++) {
                if (crossing[find(cluster, i)]) {
                    result.add(pieces.get(i));
                }
            }
        }

        /** По margin_m в обе стороны от каждого пересечения; пересечение сети в точке врезки не считается. */
        void lineZone(SpecialObjects.Special special, Envelope envelope, List<Zone> result) {
            for (int i : edgesNear(envelope)) {
                LineString line = edges.get(i).source.line;
                if (!special.crossedBy(line)) {
                    continue;
                }
                Geometry hit = line.intersection(special.geometry);
                LengthIndexedLine indexed = new LengthIndexedLine(line);
                for (Coordinate c : hit.getCoordinates()) {
                    if (special.network && atTie(c, special.geometry)) {
                        continue;
                    }
                    spread(result, i, indexed.project(c), special);
                }
            }
        }

        boolean atTie(Coordinate c, Geometry line) {
            for (List<Tree> unit : units.values()) {
                Coordinate tie = unit.get(0).root.point;
                if (c.distance(tie) <= TieInFinder.TOUCH_M
                        && line.isWithinDistance(factory.createPoint(tie), TieInFinder.TOUCH_M)) {
                    return true;
                }
            }
            return false;
        }

        void spread(List<Zone> result, int index, double position, SpecialObjects.Special special) {
            Edge edge = edges.get(index);
            double margin = special.rule.getMarginM();
            result.add(new Zone(index, Math.max(0, position - margin), Math.min(edge.length, position + margin), special));
            if (position - margin < 0) {
                walk(result, edge.from(), index, margin - position, special);
            }
            if (position + margin > edge.length) {
                walk(result, edge.to(), index, position + margin - edge.length, special);
            }
        }

        /** Зона линии продолжается через узел во все ветви. */
        void walk(List<Zone> result, String node, int cameFrom, double remaining, SpecialObjects.Special special) {
            for (int index : incident.get(node)) {
                if (index == cameFrom) {
                    continue;
                }
                Edge edge = edges.get(index);
                String far;
                if (edge.from().equals(node)) {
                    result.add(new Zone(index, 0, Math.min(remaining, edge.length), special));
                    far = edge.to();
                } else {
                    result.add(new Zone(index, Math.max(edge.length - remaining, 0), edge.length, special));
                    far = edge.from();
                }
                if (remaining > edge.length && edge.length > 0) {
                    walk(result, far, index, remaining - edge.length, special);
                }
            }
        }

        /**
         * Специальные интервалы по рёбрам, расширенные на ZONE_GROW_M; границы у концов ребра прижимаются к узлу.
         * Интервал режется там, где меняется наибольший Kспец покрывающих зон (смена набора объектов — новый
         * участок, п. 4), и сливается с соседом только при равном Kспец. При наложении зон коэффициент один,
         * наибольший, без перемножения.
         */
        List<List<double[]>> mergedZones() {
            List<List<double[]>> result = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                List<double[]> raw = new ArrayList<>();
                for (Zone zone : zonesByEdge.get(i)) {
                    if (zone.to > zone.from) {
                        raw.add(new double[] {Math.max(0, zone.from - ZONE_GROW_M),
                                Math.min(edges.get(i).length, zone.to + ZONE_GROW_M), zone.special.rule.getKSpecial()});
                    }
                }
                TreeSet<Double> bounds = new TreeSet<>();
                for (double[] interval : raw) {
                    bounds.add(interval[0]);
                    bounds.add(interval[1]);
                }
                List<double[]> merged = new ArrayList<>();
                Double from = null;
                for (double to : bounds) {
                    if (from != null) {
                        double mid = (from + to) / 2;
                        double k = raw.stream().filter(r -> r[0] <= mid && mid <= r[1]).mapToDouble(r -> r[2]).max().orElse(0);
                        double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                        if (k == 0) {
                            from = to;
                            continue;
                        }
                        if (last != null && last[2] == k && from <= last[1] + MERGE_M) {
                            last[1] = to;
                        } else {
                            merged.add(new double[] {from, to, k});
                        }
                    }
                    from = to;
                }
                double length = edges.get(i).length;
                for (double[] interval : merged) {
                    if (interval[0] <= MERGE_M) {
                        interval[0] = 0;
                    }
                    if (interval[1] >= length - MERGE_M) {
                        interval[1] = length;
                    }
                }
                result.add(merged);
            }
            return result;
        }
    }

    private static boolean inside(List<double[]> intervals, double at) {
        for (double[] interval : intervals) {
            if (interval[0] <= at && at <= interval[1]) {
                return true;
            }
        }
        return false;
    }

    private static int find(int[] cluster, int i) {
        while (cluster[i] != i) {
            cluster[i] = cluster[cluster[i]];
            i = cluster[i];
        }
        return i;
    }
}
