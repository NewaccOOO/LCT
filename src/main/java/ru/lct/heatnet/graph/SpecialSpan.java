package ru.lct.heatnet.graph;

import lombok.Value;

/** Специальная часть маршрута: расстояния от начала маршрута, после склейки перекрытий. */
@Value
public class SpecialSpan {
    double fromM;
    double toM;
    String objectId;
    String type;
    double kSpecial;
}
