package ru.lct.heatnet.model;

import lombok.Value;
import org.locationtech.jts.geom.Geometry;

@Value
public class FutureOks {
    String id;
    Geometry geometry;
    double flowTph;
    Double heatLoad;
}
