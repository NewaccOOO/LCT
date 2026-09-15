package ru.lct.heatnet.io;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Перевод геометрии между EPSG:4326 (x = долгота, y = широта) и EPSG:32637. */
public class Projector {
    // Трансформы proj4j хранят изменяемое состояние, поэтому у каждого потока своя пара.
    private static final ThreadLocal<CoordinateTransform> TO_UTM = ThreadLocal.withInitial(() -> transform("EPSG:4326", "EPSG:32637"));
    private static final ThreadLocal<CoordinateTransform> TO_WGS = ThreadLocal.withInitial(() -> transform("EPSG:32637", "EPSG:4326"));

    public static Geometry toUtm(Geometry geometry) {
        return apply(geometry, TO_UTM.get());
    }

    public static Geometry toWgs(Geometry geometry) {
        return apply(geometry, TO_WGS.get());
    }

    private static Geometry apply(Geometry geometry, CoordinateTransform transform) {
        Geometry copy = geometry.copy();
        ProjCoordinate from = new ProjCoordinate();
        ProjCoordinate to = new ProjCoordinate();
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                from.x = seq.getX(i);
                from.y = seq.getY(i);
                transform.transform(from, to);
                seq.setOrdinate(i, CoordinateSequence.X, to.x);
                seq.setOrdinate(i, CoordinateSequence.Y, to.y);
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        return copy;
    }

    private static CoordinateTransform transform(String from, String to) {
        CRSFactory crs = new CRSFactory();
        CoordinateReferenceSystem source = crs.createFromName(from);
        CoordinateReferenceSystem target = crs.createFromName(to);
        return new CoordinateTransformFactory().createTransform(source, target);
    }
}
