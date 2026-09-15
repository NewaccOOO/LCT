package ru.lct.heatnet.rules;

import java.util.Map;
import java.util.NavigableMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;

@Value
public class RestrictionRule {
    String type;
    @Getter(AccessLevel.NONE)
    boolean forbid;
    @Getter(AccessLevel.NONE)
    NavigableMap<Integer, Double> clearanceByDnMax;
    Double minAngleDeg;
    Double marginM;
    Double kSpecial;
    Double halfWidthM;

    public boolean forbid() {
        return forbid;
    }

    public double clearanceM(int dn) {
        Map.Entry<Integer, Double> tier = clearanceByDnMax.ceilingEntry(dn);
        if (tier == null) {
            throw new IllegalArgumentException("Для " + type + " не задан отступ при DN" + dn);
        }
        return tier.getValue();
    }
}
