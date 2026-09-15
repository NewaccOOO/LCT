package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
public class NewSegment {
    String id;
    String variantId;
    LineString geometry;
    String startNodeId;
    String endNodeId;
    double flowTph;
    int diameter;
    double length;
    String layingMethod;
    Double depthStart;
    Double depthEnd;
    double cost;
}
