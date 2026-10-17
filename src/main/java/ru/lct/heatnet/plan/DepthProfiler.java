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
import ru.lct.heatnet.model.DepthCrossing;
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
 * узлы. В камерах, точках подключения и врезках сначала обычная глубина, 3,0 м (§5 о глубине в узлах молчит); если
 * пересечение ближе к узлу, чем нужно на спуск, глубина узла сдвигается (см. free), а затем узлы получают глубину, при
 * которой их цепочки дешевле всего (см. cheaperNodes): «над» объектом у самого узла вместо «под» с Kгл > 1.
 * Специальный участок с пересечением газа, кабеля или существующей теплосети (зона по 2 м с каждой стороны от точки
 * пересечения, табл. 2) лежит на постоянной глубине выше или ниже объекта с просветом табл. 2. Под дорогой и трамваем
 * постоянная глубина не требуется, только верх не мельче 1,0 и 1,2 м: там профиль может спускаться и подниматься, и
 * это условие проверяется по всему профилю. Спуски и подъёмы с уклоном не круче max_slope, ближе к 3,0 м при первой
 * возможности (глубже 3,0 м дороже, Kгл). Глубина каждой зоны пересечения выбирается перебором по цепочке (динамика
 * по зонам): самый дешёвый профиль, в котором выполнимы все уклоны и глубины под дорогами. Между отрезками не глубже 3,0 м — прямая без возврата к обычной глубине (§5 разрешает сохранять
 * изменённую глубину между близкими препятствиями). Излом профиля и переход через 3,0 м делят участок техническим узлом.
 */
final class DepthProfiler {
    /**
     * Уклон с запасом на округление: глубины пишутся до микрона, и уклон по записанным глубинам не круче 0,10 на любом
     * куске от 1 см (0,0998 + 2·0,5 мкм / 1 см < 0,1). Узел ближе SNAP_M к концу участка переносится в конец.
     */
    private static final double SLOPE_SLACK = Double.parseDouble(System.getProperty("heatnet.depth.slack", "0.998"));
    /** Причина технического узла, который ставит профиль (излом или переход через 3,0 м), для проверки по плану. */
    static final String PROFILE_NODE = "depth_profile";
    /** Кандидатов глубины узла в move(): пара узлов — их квадрат, на каждое сочетание решаются все цепочки узлов. */
    private static final int MOVE_CANDIDATES = 24;
    private static final double SNAP_M = 0.1;
    /** Знаков градуса в координатах 2D-выхода (GeoJsonStreamWriter.COORDINATE_SCALE). */
    private static final int WGS_SCALE = 9;
    /** Кратчайший кусок у точного края зоны: на 1 см округление координат и глубин меняет уклон меньше чем на 0,02 %. */
    private static final double MIN_PIECE_M = 0.01;
    /** Зазор между отрезками постоянной глубины короче этого связывается прямой, без излома. */
    private static final double MIN_BEND_M = 1.0;
    /** Точка касания трассы с сетью ближе этого к концу участка — врезка, а не пересечение. */
    private static final double TOUCH_M = 0.05;
    private static final double EPS = 1e-9;
    /** Рублей за метр участка на метр отклонения от обычной глубины: только выбор из равных по стоимости. */
    private static final double TIE_RUB_M = 1;
    /** Между двумя отрезками не глубже обычной — прямая без возврата к 3,0 м (меньше изломов профиля). */
    private static final boolean STRAIGHT = Boolean.parseBoolean(System.getProperty("heatnet.depth.straight", "true"));

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
        /** Края зоны по правилу (±2 м от пересечения); from/to могут быть дотянуты до границы участка ближе SNAP_M. */
        final double exactFrom;
        final double exactTo;

        Pin(double from, double to, Range range) {
            this(from, to, range, from, to);
        }

        Pin(double from, double to, Range range, double exactFrom, double exactTo) {
            this.from = from;
            this.to = to;
            this.range = range;
            this.exactFrom = exactFrom;
            this.exactTo = exactTo;
        }

        /**
         * Зона по точным краям: узлы в сантиметрах от границы участка, но без лишней постоянной глубины. Край ближе
         * MIN_PIECE_M к границе остаётся на ней: кусок спуска в миллиметры уклоном по геометрии не проверить.
         */
        Pin exact() {
            return new Pin(from + (exactFrom - from >= MIN_PIECE_M ? exactFrom - from : 0),
                    to - (to - exactTo >= MIN_PIECE_M ? to - exactTo : 0), range);
        }
    }

    Variant profile(Variant variant) {
        if (variant.getSegments().isEmpty()) {
            return variant;
        }
        variant = written(variant);
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
            Chain chain = new Chain(segments, technical);
            chains.add(chain);
            byNode.computeIfAbsent(chain.from(), key -> new ArrayList<>()).add(chain);
            byNode.computeIfAbsent(chain.to(), key -> new ArrayList<>()).add(chain);
        }
        for (Chain chain : chains) {
            for (Object[] spill : chain.spills) {
                @SuppressWarnings("unchecked")
                List<Cross> group = (List<Cross>) spill[2];
                for (Chain other : byNode.get((String) spill[0])) {
                    if (other != chain) {
                        // ponytail: остаток длиннее соседней цепочки дальше её узла не продолжается (зона 2 м, цепочки
                        // короче 2 м за камерой у пересечения редки; G7 в check18 это ловит)
                        other.spill((String) spill[0], (Double) spill[1], group);
                    }
                }
            }
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
        chains.forEach(chain -> chain.exactZones(nodeDepth));
        List<NewSegment> segments = new ArrayList<>();
        List<TechnicalNode> nodes = new ArrayList<>(variant.getNodes());
        for (Chain chain : chains) {
            boolean manual = chain.chosen == null;
            if (manual) {
                // профиль с уклоном не помещается: глубина объектов выдерживается, уклон круче (ручная проработка)
                conflicts.computeIfAbsent(variant.getId(), key -> new ArrayList<>()).add(chain.segments.get(0).getId());
                chain.chosen = closest(chain.pins(nodeDepth));
            }
            List<Pin> pins = chain.pins(nodeDepth);
            List<double[]> profile = new ArrayList<>();
            for (int k = 0; k < pins.size(); k++) {
                Pin pin = pins.get(k);
                if (k > 0) {
                    profile.addAll(gap(pins.get(k - 1).to, chain.chosen[k - 1], pin.from, chain.chosen[k], true,
                            chain.floors));
                }
                // край зоны — обязательный излом: его нельзя переносить к концу участка, зона станет непостоянной
                profile.add(new double[] {pin.from, chain.chosen[k], 1});
                profile.add(new double[] {pin.to, chain.chosen[k], 1});
            }
            split(chain, profile, manual, prefix, numbers, segments, nodes);
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
     * ближайших спецучастков и глубины, с которых до них ровно хватает уклона. Камера врезки тоже (как и в free;
     * -Dheatnet.depth.tieFree=false — только на 3,0 м). Покоординатный спуск по узлам и парам концов цепочки, пока
     * стоимость падает.
     */
    private void cheaperNodes(List<Chain> chains, Map<String, List<Chain>> byNode, Map<String, Double> nodeDepth,
            Set<String> ends) {
        if (!Boolean.parseBoolean(System.getProperty("heatnet.depth.cheaperNodes", "true"))) {
            return;
        }
        boolean tieFree = Boolean.parseBoolean(System.getProperty("heatnet.depth.tieFree", "true"));
        boolean pairs = Boolean.parseBoolean(System.getProperty("heatnet.depth.pairs", "true"));
        for (int pass = 0; pass < 10; pass++) {
            boolean changed = false;
            Set<String> seen = new HashSet<>();
            for (Chain chain : chains) {
                List<List<String>> groups = new ArrayList<>();
                for (String node : List.of(chain.from(), chain.to())) {
                    if (seen.add(node) && (tieFree || ends.contains(node))) {
                        groups.add(List.of(node));
                    }
                }
                // короткая цепочка между двумя узлами у спецпрохода: поодиночке узел не поднять, уклон до соседа
                // не помещается, — оба конца сразу, каждому своя глубина
                if (pairs && deep(chain) && (tieFree || ends.contains(chain.from()) && ends.contains(chain.to()))) {
                    groups.add(List.of(chain.from(), chain.to()));
                }
                for (List<String> group : groups) {
                    changed |= move(group, byNode, nodeDepth);
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /**
     * Глубины узлов group (каждому своя, перебор сочетаний кандидатов), при которых их цепочки в сумме дешевле
     * текущего; true — глубины сменились.
     */
    private boolean move(List<String> group, Map<String, List<Chain>> byNode, Map<String, Double> nodeDepth) {
        Set<Chain> set = new java.util.LinkedHashSet<>();
        group.forEach(node -> set.addAll(byNode.get(node)));
        List<Chain> incident = new ArrayList<>(set);
        if (incident.stream().noneMatch(this::deep)) {
            return false;
        }
        double slope = depth.getMaxSlope() * SLOPE_SLACK;
        double current = 0;
        for (Chain other : incident) {
            current += other.cost;
        }
        // кандидаты всех узлов группы общие: в переборе есть и «оба на одной глубине»
        TreeSet<Double> own = new TreeSet<>();
        own.add(depth.getNormalM());
        for (String node : group) {
            for (Chain other : byNode.get(node)) {
                Pin pin = other.nearest(node);
                if (pin == null) {
                    continue;
                }
                double d = other.distance(node, pin);
                for (double h : values(pin, other.inner)) {
                    own.add(h);
                    own.add(h + slope * d);
                    own.add(Math.max(depth.getMinM(), h - slope * d));
                }
            }
        }
        // ponytail: не больше MOVE_CANDIDATES ближайших к текущей глубине — перебор пары квадратичен по кандидатам
        double current0 = nodeDepth.getOrDefault(group.get(0), depth.getNormalM());
        double[] picked = own.stream().sorted(java.util.Comparator.comparingDouble(h -> Math.abs(h - current0)))
                .limit(MOVE_CANDIDATES).mapToDouble(Double::doubleValue).toArray();
        List<double[]> candidates = new ArrayList<>();
        group.forEach(node -> candidates.add(picked));
        Map<String, Double> before = new HashMap<>();
        group.forEach(node -> before.put(node, nodeDepth.get(node)));
        double best = current - 1;
        double[] bestH = null;
        int[] at = new int[group.size()];
        while (true) {
            double[] h = new double[group.size()];
            for (int i = 0; i < group.size(); i++) {
                h[i] = candidates.get(i)[at[i]];
                nodeDepth.put(group.get(i), h[i]);
            }
            double sum = 0;
            for (Chain other : incident) {
                sum += other.solve(nodeDepth) == null ? Double.POSITIVE_INFINITY : other.cost;
            }
            if (sum < best) {
                best = sum;
                bestH = h;
            }
            int i = 0;
            while (i < at.length && ++at[i] == candidates.get(i).length) {
                at[i++] = 0;
            }
            if (i == at.length) {
                break;
            }
        }
        for (int i = 0; i < group.size(); i++) {
            String node = group.get(i);
            if (bestH != null) {
                nodeDepth.put(node, bestH[i]);
            } else if (before.get(node) != null) {
                nodeDepth.put(node, before.get(node));
            } else {
                nodeDepth.remove(node);
            }
        }
        incident.forEach(other -> other.solve(nodeDepth));
        return bestH != null;
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
        /** Зоны пересечений: специальные участки на постоянной глубине. */
        final List<Pin> inner = new ArrayList<>();
        /** Под дорогой и трамваем: [от, до, наименьшая глубина верха] по всему профилю. */
        final List<double[]> floors = new ArrayList<>();
        /** Точки пересечений по цепочке, для зон и для выхода. */
        final List<Cross> crosses = new ArrayList<>();
        /** Зоны, которые выходят за конец цепочки: {узел, остаток длины, пересечения зоны}. */
        final List<Object[]> spills = new ArrayList<>();

        /**
         * Остаток зоны length от узла node в этой цепочке: отрезок на постоянной глубине у её конца, с условиями
         * пересечений group; перекрытый с зоной цепочки — одна зона.
         */
        @SuppressWarnings("unchecked")
        void spill(String node, double length, List<Cross> group) {
            double total = start[segments.size()];
            double from = node.equals(from()) ? 0 : Math.max(0, total - length);
            double to = node.equals(from()) ? Math.min(total, length) : total;
            for (double b : start) {
                if (Math.abs(from - b) < SNAP_M) {
                    from = Math.min(from, b);
                }
                if (Math.abs(to - b) < SNAP_M) {
                    to = Math.max(to, b);
                }
            }
            // остаток короче 1 см — до 1 см: кусок в миллиметры уклоном не проверить
            if (to - from < MIN_PIECE_M) {
                if (node.equals(from())) {
                    to = Math.min(total, from + MIN_PIECE_M);
                } else {
                    from = Math.max(0, to - MIN_PIECE_M);
                }
            }
            Range range = range(from, to, group);
            for (int k = inner.size() - 1; k >= 0; k--) {
                Pin pin = inner.get(k);
                if (pin.from <= to + EPS && pin.to >= from - EPS) {
                    from = Math.min(from, pin.from);
                    to = Math.max(to, pin.to);
                    range = range.and(pin.range);
                    inner.remove(k);
                }
            }
            int at = 0;
            while (at < inner.size() && inner.get(at).from < from) {
                at++;
            }
            inner.add(at, new Pin(from, to, range));
            merge();
        }
        double[] chosen;
        /** Стоимость профиля chosen по цепочке (с Kгл); бесконечность — профиля нет. */
        double cost;

        Chain(List<NewSegment> segments, Set<String> technical) {
            this.segments = segments;
            int n = segments.size();
            start = new double[n + 1];
            for (int i = 0; i < n; i++) {
                start[i + 1] = start[i] + segments.get(i).getGeometry().getLength();
            }
            List<Cross> points = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                NewSegment segment = segments.get(i);
                double floor = floor(segment);
                if (floor > depth.getMinM()) {
                    floors.add(new double[] {start[i], start[i + 1], floor});
                }
                points.addAll(crossings(segment, start[i], technical));
            }
            points.sort(java.util.Comparator.comparingDouble(c -> c.at));
            // пересечение в техническом узле видят оба его участка: одна точка на объект того же типа
            for (int j = points.size() - 1; j > 0; j--) {
                for (int i = j - 1; i >= 0 && points.get(j).at - points.get(i).at < 2 * TOUCH_M; i--) {
                    if (points.get(j).type.equals(points.get(i).type)) {
                        points.remove(j);
                        break;
                    }
                }
            }
            crosses.addAll(points);
            // зона постоянной глубины — по margin (2 м) по трассе с каждой стороны от точки пересечения (табл. 2,
            // Доп. разъяснение №14); перекрытые зоны — одна зона на одной глубине
            int k = 0;
            while (k < points.size()) {
                List<Cross> group = new ArrayList<>(List.of(points.get(k)));
                double to = points.get(k).at + points.get(k).margin;
                while (++k < points.size() && points.get(k).at - points.get(k).margin <= to + EPS) {
                    group.add(points.get(k));
                    to = Math.max(to, points.get(k).at + points.get(k).margin);
                }
                double head = group.stream().mapToDouble(c -> c.at - c.margin).min().getAsDouble();
                double from = Math.max(0, head);
                // зона за концом цепочки продолжается за узлом во все его ветви (табл. 2: 2 м по трассе), см. spill
                if (head < -EPS) {
                    spills.add(new Object[] {from(), -head, group});
                }
                if (to > start[n] + EPS) {
                    spills.add(new Object[] {to(), to - start[n], group});
                }
                to = Math.min(start[n], to);
                double exactFrom = from;
                double exactTo = to;
                // край зоны у самой границы участка — до границы: узел не ставится в сантиметрах от неё (у зоны
                // глубже 3,0 м края потом возвращаются к точным, если так дешевле, см. exactZones)
                for (double b : start) {
                    if (Math.abs(from - b) < SNAP_M) {
                        from = Math.min(from, b);
                    }
                    if (Math.abs(to - b) < SNAP_M) {
                        to = Math.max(to, b);
                    }
                }
                inner.add(new Pin(from, to, range(from, to, group), exactFrom, exactTo));
            }
            merge();
        }

        /** Зоны, налезшие друг на друга после дотягивания краёв, — одна зона с условиями обеих. */
        void merge() {
            for (int k = inner.size() - 1; k > 0; k--) {
                Pin a = inner.get(k - 1);
                Pin b = inner.get(k);
                if (b.from < a.to - EPS) {
                    inner.set(k - 1, new Pin(a.from, Math.max(a.to, b.to), a.range.and(b.range),
                            a.exactFrom, Math.max(a.exactTo, b.exactTo)));
                    inner.remove(k);
                }
            }
        }

        /** Допустимые глубины зоны [from, to]: условия табл. 2 всех её пересечений и наибольший пол участков зоны. */
        private Range range(double from, double to, List<Cross> group) {
            double floor = depth.getMinM();
            for (double[] f : floors) {
                if (f[0] < to - EPS && f[1] > from + EPS) {
                    floor = Math.max(floor, f[2]);
                }
            }
            Range range = Range.of(floor, Double.MAX_VALUE);
            for (Cross c : group) {
                List<double[]> parts = new ArrayList<>();
                parts.add(new double[] {depth.getMinM(), c.top - c.clear - c.newHeight});
                parts.add(new double[] {c.top + c.height + c.clear, Double.MAX_VALUE});
                range = range.and(new Range(parts));
            }
            return range;
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
                chosen = DepthProfiler.this.solve(segments, start, pins(nodeDepth), floors, total);
            }
            cost = total[0];
            return chosen;
        }

        /**
         * Зоны глубже 3,0 м — по точным краям ±2 м, если так дешевле: дотянутый до границы участка край держит
         * лишние сантиметры на глубине с Kгл > 1. У мелких зон Kгл = 1, и узел в сантиметрах от границы не нужен.
         */
        void exactZones(Map<String, Double> nodeDepth) {
            if (chosen == null) {
                return;
            }
            List<Pin> before = new ArrayList<>(inner);
            double[] chosenBefore = chosen;
            double costBefore = cost;
            boolean changed = false;
            for (int k = 0; k < inner.size(); k++) {
                Pin pin = inner.get(k);
                if (chosen[k + 1] > depth.getNormalM() + EPS && (pin.from != pin.exactFrom || pin.to != pin.exactTo)) {
                    inner.set(k, pin.exact());
                    changed = true;
                }
            }
            if (changed && (solve(nodeDepth) == null || cost > costBefore - EPS)) {
                inner.clear();
                inner.addAll(before);
                chosen = chosenBefore;
                cost = costBefore;
            }
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
     * Пересечение трассы с объектом с вертикальными требованиями (газ, кабель, существующая сеть без врезки, табл. 2):
     * место по цепочке, верх и высота габарита объекта, просвет, зона постоянной глубины, высота габарита новой сети.
     */
    private static final class Cross {
        final String type;
        final double at;
        final double top;
        final double height;
        final double clear;
        final double margin;
        final double newHeight;

        Cross(String type, double at, double top, double height, double clear, double margin, double newHeight) {
            this.type = type;
            this.at = at;
            this.top = top;
            this.height = height;
            this.clear = clear;
            this.margin = margin;
            this.newHeight = newHeight;
        }
    }

    /** Точки, где специальный участок пересекает газ, кабель или существующую сеть (не врезка в своём конце). */
    private List<Cross> crossings(NewSegment segment, double offset, Set<String> technical) {
        // и обычный участок: у камеры врезки 2D иногда выводит трубу вдоль существующей и пересекает её без
        // спецпрохода (Москва, 3 участка) — вертикальный просвет нужен и там
        List<Cross> result = new ArrayList<>();
        LineString line = segment.getGeometry();
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double newHeight = rules.diameter(segment.getDiameter()).getHeightM();
        for (SpecialObjects.Special special : specials.around(line, segment.getDiameter())) {
            DepthRules.Crossing crossing = depth.getCrossings().get(special.rule.getType());
            if (crossing == null || special.polygon) {
                continue;
            }
            // врезка бывает только в существующую сеть: касание газа или кабеля у камеры — пересечение
            boolean network = special.network;
            for (Coordinate c : crossPoints(line, special, !network || technical.contains(segment.getStartNodeId()),
                    !network || technical.contains(segment.getEndNodeId()))) {
                result.add(new Cross(special.rule.getType(), offset + indexed.indexOf(c), crossing.getTopM(),
                        crossing.getHeightM() != null ? crossing.getHeightM() : special.height, crossing.getClearM(),
                        special.rule.getMarginM(), newHeight));
            }
        }
        return result;
    }

    /** Наименьшая глубина верха на участке: под дорогой 1,0 м, под трамваем 1,2 м (табл. 2), иначе 0,7 м (§5). */
    private double floor(NewSegment segment) {
        double floor = depth.getMinM();
        if (!NetworkAssembler.SPECIAL.equals(segment.getLayingMethod())) {
            return floor;
        }
        LineString line = segment.getGeometry();
        for (SpecialObjects.Special special : specials.around(line, segment.getDiameter())) {
            String type = special.rule.getType();
            if (depth.getMinTopM().containsKey(type) && special.closer(line, special.rule.getMarginM() + 0.01)) {
                floor = Math.max(floor, depth.getMinTopM().get(type));
            }
        }
        return floor;
    }

    /**
     * Точки, где участок пересекает линию объекта. Касание в конце участка у камеры или точки подключения — врезка, а
     * не пересечение; у технического узла (граница зоны 2D) — пересечение: техузел трассу не прерывает.
     */
    private List<Coordinate> crossPoints(LineString line, SpecialObjects.Special special, boolean startTechnical,
            boolean endTechnical) {
        List<Coordinate> result = new ArrayList<>();
        if (!special.crossedBy(line)) {
            return result;
        }
        Geometry meet = line.intersection(special.geometry);
        Coordinate a = line.getCoordinateN(0);
        Coordinate b = line.getCoordinateN(line.getNumPoints() - 1);
        for (Coordinate c : meet.getCoordinates()) {
            if ((startTechnical || c.distance(a) > TOUCH_M) && (endTechnical || c.distance(b) > TOUCH_M)) {
                result.add(c);
            }
        }
        return result;
    }

    /** Самая дешёвая выполнимая глубина каждого отрезка pins; null — профиля с уклоном нет. */
    private double[] solve(List<NewSegment> chain, double[] start, List<Pin> pins, List<double[]> floors,
            double[] total) {
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
                    List<double[]> gap = gap(pins.get(k - 1).to, prev[p], pins.get(k).from, vs[c], false, floors);
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
        double slope = depth.getMaxSlope() * SLOPE_SLACK;
        for (Pin other : pins) {
            List<Double> own = new ArrayList<>();
            for (double[] part : other.range.parts) {
                own.add(Math.max(part[0], Math.min(depth.getNormalM(), part[1])));
                if (part[1] < Double.MAX_VALUE) {
                    own.add(part[1]);
                }
                own.add(part[0]);
            }
            all.addAll(own);
            // глубина, с которой до соседа ровно хватает уклона: без неё зона между двумя глубокими соседями
            // берёт ближайшую границу диапазона, а не нужную промежуточную глубину
            double d = other.from >= pin.to ? other.from - pin.to : pin.from >= other.to ? pin.from - other.to : -1;
            if (d > EPS) {
                for (double v : own) {
                    if (v > depth.getNormalM() + EPS || other.from == other.to) {
                        all.add(v - slope * d);
                        all.add(v + slope * d);
                    }
                }
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
    private List<double[]> gap(double a, double ha, double b, double hb, boolean force, List<double[]> floors) {
        List<double[]> result = gap(a, ha, b, hb, STRAIGHT);
        if (result != null && fits(a, ha, result, b, hb, floors)) {
            return result;
        }
        if (STRAIGHT) {
            // прямая между мелкими отрезками проходит под дорогой мельче нормы: возврат к 3,0 м её обходит
            List<double[]> back = gap(a, ha, b, hb, false);
            if (back != null && fits(a, ha, back, b, hb, floors)) {
                return back;
            }
        }
        if (!force) {
            return null;
        }
        if (result == null) {
            result = new ArrayList<>();
            double normal = depth.getNormalM();
            if ((ha - normal) * (hb - normal) < 0) {
                // прямая ручной проработки проходит 3,0 м: в этой точке технический узел (§5)
                result.add(new double[] {a + (b - a) * (ha - normal) / (ha - hb), normal});
            }
        }
        return result;
    }

    /** Профиль от (a, ha) через points до (b, hb) не мельче глубины floors (дорога, трамвай) там, где они есть. */
    private static boolean fits(double a, double ha, List<double[]> points, double b, double hb, List<double[]> floors) {
        List<double[]> line = new ArrayList<>();
        line.add(new double[] {a, ha});
        line.addAll(points);
        line.add(new double[] {b, hb});
        for (double[] floor : floors) {
            double from = Math.max(a, floor[0]);
            double to = Math.min(b, floor[1]);
            if (to < from - EPS) {
                continue;
            }
            // профиль линеен между точками: наименьшая глубина — в концах отрезка floor или в изломах внутри
            List<Double> at = new ArrayList<>(List.of(from, to));
            line.forEach(point -> {
                if (point[0] > from && point[0] < to) {
                    at.add(point[0]);
                }
            });
            for (double s : at) {
                if (at(line, s) < floor[2] - EPS) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Изломы между отрезками без проверки глубины под дорогой; straight — прямая, если обе глубины не глубже 3,0 м. */
    private List<double[]> gap(double a, double ha, double b, double hb, boolean straight) {
        double slope = depth.getMaxSlope() * SLOPE_SLACK;
        double normal = depth.getNormalM();
        double g = b - a;
        double da = Math.abs(ha - normal) / slope;
        double db = Math.abs(hb - normal) / slope;
        List<double[]> result = new ArrayList<>();
        if (straight && ha <= normal + EPS && hb <= normal + EPS && Math.abs(ha - hb) <= slope * g + EPS) {
            return result; // §5: между близкими препятствиями изменённая глубина сохраняется, выше 3,0 м Kгл = 1
        }
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
        return null;
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
    private void split(Chain owner, List<double[]> profile, boolean manual, String prefix, int[] numbers,
            List<NewSegment> out, List<TechnicalNode> nodes) {
        List<NewSegment> chain = owner.segments;
        double[] start = owner.start;
        double normal = depth.getNormalM();
        for (int i = 0; i < chain.size(); i++) {
            NewSegment segment = chain.get(i);
            double length = start[i + 1] - start[i];
            TreeSet<Double> cuts = new TreeSet<>();
            for (double[] point : profile) {
                double at = point[0] - start[i];
                // излом у самого конца участка переносится в конец, кроме края зоны: там постоянная глубина
                double snap = point.length > 2 ? EPS : SNAP_M;
                if (at > snap && at < length - snap) {
                    cuts.add(at);
                }
            }
            // кусок, который проходит 3,0 м, делится в этой точке (§5); точка у конца участка не переносится
            for (boolean added = true; added; ) {
                added = false;
                double prev = 0;
                List<Double> ends0 = new ArrayList<>(cuts);
                ends0.add(length);
                for (double to : ends0) {
                    double h0 = at(profile, start[i] + prev);
                    double h1 = at(profile, start[i] + to);
                    if ((h0 - normal) * (h1 - normal) < -EPS * EPS) {
                        double cut = Double.NaN;
                        for (double[] point : profile) {
                            double at = point[0] - start[i];
                            if (Math.abs(point[1] - normal) < EPS && at > prev + EPS && at < to - EPS) {
                                cut = at;
                                break;
                            }
                        }
                        cuts.add(Double.isNaN(cut) ? prev + (to - prev) * (h0 - normal) / (h0 - h1) : cut);
                        added = true;
                        break;
                    }
                    prev = to;
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
                    nodes.add(new TechnicalNode(toId, segment.getVariantId(), factory.createPoint(coords[coords.length - 1]),
                            PROFILE_NODE));
                }
                if (from == 0) {
                    coords[0] = segment.getGeometry().getCoordinateN(0);
                }
                if (lastPiece) {
                    coords[coords.length - 1] = segment.getGeometry().getCoordinateN(segment.getGeometry().getNumPoints() - 1);
                }
                LineString line = factory.createLineString(coords);
                // длина до микрона: на кусках короче метра округление до см делает уклон по атрибуту length круче 0,10
                double len = mm(line.getLength());
                double d0 = mm(at(profile, start[i] + from));
                double d1 = mm(at(profile, start[i] + to));
                double cost = CostCalculator.round2(len * rules.diameter(segment.getDiameter()).getNewRubM() * kSpecial
                        * (k(d0) + k(d1)) / 2);
                String id = from == 0 ? segment.getId() : prefix + "seg_" + (++numbers[0]);
                out.add(new NewSegment(id, segment.getVariantId(), line, fromId, toId, segment.getFlowTph(),
                        segment.getDiameter(), len, segment.getLayingMethod(), d0, d1, cost,
                        crossings(owner, profile, start[i] + from, start[i] + to, start[chain.size()]), manual));
                from = to;
                fromId = toId;
            }
        }
    }

    /** Пересечения участка на глубине h для выхода: над или под объектом и вертикальный просвет между гранями. */
    private List<DepthCrossing> crossings(Chain chain, List<double[]> profile, double from, double to, double total) {
        List<DepthCrossing> result = new ArrayList<>();
        for (Cross c : chain.crosses) {
            // точка на разрезе — в следующем куске, в конце цепочки — в последнем
            if (c.at < from - EPS || c.at >= to - EPS && to < total - EPS) {
                continue;
            }
            double h = mm(at(profile, c.at));
            double over = c.top - (h + c.newHeight);
            double under = h - (c.top + c.height);
            result.add(new DepthCrossing(c.type, over >= under ? "over" : "under", mm(Math.max(over, under))));
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Вариант на сетке выхода: вершины трассы так, как их пишет 2D (9 знаков градуса), в метрах. Профиль, длины и
     * разрезы считаются на той же геометрии, которую увидит проверка: вершины плана совпадают с 2D-выходом, а куски
     * в сантиметры не расходятся с length на доли миллиметра (иначе уклон по геометрии выходил круче 0,10).
     */
    private static Variant written(Variant variant) {
        List<NewSegment> segments = new ArrayList<>();
        for (NewSegment s : variant.getSegments()) {
            segments.add(new NewSegment(s.getId(), s.getVariantId(), (LineString) grid(s.getGeometry()), s.getStartNodeId(),
                    s.getEndNodeId(), s.getFlowTph(), s.getDiameter(), s.getLength(), s.getLayingMethod(), s.getDepthStart(),
                    s.getDepthEnd(), s.getCost(), s.getDepthCrossings(), s.isManualReview()));
        }
        List<NewChamber> chambers = new ArrayList<>();
        for (NewChamber c : variant.getChambers()) {
            chambers.add(new NewChamber(c.getId(), c.getVariantId(), (org.locationtech.jts.geom.Point) grid(c.getGeometry()),
                    c.getDiameter(), c.getCost()));
        }
        List<TechnicalNode> nodes = new ArrayList<>();
        for (TechnicalNode n : variant.getNodes()) {
            nodes.add(new TechnicalNode(n.getId(), n.getVariantId(), (org.locationtech.jts.geom.Point) grid(n.getGeometry()),
                    n.getReason()));
        }
        return new Variant(variant.getId(), segments, chambers, nodes, variant.getSummary());
    }

    private static Geometry grid(Geometry utm) {
        Geometry wgs = ru.lct.heatnet.io.Projector.toWgs(utm).copy();
        wgs.apply((org.locationtech.jts.geom.CoordinateFilter) c -> {
            c.x = java.math.BigDecimal.valueOf(c.x).setScale(WGS_SCALE, java.math.RoundingMode.HALF_UP).doubleValue();
            c.y = java.math.BigDecimal.valueOf(c.y).setScale(WGS_SCALE, java.math.RoundingMode.HALF_UP).doubleValue();
        });
        wgs.geometryChanged();
        return ru.lct.heatnet.io.Projector.toUtm(wgs);
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

    /** Глубина до микрона: при мм округление на коротком куске делает уклон круче 0,10 (СПб: 0,1005 на 0,16 м). */
    private static double mm(double h) {
        return Math.round(h * 1_000_000) / 1_000_000.0;
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
