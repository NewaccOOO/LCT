package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Geometry;

@Value
public class ExistingOks {
    String id;
    Geometry geometry;
}
