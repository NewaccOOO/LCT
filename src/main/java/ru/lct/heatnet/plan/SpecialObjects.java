package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.RayCrossingCounter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.graph.ObstacleSet;
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
        /** Стороны линейного объекта или колец полигона. */
        final LineSegment[] sides;

        Special(Geometry geometry, boolean network, RestrictionRule rule, double halfWidth) {
            this.geometry = geometry;
            this.polygon = geometry.getDimension() == 2;
            this.network = network;
            this.rule = rule;
            this.halfWidth = halfWidth;
            this.prepared = polygon ? PreparedGeometryFactory.prepare(geometry) : null;
            this.buffered = polygon ? geometry.buffer(rule.getMarginM(), MARGIN_QUADRANT_SEGMENTS) : null;
            this.sides = sides(polygon ? geometry.getBoundary() : geometry);
        }

        /**
         * То же, что geometry.isWithinDistance(point, distance). У линии расстояние считается только до сторон, чья
         * рамка не дальше distance: у магистрали города сотни сторон, а точки вдоль нового участка идут через 0,5 м.
         */
        boolean within(Point point, double distance) {
            return within(point, distance, sides);
        }

        /**
         * within по части сторон: остальные заведомо дальше distance от точки. Точка внутри полигона или на его
         * границе ближе любого distance, как у DistanceOp: ей ноль.
         */
        boolean within(Point point, double distance, LineSegment[] near) {
            if (geometry.getEnvelopeInternal().distance(point.getEnvelopeInternal()) > distance) {
                return false;
            }
            Coordinate c = point.getCoordinate();
            if (polygon && inside(c)) {
                return true;
            }
            for (LineSegment side : near) {
                double dx = Math.max(Math.min(side.p0.x, side.p1.x) - c.x, c.x - Math.max(side.p0.x, side.p1.x));
                double dy = Math.max(Math.min(side.p0.y, side.p1.y) - c.y, c.y - Math.max(side.p0.y, side.p1.y));
                if (dx <= distance && dy <= distance && side.distance(c) <= distance) {
                    return true;
                }
            }
            return false;
        }

        /** Точка внутри полигона или на границе, по всем кольцам сразу, как IndexedPointInAreaLocator. */
        boolean inside(Coordinate c) {
            RayCrossingCounter counter = new RayCrossingCounter(c);
            for (LineSegment side : sides) {
                counter.countSegment(side.p0, side.p1);
                if (counter.isOnSegment()) {
                    return true;
                }
            }
            return counter.getLocation() != Location.EXTERIOR;
        }

        /**
         * Расстояние от линии до объекта меньше limit — то же, что line.distance(geometry) < limit, но пары отрезков
         * с рамками дальше limit не считаются: у магистрали сотни сторон. Первая точка линии внутри полигона — ноль,
         * как у DistanceOp.
         */
        boolean closer(LineString line, double limit) {
            if (line.getEnvelopeInternal().distance(geometry.getEnvelopeInternal()) >= limit) {
                return false;
            }
            if (polygon && inside(line.getCoordinateN(0))) {
                return 0 < limit;
            }
            Coordinate[] coords = line.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                Envelope piece = new Envelope(coords[i], coords[i + 1]);
                for (LineSegment side : sides) {
                    if (piece.distance(new Envelope(side.p0, side.p1)) < limit
                            && Distance.segmentToSegment(coords[i], coords[i + 1], side.p0, side.p1) < limit) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Линия задевает линейный объект: какой-то её отрезок пересекает какую-то сторону. Без этого пересечение
         * линий пусто, и наложение JTS в зоне спецперехода можно не считать.
         */
        boolean crossedBy(LineString line) {
            Coordinate[] coords = line.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                for (LineSegment side : sides) {
                    if (ObstacleSet.meet(coords[i], coords[i + 1], side.p0, side.p1)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static LineSegment[] sides(Geometry lineal) {
            List<LineSegment> result = new ArrayList<>();
            for (int i = 0; i < lineal.getNumGeometries(); i++) {
                Coordinate[] coords = lineal.getGeometryN(i).getCoordinates();
                for (int k = 0; k + 1 < coords.length; k++) {
                    result.add(new LineSegment(coords[k], coords[k + 1]));
                }
            }
            return result.toArray(new LineSegment[0]);
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
            if (special.within(point, clearance(special, dn) + NEAR_EXTRA_M)) {
                return true;
            }
        }
        return false;
    }

    /**
     * То же, что near, для многих точек одной линии: объекты и их стороны отбираются один раз по рамке линии с
     * запасом, до которого near вообще смотрит. Сборка проверяет точки нового участка через 0,5 м, а у магистрали
     * города сотни сторон.
     */
    Predicate<Coordinate> nearAlong(Geometry line, int dn) {
        Envelope reach = new Envelope(line.getEnvelopeInternal());
        reach.expandBy(maxClearance(dn) + rules.diameter(dn).getWidthM() / 2 + maxHalfWidth + NEAR_EXTRA_M);
        List<Special> candidates = around(line, dn);
        List<LineSegment[]> sides = new ArrayList<>();
        for (Special special : candidates) {
            List<LineSegment> near = new ArrayList<>();
            for (LineSegment side : special.sides) {
                if (reach.intersects(side.p0, side.p1)) {
                    near.add(side);
                }
            }
            sides.add(near.toArray(new LineSegment[0]));
        }
        return c -> {
            Point point = factory.createPoint(c);
            for (int i = 0; i < candidates.size(); i++) {
                Special special = candidates.get(i);
                if (special.within(point, clearance(special, dn) + NEAR_EXTRA_M, sides.get(i))) {
                    return true;
                }
            }
            return false;
        };
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
