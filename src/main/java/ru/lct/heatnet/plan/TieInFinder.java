package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatnet.calc.ReconPart;
import ru.lct.heatnet.calc.ReconstructionCalculator;
import ru.lct.heatnet.calc.TieInLoad;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.rules.ChamberRule;
import ru.lct.heatnet.rules.Rules;

/**
 * Кандидаты врезки (D-9): для каждой точки три ближайшие камеры и перпендикулярные проекции на три ближайших
 * участка. Проекция не дальше {@code max_dist_m} от камеры, у которой после подключения будет не больше
 * {@code max_segments} участков, становится врезкой в эту камеру.
 */
final class TieInFinder {
    static final int NEAREST = 3;
    /** Конец участка совпадает с камерой и объект касается врезки, если расстояние не больше этого (A-9). */
    static final double TOUCH_M = 0.5;
    private static final int PIPE_SEGMENTS = 2;
    /** Запас к max_dist_m: валидатор считает расстояние по координатам, округлённым до 9 знаков. */
    private static final double DIST_MARGIN_M = 0.1;
    private static final double SAME_POINT_M = 1.0;
    private static final double END_GAP_EXTRA_M = 1.0;
    /** Запас к otherTieM у сдвинутой врезки: ось участка может быть не прямой. */
    private static final double ALONG_EXTRA_M = 1.0;
    /** Сколько объектов над реконструкцией даёт кандидатов врезки, см. {@link #aboveReconstruction}. */
    private static final int ABOVE_OBJECTS = 3;

    private final InputData input;
    private final Rules rules;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, Integer> linksByChamber = new HashMap<>();
    private final Map<String, NetworkSegment> segmentById = new HashMap<>();
    private final Map<String, Chamber> chamberById = new HashMap<>();

    TieInFinder(InputData input, Rules rules) {
        this.input = input;
        this.rules = rules;
        for (Chamber chamber : input.getChambers()) {
            linksByChamber.put(chamber.getId(), links(chamber).size());
            chamberById.put(chamber.getId(), chamber);
        }
        for (NetworkSegment segment : input.getSegments()) {
            segmentById.put(segment.getId(), segment);
        }
    }

    /** Кандидаты для точек в порядке точек и расстояния; dn — расчётный диаметр новой сети у врезки. */
    List<TieCandidate> find(List<Point> points, int dn) {
        Map<String, TieCandidate> byKey = new LinkedHashMap<>();
        for (Point point : points) {
            for (Chamber chamber : nearest(input.getChambers(), c -> c.getGeometry().distance(point))) {
                TieCandidate candidate = chamberCandidate(chamber);
                if (candidate != null) {
                    byKey.putIfAbsent(candidate.nodeKey(), candidate);
                }
            }
            for (NetworkSegment segment : nearest(input.getSegments(), s -> s.getGeometry().distance(point))) {
                TieCandidate candidate = pipeCandidate(segment, point, dn);
                if (candidate != null && byKey.values().stream().noneMatch(c -> same(c, candidate))) {
                    byKey.put(candidate.nodeKey(), candidate);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Врезки в участки, которых касается {@code tie}, на расстоянии больше {@code otherTieM} от неё вдоль оси в обе
     * стороны, для второго варианта по правилу variants (D-11), когда среди ближайших кандидатов отличной врезки
     * нет. Точка ближе max_dist_m к камере со свободным местом, как и в {@link #find}, становится врезкой в камеру.
     */
    List<TieCandidate> along(TieCandidate tie, int dn, double otherTieM) {
        List<TieCandidate> result = new ArrayList<>();
        for (NetworkSegment segment : input.getSegments()) {
            if (!segment.getGeometry().isWithinDistance(tie.getPoint(), TOUCH_M)) {
                continue;
            }
            double at = new LengthIndexedLine(segment.getGeometry()).project(tie.getPoint().getCoordinate());
            for (double shift : new double[] {-otherTieM - ALONG_EXTRA_M, otherTieM + ALONG_EXTRA_M}) {
                TieCandidate candidate = pipeCandidate(segment, at + shift, dn);
                if (candidate == null || result.stream().anyMatch(c -> same(c, candidate))) {
                    continue;
                }
                boolean otherObject = !candidate.getExistingObjectId().equals(tie.getExistingObjectId());
                if (otherObject || candidate.getPoint().distance(tie.getPoint()) > otherTieM + DIST_MARGIN_M) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    /**
     * Врезки выше реконструкции. Ближайшие к ОКС кандидаты часто стоят на тонкой сети, и расход {@code flow} заставляет
     * менять её трубы до магистрали. Для каждого такого кандидата по цепочке upstream_object_id находится самый верхний
     * реконструируемый участок, и врезки ставятся в ABOVE_OBJECTS первых объектов выше него: камеры со свободным местом
     * и участки у нижнего конца. Там добавка помещается в существующий диаметр: трасса длиннее, зато без замены труб.
     * Объектов несколько, потому что у стыка коротких участков выход из врезки огибает соседнюю трубу изломами, и
     * дерево упирается в правило поворотов. Возвращает только кандидатов, которых нет среди {@code candidates}.
     */
    List<TieCandidate> aboveReconstruction(List<TieCandidate> candidates, double flow, int dn) {
        Map<String, TieCandidate> result = new LinkedHashMap<>();
        for (TieCandidate candidate : candidates) {
            TieInLoad load = new TieInLoad(candidate.nodeKey(), candidate.getExistingObjectId(),
                    candidate.getExistingObjectType(), candidate.getPoint(), flow);
            Set<String> rebuilt = new HashSet<>();
            for (ReconPart part : ReconstructionCalculator.calculate(input, rules, List.of(load)).getParts()) {
                rebuilt.add(part.getExistingObjectId());
            }
            if (rebuilt.isEmpty()) {
                continue;
            }
            List<String> chain = upstreamChain(candidate.getExistingObjectId());
            int top = -1;
            for (int k = 0; k < chain.size(); k++) {
                if (rebuilt.contains(chain.get(k))) {
                    top = k;
                }
            }
            for (TieCandidate above : above(chain, top, dn)) {
                result.putIfAbsent(above.nodeKey(), above);
            }
        }
        for (TieCandidate candidate : candidates) {
            result.remove(candidate.nodeKey());
            result.values().removeIf(other -> same(other, candidate));
        }
        return new ArrayList<>(result.values());
    }

    /** До ABOVE_OBJECTS объектов цепочки после индекса top, куда можно врезаться: камеры со свободным местом и участки. */
    private List<TieCandidate> above(List<String> chain, int top, int dn) {
        List<TieCandidate> result = new ArrayList<>();
        if (top < 0) {
            return result;
        }
        Geometry below = geometry(chain.get(top));
        for (int k = top + 1; k < chain.size() && result.size() < ABOVE_OBJECTS; k++) {
            Chamber chamber = chamberById.get(chain.get(k));
            TieCandidate candidate;
            if (chamber != null) {
                candidate = chamberCandidate(chamber);
                below = chamber.getGeometry();
            } else {
                LineString line = segmentById.get(chain.get(k)).getGeometry();
                Coordinate near = DistanceOp.nearestPoints(line, below)[0];
                candidate = pipeCandidate(segmentById.get(chain.get(k)), new LengthIndexedLine(line).project(near), dn);
                below = line;
            }
            if (candidate != null && result.stream().noneMatch(c -> c.nodeKey().equals(candidate.nodeKey()))) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Объект и все объекты выше него по upstream_object_id до источника, без источника. */
    private List<String> upstreamChain(String objectId) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String next = objectId;
        while (next != null && seen.add(next) && (segmentById.containsKey(next) || chamberById.containsKey(next))) {
            chain.add(next);
            next = segmentById.containsKey(next) ? segmentById.get(next).getUpstreamId()
                    : chamberById.get(next).getUpstreamId();
        }
        return chain;
    }

    private Geometry geometry(String objectId) {
        return segmentById.containsKey(objectId) ? segmentById.get(objectId).getGeometry()
                : chamberById.get(objectId).getGeometry();
    }

    int links(String chamberId) {
        return linksByChamber.getOrDefault(chamberId, 0);
    }

    /** Предел участков у камеры с учётом правила ответвлений. */
    int nodeLimit() {
        ChamberRule rule = rules.chamberRule();
        return Math.min(rule.getMaxSegments(), rule.getMaxBranches() + 1);
    }

    private TieCandidate chamberCandidate(Chamber chamber) {
        int capacity = nodeLimit() - links(chamber.getId());
        if (capacity <= 0) {
            return null;
        }
        Set<String> ignored = new TreeSet<>();
        for (NetworkSegment segment : input.getSegments()) {
            if (segment.getGeometry().isWithinDistance(chamber.getGeometry(), TOUCH_M)) {
                ignored.add(segment.getId());
            }
        }
        return new TieCandidate(chamber.getId(), TieCandidate.HEAT_CHAMBER, chamber.getDiameter(), chamber.getGeometry(),
                ignored, capacity);
    }

    private TieCandidate pipeCandidate(NetworkSegment segment, Point point, int dn) {
        return pipeCandidate(segment, new LengthIndexedLine(segment.getGeometry()).project(point.getCoordinate()), dn);
    }

    /** Врезка в участок в точке {@code at} м от начала оси, прижатой к отступу от концов. */
    private TieCandidate pipeCandidate(NetworkSegment segment, double at, int dn) {
        LineString line = segment.getGeometry();
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double length = line.getLength();
        // у конца участка его сосед по сети ближе отступа, и из точки врезки не выйти ни одним отрезком
        double gap = rules.restriction(TieCandidate.HEAT_NETWORK).clearanceM(dn) + rules.diameter(dn).getWidthM() / 2
                + rules.diameter(segment.getDiameter()).getWidthM() / 2 + END_GAP_EXTRA_M;
        if (length <= 2 * gap) {
            return null;
        }
        double position = Math.max(gap, Math.min(length - gap, at));
        Point tie = factory.createPoint(indexed.extractPoint(position));

        ChamberRule rule = rules.chamberRule();
        Chamber best = null;
        double bestCost = 0;
        for (Chamber chamber : input.getChambers()) {
            double distance = chamber.getGeometry().distance(tie);
            if (distance > rule.getMaxDistM() + DIST_MARGIN_M || links(chamber.getId()) + 1 > rule.getMaxSegments()) {
                continue;
            }
            // протокол 16.09.2026 п. 8: из камер в радиусе 10 м берётся дешёвая — без реконструкции под dn, при
            // равной стоимости ближняя
            double cost = dn > chamber.getDiameter() ? rules.chamberCost(dn) : 0;
            if (best == null || cost < bestCost || cost == bestCost && distance < best.getGeometry().distance(tie)) {
                best = chamber;
                bestCost = cost;
            }
        }
        if (best != null) {
            return chamberCandidate(best);
        }
        Set<String> ignored = new TreeSet<>();
        for (NetworkSegment other : input.getSegments()) {
            if (other.getGeometry().isWithinDistance(tie, TOUCH_M)) {
                ignored.add(other.getId());
            }
        }
        return new TieCandidate(segment.getId(), TieCandidate.HEAT_NETWORK, segment.getDiameter(), tie, ignored,
                nodeLimit() - PIPE_SEGMENTS);
    }

    private static boolean same(TieCandidate a, TieCandidate b) {
        return a.getExistingObjectId().equals(b.getExistingObjectId())
                && a.getPoint().distance(b.getPoint()) <= SAME_POINT_M;
    }

    /** Существующие участки камеры: те, у которых конец не дальше 0,5 м от неё. */
    private List<NetworkSegment> links(Chamber chamber) {
        List<NetworkSegment> result = new ArrayList<>();
        Coordinate at = chamber.getGeometry().getCoordinate();
        for (NetworkSegment segment : input.getSegments()) {
            LineString line = segment.getGeometry();
            if (line.getCoordinateN(0).distance(at) <= TOUCH_M
                    || line.getCoordinateN(line.getNumPoints() - 1).distance(at) <= TOUCH_M) {
                result.add(segment);
            }
        }
        return result;
    }

    private static <T> List<T> nearest(List<T> items, ToDoubleFunction<T> distance) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(distance));
        return sorted.subList(0, Math.min(NEAREST, sorted.size()));
    }
}
