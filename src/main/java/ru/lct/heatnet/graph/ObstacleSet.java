package ru.lct.heatnet.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RectangleLineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
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
    private static final BufferParameters ZONE_BUFFER = new BufferParameters(
            BufferParameters.DEFAULT_QUADRANT_SEGMENTS, BufferParameters.CAP_SQUARE,
            BufferParameters.JOIN_MITRE, BufferParameters.DEFAULT_MITRE_LIMIT);

    private final GeometryFactory factory = new GeometryFactory();
    private final STRtree forbidZones = new STRtree();
    private final STRtree specials = new STRtree();
    private final List<Coordinate> nodes = new ArrayList<>();
    // соседи узла по кольцу его зоны или null у точек вдоль дорог: ребро полезно, только если касается зоны
    private final List<Coordinate[]> rings = new ArrayList<>();

    private static final class Zone {
        final Geometry geometry;
        final PreparedGeometry prepared;
        // Длинный отрезок задевает рамки многих зон; проверка отрезка против рамки дешевле PreparedGeometry.
        final RectangleLineIntersector box;

        Zone(Geometry geometry) {
            this.geometry = geometry;
            this.prepared = PreparedGeometryFactory.prepare(geometry);
            this.box = new RectangleLineIntersector(geometry.getEnvelopeInternal());
        }
    }

    private static final class Special {
        final String id;
        final String type;
        final RestrictionRule rule;
        final boolean polygon;
        final PreparedGeometry object;
        final Zone zone;
        final Geometry marginZone;
        final LineSegment[] sides;

        Special(String id, String type, RestrictionRule rule, Geometry geometry, Geometry zone) {
            this.id = id;
            this.type = type;
            this.rule = rule;
            this.polygon = geometry.getDimension() == 2;
            this.object = PreparedGeometryFactory.prepare(geometry);
            this.zone = new Zone(zone);
            this.marginZone = polygon ? geometry.buffer(rule.getMarginM(), MARGIN_QUADRANT_SEGMENTS) : null;
            this.sides = segments(polygon ? geometry.getBoundary() : geometry);
        }
    }

    public ObstacleSet(InputData input, Rules rules, Envelope area, int dn) {
        double halfWidth = rules.diameter(dn).getWidthM() / 2;
        List<Geometry> forbid = new ArrayList<>();
        List<Geometry> nodeZones = new ArrayList<>();
        List<Geometry> crossingNodeZones = new ArrayList<>();
        List<Special> specialList = new ArrayList<>();

        double oksDistance = rules.restriction(OKS_EXISTING).clearanceM(dn) + halfWidth;
        for (ExistingOks oks : input.getExistingOks()) {
            if (near(oks.getGeometry(), oksDistance, area)) {
                forbid.add(zone(oks.getGeometry(), oksDistance));
                nodeZones.add(nodeZone(oks.getGeometry(), oksDistance));
            }
        }
        for (Restriction restriction : input.getRestrictions()) {
            RestrictionRule rule = rules.restriction(restriction.getType());
            Geometry geometry = restriction.getGeometry();
            double distance = rule.clearanceM(dn) + halfWidth;
            if (geometry.getDimension() < 2 && rule.getHalfWidthM() != null) {
                distance += rule.getHalfWidthM();
            }
            if (!near(geometry, distance, area)) {
                continue;
            }
            Geometry nodeZone = nodeZone(geometry, distance);
            nodeZones.add(nodeZone);
            if (rule.getMinAngleDeg() != null && geometry.getDimension() == 2) {
                crossingNodeZones.add(nodeZone);
            }
            // точку нельзя пересечь под углом или пройти через её зону: её обходят с отступом правила, как запрет
            if (rule.forbid() || geometry.getDimension() == 0) {
                forbid.add(zone(geometry, distance));
            } else {
                specialList.add(new Special(restriction.getId(), restriction.getType(), rule, geometry, zone(geometry, distance)));
            }
        }
        RestrictionRule network = rules.restriction(HEAT_NETWORK);
        for (NetworkSegment segment : input.getSegments()) {
            double distance = network.clearanceM(dn) + halfWidth + rules.diameter(segment.getDiameter()).getWidthM() / 2;
            if (near(segment.getGeometry(), distance, area)) {
                nodeZones.add(nodeZone(segment.getGeometry(), distance));
                specialList.add(new Special(segment.getId(), HEAT_NETWORK, network, segment.getGeometry(),
                        zone(segment.getGeometry(), distance)));
            }
        }

        if (!forbid.isEmpty()) {
            Geometry union = UnaryUnionOp.union(forbid);
            for (int i = 0; i < union.getNumGeometries(); i++) {
                Geometry part = union.getGeometryN(i);
                forbidZones.insert(part.getEnvelopeInternal(), new Zone(part));
            }
        }
        for (Special special : specialList) {
            specials.insert(special.zone.geometry.getEnvelopeInternal(), special);
        }
        forbidZones.build();
        specials.build();

        Map<Coordinate, Coordinate[]> candidates = new LinkedHashMap<>();
        for (Geometry nodeZone : nodeZones) {
            for (int i = 0; i < nodeZone.getNumGeometries(); i++) {
                Polygon polygon = (Polygon) nodeZone.getGeometryN(i);
                addConvexVertices(polygon.getExteriorRing(), true, candidates);
                for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                    addConvexVertices(polygon.getInteriorRingN(h), false, candidates);
                }
            }
        }
        // Вдоль сторон дорог и путей нужны точки поворота, иначе при остром угле к дороге остаётся только обход её конца.
        for (Geometry nodeZone : crossingNodeZones) {
            for (Coordinate c : Densifier.densify(nodeZone.getBoundary(), CROSSING_STEP_M).getCoordinates()) {
                candidates.putIfAbsent(c, null);
            }
        }
        for (Map.Entry<Coordinate, Coordinate[]> candidate : candidates.entrySet()) {
            if (!insideAnyZone(candidate.getKey())) {
                nodes.add(candidate.getKey());
                rings.add(candidate.getValue());
            }
        }
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
     * {@code ignored} не проверяется, если a или b лежит на нём.
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
            if (zone.box.intersects(a, b) && zone.prepared.intersects(edge)) {
                return Double.NaN;
            }
        }
        List<Special> crossed = new ArrayList<>();
        for (Object item : specials.query(envelope)) {
            Special special = (Special) item;
            if (!special.zone.box.intersects(a, b) || ignored.contains(special.id) && touches(special, a, b)) {
                continue;
            }
            if (!special.object.intersects(edge)) {
                if (special.zone.prepared.intersects(edge)) {
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
            for (LineSegment side : special.sides) {
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
            if (ignored.contains(special.id) && special.object.getGeometry().isWithinDistance(away, ALONG_TOL_M)) {
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

    static double weight(double length, List<SpecialSpan> spans) {
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
                    Geometry pieces = factory.createLineString(new Coordinate[] {p0, p1}).intersection(special.marginZone);
                    for (int g = 0; g < pieces.getNumGeometries(); g++) {
                        Coordinate[] piece = pieces.getGeometryN(g).getCoordinates();
                        if (piece.length < 2) {
                            continue;
                        }
                        double d0 = p0.distance(piece[0]);
                        double d1 = p0.distance(piece[piece.length - 1]);
                        raw.add(span(start + Math.min(d0, d1), start + Math.max(d0, d1), special));
                    }
                } else {
                    for (LineSegment side : special.sides) {
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
        for (LineSegment side : special.sides) {
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
        Geometry object = special.object.getGeometry();
        return object.isWithinDistance(factory.createPoint(a), TIE_IN_TOUCH_M)
                || object.isWithinDistance(factory.createPoint(b), TIE_IN_TOUCH_M);
    }

    private boolean insideAnyZone(Coordinate c) {
        Geometry point = factory.createPoint(c);
        Envelope envelope = new Envelope(c);
        for (Object item : forbidZones.query(envelope)) {
            if (((Zone) item).prepared.intersects(point)) {
                return true;
            }
        }
        for (Object item : specials.query(envelope)) {
            if (((Special) item).zone.prepared.intersects(point)) {
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

    // Зона узлов снаружи зоны запрета не меньше чем на NODE_OFFSET_M с учётом упрощения обеих зон.
    private static Geometry nodeZone(Geometry geometry, double distance) {
        return zone(geometry, distance + 2 * SIMPLIFY_M + NODE_OFFSET_M);
    }

    private static void addConvexVertices(LinearRing ring, boolean shell, Map<Coordinate, Coordinate[]> out) {
        Coordinate[] coords = ring.getCoordinates();
        int n = coords.length - 1;
        if (n < 3) {
            return;
        }
        int obstacleTurn = shell == Orientation.isCCW(coords) ? Orientation.COUNTERCLOCKWISE : Orientation.CLOCKWISE;
        for (int i = 0; i < n; i++) {
            Coordinate prev = coords[(i + n - 1) % n];
            if (Orientation.index(prev, coords[i], coords[i + 1]) == obstacleTurn) {
                out.putIfAbsent(coords[i], new Coordinate[] {prev, coords[i + 1]});
            }
        }
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
