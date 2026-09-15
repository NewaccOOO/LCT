package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class TieIn {
    String id;
    String variantId;
    Point geometry;
    String existingObjectId;
    String existingObjectType;
    int existingDiameter;
    int requiredDiameter;
    double cost;
}
