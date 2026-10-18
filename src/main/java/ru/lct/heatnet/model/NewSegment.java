package ru.lct.heatnet.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
@AllArgsConstructor
public class NewSegment {
    String id;
    String variantId;
    LineString geometry;
    String startNodeId;
    String endNodeId;
    double flowTph;
    int diameter;
    double length;
    String layingMethod;
    Double depthStart;
    Double depthEnd;
    double cost;
    /** Режим с глубиной: пересечения участка (над или под, просвет); null — нет или 2D. */
    List<DepthCrossing> depthCrossings;
    /** Режим с глубиной: профиль с уклоном не поместился, участок требует ручной проработки (ДИТ, п. 4.6). */
    boolean manualReview;

    public NewSegment(String id, String variantId, LineString geometry, String startNodeId, String endNodeId,
            double flowTph, int diameter, double length, String layingMethod, Double depthStart, Double depthEnd,
            double cost) {
        this(id, variantId, geometry, startNodeId, endNodeId, flowTph, diameter, length, layingMethod, depthStart,
                depthEnd, cost, null, false);
    }
}
