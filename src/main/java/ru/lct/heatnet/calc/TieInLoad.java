package ru.lct.heatnet.calc;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class TieInLoad {
    String key;
    String existingObjectId;
    String existingObjectType;
    Point point;
    double addedFlowTph;
}
