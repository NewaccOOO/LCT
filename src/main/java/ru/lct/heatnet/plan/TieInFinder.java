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
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.ChamberRule;
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

final class TieInFinder {
    static final int NEAREST = 3;
    static final double TOUCH_M = 0.5;
    private static final int PIPE_SEGMENTS = 2;
    private static final double DIST_MARGIN_M = 0.1;
    private static final double SAME_POINT_M = 1.0;
    private static final double MIN_TIE_M = 1.2;
    private static final double END_GAP_EXTRA_M = 1.0;
    private static final double ALONG_EXTRA_M = 1.0;
    private static final double FIRST_REACH_M = 64;
    private static final double WINDOW_EXTRA_M = 1.0;
    private static final double BAND_EXTRA_M = 0.05;
    private static final int MARGIN_QUADRANT_SEGMENTS = 16;

    private final InputData input;
    private final Rules rules;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, Integer> linksByChamber = new HashMap<>();
    private final STRtree segmentIndex = new STRtree();
    private final STRtree chamberIndex = new STRtree();
    private final Envelope segmentExtent = new Envelope();
    private final Envelope chamberExtent = new Envelope();
    private final Map<String, NetworkSegment> segmentById = new HashMap<>();
    private final Map<String, Chamber> chamberById = new HashMap<>();
    private final SpecialObjects specials;
    private final Map<String, double[][]> bandsBySegment = new ConcurrentHashMap<>();
    private final Map<SpecialObjects.Special, Geometry> bandBySpecial = new ConcurrentHashMap<>();
    private final double maxMargin;
    private static final double FREE_STEP_M = 0.5;
    static final boolean FREE_TIE = Boolean.parseBoolean(System.getProperty("heatnet.tie.free", "true"));
    private volatile STRtree forbidIndex;
    private double forbidReach;
    private final Map<List<Object>, Double> freeByKey = new ConcurrentHashMap<>();

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
            Coordinate at = chamber.getGeometry().getCoordinate();
            int ends = 0;
            int through = 0;
            for (NetworkSegment segment : links(chamber)) {
                LineString line = segment.getGeometry();
                if (line.getCoordinateN(0).distance(at) <= TOUCH_M
                        || line.getCoordinateN(line.getNumPoints() - 1).distance(at) <= TOUCH_M) {
                    ends++;
                } else {
                    through++;
                }
            }
            linksByChamber.put(chamber.getId(), ends + PIPE_SEGMENTS * through);
            chamberById.put(chamber.getId(), chamber);
        }
    }

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

    private static boolean within(LineString line, Coordinate c, double distance) {
        Coordinate[] coords = line.getCoordinates();
        for (int i = 0; i + 1 < coords.length; i++) {
            if (Distance.pointToSegment(c, coords[i], coords[i + 1]) <= distance) {
                return true;
            }
        }
        return false;
    }

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
                for (TieCandidate candidate : new TieCandidate[] {pipeCandidate(segment, point, dn), endCandidate(segment, point, dn)}) {
                    if (candidate != null && byKey.values().stream().noneMatch(c -> same(c, candidate))) {
                        byKey.put(candidate.nodeKey(), candidate);
                    }
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    List<TieCandidate> direct(Point point, int dn) {
        List<TieCandidate> result = new ArrayList<>();
        for (NetworkSegment segment : nearest(input.getSegments(), segmentIndex, segmentExtent, point,
                s -> s.getGeometry().distance(point), s -> s.getGeometry().getEnvelopeInternal().distance(point.getEnvelopeInternal()))) {
            for (TieCandidate candidate : new TieCandidate[] {pipeCandidate(segment, point, dn, false), projected(segment, point, dn)}) {
                if (candidate != null && result.stream().noneMatch(c -> same(c, candidate))) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    private TieCandidate projected(NetworkSegment segment, Point point, int dn) {
        LineString line = segment.getGeometry();
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double length = line.getLength();
        double gap = endGap(segment, dn);
        if (length <= 2 * gap) {
            return null;
        }
        double at = indexed.project(point.getCoordinate());
        double across = point.getCoordinate().distance(indexed.extractPoint(at));
        if (across < MIN_TIE_M) {
            double shift = Math.sqrt(MIN_TIE_M * MIN_TIE_M - across * across);
            at += at < length / 2 ? shift : -shift;
        }
        return candidateAt(segment, factory.createPoint(indexed.extractPoint(Math.max(gap, Math.min(length - gap, at)))));
    }

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

    List<TieCandidate> around(Point point, int dn, double radius, double step) {
        return around(point, dn, radius, step, false);
    }

    List<TieCandidate> around(Point point, int dn, double radius, double step, boolean inBand) {
        Map<String, TieCandidate> byKey = new LinkedHashMap<>();
        Envelope window = new Envelope(point.getCoordinate());
        window.expandBy(radius);
        for (int i : sorted(chamberIndex.query(window))) {
            Chamber chamber = input.getChambers().get(i);
            TieCandidate candidate = chamber.getGeometry().distance(point) <= radius ? chamberCandidate(chamber) : null;
            if (candidate != null) {
                byKey.putIfAbsent(candidate.nodeKey(), candidate);
            }
        }
        for (int i : sorted(segmentIndex.query(window))) {
            NetworkSegment segment = input.getSegments().get(i);
            if (!segment.getGeometry().isWithinDistance(point, radius)) {
                continue;
            }
            for (double at = 0; at <= segment.getGeometry().getLength(); at += step) {
                TieCandidate candidate = pipeCandidate(segment, at, dn);
                if (candidate != null) {
                    byKey.putIfAbsent(candidate.nodeKey(), candidate);
                }
                TieCandidate banded = inBand ? bandCandidate(segment, at, dn) : null;
                if (banded != null) {
                    byKey.putIfAbsent(banded.nodeKey(), banded);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    int links(String chamberId) {
        return linksByChamber.getOrDefault(chamberId, 0);
    }

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

    List<TieCandidate> ownPipes(TieCandidate tie, Point point, int dn) {
        Chamber own = tie.isChamber() ? chamberById.get(tie.getExistingObjectId()) : null;
        NetworkSegment pipe = tie.isChamber() ? null : segmentById.get(tie.getExistingObjectId());
        List<NetworkSegment> segments = own != null ? links(own) : pipe != null ? List.of(pipe) : List.of();
        List<TieCandidate> result = new ArrayList<>();
        for (NetworkSegment segment : segments) {
            TieCandidate candidate = pipeCandidate(segment, point, dn);
            if (candidate == null) {
                continue;
            }
            result.add(candidate);
        }
        return result;
    }

    TieCandidate shifted(TieCandidate tie, Coordinate point, double shift, int dn) {
        NetworkSegment segment = segmentById.get(tie.getExistingObjectId());
        if (segment == null) {
            return null;
        }
        return pipeCandidate(segment, new LengthIndexedLine(segment.getGeometry()).project(point) + shift, dn);
    }

    LineString pipe(TieCandidate tie) {
        NetworkSegment segment = segmentById.get(tie.getExistingObjectId());
        return segment == null ? null : segment.getGeometry();
    }

    TieCandidate pipeCandidate(NetworkSegment segment, Point point, int dn) {
        return pipeCandidate(segment, point, dn, FREE_TIE);
    }

    TieCandidate pipeCandidate(NetworkSegment segment, Point point, int dn, boolean free) {
        LengthIndexedLine indexed = new LengthIndexedLine(segment.getGeometry());
        double at = indexed.project(point.getCoordinate());
        double across = point.getCoordinate().distance(indexed.extractPoint(at));
        if (across < MIN_TIE_M) {
            double shift = Math.sqrt(MIN_TIE_M * MIN_TIE_M - across * across);
            at += at < segment.getGeometry().getLength() / 2 ? shift : -shift;
        }
        return pipeCandidate(segment, at, dn, free);
    }

    TieCandidate endCandidate(NetworkSegment segment, Point point, int dn) {
        LineString line = segment.getGeometry();
        double at = new LengthIndexedLine(line).project(point.getCoordinate());
        double length = line.getLength();
        double gap = endGap(segment, dn);
        Coordinate first = line.getCoordinateN(0);
        Coordinate last = line.getCoordinateN(line.getNumPoints() - 1);
        Coordinate end = at < gap ? first : at > length - gap ? last : null;
        if (length <= 2 * gap || end == null || !deadEnd(segment, end) || point.getCoordinate().distance(end) < MIN_TIE_M
                || inBand(bands(segment), end == first ? 0 : length)) {
            return null;
        }
        return candidateAt(segment, factory.createPoint(end));
    }

    TieCandidate pipeCandidate(NetworkSegment segment, double at, int dn) {
        return pipeCandidate(segment, at, dn, FREE_TIE);
    }

    TieCandidate pipeCandidate(NetworkSegment segment, double at, int dn, boolean free) {
        LineString line = segment.getGeometry();
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double length = line.getLength();
        double gap = endGap(segment, dn);
        if (length <= 2 * gap) {
            return null;
        }
        double position = outside(bands(segment), Math.max(gap, Math.min(length - gap, at)), gap, length - gap);
        if (free) {
            double from = position;
            position = freeByKey.computeIfAbsent(List.of(segment.getId(), dn, from), key -> forbidden(indexed.extractPoint(from), dn)
                    ? free(indexed, bands(segment), from, gap, length - gap, dn) : from);
        }
        return candidateAt(segment, factory.createPoint(indexed.extractPoint(position)));
    }

    private TieCandidate bandCandidate(NetworkSegment segment, double at, int dn) {
        LineString line = segment.getGeometry();
        double length = line.getLength();
        double gap = endGap(segment, dn);
        if (length <= 2 * gap || at < gap || at > length - gap || !inBand(bands(segment), at)) {
            return null;
        }
        Coordinate c = new LengthIndexedLine(line).extractPoint(at);
        return forbidden(c, dn) ? null : candidateAt(segment, factory.createPoint(c));
    }

    private TieCandidate candidateAt(NetworkSegment segment, Point tie) {
        ChamberRule rule = rules.chamberRule();
        Chamber best = null;
        Envelope window = new Envelope(tie.getCoordinate());
        window.expandBy(rule.getMaxDistM() + DIST_MARGIN_M + WINDOW_EXTRA_M);
        for (int i : sorted(chamberIndex.query(window))) {
            Chamber chamber = input.getChambers().get(i);
            double distance = chamber.getGeometry().distance(tie);
            if (distance > rule.getMaxDistM() + DIST_MARGIN_M || links(chamber.getId()) + 1 > rule.getMaxSegments()) {
                continue;
            }
            if (best == null || distance < best.getGeometry().distance(tie)) {
                best = chamber;
            }
        }
        if (best != null) {
            return chamberCandidate(best);
        }
        Set<String> touching = touching(tie);
        int capacity = nodeLimit() - PIPE_SEGMENTS * Math.max(1, touching.size());
        if (capacity <= 0) {
            return null;
        }
        return new TieCandidate(segment.getId(), TieCandidate.HEAT_NETWORK, segment.getDiameter(), tie, touching, capacity);
    }

    private double endGap(NetworkSegment segment, int dn) {
        return rules.restriction(TieCandidate.HEAT_NETWORK).clearanceM(dn) + rules.diameter(dn).getWidthM() / 2
                + rules.diameter(segment.getDiameter()).getWidthM() / 2 + END_GAP_EXTRA_M;
    }

    private boolean deadEnd(NetworkSegment segment, Coordinate end) {
        Point point = factory.createPoint(end);
        if (!touching(point).equals(Set.of(segment.getId()))) {
            return false;
        }
        Envelope window = new Envelope(end);
        window.expandBy(TOUCH_M);
        for (Object item : chamberIndex.query(window)) {
            if (input.getChambers().get((Integer) item).getGeometry().getCoordinate().distance(end) <= TOUCH_M) {
                return false;
            }
        }
        return true;
    }

    boolean crowded(TieCandidate candidate) {
        return !candidate.isChamber() && fullNear(candidate.getPoint()) != null;
    }

    private String fullNear(Point tie) {
        ChamberRule rule = rules.chamberRule();
        Envelope window = new Envelope(tie.getCoordinate());
        window.expandBy(rule.getMaxDistM() + DIST_MARGIN_M + WINDOW_EXTRA_M);
        Chamber nearest = null;
        for (int i : sorted(chamberIndex.query(window))) {
            Chamber chamber = input.getChambers().get(i);
            double distance = chamber.getGeometry().distance(tie);
            if (distance <= rule.getMaxDistM() + DIST_MARGIN_M && links(chamber.getId()) + 1 > rule.getMaxSegments()
                    && (nearest == null || distance < nearest.getGeometry().distance(tie))) {
                nearest = chamber;
            }
        }
        return nearest == null ? null : nearest.getId();
    }

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

    private double free(LengthIndexedLine indexed, double[][] bands, double position, double low, double high, int dn) {
        for (double step = FREE_STEP_M; position - step >= low || position + step <= high; step += FREE_STEP_M) {
            for (double at : new double[] {position - step, position + step}) {
                if (low <= at && at <= high && !inBand(bands, at) && !forbidden(indexed.extractPoint(at), dn)) {
                    return at;
                }
            }
        }
        return position;
    }

    boolean forbidden(Coordinate c, int dn) {
        double half = rules.diameter(dn).getWidthM() / 2;
        STRtree index = forbidIndex();
        Envelope window = new Envelope(c);
        window.expandBy(forbidReach);
        Point point = factory.createPoint(c);
        for (Object item : index.query(window)) {
            Object[] entry = (Object[]) item;
            Geometry geometry = (Geometry) entry[0];
            RestrictionRule rule = rules.restriction((String) entry[1]);
            double limit = rule.clearanceM(dn) + half
                    + (geometry.getDimension() == 1 && rule.getHalfWidthM() != null ? rule.getHalfWidthM() : 0);
            if (geometry.isWithinDistance(point, limit - 1e-9)) {
                return true;
            }
        }
        return false;
    }

    private STRtree forbidIndex() {
        STRtree index = forbidIndex;
        if (index == null) {
            synchronized (this) {
                if (forbidIndex == null) {
                    STRtree built = new STRtree();
                    int maxDn = rules.diameters().get(rules.diameters().size() - 1).getDn();
                    double reach = rules.restriction("oks_existing").clearanceM(maxDn);
                    for (ExistingOks oks : input.getExistingOks()) {
                        built.insert(oks.getGeometry().getEnvelopeInternal(), new Object[] {oks.getGeometry(), "oks_existing"});
                    }
                    for (Restriction restriction : input.getRestrictions()) {
                        RestrictionRule rule = rules.restriction(restriction.getType());
                        if (rule.forbid()) {
                            built.insert(restriction.getGeometry().getEnvelopeInternal(),
                                    new Object[] {restriction.getGeometry(), restriction.getType()});
                            reach = Math.max(reach, rule.clearanceM(maxDn) + (rule.getHalfWidthM() == null ? 0 : rule.getHalfWidthM()));
                        }
                    }
                    built.build();
                    forbidReach = reach + rules.diameter(maxDn).getWidthM();
                    forbidIndex = built;
                }
                index = forbidIndex;
            }
        }
        return index;
    }

    private static boolean inBand(double[][] bands, double at) {
        for (double[] band : bands) {
            if (band[0] < at && at < band[1]) {
                return true;
            }
        }
        return false;
    }

    private double[][] bands(NetworkSegment segment) {
        return bandsBySegment.computeIfAbsent(segment.getId(), id -> {
            LineString line = segment.getGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(line);
            List<double[]> result = new ArrayList<>();
            Envelope window = new Envelope(line.getEnvelopeInternal());
            window.expandBy(maxMargin);
            for (SpecialObjects.Special special : specials.zonesNear(window)) {
                double margin = special.rule.getMarginM();
                if (!special.band || !special.geometry.isWithinDistance(line, margin)) {
                    continue;
                }
                Geometry inside = bandBySpecial.computeIfAbsent(special,
                        s -> s.geometry.buffer(margin, MARGIN_QUADRANT_SEGMENTS)).intersection(line);
                for (int i = 0; i < inside.getNumGeometries(); i++) {
                    Coordinate[] c = inside.getGeometryN(i).getCoordinates();
                    if (c.length == 0) {
                        continue;
                    }
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

    private List<NetworkSegment> links(Chamber chamber) {
        List<NetworkSegment> result = new ArrayList<>();
        Coordinate at = chamber.getGeometry().getCoordinate();
        Envelope envelope = new Envelope(at);
        envelope.expandBy(TOUCH_M);
        for (Object item : segmentIndex.query(envelope)) {
            NetworkSegment segment = input.getSegments().get((Integer) item);
            if (within(segment.getGeometry(), at, TOUCH_M)) {
                result.add(segment);
            }
        }
        return result;
    }

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

    private static int[] sorted(List<?> items) {
        int[] ids = new int[items.size()];
        for (int k = 0; k < ids.length; k++) {
            ids[k] = (Integer) items.get(k);
        }
        Arrays.sort(ids);
        return ids;
    }
}
