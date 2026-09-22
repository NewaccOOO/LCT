package ru.lct.heatnet.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
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
 * Дополнительные критерии варианта сверх формулы S: какие объекты пересечены спецпереходами, сколько поворотов
 * и нестандартных углов, сколько стоит надбавка за спецпереходы и углы, удельная стоимость подключения. Идут в
 * сводку API и в файл рядом с выходом CLI; в выходной GeoJSON не пишутся, пока организаторы не ответили, можно ли
 * расширять его атрибуты.
 */
public final class VariantCriteria {
    private static final double MIN_TURN_DEG = 3;
    private static final String SPECIAL = "special";
    private static final String HEAT_NETWORK = "heat_network";
    private static final String OKS_EXISTING = "oks_existing";
    // ponytail: замкнутость ищется среди зон в этом радиусе; кольцо шире даст причину no_route вместо enclosed
    private static final double ENCLOSURE_RADIUS_M = 2000;

    private final InputData input;
    private final Rules rules;
    private final STRtree crossable = new STRtree();
    private final Map<String, Map<String, Object>> reasons = new HashMap<>();
    private final Set<String> overCapacity;
    // точка подключения и расход по ОКС: строятся при первой причине, линейный поиск на городе был O(n²)
    private Map<String, ConnectionPoint> connectionByOks;
    private Map<String, Double> flowByOks;

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
        this(input, rules, Set.of());
    }

    /** overCapacity — ОКС сверх пропускной способности сети: причина у них одна, в критериях только их число. */
    public VariantCriteria(InputData input, Rules rules, Set<String> overCapacity) {
        this.input = input;
        this.rules = rules;
        this.overCapacity = overCapacity;
        for (Restriction restriction : input.getRestrictions()) {
            Geometry geometry = restriction.getGeometry();
            if (!rules.restriction(restriction.getType()).forbid() && geometry.getDimension() > 0) {
                crossable.insert(geometry.getEnvelopeInternal(), new Crossable(restriction.getType(), geometry));
            }
        }
        for (NetworkSegment segment : input.getSegments()) {
            crossable.insert(segment.getGeometry().getEnvelopeInternal(), new Crossable(HEAT_NETWORK, segment.getGeometry()));
        }
        crossable.build();
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

        int[] turns = turns(variant);
        Set<String> unconnected = new HashSet<>(summary.getUnconnectedOksIds());
        int connected = 0;
        double connectedFlow = 0;
        for (FutureOks oks : input.getFutureOks()) {
            if (!unconnected.contains(oks.getId())) {
                connected++;
                connectedFlow += oks.getFlowTph();
            }
        }
        double costWithoutPenalty = summary.getCalculatedCost() - summary.getUnconnectedPenalty();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected_oks", connected);
        result.put("connected_flow_tph", scaled(connectedFlow, 3));
        List<Map<String, Object>> unconnectedReasons = new ArrayList<>();
        for (String oksId : summary.getUnconnectedOksIds()) {
            if (!overCapacity.contains(oksId)) {
                unconnectedReasons.add(reasons.computeIfAbsent(oksId, this::reason));
            }
        }
        result.put("unconnected_reasons", unconnectedReasons);
        if (!overCapacity.isEmpty()) {
            result.put("over_capacity_oks", overCapacity.size());
        }
        result.put("tie_ins", variant.getTieIns().size());
        result.put("new_chambers", variant.getChambers().size());
        result.put("technical_nodes", variant.getNodes().size());
        result.put("chamber_reconstructions", variant.getChamberReconstructions().size());
        result.put("special_segments", specialSegments);
        result.put("special_length_m", scaled(specialLength, 2));
        result.put("crossed_objects", crossedByType);
        result.put("turns", turns[0]);
        result.put("nonstandard_turns", turns[1]);
        result.put("surcharge_cost", scaled(surcharge, 2));
        result.put("reconstruction_share", summary.getLength() > 0
                ? scaled(summary.getReconstructionLength() / summary.getLength(), 3) : scaled(0, 3));
        result.put("cost_per_oks", connected > 0 ? scaled(costWithoutPenalty / connected, 2) : null);
        result.put("cost_per_tph", connectedFlow > 0 ? scaled(costWithoutPenalty / connectedFlow, 2) : null);
        return result;
    }

    /**
     * Повороты новой сети: вершины внутри участков и стыки участков в технических узлах с отклонением от 3°.
     * В камерах, врезках и точках подключения отвода нет. Второй элемент — сколько из них не 45° и не 90°.
     */
    private int[] turns(Variant variant) {
        Set<String> technical = new HashSet<>();
        for (TechnicalNode node : variant.getNodes()) {
            technical.add(node.getId());
        }
        Map<String, NewSegment> incoming = new HashMap<>();
        for (NewSegment segment : variant.getSegments()) {
            incoming.put(segment.getEndNodeId(), segment);
        }
        int all = 0;
        int nonstandard = 0;
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
                    if (rules.kTurn(bend) > 1) {
                        nonstandard++;
                    }
                }
            }
        }
        return new int[] {all, nonstandard};
    }

    /**
     * Почему у ОКС нет маршрута. Причина ищется после расчёта по тем же зонам запрета с отступами, что у поиска
     * маршрута при диаметре по расходу ОКС: точка внутри зоны или в кольце зон. Иначе зоны точку не замыкают, и
     * трассу не дали предельная длина, правило поворотов, углы пересечения или соседние деревья.
     */
    private Map<String, Object> reason(String oksId) {
        if (connectionByOks == null) {
            connectionByOks = new HashMap<>();
            input.getConnectionPoints().forEach(c -> connectionByOks.putIfAbsent(c.getOksId(), c));
            flowByOks = new HashMap<>();
            input.getFutureOks().forEach(o -> flowByOks.putIfAbsent(o.getId(), o.getFlowTph()));
        }
        ConnectionPoint connection = connectionByOks.get(oksId);
        if (connection == null) {
            return reason(oksId, "no_connection_point", "у ОКС нет точки подключения", List.of());
        }
        double flow = flowByOks.getOrDefault(oksId, 0.0);
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
        for (ExistingOks oks : input.getExistingOks()) {
            double distance = rules.restriction(OKS_EXISTING).clearanceM(diameter.getDn()) + halfWidth;
            addZone(zones, oks.getId(), oks.getGeometry(), distance, around);
        }
        for (Restriction restriction : input.getRestrictions()) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            Geometry geometry = restriction.getGeometry();
            if (!rule.forbid() && geometry.getDimension() > 0) {
                continue;
            }
            double distance = rule.clearanceM(diameter.getDn()) + halfWidth;
            if (geometry.getDimension() == 1 && rule.getHalfWidthM() != null) {
                distance += rule.getHalfWidthM();
            }
            addZone(zones, restriction.getId(), geometry, distance, around);
        }

        List<String> inside = new ArrayList<>();
        zones.forEach((id, zone) -> {
            if (zone.covers(point)) {
                inside.add(id);
            }
        });
        if (!inside.isEmpty()) {
            return reason(oksId, "inside_forbidden_zone", "точка подключения внутри запретной зоны с учётом отступа", inside);
        }
        if (!zones.isEmpty()) {
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

    private static void addZone(Map<String, Geometry> zones, String id, Geometry geometry, double distance, Envelope around) {
        Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
        envelope.expandBy(distance);
        if (envelope.intersects(around)) {
            zones.put(id, geometry.buffer(distance));
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
