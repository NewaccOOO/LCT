package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

/** Объекты со специальным проходом: дороги и пути, газопроводы, кабели и существующая сеть. */
final class SpecialObjects {
    private static final int MARGIN_QUADRANT_SEGMENTS = 16;
    /** Запас к отступу, чтобы узел не встал на самой границе зоны сближения. */
    private static final double NEAR_EXTRA_M = 0.1;

    static final class Special {
        final Geometry geometry;
        final boolean polygon;
        final boolean network;
        final RestrictionRule rule;
        /** Полуширина самого объекта: половина ширины существующей трубы или half_width_m линии. */
        final double halfWidth;
        final PreparedGeometry prepared;
        final Geometry buffered;

        Special(Geometry geometry, boolean network, RestrictionRule rule, double halfWidth) {
            this.geometry = geometry;
            this.polygon = geometry.getDimension() == 2;
            this.network = network;
            this.rule = rule;
            this.halfWidth = halfWidth;
            this.prepared = polygon ? PreparedGeometryFactory.prepare(geometry) : null;
            this.buffered = polygon ? geometry.buffer(rule.getMarginM(), MARGIN_QUADRANT_SEGMENTS) : null;
        }
    }

    final List<Special> all = new ArrayList<>();
    private final STRtree index = new STRtree();
    private final Set<RestrictionRule> kinds = new HashSet<>();
    private final Rules rules;
    private final double maxHalfWidth;
    private final GeometryFactory factory = new GeometryFactory();

    SpecialObjects(InputData input, Rules rules) {
        this.rules = rules;
        RestrictionRule network = rules.restriction(TieCandidate.HEAT_NETWORK);
        for (NetworkSegment segment : input.getSegments()) {
            all.add(new Special(segment.getGeometry(), true, network, rules.diameter(segment.getDiameter()).getWidthM() / 2));
        }
        for (Restriction restriction : input.getRestrictions()) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            // точечный объект со специальным проходом обходится как запрет, см. ObstacleSet
            if (!rule.forbid() && restriction.getGeometry().getDimension() > 0) {
                double halfWidth = restriction.getGeometry().getDimension() < 2 && rule.getHalfWidthM() != null
                        ? rule.getHalfWidthM() : 0;
                all.add(new Special(restriction.getGeometry(), false, rule, halfWidth));
            }
        }
        double widest = 0;
        for (Special special : all) {
            index.insert(special.geometry.getEnvelopeInternal(), special);
            widest = Math.max(widest, special.halfWidth);
            kinds.add(special.rule);
        }
        maxHalfWidth = widest;
        index.build();
    }

    /** Точка ближе отступа к какому-либо объекту со специальным проходом для диаметра dn. */
    boolean near(Coordinate c, int dn) {
        Point point = factory.createPoint(c);
        for (Special special : around(point, dn)) {
            if (special.geometry.isWithinDistance(point, clearance(special, dn) + NEAR_EXTRA_M)) {
                return true;
            }
        }
        return false;
    }

    /** Объекты, до которых от геометрии может не хватить отступа для диаметра dn. */
    List<Special> around(Geometry geometry, int dn) {
        Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
        envelope.expandBy(maxClearance(dn) + rules.diameter(dn).getWidthM() / 2 + maxHalfWidth + NEAR_EXTRA_M);
        List<Special> result = new ArrayList<>();
        for (Object item : index.query(envelope)) {
            result.add((Special) item);
        }
        return result;
    }

    /** Отступ оси нового участка от оси или границы объекта: clearance + width/2 + полуширина объекта. */
    double clearance(Special special, int dn) {
        return special.rule.clearanceM(dn) + rules.diameter(dn).getWidthM() / 2 + special.halfWidth;
    }

    private double maxClearance(int dn) {
        double max = 0;
        for (RestrictionRule rule : kinds) {
            max = Math.max(max, rule.clearanceM(dn));
        }
        return max;
    }
}
