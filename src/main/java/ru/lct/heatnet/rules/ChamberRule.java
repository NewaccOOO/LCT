package ru.lct.heatnet.rules;

import lombok.Value;

@Value
public class ChamberRule {
    double maxDistM;
    int maxSegments;
    int maxBranches;
}
