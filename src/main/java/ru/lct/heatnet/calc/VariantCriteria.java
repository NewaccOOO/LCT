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
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
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

    private final InputData input;
    private final Rules rules;
    private final STRtree crossable = new STRtree();

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
