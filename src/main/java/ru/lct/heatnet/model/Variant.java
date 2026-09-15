package ru.lct.heatnet.model;

import java.util.List;
import lombok.Value;

@Value
public class Variant {
    String id;
    List<NewSegment> segments;
    List<TieIn> tieIns;
    List<Reconstruction> reconstructions;
    List<NewChamber> chambers;
    List<ChamberReconstruction> chamberReconstructions;
    List<TechnicalNode> nodes;
    VariantSummary summary;
}
