package ru.lct.heatnet.plan;

import java.util.Locale;
import java.util.Set;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class TieCandidate {
    public static final String HEAT_NETWORK = "heat_network";
    public static final String HEAT_CHAMBER = "heat_chamber";

    String existingObjectId;
    String existingObjectType;
    int existingDiameter;
    Point point;
    Set<String> ignored;
    int capacity;

    boolean isChamber() {
        return HEAT_CHAMBER.equals(existingObjectType);
    }

    String nodeKey() {
        if (isChamber()) {
            return "chamber:" + existingObjectId;
        }
        return String.format(Locale.ROOT, "pipe:%s@%.3f,%.3f", existingObjectId, point.getX(), point.getY());
    }
}
