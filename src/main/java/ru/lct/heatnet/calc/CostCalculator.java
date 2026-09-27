package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.List;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Rules;

/** Стоимость по разделу 6 технического приложения от 18.09.2026: участки, камеры, врезки, штраф, S. */
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

    /** Врезка нового участка в существующую камеру. */
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

    /** Сводка варианта, rank = 0 до ранжирования; existingTieIns — число новых участков, заканчивающихся в существующих камерах. */
    public VariantSummary summary(String id, String variantId, List<NewSegment> segments, List<NewChamber> chambers,
            int existingTieIns, List<FutureOks> unconnected) {
        double segmentCost = 0;
        double newNetworkLength = 0;
        for (NewSegment segment : segments) {
            segmentCost += segment.getCost();
            newNetworkLength += segment.getLength();
        }
        double chamberConstructionCost = 0;
        for (NewChamber chamber : chambers) {
            chamberConstructionCost += chamber.getCost();
        }
        List<String> unconnectedIds = new ArrayList<>();
        for (FutureOks oks : unconnected) {
            unconnectedIds.add(oks.getId());
        }
        chamberConstructionCost = round2(chamberConstructionCost);
        double tieInCost = round2(existingTieIns * tieInCost());
        double constructionCost = round2(segmentCost + chamberConstructionCost + tieInCost);
        double penalty = penalty(unconnected);
        double calculatedCost = round2(constructionCost + penalty);
        newNetworkLength = round2(newNetworkLength);
        // четыре знака, как в примере п. 7.3 приложения 18.09 (0,6913); поиск сравнивает черновики с округлением до
        // 0,001, см. VariantEnumerator.searchScore
        double score = Math.round(rules.score(calculatedCost, newNetworkLength) * 10000) / 10000.0;
        return new VariantSummary(id, variantId, 0, constructionCost, chamberConstructionCost, existingTieIns, tieInCost,
                penalty, calculatedCost, newNetworkLength, score, unconnectedIds);
    }
}
