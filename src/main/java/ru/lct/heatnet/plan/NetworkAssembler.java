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
import java.util.function.Predicate;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.calc.CostCalculator;
import ru.lct.heatnet.calc.DiameterPlanner;
import ru.lct.heatnet.calc.FlowCalculator;
import ru.lct.heatnet.calc.ReconPart;
import ru.lct.heatnet.calc.ReconstructionCalculator;
import ru.lct.heatnet.calc.ReconstructionResult;
import ru.lct.heatnet.calc.SizedPiece;
import ru.lct.heatnet.calc.TieInLoad;
import ru.lct.heatnet.calc.TreeEdge;
import ru.lct.heatnet.model.ChamberReconstruction;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Reconstruction;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.TieIn;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

/**
 * Сборка варианта из деревьев (D-12): расходы, специальные части, диаметры и разрезы, участки с узлами,
 * реконструкция, стоимость и сводка. Специальные части считаются по всей трассе варианта, как в разделе
 * «Уточнения, принятые в валидаторе» docs/interpretation.md: зона линии продолжается через узлы во все ветви,
 * зона дороги — вся связная часть трассы в буфере, пересекающая полигон.
 */
final class NetworkAssembler {
    static final String BASE = "base";
    static final String SPECIAL = "special";
    /** Границы ближе этого сливаются: валидатор считает узлы в 0,05 м одним узлом. */
    private static final double MERGE_M = 0.08;
    /** Валидатор расширяет специальную зону на 0,05 м и считает участок в зоне при перекрытии больше 0,1 м. */
    private static final double ZONE_TOL_M = 0.05;
    private static final double ZONE_OVERLAP_M = 0.1;
    /**
     * Специальная часть шире зоны на 2 см с каждой стороны: обычный участок между двумя зонами одного объекта иначе
     * перекрыл бы расширенные зоны ровно на 0,1 м, на границе порога валидатора.
     */
    private static final double ZONE_GROW_M = 0.02;
    /**
     * Выход округляется до 9 знаков градуса, это около 0,1 мм: подотрезок ровно в метр становится короче метра,
     * а у подотрезка в доли миллиметра направление, а с ним и излом в вершине, случайны.
     */
    private static final double ROUNDING_MARGIN_M = 0.01;
    /** Разрез ближе этого к вершине ребра переносится в вершину; сдвиг вместе с ZONE_GROW_M не выходит за ZONE_TOL_M. */
    private static final double VERTEX_SNAP_M = ZONE_TOL_M - ZONE_GROW_M;
    private static final double NEAR_STEP_M = 0.5;
    private static final double DIST_EPS_M = 0.001;
    private static final double MIN_TURN_DEG = 3;
    /** Валидатор склеивает узлы ближе 0,05 м и видит касание участков ближе 0,001 м за вырезом 0,15 м у узла. */
    private static final double NODE_APART_M = 0.06;
    private static final double TOUCH_APART_M = 0.002;
    private static final double SHARED_CLIP_M = 0.14;
    /** Валидатор считает участок в зоне при перекрытии больше 0,1 м: специальный кусок короче не распознать. */
    private static final double MIN_SPECIAL_M = 0.12;
    private static final double MIN_SPLIT_DEG = 30.5;

    private final InputData input;
    private final Rules rules;
    private final SpecialObjects specials;
    private final CostCalculator costs;
    private final DiameterPlanner planner;
    private final Map<String, Double> flowByOks = new HashMap<>();
    /** ID входа: выходные ID с ними не совпадают (правило schema). */
    private final Set<String> inputIds = new HashSet<>();
    private final Map<String, Boolean> startsByPrefix = new HashMap<>();
    /** Полигоны, которые считаются в правиле поворотов: запрещённые, дороги и трамвайные пути. */
    private final TurnRule turnRule;
    private final GeometryFactory factory = new GeometryFactory();

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

        /** Перекрытие с частью ребра так, как считает валидатор: зона расширена на ZONE_TOL_M. */
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

    NetworkAssembler(InputData input, Rules rules, SpecialObjects specials, TurnRule turnRule) {
        this.input = input;
        this.rules = rules;
        this.specials = specials;
        this.costs = new CostCalculator(rules);
        this.planner = new DiameterPlanner(rules);
        inputIds.add(input.getSource().getId());
        input.getSegments().forEach(segment -> inputIds.add(segment.getId()));
        input.getChambers().forEach(chamber -> inputIds.add(chamber.getId()));
        input.getConnectionPoints().forEach(connection -> inputIds.add(connection.getId()));
        for (FutureOks oks : input.getFutureOks()) {
            flowByOks.put(oks.getId(), oks.getFlowTph());
            inputIds.add(oks.getId());
        }
        for (ExistingOks oks : input.getExistingOks()) {
            inputIds.add(oks.getId());
        }
        for (Restriction restriction : input.getRestrictions()) {
            inputIds.add(restriction.getId());
        }
        this.turnRule = turnRule;
    }

    private boolean startsInputId(String prefix) {
        // вход города — миллионы ID, а префиксов за расчёт единицы: каждый проверяется один раз
        return startsByPrefix.computeIfAbsent(prefix, key -> inputIds.stream().anyMatch(id -> id.startsWith(key)));
    }

    /**
     * Вариант из деревьев; деревья с общей точкой врезки собираются в один узел, у существующей камеры каждое
     * ребро из неё — своя врезка (протокол 16.09.2026 п. 8). Бросает
     * {@link IllegalStateException}, если диаметры не разрезаются по предельной длине или собранная сеть нарушает
     * отступ от объектов специального прохода, число поворотов или форму участков: такое дерево не выдаётся.
     */
    Variant assemble(String variantId, int rank, List<Tree> trees, List<FutureOks> unconnected) {
        return new Build(variantId, rank, trees, unconnected).run();
    }

    /** Состояние одной сборки. */
    private final class Build {
        final String variantId;
        final int rank;
        final String prefix;
        final List<FutureOks> unconnected;
        final Map<String, List<Tree>> units = new LinkedHashMap<>();
        final List<Edge> edges = new ArrayList<>();
        final Map<String, List<Integer>> incident = new HashMap<>();
        final Map<String, Double> flowByEdge = new HashMap<>();
        final Map<String, Double> flowByUnit = new HashMap<>();
        final Map<String, List<SizedPiece>> piecesByEdge = new HashMap<>();
        final Map<String, String> nodeIds = new HashMap<>();
        /** ID врезки по индексу ребра из корня-камеры: каждый луч из существующей камеры — своя врезка. */
        final Map<Integer, String> tieIdByEdge = new HashMap<>();
        final Map<String, Integer> maxDnByNode = new HashMap<>();
        final Map<String, Set<SpecialObjects.Special>> exemptByNode = new HashMap<>();
        final Map<String, NewSegment> incomingByNode = new HashMap<>();
        final List<TieIn> tieIns = new ArrayList<>();
        final List<NewChamber> chambers = new ArrayList<>();
        final List<TechnicalNode> nodes = new ArrayList<>();
        final List<NewSegment> segments = new ArrayList<>();
        List<Zone> zones;
        List<List<double[]>> specialByEdge;

        Build(String variantId, int rank, List<Tree> trees, List<FutureOks> unconnected) {
            this.variantId = variantId;
            this.rank = rank;
            // вход может содержать ID вида v1_seg_1: префикс удлиняется, пока с него не начинается ни один ID входа
            String free = "v" + variantId + "_";
            while (startsInputId(free)) {
                free = "v" + free;
            }
            this.prefix = free;
            this.unconnected = unconnected;
            for (Tree tree : trees) {
                if (!tree.edges.isEmpty()) {
                    units.computeIfAbsent(tree.root.key, key -> new ArrayList<>()).add(tree);
                }
            }
            for (List<Tree> unit : units.values()) {
                for (Tree tree : unit) {
                    for (Tree.Edge source : tree.edges) {
                        Edge edge = new Edge("e" + edges.size(), tree, source);
                        incident.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edges.size());
                        incident.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edges.size());
                        edges.add(edge);
                    }
                }
            }
        }

        Variant run() {
            zones = zones();
            specialByEdge = mergedZones();
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                plan(unit.getKey(), unit.getValue());
            }
            nameNodes();
            for (int i = 0; i < edges.size(); i++) {
                cut(i);
            }
            checkClearance();
            checkApart();
            checkTurns();
            checkShape();
            for (Edge edge : edges) {
                Tree.Node node = edge.source.to;
                if (node.kind == Tree.Kind.JUNCTION) {
                    String id = nodeIds.get(node.key);
                    int dn = maxDnByNode.get(id);
                    chambers.add(new NewChamber(id, variantId, factory.createPoint(node.point), dn, costs.chamberCost(dn)));
                }
            }
            List<ChamberReconstruction> chamberReconstructions = new ArrayList<>();
            ReconstructionResult recon = tieIns(chamberReconstructions);

            List<Reconstruction> reconstructions = new ArrayList<>();
            for (ReconPart part : recon.getParts()) {
                reconstructions.add(new Reconstruction(prefix + "recon_" + (reconstructions.size() + 1), variantId,
                        part.getGeometry(), part.getExistingObjectId(), part.getExistingFlowTph(), part.getAddedFlowTph(),
                        part.getCalculatedFlowTph(), part.getExistingDiameter(), part.getRequiredDiameter(),
                        part.getLength(), part.getCost()));
            }

            String summaryId = inputIds.contains("summary_" + variantId) ? prefix + "summary" : "summary_" + variantId;
            VariantSummary draft = costs.summary(summaryId, variantId, segments, chambers, tieIns,
                    reconstructions, chamberReconstructions, unconnected);
            VariantSummary summary = new VariantSummary(draft.getId(), variantId, rank, draft.getConstructionCost(),
                    draft.getChamberConstructionCost(), draft.getTieInCost(), draft.getReconstructionCost(),
                    draft.getChamberReconstructionCost(), draft.getUnconnectedPenalty(), draft.getCalculatedCost(),
                    draft.getNewNetworkLength(), draft.getReconstructionLength(), draft.getLength(), draft.getScore(),
                    draft.getUnconnectedOksIds());
            return new Variant(variantId, segments, tieIns, reconstructions, chambers, chamberReconstructions, nodes,
                    summary);
        }

        /** Расходы и куски диаметров одного дерева; разрезы запрещены в специальных частях, у вершин и в зонах
         * сближения, где узел лишил бы соседний участок права на отступ через специальный участок. */
        void plan(String root, List<Tree> unit) {
            List<TreeEdge> treeEdges = new ArrayList<>();
            Map<String, Double> oksFlowByNode = new HashMap<>();
            for (Edge edge : edges) {
                if (unit.contains(edge.tree)) {
                    treeEdges.add(new TreeEdge(edge.id, edge.from(), edge.to(), edge.length));
                    if (edge.source.to.kind == Tree.Kind.CONNECTION) {
                        oksFlowByNode.put(edge.to(), flowByOks.getOrDefault(edge.source.to.connection.getOksId(), 0.0));
                    }
                }
            }
            Map<String, Double> flows = FlowCalculator.flows(treeEdges, root, oksFlowByNode);
            flowByEdge.putAll(flows);
            Map<String, List<double[]>> noCut = new HashMap<>();
            double unitFlow = 0;
            for (int i = 0; i < edges.size(); i++) {
                Edge edge = edges.get(i);
                if (!flows.containsKey(edge.id)) {
                    continue;
                }
                if (edge.from().equals(root)) {
                    unitFlow += flows.get(edge.id);
                }
                List<double[]> intervals = new ArrayList<>(specialByEdge.get(i));
                for (double vertex : TreeBuilder.vertexPositions(edge.source.line)) {
                    double gap = TreeBuilder.MIN_PIECE_M + ROUNDING_MARGIN_M;
                    intervals.add(new double[] {vertex - gap, vertex + gap});
                }
                intervals.addAll(nearSpecial(edge, flows.get(edge.id)));
                noCut.put(edge.id, intervals);
            }
            flowByUnit.put(root, unitFlow);
            for (SizedPiece piece : planner.plan(treeEdges, root, flows, noCut)) {
                piecesByEdge.computeIfAbsent(piece.getEdgeId(), id -> new ArrayList<>()).add(piece);
            }
        }

        /** Интервалы ребра ближе отступа к объектам со специальным проходом для диаметра на ступень выше расхода. */
        List<double[]> nearSpecial(Edge edge, double flow) {
            Diameter byFlow = rules.diameterFor(flow);
            int dn = rules.nextDiameter(byFlow.getDn()) != null ? rules.nextDiameter(byFlow.getDn()).getDn() : byFlow.getDn();
            LengthIndexedLine indexed = new LengthIndexedLine(edge.source.line);
            List<double[]> intervals = new ArrayList<>();
            double[] open = null;
            Predicate<Coordinate> near = specials.nearAlong(edge.source.line, dn);
            for (double at = 0; at <= edge.length + NEAR_STEP_M; at += NEAR_STEP_M) {
                double position = Math.min(at, edge.length);
                if (near.test(indexed.extractPoint(position))) {
                    if (open == null) {
                        open = new double[] {position - NEAR_STEP_M, position + NEAR_STEP_M};
                        intervals.add(open);
                    }
                    open[1] = position + NEAR_STEP_M;
                } else {
                    open = null;
                }
            }
            return intervals;
        }

        void nameNodes() {
            int tieNo = 0;
            int chamberNo = 0;
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                nodeIds.put(unit.getKey(), prefix + "tie_" + ++tieNo);
                // протокол 16.09.2026 п. 8: несколько новых веток к одной камере — несколько независимых врезок
                if (unit.getValue().get(0).tie.isChamber()) {
                    boolean first = true;
                    for (int i = 0; i < edges.size(); i++) {
                        if (edges.get(i).from().equals(unit.getKey())) {
                            tieIdByEdge.put(i, first ? nodeIds.get(unit.getKey()) : prefix + "tie_" + ++tieNo);
                            first = false;
                        }
                    }
                }
            }
            for (Edge edge : edges) {
                for (Tree.Node node : List.of(edge.source.from, edge.source.to)) {
                    if (node.kind == Tree.Kind.JUNCTION && !nodeIds.containsKey(node.key)) {
                        nodeIds.put(node.key, prefix + "ch_" + ++chamberNo);
                    } else if (node.kind == Tree.Kind.CONNECTION) {
                        nodeIds.put(node.key, node.connection.getId());
                    }
                }
            }
        }

        /** Режет ребро на участки в границах специальных частей и в точках смены диаметра. */
        void cut(int index) {
            Edge edge = edges.get(index);
            List<double[]> special = specialByEdge.get(index);
            List<SizedPiece> pieces = piecesByEdge.get(edge.id);
            TreeMap<Double, String> cuts = new TreeMap<>();
            cuts.put(0.0, tieIdByEdge.getOrDefault(index, nodeIds.get(edge.from())));
            cuts.put(edge.length, nodeIds.get(edge.to()));
            List<Double> candidates = new ArrayList<>();
            for (double[] interval : special) {
                candidates.add(interval[0]);
                candidates.add(interval[1]);
            }
            for (SizedPiece piece : pieces) {
                candidates.add(piece.getFromM());
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
                    String id = prefix + "node_" + (nodes.size() + 1);
                    nodes.add(new TechnicalNode(id, variantId, factory.createPoint(point)));
                    cuts.put(at, id);
                    points.put(at, point);
                }
            }
            Double from = null;
            for (double to : cuts.keySet()) {
                if (from != null) {
                    double mid = (from + to) / 2;
                    int dn = dnAt(pieces, mid);
                    boolean isSpecial = inside(special, mid);
                    if (!isSpecial) {
                        for (Zone zone : zones) {
                            // хвост зоны за разрезом, слитым с соседним ближе MERGE_M: валидатор увидит обычный
                            // участок в зоне перехода
                            if (zone.edge == index && zone.overlap(from, to) > ZONE_OVERLAP_M) {
                                throw new IllegalStateException("Обычный участок ребра " + edge.id + " лежит в зоне "
                                        + "спецперехода больше чем на " + ZONE_OVERLAP_M + " м");
                            }
                        }
                    }
                    Coordinate[] coords = indexed.extractLine(from, to).getCoordinates();
                    coords[0] = points.get(from);
                    coords[coords.length - 1] = points.get(to);
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
                    k *= kTurn(coords, from == 0.0 ? null : incomingByNode.get(startId));
                    NewSegment segment = new NewSegment(prefix + "seg_" + (segments.size() + 1), variantId, line, startId,
                            endId, flowByEdge.get(edge.id), dn, length, isSpecial ? SPECIAL : BASE, null, null,
                            costs.segmentCost(length, dn, k));
                    segments.add(segment);
                    incomingByNode.put(endId, segment);
                    maxDnByNode.merge(startId, dn, Math::max);
                    maxDnByNode.merge(endId, dn, Math::max);
                }
                from = to;
            }
        }

        /**
         * Коэффициент за изломы участка (протокол 16.09.2026 п. 9): вершины внутри линии и, если участок начинается
         * в техническом узле, излом к входящему участку. Изломы в камерах и врезках не считаются.
         */
        double kTurn(Coordinate[] coords, NewSegment incoming) {
            double k = 1;
            Coordinate[] before = incoming == null ? null : incoming.getGeometry().getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate prev = i > 0 ? coords[i - 1] : before == null ? null : before[before.length - 2];
                if (prev == null) {
                    continue;
                }
                double deflection = TreeBuilder.deflectionDeg(prev, coords[i], coords[i + 1]);
                if (deflection >= MIN_TURN_DEG) {
                    k = Math.max(k, rules.kTurn(deflection));
                }
            }
            return k;
        }

        /** Объекты, чьи зоны валидатор засчитает специальному участку: перекрытие больше ZONE_OVERLAP_M. */
        List<SpecialObjects.Special> crossed(int edge, double from, double to) {
            List<SpecialObjects.Special> result = new ArrayList<>();
            for (Zone zone : zones) {
                if (zone.edge == edge && zone.overlap(from, to) > ZONE_OVERLAP_M && !result.contains(zone.special)) {
                    result.add(zone.special);
                }
            }
            return result;
        }

        /**
         * Отступ обычного участка от объектов со специальным проходом, как в правиле special валидатора: объекты,
         * через которые проходит смежный специальный участок, и сеть у врезки, с которой участок начинается,
         * не проверяются. Маршрут проверен по рёбрам графа целиком, а здесь проверяется каждый участок.
         */
        void checkClearance() {
            for (NewSegment segment : segments) {
                if (SPECIAL.equals(segment.getLayingMethod())) {
                    continue;
                }
                Set<SpecialObjects.Special> exempt = new HashSet<>(exemptByNode.getOrDefault(segment.getStartNodeId(), Set.of()));
                exempt.addAll(exemptByNode.getOrDefault(segment.getEndNodeId(), Set.of()));
                boolean fromTie = segment.getStartNodeId().startsWith(prefix + "tie_");
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
         * Форма участков: внутри нет вершин с отклонением меньше 3° и подотрезков короче метра, кроме первого
         * и последнего подотрезка у узла, общего со специальным участком.
         */
        /**
         * Узлы разных участков не ближе NODE_APART_M, а участки не касаются вне общих узлов, как считает валидатор
         * (узлы в 0,05 м склеиваются, касание — 0,001 м за вырезом 0,15 м у общего узла), с запасом на округление.
         * Иначе ветка, ушедшая от развилки почти назад вдоль ствола, давала склеенные узлы, цикл и касание.
         * Несколько врезок в одну существующую камеру стоят в одной точке законно.
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
                    if (!isTie(id) || !isTie(other)) {
                        throw new IllegalStateException("Узлы " + id + " и " + other + " ближе "
                                + NODE_APART_M + " м: валидатор считает их одним узлом");
                    }
                    group.put(other, group.get(id));
                }
            }
            for (NewSegment segment : segments) {
                Coordinate[] coords = segment.getGeometry().getCoordinates();
                if (SPECIAL.equals(segment.getLayingMethod()) && segment.getGeometry().getLength() <= MIN_SPECIAL_M) {
                    throw new IllegalStateException("Специальный участок " + segment.getId() + " короче " + MIN_SPECIAL_M
                            + " м: валидатор не видит у него перехода");
                }
                for (int i = 0; i + 1 < coords.length; i++) {
                    for (int j = i + 2; j + 1 < coords.length; j++) {
                        if (new LineSegment(coords[i], coords[i + 1]).distance(new LineSegment(coords[j], coords[j + 1])) <= TOUCH_APART_M) {
                            throw new IllegalStateException("Участок " + segment.getId() + " касается сам себя");
                        }
                    }
                }
            }
            // обычная и специальная ветки одного узла под острым углом: зона специальной, раздутая валидатором
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
                    // зона спецперехода у валидатора раздута на 0,05 м: обычный участок рядом со специальным другой
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

        boolean isTie(String nodeId) {
            return nodeId.startsWith(prefix + "tie_");
        }

        void checkShape() {
            Set<String> specialNodes = new HashSet<>();
            for (NewSegment segment : segments) {
                if (SPECIAL.equals(segment.getLayingMethod())) {
                    specialNodes.add(segment.getStartNodeId());
                    specialNodes.add(segment.getEndNodeId());
                }
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
                    if (i > 0 && TreeBuilder.deflectionDeg(coords[i - 1], coords[i], coords[i + 1]) < MIN_TURN_DEG) {
                        throw new IllegalStateException("Участок " + segment.getId() + ": излом меньше 3°");
                    }
                }
            }
        }

        /** Правило поворотов: на пути от врезки до точки подключения не больше turnLimit(k) поворотов. */
        void checkTurns() {
            Map<String, Coordinate> tiePoint = new HashMap<>();
            for (List<Tree> unit : units.values()) {
                tiePoint.put(nodeIds.get(unit.get(0).root.key), unit.get(0).root.point);
            }
            for (Map.Entry<Integer, String> entry : tieIdByEdge.entrySet()) {
                tiePoint.put(entry.getValue(), edges.get(entry.getKey()).source.from.point);
            }
            for (Edge edge : edges) {
                if (edge.source.to.kind != Tree.Kind.CONNECTION) {
                    continue;
                }
                List<Coordinate> coords = new ArrayList<>();
                String node = nodeIds.get(edge.to());
                while (!tiePoint.containsKey(node)) {
                    NewSegment segment = incomingByNode.get(node);
                    Coordinate[] part = segment.getGeometry().getCoordinates();
                    for (int i = part.length - 1; i >= (tiePoint.containsKey(segment.getStartNodeId()) ? 0 : 1); i--) {
                        coords.add(part[i]);
                    }
                    node = segment.getStartNodeId();
                }
                int turns = TurnRule.turns(coords);
                int allowed = turnRule.allowed(tiePoint.get(node), edge.source.to.point);
                if (turns > allowed) {
                    throw new IllegalStateException("На пути к " + edge.source.to.connection.getId() + " поворотов "
                            + turns + ", допустимо " + allowed);
                }
            }
        }

        /** Врезки, камеры врезок в трубу, реконструкция существующей сети и камер врезки. */
        ReconstructionResult tieIns(List<ChamberReconstruction> chamberReconstructions) {
            List<TieInLoad> loads = new ArrayList<>();
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                TieCandidate tie = unit.getValue().get(0).tie;
                loads.add(new TieInLoad(unit.getKey(), tie.getExistingObjectId(), tie.getExistingObjectType(),
                        tie.getPoint(), flowByUnit.get(unit.getKey())));
            }
            ReconstructionResult recon = ReconstructionCalculator.calculate(input, rules, loads);
            int chamberNo = chambers.size();
            for (Map.Entry<String, List<Tree>> unit : units.entrySet()) {
                TieCandidate tie = unit.getValue().get(0).tie;
                String tieId = nodeIds.get(unit.getKey());
                // ТП §10.2: required_diameter врезки — диаметр новой сети в точке врезки, то есть участков, которые
                // в ней начинаются; реконструкция трубы и камеры на него не влияет (пример 10.8: 150 → 200, труба 250).
                if (tie.isChamber()) {
                    List<String> rayIds = new ArrayList<>();
                    for (int i = 0; i < edges.size(); i++) {
                        if (edges.get(i).from().equals(unit.getKey())) {
                            rayIds.add(tieIdByEdge.get(i));
                        }
                    }
                    int maxNew = rayIds.stream().mapToInt(maxDnByNode::get).max().orElseThrow();
                    int required = recon.chamberRequiredDiameter(tie.getExistingObjectId(), maxNew);
                    if (required > tie.getExistingDiameter()) {
                        chamberReconstructions.add(new ChamberReconstruction(
                                prefix + "chrecon_" + (chamberReconstructions.size() + 1), variantId, tie.getPoint(),
                                tie.getExistingObjectId(), tie.getExistingDiameter(), required, costs.chamberCost(required)));
                    }
                    for (String rayId : rayIds) {
                        tieIns.add(new TieIn(rayId, variantId, tie.getPoint(), tie.getExistingObjectId(),
                                tie.getExistingObjectType(), tie.getExistingDiameter(), maxDnByNode.get(rayId),
                                costs.tieInCost()));
                    }
                } else {
                    int maxNew = maxDnByNode.get(tieId);
                    int dn = Math.max(maxNew, recon.getRequiredDiameterByTieIn().get(unit.getKey()));
                    chambers.add(new NewChamber(prefix + "ch_" + ++chamberNo, variantId, tie.getPoint(), dn,
                            costs.chamberCost(dn)));
                    tieIns.add(new TieIn(tieId, variantId, tie.getPoint(), tie.getExistingObjectId(),
                            tie.getExistingObjectType(), tie.getExistingDiameter(), maxNew, costs.tieInCost()));
                }
            }
            return recon;
        }

        /** Специальные зоны всех объектов по трассе варианта. */
        List<Zone> zones() {
            List<Zone> result = new ArrayList<>();
            if (edges.isEmpty()) {
                return result;
            }
            Envelope trace = new Envelope();
            for (Edge edge : edges) {
                trace.expandToInclude(edge.source.line.getEnvelopeInternal());
            }
            for (SpecialObjects.Special special : specials.all) {
                Envelope envelope = (special.polygon ? special.buffered : special.geometry).getEnvelopeInternal();
                if (!envelope.intersects(trace)) {
                    continue;
                }
                if (special.polygon) {
                    polygonZone(special, envelope, result);
                } else {
                    lineZone(special, envelope, result);
                }
            }
            return result;
        }

        /** Связные части трассы в буфере полигона, которые пересекают сам полигон. */
        void polygonZone(SpecialObjects.Special special, Envelope envelope, List<Zone> result) {
            List<Zone> pieces = new ArrayList<>();
            List<Geometry> parts = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                LineString line = edges.get(i).source.line;
                if (!line.getEnvelopeInternal().intersects(envelope)) {
                    continue;
                }
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
            for (int i = 0; i < edges.size(); i++) {
                LineString line = edges.get(i).source.line;
                if (!line.getEnvelopeInternal().intersects(envelope) || !special.crossedBy(line)) {
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

        /** Зона линии продолжается через узел во все ветви, как в валидаторе. */
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
         * Интервал режется там, где меняется наибольший Kспец покрывающих зон (CONSTRAINTS §5: смена коэффициента —
         * технический узел), и сливается с соседом только при равном Kспец. При наложении зон коэффициент один,
         * наибольший, без перемножения (§18).
         */
        List<List<double[]>> mergedZones() {
            List<List<double[]>> result = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                List<double[]> raw = new ArrayList<>();
                for (Zone zone : zones) {
                    if (zone.edge == i && zone.to > zone.from) {
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

    private static int dnAt(List<SizedPiece> pieces, double at) {
        for (SizedPiece piece : pieces) {
            if (piece.getFromM() <= at && at <= piece.getToM()) {
                return piece.getDn();
            }
        }
        throw new IllegalStateException("Нет диаметра для точки " + at + " м ребра " + pieces.get(0).getEdgeId());
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
