package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

/**
 * Варианты подключения (D-11). Стратегии: каждый ОКС своей лучшей врезкой; группы близких ОКС общими деревьями;
 * те же разбиения с другой врезкой; группы из трёх и больше ОКС, разбитые k-means на две. Из собранных вариантов
 * выбираются до трёх лучших по score, попарно различающихся набором врезок или разбиением ОКС по деревьям.
 */
public final class VariantEnumerator {
    /** ОКС ближе этого по точкам подключения считаются близкими и пробуются общим деревом. */
    private static final double GROUP_DISTANCE_M = 300;
    /** Запас области графа вокруг ОКС и кандидатов врезки (D-6). */
    private static final double AREA_MARGIN_M = 150;
    private static final double WIDE_AREA_MARGIN_M = 600;
    /** Врезка дальше этого от всех врезок другого варианта делает варианты разными (правило variants). */
    private static final double OTHER_TIE_M = 20;
    /** R-11: варианты одинаковы, если больше этой доли длины меньшего лежит в полосе SAME_ROUTE_M от другого. */
    private static final double SAME_ROUTE_SHARE = 0.8;
    private static final double SAME_ROUTE_M = 1.0;
    /** Дерево ОКС идёт в обход, если оно длиннее прямой до ближайшего кандидата врезки больше чем во столько раз. */
    private static final double DETOUR_RATIO = 1.1;
    private static final double TREES_APART_M = 0.5;
    private static final double SHARED_ROOT_CLIP_M = 0.15;
    private static final double SHARED_ROOT_APART_M = 0.01;
    private static final int MAX_VARIANTS = 3;
    private static final int KMEANS_ITERATIONS = 20;

    private final InputData input;
    private final Rules rules;
    private final TieInFinder finder;
    private final SpecialObjects specials;
    private final TreeBuilder builder;
    private NetworkAssembler assembler;
    private final GeometryFactory factory = new GeometryFactory();
    private final Map<String, FutureOks> oksById = new LinkedHashMap<>();
    private final Map<String, ConnectionPoint> connectionByOks = new LinkedHashMap<>();
    private final Map<String, Region> regionByConnection = new HashMap<>();

    private static final class Option {
        final Tree tree;
        final double score;

        Option(Tree tree, double score) {
            this.tree = tree;
            this.score = score;
        }
    }

    /** Группа близких ОКС: общий граф на её область и кэш деревьев по подмножествам. */
    private final class Region {
        final int dn;
        final Envelope area;
        final Envelope wideArea;
        final Map<String, List<Option>> options = new HashMap<>();
        final Map<String, Router> routers = new HashMap<>();

        Region(List<ConnectionPoint> connections) {
            Diameter byFlow = rules.diameterFor(flow(connections));
            Diameter above = rules.nextDiameter(byFlow.getDn());
            // диаметр ствола с запасом на ступень выше: так отступы верны и для кусков, поднятых по предельной длине
            this.dn = above != null ? above.getDn() : byFlow.getDn();
            Envelope envelope = new Envelope();
            List<Point> points = points(connections);
            for (Point point : points) {
                envelope.expandToInclude(point.getCoordinate());
            }
            for (TieCandidate candidate : finder.find(points, dn)) {
                envelope.expandToInclude(candidate.getPoint().getCoordinate());
            }
            this.wideArea = new Envelope(envelope);
            envelope.expandBy(AREA_MARGIN_M);
            wideArea.expandBy(WIDE_AREA_MARGIN_M);
            this.area = envelope;
        }

        Router router(int routerDn, Envelope routerArea) {
            return routers.computeIfAbsent(routerDn + "@" + routerArea,
                    key -> new Router(input, rules, routerArea, routerDn));
        }
    }

    /** Собранный вариант до присвоения ранга. */
    private static final class Draft {
        final List<Tree> trees;
        final List<FutureOks> unconnected;
        final Variant variant;

        Draft(List<Tree> trees, List<FutureOks> unconnected, Variant variant) {
            this.trees = trees;
            this.unconnected = unconnected;
            this.variant = variant;
        }

        double score() {
            return variant.getSummary().getScore();
        }
    }

    public VariantEnumerator(InputData input, Rules rules) {
        this.input = input;
        this.rules = rules;
        this.finder = new TieInFinder(input, rules);
        Map<String, LineString> networkById = new HashMap<>();
        for (NetworkSegment segment : input.getSegments()) {
            networkById.put(segment.getId(), segment.getGeometry());
        }
        this.specials = new SpecialObjects(input, rules);
        this.builder = new TreeBuilder(finder.nodeLimit(), networkById, specials);
        for (FutureOks oks : input.getFutureOks()) {
            oksById.put(oks.getId(), oks);
        }
        for (ConnectionPoint connection : input.getConnectionPoints()) {
            if (oksById.containsKey(connection.getOksId())) {
                connectionByOks.putIfAbsent(connection.getOksId(), connection);
            }
        }
    }

    public Result run() {
        List<List<ConnectionPoint>> groups = groups(new ArrayList<>(connectionByOks.values()));
        Envelope extent = new Envelope();
        for (List<ConnectionPoint> group : groups) {
            Region region = new Region(group);
            extent.expandToInclude(region.wideArea);
            for (ConnectionPoint connection : group) {
                regionByConnection.put(connection.getId(), region);
            }
        }
        assembler = new NetworkAssembler(input, rules, specials, extent);
        List<List<ConnectionPoint>> singles = new ArrayList<>();
        for (ConnectionPoint connection : connectionByOks.values()) {
            singles.add(List.of(connection));
        }
        List<List<ConnectionPoint>> split = new ArrayList<>();
        boolean anyGroup = false;
        boolean anySplit = false;
        for (List<ConnectionPoint> group : groups) {
            anyGroup |= group.size() > 1;
            if (group.size() > 2) {
                anySplit = true;
                split.addAll(kMeans(group));
            } else {
                split.add(group);
            }
        }

        List<List<List<ConnectionPoint>>> partitions = new ArrayList<>(List.of(singles));
        if (anyGroup) {
            partitions.add(groups);
        }
        List<Draft> drafts = new ArrayList<>();
        for (List<List<ConnectionPoint>> partition : partitions) {
            drafts.add(draft(partition, subset -> false));
            drafts.add(draft(partition, subset -> true));
        }
        if (anySplit) {
            drafts.add(draft(split, subset -> false));
        }
        List<Draft> picked = pick(drafts);
        if (picked.size() < 2) {
            // другие врезки всех ОКС разом могут дать тот же набор врезок, например ОКС поменялись камерами местами:
            // пробуется другая врезка у одного подмножества при лучших у остальных
            for (List<List<ConnectionPoint>> partition : partitions) {
                if (partition.size() > 1) {
                    for (List<ConnectionPoint> one : partition) {
                        drafts.add(draft(partition, subset -> subset == one));
                    }
                }
            }
            picked = pick(drafts);
        }

        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Draft draft = picked.get(i);
            variants.add(assembler.assemble(String.valueOf(i + 1), i + 1, draft.trees, draft.unconnected));
        }
        return new Result(variants);
    }

    /** До трёх лучших по score черновиков, попарно различных по правилу variants и по трассе (R-11). */
    private List<Draft> pick(List<Draft> drafts) {
        drafts.removeIf(Objects::isNull);
        drafts.sort(Comparator.comparingDouble(Draft::score));
        List<Draft> picked = new ArrayList<>();
        for (Draft draft : drafts) {
            if (picked.size() < MAX_VARIANTS && picked.stream().allMatch(p -> differ(p, draft) && !sameRoute(p, draft))) {
                picked.add(draft);
            }
        }
        if (picked.size() < 2) {
            // R-11 оставил меньше двух вариантов: добираем по правилу различия врезок и разбиения
            for (Draft draft : drafts) {
                if (picked.size() < MAX_VARIANTS && !picked.contains(draft) && picked.stream().allMatch(p -> differ(p, draft))) {
                    picked.add(draft);
                }
            }
            picked.sort(Comparator.comparingDouble(Draft::score));
        }
        return picked;
    }

    /**
     * Вариант разбиения: подмножества по убыванию размера берут лучшее совместимое дерево, а те, что отобраны
     * {@code alternative}, — первое с другой врезкой. ОКС, которые не вошли в общее дерево, подключаются отдельными
     * врезками; без маршрута — в штраф.
     */
    private Draft draft(List<List<ConnectionPoint>> partition, Predicate<List<ConnectionPoint>> alternative) {
        List<List<ConnectionPoint>> queue = new ArrayList<>(partition);
        queue.sort(Comparator.comparingInt((List<ConnectionPoint> subset) -> -subset.size())
                .thenComparing(subset -> subset.get(0).getId()));
        List<Tree> accepted = new ArrayList<>();
        Set<String> unconnectedIds = new HashSet<>();
        for (int next = 0; next < queue.size(); next++) {
            List<ConnectionPoint> subset = queue.get(next);
            List<Option> options = options(subset);
            int start = alternative.test(subset) ? alternativeIndex(options) : 0;
            Tree chosen = null;
            // с другой врезкой ищем от первого отличного дерева, а если все дальше несовместимы — с начала списка
            for (int k = 0; k < options.size() && chosen == null; k++) {
                Tree tree = options.get((start + k) % options.size()).tree;
                if (compatible(tree, accepted)) {
                    chosen = tree;
                }
            }
            if (chosen == null) {
                chosen = merged(subset, options, accepted);
            }
            if (chosen == null) {
                subset.forEach(connection -> unconnectedIds.add(connection.getOksId()));
                continue;
            }
            accepted.add(chosen);
            for (ConnectionPoint left : chosen.unconnected) {
                if (subset.size() > 1) {
                    queue.add(List.of(left));
                } else {
                    unconnectedIds.add(left.getOksId());
                }
            }
        }
        List<FutureOks> unconnected = unconnected(unconnectedIds);
        try {
            return new Draft(accepted, unconnected, assembler.assemble("0", 0, accepted, unconnected));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // несколько деревьев в одной камере по отдельности собирались, а вместе нет: разбиение пропускается
            return null;
        }
    }

    /**
     * Все деревья подмножества пересекают уже принятые, поэтому деревья объединяются. Принятое дерево той же группы
     * ОКС, которое мешает лучшему дереву подмножества, убирается из accepted, а его ОКС вместе с подмножеством
     * строятся одним деревом. Возвращает это дерево (вызывающий добавит его в accepted) или null, если объединить
     * не удалось; тогда accepted остаётся прежним.
     */
    private Tree merged(List<ConnectionPoint> subset, List<Option> options, List<Tree> accepted) {
        if (options.isEmpty()) {
            return null;
        }
        Region region = regionByConnection.get(subset.get(0).getId());
        Geometry best = geometry(options.get(0).tree);
        for (Tree blocker : new ArrayList<>(accepted)) {
            List<ConnectionPoint> union = new ArrayList<>(blocker.connected());
            if (union.isEmpty() || regionByConnection.get(union.get(0).getId()) != region
                    || geometry(blocker).distance(best) > TREES_APART_M && !blocker.root.key.equals(options.get(0).tree.root.key)) {
                continue;
            }
            union.addAll(subset);
            accepted.remove(blocker);
            for (Option option : options(union)) {
                if (option.tree.unconnected.isEmpty() && compatible(option.tree, accepted)) {
                    return option.tree;
                }
            }
            accepted.add(blocker);
        }
        return null;
    }

    /** Деревья подмножества ОКС по всем кандидатам врезки, от лучшего score отдельно собранного дерева. */
    private List<Option> options(List<ConnectionPoint> subset) {
        Region region = regionByConnection.get(subset.get(0).getId());
        String key = subset.stream().map(ConnectionPoint::getId).sorted().collect(Collectors.joining("|"));
        List<Option> cached = region.options.get(key);
        if (cached != null) {
            return cached;
        }
        List<Point> points = points(subset);
        List<Option> options = options(region, region.dn, region.area, subset, false, finder.find(points, region.dn));
        // ОКС, не вошедшие в общее дерево, draft подключает по одному, поэтому повторы нужны только одиночным
        if (subset.size() == 1) {
            int ownDn = rules.diameterFor(oksById.get(subset.get(0).getOksId()).getFlowTph()).getDn();
            if (ownDn < region.dn) {
                List<TieCandidate> own = finder.find(points, ownDn);
                if (detour(options, points.get(0), own)) {
                    // D-7: с запасом по диаметру маршрута нет или он в обход, а отступы для Ду по расходу меньше и
                    // могут пропустить короче: повтор с этим Ду, отступы и предельная длина — по фактическому Ду
                    options.addAll(options(region, ownDn, region.area, subset, true, own));
                }
            }
            if (incomplete(options)) {
                // обход может не поместиться в область вокруг ОКС и кандидатов: последняя попытка на широкой области
                options.addAll(options(region, region.dn, region.wideArea, subset, false, finder.find(points, region.dn)));
            }
        }
        options.sort(Comparator.comparingDouble(option -> option.score));
        if (!options.isEmpty() && alternativeIndex(options) == 0) {
            // все ближайшие кандидаты дают ту же врезку, а вариантов нужно не меньше двух (правило variants):
            // пробуется та же сеть дальше OTHER_TIE_M от лучшей врезки
            List<TieCandidate> along = finder.along(options.get(0).tree.tie, region.dn, OTHER_TIE_M);
            options.addAll(options(region, region.dn, region.area, subset, false, along));
            options.sort(Comparator.comparingDouble(option -> option.score));
        }
        region.options.put(key, options);
        return options;
    }

    private static boolean incomplete(List<Option> options) {
        return options.stream().allMatch(option -> !option.tree.unconnected.isEmpty());
    }

    /**
     * Полного дерева нет или самое короткое длиннее прямой до ближайшего кандидата врезки больше чем в DETOUR_RATIO раз:
     * только тогда граф с меньшими отступами может дать трассу заметно короче, иначе повтор не окупает время.
     */
    private static boolean detour(List<Option> options, Point point, List<TieCandidate> candidates) {
        double shortest = options.stream().filter(option -> option.tree.unconnected.isEmpty())
                .mapToDouble(option -> option.tree.length()).min().orElse(Double.POSITIVE_INFINITY);
        double straight = candidates.stream().mapToDouble(candidate -> candidate.getPoint().distance(point))
                .min().orElse(Double.POSITIVE_INFINITY);
        return shortest > DETOUR_RATIO * straight;
    }

    private List<Option> options(Region region, int dn, Envelope area, List<ConnectionPoint> subset, boolean verify,
            List<TieCandidate> candidates) {
        List<Option> options = new ArrayList<>();
        for (TieCandidate candidate : candidates) {
            Tree tree = builder.build(region.router(dn, area), dn, area, candidate, subset);
            if (tree.edges.isEmpty()) {
                continue;
            }
            Set<String> ids = new HashSet<>();
            tree.unconnected.forEach(connection -> ids.add(connection.getOksId()));
            try {
                Variant alone = assembler.assemble("0", 0, List.of(tree), unconnected(ids));
                if (!verify || clearanceHolds(tree, alone, area)) {
                    options.add(new Option(tree, alone.getSummary().getScore()));
                }
            } catch (IllegalStateException | IllegalArgumentException e) {
                // дерево нарушает правила при сборке (предельная длина, отступ участка, число поворотов): кандидат отбрасывается
            }
        }
        options.sort(Comparator.comparingDouble(option -> option.score));
        return options;
    }

    /** Отступы дерева, построенного по графу меньшего диаметра, проверяются для наибольшего фактического диаметра. */
    private boolean clearanceHolds(Tree tree, Variant alone, Envelope area) {
        int dn = alone.getSegments().stream().mapToInt(NewSegment::getDiameter).max().orElseThrow();
        ObstacleSet obstacles = new ObstacleSet(input, rules, area, dn);
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] coords = edge.line.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                if (Double.isNaN(obstacles.edgeWeight(coords[i], coords[i + 1], tree.tie.getIgnored()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Первый вариант дерева с другой врезкой: другой объект или точка дальше OTHER_TIE_M. */
    private static int alternativeIndex(List<Option> options) {
        for (int i = 1; i < options.size(); i++) {
            TieCandidate best = options.get(0).tree.tie;
            TieCandidate other = options.get(i).tree.tie;
            if (!best.getExistingObjectId().equals(other.getExistingObjectId())
                    || best.getPoint().distance(other.getPoint()) > OTHER_TIE_M) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Дерево не касается уже принятых. Деревья с общей камерой врезки не переполняют её и собираются вместе:
     * цепочки одного диаметра у них общие через камеру.
     */
    private boolean compatible(Tree tree, List<Tree> accepted) {
        Geometry geometry = geometry(tree);
        int rootDegree = tree.degree(tree.root);
        for (Tree other : accepted) {
            if (other.root.key.equals(tree.root.key)) {
                rootDegree += other.degree(other.root);
            }
        }
        if (rootDegree > tree.tie.getCapacity()) {
            return false;
        }
        List<Tree> sharing = new ArrayList<>();
        for (Tree other : accepted) {
            if (other.root.key.equals(tree.root.key)) {
                Geometry clip = factory.createPoint(tree.root.point).buffer(SHARED_ROOT_CLIP_M);
                if (geometry.difference(clip).distance(geometry(other).difference(clip)) <= SHARED_ROOT_APART_M) {
                    return false;
                }
                sharing.add(other);
            } else if (geometry.distance(geometry(other)) <= TREES_APART_M) {
                return false;
            }
        }
        if (sharing.isEmpty()) {
            return true;
        }
        sharing.add(tree);
        try {
            assembler.assemble("0", 0, sharing, List.of());
            return true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Правило variants: другой existing_object_id, врезка дальше 20 м от всех врезок другого или другое разбиение. */
    private boolean differ(Draft a, Draft b) {
        Set<String> idsA = new HashSet<>();
        Set<String> idsB = new HashSet<>();
        a.trees.forEach(tree -> idsA.add(tree.tie.getExistingObjectId()));
        b.trees.forEach(tree -> idsB.add(tree.tie.getExistingObjectId()));
        return !idsA.equals(idsB) || farTie(a, b) || farTie(b, a) || !partition(a).equals(partition(b));
    }

    private static boolean farTie(Draft mine, Draft others) {
        for (Tree tree : mine.trees) {
            boolean far = true;
            for (Tree other : others.trees) {
                far &= tree.tie.getPoint().distance(other.tie.getPoint()) > OTHER_TIE_M;
            }
            if (far) {
                return true;
            }
        }
        return false;
    }

    private static Set<Set<String>> partition(Draft draft) {
        Map<String, Set<String>> byRoot = new HashMap<>();
        for (Tree tree : draft.trees) {
            Set<String> oks = byRoot.computeIfAbsent(tree.root.key, key -> new HashSet<>());
            tree.connected().forEach(connection -> oks.add(connection.getOksId()));
        }
        return new HashSet<>(byRoot.values());
    }

    /** R-11: больше 80 % длины меньшего варианта совпадает с трассой другого. */
    private boolean sameRoute(Draft a, Draft b) {
        Geometry lineA = lines(a);
        Geometry lineB = lines(b);
        double shorter = Math.min(lineA.getLength(), lineB.getLength());
        if (shorter == 0) {
            return lineA.getLength() == lineB.getLength();
        }
        double common = Math.min(lineA.intersection(lineB.buffer(SAME_ROUTE_M)).getLength(),
                lineB.intersection(lineA.buffer(SAME_ROUTE_M)).getLength());
        return common > SAME_ROUTE_SHARE * shorter;
    }

    private Geometry lines(Draft draft) {
        return factory.createMultiLineString(draft.trees.stream().flatMap(tree -> tree.edges.stream())
                .map(edge -> edge.line).toArray(LineString[]::new));
    }

    private Geometry geometry(Tree tree) {
        return factory.createMultiLineString(tree.edges.stream().map(edge -> edge.line).toArray(LineString[]::new));
    }

    private List<FutureOks> unconnected(Set<String> oksIds) {
        List<FutureOks> result = new ArrayList<>();
        for (FutureOks oks : input.getFutureOks()) {
            if (oksIds.contains(oks.getId()) || !connectionByOks.containsKey(oks.getId())) {
                result.add(oks);
            }
        }
        return result;
    }

    private double flow(List<ConnectionPoint> connections) {
        double flow = 0;
        for (ConnectionPoint connection : connections) {
            flow += oksById.get(connection.getOksId()).getFlowTph();
        }
        return flow;
    }

    /** Точки подключения и, для нескольких ОКС, их центр. */
    private List<Point> points(List<ConnectionPoint> connections) {
        List<Point> points = new ArrayList<>();
        connections.forEach(connection -> points.add(connection.getGeometry()));
        if (connections.size() > 1) {
            points.add(factory.createPoint(center(connections)));
        }
        return points;
    }

    /** Одиночная связь: ОКС в одной группе, если цепочка точек подключения с шагом не больше GROUP_DISTANCE_M. */
    private static List<List<ConnectionPoint>> groups(List<ConnectionPoint> connections) {
        int[] root = new int[connections.size()];
        for (int i = 0; i < root.length; i++) {
            root[i] = i;
        }
        for (int i = 0; i < connections.size(); i++) {
            for (int j = i + 1; j < connections.size(); j++) {
                if (connections.get(i).getGeometry().distance(connections.get(j).getGeometry()) <= GROUP_DISTANCE_M) {
                    root[find(root, i)] = find(root, j);
                }
            }
        }
        Map<Integer, List<ConnectionPoint>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < connections.size(); i++) {
            byRoot.computeIfAbsent(find(root, i), key -> new ArrayList<>()).add(connections.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    /** k-means с k = 2, старт с двух самых далёких точек. */
    static List<List<ConnectionPoint>> kMeans(List<ConnectionPoint> connections) {
        Coordinate a = null;
        Coordinate b = null;
        for (ConnectionPoint p : connections) {
            for (ConnectionPoint q : connections) {
                if (a == null || p.getGeometry().distance(q.getGeometry()) > a.distance(b)) {
                    a = p.getGeometry().getCoordinate().copy();
                    b = q.getGeometry().getCoordinate().copy();
                }
            }
        }
        List<ConnectionPoint> first = new ArrayList<>();
        List<ConnectionPoint> second = new ArrayList<>();
        for (int iteration = 0; iteration < KMEANS_ITERATIONS; iteration++) {
            first.clear();
            second.clear();
            for (ConnectionPoint connection : connections) {
                Coordinate c = connection.getGeometry().getCoordinate();
                (c.distance(a) <= c.distance(b) ? first : second).add(connection);
            }
            if (first.isEmpty() || second.isEmpty()) {
                break;
            }
            a = center(first);
            b = center(second);
        }
        List<List<ConnectionPoint>> result = new ArrayList<>();
        for (List<ConnectionPoint> part : List.of(first, second)) {
            if (!part.isEmpty()) {
                result.add(new ArrayList<>(part));
            }
        }
        return result;
    }

    private static Coordinate center(List<ConnectionPoint> connections) {
        Coordinate center = new Coordinate(0, 0);
        for (ConnectionPoint connection : connections) {
            center.x += connection.getGeometry().getX() / connections.size();
            center.y += connection.getGeometry().getY() / connections.size();
        }
        return center;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) {
            root[i] = root[root[i]];
            i = root[i];
        }
        return i;
    }
}
