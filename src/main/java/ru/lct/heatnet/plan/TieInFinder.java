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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
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
    /** Первое окно поиска ближайших, м; дальше оно растёт вчетверо, см. {@link #nearest}. */
    private static final double FIRST_REACH_M = 64;
    /** Запас окна запроса к индексу: объект на самом краю не должен выпасть из-за округления. */
    private static final double WINDOW_EXTRA_M = 1.0;
    /**
     * Врезка, вынесенная из полосы margin_m дороги, стоит на столько дальше её границы вдоль трубы: буфер строится
     * хордами и лежит внутри полосы на миллиметры. Спецучасток через дорогу из камеры сборка начинает в самой камере:
     * граница полосы ближе MERGE_M.
     */
    private static final double BAND_EXTRA_M = 0.05;
    private static final int MARGIN_QUADRANT_SEGMENTS = 16;

    private final InputData input;
    private final Rules rules;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, Integer> linksByChamber = new HashMap<>();
    /**
     * Участки и камеры сети по рамке, элемент — номер во входе: город — сотни тысяч участков, и перебор всех на
     * каждую врезку был главной ценой.
     */
    private final STRtree segmentIndex = new STRtree();
    private final STRtree chamberIndex = new STRtree();
    private final Envelope segmentExtent = new Envelope();
    private final Envelope chamberExtent = new Envelope();
    private final Map<String, NetworkSegment> segmentById = new HashMap<>();
    private final SpecialObjects specials;
    /** Части оси трубы в полосе margin_m полигонов спецпрохода, по длине от начала оси; считаются раз на трубу. */
    private final Map<String, double[][]> bandsBySegment = new ConcurrentHashMap<>();
    /** Буфер margin_m полигона спецпрохода; строится при первой трубе рядом. */
    private final Map<SpecialObjects.Special, Geometry> bandBySpecial = new ConcurrentHashMap<>();
    private final double maxMargin;

    TieInFinder(InputData input, Rules rules) {
        this(input, rules, new SpecialObjects(input, rules));
    }

    TieInFinder(InputData input, Rules rules, SpecialObjects specials) {
        this.input = input;
        this.rules = rules;
        this.specials = specials;
        this.maxMargin = specials.all.stream().mapToDouble(special -> special.rule.getMarginM()).max().orElse(0);
        for (int i = 0; i < input.getSegments().size(); i++) {
            NetworkSegment segment = input.getSegments().get(i);
            segmentIndex.insert(segment.getGeometry().getEnvelopeInternal(), i);
            segmentExtent.expandToInclude(segment.getGeometry().getEnvelopeInternal());
            segmentById.put(segment.getId(), segment);
        }
        for (int i = 0; i < input.getChambers().size(); i++) {
            Envelope envelope = input.getChambers().get(i).getGeometry().getEnvelopeInternal();
            chamberIndex.insert(envelope, i);
            chamberExtent.expandToInclude(envelope);
        }
        segmentIndex.build();
        chamberIndex.build();
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
            NetworkSegment segment = input.getSegments().get((Integer) item);
            if (within(segment.getGeometry(), point.getCoordinate(), TOUCH_M)) {
                result.add(segment.getId());
            }
        }
        return result;
    }

    /**
     * То же, что line.isWithinDistance(point, distance), без объектов DistanceOp: рамка запроса у длинных труб города
     * задевает десятки участков, и проверка шла на каждую врезку.
     */
    private static boolean within(LineString line, Coordinate c, double distance) {
        Coordinate[] coords = line.getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            if (Distance.pointToSegment(c, coords[i], coords[i + 1]) <= distance) {
                return true;
            }
        }
        return false;
    }

    /** Кандидаты для точек в порядке точек и расстояния; dn — расчётный диаметр новой сети у врезки. */
    List<TieCandidate> find(List<Point> points, int dn) {
        Map<String, TieCandidate> byKey = new LinkedHashMap<>();
        for (Point point : points) {
            for (Chamber chamber : nearest(input.getChambers(), chamberIndex, chamberExtent, point,
                    c -> c.getGeometry().distance(point), c -> c.getGeometry().getEnvelopeInternal().distance(point.getEnvelopeInternal()))) {
                TieCandidate candidate = chamberCandidate(chamber);
                if (candidate != null) {
                    byKey.putIfAbsent(candidate.nodeKey(), candidate);
                }
            }
            for (NetworkSegment segment : nearest(input.getSegments(), segmentIndex, segmentExtent, point,
                    s -> s.getGeometry().distance(point),
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
        Envelope window = new Envelope(tie.getPoint().getCoordinate());
        window.expandBy(TOUCH_M + WINDOW_EXTRA_M);
        for (int i : sorted(segmentIndex.query(window))) {
            NetworkSegment segment = input.getSegments().get(i);
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

    /** Врезка у проекции точки на участок врезки tie в трубу, как у {@link #find}; null — участок не подходит. */
    TieCandidate onSamePipe(TieCandidate tie, Point point, int dn) {
        NetworkSegment segment = segmentById.get(tie.getExistingObjectId());
        return segment == null ? null : pipeCandidate(segment, point, dn);
    }

    /** Врезка на участке врезки tie в трубу в shift м вдоль оси от проекции point; null — участок не подходит. */
    TieCandidate shifted(TieCandidate tie, Coordinate point, double shift, int dn) {
        NetworkSegment segment = segmentById.get(tie.getExistingObjectId());
        if (segment == null) {
            return null;
        }
        return pipeCandidate(segment, new LengthIndexedLine(segment.getGeometry()).project(point) + shift, dn);
    }

    /** Ось участка врезки tie в трубу; null — такого участка нет. */
    LineString pipe(TieCandidate tie) {
        NetworkSegment segment = segmentById.get(tie.getExistingObjectId());
        return segment == null ? null : segment.getGeometry();
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
        double position = outside(bands(segment), Math.max(gap, Math.min(length - gap, at)), gap, length - gap);
        Point tie = factory.createPoint(indexed.extractPoint(position));

        ChamberRule rule = rules.chamberRule();
        Chamber best = null;
        // камеры дальше max_dist_m отбрасываются и при переборе всех; окно с запасом, обход — в порядке входа
        Envelope window = new Envelope(tie.getCoordinate());
        window.expandBy(rule.getMaxDistM() + DIST_MARGIN_M + WINDOW_EXTRA_M);
        for (int i : sorted(chamberIndex.query(window))) {
            Chamber chamber = input.getChambers().get(i);
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

    /**
     * Разъяснение 6: спецпроход — один прямой участок, угол проверяется в точке входа. Камера врезки в полосе margin_m
     * дороги или трамвайных путей начинала бы спецучастки внутри неё, поэтому врезка переносится вдоль трубы за
     * ближайшую границу полосы в пределах [low, high]. Места вне полос на трубе нет — точка остаётся.
     */
    static double outside(double[][] bands, double position, double low, double high) {
        if (!inBand(bands, position)) {
            return position;
        }
        double best = Double.NaN;
        for (double[] band : bands) {
            for (double at : new double[] {band[0] - BAND_EXTRA_M, band[1] + BAND_EXTRA_M}) {
                if (low <= at && at <= high && !inBand(bands, at)
                        && (Double.isNaN(best) || Math.abs(at - position) < Math.abs(best - position))) {
                    best = at;
                }
            }
        }
        return Double.isNaN(best) ? position : best;
    }

    private static boolean inBand(double[][] bands, double at) {
        for (double[] band : bands) {
            if (band[0] < at && at < band[1]) {
                return true;
            }
        }
        return false;
    }

    /** Части оси участка в буфере margin_m полигонов спецпрохода: [от, до] по длине оси. */
    private double[][] bands(NetworkSegment segment) {
        return bandsBySegment.computeIfAbsent(segment.getId(), id -> {
            LineString line = segment.getGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(line);
            List<double[]> result = new ArrayList<>();
            Envelope window = new Envelope(line.getEnvelopeInternal());
            window.expandBy(maxMargin);
            for (SpecialObjects.Special special : specials.zonesNear(window)) {
                double margin = special.rule.getMarginM();
                if (!special.polygon || !special.geometry.isWithinDistance(line, margin)) {
                    continue;
                }
                Geometry inside = bandBySpecial.computeIfAbsent(special,
                        s -> s.geometry.buffer(margin, MARGIN_QUADRANT_SEGMENTS)).intersection(line);
                for (int i = 0; i < inside.getNumGeometries(); i++) {
                    Coordinate[] c = inside.getGeometryN(i).getCoordinates();
                    double a = indexed.indexOf(c[0]);
                    double b = indexed.indexOf(c[c.length - 1]);
                    result.add(new double[] {Math.min(a, b), Math.max(a, b)});
                }
            }
            return result.toArray(new double[0][]);
        });
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
            NetworkSegment segment = input.getSegments().get((Integer) item);
            LineString line = segment.getGeometry();
            if (line.getCoordinateN(0).distance(at) <= TOUCH_M
                    || line.getCoordinateN(line.getNumPoints() - 1).distance(at) <= TOUCH_M) {
                result.add(segment);
            }
        }
        return result;
    }

    /**
     * NEAREST ближайших к point по distance, при равенстве — в порядке входа. Объекты берутся из index окнами вокруг
     * точки, окно растёт вчетверо, пока NEAREST-й найденный не окажется ближе края окна: за окном рамки, а значит и
     * геометрии дальше. Внутри окна расстояние до рамки не больше расстояния до геометрии, поэтому точное считается
     * только у объектов, чья рамка не дальше NEAREST-го найденного. Прежний перебор с сортировкой всех объектов
     * города по рамке на каждую точку занимал пятую часть расчёта района.
     */
    static <T> List<T> nearest(List<T> items, STRtree index, Envelope extent, Point point,
            ToDoubleFunction<T> distance, ToDoubleFunction<T> bound) {
        if (items.isEmpty()) {
            return List.of();
        }
        for (double reach = FIRST_REACH_M; ; reach *= 4) {
            Envelope window = new Envelope(point.getCoordinate());
            window.expandBy(reach);
            List<double[]> found = nearest(items, sorted(index.query(window)), distance, bound);
            boolean all = window.covers(extent);
            if (all || found.size() >= NEAREST && found.get(NEAREST - 1)[0] < reach) {
                List<T> result = new ArrayList<>();
                for (int k = 0; k < Math.min(NEAREST, found.size()); k++) {
                    result.add(items.get((int) found.get(k)[1]));
                }
                return result;
            }
        }
    }

    /** Прежний отбор среди объектов с номерами ids по возрастанию: расстояние и номер NEAREST ближайших по порядку. */
    private static <T> List<double[]> nearest(List<T> items, int[] ids, ToDoubleFunction<T> distance, ToDoubleFunction<T> bound) {
        Integer[] byBound = new Integer[ids.length];
        double[] bounds = new double[ids.length];
        for (int k = 0; k < ids.length; k++) {
            byBound[k] = k;
            bounds[k] = bound.applyAsDouble(items.get(ids[k]));
        }
        Arrays.sort(byBound, Comparator.comparingDouble(k -> bounds[k]));
        List<double[]> found = new ArrayList<>();
        double worst = Double.POSITIVE_INFINITY;
        for (int k : byBound) {
            if (bounds[k] > worst) {
                break;
            }
            found.add(new double[] {distance.applyAsDouble(items.get(ids[k])), ids[k]});
            if (found.size() >= NEAREST) {
                found.sort(Comparator.comparingDouble((double[] d) -> d[0]).thenComparingDouble(d -> d[1]));
                worst = found.get(NEAREST - 1)[0];
            }
        }
        found.sort(Comparator.comparingDouble((double[] d) -> d[0]).thenComparingDouble(d -> d[1]));
        return found;
    }

    /** Номера из запроса к индексу по возрастанию. */
    private static int[] sorted(List<?> items) {
        int[] ids = new int[items.size()];
        for (int k = 0; k < ids.length; k++) {
            ids[k] = (Integer) items.get(k);
        }
        Arrays.sort(ids);
        return ids;
    }
}
