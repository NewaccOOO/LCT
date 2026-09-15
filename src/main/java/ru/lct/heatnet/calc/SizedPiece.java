package ru.lct.heatnet.calc;

import lombok.Value;

/** Часть ребра от fromM до toM (метры от fromNode) одного диаметра. */
@Value
public class SizedPiece {
    String edgeId;
    double fromM;
    double toM;
    int dn;
}
