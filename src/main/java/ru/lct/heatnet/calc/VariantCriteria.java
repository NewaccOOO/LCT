package ru.lct.heatnet.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

/**
 * Дополнительные критерии варианта сверх формулы S: какие объекты пересечены спецпереходами, сколько поворотов и
 * самый крутой из них, сколько стоит надбавка за спецпереходы, удельная стоимость подключения. Идут в сводку API и
 * в файл рядом с выходом CLI; в выходной GeoJSON не пишутся.
 */
public final class VariantCriteria {
    private static final Logger log = LoggerFactory.getLogger(VariantCriteria.class);
    private static final double MIN_TURN_DEG = 3;
    private static final String SPECIAL = "special";
    private static final String HEAT_NETWORK = "heat_network";
    private static final String OKS_EXISTING = "oks_existing";
    // ponytail: замкнутость ищется среди зон в этом радиусе; кольцо шире даст причину no_route вместо enclosed
    private static final double ENCLOSURE_RADIUS_M = 2000;
    /** Больше стольких причин на вариант не считается: на городе неподключённых десятки тысяч, причина на каждую — секунды. */
    private static final int MAX_REASONS = 1000;
    /** Зоны ближе этого считаются касающимися, точка ближе этого к контуру — лежащей на нём: запас на округления. */
    private static final double TOUCH_EPS_M = 1e-3;

    private final InputData input;
    private final Rules rules;
    private final STRtree crossable = new STRtree();
    /** Существующая сеть: точка дальше предельной длины наибольшего ДУ от неё недостижима при любом диаметре. */
    private final STRtree network = new STRtree();
    private final double reachM;
    /** Рамка сети: точка вне рамки с запасом reachM дальше reachM без запроса к индексу. */
    private final Envelope networkArea = new Envelope();
    /** ОКС с точкой не дальше reachM от сети; остальные с точкой «дальше досягаемости» (на городе их 3 млн на вариант). */
    private Set<String> within;
    private final Map<String, Map<String, Object>> reasons = new HashMap<>();
    /** Зоны запрета по объекту и отступу для причин неподключения. */
    private final Map<String, Geometry> zoneCache = new ConcurrentHashMap<>();
    // точка подключения по ОКС: строится при первой причине, линейный поиск на городе был O(n²)
    private Map<String, ConnectionPoint> connectionByOks;
    /** Зоны запрета по рамке: здания и запретные ограничения; строится при первой причине. */
    private STRtree forbidIndex;

    /** Объект, который новая сеть может пересечь спецпереходом. */
    private static final class Crossable {
        final String type;
        final PreparedGeometry geometry;

        Crossable(String type, Geometry geometry) {
            this.type = type;
            this.geometry = PreparedGeometryFactory.prepare(geometry);
        }
    }

    public VariantCriteria(InputData input, Rules rules) {
        this.input = input;
        this.rules = rules;
        for (Restriction restriction : input.getRestrictions()) {
            Geometry geometry = restriction.getGeometry();
            if (!rules.restriction(restriction.getType()).forbid() && geometry.getDimension() > 0) {
                crossable.insert(geometry.getEnvelopeInternal(), new Crossable(restriction.getType(), geometry));
            }
        }
        for (NetworkSegment segment : input.getSegments()) {
            crossable.insert(segment.getGeometry().getEnvelopeInternal(), new Crossable(HEAT_NETWORK, segment.getGeometry()));
            network.insert(segment.getGeometry().getEnvelopeInternal(), segment.getGeometry());
            networkArea.expandToInclude(segment.getGeometry().getEnvelopeInternal());
        }
        input.getChambers().forEach(chamber -> {
            network.insert(chamber.getGeometry().getEnvelopeInternal(), chamber.getGeometry());
            networkArea.expandToInclude(chamber.getGeometry().getEnvelopeInternal());
        });
        crossable.build();
        network.build();
        reachM = rules.diameters().get(rules.diameters().size() - 1).getMaxLengthM();
    }

    /** Критерии варианта в порядке вывода; деньги и длины до копеек и сантиметров. */
    public Map<String, Object> of(Variant variant) {
        VariantSummary summary = variant.getSummary();
        Set<Crossable> crossed = new HashSet<>();
        double specialLength = 0;
        int specialSegments = 0;
        double surcharge = 0;
        for (NewSegment segment : variant.getSegments()) {
            surcharge += segment.getCost()
                    - CostCalculator.round2(segment.getLength()) * rules.diameter(segment.getDiameter()).getNewRubM();
            if (!SPECIAL.equals(segment.getLayingMethod())) {
                continue;
            }
            specialSegments++;
            specialLength += segment.getLength();
            for (Object item : crossable.query(segment.getGeometry().getEnvelopeInternal())) {
                Crossable object = (Crossable) item;
                if (object.geometry.intersects(segment.getGeometry())) {
                    crossed.add(object);
                }
            }
        }
        Map<String, Integer> crossedByType = new TreeMap<>();
        for (Crossable object : crossed) {
            crossedByType.merge(object.type, 1, Integer::sum);
        }

        double[] turns = turns(variant);
        // проход по 3 млн ОКС города параллельно; расход складывается по порядку входа, как раньше
        Set<String> unconnected = new HashSet<>(summary.getUnconnectedOksIds());
        List<FutureOks> connectedOks = input.getFutureOks().parallelStream()
                .filter(oks -> !unconnected.contains(oks.getId())).collect(Collectors.toList());
        int connected = connectedOks.size();
        double connectedFlow = 0;
        for (FutureOks oks : connectedOks) {
            connectedFlow += oks.getFlowTph();
        }
        double costWithoutPenalty = summary.getCalculatedCost() - summary.getUnconnectedPenalty();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected_oks", connected);
        result.put("connected_flow_tph", scaled(connectedFlow, 3));
        List<String> explained = new ArrayList<>();
        int beyondReach = 0;
        int withoutReason = 0;
        for (String oksId : summary.getUnconnectedOksIds()) {
            if (beyondReach(oksId)) {
                beyondReach++;
            } else if (explained.size() < MAX_REASONS) {
                explained.add(oksId);
            } else {
                withoutReason++;
            }
        }
        // геометрии своих зданий и расходы одним проходом по ОКС: поиск по id на каждую причину на городе шёл по
        // 3 млн записей; расход — у первого ОКС с этим id, геометрия — у последнего, как было
        Set<String> wanted = new HashSet<>(explained);
        wanted.removeAll(reasons.keySet());
        Map<String, Geometry> own = new HashMap<>();
        Map<String, Double> flows = new HashMap<>();
        for (FutureOks oks : wanted.isEmpty() ? List.<FutureOks>of() : input.getFutureOks()) {
            if (wanted.contains(oks.getId())) {
                own.put(oks.getId(), oks.getGeometry());
                flows.putIfAbsent(oks.getId(), oks.getFlowTph());
            }
        }
        List<Map<String, Object>> unconnectedReasons = new ArrayList<>();
        long reasoning = System.nanoTime();
        // причины независимы: индексы после build только читаются, кэш зон потокобезопасный
        index();
        forbidIndex();
        Map<String, Map<String, Object>> fresh = new ConcurrentHashMap<>();
        wanted.parallelStream().forEach(id -> fresh.put(id, reason(id, own.get(id), flows.getOrDefault(id, 0.0))));
        reasons.putAll(fresh);
        for (String oksId : explained) {
            unconnectedReasons.add(reasons.get(oksId));
        }
        log.info("criteria: variant {} beyond={} reasons={} new={} elapsed={}s", variant.getId(), beyondReach, explained.size(),
                wanted.size(), (System.nanoTime() - reasoning) / 1_000_000_000L);
        result.put("unconnected_reasons", unconnectedReasons);
        if (beyondReach > 0) {
            // на городе таких миллионы: причина у них одна, в списке их нет
            result.put("beyond_reach_oks", beyondReach);
        }
        if (withoutReason > 0) {
            result.put("unconnected_without_reason", withoutReason);
        }
        result.put("existing_chamber_tie_ins", summary.getExistingChamberTieInCount());
        result.put("new_chambers", variant.getChambers().size());
        result.put("technical_nodes", variant.getNodes().size());
        result.put("special_segments", specialSegments);
        result.put("special_length_m", scaled(specialLength, 2));
        result.put("crossed_objects", crossedByType);
        result.put("turns", (int) turns[0]);
        result.put("max_turn_deg", scaled(turns[1], 1));
        result.put("surcharge_cost", scaled(surcharge, 2));
        result.put("cost_per_oks", connected > 0 ? scaled(costWithoutPenalty / connected, 2) : null);
        result.put("cost_per_tph", connectedFlow > 0 ? scaled(costWithoutPenalty / connectedFlow, 2) : null);
        return result;
    }

    /**
     * Повороты новой сети: вершины внутри участков и стыки участков в технических узлах с отклонением от 3°.
     * В камерах и точках подключения отвода нет. Второй элемент — самый крутой поворот в градусах.
     */
    private double[] turns(Variant variant) {
        Set<String> technical = new HashSet<>();
        for (TechnicalNode node : variant.getNodes()) {
            technical.add(node.getId());
        }
        Map<String, NewSegment> incoming = new HashMap<>();
        for (NewSegment segment : variant.getSegments()) {
            incoming.put(segment.getEndNodeId(), segment);
        }
        int all = 0;
        double steepest = 0;
        for (NewSegment segment : variant.getSegments()) {
            Coordinate[] coords = segment.getGeometry().getCoordinates();
            List<Double> bends = new ArrayList<>();
            for (int i = 1; i + 1 < coords.length; i++) {
                bends.add(deflection(coords[i - 1], coords[i], coords[i + 1]));
            }
            NewSegment before = incoming.get(segment.getStartNodeId());
            if (technical.contains(segment.getStartNodeId()) && before != null && coords.length > 1) {
                Coordinate[] prev = before.getGeometry().getCoordinates();
                bends.add(deflection(prev[prev.length - 2], coords[0], coords[1]));
            }
            for (double bend : bends) {
                if (bend >= MIN_TURN_DEG) {
                    all++;
                    steepest = Math.max(steepest, bend);
                }
            }
        }
        return new double[] {all, steepest};
    }

    /** Точка подключения дальше предельной длины наибольшего ДУ от сети: ни один путь не уложится в предел. */
    private boolean beyondReach(String oksId) {
        if (within == null) {
            index();
            within = ConcurrentHashMap.newKeySet();
            Envelope area = new Envelope(networkArea);
            area.expandBy(reachM);
            // расстояния независимы, индекс после build только читается; хранятся ближние, их на городе 64 тыс.
            connectionByOks.values().parallelStream()
                    .filter(c -> area.contains(c.getGeometry().getCoordinate()) && networkDistance(c.getGeometry()) <= reachM)
                    .forEach(c -> within.add(c.getOksId()));
        }
        return !network.isEmpty() && connectionByOks.containsKey(oksId) && !within.contains(oksId);
    }

    private double networkDistance(Point point) {
        Object nearest = network.nearestNeighbour(point.getEnvelopeInternal(), point,
                (a, b) -> ((Geometry) a.getItem()).distance((Geometry) b.getItem()));
        return ((Geometry) nearest).distance(point);
    }

    private STRtree forbidIndex() {
        if (forbidIndex == null) {
            forbidIndex = new STRtree();
            for (ExistingOks oks : input.getExistingOks()) {
                forbidIndex.insert(oks.getGeometry().getEnvelopeInternal(), oks);
            }
            for (Restriction restriction : input.getRestrictions()) {
                RestrictionRule rule = rules.restriction(restriction.getType());
                if (rule.forbid() || restriction.getGeometry().getDimension() == 0) {
                    forbidIndex.insert(restriction.getGeometry().getEnvelopeInternal(), restriction);
                }
            }
            forbidIndex.build();
        }
        return forbidIndex;
    }

    private void index() {
        if (connectionByOks == null) {
            connectionByOks = new HashMap<>();
            input.getConnectionPoints().forEach(c -> connectionByOks.putIfAbsent(c.getOksId(), c));
        }
    }

    /**
     * Почему у ОКС нет маршрута. Причина ищется после расчёта по тем же зонам запрета с отступами, что у поиска
     * маршрута при диаметре по расходу ОКС: точка внутри зоны или в кольце зон. Иначе зоны точку не замыкают, и
     * трассу не дали предельная длина, правило поворотов, углы пересечения или соседние деревья.
     */
    private Map<String, Object> reason(String oksId, Geometry own, double flow) {
        index();
        ConnectionPoint connection = connectionByOks.get(oksId);
        if (connection == null) {
            return reason(oksId, "no_connection_point", "у ОКС нет точки подключения", List.of());
        }
        List<Diameter> diameters = rules.diameters();
        if (flow > diameters.get(diameters.size() - 1).getCapacityTph()) {
            return reason(oksId, "flow_exceeds_capacity", "расход больше пропускной способности наибольшего диаметра",
                    List.of());
        }
        Diameter diameter = rules.diameterFor(flow);
        Point point = connection.getGeometry();
        Envelope around = new Envelope(point.getCoordinate());
        around.expandBy(ENCLOSURE_RADIUS_M);
        Map<String, Geometry> zones = new LinkedHashMap<>();
        double halfWidth = diameter.getWidthM() / 2;
        // своё здание точке не мешает: к ней ведёт финальный прямой участок без отступа (приложение 18.09, п. 2.2)
        for (Object item : forbidIndex().query(around)) {
            if (item instanceof ExistingOks) {
                ExistingOks oks = (ExistingOks) item;
                if (oks.getGeometry() == own) {
                    continue;
                }
                double distance = rules.restriction(OKS_EXISTING).clearanceM(diameter.getDn()) + halfWidth;
                addZone(zones, oks.getId(), oks.getGeometry(), distance, around);
            } else {
                Restriction restriction = (Restriction) item;
                RestrictionRule rule = rules.restriction(restriction.getType());
                Geometry geometry = restriction.getGeometry();
                double distance = rule.clearanceM(diameter.getDn()) + halfWidth;
                if (geometry.getDimension() == 1 && rule.getHalfWidthM() != null) {
                    distance += rule.getHalfWidthM();
                }
                addZone(zones, restriction.getId(), geometry, distance, around);
            }
        }

        List<String> inside = new ArrayList<>();
        zones.forEach((id, zone) -> {
            if (zone.covers(point)) {
                inside.add(id);
            }
        });
        if (!inside.isEmpty()) {
            return reason(oksId, "inside_forbidden_zone", "точка подключения внутри запретной зоны с учётом отступа: "
                    + "финальный прямой участок из своего здания упирается в чужую зону", inside);
        }
        if (!zones.isEmpty() && !open(point, zones.values())) {
            Geometry union = UnaryUnionOp.union(zones.values());
            for (int i = 0; i < union.getNumGeometries(); i++) {
                Geometry part = union.getGeometryN(i);
                if (part instanceof Polygon && part.getFactory().createPolygon(((Polygon) part).getExteriorRing().getCoordinates()).contains(point)) {
                    List<String> ring = new ArrayList<>();
                    zones.forEach((id, zone) -> {
                        if (zone.intersects(part)) {
                            ring.add(id);
                        }
                    });
                    return reason(oksId, "enclosed", "точка подключения окружена запретными зонами, обхода нет", ring);
                }
            }
        }
        return reason(oksId, "no_route", "запретные зоны точку не замыкают, но допустимая трасса не найдена: "
                + "мешают предельная длина, правило поворотов, углы пересечения или соседние трассы", List.of());
    }

    /**
     * Зоны точку заведомо не окружают. Кольцо зон вокруг точки пересекает любой луч из неё, поэтому проверяются только
     * группы касающихся зон у луча вправо: группа не окружает точку, если её рамка точку не содержит или её объединение —
     * один полигон, внешний контур которого точку не содержит. Объединение всех зон в радиусе 2 км на городе — 0,2 с
     * на точку, группы у луча — миллисекунды. При сомнении false, и решает полное объединение, как раньше.
     */
    static boolean open(Point point, Collection<Geometry> zones) {
        List<Geometry> list = new ArrayList<>(zones);
        Envelope[] grown = new Envelope[list.size()];
        STRtree byEnvelope = new STRtree();
        double right = point.getX();
        for (int i = 0; i < list.size(); i++) {
            grown[i] = new Envelope(list.get(i).getEnvelopeInternal());
            grown[i].expandBy(TOUCH_EPS_M);
            byEnvelope.insert(grown[i], i);
            right = Math.max(right, grown[i].getMaxX());
        }
        Coordinate at = point.getCoordinate();
        LineString ray = point.getFactory().createLineString(new Coordinate[] {at, new Coordinate(right + 1, at.y)});
        boolean[] grouped = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (grouped[i] || !grown[i].intersects(ray.getEnvelopeInternal())
                    || !list.get(i).isWithinDistance(ray, TOUCH_EPS_M)) {
                continue;
            }
            List<Geometry> group = new ArrayList<>();
            Envelope envelope = new Envelope();
            Deque<Integer> queue = new ArrayDeque<>(List.of(i));
            grouped[i] = true;
            while (!queue.isEmpty()) {
                int a = queue.poll();
                group.add(list.get(a));
                envelope.expandToInclude(grown[a]);
                for (Object item : byEnvelope.query(grown[a])) {
                    int b = (Integer) item;
                    if (!grouped[b] && list.get(a).isWithinDistance(list.get(b), TOUCH_EPS_M)) {
                        grouped[b] = true;
                        queue.add(b);
                    }
                }
            }
            if (!envelope.contains(at)) {
                continue;
            }
            // несколько полигонов у касающихся зон — касание в точке, его объединение всех зон может решить иначе
            Geometry union = group.size() == 1 ? group.get(0) : UnaryUnionOp.union(group);
            if (!(union instanceof Polygon)) {
                return false;
            }
            LinearRing shell = ((Polygon) union).getExteriorRing();
            if (shell.isWithinDistance(point, TOUCH_EPS_M)
                    || union.getFactory().createPolygon(shell.getCoordinates()).contains(point)) {
                return false;
            }
        }
        return true;
    }

    private void addZone(Map<String, Geometry> zones, String id, Geometry geometry, double distance, Envelope around) {
        Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
        envelope.expandBy(distance);
        if (envelope.intersects(around)) {
            // соседние точки без маршрута делят одни и те же здания вокруг: зона строится один раз на объект и отступ
            zones.put(id, zoneCache.computeIfAbsent(id + "@" + distance, key -> geometry.buffer(distance)));
        }
    }

    private static Map<String, Object> reason(String oksId, String code, String message, List<String> objectIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oks_id", oksId);
        result.put("reason", code);
        result.put("message", message);
        result.put("object_ids", objectIds);
        return result;
    }

    private static double deflection(Coordinate a, Coordinate b, Coordinate c) {
        if (a.distance(b) == 0 || b.distance(c) == 0) {
            return 0;
        }
        return 180 - Math.toDegrees(Angle.angleBetween(a, b, c));
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
