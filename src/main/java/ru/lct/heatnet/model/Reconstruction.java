package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
public class Reconstruction {
    String id;
    String variantId;
    LineString geometry;
    String existingObjectId;
    double existingFlowTph;
    double addedFlowTph;
    double calculatedFlowTph;
    int existingDiameter;
    int requiredDiameter;
    double length;
    double cost;
}
