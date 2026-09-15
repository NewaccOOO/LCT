package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class TechnicalNode {
    String id;
    String variantId;
    Point geometry;
}
