package ru.lct.heatnet.model;

import java.util.List;
import lombok.Value;

@Value
public class Variant {
    String id;
    List<NewSegment> segments;
    List<NewChamber> chambers;
    List<TechnicalNode> nodes;
    VariantSummary summary;
}
