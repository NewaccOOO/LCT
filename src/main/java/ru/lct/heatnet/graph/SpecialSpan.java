package ru.lct.heatnet.graph;

import lombok.Value;

@Value
public class SpecialSpan {
    double fromM;
    double toM;
    String objectId;
    String type;
    double kSpecial;
}
