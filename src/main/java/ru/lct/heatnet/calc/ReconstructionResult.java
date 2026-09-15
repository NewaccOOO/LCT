package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.rules.Rules;

public final class ReconstructionResult {
    /** Конец существующего участка примыкает к камере, если он не дальше этого расстояния (A-9). */
    private static final double ENDPOINT_TOLERANCE_M = 0.5;

    private final Map<String, NetworkSegment> segments;
    private final Map<String, Chamber> chambers;
    private final Rules rules;
    @Getter
    private final List<ReconPart> parts;
    /** Только врезки в трубу; для камеры — {@link #chamberRequiredDiameter}. */
    @Getter
    private final Map<String, Integer> requiredDiameterByTieIn;
    @Getter
    private final Map<String, Integer> existingDiameterByTieIn;
    private final Map<String, Integer> requiredDiameterBySegment;
    private final List<String> tieInChamberIds;

    ReconstructionResult(Map<String, NetworkSegment> segments, Map<String, Chamber> chambers, Rules rules,
            List<ReconPart> parts, Map<String, Integer> requiredDiameterByTieIn,
            Map<String, Integer> existingDiameterByTieIn, Map<String, Integer> requiredDiameterBySegment,
            List<String> tieInChamberIds) {
        this.segments = segments;
        this.chambers = chambers;
        this.rules = rules;
        this.parts = List.copyOf(parts);
        this.requiredDiameterByTieIn = Map.copyOf(requiredDiameterByTieIn);
        this.existingDiameterByTieIn = Map.copyOf(existingDiameterByTieIn);
        this.requiredDiameterBySegment = Map.copyOf(requiredDiameterBySegment);
        this.tieInChamberIds = List.copyOf(tieInChamberIds);
    }

    /** Диаметр существующего участка после реконструкции: наибольший из требуемых у его частей, иначе исходный. */
    public int diameterAfter(String segmentId) {
        NetworkSegment segment = segments.get(segmentId);
        if (segment == null) {
            throw new IllegalArgumentException("Участка " + segmentId + " нет во входных данных");
        }
        return requiredDiameterBySegment.getOrDefault(segmentId, segment.getDiameter());
    }

    /**
     * Наибольший диаметр примыкающих к камере участков: новых (maxNewDn) и существующих с диаметром той их части,
     * что касается камеры. Часть того же участка дальше от камеры, реконструированная из-за другой врезки, не в счёт.
     */
    public int chamberRequiredDiameter(String chamberId, int maxNewDn) {
        Chamber chamber = chamber(chamberId);
        int required = maxNewDn;
        for (NetworkSegment segment : segments.values()) {
            if (segment.getGeometry().getStartPoint().distance(chamber.getGeometry()) > ENDPOINT_TOLERANCE_M
                    && segment.getGeometry().getEndPoint().distance(chamber.getGeometry()) > ENDPOINT_TOLERANCE_M) {
                continue;
            }
            int dn = segment.getDiameter();
            for (ReconPart part : parts) {
                if (part.getExistingObjectId().equals(segment.getId())
                        && part.getGeometry().distance(chamber.getGeometry()) <= ENDPOINT_TOLERANCE_M) {
                    dn = Math.max(dn, part.getRequiredDiameter());
                }
            }
            required = Math.max(required, dn);
        }
        return required;
    }

    /** Реконструкции камер врезки, одна на камеру; maxNewDnByChamber — наибольший диаметр новых участков у камеры. */
    public List<ChamberRecon> chamberReconstructions(Map<String, Integer> maxNewDnByChamber) {
        List<ChamberRecon> result = new ArrayList<>();
        for (String chamberId : tieInChamberIds) {
            Integer maxNewDn = maxNewDnByChamber.get(chamberId);
            if (maxNewDn == null) {
                throw new IllegalArgumentException("Нет диаметра новых участков для камеры врезки " + chamberId);
            }
            Chamber chamber = chamber(chamberId);
            int required = chamberRequiredDiameter(chamberId, maxNewDn);
            if (required > chamber.getDiameter()) {
                result.add(new ChamberRecon(chamberId, chamber.getGeometry(), chamber.getDiameter(), required,
                        rules.chamberCost(required)));
            }
        }
        return result;
    }

    private Chamber chamber(String chamberId) {
        Chamber chamber = chambers.get(chamberId);
        if (chamber == null) {
            throw new IllegalArgumentException("Камеры " + chamberId + " нет во входных данных");
        }
        return chamber;
    }
}
