package ru.lct.heatnet.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.graph.ObstacleSet;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.rules.Rules;

/**
 * Перенос камеры ветвления готового дерева в точку рядом. Дерево растёт присоединением ветки в первой точке касания,
 * и камера стоит буквой Т; сдвиг в сторону равнодействующей рёбер камеры (вес ребра — цена метра по Ду его расхода
 * плюс цена длины в S) ведёт развилку к точке Штейнера. Каждое ребро камеры перекладывается из новой точки прямой к
 * самой дальней своей вершине, до которой прямая допустима по зонам графа дерева; одно ребро может идти и через
 * прежнюю точку камеры. Финальный участок к точке подключения внутри здания не меняется (п. 2.2). Точки пробуются по
 * оценке выигрыша, окончательно выбирает сборка по точному score.
 */
final class JunctionMover {
    /** Точки вокруг камеры: повороты от направления равнодействующей и расстояния, и точки на рёбрах камеры. */
    private static final double[] TURNS_DEG = {0, 20, -20, 40, -40, 60, -60, 90, -90};
    private static final double[] RADII_M = {1, 2, 3, 5, 7, 10, 13, 16, 20};
    private static final double[] ALONG_M = {1, 2, 3, 5, 7, 10, 15, 20, 30};
    /** Перенос с оценкой выигрыша меньше этого (около 0,3 м трубы) не пробуется. */
    private static final double MIN_GAIN_RUB = 30_000;
    private static final double APART_M = 0.5;

    private final Rules rules;
    private final SpecialObjects specials;
    private final Map<String, ExistingOks> buildingByConnection;
    private final Map<String, FutureOks> oksById;
    private final GeometryFactory factory = new GeometryFactory();

    JunctionMover(Rules rules, SpecialObjects specials, Map<String, ExistingOks> buildingByConnection,
            Map<String, FutureOks> oksById) {
        this.rules = rules;
        this.specials = specials;
        this.buildingByConnection = buildingByConnection;
        this.oksById = oksById;
    }

    /** Камеры ветвления дерева в порядке рёбер. */
    static List<Tree.Node> junctions(Tree tree) {
        Set<Tree.Node> junctions = new LinkedHashSet<>();
        for (Tree.Edge edge : tree.edges) {
            if (edge.to.kind == Tree.Kind.JUNCTION) {
                junctions.add(edge.to);
            }
        }
        return new ArrayList<>(junctions);
    }

    /** Рёбра дерева у узла node в порядке рёбер дерева. */
    static List<Tree.Edge> incident(Tree tree, Tree.Node node) {
        List<Tree.Edge> incident = new ArrayList<>();
        for (Tree.Edge edge : tree.edges) {
            if (edge.from == node || edge.to == node) {
                incident.add(edge);
            }
        }
        return incident;
    }

    /** До limit деревьев с камерой junction в другой точке, по убыванию оценки выигрыша. */
    List<Tree> moves(Tree tree, Tree.Node junction, ObstacleSet obstacles, Envelope area, int dn, int limit) {
        Map<Tree.Edge, Double> rub = metreRub(tree);
        List<Tree.Edge> incident = incident(tree, junction);
        int count = incident.size();
        // рёбра камеры от неё, цена метра, длина и конец, у которого финальный участок в здание не трогается
        Coordinate[][] lines = new Coordinate[count][];
        double[] price = new double[count];
        double[] old = new double[count];
        boolean[] inside = new boolean[count];
        Coordinate at = junction.point;
        double fx = 0;
        double fy = 0;
        List<Coordinate> points = new ArrayList<>();
        for (int e = 0; e < count; e++) {
            Tree.Edge edge = incident.get(e);
            Tree.Node far = edge.from == junction ? edge.to : edge.from;
            lines[e] = fromJunction(edge, junction);
            price[e] = rub.get(edge);
            old[e] = edge.line.getLength();
            inside[e] = far.kind == Tree.Kind.CONNECTION && buildingByConnection.containsKey(far.connection.getId());
            Coordinate next = lines[e][1];
            double length = at.distance(next);
            fx += price[e] * (next.x - at.x) / length;
            fy += price[e] * (next.y - at.y) / length;
            LengthIndexedLine line = new LengthIndexedLine(factory.createLineString(lines[e]));
            for (double along : ALONG_M) {
                if (along < old[e] - TreeBuilder.MIN_PIECE_M) {
                    points.add(line.extractPoint(along));
                }
            }
        }
        double heading = Math.atan2(fy, fx);
        for (double turn : TURNS_DEG) {
            for (double radius : RADII_M) {
                double angle = heading + Math.toRadians(turn);
                points.add(new Coordinate(at.x + radius * Math.cos(angle), at.y + radius * Math.sin(angle)));
            }
        }
        // оценка снизу: ребро из точки не короче прямой до своего дальнего конца. Точки идут по ней, и перебор
        // кончается, когда она не лучше худшей из limit найденных
        double[] bound = new double[points.size()];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int e = 0; e < count; e++) {
                bound[i] += price[e] * (points.get(i).distance(lines[e][lines[e].length - 1]) - old[e]);
            }
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> bound[i]));
        List<Double> gains = new ArrayList<>();
        List<Map<Tree.Edge, Coordinate[]>> lays = new ArrayList<>();
        List<Coordinate> spots = new ArrayList<>();
        for (int i : order) {
            double worst = gains.size() < limit ? -MIN_GAIN_RUB : gains.get(gains.size() - 1);
            if (bound[i] >= worst) {
                break;
            }
            Coordinate point = points.get(i);
            if (!area.contains(point) || obstacles.insideForbid(point, false) || specials.near(point, dn)) {
                continue;
            }
            Map<Tree.Edge, Coordinate[]> laid = new IdentityHashMap<>();
            // оценка по уже переложенным рёбрам и прямым для остальных: ребро длиннее своего запаса не даст точке
            // обойти худшую из найденных
            double delta = bound[i];
            int through = 0;
            for (int e = 0; e < count; e++) {
                Coordinate[] coords = lines[e];
                double straight = point.distance(coords[coords.length - 1]);
                Coordinate[] line = relay(tree, coords, inside[e], incident.get(e).from == tree.root, point, obstacles,
                        straight + (worst - delta) / price[e]);
                // через прежнюю точку камеры идёт не больше одного ребра, иначе рёбра лягут друг на друга
                if (line == null || line[1].equals2D(at) && through++ > 0) {
                    break;
                }
                laid.put(incident.get(e), line);
                delta += price[e] * (length(line) - straight);
            }
            if (laid.size() < count || delta >= worst || !apart(tree, laid)) {
                continue;
            }
            int slot = 0;
            while (slot < gains.size() && gains.get(slot) <= delta) {
                slot++;
            }
            gains.add(slot, delta);
            lays.add(slot, laid);
            spots.add(slot, point);
            if (gains.size() > limit) {
                gains.remove(limit);
                lays.remove(limit);
                spots.remove(limit);
            }
        }
        List<Tree> result = new ArrayList<>();
        for (int i = 0; i < gains.size(); i++) {
            result.add(rebuilt(tree, junction, spots.get(i), lays.get(i)));
        }
        return result;
    }

    /**
     * Ребро coords (от камеры) из точки point вместо камеры: прямая к самой дальней вершине ребра, до которой она
     * допустима, остаток ребра прежний. Вершина 0 — прежняя точка камеры: ребро идёт через неё. {@code inside} —
     * ребро кончается в точке подключения внутри здания, {@code fromRoot} — ребро от врезки. null — ни одна прямая
     * не годится или ребро выходит не короче budget.
     */
    private Coordinate[] relay(Tree tree, Coordinate[] coords, boolean inside, boolean fromRoot, Coordinate point,
            ObstacleSet obstacles, double budget) {
        int n = coords.length - 1;
        if (inside && n == 1 && new LineSegment(coords[0], coords[1]).distance(point) < 1e-6
                && point.distance(coords[1]) >= TreeBuilder.MIN_PIECE_M) {
            // камера стоит на самом финальном участке, и сдвиг вдоль него участок не меняет
            return point.distance(coords[1]) < budget ? new Coordinate[] {point, coords[1]} : null;
        }
        // финальный участок от выхода из здания к точке подключения остаётся на месте (п. 2.2)
        int last = inside ? n - 1 : n;
        Set<String> ignored = tree.tie.getIgnored();
        double rest = inside ? coords[n].distance(coords[n - 1]) : 0;
        for (int k = last; k >= 0; k--) {
            Coordinate to = coords[k];
            double straight = point.distance(to);
            if (k < last) {
                rest += coords[k].distance(coords[k + 1]);
            }
            // с меньшим k ребро только длиннее
            if (straight + rest >= budget) {
                return null;
            }
            if (straight < TreeBuilder.MIN_PIECE_M) {
                continue;
            }
            if (k < n) {
                double turn = Router.deflectionDeg(point, to, coords[k + 1]);
                if (turn > Router.MAX_TURN_DEG || turn < TreeBuilder.MIN_TURN_DEG) {
                    continue;
                }
            }
            double weight = obstacles.edgeWeight(point, to, ignored);
            if (Double.isNaN(weight)) {
                continue;
            }
            // от врезки прямая обычная и не идёт вдоль трубы, как у переноса врезки к стволу
            if (fromRoot && k == n && (weight > straight + 1e-9 || obstacles.alongIgnored(to, point, ignored))) {
                continue;
            }
            Coordinate[] line = new Coordinate[n - k + 2];
            line[0] = point;
            System.arraycopy(coords, k, line, 1, n - k + 1);
            return line;
        }
        return null;
    }

    /** Новые отрезки у точки камеры не ближе APART_M к отрезкам других рёбер, кроме примыкающих к их концу. */
    private boolean apart(Tree tree, Map<Tree.Edge, Coordinate[]> laid) {
        for (Map.Entry<Tree.Edge, Coordinate[]> entry : laid.entrySet()) {
            LineSegment fresh = new LineSegment(entry.getValue()[0], entry.getValue()[1]);
            for (Tree.Edge edge : tree.edges) {
                Coordinate[] coords = laid.containsKey(edge) ? laid.get(edge) : edge.line.getCoordinates();
                for (int i = laid.containsKey(edge) ? 1 : 0; edge != entry.getKey() && i + 1 < coords.length; i++) {
                    LineSegment other = new LineSegment(coords[i], coords[i + 1]);
                    boolean adjacent = other.p0.equals2D(fresh.p1) || other.p1.equals2D(fresh.p1);
                    if (!adjacent && !Router.apart(fresh.p0, fresh.p1, other.p0, other.p1, APART_M)
                            && fresh.distance(other) < APART_M) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Дерево с камерой в точке point и переложенными рёбрами; ребро от врезки получает узел врезки нового дерева. */
    private Tree rebuilt(Tree tree, Tree.Node junction, Coordinate point, Map<Tree.Edge, Coordinate[]> laid) {
        Tree result = new Tree(tree.tie);
        result.unconnected.addAll(tree.unconnected);
        result.narrow = tree.narrow;
        Tree.Node moved = Tree.Node.junction(point);
        for (Tree.Edge edge : tree.edges) {
            Tree.Node from = edge.from == tree.root ? result.root : edge.from == junction ? moved : edge.from;
            Tree.Node to = edge.to == junction ? moved : edge.to;
            Coordinate[] line = laid.get(edge);
            if (line == null) {
                result.edges.add(from == edge.from ? edge : new Tree.Edge(from, to, edge.line));
            } else {
                result.edges.add(new Tree.Edge(from, to, factory.createLineString(edge.to == junction ? reversed(line) : line)));
            }
        }
        return result;
    }

    /** Цена метра ребра в рублях: труба по Ду расхода ниже по дереву и длина по весам S. */
    private Map<Tree.Edge, Double> metreRub(Tree tree) {
        Map<Tree.Node, List<Tree.Edge>> children = new IdentityHashMap<>();
        for (Tree.Edge edge : tree.edges) {
            children.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(edge);
        }
        Map<Tree.Edge, Double> flows = new IdentityHashMap<>();
        for (Tree.Edge edge : tree.edges) {
            flow(edge, children, flows);
        }
        Map<Tree.Edge, Double> rub = new IdentityHashMap<>();
        flows.forEach((edge, flow) -> rub.put(edge, rules.diameterFor(flow).getNewRubM() + rules.lengthWorthRub()));
        return rub;
    }

    private double flow(Tree.Edge edge, Map<Tree.Node, List<Tree.Edge>> children, Map<Tree.Edge, Double> flows) {
        Double known = flows.get(edge);
        if (known != null) {
            return known;
        }
        double flow = 0;
        if (edge.to.kind == Tree.Kind.CONNECTION) {
            FutureOks oks = oksById.get(edge.to.connection.getOksId());
            flow = oks == null ? 0 : oks.getFlowTph();
        }
        for (Tree.Edge child : children.getOrDefault(edge.to, List.of())) {
            flow += flow(child, children, flows);
        }
        flows.put(edge, flow);
        return flow;
    }

    /** Координаты ребра от камеры junction. */
    private static Coordinate[] fromJunction(Tree.Edge edge, Tree.Node junction) {
        Coordinate[] coords = edge.line.getCoordinates();
        return edge.from == junction ? coords : reversed(coords);
    }

    private static Coordinate[] reversed(Coordinate[] coords) {
        Coordinate[] result = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            result[i] = coords[coords.length - 1 - i];
        }
        return result;
    }

    private static double length(Coordinate[] line) {
        double length = 0;
        for (int i = 0; i + 1 < line.length; i++) {
            length += line[i].distance(line[i + 1]);
        }
        return length;
    }
}
