package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.List;
import ru.lct.heatnet.model.ChamberReconstruction;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Reconstruction;
import ru.lct.heatnet.model.TieIn;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Rules;

public final class CostCalculator {
    private final Rules rules;

    public CostCalculator(Rules rules) {
        this.rules = rules;
    }

    public static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    public double segmentCost(double length, int dn, double kSpecial) {
        return round2(round2(length) * rules.diameter(dn).getNewRubM() * kSpecial);
    }

    public double chamberCost(int dn) {
        return rules.chamberCost(dn);
    }

    public double tieInCost() {
        return rules.tieInCost();
    }

    public double penalty(List<FutureOks> unconnected) {
        double sum = 0;
        for (FutureOks oks : unconnected) {
            sum += rules.penalty(oks.getFlowTph());
        }
        return round2(sum);
    }

    /** Сводка варианта, rank = 0 до ранжирования. */
    public VariantSummary summary(String id, String variantId, List<NewSegment> segments, List<NewChamber> chambers,
            List<TieIn> tieIns, List<Reconstruction> reconstructions,
            List<ChamberReconstruction> chamberReconstructions, List<FutureOks> unconnected) {
        double constructionCost = 0;
        double newNetworkLength = 0;
        for (NewSegment segment : segments) {
            constructionCost += segment.getCost();
            newNetworkLength += segment.getLength();
        }
        double chamberConstructionCost = 0;
        for (NewChamber chamber : chambers) {
            chamberConstructionCost += chamber.getCost();
        }
        double tieInCost = 0;
        for (TieIn tieIn : tieIns) {
            tieInCost += tieIn.getCost();
        }
        double reconstructionCost = 0;
        double reconstructionLength = 0;
        for (Reconstruction reconstruction : reconstructions) {
            reconstructionCost += reconstruction.getCost();
            reconstructionLength += reconstruction.getLength();
        }
        double chamberReconstructionCost = 0;
        for (ChamberReconstruction chamber : chamberReconstructions) {
            chamberReconstructionCost += chamber.getCost();
        }
        List<String> unconnectedIds = new ArrayList<>();
        for (FutureOks oks : unconnected) {
            unconnectedIds.add(oks.getId());
        }
        constructionCost = round2(constructionCost);
        chamberConstructionCost = round2(chamberConstructionCost);
        tieInCost = round2(tieInCost);
        reconstructionCost = round2(reconstructionCost);
        chamberReconstructionCost = round2(chamberReconstructionCost);
        double penalty = penalty(unconnected);
        double calculatedCost = round2(constructionCost + chamberConstructionCost + tieInCost + reconstructionCost
                + chamberReconstructionCost + penalty);
        newNetworkLength = round2(newNetworkLength);
        reconstructionLength = round2(reconstructionLength);
        double length = round2(newNetworkLength + reconstructionLength);
        double score = Math.round(rules.score(calculatedCost, length) * 1000) / 1000.0;
        return new VariantSummary(id, variantId, 0, constructionCost, chamberConstructionCost, tieInCost,
                reconstructionCost, chamberReconstructionCost, penalty, calculatedCost, newNetworkLength,
                reconstructionLength, length, score, unconnectedIds);
    }
}
