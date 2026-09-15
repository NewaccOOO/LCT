package ru.lct.heatnet.model;

import java.util.List;
import lombok.Value;

@Value
public class InputData {
    Source source;
    List<NetworkSegment> segments;
    List<Chamber> chambers;
    List<FutureOks> futureOks;
    List<ConnectionPoint> connectionPoints;
    List<ExistingOks> existingOks;
    List<Restriction> restrictions;
    List<Diagnostic> diagnostics;
}
