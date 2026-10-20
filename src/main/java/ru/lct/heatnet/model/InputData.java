package ru.lct.heatnet.model;

import java.util.List;
import java.util.Set;
import lombok.Value;

@Value
public class InputData {
    List<Source> sources;
    List<NetworkSegment> segments;
    List<Chamber> chambers;
    List<FutureOks> futureOks;
    List<ConnectionPoint> connectionPoints;
    List<ExistingOks> existingOks;
    List<Restriction> restrictions;
    List<Diagnostic> diagnostics;
    List<String> warnings;
    Set<String> numericIds;
}
