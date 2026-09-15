package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class ChamberReconstruction {
    String id;
    String variantId;
    Point geometry;
    String existingObjectId;
    int existingDiameter;
    int requiredDiameter;
    double cost;
}
