package ru.lct.heatnet.calc;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class ChamberRecon {
    String chamberId;
    Point geometry;
    int existingDiameter;
    int requiredDiameter;
    double cost;
}
