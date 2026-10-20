package ru.lct.heatnet.model;

import lombok.AllArgsConstructor;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@AllArgsConstructor
public class TechnicalNode {
    String id;
    String variantId;
    Point geometry;
    String reason;

    public TechnicalNode(String id, String variantId, Point geometry) {
        this(id, variantId, geometry, null);
    }
}
