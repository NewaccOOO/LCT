package ru.lct.heatnet.calc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

/**
 * Условный диаметр каждого ребра дерева по разделу 2.3 технического приложения от 18.09.2026. Ребро идёт между узлами
 * смены расхода, поэтому ДУ на нём один. От листьев к корню: ДУ ребра — наименьший, который проходит по расходу, не
 * меньше ДУ дочерних рёбер и укладывает в предельную длину самый длинный путь одного ДУ, проходящий через ребро.
 * Камера без смены ДУ отсчёт не начинает, смена ДУ — начинает.
 */
public final class DiameterPlanner {
    private static final double EPS = 1e-9;

    private final Rules rules;

    public DiameterPlanner(Rules rules) {
        this.rules = rules;
    }

    /** ДУ по id ребра; бросает {@link IllegalStateException}, если даже наибольший ДУ не укладывает путь в предел. */
    public Map<String, Integer> plan(List<TreeEdge> edges, String rootNode, Map<String, Double> flowByEdge) {
        List<TreeEdge> order = FlowCalculator.orderFromRoot(edges, rootNode);
        Map<String, Integer> dnByEdge = new HashMap<>();
        // длина цепочки одного ДУ от самого дальнего листа до верхнего узла ребра, с учётом округления длин до 0,01 м
        Map<String, Double> runByEdge = new HashMap<>();
        Map<String, List<TreeEdge>> childrenByNode = new HashMap<>();
        for (TreeEdge edge : edges) {
            childrenByNode.computeIfAbsent(edge.getFromNode(), node -> new java.util.ArrayList<>()).add(edge);
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            TreeEdge edge = order.get(i);
            Double flow = flowByEdge.get(edge.getId());
            if (flow == null) {
                throw new IllegalArgumentException("Нет расхода для ребра " + edge.getId());
            }
            Diameter dn = rules.diameterFor(flow);
            List<TreeEdge> children = childrenByNode.getOrDefault(edge.getToNode(), List.of());
            for (TreeEdge child : children) {
                if (dnByEdge.get(child.getId()) > dn.getDn()) {
                    dn = rules.diameter(dnByEdge.get(child.getId()));
                }
            }
            double length = CostCalculator.round2(edge.getLength());
            while (true) {
                double run = length;
                for (TreeEdge child : children) {
                    if (dnByEdge.get(child.getId()) == dn.getDn()) {
                        run = Math.max(run, length + runByEdge.get(child.getId()));
                    }
                }
                if (run <= dn.getMaxLengthM() + EPS) {
                    dnByEdge.put(edge.getId(), dn.getDn());
                    runByEdge.put(edge.getId(), run);
                    break;
                }
                Diameter next = rules.nextDiameter(dn.getDn());
                if (next == null) {
                    throw new IllegalStateException(String.format("Ребро %s: путь DN%d длиной %.2f м длиннее предела "
                            + "наибольшего диаметра", edge.getId(), dn.getDn(), run));
                }
                dn = next;
            }
        }
        return dnByEdge;
    }
}
