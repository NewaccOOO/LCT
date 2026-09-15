package ru.lct.heatnet.model;

import lombok.Value;

@Value
public class Diagnostic {
    String featureId;
    String field;
    String problem;
}
