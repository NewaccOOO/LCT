package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
public class NetworkSegment {
    String id;
    LineString geometry;
    int diameter;
    double flowTph;
    String upstreamId;
}
