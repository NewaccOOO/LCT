package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class NewChamber {
    String id;
    String variantId;
    Point geometry;
    int diameter;
    double cost;
}
