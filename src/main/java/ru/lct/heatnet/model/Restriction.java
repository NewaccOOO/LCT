package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Geometry;

@Value
public class Restriction {
    String id;
    Geometry geometry;
    String type;
}
