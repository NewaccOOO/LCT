package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

/**
 * Делит рёбра дерева на куски минимального диаметра и диаметра на ступень выше так, чтобы цепочки одного
 * диаметра не превышали предельную длину.
 *
 * <p>Цепочка минимального диаметра копится от листьев к корню. Когда она длиннее предела, в неё вставляется
 * кусок на ступень выше длиной {@link #MIN_PIECE_M}, и цепочка до него получается на {@link #CUT_MARGIN_M}
 * короче предела. Ветки, сходящиеся в узле, укорачиваются так же. Кусок на ступень выше не касается верхнего узла
 * своей цепочки, поэтому не сливается с участками следующего диаметра. В конце куски, без которых цепочка
 * укладывается в предел, возвращаются к минимальному диаметру, а итог проверяется так же, как в валидаторе.
 * Если разбиения не нашлось, бросается {@link IllegalStateException}.
 */
public final class DiameterPlanner {
    private static final double MIN_PIECE_M = 2.0;
    private static final double CUT_MARGIN_M = 0.5;
    private static final double EPS = 1e-9;

    private final Rules rules;

    public DiameterPlanner(Rules rules) {
        this.rules = rules;
    }

    public List<SizedPiece> plan(List<TreeEdge> edges, String rootNode, Map<String, Double> flowByEdge) {
        return plan(edges, rootNode, flowByEdge, Map.of());
    }

    /** noCutByEdge: интервалы {from, to} в метрах от fromNode, внутри которых нельзя ставить разрез. */
    public List<SizedPiece> plan(List<TreeEdge> edges, String rootNode, Map<String, Double> flowByEdge,
            Map<String, List<double[]>> noCutByEdge) {
        return new Run(edges, rootNode, flowByEdge, noCutByEdge).plan();
    }

    /** Куски одного ребра на ступень выше ({from, to}) и длина цепочки минимального диаметра у верхнего узла. */
    private static final class Walk {
        final double accAtBottom;
        final List<double[]> bumps;
        final double exposure;

        Walk(double accAtBottom, List<double[]> bumps, double exposure) {
            this.accAtBottom = accAtBottom;
            this.bumps = bumps;
            this.exposure = exposure;
        }
    }

    private final class Run {
        final List<TreeEdge> edges;
        final String rootNode;
        final Map<String, List<double[]>> noCutByEdge;
        final Map<String, Diameter> minByEdge = new HashMap<>();
        final Map<String, TreeEdge> parentByNode = new HashMap<>();
        final Map<String, List<TreeEdge>> childrenByNode = new HashMap<>();
        final Map<String, Walk> walkByEdge = new HashMap<>();

        Run(List<TreeEdge> edges, String rootNode, Map<String, Double> flowByEdge,
                Map<String, List<double[]>> noCutByEdge) {
            this.edges = edges;
            this.rootNode = rootNode;
            this.noCutByEdge = noCutByEdge;
            for (TreeEdge edge : edges) {
                Double flow = flowByEdge.get(edge.getId());
                if (flow == null) {
                    throw new IllegalArgumentException("Нет расхода для ребра " + edge.getId());
                }
                minByEdge.put(edge.getId(), rules.diameterFor(flow));
                parentByNode.put(edge.getToNode(), edge);
                childrenByNode.computeIfAbsent(edge.getFromNode(), node -> new ArrayList<>()).add(edge);
            }
        }

        List<SizedPiece> plan() {
            List<TreeEdge> order = FlowCalculator.orderFromRoot(edges, rootNode);
            for (int i = order.size() - 1; i >= 0; i--) {
                TreeEdge edge = order.get(i);
                double acc = settleNode(edge.getToNode(), minByEdge.get(edge.getId()).getDn(), limit(edge));
                Walk walk = walkOrSettle(edge, acc, limit(edge));
                if (walk == null) {
                    throw new IllegalStateException(String.format("Ребро %s: цепочку DN%d не удаётся разрезать так, "
                            + "чтобы она была не длиннее %.0f м", edge.getId(), minByEdge.get(edge.getId()).getDn(),
                            limit(edge)));
                }
                walkByEdge.put(edge.getId(), walk);
            }
            settleNode(rootNode, -1, 0);
            List<SizedPiece> pieces = pieces();
            for (int unneeded = check(pieces); unneeded >= 0; unneeded = check(pieces)) {
                // жадная расстановка могла перестраховаться: без такого куска цепочка всё равно в пределе
                SizedPiece bump = pieces.get(unneeded);
                walkByEdge.get(bump.getEdgeId()).bumps.removeIf(range -> range[0] == bump.getFromM());
                pieces = pieces();
            }
            return pieces;
        }

        /**
         * Сводит цепочки дочерних рёбер, сходящиеся в узле, к пределу и возвращает длину цепочки диаметра parentDn,
         * которая продолжится в родительское ребро. Для этой цепочки предел ужесточается до parentTarget.
         */
        double settleNode(String node, int parentDn, double parentTarget) {
            Map<Integer, List<TreeEdge>> groups = new TreeMap<>();
            for (TreeEdge child : childrenByNode.getOrDefault(node, List.of())) {
                groups.computeIfAbsent(minByEdge.get(child.getId()).getDn(), dn -> new ArrayList<>()).add(child);
            }
            double open = 0;
            for (Map.Entry<Integer, List<TreeEdge>> group : groups.entrySet()) {
                List<TreeEdge> children = new ArrayList<>(group.getValue());
                double limit = limit(children.get(0));
                double target = group.getKey() == parentDn ? Math.min(limit, parentTarget) : limit;
                double sum = 0;
                for (TreeEdge child : children) {
                    sum += walkByEdge.get(child.getId()).exposure;
                }
                // у верхнего узла цепочки кусок на ступень выше не ставится, значит сверху остаётся не меньше 2 м
                double floor = group.getKey() == parentDn ? 0 : MIN_PIECE_M;
                boolean progress = true;
                while (sum > target + EPS && progress) {
                    // проходы повторяются: когда одна ветка укоротилась, другой может хватить более мягкого предела
                    progress = false;
                    children.sort(Comparator.comparingDouble(
                            (TreeEdge child) -> -walkByEdge.get(child.getId()).exposure));
                    for (TreeEdge child : children) {
                        if (sum <= target + EPS) {
                            break;
                        }
                        Walk walk = walkByEdge.get(child.getId());
                        double others = sum - walk.exposure;
                        double cap = Math.max(floor, Math.min(limit - CUT_MARGIN_M, target) - others);
                        Map<String, Walk> saved = new HashMap<>(walkByEdge);
                        Walk capped = walkOrSettle(child, walk.accAtBottom, cap);
                        if (capped != null && capped.exposure < walk.exposure - EPS) {
                            walkByEdge.put(child.getId(), capped);
                            sum = others + capped.exposure;
                            progress = true;
                        } else {
                            walkByEdge.clear();
                            walkByEdge.putAll(saved);
                        }
                    }
                }
                if (sum > limit + EPS) {
                    throw new IllegalStateException(String.format("В узле %s ветки DN%d сходятся в цепочку %.2f м, "
                            + "больше предела %.0f м, и разрезать их не удаётся", node, group.getKey(), sum, limit));
                }
                if (group.getKey() == parentDn) {
                    open = sum;
                }
            }
            return open;
        }

        /**
         * Как {@link #walk}, но если расстановки нет, сначала укорачивает цепочку под ребром так, чтобы ребро вошло
         * в неё целиком, а короткое ребро — чтобы цепочка у его верха была не длиннее самого ребра. Лишние
         * укорочения потом снимает {@link #plan}; вызывающий сам откатывает планы нижних рёбер при неудаче.
         */
        Walk walkOrSettle(TreeEdge edge, double acc, double cap) {
            Walk walk = walk(edge, acc, cap);
            if (walk != null || acc <= 0) {
                return walk;
            }
            double limit = limit(edge);
            double relaxedCap = Math.min(Math.max(cap, edge.getLength()), limit);
            double target = Math.max(0, Math.min(relaxedCap, limit - CUT_MARGIN_M) - edge.getLength());
            return walk(edge, settleNode(edge.getToNode(), minByEdge.get(edge.getId()).getDn(), target), relaxedCap);
        }

        /** Расставляет куски на ступень выше снизу вверх по ребру; null, если корректной расстановки нет. */
        Walk walk(TreeEdge edge, double accAtBottom, double cap) {
            Diameter min = minByEdge.get(edge.getId());
            double limit = min.getMaxLengthM();
            double budget = limit - CUT_MARGIN_M;
            List<double[]> noCut = noCutByEdge.getOrDefault(edge.getId(), List.of());
            TreeEdge parent = parentByNode.get(edge.getFromNode());
            boolean topTouch = parent != null && minByEdge.get(parent.getId()).getDn() == min.getDn();
            List<double[]> bumps = new ArrayList<>();
            double bottom = edge.getLength();
            double acc = accAtBottom;
            boolean atBottom = true;
            while (acc + bottom > cap + EPS) {
                if (rules.nextDiameter(min.getDn()) == null) {
                    return null;
                }
                if (acc + bottom <= limit + EPS) {
                    double[] bump = capBump(bottom, cap, atBottom, topTouch, noCut);
                    if (bump == null) {
                        return null;
                    }
                    bumps.add(bump);
                    return new Walk(accAtBottom, bumps, bump[0]);
                }
                double qMin = Math.min(bottom, bottom - (budget - acc));
                double[] bump = limitBump(bottom, qMin, atBottom, topTouch, noCut);
                if (bump == null) {
                    return null;
                }
                bumps.add(bump);
                bottom = bump[0];
                acc = 0;
                atBottom = false;
            }
            return new Walk(accAtBottom, bumps, acc + bottom);
        }

        /** Кусок {t, q} с нижним концом не выше qMin (в метрах от верха ребра), чтобы цепочка под ним уложилась. */
        double[] limitBump(double bottom, double qMin, boolean atBottom, boolean topTouch, List<double[]> noCut) {
            List<Double> candidates = new ArrayList<>(List.of(qMin, bottom, MIN_PIECE_M, 2 * MIN_PIECE_M));
            for (double[] interval : noCut) {
                candidates.add(interval[1] + MIN_PIECE_M);
            }
            candidates.sort(Comparator.naturalOrder());
            for (double q : candidates) {
                if (q < qMin - EPS || q > bottom + EPS) {
                    continue;
                }
                double[] bump = bump(q - MIN_PIECE_M, q, bottom, atBottom, topTouch, noCut);
                if (bump != null) {
                    return bump;
                }
            }
            return null;
        }

        /** Кусок {t, t + 2} с верхним концом t не дальше cap от верха ребра, чтобы цепочка над ним уложилась в cap. */
        double[] capBump(double bottom, double cap, boolean atBottom, boolean topTouch, List<double[]> noCut) {
            List<Double> candidates = new ArrayList<>(
                    List.of(cap, bottom - 2 * MIN_PIECE_M, bottom - MIN_PIECE_M, 0.0));
            for (double[] interval : noCut) {
                candidates.add(interval[0] - MIN_PIECE_M);
            }
            candidates.sort(Comparator.reverseOrder());
            for (double t : candidates) {
                if (t > cap + EPS) {
                    continue;
                }
                double[] bump = bump(t, t + MIN_PIECE_M, bottom, atBottom, topTouch, noCut);
                if (bump != null) {
                    return bump;
                }
            }
            return null;
        }

        double[] bump(double t, double q, double bottom, boolean atBottom, boolean topTouch, List<double[]> noCut) {
            if (t < MIN_PIECE_M - EPS) {
                if (t < -EPS || !topTouch) {
                    return null;
                }
                t = 0;
            }
            boolean bottomRunOk = bottom - q >= MIN_PIECE_M - EPS || (atBottom && Math.abs(bottom - q) <= EPS);
            if (!bottomRunOk) {
                return null;
            }
            for (double[] interval : noCut) {
                if (Math.max(t, interval[0]) < Math.min(q, interval[1]) - EPS) {
                    return null;
                }
            }
            return new double[] {t, Math.min(q, bottom)};
        }

        List<SizedPiece> pieces() {
            List<SizedPiece> pieces = new ArrayList<>();
            for (TreeEdge edge : edges) {
                pieces.addAll(pieces(edge));
            }
            return pieces;
        }

        List<SizedPiece> pieces(TreeEdge edge) {
            int minDn = minByEdge.get(edge.getId()).getDn();
            int upDn = minDn;
            List<double[]> bumps = new ArrayList<>(walkByEdge.get(edge.getId()).bumps);
            if (!bumps.isEmpty()) {
                upDn = rules.nextDiameter(minDn).getDn();
            }
            bumps.sort(Comparator.comparingDouble(bump -> bump[0]));
            List<SizedPiece> pieces = new ArrayList<>();
            double cursor = 0;
            for (double[] bump : bumps) {
                if (bump[0] > cursor + EPS) {
                    pieces.add(new SizedPiece(edge.getId(), cursor, bump[0], minDn));
                }
                pieces.add(new SizedPiece(edge.getId(), bump[0], bump[1], upDn));
                cursor = bump[1];
            }
            if (edge.getLength() > cursor + EPS || pieces.isEmpty()) {
                pieces.add(new SizedPiece(edge.getId(), cursor, edge.getLength(), minDn));
            }
            return pieces;
        }

        /**
         * Проверяет гарантии на округлённых до 0,01 м длинах, как валидатор. Бросает исключение, если кусок короче 2 м
         * или цепочка длиннее предела; возвращает индекс первого куска на ступень выше, без которого цепочка
         * не превысила бы предел, или -1.
         */
        int check(List<SizedPiece> pieces) {
            Map<String, TreeEdge> edgeById = new HashMap<>();
            for (TreeEdge edge : edges) {
                edgeById.put(edge.getId(), edge);
            }
            Map<String, List<Integer>> piecesByPoint = new HashMap<>();
            Map<String, Integer> pieceCountByEdge = new HashMap<>();
            for (int i = 0; i < pieces.size(); i++) {
                SizedPiece piece = pieces.get(i);
                TreeEdge edge = edgeById.get(piece.getEdgeId());
                piecesByPoint.computeIfAbsent(startKey(piece, edge), key -> new ArrayList<>()).add(i);
                piecesByPoint.computeIfAbsent(endKey(piece, edge), key -> new ArrayList<>()).add(i);
                pieceCountByEdge.merge(piece.getEdgeId(), 1, Integer::sum);
            }
            int[] root = new int[pieces.size()];
            for (int i = 0; i < root.length; i++) {
                root[i] = i;
            }
            for (List<Integer> atPoint : piecesByPoint.values()) {
                for (int a : atPoint) {
                    for (int b : atPoint) {
                        if (pieces.get(a).getDn() == pieces.get(b).getDn()) {
                            root[find(root, a)] = find(root, b);
                        }
                    }
                }
            }
            double[] chainLength = new double[pieces.size()];
            for (int i = 0; i < pieces.size(); i++) {
                chainLength[find(root, i)] += length(pieces.get(i));
            }
            for (int i = 0; i < pieces.size(); i++) {
                SizedPiece piece = pieces.get(i);
                if (length(piece) < MIN_PIECE_M - EPS && pieceCountByEdge.get(piece.getEdgeId()) > 1) {
                    throw new IllegalStateException("Ребро " + piece.getEdgeId() + ": кусок короче 2 м");
                }
                if (chainLength[find(root, i)] > rules.diameter(piece.getDn()).getMaxLengthM() + EPS) {
                    throw new IllegalStateException(String.format("Ребро %s: цепочка DN%d длиной %.2f м длиннее "
                            + "предела", piece.getEdgeId(), piece.getDn(), chainLength[find(root, i)]));
                }
            }
            for (int i = 0; i < pieces.size(); i++) {
                SizedPiece piece = pieces.get(i);
                TreeEdge edge = edgeById.get(piece.getEdgeId());
                Diameter min = minByEdge.get(piece.getEdgeId());
                if (piece.getDn() == min.getDn()) {
                    continue;
                }
                Set<Integer> neighbours = new HashSet<>();
                for (String key : List.of(startKey(piece, edge), endKey(piece, edge))) {
                    for (int other : piecesByPoint.get(key)) {
                        if (pieces.get(other).getDn() == min.getDn()) {
                            neighbours.add(find(root, other));
                        }
                    }
                }
                double merged = length(piece);
                for (int neighbour : neighbours) {
                    merged += chainLength[neighbour];
                }
                if (merged <= min.getMaxLengthM() + EPS) {
                    return i;
                }
            }
            return -1;
        }

        double limit(TreeEdge edge) {
            return minByEdge.get(edge.getId()).getMaxLengthM();
        }
    }

    private static String startKey(SizedPiece piece, TreeEdge edge) {
        return piece.getFromM() <= EPS ? "node:" + edge.getFromNode() : "cut:" + edge.getId() + ":" + piece.getFromM();
    }

    private static String endKey(SizedPiece piece, TreeEdge edge) {
        return piece.getToM() >= edge.getLength() - EPS
                ? "node:" + edge.getToNode()
                : "cut:" + edge.getId() + ":" + piece.getToM();
    }

    private static double length(SizedPiece piece) {
        return CostCalculator.round2(piece.getToM() - piece.getFromM());
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) {
            root[i] = root[root[i]];
            i = root[i];
        }
        return i;
    }
}
