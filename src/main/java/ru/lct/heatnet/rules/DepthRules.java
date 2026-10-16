package ru.lct.heatnet.rules;

import java.util.Map;
import lombok.Value;

/** Режим с учётом глубины (приложение 18.09, §5 и табл. 2); h — глубина от поверхности до верха габарита, м. */
@Value
public class DepthRules {
    double normalM;
    double minM;
    double maxSlope;
    /** Kгл = 1 + kPerM · (h − normalM) при h > normalM. */
    double kPerM;
    /** Линейные объекты, которые новая сеть проходит выше или ниже, по restriction_type (сеть — heat_network). */
    Map<String, Crossing> crossings;
    /** Наименьшая глубина верха новой сети под объектом: дорога, трамвай. */
    Map<String, Double> minTopM;

    @Value
    public static class Crossing {
        double topM;
        /** Высота габарита объекта; null — по Ду трубы из таблицы 1. */
        Double heightM;
        double clearM;
    }

    public double k(double h) {
        return h > normalM ? 1 + kPerM * (h - normalM) : 1;
    }
}
