package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.rules.Rules;

public final class ReconstructionCalculator {
    private static final String HEAT_NETWORK = "heat_network";
    private static final String HEAT_CHAMBER = "heat_chamber";
    private static final double EPS = 1e-9;

    private ReconstructionCalculator() {
    }

    /** Прокидывает добавленный расход врезок к источнику и находит части существующих участков под больший диаметр. */
    public static ReconstructionResult calculate(InputData input, Rules rules, List<TieInLoad> tieIns) {
        Map<String, NetworkSegment> segments = new HashMap<>();
        for (NetworkSegment segment : input.getSegments()) {
            segments.put(segment.getId(), segment);
        }
        Map<String, Chamber> chambers = new HashMap<>();
        for (Chamber chamber : input.getChambers()) {
            chambers.put(chamber.getId(), chamber);
        }
        Map<String, Double> wholeAddedBySegment = new HashMap<>();
        Map<String, List<double[]>> tieInsInsideBySegment = new HashMap<>();
        Map<String, double[]> positionByTieIn = new HashMap<>();
        Map<String, Integer> existingDiameterByTieIn = new LinkedHashMap<>();
        List<String> tieInChamberIds = new ArrayList<>();
        for (TieInLoad tieIn : tieIns) {
            String next;
            if (HEAT_NETWORK.equals(tieIn.getExistingObjectType())) {
                NetworkSegment segment = segments.get(tieIn.getExistingObjectId());
                if (segment == null) {
                    throw new IllegalArgumentException("Врезка " + tieIn.getKey() + ": нет участка "
                            + tieIn.getExistingObjectId());
                }
                LineString line = segment.getGeometry();
                double index = new LengthIndexedLine(line).project(tieIn.getPoint().getCoordinate());
                boolean atStart = upstreamAtStart(segment, input, segments, chambers);
                double position = atStart ? index : line.getLength() - index;
                double[] load = {position, tieIn.getAddedFlowTph()};
                tieInsInsideBySegment.computeIfAbsent(segment.getId(), id -> new ArrayList<>()).add(load);
                positionByTieIn.put(tieIn.getKey(), load);
                existingDiameterByTieIn.put(tieIn.getKey(), segment.getDiameter());
                next = segment.getUpstreamId();
            } else if (HEAT_CHAMBER.equals(tieIn.getExistingObjectType())) {
                Chamber chamber = chambers.get(tieIn.getExistingObjectId());
                if (chamber == null) {
                    throw new IllegalArgumentException("Врезка " + tieIn.getKey() + ": нет камеры "
                            + tieIn.getExistingObjectId());
                }
                existingDiameterByTieIn.put(tieIn.getKey(), chamber.getDiameter());
                if (!tieInChamberIds.contains(chamber.getId())) {
                    tieInChamberIds.add(chamber.getId());
                }
                next = chamber.getUpstreamId();
            } else {
                throw new IllegalArgumentException("Врезка " + tieIn.getKey() + ": неизвестный тип объекта "
                        + tieIn.getExistingObjectType());
            }
            Set<String> seen = new HashSet<>();
            while (!input.getSource().getId().equals(next)) {
                if (next == null || !seen.add(next)) {
                    throw new IllegalArgumentException("Врезка " + tieIn.getKey()
                            + ": цепочка upstream_object_id не доходит до источника");
                }
                NetworkSegment segment = segments.get(next);
                Chamber chamber = chambers.get(next);
                if (segment != null) {
                    wholeAddedBySegment.merge(next, tieIn.getAddedFlowTph(), Double::sum);
                    next = segment.getUpstreamId();
                } else if (chamber != null) {
                    next = chamber.getUpstreamId();
                } else {
                    throw new IllegalArgumentException("Врезка " + tieIn.getKey() + ": объект " + next
                            + " из цепочки upstream_object_id не найден");
                }
            }
        }

        List<ReconPart> parts = new ArrayList<>();
        Map<String, Integer> requiredDiameterBySegment = new HashMap<>();
        for (NetworkSegment segment : input.getSegments()) {
            double whole = wholeAddedBySegment.getOrDefault(segment.getId(), 0.0);
            List<double[]> inside = tieInsInsideBySegment.getOrDefault(segment.getId(), List.of());
            if (whole == 0 && inside.isEmpty()) {
                continue;
            }
            LineString line = segment.getGeometry();
            TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, line.getLength()));
            for (double[] load : inside) {
                cuts.add(load[0]);
            }
            boolean atStart = upstreamAtStart(segment, input, segments, chambers);
            Double from = null;
            for (double to : cuts) {
                if (from != null && to - from > EPS) {
                    double added = whole + flowFrom(inside, to);
                    double calculated = segment.getFlowTph() + added;
                    int required = rules.diameterFor(calculated).getDn();
                    if (added > 0 && required > segment.getDiameter()) {
                        LineString geometry = atStart ? subLine(line, from, to) : reversedSubLine(line, from, to);
                        double length = CostCalculator.round2(geometry.getLength());
                        double cost = CostCalculator.round2(length * rules.diameter(required).getReconRubM());
                        parts.add(new ReconPart(geometry, segment.getId(), segment.getFlowTph(), added, calculated,
                                segment.getDiameter(), required, length, cost));
                        requiredDiameterBySegment.merge(segment.getId(), required, Math::max);
                    }
                }
                from = to;
            }
        }

        Map<String, Integer> requiredDiameterByTieIn = new LinkedHashMap<>();
        for (TieInLoad tieIn : tieIns) {
            double[] load = positionByTieIn.get(tieIn.getKey());
            if (load != null) {
                NetworkSegment segment = segments.get(tieIn.getExistingObjectId());
                double added = wholeAddedBySegment.getOrDefault(segment.getId(), 0.0)
                        + flowFrom(tieInsInsideBySegment.get(segment.getId()), load[0]);
                int required = rules.diameterFor(segment.getFlowTph() + added).getDn();
                requiredDiameterByTieIn.put(tieIn.getKey(), Math.max(segment.getDiameter(), required));
            }
        }
        return new ReconstructionResult(segments, chambers, rules, parts, requiredDiameterByTieIn,
                existingDiameterByTieIn, requiredDiameterBySegment, tieInChamberIds);
    }

    /** Сумма добавок врезок, стоящих не ближе position к концу участка у источника. */
    private static double flowFrom(List<double[]> inside, double position) {
        double flow = 0;
        for (double[] load : inside) {
            if (load[0] >= position - EPS) {
                flow += load[1];
            }
        }
        return flow;
    }

    /** Конец участка к источнику — ближайший к геометрии объекта upstream_object_id (A-9). */
    public static boolean upstreamAtStart(NetworkSegment segment, InputData input,
            Map<String, NetworkSegment> segments, Map<String, Chamber> chambers) {
        String upstreamId = segment.getUpstreamId();
        Geometry upstream;
        if (input.getSource().getId().equals(upstreamId)) {
            upstream = input.getSource().getGeometry();
        } else if (segments.containsKey(upstreamId)) {
            upstream = segments.get(upstreamId).getGeometry();
        } else if (chambers.containsKey(upstreamId)) {
            upstream = chambers.get(upstreamId).getGeometry();
        } else {
            throw new IllegalArgumentException("Участок " + segment.getId() + ": объект upstream_object_id "
                    + upstreamId + " не найден");
        }
        LineString line = segment.getGeometry();
        return line.getStartPoint().distance(upstream) <= line.getEndPoint().distance(upstream);
    }

    private static LineString subLine(LineString line, double from, double to) {
        return (LineString) new LengthIndexedLine(line).extractLine(from, to);
    }

    private static LineString reversedSubLine(LineString line, double from, double to) {
        double length = line.getLength();
        return (LineString) subLine(line, length - to, length - from).reverse();
    }
}
