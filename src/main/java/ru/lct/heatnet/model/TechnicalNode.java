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
    /** Причина узла, если её надо отличать: depth_profile — излом профиля глубины или переход через 3,0 м (§5). */
    String reason;

    public TechnicalNode(String id, String variantId, Point geometry) {
        this(id, variantId, geometry, null);
    }
}
