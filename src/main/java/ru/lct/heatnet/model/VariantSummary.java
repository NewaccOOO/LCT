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
    double tieInCost;
    double reconstructionCost;
    double chamberReconstructionCost;
    double unconnectedPenalty;
    double calculatedCost;
    double newNetworkLength;
    double reconstructionLength;
    double length;
    double score;
    List<String> unconnectedOksIds;
}
