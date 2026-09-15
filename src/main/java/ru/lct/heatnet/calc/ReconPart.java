package ru.lct.heatnet.calc;

import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
public class ReconPart {
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
