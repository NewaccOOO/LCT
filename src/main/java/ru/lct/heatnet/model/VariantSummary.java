package ru.lct.heatnet.model;

import java.util.List;
import lombok.Value;

/** Сводка варианта по разделу 7.2 технического приложения от 18.09.2026. */
@Value
public class VariantSummary {
    String id;
    String variantId;
    int rank;
    /** Новые участки, новые камеры и врезки в существующие камеры. */
    double constructionCost;
    double chamberConstructionCost;
    int existingChamberTieInCount;
    double existingChamberTieInCost;
    double unconnectedPenalty;
    double calculatedCost;
    double newNetworkLength;
    double score;
    List<String> unconnectedOksIds;
}
