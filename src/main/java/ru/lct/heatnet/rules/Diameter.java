package ru.lct.heatnet.rules;

import lombok.Value;

@Value
public class Diameter {
    int dn;
    double capacityTph;
    double maxLengthM;
    double newRubM;
    double reconRubM;
    double widthM;
    double heightM;
}
