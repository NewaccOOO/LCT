package ru.lct.heatnet.model;

import java.util.List;
import lombok.Value;

@Value
public class VariantSummary {
    String id;
    String variantId;
    int rank;
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
