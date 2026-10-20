package ru.lct.heatnet.model;

import lombok.Value;

@Value
public class DepthCrossing {
    String type;
    String position;
    double clearanceM;
}
