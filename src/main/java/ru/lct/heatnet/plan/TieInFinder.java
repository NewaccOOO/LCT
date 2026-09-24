package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
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
    /** Врезка в трубу не ближе этого к точке, от которой она ищется: метр подотрезка и запас на округление. */
    private static final double MIN_TIE_M = 1.2;
    private static final double END_GAP_EXTRA_M = 1.0;
    /** Запас к otherTieM у сдвинутой врезки: ось участка может быть не прямой. */
    private static final double ALONG_EXTRA_M = 1.0;

    private final InputData input;
    private final Rules rules;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, Integer> linksByChamber = new HashMap<>();
    /** Участки сети по рамке: город — сотни тысяч участков, и перебор всех на каждую врезку был главной ценой. */
    private final STRtree segmentIndex = new STRtree();

    TieInFinder(InputData input, Rules rules) {
        this.input = input;
        this.rules = rules;
        for (NetworkSegment segment : input.getSegments()) {
            segmentIndex.insert(segment.getGeometry().getEnvelopeInternal(), segment);
        }
        segmentIndex.build();
        for (Chamber chamber : input.getChambers()) {
            linksByChamber.put(chamber.getId(), links(chamber).size());
        }
    }

    /** Участки сети не дальше TOUCH_M от точки, по возрастанию порядка входа. */
    private Set<String> touching(Point point) {
        Envelope envelope = new Envelope(point.getCoordinate());
        envelope.expandBy(TOUCH_M);
        Set<String> result = new TreeSet<>();
        for (Object item : segmentIndex.query(envelope)) {
            NetworkSegment segment = (NetworkSegment) item;
            if (segment.getGeometry().isWithinDistance(point, TOUCH_M)) {
                result.add(segment.getId());
            }
        }
        return result;
    }

    /** Кандидаты для точек в порядке точек и расстояния; dn — расчётный диаметр новой сети у врезки. */
    List<TieCandidate> find(List<Point> points, int dn) {
        Map<String, TieCandidate> byKey = new LinkedHashMap<>();
        for (Point point : points) {
            for (Chamber chamber : nearest(input.getChambers(), c -> c.getGeometry().distance(point),
                    c -> c.getGeometry().getEnvelopeInternal().distance(point.getEnvelopeInternal()))) {
                TieCandidate candidate = chamberCandidate(chamber);
                if (candidate != null) {
                    byKey.putIfAbsent(candidate.nodeKey(), candidate);
                }
            }
            for (NetworkSegment segment : nearest(input.getSegments(), s -> s.getGeometry().distance(point),
                    s -> s.getGeometry().getEnvelopeInternal().distance(point.getEnvelopeInternal()))) {
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

    int links(String chamberId) {
        return linksByChamber.getOrDefault(chamberId, 0);
    }

    /** Предел участков у камеры с учётом правила ответвлений. */
    int nodeLimit() {
        ChamberRule rule = rules.chamberRule();
        return Math.min(rule.getMaxSegments(), rule.getMaxBranches() + 1);
    }

    TieCandidate chamberCandidate(Chamber chamber) {
        int capacity = nodeLimit() - links(chamber.getId());
        if (capacity <= 0) {
            return null;
        }
        return new TieCandidate(chamber.getId(), TieCandidate.HEAT_CHAMBER, chamber.getDiameter(), chamber.getGeometry(),
                touching(chamber.getGeometry()), capacity);
    }

    TieCandidate pipeCandidate(NetworkSegment segment, Point point, int dn) {
        LengthIndexedLine indexed = new LengthIndexedLine(segment.getGeometry());
        double at = indexed.project(point.getCoordinate());
        // точка у самой трубы: отрезок от перпендикуляра короче метра, его не пропускает правило длины подотрезка,
        // и трасса уходит петлёй по зоне трубы; врезка сдвигается вдоль оси к середине участка
        double across = point.getCoordinate().distance(indexed.extractPoint(at));
        if (across < MIN_TIE_M) {
            double shift = Math.sqrt(MIN_TIE_M * MIN_TIE_M - across * across);
            at += at < segment.getGeometry().getLength() / 2 ? shift : -shift;
        }
        return pipeCandidate(segment, at, dn);
    }

    /** Врезка в участок в точке {@code at} м от начала оси, прижатой к отступу от концов. */
    TieCandidate pipeCandidate(NetworkSegment segment, double at, int dn) {
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
        for (Chamber chamber : input.getChambers()) {
            double distance = chamber.getGeometry().distance(tie);
            if (distance > rule.getMaxDistM() + DIST_MARGIN_M || links(chamber.getId()) + 1 > rule.getMaxSegments()) {
                continue;
            }
            // приложение 18.09, п. 2.4: камера не дальше 10 м со свободным местом обязательна; из нескольких — ближняя
            if (best == null || distance < best.getGeometry().distance(tie)) {
                best = chamber;
            }
        }
        if (best != null) {
            return chamberCandidate(best);
        }
        // камера режет каждую проходящую трубу на два примыкания (п. 2.1): на дублях труб места для нового участка нет
        Set<String> touching = touching(tie);
        int capacity = nodeLimit() - PIPE_SEGMENTS * Math.max(1, touching.size());
        if (capacity <= 0) {
            return null;
        }
        return new TieCandidate(segment.getId(), TieCandidate.HEAT_NETWORK, segment.getDiameter(), tie, touching, capacity);
    }

    private static boolean same(TieCandidate a, TieCandidate b) {
        return a.getExistingObjectId().equals(b.getExistingObjectId())
                && a.getPoint().distance(b.getPoint()) <= SAME_POINT_M;
    }

    /** Существующие участки камеры: те, у которых конец не дальше 0,5 м от неё. */
    private List<NetworkSegment> links(Chamber chamber) {
        List<NetworkSegment> result = new ArrayList<>();
        Coordinate at = chamber.getGeometry().getCoordinate();
        Envelope envelope = new Envelope(at);
        envelope.expandBy(TOUCH_M);
        for (Object item : segmentIndex.query(envelope)) {
            NetworkSegment segment = (NetworkSegment) item;
            LineString line = segment.getGeometry();
            if (line.getCoordinateN(0).distance(at) <= TOUCH_M
                    || line.getCoordinateN(line.getNumPoints() - 1).distance(at) <= TOUCH_M) {
                result.add(segment);
            }
        }
        return result;
    }

    /**
     * NEAREST ближайших по distance, при равенстве — в порядке входа. Расстояние до рамки не больше расстояния до
     * геометрии, поэтому точное считается только у объектов, чья рамка не дальше NEAREST-го найденного: на городе
     * в тысячи участков по сотни вершин полный перебор с сортировкой занимал десятую часть расчёта.
     */
    private static <T> List<T> nearest(List<T> items, ToDoubleFunction<T> distance, ToDoubleFunction<T> bound) {
        Integer[] byBound = new Integer[items.size()];
        double[] bounds = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            byBound[i] = i;
            bounds[i] = bound.applyAsDouble(items.get(i));
        }
        Arrays.sort(byBound, Comparator.comparingDouble(i -> bounds[i]));
        List<double[]> found = new ArrayList<>();
        double worst = Double.POSITIVE_INFINITY;
        for (int i : byBound) {
            if (bounds[i] > worst) {
                break;
            }
            found.add(new double[] {distance.applyAsDouble(items.get(i)), i});
            if (found.size() >= NEAREST) {
                found.sort(Comparator.comparingDouble((double[] d) -> d[0]).thenComparingDouble(d -> d[1]));
                worst = found.get(NEAREST - 1)[0];
            }
        }
        found.sort(Comparator.comparingDouble((double[] d) -> d[0]).thenComparingDouble(d -> d[1]));
        List<T> result = new ArrayList<>();
        for (int k = 0; k < Math.min(NEAREST, found.size()); k++) {
            result.add(items.get((int) found.get(k)[1]));
        }
        return result;
    }
}
