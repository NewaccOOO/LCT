package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class ConnectionPoint {
    String id;
    Point geometry;
    String oksId;
}
