package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RayCrossingCounter;
import org.locationtech.jts.algorithm.RectangleLineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

/**
 * Зоны запрета и сближения для одной области и диаметра новой сети, узлы visibility graph,
 * проверка и вес отрезка. Геометрия в EPSG:32637.
 */
public final class ObstacleSet {
    private static final String OKS_EXISTING = "oks_existing";
    private static final String HEAT_NETWORK = "heat_network";
    private static final double SIMPLIFY_M = 0.05;
    private static final double NODE_OFFSET_M = 0.05;
    private static final double ALONG_SKIP_M = 0.5;
    private static final double ALONG_TOL_M = 0.01;
    private static final double SPAN_JOIN_M = 1e-6;
    // Объект врезки пропускается только у отрезков, которые начинаются или заканчиваются на нём (A-9: до 0,5 м).
    private static final double TIE_IN_TOUCH_M = 0.5;
    // Запас на округление координат до 9 знаков в EPSG:4326: валидатор не должен увидеть 44,999° вместо 45°.
    private static final double ANGLE_MARGIN_DEG = 0.01;
    // Шаг узлов вдоль сторон дорог: у полосы 8 м пара узлов напротив друг друга даёт пересечение не круче 45°.
    private static final double CROSSING_STEP_M = 20;
    // shapely по умолчанию строит буфер с 16 сегментами на четверть круга, валидатор считает так же
    private static final int MARGIN_QUADRANT_SEGMENTS = 16;
    private static final double TANGENT_EPS = 1e-9;
    /** Насколько узлы пересечения дороги стоят внутри полосы margin_m: сборка прижимает границу спецучастка к узлу ближе 0,08 м. */
    private static final double MARGIN_NODE_INSET_M = 0.1;
    /** Зоны и полигоны не больше чем в столько вершин проверяются против отрезка напрямую, см. Zone#intersects. */
    private static final int SMALL_POLYGON_POINTS = 64;
    /** Повороты угла зоны, у которых вершина JOIN_MITRE в зоне соседа заменяется двумя, см. halves. */
    private static final double HALVES_MIN_TURN_DEG = 20;
    private static final double HALVES_MAX_TURN_DEG = 120;
    private static final BufferParameters ZONE_BUFFER = new BufferParameters(
            BufferParameters.DEFAULT_QUADRANT_SEGMENTS, BufferParameters.CAP_SQUARE,
            BufferParameters.JOIN_MITRE, BufferParameters.DEFAULT_MITRE_LIMIT);

    private final GeometryFactory factory = new GeometryFactory();
    private final STRtree forbidZones = new STRtree();
    private final STRtree specials = new STRtree();
    /** Отступ оси новой сети от полигона ОКС: отступ правила плюс полуширина пары. */
    private final double oksClearance;
    private final List<Coordinate> nodes = new ArrayList<>();
    // соседи узла по кольцу его зоны или null у точек вдоль дорог: ребро полезно, только если касается зоны
    private final List<Coordinate[]> rings = new ArrayList<>();

    /**
     * Зона отступа: точки ближе distance к объекту. Расстояние считается точно, как у правила и check18, а не по
     * буферу с углами JOIN_MITRE: у угла здания такой буфер торчит на d·√2, закрывает проходы между углами соседних
     * зданий и уводит трассу от угла дальше нужного.
     */
    private static final class Zone {
        /** ID объекта зоны запрета; отрезок с этим ID в ignored зону не проверяет (финальный участок к своему ОКС). */
        final String id;
        final double distance;
        final Envelope envelope;
        // Длинный отрезок задевает рамки многих зон; проверка отрезка против рамки дешевле точного расстояния.
        final RectangleLineIntersector box;
        final boolean polygon;
        final Side[] sides;
        /** Индекс сторон и точки в полигоне у большого объекта (квартал в сотни вершин), у маленького null. */
        final STRtree sideIndex;
        final IndexedPointInAreaLocator locator;
        final Geometry geometry;
        final Envelope core;
        /** Круг вокруг объекта: отрезок дальше круга с отступом зону не задевает, стороны не перебираются. */
        final double centerX;
        final double centerY;
        final double radius;
        /** Прежняя зона с углами JOIN_MITRE для точек выхода и камер, строится при первом запросе. */
        private volatile PreparedGeometry mitre;

        /** distance — отступ правила; зона берёт его с запасом SIMPLIFY_M, как прежний буфер до упрощения. */
        Zone(String id, Geometry geometry, double distance) {
            this.id = id;
            this.geometry = geometry;
            this.distance = distance + SIMPLIFY_M;
            this.core = geometry.getEnvelopeInternal();
            this.centerX = core.centre().x;
            this.centerY = core.centre().y;
            this.radius = Math.hypot(core.getWidth(), core.getHeight()) / 2;
            this.envelope = new Envelope(core);
            envelope.expandBy(this.distance);
            this.box = new RectangleLineIntersector(envelope);
            this.polygon = geometry.getDimension() == 2;
            LineSegment[] segments = geometry.getDimension() == 0 ? points(geometry)
                    : segments(polygon ? geometry.getBoundary() : geometry);
            this.sides = new Side[segments.length];
            for (int i = 0; i < segments.length; i++) {
                sides[i] = new Side(segments[i].p0, segments[i].p1);
            }
            if (sides.length > SMALL_POLYGON_POINTS) {
                sideIndex = new STRtree();
                for (Side side : sides) {
                    sideIndex.insert(new Envelope(side.minX, side.maxX, side.minY, side.maxY), side);
                }
                sideIndex.build();
                locator = polygon ? new IndexedPointInAreaLocator(geometry) : null;
                if (locator != null) {
                    // индекс строится при первом вызове; дальше зону читают из нескольких нитей
                    locator.locate(geometry.getCoordinate());
                }
            } else {
                sideIndex = null;
                locator = null;
            }
        }

        /**
         * Отрезок a–b (a и b могут совпадать) ближе distance к объекту или внутри полигона. Расстояние до стороны —
         * ближайший из концов к другому отрезку или пересечение, в квадратах и без JTS: ребро графа проверяется
         * против зон сотни тысяч раз. Пересечение считается без робастной арифметики: почти касание и так ловит
         * расстояние, отступ не меньше метра.
         */
        boolean intersects(Coordinate a, Coordinate b) {
            return intersects(a, b, 0, false);
        }

        /**
         * То же с отступом шире на extra. {@code outside} — один из концов заведомо вне объекта (узел графа вне
         * всех зон): тогда отрезок без близких сторон целиком снаружи, и точка в полигоне не проверяется.
         */
        boolean intersects(Coordinate a, Coordinate b, double extra, boolean outside) {
            double reach = distance + extra;
            double minX = Math.min(a.x, b.x) - reach;
            double maxX = Math.max(a.x, b.x) + reach;
            double minY = Math.min(a.y, b.y) - reach;
            double maxY = Math.max(a.y, b.y) + reach;
            double limit = reach * reach;
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double length2 = dx * dx + dy * dy;
            if (squared(centerX, centerY, a, dx, dy, length2) > (radius + reach) * (radius + reach)) {
                return false;
            }
            // сторона по одну сторону от прямой отрезка дальше отступа до него не дотягивается: векторное
            // произведение — расстояние до прямой, умноженное на длину отрезка
            double far = reach * Math.sqrt(length2);
            List<?> candidates = sideIndex == null ? Arrays.asList(sides) : sideIndex.query(new Envelope(minX, maxX, minY, maxY));
            for (Object item : candidates) {
                Side side = (Side) item;
                if (side.maxX < minX || side.minX > maxX || side.maxY < minY || side.minY > maxY) {
                    continue;
                }
                double c0 = dx * (side.y0 - a.y) - dy * (side.x0 - a.x);
                double c1 = dx * (side.y1 - a.y) - dy * (side.x1 - a.x);
                if (c0 > far && c1 > far || c0 < -far && c1 < -far) {
                    continue;
                }
                if (side.squared(a.x, a.y) <= limit || side.squared(b.x, b.y) <= limit
                        || squared(side.x0, side.y0, a, dx, dy, length2) <= limit
                        || squared(side.x1, side.y1, a, dx, dy, length2) <= limit || side.crosses(a, dx, dy)) {
                    return true;
                }
            }
            // ни одна сторона не близко: отрезок целиком снаружи или целиком внутри полигона
            return polygon && !outside && core.contains(a) && core.contains(b) && inside(a);
        }

        boolean covers(Coordinate c) {
            return envelope.contains(c) && intersects(c, c);
        }

        /**
         * Точка в прежней зоне с углами JOIN_MITRE. По ней ставятся точки выхода из здания: у выхода между частями
         * здания угол такой зоны отодвигает его до второй части, луч снова входит в здание, и берётся следующая
         * сторона (приложение 18.09, п. 2.2); по точной зоне выход остаётся в тупике, откуда ветки нет.
         */
        boolean coversMitre(Coordinate c, Geometry point) {
            if (!envelope.contains(c)) {
                return false;
            }
            PreparedGeometry zone = mitre;
            if (zone == null) {
                zone = PreparedGeometryFactory.prepare(zone(geometry, distance - SIMPLIFY_M));
                mitre = zone;
            }
            return zone.intersects(point);
        }

        /** Точка внутри полигона, чётность пересечений луча вправо; точку у границы уже поймало расстояние. */
        private boolean inside(Coordinate c) {
            if (locator != null) {
                return locator.locate(c) != Location.EXTERIOR;
            }
            boolean inside = false;
            for (Side side : sides) {
                if (side.y0 > c.y != side.y1 > c.y && c.x < side.x0 + (c.y - side.y0) * (side.x1 - side.x0) / (side.y1 - side.y0)) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    /** Сторона объекта зоны с рамкой и направлением. */
    private static final class Side {
        final double x0;
        final double y0;
        final double x1;
        final double y1;
        final double dx;
        final double dy;
        final double length2;
        final double minX;
        final double maxX;
        final double minY;
        final double maxY;

        Side(Coordinate p0, Coordinate p1) {
            x0 = p0.x;
            y0 = p0.y;
            x1 = p1.x;
            y1 = p1.y;
            dx = x1 - x0;
            dy = y1 - y0;
            length2 = dx * dx + dy * dy;
            minX = Math.min(x0, x1);
            maxX = Math.max(x0, x1);
            minY = Math.min(y0, y1);
            maxY = Math.max(y0, y1);
        }

        /** Квадрат расстояния от точки (x, y) до стороны. */
        double squared(double x, double y) {
            double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / length2));
            double ex = x0 + t * dx - x;
            double ey = y0 + t * dy - y;
            return ex * ex + ey * ey;
        }

        /** Отрезок из a с направлением (ux, uy) пересекает сторону: концы каждого по разные стороны другого. */
        boolean crosses(Coordinate a, double ux, double uy) {
            double s0 = ux * (y0 - a.y) - uy * (x0 - a.x);
            double s1 = ux * (y1 - a.y) - uy * (x1 - a.x);
            double t0 = dx * (a.y - y0) - dy * (a.x - x0);
            double t1 = dx * (a.y + uy - y0) - dy * (a.x + ux - x0);
            return (s0 < 0) != (s1 < 0) && (t0 < 0) != (t1 < 0);
        }
    }

    /** Квадрат расстояния от точки (x, y) до отрезка из a с направлением (dx, dy), length2 — квадрат его длины. */
    private static double squared(double x, double y, Coordinate a, double dx, double dy, double length2) {
        double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x) * dx + (y - a.y) * dy) / length2));
        double ex = a.x + t * dx - x;
        double ey = a.y + t * dy - y;
        return ex * ex + ey * ey;
    }

    /** Отрезок a–b задевает стороны или, если area, его начало внутри колец из этих сторон. */
    private static boolean crosses(List<LineSegment> sides, Coordinate a, Coordinate b, boolean area) {
        if (area) {
            RayCrossingCounter counter = new RayCrossingCounter(a);
            for (LineSegment side : sides) {
                counter.countSegment(side.p0, side.p1);
                if (counter.isOnSegment()) {
                    return true;
                }
            }
            if (counter.getLocation() != Location.EXTERIOR) {
                return true;
            }
        }
        for (LineSegment side : sides) {
            if (meet(a, b, side.p0, side.p1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * У отрезков p и q есть общая точка — то же, что RobustLineIntersector.hasIntersection (JTS 1.20), но без
     * вычисления самой точки: рамки, затем знаки ориентации концов, коллинеарный случай — через сам LineIntersector.
     */
    public static boolean meet(Coordinate p1, Coordinate p2, Coordinate q1, Coordinate q2) {
        if (!Envelope.intersects(p1, p2, q1, q2)) {
            return false;
        }
        int pq1 = Orientation.index(p1, p2, q1);
        int pq2 = Orientation.index(p1, p2, q2);
        if (pq1 > 0 && pq2 > 0 || pq1 < 0 && pq2 < 0) {
            return false;
        }
        int qp1 = Orientation.index(q1, q2, p1);
        int qp2 = Orientation.index(q1, q2, p2);
        if (qp1 > 0 && qp2 > 0 || qp1 < 0 && qp2 < 0) {
            return false;
        }
        if (pq1 == 0 && pq2 == 0 && qp1 == 0 && qp2 == 0) {
            LineIntersector intersector = new RobustLineIntersector();
            intersector.computeIntersection(p1, p2, q1, q2);
            return intersector.hasIntersection();
        }
        return true;
    }

    private static final class Special {
        final String id;
        final String type;
        final RestrictionRule rule;
        final boolean polygon;
        final PreparedGeometry object;
        final Zone zone;
        final Geometry marginZone;
        /** Стороны колец marginZone; у линии null. */
        final LineSegment[] marginSides;
        final LineSegment[] sides;
        final boolean small;

        Special(String id, String type, RestrictionRule rule, Geometry geometry, double zoneDistance) {
            this.id = id;
            this.type = type;
            this.rule = rule;
            this.polygon = geometry.getDimension() == 2;
            this.object = PreparedGeometryFactory.prepare(geometry);
            this.zone = new Zone(id, geometry, zoneDistance);
            this.marginZone = polygon ? geometry.buffer(rule.getMarginM(), MARGIN_QUADRANT_SEGMENTS) : null;
            this.marginSides = polygon ? segments(marginZone.getBoundary()) : null;
            this.sides = segments(polygon ? geometry.getBoundary() : geometry);
            this.small = polygon && geometry.getNumPoints() <= SMALL_POLYGON_POINTS;
        }

        /**
         * То же, что object.getGeometry().isWithinDistance(отрезок a–b, distance), a и b могут совпадать: начало
         * внутри полигона — ноль, как у DistanceOp, иначе ближайшая из сторон, чья рамка не дальше distance.
         */
        boolean near(Coordinate a, Coordinate b, double distance) {
            Envelope envelope = new Envelope(a, b);
            if (object.getGeometry().getEnvelopeInternal().distance(envelope) > distance) {
                return false;
            }
            if (polygon) {
                RayCrossingCounter counter = new RayCrossingCounter(a);
                for (LineSegment side : sides) {
                    counter.countSegment(side.p0, side.p1);
                }
                if (counter.getLocation() != Location.EXTERIOR) {
                    return true;
                }
            }
            for (LineSegment side : sides) {
                if (new Envelope(side.p0, side.p1).distance(envelope) <= distance
                        && Distance.segmentToSegment(a, b, side.p0, side.p1) <= distance) {
                    return true;
                }
            }
            return false;
        }

        /** То же, что object.intersects(edge) для отрезка a–b, без подготовки JTS у линий и маленьких полигонов. */
        boolean crossedBy(Coordinate a, Coordinate b, LineString edge) {
            if (polygon && !small) {
                return object.intersects(edge);
            }
            return polygon ? crosses(Arrays.asList(sides), a, b, true) : crosses(sidesNear(a, b), a, b, false);
        }

        /**
         * Стороны, чья рамка пересекает рамку отрезка a–b: с остальными у него нет общих точек, и LineIntersector
         * их всё равно отбросил бы. У магистрали города сторон сотни, и перебор всех был главной ценой ребра графа.
         */
        List<LineSegment> sidesNear(Coordinate a, Coordinate b) {
            double minX = Math.min(a.x, b.x);
            double maxX = Math.max(a.x, b.x);
            double minY = Math.min(a.y, b.y);
            double maxY = Math.max(a.y, b.y);
            List<LineSegment> result = new ArrayList<>();
            for (LineSegment side : sides) {
                if (Math.max(side.p0.x, side.p1.x) >= minX && Math.min(side.p0.x, side.p1.x) <= maxX
                        && Math.max(side.p0.y, side.p1.y) >= minY && Math.min(side.p0.y, side.p1.y) <= maxY) {
                    result.add(side);
                }
            }
            return result;
        }
    }

    public ObstacleSet(InputData input, Rules rules, Envelope area, int dn) {
        this(new ObstacleIndex(input, rules), rules, area, dn);
    }

    public ObstacleSet(ObstacleIndex index, Rules rules, Envelope area, int dn) {
        this(index, rules, area, dn, null);
    }

    /**
     * То же, но узлы графа и препятствия берутся только внутри {@code corridor} (полигон в area): у дальней точки
     * города прямоугольник вокруг точки и врезки накрывает квадратные километры зданий, а трасса идёт полосой.
     */
    public ObstacleSet(ObstacleIndex index, Rules rules, Envelope area, int dn, Geometry corridor) {
        PreparedGeometry inside = corridor == null ? null : PreparedGeometryFactory.prepare(corridor);
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        List<Zone> forbid = new ArrayList<>();
        List<Geometry> nodeZones = new ArrayList<>();
        List<Geometry> crossingNodeZones = new ArrayList<>();
        List<Geometry> marginZones = new ArrayList<>();
        List<Special> specialList = new ArrayList<>();

        double oksDistance = rules.restriction(OKS_EXISTING).clearanceM(dn) + halfWidth;
        oksClearance = oksDistance;
        for (ExistingOks oks : index.existingOks(area)) {
            if (near(oks.getGeometry(), oksDistance, area) && (inside == null || inside.intersects(oks.getGeometry()))) {
                forbid.add(new Zone(oks.getId(), oks.getGeometry(), oksDistance));
                nodeZones.add(nodeZone(oks.getGeometry(), oksDistance));
            }
        }
        for (Restriction restriction : index.restrictions(area)) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            Geometry geometry = restriction.getGeometry();
            double distance = rule.clearanceM(dn) + halfWidth;
            if (geometry.getDimension() < 2 && rule.getHalfWidthM() != null) {
                distance += rule.getHalfWidthM();
            }
            if (!near(geometry, distance, area) || inside != null && !inside.intersects(geometry)) {
                continue;
            }
            nodeZones.add(nodeZone(geometry, distance));
            // точку нельзя пересечь под углом или пройти через её зону: её обходят с отступом правила, как запрет
            if (rule.forbid() || geometry.getDimension() == 0) {
                forbid.add(new Zone(restriction.getId(), geometry, distance));
            } else {
                Special special = new Special(restriction.getId(), restriction.getType(), rule, geometry, distance);
                specialList.add(special);
                if (special.polygon) {
                    // спецпроход — один прямой участок с полосой margin_m за полигоном: внутри полосы узлов нет, а
                    // узлы для пересечения стоят у её внешней границы, чтобы отрезок через дорогу был прямым от узла до узла
                    marginZones.add(geometry.buffer(rule.getMarginM() - MARGIN_NODE_INSET_M, MARGIN_QUADRANT_SEGMENTS));
                    crossingNodeZones.add(nodeZone(geometry, Math.max(distance, rule.getMarginM() - 2 * SIMPLIFY_M - NODE_OFFSET_M)));
                }
            }
        }
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        for (NetworkSegment segment : index.segments(area)) {
            double distance = network.clearanceM(dn) + halfWidth + rules.diameter(segment.getDiameter()).getWidthM() / 2;
            if (near(segment.getGeometry(), distance, area) && (inside == null || inside.intersects(segment.getGeometry()))) {
                nodeZones.add(nodeZone(segment.getGeometry(), distance));
                specialList.add(new Special(segment.getId(), HEAT_NETWORK, network, segment.getGeometry(), distance));
            }
        }

        for (Zone zone : forbid) {
            forbidZones.insert(zone.envelope, zone);
        }
        for (Special special : specialList) {
            specials.insert(special.zone.envelope, special);
        }
        forbidZones.build();
        specials.build();

        Map<Coordinate, Coordinate[]> candidates = new LinkedHashMap<>();
        Map<Coordinate, Double> offsets = new java.util.HashMap<>();
        for (Geometry nodeZone : nodeZones) {
            double offset = (Double) nodeZone.getUserData();
            for (int i = 0; i < nodeZone.getNumGeometries(); i++) {
                Polygon polygon = (Polygon) nodeZone.getGeometryN(i);
                addConvexVertices(polygon.getExteriorRing(), true, offset, candidates, offsets);
                for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                    addConvexVertices(polygon.getInteriorRingN(h), false, offset, candidates, offsets);
                }
            }
        }
        // Вдоль сторон дорог и путей нужны точки поворота, иначе при остром угле к дороге остаётся только обход её конца.
        for (Geometry nodeZone : crossingNodeZones) {
            for (Coordinate c : Densifier.densify(nodeZone.getBoundary(), CROSSING_STEP_M).getCoordinates()) {
                candidates.putIfAbsent(c, null);
            }
        }
        List<PreparedGeometry> margins = new ArrayList<>();
        for (Geometry marginZone : marginZones) {
            margins.add(PreparedGeometryFactory.prepare(marginZone));
        }
        // узлы только в области: зоны длинных дорог и труб иначе приносят узлы на километры вокруг, а граф O(n²)
        for (Map.Entry<Coordinate, Coordinate[]> candidate : candidates.entrySet()) {
            Coordinate c = candidate.getKey();
            Coordinate[] ring = candidate.getValue();
            if (!area.contains(c) || inside != null && !inside.intersects(factory.createPoint(c)) || insideAny(margins, c)) {
                continue;
            }
            if (!insideAnyZone(c)) {
                nodes.add(c);
                rings.add(ring);
                continue;
            }
            // угол зоны JOIN_MITRE попал в зону соседа, хотя проход между углами бывает: вместо него две вершины
            // описанного вокруг угла многоугольника, они ближе к объекту
            Coordinate[] halves = ring == null ? null : halves(c, ring, offsets.get(c));
            if (halves != null) {
                addNode(halves[0], new Coordinate[] {ring[0], halves[1]}, area, inside, margins);
                addNode(halves[1], new Coordinate[] {halves[0], ring[1]}, area, inside, margins);
            }
        }
    }

    private void addNode(Coordinate c, Coordinate[] ring, Envelope area, PreparedGeometry inside, List<PreparedGeometry> margins) {
        if (area.contains(c) && (inside == null || inside.intersects(factory.createPoint(c))) && !insideAnyZone(c)
                && !insideAny(margins, c)) {
            nodes.add(c);
            rings.add(ring);
        }
    }

    /**
     * Две вершины описанного вокруг угла объекта многоугольника в две стороны вместо вершины v угла JOIN_MITRE с
     * соседями ring по кольцу: v отстоит от угла объекта на D/cos(θ/2), они — на D/cos(θ/4), где D — отступ зоны
     * узлов, θ — поворот в v. null — угол слишком острый или соседи ближе нужного сдвига.
     */
    private static Coordinate[] halves(Coordinate v, Coordinate[] ring, double offset) {
        double turn = Math.toRadians(Router.deflectionDeg(ring[0], v, ring[1]));
        double shift = offset * (Math.tan(turn / 2) - Math.tan(turn / 4));
        if (turn < Math.toRadians(HALVES_MIN_TURN_DEG) || turn > Math.toRadians(HALVES_MAX_TURN_DEG)
                || shift >= v.distance(ring[0]) || shift >= v.distance(ring[1])) {
            return null;
        }
        return new Coordinate[] {toward(v, ring[0], shift), toward(v, ring[1], shift)};
    }

    private static Coordinate toward(Coordinate from, Coordinate to, double distance) {
        double share = distance / from.distance(to);
        return new Coordinate(from.x + (to.x - from.x) * share, from.y + (to.y - from.y) * share);
    }

    private boolean insideAny(List<PreparedGeometry> polygons, Coordinate c) {
        Geometry point = factory.createPoint(c);
        for (PreparedGeometry polygon : polygons) {
            if (polygon.getGeometry().getEnvelopeInternal().contains(c) && polygon.intersects(point)) {
                return true;
            }
        }
        return false;
    }

    /** Отступ оси новой сети от полигона ОКС для диаметра набора. */
    public double oksClearance() {
        return oksClearance;
    }

    /** Точка в прежней зоне запрета с углами JOIN_MITRE, см. Zone#coversMitre. */
    public boolean insideForbid(Coordinate c) {
        return insideForbid(c, true);
    }

    /** Точка в зоне запрета: точной (там не ставится камера ветвления) или, если {@code mitre}, прежней. */
    public boolean insideForbid(Coordinate c, boolean mitre) {
        Geometry point = factory.createPoint(c);
        for (Object item : forbidZones.query(new Envelope(c))) {
            Zone zone = (Zone) item;
            if (mitre ? zone.coversMitre(c, point) : zone.covers(c)) {
                return true;
            }
        }
        return false;
    }

    /** Узлы visibility graph: выпуклые снаружи вершины зон и точки вдоль сторон дорог, не лежащие ни в одной зоне. */
    public List<Coordinate> nodes() {
        return nodes;
    }

    /**
     * Отрезок от узла node к other касается зоны узла: оба соседа по кольцу лежат по одну сторону от него. Ребро,
     * которое входит в вершину зоны и уходит через неё «внутрь угла», в кратчайшем пути не бывает, и его можно не
     * проверять; узлы без кольца (точки вдоль дорог) допускают любые рёбра.
     */
    public boolean tangent(int node, Coordinate other) {
        Coordinate[] ring = rings.get(node);
        if (ring == null) {
            return true;
        }
        Coordinate v = nodes.get(node);
        double dx = other.x - v.x;
        double dy = other.y - v.y;
        double prev = dx * (ring[0].y - v.y) - dy * (ring[0].x - v.x);
        double next = dx * (ring[1].y - v.y) - dy * (ring[1].x - v.x);
        return prev * next >= -TANGENT_EPS;
    }

    /**
     * Вес отрезка a–b с учётом специальных частей или {@code Double.NaN}, если отрезок недопустим. Объект из
     * {@code ignored} не проверяется, если a или b лежит на нём; зона запрета из {@code ignored} не проверяется
     * вовсе: так финальный прямой участок к точке подключения проходит зону своего ОКС (приложение 18.09, п. 2.2).
     */
    public double edgeWeight(Coordinate a, Coordinate b, Set<String> ignored) {
        return edgeWeight(a, b, ignored, false, false);
    }

    /**
     * Вес ребра графа. {@code aNode} и {@code bNode} говорят, что конец — узел графа, а не начало или конец пути.
     * Узел может лежать в полосе margin_m пересечённого объекта. Тогда путь до узла прошёл часть этой полосы по
     * соседнему ребру, и эта часть тоже специальная, хотя соседнее ребро объект не пересекает. Ребро получает её
     * вес по нижней оценке, иначе переход со сдвигом вбок через узлы в полосе легче прямого.
     */
    public double edgeWeight(Coordinate a, Coordinate b, Set<String> ignored, boolean aNode, boolean bNode) {
        LineString edge = factory.createLineString(new Coordinate[] {a, b});
        Envelope envelope = edge.getEnvelopeInternal();
        for (Object item : forbidZones.query(envelope)) {
            Zone zone = (Zone) item;
            if (!ignored.contains(zone.id) && zone.box.intersects(a, b) && zone.intersects(a, b, 0, aNode || bNode)) {
                return Double.NaN;
            }
        }
        List<Special> crossed = new ArrayList<>();
        for (Object item : specials.query(envelope)) {
            Special special = (Special) item;
            if (!special.zone.box.intersects(a, b) || ignored.contains(special.id) && touches(special, a, b)) {
                continue;
            }
            if (!special.crossedBy(a, b, edge)) {
                if (special.zone.intersects(a, b, 0, aNode || bNode)) {
                    return Double.NaN;
                }
                continue;
            }
            if (!crossingAllowed(special, a, b)) {
                return Double.NaN;
            }
            crossed.add(special);
        }
        double beforeA = 0;
        double beforeB = 0;
        for (Special special : crossed) {
            double extra = special.rule.getKSpecial() - 1;
            if (aNode) {
                beforeA = Math.max(beforeA, extra * specialBeyond(special, a, b));
            }
            if (bNode) {
                beforeB = Math.max(beforeB, extra * specialBeyond(special, b, a));
            }
        }
        return weight(edge.getLength(), spans(edge, crossed, Set.of())) + beforeA + beforeB;
    }

    /**
     * Отрезок a–b обычный: не пересекает объектов специального прохода и держит отступы с запасом margin. Так
     * проверяется хорда срезки угла: трасса у самой зоны графа не прошла бы проверку отступов, когда сборка
     * поднимает Ду по длине на ступень, а запас как у узлов графа эту ступень покрывает.
     */
    public boolean plain(Coordinate a, Coordinate b, Set<String> ignored, double margin) {
        LineString edge = factory.createLineString(new Coordinate[] {a, b});
        Envelope envelope = new Envelope(a, b);
        envelope.expandBy(margin);
        for (Object item : forbidZones.query(envelope)) {
            Zone zone = (Zone) item;
            if (!ignored.contains(zone.id) && zone.intersects(a, b, margin, false)) {
                return false;
            }
        }
        for (Object item : specials.query(envelope)) {
            Special special = (Special) item;
            if (ignored.contains(special.id) && touches(special, a, b)) {
                continue;
            }
            if (special.crossedBy(a, b, edge) || special.zone.intersects(a, b, margin, false)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Длина специальной части за концом end, которую проходит любой путь, пришедший в end. У полигона это
     * margin_m минус расстояние от end до полигона: не меньше этого путь идёт по буферу. У линии отсчёт margin_m
     * от ближайшей к end точки пересечения продолжается через узел.
     */
    private double specialBeyond(Special special, Coordinate end, Coordinate other) {
        double nearest = Double.POSITIVE_INFINITY;
        if (special.polygon) {
            nearest = special.object.getGeometry().distance(factory.createPoint(end));
        } else {
            LineIntersector intersector = new RobustLineIntersector();
            for (LineSegment side : special.sidesNear(end, other)) {
                intersector.computeIntersection(end, other, side.p0, side.p1);
                for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                    nearest = Math.min(nearest, end.distance(intersector.getIntersection(k)));
                }
            }
        }
        return Math.max(0, special.rule.getMarginM() - nearest);
    }

    /**
     * Отрезок a–b дальше ALONG_SKIP_M от a пересекает объект из {@code ignored} или идёт по нему: так отрезок пути
     * у врезки ложится на трубу врезки или пересекает её, а проверка отступов их для него пропускает (как
     * leavesNetwork в TreeBuilder).
     */
    public boolean alongIgnored(Coordinate a, Coordinate b, Set<String> ignored) {
        double length = a.distance(b);
        if (ignored.isEmpty() || length <= ALONG_SKIP_M) {
            return false;
        }
        LineString away = factory.createLineString(new Coordinate[] {new LineSegment(a, b).pointAlong(ALONG_SKIP_M / length), b});
        for (Object item : specials.query(away.getEnvelopeInternal())) {
            Special special = (Special) item;
            // по расстоянию, а не intersects: у отрезка, разложенного по сетке, координаты с шумом 1e-15
            if (ignored.contains(special.id) && special.near(away.getCoordinateN(0), b, ALONG_TOL_M)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Специальные части линии от всех пересечённых ею объектов. Пересечение объекта из {@code ignored} не даёт
     * специальной части, если оно у начала или конца линии.
     */
    public List<SpecialSpan> spans(LineString line, Set<String> ignored) {
        List<Special> crossed = new ArrayList<>();
        for (Object item : specials.query(line.getEnvelopeInternal())) {
            Special special = (Special) item;
            if (special.object.intersects(line)) {
                crossed.add(special);
            }
        }
        return spans(line, crossed, ignored);
    }

    public static double weight(double length, List<SpecialSpan> spans) {
        double weight = length;
        for (SpecialSpan span : spans) {
            weight += (span.getKSpecial() - 1) * (span.getToM() - span.getFromM());
        }
        return weight;
    }

    private List<SpecialSpan> spans(LineString line, List<Special> crossed, Set<String> ignored) {
        List<SpecialSpan> raw = new ArrayList<>();
        Coordinate[] coords = line.getCoordinates();
        double total = line.getLength();
        LineIntersector intersector = new RobustLineIntersector();
        for (Special special : crossed) {
            boolean skipAtEnds = ignored.contains(special.id);
            if (skipAtEnds && special.polygon && touches(special, coords[0], coords[coords.length - 1])) {
                continue;
            }
            double margin = special.rule.getMarginM();
            double start = 0;
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate p0 = coords[i];
                Coordinate p1 = coords[i + 1];
                double length = p0.distance(p1);
                if (length == 0) {
                    continue;
                }
                if (special.polygon) {
                    for (double[] piece : inside(p0, p1, special.marginSides, intersector)) {
                        raw.add(span(start + piece[0], start + piece[1], special));
                    }
                } else {
                    for (LineSegment side : special.sidesNear(p0, p1)) {
                        intersector.computeIntersection(p0, p1, side.p0, side.p1);
                        for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                            double at = start + p0.distance(intersector.getIntersection(k));
                            if (skipAtEnds && (at <= TIE_IN_TOUCH_M || at >= total - TIE_IN_TOUCH_M)) {
                                continue;
                            }
                            raw.add(span(Math.max(0, at - margin), Math.min(total, at + margin), special));
                        }
                    }
                }
                start += length;
            }
        }
        return merge(raw);
    }

    /**
     * Части отрезка p0–p1 внутри полигона со сторонами sides: пары расстояний от p0 до входа и выхода. Отрезок
     * режется в точках пересечения со сторонами, кусок берётся, если его середина внутри. Замена наложения JTS
     * {@code intersection}: оно на каждый отрезок графа строило планарный граф.
     */
    private static List<double[]> inside(Coordinate p0, Coordinate p1, LineSegment[] sides, LineIntersector intersector) {
        TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, p0.distance(p1)));
        for (LineSegment side : sides) {
            if (!meet(p0, p1, side.p0, side.p1)) {
                continue;
            }
            intersector.computeIntersection(p0, p1, side.p0, side.p1);
            for (int k = 0; k < intersector.getIntersectionNum(); k++) {
                cuts.add(p0.distance(intersector.getIntersection(k)));
            }
        }
        List<double[]> result = new ArrayList<>();
        double length = p0.distance(p1);
        Double from = null;
        for (double to : cuts) {
            if (from != null && to > from) {
                double mid = (from + to) / 2 / length;
                Coordinate middle = new Coordinate(p0.x + (p1.x - p0.x) * mid, p0.y + (p1.y - p0.y) * mid);
                RayCrossingCounter counter = new RayCrossingCounter(middle);
                for (LineSegment side : sides) {
                    counter.countSegment(side.p0, side.p1);
                }
                if (counter.getLocation() != Location.EXTERIOR) {
                    if (!result.isEmpty() && result.get(result.size() - 1)[1] == from) {
                        result.get(result.size() - 1)[1] = to;
                    } else {
                        result.add(new double[] {from, to});
                    }
                }
            }
            from = to;
        }
        return result;
    }

    private static SpecialSpan span(double fromM, double toM, Special special) {
        return new SpecialSpan(fromM, toM, special.id, special.type, special.rule.getKSpecial());
    }

    private static List<SpecialSpan> merge(List<SpecialSpan> raw) {
        raw.sort(Comparator.comparingDouble(SpecialSpan::getFromM));
        List<SpecialSpan> merged = new ArrayList<>();
        SpecialSpan current = null;
        for (SpecialSpan span : raw) {
            if (current != null && span.getFromM() <= current.getToM() + SPAN_JOIN_M) {
                SpecialSpan top = span.getKSpecial() > current.getKSpecial() ? span : current;
                current = new SpecialSpan(current.getFromM(), Math.max(current.getToM(), span.getToM()),
                        top.getObjectId(), top.getType(), top.getKSpecial());
            } else {
                if (current != null) {
                    merged.add(current);
                }
                current = span;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }

    private static boolean crossingAllowed(Special special, Coordinate a, Coordinate b) {
        Double minAngleDeg = special.rule.getMinAngleDeg();
        LineIntersector intersector = new RobustLineIntersector();
        boolean sideCrossed = false;
        for (LineSegment side : special.sidesNear(a, b)) {
            intersector.computeIntersection(a, b, side.p0, side.p1);
            if (!intersector.hasIntersection()) {
                continue;
            }
            // отрезок, идущий вдоль стороны или оси объекта, пересечением не считается
            if (intersector.getIntersectionNum() == LineIntersector.COLLINEAR_INTERSECTION) {
                return false;
            }
            if (minAngleDeg != null && acuteAngleDeg(a, b, side) < minAngleDeg + ANGLE_MARGIN_DEG) {
                return false;
            }
            sideCrossed = true;
        }
        // отрезок целиком внутри полигона: угол входа проверить нельзя, поэтому запрещаем
        return sideCrossed;
    }

    private static double acuteAngleDeg(Coordinate a, Coordinate b, LineSegment side) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = side.p1.x - side.p0.x;
        double vy = side.p1.y - side.p0.y;
        return Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), Math.abs(ux * vx + uy * vy)));
    }

    private boolean touches(Special special, Coordinate a, Coordinate b) {
        return special.near(a, a, TIE_IN_TOUCH_M) || special.near(b, b, TIE_IN_TOUCH_M);
    }

    private boolean insideAnyZone(Coordinate c) {
        Envelope envelope = new Envelope(c);
        for (Object item : forbidZones.query(envelope)) {
            if (((Zone) item).covers(c)) {
                return true;
            }
        }
        for (Object item : specials.query(envelope)) {
            if (((Special) item).zone.covers(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(Geometry geometry, double distance, Envelope area) {
        Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
        envelope.expandBy(distance);
        return envelope.intersects(area);
    }

    // Буфер строится на SIMPLIFY_M шире: после упрощения граница не заходит внутрь настоящего отступа.
    private static Geometry zone(Geometry geometry, double distance) {
        Geometry buffer = BufferOp.bufferOp(geometry, distance + SIMPLIFY_M, ZONE_BUFFER);
        return TopologyPreservingSimplifier.simplify(buffer, SIMPLIFY_M);
    }

    // Зона узлов снаружи зоны запрета не меньше чем на NODE_OFFSET_M с учётом упрощения обеих зон; в userData её отступ.
    private static Geometry nodeZone(Geometry geometry, double distance) {
        Geometry zone = zone(geometry, distance + 2 * SIMPLIFY_M + NODE_OFFSET_M);
        zone.setUserData(distance + 3 * SIMPLIFY_M + NODE_OFFSET_M);
        return zone;
    }

    private static void addConvexVertices(LinearRing ring, boolean shell, double offset, Map<Coordinate, Coordinate[]> out,
            Map<Coordinate, Double> offsets) {
        Coordinate[] coords = ring.getCoordinates();
        int n = coords.length - 1;
        if (n < 3) {
            return;
        }
        int obstacleTurn = shell == Orientation.isCCW(coords) ? Orientation.COUNTERCLOCKWISE : Orientation.CLOCKWISE;
        for (int i = 0; i < n; i++) {
            Coordinate prev = coords[(i + n - 1) % n];
            if (Orientation.index(prev, coords[i], coords[i + 1]) == obstacleTurn
                    && out.putIfAbsent(coords[i], new Coordinate[] {prev, coords[i + 1]}) == null) {
                offsets.put(coords[i], offset);
            }
        }
    }

    /** Точечный объект: вырожденные стороны в его точках, расстояние до них — до самих точек. */
    private static LineSegment[] points(Geometry puntal) {
        Coordinate[] coords = puntal.getCoordinates();
        LineSegment[] result = new LineSegment[coords.length];
        for (int i = 0; i < coords.length; i++) {
            result[i] = new LineSegment(coords[i], coords[i]);
        }
        return result;
    }

    private static LineSegment[] segments(Geometry lineal) {
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
