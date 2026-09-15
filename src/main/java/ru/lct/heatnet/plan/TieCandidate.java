package ru.lct.heatnet.plan;

import java.util.Locale;
import java.util.Set;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Кандидат врезки: в существующую камеру или в точку на оси существующего участка. */
@Value
public class TieCandidate {
    public static final String HEAT_NETWORK = "heat_network";
    public static final String HEAT_CHAMBER = "heat_chamber";

    String existingObjectId;
    String existingObjectType;
    int existingDiameter;
    Point point;
    /** Участки существующей сети, которых точка врезки касается (не дальше 0,5 м): их отступ у врезки не проверяется. */
    Set<String> ignored;
    /** Сколько новых участков ещё можно подключить в точке врезки. */
    int capacity;

    boolean isChamber() {
        return HEAT_CHAMBER.equals(existingObjectType);
    }

    /** Ключ узла врезки: две врезки в одну камеру это один узел. */
    String nodeKey() {
        if (isChamber()) {
            return "chamber:" + existingObjectId;
        }
        return String.format(Locale.ROOT, "pipe:%s@%.3f,%.3f", existingObjectId, point.getX(), point.getY());
    }
}
