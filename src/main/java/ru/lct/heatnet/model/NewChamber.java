package ru.lct.heatnet.model;

import lombok.AllArgsConstructor;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@AllArgsConstructor
public class NewChamber {
    String id;
    String variantId;
    Point geometry;
    int diameter;
    double cost;
    /** Режим с глубиной: глубина верха габарита труб в камере, м; null — 2D. */
    Double depth;

    public NewChamber(String id, String variantId, Point geometry, int diameter, double cost) {
        this(id, variantId, geometry, diameter, cost, null);
    }
}
