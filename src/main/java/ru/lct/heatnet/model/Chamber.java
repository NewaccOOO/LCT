package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class Chamber {
    String id;
    Point geometry;
    int diameter;
    String upstreamId;
}
