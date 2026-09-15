package ru.lct.heatnet.graph;

import java.util.List;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
public class Route {
    LineString geometry;
    double length;
    double weight;
    List<SpecialSpan> spans;
}
