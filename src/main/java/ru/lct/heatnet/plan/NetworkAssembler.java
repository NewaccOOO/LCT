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
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
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

    private final InputData input;
    private final Rules rules;
    private final SpecialObjects specials;
    private final CostCalculator costs;
    private final DiameterPlanner planner;
    private final Map<String, Double> flowByOks = new HashMap<>();
    /** ID входа: выходные ID с ними не совпадают (правило schema). */
    private final Set<String> inputIds = new HashSet<>();
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
        return inputIds.stream().anyMatch(id -> id.startsWith(prefix));
    }

    /**
     * Вариант из деревьев; деревья с общей камерой врезки собираются одной врезкой. Бросает
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
            for (double at = 0; at <= edge.length + NEAR_STEP_M; at += NEAR_STEP_M) {
                double position = Math.min(at, edge.length);
                if (specials.near(indexed.extractPoint(position), dn)) {
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
            for (List<Tree> unit : units.values()) {
                nodeIds.put(unit.get(0).root.key, prefix + "tie_" + ++tieNo);
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
            cuts.put(0.0, nodeIds.get(edge.from()));
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
                    if (segment.getGeometry().distance(special.geometry) < need - DIST_EPS_M) {
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

        /** Правило поворотов: на пути от врезки до точки подключения не больше 3k + 4 поворотов. */
        void checkTurns() {
            Map<String, Coordinate> tiePoint = new HashMap<>();
            for (List<Tree> unit : units.values()) {
                tiePoint.put(nodeIds.get(unit.get(0).root.key), unit.get(0).root.point);
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
                int maxNew = maxDnByNode.get(tieId);
                if (tie.isChamber()) {
                    int required = recon.chamberRequiredDiameter(tie.getExistingObjectId(), maxNew);
                    if (required > tie.getExistingDiameter()) {
                        chamberReconstructions.add(new ChamberReconstruction(
                                prefix + "chrecon_" + (chamberReconstructions.size() + 1), variantId, tie.getPoint(),
                                tie.getExistingObjectId(), tie.getExistingDiameter(), required, costs.chamberCost(required)));
                    }
                } else {
                    int dn = Math.max(maxNew, recon.getRequiredDiameterByTieIn().get(unit.getKey()));
                    chambers.add(new NewChamber(prefix + "ch_" + ++chamberNo, variantId, tie.getPoint(), dn,
                            costs.chamberCost(dn)));
                }
                tieIns.add(new TieIn(tieId, variantId, tie.getPoint(), tie.getExistingObjectId(),
                        tie.getExistingObjectType(), tie.getExistingDiameter(), maxNew, costs.tieInCost()));
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
                if (!line.getEnvelopeInternal().intersects(envelope)) {
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

        /** Объединённые специальные интервалы по рёбрам, расширенные на ZONE_GROW_M; границы у концов ребра прижимаются к узлу. */
        List<List<double[]>> mergedZones() {
            List<List<double[]>> result = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                List<double[]> raw = new ArrayList<>();
                for (Zone zone : zones) {
                    if (zone.edge == i && zone.to > zone.from) {
                        raw.add(new double[] {Math.max(0, zone.from - ZONE_GROW_M), Math.min(edges.get(i).length, zone.to + ZONE_GROW_M)});
                    }
                }
                raw.sort(Comparator.comparingDouble(interval -> interval[0]));
                List<double[]> merged = new ArrayList<>();
                for (double[] interval : raw) {
                    double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                    if (last != null && interval[0] <= last[1] + MERGE_M) {
                        last[1] = Math.max(last[1], interval[1]);
                    } else {
                        merged.add(interval.clone());
                    }
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
