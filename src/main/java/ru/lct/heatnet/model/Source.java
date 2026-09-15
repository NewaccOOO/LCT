package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
public class Source {
    String id;
    Point geometry;
}
