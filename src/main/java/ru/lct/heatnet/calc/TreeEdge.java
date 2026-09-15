package ru.lct.heatnet.calc;

import lombok.Value;

/** Ребро дерева новой сети, направлено от врезки к ОКС. */
@Value
public class TreeEdge {
    String id;
    String fromNode;
    String toNode;
    double length;
}
