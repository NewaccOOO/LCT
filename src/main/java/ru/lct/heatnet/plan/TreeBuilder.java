package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.Route;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.graph.SpecialSpan;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;

/**
 * Дерево одной врезки эвристикой Такахаши–Мацуямы (D-10): на каждом шаге присоединяется ОКС с самым лёгким
 * маршрутом до уже построенного дерева. Маршрут обрезается в первой точке касания дерева со стороны ОКС, там
 * ставится камера ветвления; если в узле нет места, ответвление переносится на соседнюю точку ствола.
 *
 * <p>Точка подключения внутри полигона ОКС достижима только финальным прямым участком от ближайшей границы полигона
 * (приложение 18.09, п. 2.2): маршрут ищется от точки выхода на луче «точка → ближайшая граница» сразу за зоной
 * отступа, а сам участок точка–выход проверяется без отступа к своему полигону, но из его зоны отступа выходит один
 * раз, см. {@link Run#portal} и {@link #leavesZoneOnce}.
 */
final class TreeBuilder {
    private static final Logger log = LoggerFactory.getLogger(TreeBuilder.class);
    /** Подотрезок и расстояние между узлами не короче метра (правило geometry). */
    static final double MIN_PIECE_M = 1.0;
    static final double MIN_TURN_DEG = 3.0;
    /** Шаг и наибольший угол поворота звена у камеры ветвления, см. {@link #bends}. */
    private static final double BEND_STEP_DEG = 0.5;
    private static final double MAX_BEND_DEG = 90;
    private static final double TOUCH_M = 0.05;
    /** Валидатор не проверяет касание участков в круге 0,15 м вокруг общего узла. */
    private static final double JUNCTION_CLIP_M = 0.15;
    private static final double APART_M = 0.005;
    private static final double SAMPLE_STEP_M = 10;
    private static final double CONNECTION_GAP_M = 3;
    private static final double[] SHIFTS_M = {3, 6, 12, 24};
    /** Сдвиги камеры ветвления вдоль ребра от прежнего места, м; вершины ребра пробуются тоже, см. {@link #slides}. */
    private static final double[] SLIDE_STEPS_M = {1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50};
    /** Точка выхода стоит за зоной отступа своего ОКС на столько, чтобы не лечь на её упрощённую границу. */
    private static final double PORTAL_EXTRA_M = 0.3;
    /** Шаг и предел удлинения финального участка, пока выход лежит в чужой зоне запрета. */
    private static final double PORTAL_STEP_M = 0.5;
    private static final double PORTAL_MAX_M = 30;
    /** Сколько точек границы пробуется как начало финального участка: ближайшая, затем ближайшие точки сторон. */
    private static final int PORTAL_TRIES = 8;
    /** Проходы срезки углов рёбер, см. {@link #cut}. */
    static final int CUT_PASSES = 2;
    /**
     * Звено после выхода из здания, см. {@link #exitLink}: поворот в точке выхода и длины звена по порядку попыток;
     * heatnet.exitLink=false — без звена, выход как в v0.8.1.
     */
    private static final boolean EXIT_LINK = Boolean.parseBoolean(System.getProperty("heatnet.exitLink", "true"));
    private static final double EXIT_LINK_DEG = 80;
    private static final double[] EXIT_LINK_M = {1.5, 3, 6};

    private final int nodeLimit;
    private final Map<String, LineString> networkById;
    private final SpecialObjects specials;
    /** Полигон ОКС, в котором лежит точка подключения, по id точки; точки вне полигонов в карте нет. */
    private final Map<String, ExistingOks> buildingByConnection;
    /** leavesOnce по зданию, концам отрезка и отступу: те же точки выхода проверяются в каждом дереве перебора. */
    private final Map<List<Object>, Boolean> leavesOnceCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** leavesZoneOnce так же: финальные отрезки одни и те же во всех деревьях перебора. */
    private final Map<List<Object>, Boolean> leavesZoneOnceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final GeometryFactory factory = GEOMETRY;

    /** Место присоединения ветки: узел дерева или точка на ребре. */
    private static final class Spot {
        final Tree.Node node;
        final Tree.Edge edge;
        final double position;
        final Coordinate point;

        Spot(Tree.Node node, Tree.Edge edge, double position, Coordinate point) {
            this.node = node;
            this.edge = edge;
            this.position = position;
            this.point = point;
        }
    }

    private static final class Attach {
        final ConnectionPoint connection;
        final Coordinate[] branch;
        final Spot spot;
        final double weight;
        /** Цель маршрута, к которой он пришёл; место присоединения spot может быть сдвинуто от неё. */
        final Coordinate target;
        /** Ветка построена по графу меньшего Ду, чем у дерева, см. {@link Run#portal}. */
        final boolean narrow;
        /** Ветка со звеном после выхода, см. {@link #exitLink}. */
        boolean linked;

        Attach(ConnectionPoint connection, Coordinate[] branch, Spot spot, double weight, Coordinate target, boolean narrow) {
            this.connection = connection;
            this.branch = branch;
            this.spot = spot;
            this.weight = weight;
            this.target = target;
            this.narrow = narrow;
        }
    }

    /** Точка выхода из здания, граф ветки от неё и номер точки границы, через которую идёт финальный участок. */
    private static final class Exit {
        final Coordinate point;
        final Router router;
        final int anchor;

        Exit(Coordinate point, Router router, int anchor) {
            this.point = point;
            this.router = router;
            this.anchor = anchor;
        }
    }

    private static final Exit NO_EXIT = new Exit(null, null, Integer.MAX_VALUE);

    /** Ду по расходу точек подключения и графы других Ду той же области, см. {@link Run#portal} и {@link #cut}. */
    interface Graphs {
        int dn(List<ConnectionPoint> connections);

        ObstacleSet obstacles(int dn);

        Router router(int dn);
    }

    private static final class Piece {
        final LineSegment segment;
        final Tree.Edge edge;
        final double start;

        Piece(LineSegment segment, Tree.Edge edge, double start) {
            this.segment = segment;
            this.edge = edge;
            this.start = start;
        }
    }

    TreeBuilder(int nodeLimit, Map<String, LineString> networkById, SpecialObjects specials,
            Map<String, ExistingOks> buildingByConnection) {
        this.nodeLimit = nodeLimit;
        this.networkById = networkById;
        this.specials = specials;
        this.buildingByConnection = buildingByConnection;
    }

    /**
     * Дерево от врезки ко всем точкам подключения по графу диаметра dn; маршрут за пределами area отбрасывается.
     * Недостижимые точки попадают в {@link Tree#unconnected}.
     */
    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections) {
        return build(router, dn, area, tie, connections, 0, 0);
    }

    /**
     * То же с надбавками к целям в метрах: {@code chamberPenaltyM} за присоединение посреди ребра, где придётся
     * построить камеру, {@code tieInPenaltyM} за второй и следующие лучи из существующей камеры врезки, каждый из
     * которых — своя врезка. Так ветка предпочитает свободный луч уже построенной камеры, если крюк до неё короче
     * стоимости новой.
     */
    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            double chamberPenaltyM, double tieInPenaltyM) {
        return build(router, dn, area, tie, connections, chamberPenaltyM, tieInPenaltyM, false, null);
    }

    /**
     * То же; при {@code fromPortalDirection} маршрут выходит из здания как продолжение финального прямого участка: поворот в точке выхода не
     * круче 90° (п. 2.1). Такая трасса длиннее, поэтому строится только для точки, которая иначе остаётся без сети:
     * неподключение при доступном маршруте запрещено (п. 2.5). {@code graphs} — графы других Ду: ветка к точке, у
     * которой по графу дерева ближняя сторона здания закрыта, строится по графу Ду своего участка, см.
     * {@link Run#portal}; null — все ветки по графу дерева.
     */
    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            double chamberPenaltyM, double tieInPenaltyM, boolean fromPortalDirection, Graphs graphs) {
        return build(router, dn, area, tie, connections, chamberPenaltyM, tieInPenaltyM, fromPortalDirection, graphs, false);
    }

    /**
     * То же; {@code link} — ветку, которую отбраковал поворот круче 90° в точке выхода из здания, присоединять со
     * звеном после выхода ({@link #exitLink}). Без него такая точка уходит в {@link Tree#unconnected}, а дерево
     * помечается {@link Tree#turnStuck}.
     */
    Tree build(Router router, int dn, Envelope area, TieCandidate tie, List<ConnectionPoint> connections,
            double chamberPenaltyM, double tieInPenaltyM, boolean fromPortalDirection, Graphs graphs, boolean link) {
        Run run = new Run(router, dn, area, tie, chamberPenaltyM, tieInPenaltyM);
        run.link = link;
        run.fromPortalDirection = fromPortalDirection;
        run.graphs = graphs;
        return run.build(connections);
    }

    /**
     * Финальный участок от cp до exit выходит из своего здания и из его зоны отступа clearance по одному разу и дальше
     * в них не входит (приложение 18.09, п. 2.2: участок «от ближайшей границы до точки», полигон ОКС непроходим, а
     * отступ к нему снят только с части участка в зоне перед границей). Луч через ближайшую точку контура
     * П-образного здания иначе пересекал бы второе крыло или проходил у выступа ближе отступа.
     */
    private boolean leavesOnce(ExistingOks building, Coordinate cp, Coordinate exit, double clearance) {
        return leavesOnceCache.computeIfAbsent(List.of(building.getId(), cp.x, cp.y, exit.x, exit.y, clearance),
                key -> leavesOnce(building.getGeometry(), cp, exit, clearance));
    }

    /** {@link #leavesZoneOnce(Geometry, Coordinate, Coordinate, double)} по зданию точки. */
    boolean leavesZoneOnce(ExistingOks building, Coordinate cp, Coordinate exit, double clearance) {
        return leavesZoneOnceCache.computeIfAbsent(List.of(building.getId(), cp.x, cp.y, exit.x, exit.y, clearance),
                key -> leavesZoneOnce(building.getGeometry(), cp, exit, clearance));
    }

    /**
     * Точка выхода финального прямого участка из здания точки по зонам графа {@code router}, как у ветки дерева
     * врезки {@code tie} в области {@code area}, см. {@link Run#exit}; null — точка не в здании или выхода нет.
     */
    Coordinate exit(Router router, int dn, Envelope area, TieCandidate tie, ConnectionPoint connection) {
        ExistingOks building = buildingByConnection.get(connection.getId());
        if (building == null) {
            return null;
        }
        Exit exit = new Run(router, dn, area, tie, 0, 0).exit(connection, building, router.obstacles(), router);
        return exit == NO_EXIT ? null : exit.point;
    }

    /**
     * Отрезок tail, который кончается во врезке, не идёт вдоль участков {@code ignored}, которых врезка касается:
     * дальше 0,5 м от неё он их не пересекает.
     */
    boolean leavesNetwork(LineSegment tail, Set<String> ignored) {
        double skip = TieInFinder.TOUCH_M / tail.getLength();
        if (skip >= 1) {
            return false;
        }
        LineString away = factory.createLineString(new Coordinate[] {tail.p0, tail.pointAlong(1 - skip)});
        for (String id : ignored) {
            LineString network = networkById.get(id);
            if (network != null && network.intersects(away)) {
                return false;
            }
        }
        return true;
    }

    /** {@link #leavesOnce(ExistingOks, Coordinate, Coordinate, double)} по геометрии здания. */
    static boolean leavesOnce(Geometry building, Coordinate cp, Coordinate exit, double clearance) {
        Geometry inside = building.intersection(GEOMETRY.createLineString(new Coordinate[] {cp, exit}));
        int pieces = 0;
        for (int i = 0; i < inside.getNumGeometries(); i++) {
            if (inside.getGeometryN(i).getLength() > TOUCH_M) {
                pieces++;
            }
        }
        return pieces == 1 && leavesZoneOnce(building, cp, exit, clearance);
    }

    /**
     * Отрезок cp–exit, вышедший из зоны отступа clearance вокруг здания, в неё не возвращается. Части отрезка ближе
     * clearance к сторонам контура идут кусками, а промежуток между ними лежит целиком в здании или целиком вне
     * зоны; за промежутком вне здания кусков быть не должно.
     */
    static boolean leavesZoneOnce(Geometry building, Coordinate cp, Coordinate exit, double clearance) {
        List<double[]> spans = new ArrayList<>();
        Geometry boundary = building.getBoundary();
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] ring = boundary.getGeometryN(g).getCoordinates();
            for (int k = 0; k + 1 < ring.length; k++) {
                double[] span = near(cp, exit, ring[k], ring[k + 1], clearance);
                if (span != null) {
                    spans.add(span);
                }
            }
        }
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        LineSegment line = new LineSegment(cp, exit);
        double reach = 0;
        for (double[] span : spans) {
            if (span[0] > reach) {
                Coordinate gap = line.pointAlong((reach + span[0]) / 2 / line.getLength());
                if (SimplePointInAreaLocator.locate(gap, building) == Location.EXTERIOR) {
                    return false;
                }
            }
            reach = Math.max(reach, span[1]);
        }
        return true;
    }

    /**
     * Часть отрезка p–q ближе distance к стороне a–b: от и до в метрах от p, null — такой нет. Полоса вокруг стороны —
     * прямоугольник вдоль неё и круги у концов; она выпукла, поэтому её часть на отрезке — один кусок от самого
     * раннего начала до самого позднего конца частей этих трёх фигур.
     */
    static double[] near(Coordinate p, Coordinate q, Coordinate a, Coordinate b, double distance) {
        double length = p.distance(q);
        double ux = (q.x - p.x) / length;
        double uy = (q.y - p.y) / length;
        double from = Double.POSITIVE_INFINITY;
        double to = Double.NEGATIVE_INFINITY;
        for (Coordinate end : new Coordinate[] {a, b}) {
            // |p + t·u − end|² < distance²
            double wx = p.x - end.x;
            double wy = p.y - end.y;
            double half = ux * wx + uy * wy;
            double disc = half * half - wx * wx - wy * wy + distance * distance;
            if (disc > 0) {
                from = Math.min(from, -half - Math.sqrt(disc));
                to = Math.max(to, -half + Math.sqrt(disc));
            }
        }
        double side = a.distance(b);
        if (side > 0) {
            double vx = (b.x - a.x) / side;
            double vy = (b.y - a.y) / side;
            // проекция на сторону в [0, side], расстояние от её прямой меньше distance
            double[] along = between((p.x - a.x) * vx + (p.y - a.y) * vy, ux * vx + uy * vy, 0, side);
            double[] across = between(vx * (p.y - a.y) - vy * (p.x - a.x), vx * uy - vy * ux, -distance, distance);
            if (along != null && across != null && Math.max(along[0], across[0]) < Math.min(along[1], across[1])) {
                from = Math.min(from, Math.max(along[0], across[0]));
                to = Math.max(to, Math.min(along[1], across[1]));
            }
        }
        from = Math.max(from, 0);
        to = Math.min(to, length);
        return from < to ? new double[] {from, to} : null;
    }

    /** Значения t, при которых start + t·rate лежит в [low, high]; null — таких нет. */
    private static double[] between(double start, double rate, double low, double high) {
        if (rate == 0) {
            return start >= low && start <= high ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        }
        double first = (low - start) / rate;
        double second = (high - start) / rate;
        return new double[] {Math.min(first, second), Math.max(first, second)};
    }

    private final class Run {
        final Router router;
        final ObstacleSet obstacles;
        final int dn;
        final Envelope area;
        final Tree tree;
        final Set<String> ignored;
        final double chamberPenaltyM;
        final double tieInPenaltyM;
        // ребро дерева не меняется после создания, а его специальные части и допустимые цели нужны на каждом шаге
        // для каждого оставшегося ОКС
        final Map<Tree.Edge, List<SpecialSpan>> spansByEdge = new IdentityHashMap<>();
        /** Положения вершин ребра по длине: allowed проверяет по ним каждую точку ребра через 10 м. */
        final Map<Tree.Edge, List<Double>> verticesByEdge = new IdentityHashMap<>();
        final Map<Tree.Edge, List<Point>> targetsByEdge = new IdentityHashMap<>();
        /** Точка выхода по id точки подключения, NO_EXIT — выхода нет; точки вне полигонов в карте нет. */
        final Map<String, Exit> exitByConnection = new HashMap<>();
        /** Маршрут выходит из здания вдоль финального прямого участка, см. {@link #build}. */
        boolean fromPortalDirection;
        /** Номер шага build, цели шага по ключу {@link #key} и цели, которых на прошлом шаге не было. */
        int step;
        Set<List<Double>> keys = Set.of();
        List<Point> added = List.of();
        /** Выбор цели по точке подключения и шаг, на котором он сделан, см. {@link #choice}. */
        final Map<String, Chosen> chosen = new HashMap<>();
        /** Графы других Ду для веток, см. {@link #portal}; null — все ветки по графу дерева. */
        Graphs graphs;

        Run(Router router, int dn, Envelope area, TieCandidate tie, double chamberPenaltyM, double tieInPenaltyM) {
            this.router = router;
            this.obstacles = router.obstacles();
            this.dn = dn;
            this.area = area;
            this.tree = new Tree(tie);
            this.ignored = tie.getIgnored();
            this.chamberPenaltyM = chamberPenaltyM;
            this.tieInPenaltyM = tieInPenaltyM;
        }

        /** Звено после выхода разрешено, см. {@link #build}; turned — на шаге его не хватило какой-то точке. */
        boolean link;
        boolean turned;

        Tree build(List<ConnectionPoint> connections) {
            List<ConnectionPoint> remaining = new ArrayList<>(connections);
            while (!remaining.isEmpty()) {
                List<Point> targets = targets();
                Set<List<Double>> previous = keys;
                keys = new HashSet<>();
                added = new ArrayList<>();
                for (Point target : targets) {
                    List<Double> key = key(target.getCoordinate(), extra(target));
                    keys.add(key);
                    if (!previous.contains(key)) {
                        added.add(target);
                    }
                }
                step++;
                turned = false;
                Attach best = null;
                for (ConnectionPoint connection : remaining) {
                    Attach attach = attach(connection, targets, fromPortalDirection);
                    if (attach != null && (best == null || attach.weight < best.weight)) {
                        best = attach;
                    }
                }
                tree.turnStuck |= best == null && turned;
                if (best == null) {
                    // выход через другую сторону здания допустим только при закрытой ближней (п. 2.2), поэтому точку,
                    // от выхода которой нет ветки к этому дереву, не переводят на дальнюю сторону: она уходит из дерева
                    // и подключается отдельно
                    tree.unconnected.addAll(remaining);
                    break;
                }
                apply(best);
                remaining.remove(best.connection);
            }
            return tree;
        }

        /**
         * Точка выхода финального прямого участка из своего ОКС и граф ветки от неё; null — точка не в полигоне,
         * маршрут идёт от неё самой. Отступ от полигона — по Ду графа дерева. Если при нём ближняя сторона закрыта
         * (выход в зоне соседа, участок до него задевает чужую зону), а Ду участка у точки по её расходу меньше,
         * выход ставится по Ду участка и ветка идёт по его графу (приложение 18.09, п. 2.2: участок от ближайшей
         * границы). Если сборка поднимет Ду ветки выше, её отсеет проверка отступов по фактическому Ду.
         */
        Exit portal(ConnectionPoint connection) {
            ExistingOks building = buildingByConnection.get(connection.getId());
            if (building == null) {
                return null;
            }
            return exitByConnection.computeIfAbsent(connection.getId(), id -> {
                Exit wide = exit(connection, building, obstacles, router);
                if (graphs == null || wide.anchor == 1) {
                    return wide;
                }
                int leaf = graphs.dn(List.of(connection));
                if (leaf >= dn) {
                    return wide;
                }
                Exit tight = exit(connection, building, graphs.obstacles(leaf), null);
                if (tight.anchor >= wide.anchor) {
                    return wide;
                }
                log.debug("portal: {} anchor {} by DN{} instead of {} by DN{}", id, tight.anchor, leaf,
                        wide == NO_EXIT ? "none" : wide.anchor, dn);
                return new Exit(tight.point, graphs.router(leaf), tight.anchor);
            });
        }

        /**
         * Выход на луче от точки подключения через ближайшую точку границы полигона, сразу за зоной отступа zones,
         * дальше, пока выход лежит в чужой зоне запрета. Если участок до такого выхода недопустим или снова входит в
         * своё здание или его зону отступа, пробуются ближайшие точки других сторон полигона. NO_EXIT — выхода нет.
         */
        Exit exit(ConnectionPoint connection, ExistingOks building, ObstacleSet zones, Router branchRouter) {
            Coordinate cp = connection.getGeometry().getCoordinate();
            // сначала внешние контуры: ближайшая граница двора (дырки) ведёт внутрь зоны отступа, выхода там нет
            List<Coordinate> anchors = anchors(building.getGeometry(), cp);
            Set<String> own = new HashSet<>(ignored);
            own.add(building.getId());
            Coordinate centroid = building.getGeometry().getCentroid().getCoordinate();
            int tries = 0;
            Coordinate last = null;
            for (Coordinate anchor : anchors) {
                if (last != null && anchor.distance(last) < MIN_PIECE_M) {
                    continue;
                }
                last = anchor;
                if (tries++ >= PORTAL_TRIES) {
                    break;
                }
                double dx = anchor.x - cp.x;
                double dy = anchor.y - cp.y;
                double length = Math.hypot(dx, dy);
                if (length < 1e-6) {
                    dx = cp.x - centroid.x;
                    dy = cp.y - centroid.y;
                    length = Math.hypot(dx, dy);
                    if (length < 1e-6) {
                        continue;
                    }
                }
                dx /= length;
                dy /= length;
                double along = cp.distance(anchor) + zones.oksClearance() + PORTAL_EXTRA_M;
                Coordinate exit = new Coordinate(cp.x + dx * along, cp.y + dy * along);
                for (double extra = 0; zones.insideForbid(exit) && extra < PORTAL_MAX_M; extra += PORTAL_STEP_M) {
                    exit = new Coordinate(cp.x + dx * (along + extra), cp.y + dy * (along + extra));
                }
                boolean inForbid = zones.insideForbid(exit);
                boolean inArea = area.contains(exit);
                // пересечение с полигоном здания дорогое (у квартала сотни вершин): только после дешёвых проверок
                boolean once = !inForbid && inArea && leavesOnce(building, cp, exit, zones.oksClearance());
                double weight = !once ? Double.NaN : zones.edgeWeight(cp, exit, own);
                if (!Double.isNaN(weight)) {
                    if (tries > 1) {
                        log.debug("portal: {} anchor {} of {}", connection.getId(), tries, anchors.size());
                    }
                    return new Exit(exit, branchRouter, tries);
                }
                log.debug("portal: {} anchor {} rejected: forbid={} area={} once={} along={}", connection.getId(),
                        tries, inForbid, inArea, once, along);
            }
            return NO_EXIT;
        }

        /** Ближайшие к cp точки сторон полигона по возрастанию расстояния: внешние контуры, затем дырки. */
        List<Coordinate> anchors(Geometry building, Coordinate cp) {
            List<Coordinate> shells = new ArrayList<>();
            List<Coordinate> holes = new ArrayList<>();
            for (int g = 0; g < building.getNumGeometries(); g++) {
                org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) building.getGeometryN(g);
                closest(polygon.getExteriorRing().getCoordinates(), cp, shells);
                for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                    closest(polygon.getInteriorRingN(h).getCoordinates(), cp, holes);
                }
            }
            shells.sort(Comparator.comparingDouble(cp::distance));
            holes.sort(Comparator.comparingDouble(cp::distance));
            shells.addAll(holes);
            return shells;
        }

        void closest(Coordinate[] ring, Coordinate cp, List<Coordinate> out) {
            for (int k = 0; k + 1 < ring.length; k++) {
                out.add(new LineSegment(ring[k], ring[k + 1]).closestPoint(cp));
            }
        }

        /** Объекты, которые не проверяются у первого отрезка ветки: у финального участка — ещё и свой полигон ОКС. */
        Set<String> firstIgnored(ConnectionPoint connection) {
            ExistingOks building = buildingByConnection.get(connection.getId());
            if (building == null) {
                return ignored;
            }
            Set<String> own = new HashSet<>(ignored);
            own.add(building.getId());
            return own;
        }

        /**
         * Присоединение точки к дереву кратчайшим маршрутом от точки выхода из её здания. При
         * {@code fromPortalDirection} маршрут выходит как продолжение финального прямого участка: так поворот в точке
         * выхода не круче 90°, иначе собранную ветку отбраковала бы проверка формы (п. 2.1).
         */
        Attach attach(ConnectionPoint connection, List<Point> targets, boolean fromPortalDirection) {
            if (targets.isEmpty()) {
                return null;
            }
            Exit portal = portal(connection);
            if (portal == NO_EXIT) {
                return null;
            }
            Router branchRouter = portal == null ? router : portal.router;
            Point start = portal == null ? connection.getGeometry() : factory.createPoint(portal.point);
            // маршруты со срезанными углами у вершин зон, см. Router#cutPass
            Route route;
            if (portal == null || !fromPortalDirection) {
                Router.Choice choice = choice(connection, branchRouter, start.getCoordinate(), targets);
                route = choice == null ? null : branchRouter.route(start, choice, ignored, true);
            } else {
                route = branchRouter.routeExact(start, targets, ignored, connection.getGeometry().getCoordinate(), true);
            }
            if (route == null) {
                return null;
            }
            Coordinate[] coords = route.getGeometry().getCoordinates();
            if (portal != null) {
                Coordinate[] withPortal = new Coordinate[coords.length + 1];
                withPortal[0] = connection.getGeometry().getCoordinate();
                System.arraycopy(coords, 0, withPortal, 1, coords.length);
                coords = withPortal;
            }
            for (Coordinate c : coords) {
                if (!area.contains(c)) {
                    return null;
                }
            }
            Set<String> firstIgnored = firstIgnored(connection);
            List<Piece> pieces = pieces();
            Attach attach = attach(connection, coords, route.getWeight(), firstIgnored, pieces, branchRouter);
            if (attach != null || portal == null || !EXIT_LINK
                    || deflectionDeg(coords[0], coords[1], coords[2]) <= Router.MAX_TURN_DEG) {
                return attach;
            }
            if (!link) {
                turned = true;
                return null;
            }
            // маршрут от выхода уходит назад к стене круче 90°: звено из двух поворотов до 90° после выхода,
            // финальный участок от ближайшей границы тот же (п. 2.1, 2.2)
            for (double length : EXIT_LINK_M) {
                Coordinate[] linked = exitLink(coords, length);
                if (linked == null || !area.contains(linked[2])) {
                    continue;
                }
                double extra = linked[1].distance(linked[2]) + linked[2].distance(linked[3]) - coords[1].distance(coords[2]);
                attach = attach(connection, linked, route.getWeight() + extra, firstIgnored, pieces, branchRouter);
                if (attach != null) {
                    attach.linked = true;
                    return attach;
                }
            }
            return null;
        }

        /** Присоединение по маршруту coords от точки подключения: ветка до первого касания дерева. */
        Attach attach(ConnectionPoint connection, Coordinate[] coords, double weight, Set<String> firstIgnored,
                List<Piece> pieces, Router branchRouter) {
            Coordinate target = coords[coords.length - 1];
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate touch = firstTouch(coords[i], coords[i + 1], pieces);
                if (touch == null) {
                    continue;
                }
                List<Coordinate> head = new ArrayList<>(Arrays.asList(coords).subList(0, i + 1));
                List<Spot> spots = spots(pieces, touch);
                Attach direct = attach(connection, head, spots, pieces, weight, target, firstIgnored, branchRouter);
                double length = coords[i].distance(coords[i + 1]);
                if (direct != null || length == 0) {
                    // отрезок нулевой длины: точка подключения лежит на дереве, обойти касание не из чего
                    return direct;
                }
                // маршрут подошёл к дереву вдоль ребра, например через точку подключения другого ОКС на той же прямой:
                // прямая ветка к любому месту легла бы на ребро, поэтому ветка обходит точку касания сбоку
                double nx = (coords[i].y - coords[i + 1].y) / length;
                double ny = (coords[i + 1].x - coords[i].x) / length;
                for (double shift : SHIFTS_M) {
                    for (int side : new int[] {1, -1}) {
                        List<Coordinate> around = new ArrayList<>(head);
                        around.add(new Coordinate(touch.x + side * shift * nx, touch.y + side * shift * ny));
                        Attach aside = attach(connection, around, spots, pieces, weight, target, firstIgnored, branchRouter);
                        if (aside != null) {
                            return aside;
                        }
                    }
                }
                return null;
            }
            return null;
        }

        /**
         * Цель маршрута точки, как у {@link Router#routeToAny} по всем целям. Дерево на шаге только растёт: цели,
         * которые остались с той же надбавкой, дают прежние веса, поэтому если прежняя лучшая цель на месте, достаточно
         * сравнить её с лучшей из новых. При равных весах выбор зависит от порядка целей, и тогда, как и без прежнего
         * выбора, цели перебираются все.
         */
        Router.Choice choice(ConnectionPoint connection, Router branchRouter, Coordinate start, List<Point> targets) {
            Chosen previous = chosen.get(connection.getId());
            Router.Choice choice;
            if (previous == null || previous.step != step - 1 || previous.choice != null
                    && (previous.choice.tied() || !keys.contains(key(previous.choice.target(), previous.choice.extra())))) {
                choice = branchRouter.choose(start, targets, ignored, Double.POSITIVE_INFINITY);
            } else {
                double bound = previous.choice == null ? Double.POSITIVE_INFINITY : Math.nextUp(previous.choice.weight());
                Router.Choice fresh = added.isEmpty() ? null : branchRouter.choose(start, added, ignored, bound);
                if (fresh == null) {
                    choice = previous.choice;
                } else if (previous.choice == null || fresh.weight() < previous.choice.weight()) {
                    choice = fresh;
                } else {
                    choice = branchRouter.choose(start, targets, ignored, Double.POSITIVE_INFINITY);
                }
            }
            chosen.put(connection.getId(), new Chosen(choice, step));
            return choice;
        }

        Attach attach(ConnectionPoint connection, List<Coordinate> head, List<Spot> spots, List<Piece> pieces, double weight,
                Coordinate target, Set<String> firstIgnored, Router branchRouter) {
            ObstacleSet zones = branchRouter.obstacles();
            for (Spot spot : spots) {
                Coordinate[] branch = branch(head, spot.point, zones);
                if (branch != null && valid(branch, pieces, spot, firstIgnored, zones)) {
                    return new Attach(connection, branch, spot, weight, target, branchRouter != router);
                }
            }
            return null;
        }

        /** Цели маршрута: свободные узлы дерева, вершины рёбер и точки вдоль рёбер, где можно поставить камеру. */
        List<Point> targets() {
            List<Point> targets = new ArrayList<>();
            if (tree.degree(tree.root) < tree.tie.getCapacity()) {
                Point root = factory.createPoint(tree.root.point);
                if (tree.tie.isChamber() && tree.degree(tree.root) >= 1) {
                    root.setUserData(tieInPenaltyM);
                }
                targets.add(root);
            }
            Set<Tree.Node> junctions = new LinkedHashSet<>();
            for (Tree.Edge edge : tree.edges) {
                junctions.add(edge.from);
                junctions.add(edge.to);
            }
            for (Tree.Node node : junctions) {
                if (node.kind == Tree.Kind.JUNCTION && tree.degree(node) < nodeLimit) {
                    targets.add(factory.createPoint(node.point));
                }
            }
            for (Tree.Edge edge : tree.edges) {
                targets.addAll(targetsByEdge.computeIfAbsent(edge, this::edgeTargets));
            }
            return targets;
        }

        List<Point> edgeTargets(Tree.Edge edge) {
            List<Point> targets = new ArrayList<>();
            List<SpecialSpan> spans = spans(edge);
            LengthIndexedLine indexed = new LengthIndexedLine(edge.line);
            List<Double> positions = vertexPositions(edge.line);
            for (double at = SAMPLE_STEP_M; at < edge.line.getLength(); at += SAMPLE_STEP_M) {
                positions.add(at);
            }
            for (double at : positions) {
                if (allowed(edge, at, spans)) {
                    Point target = factory.createPoint(indexed.extractPoint(at));
                    target.setUserData(chamberPenaltyM);
                    targets.add(target);
                }
            }
            return targets;
        }

        List<SpecialSpan> spans(Tree.Edge edge) {
            return spansByEdge.computeIfAbsent(edge, e -> obstacles.spans(e.line, ignored));
        }

        /** Точка на отрезке p–q, где маршрут впервые подходит к дереву ближе TOUCH_M, или null. */
        Coordinate firstTouch(Coordinate p, Coordinate q, List<Piece> pieces) {
            LineSegment route = new LineSegment(p, q);
            double best = Double.POSITIVE_INFINITY;
            if (tree.edges.isEmpty() && route.distance(tree.root.point) <= TOUCH_M) {
                best = clamp(route.projectionFactor(tree.root.point));
            }
            for (Piece piece : pieces) {
                LineSegment segment = piece.segment;
                if (Router.apart(p, q, segment.p0, segment.p1, TOUCH_M) || route.distance(segment) > TOUCH_M) {
                    continue;
                }
                List<Coordinate> hits = new ArrayList<>();
                Coordinate cross = route.intersection(segment);
                if (cross != null) {
                    hits.add(cross);
                }
                for (Coordinate c : List.of(p, q)) {
                    if (segment.distance(c) <= TOUCH_M) {
                        hits.add(c);
                    }
                }
                for (Coordinate c : List.of(segment.p0, segment.p1)) {
                    if (route.distance(c) <= TOUCH_M) {
                        hits.add(c);
                    }
                }
                for (Coordinate c : hits) {
                    best = Math.min(best, clamp(route.projectionFactor(c)));
                }
            }
            return best == Double.POSITIVE_INFINITY ? null : route.pointAlong(best);
        }

        /** Места присоединения по порядку предпочтения: ближайшее к касанию, затем сдвиги вдоль ствола. */
        List<Spot> spots(List<Piece> pieces, Coordinate touch) {
            List<Spot> spots = new ArrayList<>();
            if (tree.edges.isEmpty()) {
                spots.add(new Spot(tree.root, null, 0, tree.root.point));
                return spots;
            }
            Piece nearest = pieces.get(0);
            for (Piece piece : pieces) {
                if (piece.segment.distance(touch) < nearest.segment.distance(touch)) {
                    nearest = piece;
                }
            }
            Tree.Edge edge = nearest.edge;
            double length = edge.line.getLength();
            double position = nearest.start + clamp(nearest.segment.projectionFactor(touch)) * nearest.segment.getLength();
            Tree.Node node = position <= MIN_PIECE_M ? edge.from : position >= length - MIN_PIECE_M ? edge.to : null;
            if (node != null) {
                if (node.kind != Tree.Kind.CONNECTION && tree.degree(node) < limit(node)) {
                    spots.add(new Spot(node, null, 0, node.point));
                }
                for (double shift : SHIFTS_M) {
                    for (Tree.Edge incident : tree.edges) {
                        if (incident.from == node) {
                            addEdgeSpot(spots, incident, shift);
                        } else if (incident.to == node) {
                            addEdgeSpot(spots, incident, incident.line.getLength() - shift);
                        }
                    }
                }
                return spots;
            }
            for (double vertex : vertexPositions(edge.line)) {
                if (Math.abs(vertex - position) <= MIN_PIECE_M) {
                    position = vertex;
                }
            }
            addEdgeSpot(spots, edge, position);
            for (double shift : SHIFTS_M) {
                addEdgeSpot(spots, edge, position - shift);
                addEdgeSpot(spots, edge, position + shift);
            }
            return spots;
        }

        void addEdgeSpot(List<Spot> spots, Tree.Edge edge, double position) {
            if (allowed(edge, position, spans(edge))) {
                spots.add(new Spot(null, edge, position, new LengthIndexedLine(edge.line).extractPoint(position)));
            }
        }

        /**
         * Камера ветвления на ребре: не ближе метра к узлам и вершинам, не в специальной части и не в зоне сближения
         * с объектом специального прохода (узел там лишил бы участок права на отступ через специальный участок),
         * не у точки подключения и не в зоне запрета (на финальном участке в свой ОКС).
         */
        boolean allowed(Tree.Edge edge, double position, List<SpecialSpan> spans) {
            return spotAllowed(edge, position, verticesByEdge.computeIfAbsent(edge, e -> vertexPositions(e.line)), spans,
                    obstacles, dn);
        }

        Coordinate[] branch(List<Coordinate> head, Coordinate end, ObstacleSet zones) {
            List<Coordinate> coords = new ArrayList<>(head);
            while (!coords.isEmpty() && coords.get(coords.size() - 1).distance(end) < 1e-6) {
                coords.remove(coords.size() - 1);
            }
            coords.add(end);
            if (coords.size() < 2) {
                return null;
            }
            straighten(coords, zones, ignored);
            return coords.toArray(new Coordinate[0]);
        }

        boolean valid(Coordinate[] branch, List<Piece> pieces, Spot spot, Set<String> firstIgnored, ObstacleSet zones) {
            // сначала дешёвые проверки: почти все отвергнутые ветки отпадают на изломах, а вес отрезка дорогой
            for (int i = 1; i + 1 < branch.length; i++) {
                double deflection = deflectionDeg(branch[i - 1], branch[i], branch[i + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < MIN_TURN_DEG) {
                    return false;
                }
            }
            int last = branch.length - 1;
            LineSegment tail = new LineSegment(branch[last - 1], branch[last]);
            if (tail.getLength() <= JUNCTION_CLIP_M) {
                return false;
            }
            for (int i = 0; i + 1 < branch.length; i++) {
                if (Double.isNaN(zones.edgeWeight(branch[i], branch[i + 1], i == 0 ? firstIgnored : ignored))) {
                    return false;
                }
            }
            List<SpecialSpan> spans = zones.spans(factory.createLineString(branch), ignored);
            double at = 0;
            for (int i = 0; i + 1 < branch.length; i++) {
                double length = branch[i].distance(branch[i + 1]);
                if (length < MIN_PIECE_M && !overlaps(spans, at, at + length)) {
                    return false;
                }
                at += length;
            }
            Coordinate[] clipped = branch.clone();
            clipped[last] = tail.pointAlong(1 - JUNCTION_CLIP_M / tail.getLength());
            for (int i = 0; i < last; i++) {
                LineSegment segment = new LineSegment(clipped[i], clipped[i + 1]);
                for (Piece piece : pieces) {
                    if (!Router.apart(clipped[i], clipped[i + 1], piece.segment.p0, piece.segment.p1, APART_M)
                            && segment.distance(piece.segment) <= APART_M) {
                        return false;
                    }
                }
            }
            return spot.node != tree.root || leavesNetwork(tail);
        }

        boolean leavesNetwork(LineSegment tail) {
            return TreeBuilder.this.leavesNetwork(tail, ignored);
        }

        void apply(Attach attach) {
            tree.narrow |= attach.narrow;
            tree.linked |= attach.linked;
            Spot spot = attach.spot;
            Tree.Node at = spot.node;
            if (at == null) {
                at = Tree.Node.junction(spot.point);
                Tree.Edge edge = spot.edge;
                int index = tree.edges.indexOf(edge);
                tree.edges.set(index, new Tree.Edge(edge.from, at, part(edge.line, 0, spot.position, spot.point, false)));
                tree.edges.add(index + 1, new Tree.Edge(at, edge.to,
                        part(edge.line, spot.position, edge.line.getLength(), spot.point, true)));
            }
            Coordinate[] reversed = new Coordinate[attach.branch.length];
            for (int i = 0; i < reversed.length; i++) {
                reversed[i] = attach.branch[attach.branch.length - 1 - i];
            }
            reversed[0] = at.point;
            tree.edges.add(new Tree.Edge(at, Tree.Node.connection(attach.connection), factory.createLineString(reversed)));
        }

        int limit(Tree.Node node) {
            return node == tree.root ? tree.tie.getCapacity() : nodeLimit;
        }

        List<Piece> pieces() {
            List<Piece> pieces = new ArrayList<>();
            for (Tree.Edge edge : tree.edges) {
                Coordinate[] coords = edge.line.getCoordinates();
                double start = 0;
                for (int i = 0; i + 1 < coords.length; i++) {
                    LineSegment segment = new LineSegment(coords[i], coords[i + 1]);
                    pieces.add(new Piece(segment, edge, start));
                    start += segment.getLength();
                }
            }
            return pieces;
        }
    }

    /**
     * Дерево с углами рёбер, срезанными хордами ({@link Router#cutPass}) в passes проходов (0 — без срезки), и
     * доведённой формой ({@link Router#sharpen}): новые куски не ближе CUT_APART_M к другим рёбрам, точка выхода из
     * здания остаётся на месте — финальный участок идёт от ближайшей границы. Ребро проверяется по зонам Ду расхода
     * точек ниже него, если этот Ду меньше Ду графа dn: проверка сдаваемых участков меряет отступы по Ду участка, и
     * лишняя по ней вершина не должна остаться из-за зон ствола. Такое дерево помечается narrow, и сборка проверяет
     * его отступы по Ду участков; graphs null — все рёбра по зонам графа. Узлы и топология дерева прежние; без
     * изменений возвращается то же дерево.
     */
    Tree cut(Tree tree, Router router, int dn, Graphs graphs, int passes) {
        Map<Tree.Node, List<ConnectionPoint>> below = new IdentityHashMap<>();
        List<LineString> lines = new ArrayList<>();
        List<Boolean> exits = new ArrayList<>();
        List<ObstacleSet> zones = new ArrayList<>();
        boolean[] narrow = new boolean[tree.edges.size()];
        long points = 0;
        for (Tree.Edge edge : tree.edges) {
            int edgeDn = graphs == null ? dn : graphs.dn(below(tree, edge.to, below));
            narrow[lines.size()] = edgeDn < dn;
            zones.add(edgeDn < dn ? graphs.obstacles(edgeDn) : router.obstacles());
            exits.add(edge.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(edge.to.connection.getId())
                    && edge.line.getNumPoints() > 2);
            lines.add(edge.line);
            points += edge.line.getNumPoints();
        }
        Set<String> ignored = tree.tie.getIgnored();
        // срезка зависит только от линий рёбер, их зон и точек выхода, а одно и то же дерево приходит сюда много раз:
        // ствол, проложенный заново от первой камеры, у разных кандидатов врезки один и тот же
        Cut cut = router.cached(List.of("treeCut", passes, ignored, lines, exits, zones), 128 * points,
                () -> cut(router, lines, exits, zones, ignored, passes));
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        boolean changed = false;
        for (int e = 0; e < tree.edges.size(); e++) {
            Tree.Edge edge = tree.edges.get(e);
            result.narrow |= cut.changed[e] && narrow[e];
            changed |= cut.changed[e];
            // у нового дерева свой узел врезки: ребро от прежнего degree(root) не считает, и ёмкость общей камеры
            // врезки (VariantEnumerator#compatible) не проверялась бы
            Tree.Node from = edge.from == tree.root ? result.root : edge.from;
            result.edges.add(new Tree.Edge(from, edge.to, factory.createLineString(cut.lines[e].clone())));
        }
        return changed ? result : tree;
    }

    /** Вершины рёбер после срезки и доводки и какие рёбра изменились, см. {@link #cut}. */
    private static final class Cut {
        final Coordinate[][] lines;
        final boolean[] changed;

        Cut(Coordinate[][] lines, boolean[] changed) {
            this.lines = lines;
            this.changed = changed;
        }
    }

    /** Срезка и доводка рёбер lines по зонам zones; exits — у ребра точка выхода из здания перед последней вершиной. */
    private Cut cut(Router router, List<LineString> lines, List<Boolean> exits, List<ObstacleSet> zones,
            Set<String> ignored, int passes) {
        // рёбра, уже срезанные на этом дереве, сравниваются в новом виде: выпрямление дуги уводит ребро наружу
        List<LineString> current = new ArrayList<>(lines);
        Coordinate[][] result = new Coordinate[lines.size()][];
        boolean[] changed = new boolean[lines.size()];
        for (int e = 0; e < lines.size(); e++) {
            List<LineSegment> others = new ArrayList<>();
            for (int o = 0; o < current.size(); o++) {
                Coordinate[] coords = current.get(o).getCoordinates();
                for (int i = 0; o != e && i + 1 < coords.length; i++) {
                    others.add(new LineSegment(coords[i], coords[i + 1]));
                }
            }
            List<Coordinate> coords = new ArrayList<>(Arrays.asList(lines.get(e).getCoordinates()));
            Coordinate exit = exits.get(e) ? coords.get(coords.size() - 2) : null;
            for (int pass = 0; pass < passes && router.cutPass(zones.get(e), coords, ignored, exit, others); pass++) {
                changed[e] = true;
            }
            changed[e] |= router.sharpen(zones.get(e), coords, ignored, exit, others);
            result[e] = coords.toArray(new Coordinate[0]);
            current.set(e, factory.createLineString(result[e]));
        }
        return new Cut(result, changed);
    }

    /** Точки подключения в поддереве узла node, по узлам в memo. */
    private static List<ConnectionPoint> below(Tree tree, Tree.Node node, Map<Tree.Node, List<ConnectionPoint>> memo) {
        List<ConnectionPoint> known = memo.get(node);
        if (known != null) {
            return known;
        }
        List<ConnectionPoint> result = new ArrayList<>();
        if (node.kind == Tree.Kind.CONNECTION) {
            result.add(node.connection);
        }
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == node) {
                result.addAll(below(tree, edge.to, memo));
            }
        }
        memo.put(node, result);
        return result;
    }

    /** Место камеры ветвления на ребре, см. {@link Run#allowed}; vertices — положения вершин ребра по длине. */
    private boolean spotAllowed(Tree.Edge edge, double position, List<Double> vertices, List<SpecialSpan> spans,
            ObstacleSet zones, int dn) {
        double length = edge.line.getLength();
        return position >= MIN_PIECE_M && position <= length - MIN_PIECE_M
                && placeAllowed(edge, position, vertices, spans, zones, dn);
    }

    /** {@link #spotAllowed} без метра от концов ребра: сдвиг камеры по своему ребру уводит её от прежнего места. */
    private boolean placeAllowed(Tree.Edge edge, double position, List<Double> vertices, List<SpecialSpan> spans,
            ObstacleSet zones, int dn) {
        double length = edge.line.getLength();
        if (edge.to.kind == Tree.Kind.CONNECTION && position > length - CONNECTION_GAP_M) {
            return false;
        }
        for (double vertex : vertices) {
            double gap = Math.abs(vertex - position);
            if (gap > 1e-9 && gap < MIN_PIECE_M) {
                return false;
            }
        }
        for (SpecialSpan span : spans) {
            if (position > span.getFromM() - MIN_PIECE_M && position < span.getToM() + MIN_PIECE_M) {
                return false;
            }
        }
        Coordinate at = new LengthIndexedLine(edge.line).extractPoint(position);
        return !specials.near(at, dn) && !zones.insideForbid(at, false);
    }

    /** Сдвиг камеры ветвления: оценка в рублях (меньше нуля — дешевле) и новые линии рёбер камеры. */
    static final class Slide {
        final double gain;
        final Tree.Node junction;
        final Coordinate point;
        final Map<Tree.Edge, Coordinate[]> lines;

        Slide(double gain, Tree.Node junction, Coordinate point, Map<Tree.Edge, Coordinate[]> lines) {
            this.gain = gain;
            this.junction = junction;
            this.point = point;
            this.lines = lines;
        }
    }

    /**
     * Сдвиги камер ветвления вдоль своих рёбер: к родителю или к одному из детей. Ребро сдвига и продолжающее его
     * ребро меняются только в длине, остальные ветки камеры идут от нового места прямой к одной из своих вершин,
     * выход из здания остаётся на месте. Жадное дерево ставит камеру там, где ветку удобно присоединить в момент
     * присоединения, а более поздние ветки и разные Ду рёбер камеры не учитывает. Топология прежняя, поэтому расход и
     * цена метра ребра ({@code priceRub}) тоже: на каждую камеру берётся лучший допустимый сдвиг, который по этой цене
     * дешевле хотя бы на {@code minGainRub}, от лучшей оценки. Точную цену и все проверки сдвига даёт сборка дерева
     * {@link #moved}. {@code cache} — сдвиги по рёбрам камеры с прошлых вызовов для того же дерева: сдвиг камеры
     * зависит только от её рёбер. Камеры считаются параллельно, если {@code parallel}; результат от этого не зависит.
     */
    List<Slide> slides(Tree tree, ObstacleSet zones, int dn, Map<Tree.Edge, Double> priceRub, double minGainRub,
            Map<List<Tree.Edge>, Slide> cache, boolean parallel) {
        List<List<Tree.Edge>> junctions = new ArrayList<>();
        List<List<Tree.Edge>> missing = new ArrayList<>();
        for (Tree.Edge in : tree.edges) {
            if (in.to.kind != Tree.Kind.JUNCTION) {
                continue;
            }
            List<Tree.Edge> incident = new ArrayList<>(List.of(in));
            for (Tree.Edge edge : tree.edges) {
                if (edge.from == in.to) {
                    incident.add(edge);
                }
            }
            junctions.add(incident);
            if (!cache.containsKey(incident)) {
                missing.add(incident);
            }
        }
        java.util.stream.Stream<List<Tree.Edge>> stream = parallel ? missing.parallelStream() : missing.stream();
        List<Slide> computed = stream.map(incident -> slide(tree, incident, zones, dn, priceRub, minGainRub))
                .collect(java.util.stream.Collectors.toList());
        for (int i = 0; i < missing.size(); i++) {
            cache.put(missing.get(i), computed.get(i));
        }
        List<Slide> found = new ArrayList<>();
        for (List<Tree.Edge> incident : junctions) {
            if (cache.get(incident) != null) {
                found.add(cache.get(incident));
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    /** Лучший сдвиг камеры с рёбрами incident (первое — входящее) или null. */
    private Slide slide(Tree tree, List<Tree.Edge> incident, ObstacleSet zones, int dn, Map<Tree.Edge, Double> priceRub,
            double minGainRub) {
        Set<String> ignored = tree.tie.getIgnored();
        Tree.Edge in = incident.get(0);
        List<Tree.Edge> outs = incident.subList(1, incident.size());
        Slide best = null;
        for (Tree.Edge rail : incident) {
            List<Double> vertices = vertexPositions(rail.line);
            List<SpecialSpan> spans = null;
            double length = rail.line.getLength();
            LengthIndexedLine indexed = new LengthIndexedLine(rail.line);
            // на ребре к точке в здании камера стоит до точки выхода: финальный участок остаётся от ближней стены
            boolean toBuilding = rail.to.kind == Tree.Kind.CONNECTION
                    && buildingByConnection.containsKey(rail.to.connection.getId());
            double limit = toBuilding && !vertices.isEmpty() ? vertices.get(vertices.size() - 1) : length;
            for (double shift : shifts(vertices, length, rail == in)) {
                double at = rail == in ? length - shift : shift;
                if (at >= limit) {
                    continue;
                }
                Coordinate point = indexed.extractPoint(at);
                // оценка снизу без геометрии: ребро сдвига и продолжающее его меняются на shift, другие ветки — прямой
                // к ближайшей по длине вершине; дорогие проверки только у сдвигов, которые могут выиграть
                Map<Tree.Edge, Double> straight = new IdentityHashMap<>();
                double others = 0;
                for (Tree.Edge out : outs) {
                    straight.put(out, price(priceRub, out) * (shortest(out, point) - out.line.getLength()));
                    others += straight.get(out);
                }
                for (Tree.Edge through : rail == in ? outs : List.of(rail)) {
                    double bound = best == null ? -minGainRub : best.gain;
                    double optimistic = shift * (price(priceRub, through) - price(priceRub, in)) * (rail == in ? 1 : -1)
                            + others - straight.get(through);
                    if (optimistic >= bound) {
                        continue;
                    }
                    if (spans == null) {
                        spans = zones.spans(rail.line, ignored);
                    }
                    if (!spotAllowed(rail, at, vertices, spans, zones, dn)) {
                        break;
                    }
                    Slide slide = slide(in, rail, through, outs, at, point, zones, ignored, priceRub, straight, bound);
                    if (slide != null) {
                        best = slide;
                    }
                }
            }
        }
        return best;
    }

    /** Расстояния сдвига от камеры вдоль ребра: шаги SLIDE_STEPS_M и вершины ребра. */
    private static List<Double> shifts(List<Double> vertices, double length, boolean fromEnd) {
        Set<Double> shifts = new java.util.TreeSet<>();
        for (double step : SLIDE_STEPS_M) {
            shifts.add(step);
        }
        for (double vertex : vertices) {
            shifts.add(fromEnd ? length - vertex : vertex);
        }
        return new ArrayList<>(shifts);
    }

    /**
     * Сдвиг камеры ребра in в точку point на ребре rail (at — место на rail по длине); through — ребро, которое
     * продолжает rail за камерой: при сдвиге к родителю к нему отходит конец in, при сдвиге к ребёнку in
     * продолжается началом rail. Null — сдвиг недопустим или не дешевле bound.
     */
    private Slide slide(Tree.Edge in, Tree.Edge rail, Tree.Edge through, List<Tree.Edge> outs, double at, Coordinate point,
            ObstacleSet zones, Set<String> ignored, Map<Tree.Edge, Double> priceRub, Map<Tree.Edge, Double> straight,
            double bound) {
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        List<Coordinate> joined;
        if (rail == in) {
            lines.put(in, part(in.line, 0, at, point, false).getCoordinates());
            joined = new ArrayList<>(Arrays.asList(part(in.line, at, in.line.getLength(), point, true).getCoordinates()));
            joined.addAll(Arrays.asList(through.line.getCoordinates()).subList(1, through.line.getNumPoints()));
        } else {
            lines.put(rail, part(rail.line, at, rail.line.getLength(), point, true).getCoordinates());
            joined = new ArrayList<>(Arrays.asList(in.line.getCoordinates()));
            Coordinate[] head = part(rail.line, 0, at, point, false).getCoordinates();
            joined.addAll(Arrays.asList(head).subList(1, head.length));
        }
        // старое место камеры — вершина продолжающего ребра: излом меньше 3° снимается, круче 90° недопустим
        straighten(joined, zones, ignored);
        Coordinate[] joinedLine = joined.toArray(new Coordinate[0]);
        if (!shapeValid(joinedLine)) {
            return null;
        }
        lines.put(rail == in ? through : in, joinedLine);
        double gain = change(priceRub, in, lines) + change(priceRub, through, lines);
        // другие ветки по оценке снизу, пока их прямые не проверены: проверки дорогие, сдвиг бросается сразу
        double rest = -straight.get(through);
        for (Tree.Edge out : outs) {
            rest += straight.get(out);
        }
        for (Tree.Edge out : outs) {
            if (out == through) {
                continue;
            }
            if (gain + rest >= bound) {
                return null;
            }
            Coordinate[] line = shortcut(out, point, zones, ignored);
            if (line.length == 0) {
                return null;
            }
            lines.put(out, line);
            gain += change(priceRub, out, lines);
            rest -= straight.get(out);
        }
        return gain < bound ? new Slide(gain, in.to, point, lines) : null;
    }

    /**
     * Сдвиги камер ветвления, которые убирают вершину ребра у камеры (п. 5: без необоснованных изломов). Камера идёт
     * вдоль первого звена своего ребра rail до продолжения звена ребра s за его вершиной у камеры, и эта вершина
     * уходит. Ребро rail только короче, остальные рёбра идут от нового места прямой к своей первой вершине. Новые
     * звенья обычные с запасом {@link Router#CUT_MARGIN_M} по зонам Ду своего ребра ({@code zones}), не короче
     * {@link Router#CUT_PIECE_M} и не ближе к другим рёбрам дерева, чем допускает {@link Router#apart}. Повороты в
     * вершинах и в камере от ребра к родителю не круче MAX_TURN_DEG, место камеры проверяет {@link #placeAllowed} по
     * наибольшему Ду рёбер камеры ({@code dnByEdge}). Финальный участок в здание не меняется: у s за точкой выхода
     * только продолжается его прямая, а ребро, которое входит в здание прямо от камеры, сдвигать камеру не даёт.
     * gain — изменение цены рёбер по {@code priceRub}, сдвиги с gain больше maxGainRub не берутся. Порядок — по gain.
     */
    List<Slide> unkinks(Tree tree, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        Set<String> ignored = tree.tie.getIgnored();
        List<Slide> found = new ArrayList<>();
        for (Tree.Node junction : JunctionMover.junctions(tree)) {
            List<Tree.Edge> incident = JunctionMover.incident(tree, junction);
            Map<Tree.Edge, Coordinate[]> from = new IdentityHashMap<>();
            int dn = 0;
            for (Tree.Edge edge : incident) {
                from.put(edge, JunctionMover.fromJunction(edge, junction));
                dn = Math.max(dn, dnByEdge.get(edge));
            }
            for (Tree.Edge s : incident) {
                Coordinate[] cs = from.get(s);
                if (cs.length < 3 || deflectionDeg(cs[0], cs[1], cs[2]) < MIN_TURN_DEG) {
                    continue;
                }
                for (Tree.Edge rail : incident) {
                    Coordinate[] ct = from.get(rail);
                    Coordinate point = rail == s ? null : kinkPoint(cs[2], cs[1], ct[0], ct[1]);
                    if (point == null || point.distance(ct[1]) < Router.CUT_PIECE_M) {
                        continue;
                    }
                    double position = rail.from == junction ? point.distance(ct[0])
                            : rail.line.getLength() - point.distance(ct[0]);
                    ObstacleSet railZones = zones.apply(rail);
                    if (!placeAllowed(rail, position, vertexPositions(rail.line), railZones.spans(rail.line, ignored),
                            railZones, dn)) {
                        continue;
                    }
                    Slide slide = unkink(tree, junction, incident, from, s, rail, point, zones, priceRub, ignored);
                    if (slide != null && slide.gain <= maxGainRub) {
                        found.add(slide);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    /**
     * Сдвиг камеры junction в point на ребре rail, который убирает вершину s у камеры, см. {@link #unkinks}; s null —
     * вершины не убираются ({@link #turnSlides}). null — нельзя.
     */
    private Slide unkink(Tree tree, Tree.Node junction, List<Tree.Edge> incident, Map<Tree.Edge, Coordinate[]> from,
            Tree.Edge s, Tree.Edge rail, Coordinate point, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Double> priceRub, Set<String> ignored) {
        Map<Tree.Edge, Coordinate[]> fresh = new IdentityHashMap<>();
        for (Tree.Edge edge : incident) {
            Coordinate[] c = from.get(edge);
            int skip = edge == s ? 2 : 1;
            Coordinate[] line = new Coordinate[c.length - skip + 1];
            line[0] = point;
            System.arraycopy(c, skip, line, 1, c.length - skip);
            fresh.put(edge, line);
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            boolean inside = far.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(far.connection.getId());
            ObstacleSet edgeZones = zones.apply(edge);
            if (edge == s) {
                // новое только продолжение прямой звена за вершину: у ребра в здание — до точки выхода
                if (!edgeZones.covers(point, c[1]) || !edgeZones.plain(point, c[1], ignored, Router.CUT_MARGIN_M)) {
                    return null;
                }
            } else if (edge != rail && (inside && c.length == 2 || point.distance(c[1]) < Router.CUT_PIECE_M
                    || c.length > 2 && deflectionDeg(point, c[1], c[2]) > Router.MAX_TURN_DEG
                    || !edgeZones.covers(point, c[1]) || !edgeZones.plain(point, c[1], ignored, Router.CUT_MARGIN_M))) {
                return null;
            }
        }
        for (Tree.Edge edge : incident) {
            if (edge.to == junction) {
                for (Tree.Edge out : incident) {
                    if (out != edge && deflectionDeg(fresh.get(edge)[1], point, fresh.get(out)[1]) > Router.MAX_TURN_DEG) {
                        return null;
                    }
                }
            }
        }
        // новые звенья от камеры не ближе CUT_APART_M к отрезкам других рёбер дерева в новом виде
        for (Tree.Edge edge : incident) {
            if (edge == rail) {
                continue;
            }
            LineSegment link = new LineSegment(point, fresh.get(edge)[1]);
            List<LineSegment> others = new ArrayList<>();
            for (Tree.Edge other : tree.edges) {
                Coordinate[] c = fresh.containsKey(other) ? fresh.get(other) : other.line.getCoordinates();
                for (int i = 0; other != edge && i + 1 < c.length; i++) {
                    others.add(new LineSegment(c[i], c[i + 1]));
                }
            }
            if (!Router.apart(link, others)) {
                return null;
            }
        }
        double gain = 0;
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        for (Tree.Edge edge : incident) {
            Coordinate[] line = fresh.get(edge);
            gain += price(priceRub, edge) * (length(line) - edge.line.getLength());
            lines.put(edge, edge.from == junction ? line : JunctionMover.reversed(line));
        }
        return new Slide(gain, junction, point, lines);
    }

    /**
     * Сдвиги камеры ветвления junction вдоль первого звена её рёбер с шагом step до maxShift, после которых поворот в
     * камере на пути точки к врезке не круче MAX_TURN_DEG (п. 2.1, разъяснение 5). Рёбра идут от нового места прямой к
     * своей первой вершине, место камеры и новые звенья проверяются, как у {@link #unkinks}. Порядок — по gain,
     * сдвиги с gain больше maxGainRub не берутся.
     */
    List<Slide> turnSlides(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Integer> dnByEdge, Map<Tree.Edge, Double> priceRub, double maxGainRub, double step,
            double maxShift) {
        Set<String> ignored = tree.tie.getIgnored();
        List<Tree.Edge> incident = JunctionMover.incident(tree, junction);
        Map<Tree.Edge, Coordinate[]> from = new IdentityHashMap<>();
        int dn = 0;
        for (Tree.Edge edge : incident) {
            from.put(edge, JunctionMover.fromJunction(edge, junction));
            dn = Math.max(dn, dnByEdge.get(edge));
        }
        List<Slide> found = new ArrayList<>();
        for (Tree.Edge rail : incident) {
            Coordinate[] ct = from.get(rail);
            LineSegment link = new LineSegment(ct[0], ct[1]);
            ObstacleSet railZones = zones.apply(rail);
            List<Double> vertices = vertexPositions(rail.line);
            List<SpecialSpan> spans = railZones.spans(rail.line, ignored);
            for (double shift = step; shift <= Math.min(maxShift, link.getLength() - Router.CUT_PIECE_M); shift += step) {
                Coordinate point = link.pointAlong(shift / link.getLength());
                double position = rail.from == junction ? shift : rail.line.getLength() - shift;
                if (!placeAllowed(rail, position, vertices, spans, railZones, dn)) {
                    continue;
                }
                Slide slide = unkink(tree, junction, incident, from, null, rail, point, zones, priceRub, ignored);
                if (slide != null && slide.gain <= maxGainRub) {
                    found.add(slide);
                }
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    /**
     * Изломы у камеры ветвления junction, после которых поворот в ней на пути точки к врезке не круче MAX_TURN_DEG
     * (п. 2.1, разъяснение 5): звено одного ребра у камеры поворачивается на наименьший для этого угол с шагом
     * BEND_STEP_DEG, но не меньше MIN_TURN_DEG с шагом запаса, и ребро идёт от камеры до новой вершины в r метрах (от
     * CUT_PIECE_M, дальше вдвое больше, пока до прежней первой вершины остаётся звено), оттуда к прежней первой вершине.
     * Камера остаётся на месте, финальный участок прямо от камеры в здание не меняется, новые звенья проверяются, как у
     * {@link #unkink}, а изломы в новой вершине и в прежней первой — не меньше MIN_TURN_DEG. Порядок — по gain, изломы с
     * gain больше maxGainRub не берутся.
     */
    List<Slide> bends(Tree tree, Tree.Node junction, java.util.function.Function<Tree.Edge, ObstacleSet> zones,
            Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        List<Tree.Edge> incident = JunctionMover.incident(tree, junction);
        Map<Tree.Edge, Coordinate[]> from = new IdentityHashMap<>();
        incident.forEach(edge -> from.put(edge, JunctionMover.fromJunction(edge, junction)));
        List<Slide> found = new ArrayList<>();
        for (Tree.Edge edge : incident) {
            Coordinate[] c = from.get(edge);
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            if (c.length == 2 && far.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(far.connection.getId())) {
                continue;
            }
            double angle = Math.atan2(c[1].y - c[0].y, c[1].x - c[0].x);
            for (int sign = -1; sign <= 1; sign += 2) {
                Coordinate toward = null;
                for (double delta = MIN_TURN_DEG + BEND_STEP_DEG; toward == null && delta <= MAX_BEND_DEG; delta += BEND_STEP_DEG) {
                    double a = angle + sign * Math.toRadians(delta);
                    Coordinate unit = new Coordinate(c[0].x + Math.cos(a), c[0].y + Math.sin(a));
                    toward = turnsAllowed(junction, incident, from, edge, unit) ? unit : null;
                }
                for (double r = Router.CUT_PIECE_M; toward != null && r + Router.CUT_PIECE_M <= c[0].distance(c[1]); r *= 2) {
                    Coordinate[] line = new Coordinate[c.length + 1];
                    line[0] = c[0];
                    line[1] = new Coordinate(c[0].x + (toward.x - c[0].x) * r, c[0].y + (toward.y - c[0].y) * r);
                    System.arraycopy(c, 1, line, 2, c.length - 1);
                    Slide slide = bend(tree, junction, edge, line, zones.apply(edge), priceRub);
                    if (slide != null && slide.gain <= maxGainRub) {
                        found.add(slide);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(slide -> slide.gain));
        return found;
    }

    /** Повороты в камере junction на пути точки к врезке не круче MAX_TURN_DEG, если звено ребра edge идёт к point. */
    private static boolean turnsAllowed(Tree.Node junction, List<Tree.Edge> incident, Map<Tree.Edge, Coordinate[]> from,
            Tree.Edge edge, Coordinate point) {
        for (Tree.Edge in : incident) {
            if (in.to != junction) {
                continue;
            }
            Coordinate before = in == edge ? point : from.get(in)[1];
            for (Tree.Edge out : incident) {
                if (out != in && deflectionDeg(before, junction.point, out == edge ? point : from.get(out)[1]) > Router.MAX_TURN_DEG) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Ребро edge камеры junction с линией line от камеры, см. {@link #bends}; null — два новых звена не годятся. */
    private Slide bend(Tree tree, Tree.Node junction, Tree.Edge edge, Coordinate[] line, ObstacleSet zones,
            Map<Tree.Edge, Double> priceRub) {
        Set<String> ignored = tree.tie.getIgnored();
        List<LineSegment> others = new ArrayList<>();
        for (Tree.Edge other : tree.edges) {
            Coordinate[] c = other.line.getCoordinates();
            for (int i = 0; other != edge && i + 1 < c.length; i++) {
                others.add(new LineSegment(c[i], c[i + 1]));
            }
        }
        for (int i = 2; i + 1 < line.length; i++) {
            others.add(new LineSegment(line[i], line[i + 1]));
        }
        for (int i = 0; i < 2; i++) {
            Coordinate a = line[i];
            Coordinate b = line[i + 1];
            double turn = i + 2 < line.length ? deflectionDeg(a, b, line[i + 2]) : MIN_TURN_DEG;
            if (a.distance(b) < Router.CUT_PIECE_M || turn > Router.MAX_TURN_DEG || turn < MIN_TURN_DEG
                    || !zones.covers(a, b) || !zones.plain(a, b, ignored, Router.CUT_MARGIN_M)
                    || !Router.apart(new LineSegment(a, b), others)) {
                return null;
            }
        }
        double gain = price(priceRub, edge) * (length(line) - edge.line.getLength());
        return new Slide(gain, junction, junction.point, Map.of(edge, edge.from == junction ? line : JunctionMover.reversed(line)));
    }

    /**
     * Сдвиг новой камеры врезки на трубе в point, который убирает первую вершину ребра s от врезки (п. 5): s идёт
     * прямой от point к своей второй вершине, остальные рёбра врезки — к своей первой. Здесь только дешёвые проверки
     * новых звеньев, как у {@link #unkink}: не короче CUT_PIECE_M, поворот в следующей вершине не круче MAX_TURN_DEG,
     * у ребра в здание звено от выхода к точке остаётся; остальное проверяет {@link #retieClear}. null — нельзя или
     * gain больше maxGainRub.
     */
    Slide retie(Tree tree, Tree.Edge s, Coordinate point, Map<Tree.Edge, Double> priceRub, double maxGainRub) {
        Map<Tree.Edge, Coordinate[]> lines = new IdentityHashMap<>();
        double gain = 0;
        for (Tree.Edge edge : tree.edges) {
            if (edge.from != tree.root) {
                continue;
            }
            Coordinate[] c = edge.line.getCoordinates();
            int skip = edge == s ? 2 : 1;
            boolean inside = edge.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(edge.to.connection.getId());
            if (c.length - skip < (inside ? 2 : 1)) {
                return null;
            }
            Coordinate[] line = new Coordinate[c.length - skip + 1];
            line[0] = point;
            System.arraycopy(c, skip, line, 1, c.length - skip);
            if (point.distance(line[1]) < Router.CUT_PIECE_M
                    || line.length > 2 && deflectionDeg(point, line[1], line[2]) > Router.MAX_TURN_DEG) {
                return null;
            }
            lines.put(edge, line);
            gain += price(priceRub, edge) * (length(line) - edge.line.getLength());
        }
        return gain <= maxGainRub ? new Slide(gain, tree.root, point, lines) : null;
    }

    /**
     * Новые звенья сдвига врезки slide ({@link #retie}) обычные с запасом {@link Router#CUT_MARGIN_M} по зонам Ду
     * своего ребра, уходят от сети у новой врезки ({@link #leavesNetwork}, ignored — её участки) и не ближе к другим
     * рёбрам дерева, чем допускает {@link Router#apart}.
     */
    boolean retieClear(Tree tree, Slide slide, Set<String> ignored, java.util.function.Function<Tree.Edge, ObstacleSet> zones) {
        Coordinate point = slide.point;
        for (Map.Entry<Tree.Edge, Coordinate[]> entry : slide.lines.entrySet()) {
            Coordinate next = entry.getValue()[1];
            ObstacleSet edgeZones = zones.apply(entry.getKey());
            if (!edgeZones.covers(point, next) || !edgeZones.plain(point, next, ignored, Router.CUT_MARGIN_M)
                    || !leavesNetwork(new LineSegment(next, point), ignored)) {
                return false;
            }
            List<LineSegment> others = new ArrayList<>();
            for (Tree.Edge other : tree.edges) {
                Coordinate[] c = slide.lines.containsKey(other) ? slide.lines.get(other) : other.line.getCoordinates();
                for (int i = 0; other != entry.getKey() && i + 1 < c.length; i++) {
                    others.add(new LineSegment(c[i], c[i + 1]));
                }
            }
            if (!Router.apart(new LineSegment(point, next), others)) {
                return false;
            }
        }
        return true;
    }

    /** Точка звена c–u (не на концах) на прямой w–v по ту сторону от w, где v; null — такой нет. */
    private static Coordinate kinkPoint(Coordinate w, Coordinate v, Coordinate c, Coordinate u) {
        LineSegment kink = new LineSegment(w, v);
        LineSegment link = new LineSegment(c, u);
        Coordinate point = kink.lineIntersection(link);
        if (point == null) {
            return null;
        }
        double along = link.projectionFactor(point);
        return along > 0 && along < 1 && kink.projectionFactor(point) > 0 ? point : null;
    }

    /** Длина ветки out от point прямой к ближайшей по длине вершине, без проверок: нижняя оценка shortcut. */
    private double shortest(Tree.Edge out, Coordinate point) {
        Coordinate[] c = out.line.getCoordinates();
        double best = Double.POSITIVE_INFINITY;
        double rest = 0;
        for (int k = c.length - 1; k >= 1; k--) {
            best = Math.min(best, point.distance(c[k]) + rest);
            rest += c[k - 1].distance(c[k]);
        }
        return best;
    }

    /**
     * Ветка out от новой камеры point: прямая к одной из вершин out и дальше по out, самая короткая из допустимых;
     * у точки в здании прямая идёт не дальше точки выхода. Пустой массив — допустимой нет.
     */
    private Coordinate[] shortcut(Tree.Edge out, Coordinate point, ObstacleSet zones, Set<String> ignored) {
        Coordinate[] c = out.line.getCoordinates();
        int last = c.length - 1;
        if (out.to.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(out.to.connection.getId())) {
            last--;
        }
        double[] rest = new double[c.length];
        for (int k = c.length - 2; k >= 0; k--) {
            rest[k] = rest[k + 1] + c[k].distance(c[k + 1]);
        }
        List<Integer> order = new ArrayList<>();
        for (int k = 1; k <= last; k++) {
            order.add(k);
        }
        order.sort(Comparator.comparingDouble(k -> point.distance(c[k]) + rest[k]));
        for (int k : order) {
            double straight = point.distance(c[k]);
            if (straight < MIN_PIECE_M) {
                continue;
            }
            if (k + 1 < c.length) {
                double deflection = deflectionDeg(point, c[k], c[k + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < MIN_TURN_DEG) {
                    continue;
                }
            }
            // прямая без спецпрохода и не вдоль трубы врезки, как у переноса врезки к стволу
            if (!(zones.edgeWeight(point, c[k], ignored) <= straight + 1e-9) || zones.alongIgnored(point, c[k], ignored)) {
                continue;
            }
            Coordinate[] line = new Coordinate[c.length - k + 1];
            line[0] = point;
            System.arraycopy(c, k, line, 1, c.length - k);
            return line;
        }
        return new Coordinate[0];
    }

    /** Изломы в вершинах от 3 до 90°, подотрезки не короче метра. */
    private static boolean shapeValid(Coordinate[] line) {
        for (int i = 0; i + 1 < line.length; i++) {
            if (line[i].distance(line[i + 1]) < MIN_PIECE_M) {
                return false;
            }
            if (i > 0) {
                double deflection = deflectionDeg(line[i - 1], line[i], line[i + 1]);
                if (deflection > Router.MAX_TURN_DEG || deflection < MIN_TURN_DEG) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double price(Map<Tree.Edge, Double> priceRub, Tree.Edge edge) {
        return priceRub.get(edge);
    }

    /** Изменение цены ребра edge с новой линией из lines. */
    private static double change(Map<Tree.Edge, Double> priceRub, Tree.Edge edge, Map<Tree.Edge, Coordinate[]> lines) {
        return price(priceRub, edge) * (length(lines.get(edge)) - edge.line.getLength());
    }

    private static double length(Coordinate[] line) {
        double length = 0;
        for (int i = 0; i + 1 < line.length; i++) {
            length += line[i].distance(line[i + 1]);
        }
        return length;
    }

    /** Дерево tree со сдвигом slide, найденным для него {@link #slides}. */
    Tree moved(Tree tree, Slide slide) {
        return moved(tree, slide, tree.tie);
    }

    /** Дерево tree со сдвигом slide и врезкой tie: у сдвига врезки ({@link #retie}) она новая, у камеры — прежняя. */
    Tree moved(Tree tree, Slide slide, TieCandidate tie) {
        Tree.Node junction = slide.junction;
        Tree result = new Tree(tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        Tree.Node at = junction == tree.root ? result.root : Tree.Node.junction(slide.point);
        for (Tree.Edge edge : tree.edges) {
            Coordinate[] line = slide.lines.get(edge);
            Tree.Node from = edge.from == tree.root ? result.root : edge.from == junction ? at : edge.from;
            Tree.Node to = edge.to == junction ? at : edge.to;
            result.edges.add(line == null && from == edge.from && to == edge.to ? edge
                    : new Tree.Edge(from, to, line == null ? edge.line : factory.createLineString(line)));
        }
        return result;
    }

    /** Часть полилинии; конец у камеры ветвления ставится ровно в её точку. */
    private LineString part(LineString line, double from, double to, Coordinate junction, boolean junctionAtStart) {
        Coordinate[] coords = new LengthIndexedLine(line).extractLine(from, to).getCoordinates();
        coords[junctionAtStart ? 0 : coords.length - 1] = junction;
        return factory.createLineString(coords);
    }

    /**
     * Маршрут coords (точка подключения, выход, ...) со звеном после выхода: из выхода под EXIT_LINK_DEG к
     * финальному участку в сторону следующей вершины на length метров, оттуда к ней. Поворот в выходе 80°, в конце
     * звена — остаток до направления на следующую вершину. null — выход совпал с точкой подключения.
     */
    static Coordinate[] exitLink(Coordinate[] coords, double length) {
        Coordinate cp = coords[0];
        Coordinate exit = coords[1];
        Coordinate next = coords[2];
        double dx = exit.x - cp.x;
        double dy = exit.y - cp.y;
        double norm = Math.hypot(dx, dy);
        if (norm < 1e-9) {
            return null;
        }
        dx /= norm;
        dy /= norm;
        double side = dx * (next.y - exit.y) - dy * (next.x - exit.x) >= 0 ? 1 : -1;
        double angle = Math.toRadians(EXIT_LINK_DEG) * side;
        double ux = dx * Math.cos(angle) - dy * Math.sin(angle);
        double uy = dx * Math.sin(angle) + dy * Math.cos(angle);
        Coordinate[] linked = new Coordinate[coords.length + 1];
        linked[0] = cp;
        linked[1] = exit;
        linked[2] = new Coordinate(exit.x + ux * length, exit.y + uy * length);
        System.arraycopy(coords, 2, linked, 3, coords.length - 2);
        return linked;
    }

    /** Удаляет вершины с отклонением меньше 3° и у подотрезков короче метра, если спрямлённый отрезок допустим. */
    static void straighten(List<Coordinate> coords, ObstacleSet obstacles, Set<String> ignored) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i + 1 < coords.size() && !changed; i++) {
                Coordinate prev = coords.get(i - 1);
                Coordinate cur = coords.get(i);
                Coordinate next = coords.get(i + 1);
                boolean removable = deflectionDeg(prev, cur, next) < MIN_TURN_DEG
                        || prev.distance(cur) < MIN_PIECE_M || cur.distance(next) < MIN_PIECE_M;
                if (removable && !Double.isNaN(obstacles.edgeWeight(prev, next, ignored))) {
                    coords.remove(i);
                    changed = true;
                }
            }
        }
    }

    static List<Double> vertexPositions(LineString line) {
        List<Double> positions = new ArrayList<>();
        double at = 0;
        for (int i = 1; i + 1 < line.getNumPoints(); i++) {
            at += line.getCoordinateN(i - 1).distance(line.getCoordinateN(i));
            positions.add(at);
        }
        return positions;
    }

    static double deflectionDeg(Coordinate a, Coordinate b, Coordinate c) {
        return 180 - Math.toDegrees(Angle.angleBetween(a, b, c));
    }

    /** Выбор цели точки на шаге step, null — ни одна цель не достижима. */
    private static final class Chosen {
        final Router.Choice choice;
        final int step;

        Chosen(Router.Choice choice, int step) {
            this.choice = choice;
            this.step = step;
        }
    }

    private static List<Double> key(Coordinate target, double extra) {
        return List.of(target.x, target.y, extra);
    }

    private static double extra(Point target) {
        return target.getUserData() instanceof Double ? (Double) target.getUserData() : 0;
    }

    private static boolean overlaps(List<SpecialSpan> spans, double from, double to) {
        for (SpecialSpan span : spans) {
            if (from < span.getToM() && to > span.getFromM()) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double factor) {
        return Math.max(0, Math.min(1, factor));
    }
}
