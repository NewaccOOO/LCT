package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.Distance;
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
 * всей трассе варианта: margin_m вдоль трассы от точки пересечения линии или границы полигона продолжается через узлы
 * во все ветви, у дороги к ним добавляется часть трассы в полигоне.
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
    /**
     * Шаг, с которым зона полигона продлевается за margin_m, пока обычный участок был бы ближе нормы отступа, и запас
     * к норме у её конца: отступ сверяется с точностью 1 мм.
     */
    private static final double NORM_STEP_M = 0.01;
    private static final double NORM_EXTRA_M = 0.002;
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
    /** Полоса по x вокруг точки пересечения, где ищутся точки врезки: TOUCH_M с запасом на округление. */
    private static final double TIE_BAND_M = TieInFinder.TOUCH_M + 1e-6;
    /** Запас к порогу расстояния в полосе по x при поиске близких узлов. */
    private static final double GAP_EPS_M = 1e-6;
    /** Начала выходных ID: префикс варианта «v…_» и ID сводки. */
    private static final String OUTPUT_PREFIX = "v";
    private static final String SUMMARY_PREFIX = "summary_";

    private final InputData input;
    private final Rules rules;
    private final SpecialObjects specials;
    private final CostCalculator costs;
    private final DiameterPlanner planner;
    /** ОКС входа по id у перечислителя: своя карта расходов на 3 млн ОКС города не строится. */
    private final Map<String, FutureOks> oksById;
    /** ID источника входа; остальные ID берутся из списков входа, см. {@link #inputIds}. */
    private final String sourceId;
    /** Проверки ID входа по префиксу и целиком: выходные ID с ID входа не совпадают. */
    private final Map<String, Boolean> startsByPrefix = new ConcurrentHashMap<>();
    private final Map<String, Boolean> knownIds = new ConcurrentHashMap<>();
    /** ID входа с началом OUTPUT_PREFIX или SUMMARY_PREFIX, см. {@link #ids}; null — ещё не собраны. */
    private volatile List<String> reserved;
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

    NetworkAssembler(InputData input, Rules rules, SpecialObjects specials, Map<String, FutureOks> oksById) {
        this.input = input;
        this.rules = rules;
        this.specials = specials;
        this.costs = new CostCalculator(rules);
        this.planner = new DiameterPlanner(rules);
        this.sourceId = input.getSource().getId();
        this.oksById = oksById;
    }

    private boolean startsInputId(String prefix) {
        // вход города — миллионы ID, а префиксов за расчёт единицы: каждый проверяется один раз, повторный — без замка
        return startsByPrefix.computeIfAbsent(prefix, key -> ids(key).anyMatch(id -> id.startsWith(key)));
    }

    private boolean isInputId(String id) {
        return knownIds.computeIfAbsent(id, key -> ids(key).anyMatch(key::equals));
    }

    /**
     * ID входа, среди которых искать key: выходные ID начинаются с «v» или «summary_», и такие ID входа собираются
     * одним проходом; остальные ключи ищутся по всему входу. Каждая проверка проходила шесть миллионов ID города,
     * а их восемь на расчёт, и остальные сборки ждали.
     */
    private Stream<String> ids(String key) {
        if (!key.startsWith(OUTPUT_PREFIX) && !key.startsWith(SUMMARY_PREFIX)) {
            return inputIds();
        }
        List<String> found = reserved;
        if (found == null) {
            synchronized (this) {
                if (reserved == null) {
                    reserved = inputIds().filter(id -> id.startsWith(OUTPUT_PREFIX) || id.startsWith(SUMMARY_PREFIX))
                            .collect(java.util.stream.Collectors.toList());
                }
                found = reserved;
            }
        }
        return found.stream();
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

    /** Ду каждого ребра деревьев, как его назначит сборка: по расходу и предельной длине (п. 2.3). */
    Map<Tree.Edge, Integer> diameters(List<Tree> trees) {
        Build build = new Build("0", 0, trees, List.of(), new Counters());
        for (String root : build.units.keySet()) {
            build.plan(root);
        }
        Map<Tree.Edge, Integer> result = new java.util.IdentityHashMap<>();
        for (Edge edge : build.edges) {
            result.put(edge.source, build.dnByEdge.get(edge.id));
        }
        return result;
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
        /** Точки врезки узлов по возрастанию x, см. atTie; null — ещё не нужны. */
        Coordinate[] tiesByX;
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
                        edges.add(edge);
                    }
                }
            }
        }

        Variant run() {
            // Ду нужен зонам: зона дороги продлевается, пока обычный участок этого Ду был бы ближе нормы
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                plan(unit.getKey());
            }
            zonesByEdge = zones();
            specialByEdge = mergedZones();
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
                    FutureOks oks = oksById.get(edge.source.to.connection.getOksId());
                    oksFlowByNode.put(edge.to(), oks == null ? 0.0 : oks.getFlowTph());
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
                    // вершина за границей спецчасти ближе MERGE_M сама становится узлом: иначе у узла остаётся звено в
                    // сантиметры с поворотом, а спецчасть удлиняется не больше, чем у конца ребра
                    double snap = inside(special, (vertex + candidate) / 2) ? VERTEX_SNAP_M : MERGE_M;
                    if (Math.abs(vertex - candidate) <= snap) {
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
            // подряд идущие спецучастки: начало, конец и пересечённые линии
            String runStart = null;
            String runEnd = null;
            Set<SpecialObjects.Special> runLines = new HashSet<>();
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
                        runStart = runStart == null ? startId : runStart;
                        runEnd = endId;
                        crossed.stream().filter(object -> !object.band).forEach(runLines::add);
                        k = crossed.stream().mapToDouble(s -> s.rule.getKSpecial()).max().orElse(1);
                    } else if (runStart != null) {
                        exempt(runStart, runEnd, runLines);
                        runStart = null;
                        runLines = new HashSet<>();
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
            if (runStart != null) {
                exempt(runStart, runEnd, runLines);
            }
        }

        /**
         * Концы подряд идущих спецучастков ребра освобождают смежные обычные участки от отступа до линий, которые
         * эти спецучастки пересекают: зона линии — margin_m вдоль трассы, а отступ больше (п. 4, табл. 2). От дороги
         * отступ держится: её зона продлена до нормы, см. polygonZone.
         */
        void exempt(String runStart, String runEnd, Set<SpecialObjects.Special> lines) {
            exemptByNode.computeIfAbsent(runStart, id -> new HashSet<>()).addAll(lines);
            exemptByNode.computeIfAbsent(runEnd, id -> new HashSet<>()).addAll(lines);
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
         * Отступ обычного участка от объектов со специальным проходом: линии, через которые проходит смежный
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
                Coordinate start = segment.getGeometry().getCoordinateN(0);
                for (SpecialObjects.Special special : specials.around(segment.getGeometry(), segment.getDiameter())) {
                    if (exempt.contains(special)
                            || fromTie && special.network && withinDistance(special.geometry, start, TieInFinder.TOUCH_M)) {
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
            // узлы по x: пара ближе NODE_APART_M лежит в полосе по x той же ширины, дальше по списку не смотрим
            List<String> byX = new ArrayList<>(ids);
            byX.sort(Comparator.comparingDouble(id -> points.get(id).x));
            for (int i = 0; i < byX.size(); i++) {
                Coordinate at = points.get(byX.get(i));
                for (int j = i + 1; j < byX.size() && points.get(byX.get(j)).x - at.x <= NODE_APART_M + GAP_EPS_M; j++) {
                    if (at.distance(points.get(byX.get(j))) <= NODE_APART_M) {
                        throw new IllegalStateException("Узлы " + byX.get(i) + " и " + byX.get(j) + " ближе "
                                + NODE_APART_M + " м: проверка считает их одним узлом");
                    }
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
                        if (!Router.apart(coords[i], coords[i + 1], coords[j], coords[j + 1], TOUCH_APART_M) && new LineSegment(
                                coords[i], coords[i + 1]).distance(new LineSegment(coords[j], coords[j + 1])) <= TOUCH_APART_M) {
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
                    Coordinate[] coordsA = a.getGeometry().getCoordinates();
                    Coordinate[] coordsB = b.getGeometry().getCoordinates();
                    if (a.getId().compareTo(b.getId()) >= 0 || apart(coordsA, coordsB, NODE_APART_M)) {
                        continue;
                    }
                    // у участков с общим концом расстояние JTS — ноль: отрезки с общей точкой пересекаются
                    double distance = sharedEnd(coordsA, coordsB) ? 0 : a.getGeometry().distance(b.getGeometry());
                    if (distance > NODE_APART_M) {
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
                    if (distance > TOUCH_APART_M) {
                        continue;
                    }
                    LineString restA = rest(a, shared, group);
                    LineString restB = rest(b, shared, group);
                    if (restA != null && restB != null && !apart(restA.getCoordinates(), restB.getCoordinates(), TOUCH_APART_M)
                            && restA.distance(restB) <= TOUCH_APART_M) {
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
            // рёбра каждого объекта, чья зона задевает их рамку, по возрастанию индекса: списки объектов у рёбер
            // общие для всех черновиков с этим деревом, а запрос к индексу рёбер на каждый объект трассы был дороже
            // самих зон. Порядок зон у ребра на участки не влияет
            Map<SpecialObjects.Special, List<Integer>> edgesBySpecial = new LinkedHashMap<>();
            for (int i = 0; i < edges.size(); i++) {
                for (SpecialObjects.Special special : nearSpecials(edges.get(i).source)) {
                    edgesBySpecial.computeIfAbsent(special, key -> new ArrayList<>()).add(i);
                }
            }
            List<Zone> zones = new ArrayList<>();
            for (Map.Entry<SpecialObjects.Special, List<Integer>> near : edgesBySpecial.entrySet()) {
                SpecialObjects.Special special = near.getKey();
                // объект берётся, если его рамка задевает рамку трассы
                if (!special.geometry.getEnvelopeInternal().intersects(trace)) {
                    continue;
                }
                if (special.band) {
                    polygonZone(special, near.getValue(), zones);
                } else {
                    lineZone(special, near.getValue(), zones);
                }
            }
            for (Zone zone : zones) {
                result.get(zone.edge).add(zone);
            }
            return result;
        }

        /**
         * Зона полигона дороги или путей (п. 4, табл. 2): часть трассы в полигоне и margin_m вдоль трассы от каждой
         * точки пересечения его границы, через узлы во все ветви. Дорога или пути, заданные линией, — полигон нулевой
         * ширины: margin_m вдоль трассы от точки пересечения оси. Если в конце этих метров обычный участок был бы
         * ближе нормы отступа к полигону (Ду от 400 под углом около 45°), зона идёт дальше вдоль ребра до точки, где
         * норма держится; near — рёбра у полигона.
         */
        void polygonZone(SpecialObjects.Special special, List<Integer> near, List<Zone> result) {
            for (int i : near) {
                Edge edge = edges.get(i);
                Crossing crossing = crossing(edge.source, special);
                for (double[] inside : crossing.inside) {
                    result.add(new Zone(i, inside[0], inside[1], special));
                }
                double norm = specials.clearance(special, dnByEdge.get(edge.id));
                for (double at : crossing.hits) {
                    double back = reach(edge, at, -1, special, norm);
                    spread(result, i, at, back, reach(edge, at, 1, special, norm), special);
                }
            }
        }

        /**
         * Метры зоны от точки пересечения at в сторону dir: margin_m, а если там точка ребра вне полигона ближе нормы
         * norm к нему — до первой точки с шагом NORM_STEP_M, где норма держится, но не дальше конца ребра. margin_m
         * за концом ребра продолжается через узел, см. spread.
         */
        double reach(Edge edge, double at, int dir, SpecialObjects.Special special, double norm) {
            double margin = special.rule.getMarginM();
            double end = dir < 0 ? at : edge.length - at;
            LengthIndexedLine indexed = null;
            for (double distance = margin; distance < end; distance += NORM_STEP_M) {
                indexed = indexed == null ? new LengthIndexedLine(edge.source.line) : indexed;
                Coordinate c = indexed.extractPoint(at + dir * distance);
                if (special.polygon && special.inside(c) || !special.within(factory.createPoint(c), norm + NORM_EXTRA_M)) {
                    return distance;
                }
            }
            return Math.max(margin, end);
        }

        /** По margin_m в обе стороны от каждого пересечения; пересечение сети в точке врезки не считается. */
        void lineZone(SpecialObjects.Special special, List<Integer> near, List<Zone> result) {
            for (int i : near) {
                for (Hit hit : hits(edges.get(i).source, special)) {
                    if (special.network && atTie(hit.at, special.geometry)) {
                        continue;
                    }
                    spread(result, i, hit.position, special.rule.getMarginM(), special.rule.getMarginM(), special);
                }
            }
        }

        boolean atTie(Coordinate c, Geometry line) {
            if (tiesByX == null) {
                tiesByX = units.values().stream().map(unit -> unit.get(0).root.point)
                        .sorted(Comparator.comparingDouble(tie -> tie.x)).toArray(Coordinate[]::new);
            }
            // точки врезки по x: ближе TOUCH_M к c только те, что в полосе x ± TOUCH_M, остальные не проверяются
            int k = 0;
            int end = tiesByX.length;
            while (k < end) {
                int mid = (k + end) >>> 1;
                if (tiesByX[mid].x < c.x - TIE_BAND_M) {
                    k = mid + 1;
                } else {
                    end = mid;
                }
            }
            for (; k < tiesByX.length && tiesByX[k].x <= c.x + TIE_BAND_M; k++) {
                Coordinate tie = tiesByX[k];
                if (c.distance(tie) <= TieInFinder.TOUCH_M && withinDistance(line, tie, TieInFinder.TOUCH_M)) {
                    return true;
                }
            }
            return false;
        }

        /** Зона от места position назад на back и вперёд на ahead по ребру и дальше через узлы. */
        void spread(List<Zone> result, int index, double position, double back, double ahead,
                SpecialObjects.Special special) {
            Edge edge = edges.get(index);
            result.add(new Zone(index, Math.max(0, position - back), Math.min(edge.length, position + ahead), special));
            if (position - back < 0) {
                walk(result, edge.from(), index, back - position, special);
            }
            if (position + ahead > edge.length) {
                walk(result, edge.to(), index, position + ahead - edge.length, special);
            }
        }

        /** Зона продолжается через узел во все ветви. */
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
         * Интервал режется везде, где меняется набор покрывающих зон: на границах общего фрагмента начинается новый
         * участок (п. 4, разъяснение 8). Коэффициент части — наибольший Kспец её набора, без перемножения.
         */
        List<List<double[]>> mergedZones() {
            List<List<double[]>> result = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                List<Zone> raw = new ArrayList<>();
                TreeSet<Double> bounds = new TreeSet<>();
                for (Zone zone : zonesByEdge.get(i)) {
                    if (zone.to > zone.from) {
                        Zone grown = new Zone(i, Math.max(0, zone.from - ZONE_GROW_M),
                                Math.min(edges.get(i).length, zone.to + ZONE_GROW_M), zone.special);
                        raw.add(grown);
                        bounds.add(grown.from);
                        bounds.add(grown.to);
                    }
                }
                List<double[]> merged = new ArrayList<>();
                Set<SpecialObjects.Special> lastSet = null;
                Double from = null;
                for (double to : bounds) {
                    if (from != null) {
                        double mid = (from + to) / 2;
                        Set<SpecialObjects.Special> set = new HashSet<>();
                        double k = 0;
                        for (Zone zone : raw) {
                            if (zone.from <= mid && mid <= zone.to) {
                                set.add(zone.special);
                                k = Math.max(k, zone.special.rule.getKSpecial());
                            }
                        }
                        double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                        if (set.isEmpty()) {
                            from = to;
                            continue;
                        }
                        if (last != null && set.equals(lastSet) && from <= last[1] + MERGE_M) {
                            last[1] = to;
                        } else {
                            merged.add(new double[] {from, to, k});
                            lastSet = set;
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

    /** Ребро и полигон: части ребра в полигоне и места пересечения границы по длине ребра. */
    private static final class Crossing {
        final List<double[]> inside = new ArrayList<>();
        final List<Double> hits = new ArrayList<>();
    }

    /** Точка пересечения ребра с линейным объектом и её место по длине ребра. */
    private static final class Hit {
        final Coordinate at;
        final double position;

        Hit(Coordinate at, double position) {
            this.at = at;
            this.position = position;
        }
    }

    /** Спецобъекты, чья рамка задевает рамку ребра; считаются один раз на ребро. */
    private List<SpecialObjects.Special> nearSpecials(Tree.Edge edge) {
        List<SpecialObjects.Special> near = edge.nearSpecials;
        if (near == null) {
            near = specials.zonesNear(edge.line.getEnvelopeInternal());
            edge.nearSpecials = near;
        }
        return near;
    }

    /**
     * Части ребра в полигоне special и места пересечения его границы; считаются один раз на ребро и объект, см.
     * {@link Tree.Edge#crossings}. Конец ребра в полигоне тоже считается местом пересечения: его зона вдоль трассы
     * лежит внутри зон настоящих пересечений на тех же путях.
     */
    private static Crossing crossing(Tree.Edge edge, SpecialObjects.Special special) {
        Crossing cached = (Crossing) edge.crossings.get(special);
        if (cached != null) {
            return cached;
        }
        Crossing crossing = new Crossing();
        if (special.prepared.intersects(edge.line)) {
            Geometry common = edge.line.intersection(special.geometry);
            LengthIndexedLine indexed = new LengthIndexedLine(edge.line);
            for (int g = 0; g < common.getNumGeometries(); g++) {
                Coordinate[] coords = common.getGeometryN(g).getCoordinates();
                if (coords.length == 0) {
                    continue;
                }
                double a = indexed.project(coords[0]);
                double b = indexed.project(coords[coords.length - 1]);
                if (common.getGeometryN(g).getLength() > 0) {
                    crossing.inside.add(new double[] {Math.min(a, b), Math.max(a, b)});
                }
                crossing.hits.add(a);
                if (b != a) {
                    crossing.hits.add(b);
                }
            }
        }
        edge.crossings.putIfAbsent(special, crossing);
        return crossing;
    }

    /** Пересечения ребра с линейным объектом special; считаются один раз на ребро и объект. */
    @SuppressWarnings("unchecked")
    private static List<Hit> hits(Tree.Edge edge, SpecialObjects.Special special) {
        List<Hit> cached = (List<Hit>) edge.crossings.get(special);
        if (cached != null) {
            return cached;
        }
        List<Hit> hits = new ArrayList<>();
        if (special.crossedBy(edge.line)) {
            LengthIndexedLine indexed = new LengthIndexedLine(edge.line);
            for (Coordinate c : edge.line.intersection(special.geometry).getCoordinates()) {
                hits.add(new Hit(c, indexed.project(c)));
            }
        }
        edge.crossings.putIfAbsent(special, hits);
        return hits;
    }

    /**
     * То же, что line.isWithinDistance(точка c, distance) для линии сети: рамки, затем расстояния до отрезков, как в
     * DistanceOp, но без его объектов на каждый вызов. Проверка идёт на каждый участок от врезки и на каждое
     * пересечение с трубой, а у магистрали сотни отрезков.
     */
    private static boolean withinDistance(Geometry line, Coordinate c, double distance) {
        if (line.getEnvelopeInternal().distance(new Envelope(c)) > distance) {
            return false;
        }
        Coordinate[] coords = line.getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            if (Distance.pointToSegment(c, coords[i], coords[i + 1]) <= distance) {
                return true;
            }
        }
        return false;
    }

    /** Конец одной линии совпадает с концом другой. */
    private static boolean sharedEnd(Coordinate[] a, Coordinate[] b) {
        Coordinate a0 = a[0];
        Coordinate a1 = a[a.length - 1];
        Coordinate b0 = b[0];
        Coordinate b1 = b[b.length - 1];
        return a0.equals2D(b0) || a0.equals2D(b1) || a1.equals2D(b0) || a1.equals2D(b1);
    }

    /** {@link Router#apart(Coordinate, Coordinate, Coordinate, Coordinate, double)} для всех пар отрезков линий a и b. */
    private static boolean apart(Coordinate[] a, Coordinate[] b, double limit) {
        for (int i = 0; i + 1 < a.length; i++) {
            for (int j = 0; j + 1 < b.length; j++) {
                if (!Router.apart(a[i], a[i + 1], b[j], b[j + 1], limit)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean inside(List<double[]> intervals, double at) {
        for (double[] interval : intervals) {
            if (interval[0] <= at && at <= interval[1]) {
                return true;
            }
        }
        return false;
    }
}
