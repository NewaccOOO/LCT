package ru.lct.heatnet.plan;

import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

/**
 * Правило geometry валидатора (docs/interpretation.md): на пути от врезки до точки подключения поворотов не больше
 * 3k + 4, где k — число полигонов запрета, дорог и путей, которые пересекает прямая между врезкой и точкой.
 */
final class TurnRule {
    static final double MIN_TURN_DEG = 3;
    private static final int TURNS_PER_OBSTACLE = 3;
    private static final int TURNS_BASE = 4;

    private final STRtree polygons = new STRtree();
    private final GeometryFactory factory = new GeometryFactory();

    TurnRule(InputData input, Rules rules) {
        for (ExistingOks oks : input.getExistingOks()) {
            add(oks.getGeometry());
        }
        for (Restriction restriction : input.getRestrictions()) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            if (restriction.getGeometry().getDimension() == 2 && (rule.forbid() || rule.getMinAngleDeg() != null)) {
                add(restriction.getGeometry());
            }
        }
        polygons.build();
    }

    private void add(Geometry polygon) {
        polygons.insert(polygon.getEnvelopeInternal(), PreparedGeometryFactory.prepare(polygon));
    }

    int allowed(Coordinate tie, Coordinate connection) {
        LineString chord = factory.createLineString(new Coordinate[] {tie, connection});
        int crossed = 0;
        for (Object item : polygons.query(chord.getEnvelopeInternal())) {
            if (((PreparedGeometry) item).intersects(chord)) {
                crossed++;
            }
        }
        return TURNS_PER_OBSTACLE * crossed + TURNS_BASE;
    }

    /** Повороты на ломаной: вершины с отклонением от 3°, вершины между совпадающими точками не считаются. */
    static int turns(List<Coordinate> coords) {
        int turns = 0;
        for (int i = 1; i + 1 < coords.size(); i++) {
            if (coords.get(i - 1).distance(coords.get(i)) > 0 && coords.get(i).distance(coords.get(i + 1)) > 0
                    && TreeBuilder.deflectionDeg(coords.get(i - 1), coords.get(i), coords.get(i + 1)) >= MIN_TURN_DEG) {
                turns++;
            }
        }
        return turns;
    }
}
