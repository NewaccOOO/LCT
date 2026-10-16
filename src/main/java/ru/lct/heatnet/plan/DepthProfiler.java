package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.calc.CostCalculator;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.DepthRules;
import ru.lct.heatnet.rules.Rules;

/**
 * Режим с учётом глубины (приложение 18.09, §5): продольный профиль поверх готового 2D-варианта. Трасса в плане не
 * меняется (§5 разрешает не перестраивать маршрут), меняются только глубины, деление участков и стоимость.
 * <p>
 * Цепочка — участки от камеры или точки подключения до следующей камеры или точки подключения через технические
 * узлы. В камерах, точках подключения и врезках глубина обычная, 3,0 м (толкование: §5 о глубине в узлах молчит);
 * если пересечение ближе к узлу, чем нужно на спуск, глубина узла сдвигается (см. free).
 * Специальный участок у газа, кабеля или существующей теплосети лежит на постоянной глубине выше или ниже объекта с
 * просветом табл. 2, у дороги и трамвая — не мельче 1,0 и 1,2 м; спуск и подъём — в соседних участках с уклоном не
 * круче max_slope, ближе к 3,0 м при первой возможности (глубже 3,0 м дороже, Kгл). Глубина каждого специального
 * участка выбирается перебором по цепочке (динамика по участкам): самый дешёвый профиль, в котором все уклоны
 * выполнимы. Излом профиля и переход через 3,0 м делят участок техническим узлом.
 */
final class DepthProfiler {
    /** Уклон с запасом: глубины пишутся до мм, узел ближе SNAP_M к концу участка переносится в конец. */
    private static final double SLOPE_SLACK = 0.99;
    private static final double SNAP_M = 0.1;
    /** Зазор между отрезками постоянной глубины короче этого связывается прямой, без излома. */
    private static final double MIN_BEND_M = 1.0;
    /** Точка касания трассы с сетью ближе этого к концу участка — врезка, а не пересечение. */
    private static final double TOUCH_M = 0.05;
    private static final double EPS = 1e-9;
    /** Рублей за метр участка на метр отклонения от обычной глубины: только выбор из равных по стоимости. */
    private static final double TIE_RUB_M = 1;

    private final Rules rules;
    private final DepthRules depth;
    private final SpecialObjects specials;
    private final GeometryFactory factory = new GeometryFactory();

    /** Участки, для которых профиль не нашёлся: id участков по вариантам, для отчёта. */
    final Map<String, List<String>> conflicts = new HashMap<>();

    DepthProfiler(Rules rules, SpecialObjects specials) {
        this.rules = rules;
        this.depth = rules.depth();
        if (depth == null) {
            throw new IllegalStateException("В файле правил нет раздела depth: режим с глубиной не настроен");
        }
        this.specials = specials;
    }

    /** Допустимые глубины: объединение отрезков [lo, hi]. */
    private static final class Range {
        final List<double[]> parts;

        Range(List<double[]> parts) {
            this.parts = parts;
        }

        static Range of(double lo, double hi) {
            List<double[]> parts = new ArrayList<>();
            parts.add(new double[] {lo, hi});
            return new Range(parts);
        }

        Range and(Range other) {
            List<double[]> result = new ArrayList<>();
            for (double[] a : parts) {
                for (double[] b : other.parts) {
                    double lo = Math.max(a[0], b[0]);
                    double hi = Math.min(a[1], b[1]);
                    if (lo <= hi) {
                        result.add(new double[] {lo, hi});
                    }
                }
            }
            return new Range(result);
        }

        boolean contains(double h) {
            return parts.stream().anyMatch(p -> p[0] - EPS <= h && h <= p[1] + EPS);
        }
    }

    /** Отрезок цепочки на постоянной глубине из range: специальный участок или узел на концах. */
    private static final class Pin {
        final double from;
        final double to;
        final Range range;

        Pin(double from, double to, Range range) {
            this.from = from;
            this.to = to;
            this.range = range;
        }
    }

    Variant profile(Variant variant) {
        if (variant.getSegments().isEmpty()) {
            return variant;
        }
        Set<String> technical = new HashSet<>();
        variant.getNodes().forEach(node -> technical.add(node.getId()));
        Map<String, NewSegment> byStart = new HashMap<>();
        for (NewSegment segment : variant.getSegments()) {
            if (technical.contains(segment.getStartNodeId())) {
                byStart.put(segment.getStartNodeId(), segment);
            }
        }
        String prefix = prefix(variant);
        int[] numbers = {maxNumber(variant.getSegments().stream().map(NewSegment::getId), prefix + "seg_"),
                maxNumber(variant.getNodes().stream().map(TechnicalNode::getId), prefix + "node_")};
        List<Chain> chains = new ArrayList<>();
        Map<String, List<Chain>> byNode = new HashMap<>();
        Set<String> ends = new HashSet<>();
        for (NewSegment first : variant.getSegments()) {
            ends.add(first.getEndNodeId());
            if (technical.contains(first.getStartNodeId())) {
                continue;
            }
            List<NewSegment> segments = new ArrayList<>();
            segments.add(first);
            while (technical.contains(segments.get(segments.size() - 1).getEndNodeId())) {
                segments.add(byStart.get(segments.get(segments.size() - 1).getEndNodeId()));
            }
            Chain chain = new Chain(segments);
            chains.add(chain);
            byNode.computeIfAbsent(chain.from(), key -> new ArrayList<>()).add(chain);
            byNode.computeIfAbsent(chain.to(), key -> new ArrayList<>()).add(chain);
        }
        Map<String, Double> nodeDepth = new HashMap<>();
        for (Chain chain : chains) {
            chain.solve(nodeDepth);
        }
        // узел ближе спуска к пересечению: глубина в узле меняется, если все цепочки узла с ней выполнимы. Сначала
        // камеры ветвления и точки подключения, последним — камера врезки (узел без входящего участка): у неё
        // существующая сеть на 3,0 м, и новая труба выходит из камеры глубже или мельче её
        for (boolean tie : new boolean[] {false, true}) {
            for (Chain chain : chains) {
                for (String node : List.of(chain.to(), chain.from())) {
                    if (chain.chosen == null && ends.contains(node) != tie && !nodeDepth.containsKey(node)) {
                        free(List.of(node), chain, byNode, nodeDepth);
                    }
                }
            }
        }
        // цепочка из одних спецучастков между двумя узлами (перекрёсток улиц): оба узла на одной глубине
        for (Chain chain : chains) {
            if (chain.chosen == null && !nodeDepth.containsKey(chain.from()) && !nodeDepth.containsKey(chain.to())) {
                free(List.of(chain.from(), chain.to()), chain, byNode, nodeDepth);
            }
        }
        cheaperNodes(chains, byNode, nodeDepth, ends);
        List<NewSegment> segments = new ArrayList<>();
        List<TechnicalNode> nodes = new ArrayList<>(variant.getNodes());
        for (Chain chain : chains) {
            if (chain.chosen == null) {
                // профиль с уклоном не помещается: глубина объектов выдерживается, уклон круче (ручная проработка)
                conflicts.computeIfAbsent(variant.getId(), key -> new ArrayList<>()).add(chain.segments.get(0).getId());
                chain.chosen = closest(chain.pins(nodeDepth));
            }
            List<Pin> pins = chain.pins(nodeDepth);
            if (Boolean.getBoolean("heatnet.depth.debug")) {
                for (int k = 1; k + 1 < pins.size(); k++) {
                    if (chain.chosen[k] > depth.getNormalM() + EPS) {
                        boolean over = pins.get(k).range.parts.stream().anyMatch(p -> p[0] <= depth.getNormalM());
                        int seg = 0;
                        while (chain.start[seg + 1] <= pins.get(k).from + EPS) {
                            seg++;
                        }
                        System.err.printf("DEPTHDBG %s under h=%.3f over=%s pinLen=%.1f toA=%.1f toB=%.1f chainLen=%.1f pins=%d nodeA=%.2f nodeB=%.2f dn=%d parts=%s%n",
                                variant.getId(), chain.chosen[k], over, pins.get(k).to - pins.get(k).from,
                                pins.get(k).from, chain.start[chain.segments.size()] - pins.get(k).to,
                                chain.start[chain.segments.size()], pins.size() - 2, chain.chosen[0],
                                chain.chosen[pins.size() - 1], chain.segments.get(seg).getDiameter(),
                                pins.get(k).range.parts.stream().map(p -> String.format("[%.2f,%.2f]", p[0], Math.min(p[1], 99))).collect(java.util.stream.Collectors.joining()));
                    }
                }
            }
            List<double[]> profile = new ArrayList<>();
            for (int k = 0; k < pins.size(); k++) {
                Pin pin = pins.get(k);
                if (k > 0) {
                    profile.addAll(gap(pins.get(k - 1).to, chain.chosen[k - 1], pin.from, chain.chosen[k], true));
                }
                profile.add(new double[] {pin.from, chain.chosen[k]});
                profile.add(new double[] {pin.to, chain.chosen[k]});
            }
            split(chain.segments, chain.start, profile, prefix, numbers, segments, nodes);
        }
        VariantSummary s = variant.getSummary();
        double segmentCost = 0;
        double length = 0;
        for (NewSegment segment : segments) {
            segmentCost += segment.getCost();
            length += segment.getLength();
        }
        double chamberCost = 0;
        for (NewChamber chamber : variant.getChambers()) {
            chamberCost += chamber.getCost();
        }
        double construction = CostCalculator.round2(segmentCost + CostCalculator.round2(chamberCost)
                + s.getExistingChamberTieInCost());
        double calculated = CostCalculator.round2(construction + s.getUnconnectedPenalty());
        length = CostCalculator.round2(length);
        double score = Math.round(rules.score(calculated, length) * 10000) / 10000.0;
        VariantSummary summary = new VariantSummary(s.getId(), s.getVariantId(), s.getRank(), construction,
                s.getChamberConstructionCost(), s.getExistingChamberTieInCount(), s.getExistingChamberTieInCost(),
                s.getUnconnectedPenalty(), calculated, length, score, s.getUnconnectedOksIds());
        return new Variant(variant.getId(), segments, variant.getChambers(), nodes, summary);
    }

    /**
     * Глубины узлов по стоимости. Узел на 3,0 м заставляет спецучасток у самого узла уходить «под» (спуск на 0,4 м
     * короче подъёма на 1 м и больше), хотя «над» Kгл = 1: выше обычной глубины прокладка не дороже. Узел (камера,
     * точка подключения) получает глубину, при которой его цепочки в сумме дешевле всего; кандидаты — глубины
     * ближайших спецучастков и глубины, с которых до них ровно хватает уклона. Камера врезки — только при
     * heatnet.depth.tieFree (существующая сеть на 3,0 м). Покоординатный спуск по узлам, пока стоимость падает.
     */
    private void cheaperNodes(List<Chain> chains, Map<String, List<Chain>> byNode, Map<String, Double> nodeDepth,
            Set<String> ends) {
        if (!Boolean.parseBoolean(System.getProperty("heatnet.depth.cheaperNodes", "true"))) {
            return;
        }
        boolean tieFree = Boolean.getBoolean("heatnet.depth.tieFree");
        double slope = depth.getMaxSlope() * SLOPE_SLACK;
        for (int pass = 0; pass < 10; pass++) {
            boolean changed = false;
            Set<String> seen = new HashSet<>();
            for (Chain chain : chains) {
                for (String node : List.of(chain.from(), chain.to())) {
                    if (!seen.add(node) || !tieFree && !ends.contains(node)) {
                        continue;
                    }
                    List<Chain> incident = new ArrayList<>(new java.util.LinkedHashSet<>(byNode.get(node)));
                    if (incident.stream().noneMatch(this::deep)) {
                        continue;
                    }
                    double current = 0;
                    for (Chain other : incident) {
                        current += other.cost;
                    }
                    TreeSet<Double> candidates = new TreeSet<>();
                    candidates.add(depth.getNormalM());
                    for (Chain other : incident) {
                        Pin pin = other.nearest(node);
                        if (pin == null) {
                            continue;
                        }
                        double d = other.distance(node, pin);
                        for (double h : values(pin, other.inner)) {
                            candidates.add(h);
                            candidates.add(h + slope * d);
                            candidates.add(Math.max(depth.getMinM(), h - slope * d));
                        }
                    }
                    Double before = nodeDepth.get(node);
                    double best = current - 1;
                    Double bestH = null;
                    for (double h : candidates) {
                        nodeDepth.put(node, h);
                        double sum = 0;
                        for (Chain other : incident) {
                            sum += other.solve(nodeDepth) == null ? Double.POSITIVE_INFINITY : other.cost;
                        }
                        if (sum < best) {
                            best = sum;
                            bestH = h;
                        }
                    }
                    if (bestH != null) {
                        nodeDepth.put(node, bestH);
                        changed = true;
                    } else if (before != null) {
                        nodeDepth.put(node, before);
                    } else {
                        nodeDepth.remove(node);
                    }
                    incident.forEach(other -> other.solve(nodeDepth));
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /** Цепочка с участком глубже обычной (Kгл > 1) или без профиля. */
    private boolean deep(Chain chain) {
        if (chain.chosen == null) {
            return true;
        }
        for (double h : chain.chosen) {
            if (h > depth.getNormalM() + EPS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Глубина узла node, при которой выполнима цепочка chain и остальные цепочки узла: ближайшая к обычной из глубин
     * отрезков цепочки. Не нашлась — узел остаётся на обычной глубине.
     */
    private void free(List<String> nodes, Chain chain, Map<String, List<Chain>> byNode, Map<String, Double> nodeDepth) {
        Set<Chain> incident = new java.util.LinkedHashSet<>();
        nodes.forEach(node -> incident.addAll(byNode.get(node)));
        TreeSet<Double> candidates = new TreeSet<>(java.util.Comparator.comparingDouble(
                (Double h) -> Math.abs(h - depth.getNormalM())).thenComparingDouble(h -> h));
        for (Pin pin : chain.inner) {
            candidates.addAll(values(pin, chain.inner));
        }
        for (double h : candidates) {
            nodes.forEach(node -> nodeDepth.put(node, h));
            boolean ok = true;
            for (Chain other : incident) {
                ok &= other.solve(nodeDepth) != null;
            }
            if (ok) {
                return;
            }
        }
        nodes.forEach(nodeDepth::remove);
        incident.forEach(other -> other.solve(nodeDepth));
    }

    /** Цепочка участков между узлами (камера, точка подключения) через технические узлы. */
    private final class Chain {
        final List<NewSegment> segments;
        final double[] start;
        /** Специальные участки с вертикальными требованиями. */
        final List<Pin> inner = new ArrayList<>();
        double[] chosen;
        /** Стоимость профиля chosen по цепочке (с Kгл); бесконечность — профиля нет. */
        double cost;

        Chain(List<NewSegment> segments) {
            this.segments = segments;
            int n = segments.size();
            start = new double[n + 1];
            for (int i = 0; i < n; i++) {
                start[i + 1] = start[i] + segments.get(i).getGeometry().getLength();
            }
            for (int i = 0; i < n; i++) {
                Range range = range(segments.get(i));
                if (range != null) {
                    inner.add(new Pin(start[i], start[i + 1], range));
                }
            }
        }

        String from() {
            return segments.get(0).getStartNodeId();
        }

        String to() {
            return segments.get(segments.size() - 1).getEndNodeId();
        }

        List<Pin> pins(Map<String, Double> nodeDepth) {
            double a = nodeDepth.getOrDefault(from(), depth.getNormalM());
            double b = nodeDepth.getOrDefault(to(), depth.getNormalM());
            List<Pin> pins = new ArrayList<>();
            pins.add(new Pin(0, 0, Range.of(a, a)));
            pins.addAll(inner);
            pins.add(new Pin(start[segments.size()], start[segments.size()], Range.of(b, b)));
            return pins;
        }

        double[] solve(Map<String, Double> nodeDepth) {
            double[] total = {Double.POSITIVE_INFINITY};
            if (inner.isEmpty() && !nodeDepth.containsKey(from()) && !nodeDepth.containsKey(to())) {
                chosen = new double[] {depth.getNormalM(), depth.getNormalM()};
                total[0] = DepthProfiler.this.cost(segments, start, List.of(new double[] {0, depth.getNormalM()},
                        new double[] {start[segments.size()], depth.getNormalM()}));
            } else {
                chosen = DepthProfiler.this.solve(segments, start, pins(nodeDepth), total);
            }
            cost = total[0];
            return chosen;
        }

        /** Ближайший к узлу node специальный участок и расстояние до него по цепочке; null — спецучастков нет. */
        Pin nearest(String node) {
            return inner.isEmpty() ? null : node.equals(from()) ? inner.get(0) : inner.get(inner.size() - 1);
        }

        double distance(String node, Pin pin) {
            return node.equals(from()) ? pin.from : start[segments.size()] - pin.to;
        }
    }

    /**
     * Глубины специального участка по объектам, которые он пересекает; null — обычный участок. Специальный участок
     * всегда на постоянной глубине (§5: участок на постоянной глубине в зоне пересечения): в городе спецпроход улицы —
     * цепочка коротких спецучастков разных зон (дорога, газ, кабель, канализация), и требование «по 2 м с каждой
     * стороны» от пересечения выходит за участок с самим пересечением. Смежные спецучастки поэтому на одной глубине,
     * спуски — в обычных участках. Без вертикальных требований (типы вне табл. 2) — любая глубина не мельче 0,7 м.
     */
    private Range range(NewSegment segment) {
        if (!NetworkAssembler.SPECIAL.equals(segment.getLayingMethod())) {
            return null;
        }
        LineString line = segment.getGeometry();
        double height = rules.diameter(segment.getDiameter()).getHeightM();
        Range range = null;
        for (SpecialObjects.Special special : specials.around(line, segment.getDiameter())) {
            String type = special.rule.getType();
            DepthRules.Crossing crossing = depth.getCrossings().get(type);
            Range need = null;
            if (crossing != null && !special.polygon && crosses(line, special)) {
                double objectHeight = crossing.getHeightM() != null ? crossing.getHeightM() : special.height;
                double over = crossing.getTopM() - crossing.getClearM() - height;
                double under = crossing.getTopM() + objectHeight + crossing.getClearM();
                List<double[]> parts = new ArrayList<>();
                parts.add(new double[] {depth.getMinM(), over});
                parts.add(new double[] {under, Double.MAX_VALUE});
                need = new Range(parts);
            } else if (depth.getMinTopM().containsKey(type) && special.closer(line, special.rule.getMarginM() + 0.01)) {
                need = Range.of(depth.getMinTopM().get(type), Double.MAX_VALUE);
            }
            if (need != null) {
                range = range == null ? need : range.and(need);
            }
        }
        Range any = Range.of(depth.getMinM(), Double.MAX_VALUE);
        return range == null ? any : range.and(any);
    }

    /** Участок пересекает линию объекта не только в своих концах (врезка в сеть пересечением не считается). */
    private boolean crosses(LineString line, SpecialObjects.Special special) {
        if (!special.crossedBy(line)) {
            return false;
        }
        Geometry meet = line.intersection(special.geometry);
        Coordinate a = line.getCoordinateN(0);
        Coordinate b = line.getCoordinateN(line.getNumPoints() - 1);
        for (Coordinate c : meet.getCoordinates()) {
            if (c.distance(a) > TOUCH_M && c.distance(b) > TOUCH_M) {
                return true;
            }
        }
        return false;
    }

    /** Самая дешёвая выполнимая глубина каждого отрезка pins; null — профиля с уклоном нет. */
    private double[] solve(List<NewSegment> chain, double[] start, List<Pin> pins, double[] total) {
        int m = pins.size();
        List<double[]> values = new ArrayList<>();
        for (Pin pin : pins) {
            values.add(values(pin, pins).stream().mapToDouble(Double::doubleValue).toArray());
        }
        double[][] cost = new double[m][];
        int[][] back = new int[m][];
        for (int k = 0; k < m; k++) {
            double[] vs = values.get(k);
            cost[k] = new double[vs.length];
            back[k] = new int[vs.length];
            java.util.Arrays.fill(cost[k], Double.POSITIVE_INFINITY);
            for (int c = 0; c < vs.length; c++) {
                double own = cost(chain, start, List.of(new double[] {pins.get(k).from, vs[c]},
                        new double[] {pins.get(k).to, vs[c]}));
                if (k == 0) {
                    cost[k][c] = own;
                    continue;
                }
                double[] prev = values.get(k - 1);
                for (int p = 0; p < prev.length; p++) {
                    if (cost[k - 1][p] == Double.POSITIVE_INFINITY) {
                        continue;
                    }
                    List<double[]> gap = gap(pins.get(k - 1).to, prev[p], pins.get(k).from, vs[c], false);
                    if (gap == null) {
                        continue;
                    }
                    List<double[]> piece = new ArrayList<>();
                    piece.add(new double[] {pins.get(k - 1).to, prev[p]});
                    piece.addAll(gap);
                    piece.add(new double[] {pins.get(k).from, vs[c]});
                    double sum = cost[k - 1][p] + cost(chain, start, piece) + own;
                    if (sum < cost[k][c] - 1e-6) {
                        cost[k][c] = sum;
                        back[k][c] = p;
                    }
                }
            }
        }
        int best = -1;
        for (int c = 0; c < cost[m - 1].length; c++) {
            if (cost[m - 1][c] < Double.POSITIVE_INFINITY && (best < 0 || cost[m - 1][c] < cost[m - 1][best])) {
                best = c;
            }
        }
        if (best < 0) {
            return null;
        }
        total[0] = cost[m - 1][best];
        double[] chosen = new double[m];
        for (int k = m - 1; k >= 0; k--) {
            chosen[k] = values.get(k)[best];
            best = back[k][best];
        }
        return chosen;
    }

    /**
     * Глубины-кандидаты отрезка pin: ближайшая к обычной в каждой части допустимого и границы частей, а также
     * кандидаты соседей, если они допустимы (смежные специальные участки на одной глубине).
     */
    private TreeSet<Double> values(Pin pin, List<Pin> pins) {
        TreeSet<Double> all = new TreeSet<>();
        for (Pin other : pins) {
            for (double[] part : other.range.parts) {
                all.add(Math.max(part[0], Math.min(depth.getNormalM(), part[1])));
                if (part[1] < Double.MAX_VALUE) {
                    all.add(part[1]);
                }
                all.add(part[0]);
            }
        }
        all.removeIf(h -> !pin.range.contains(h));
        return all;
    }

    /** Без выполнимого профиля: у каждого отрезка глубина, ближайшая к обычной. */
    private double[] closest(List<Pin> pins) {
        double[] chosen = new double[pins.size()];
        for (int k = 0; k < pins.size(); k++) {
            double best = Double.NaN;
            for (double[] part : pins.get(k).range.parts) {
                double h = Math.max(part[0], Math.min(depth.getNormalM(), part[1]));
                if (Double.isNaN(best) || Math.abs(h - depth.getNormalM()) < Math.abs(best - depth.getNormalM())) {
                    best = h;
                }
            }
            chosen[k] = Double.isNaN(best) ? depth.getNormalM() : best;
        }
        return chosen;
    }

    /**
     * Изломы профиля между концом одного отрезка (a, ha) и началом следующего (b, hb): возврат к обычной глубине при
     * первой возможности, иначе встреча двух уклонов. Пустой список — прямая; null — уклоном не связать. force —
     * связать прямой, даже если уклон круче (для участков ручной проработки).
     */
    private List<double[]> gap(double a, double ha, double b, double hb, boolean force) {
        double slope = depth.getMaxSlope() * SLOPE_SLACK;
        double normal = depth.getNormalM();
        double g = b - a;
        double da = Math.abs(ha - normal) / slope;
        double db = Math.abs(hb - normal) / slope;
        List<double[]> result = new ArrayList<>();
        if (da + db <= g + EPS) {
            if (da > EPS) {
                result.add(new double[] {a + da, normal});
            }
            if (db > EPS && g - da - db > EPS) {
                result.add(new double[] {b - db, normal});
            }
            return result;
        }
        boolean sameSide = (ha - normal) * (hb - normal) >= 0;
        if (sameSide && Math.abs(ha - hb) <= slope * g + EPS) {
            if (g < MIN_BEND_M) {
                return result; // короткий зазор — прямая: узел ради сантиметров глубины не нужен
            }
            // обе глубины по одну сторону от обычной: уклоны навстречу друг другу до точки встречи
            double sign = ha > normal || hb > normal ? -1 : 1;
            double at = (ha - hb) * -sign / (2 * slope) + (a + b) / 2;
            at = Math.max(a, Math.min(b, at));
            if (at - a > EPS && b - at > EPS) {
                result.add(new double[] {at, ha + sign * slope * (at - a)});
            }
            return result;
        }
        return force ? result : null;
    }

    /** Стоимость части цепочки по точкам профиля (s, h), линейного между точками, по участкам цепочки. */
    private double cost(List<NewSegment> chain, double[] start, List<double[]> profile) {
        double sum = 0;
        for (int p = 0; p + 1 < profile.size(); p++) {
            double s0 = profile.get(p)[0];
            double s1 = profile.get(p + 1)[0];
            double h0 = profile.get(p)[1];
            double h1 = profile.get(p + 1)[1];
            for (int i = 0; i < chain.size(); i++) {
                double from = Math.max(s0, start[i]);
                double to = Math.min(s1, start[i + 1]);
                if (to - from <= EPS) {
                    continue;
                }
                double ha = h0 + (h1 - h0) * (from - s0) / (s1 - s0);
                double hb = h0 + (h1 - h0) * (to - s0) / (s1 - s0);
                NewSegment segment = chain.get(i);
                sum += (to - from) * rules.diameter(segment.getDiameter()).getNewRubM() * kSpecial(segment)
                        * (k(ha) + k(hb)) / 2;
                // выше обычной глубины Kгл = 1 и стоимость одна: из равных берётся ближе к 3,0 м, со спусками короче
                sum += (to - from) * TIE_RUB_M * (Math.abs(ha - depth.getNormalM()) + Math.abs(hb - depth.getNormalM())) / 2;
            }
        }
        return sum;
    }

    /**
     * Kгл куска, где глубина меняется линейно от h0 до h1. Профиль делится в точке обычной глубины, так что кусок
     * не переходит через неё, и среднее Kгл концов точное (§5).
     */
    private double k(double h) {
        return depth.k(h);
    }

    /** Kспец участка из его 2D-стоимости: L · c(Ду) · Kспец, округлённо до копеек. */
    private double kSpecial(NewSegment segment) {
        double base = CostCalculator.round2(segment.getLength()) * rules.diameter(segment.getDiameter()).getNewRubM();
        return base == 0 ? 1 : Math.round(segment.getCost() / base * 10000) / 10000.0;
    }

    /** Режет участки цепочки в изломах профиля и пишет глубины концов. */
    private void split(List<NewSegment> chain, double[] start, List<double[]> profile, String prefix, int[] numbers,
            List<NewSegment> out, List<TechnicalNode> nodes) {
        for (int i = 0; i < chain.size(); i++) {
            NewSegment segment = chain.get(i);
            double length = start[i + 1] - start[i];
            TreeSet<Double> cuts = new TreeSet<>();
            for (double[] point : profile) {
                double at = point[0] - start[i];
                if (at > SNAP_M && at < length - SNAP_M) {
                    cuts.add(at);
                }
            }
            LengthIndexedLine indexed = new LengthIndexedLine(segment.getGeometry());
            double kSpecial = kSpecial(segment);
            double from = 0;
            String fromId = segment.getStartNodeId();
            List<Double> ends = new ArrayList<>(cuts);
            ends.add(length);
            for (int c = 0; c < ends.size(); c++) {
                double to = ends.get(c);
                boolean lastPiece = c == ends.size() - 1;
                String toId = segment.getEndNodeId();
                Coordinate[] coords = indexed.extractLine(from, to).getCoordinates();
                if (!lastPiece) {
                    toId = prefix + "node_" + (++numbers[1]);
                    nodes.add(new TechnicalNode(toId, segment.getVariantId(), factory.createPoint(coords[coords.length - 1])));
                }
                if (from == 0) {
                    coords[0] = segment.getGeometry().getCoordinateN(0);
                }
                if (lastPiece) {
                    coords[coords.length - 1] = segment.getGeometry().getCoordinateN(segment.getGeometry().getNumPoints() - 1);
                }
                LineString line = factory.createLineString(coords);
                double len = CostCalculator.round2(line.getLength());
                double d0 = mm(at(profile, start[i] + from));
                double d1 = mm(at(profile, start[i] + to));
                double cost = CostCalculator.round2(len * rules.diameter(segment.getDiameter()).getNewRubM() * kSpecial
                        * (k(d0) + k(d1)) / 2);
                String id = from == 0 ? segment.getId() : prefix + "seg_" + (++numbers[0]);
                out.add(new NewSegment(id, segment.getVariantId(), line, fromId, toId, segment.getFlowTph(),
                        segment.getDiameter(), len, segment.getLayingMethod(), d0, d1, cost));
                from = to;
                fromId = toId;
            }
        }
    }

    /** Глубина профиля в точке s цепочки. */
    private static double at(List<double[]> profile, double s) {
        for (int p = 0; p + 1 < profile.size(); p++) {
            double[] a = profile.get(p);
            double[] b = profile.get(p + 1);
            if (s <= b[0] + EPS) {
                return b[0] - a[0] <= EPS ? b[1] : a[1] + (b[1] - a[1]) * (s - a[0]) / (b[0] - a[0]);
            }
        }
        return profile.get(profile.size() - 1)[1];
    }

    private static double mm(double h) {
        return Math.round(h * 1000) / 1000.0;
    }

    /** Префикс ID варианта: начало ID участков до «seg_». */
    private static String prefix(Variant variant) {
        String id = variant.getSegments().get(0).getId();
        return id.substring(0, id.lastIndexOf("seg_"));
    }

    private static int maxNumber(java.util.stream.Stream<String> ids, String prefix) {
        return ids.filter(id -> id.startsWith(prefix)).mapToInt(id -> {
            try {
                return Integer.parseInt(id.substring(prefix.length()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }).max().orElse(0);
    }
}
