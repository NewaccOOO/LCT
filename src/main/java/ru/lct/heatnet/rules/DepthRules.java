package ru.lct.heatnet.rules;

import java.util.Map;
import lombok.Value;

@Value
public class DepthRules {
    double normalM;
    double minM;
    double maxSlope;
    double kPerM;
    Map<String, Crossing> crossings;
    Map<String, Double> minTopM;

    @Value
    public static class Crossing {
        double topM;
        Double heightM;
        double clearM;
        boolean soft;
    }

    public double k(double h) {
        return h > normalM ? 1 + kPerM * (h - normalM) : 1;
    }
}
