package ru.lct.heatnet.calc;

import lombok.Value;

@Value
public class TreeEdge {
    String id;
    String fromNode;
    String toNode;
    double length;
}
