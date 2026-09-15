package ru.lct.heatnet.calc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlowCalculator {
    private FlowCalculator() {
    }

    /** Расход ребра: сумма расходов ОКС в узлах ниже по дереву. Порядок ключей как у edges. */
    public static Map<String, Double> flows(List<TreeEdge> edges, String rootNode, Map<String, Double> oksFlowByNode) {
        List<TreeEdge> order = orderFromRoot(edges, rootNode);
        Set<String> nodes = new HashSet<>();
        for (TreeEdge edge : edges) {
            nodes.add(edge.getToNode());
        }
        for (String node : oksFlowByNode.keySet()) {
            if (!nodes.contains(node)) {
                throw new IllegalArgumentException("Узел ОКС " + node + " не является концом ни одного ребра дерева");
            }
        }
        Map<String, Double> flowBelowNode = new HashMap<>();
        Map<String, Double> flowByEdgeId = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            TreeEdge edge = order.get(i);
            double flow = oksFlowByNode.getOrDefault(edge.getToNode(), 0.0)
                    + flowBelowNode.getOrDefault(edge.getToNode(), 0.0);
            flowByEdgeId.put(edge.getId(), flow);
            flowBelowNode.merge(edge.getFromNode(), flow, Double::sum);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (TreeEdge edge : edges) {
            result.put(edge.getId(), flowByEdgeId.get(edge.getId()));
        }
        return result;
    }

    /** Проверяет, что рёбра образуют дерево с корнем rootNode, и возвращает их так, что родитель идёт раньше детей. */
    static List<TreeEdge> orderFromRoot(List<TreeEdge> edges, String rootNode) {
        Map<String, List<TreeEdge>> childrenByNode = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> nodesWithParent = new HashSet<>();
        for (TreeEdge edge : edges) {
            if (!edgeIds.add(edge.getId())) {
                throw new IllegalArgumentException("Ребро " + edge.getId() + " встречается дважды");
            }
            if (edge.getToNode().equals(rootNode) || !nodesWithParent.add(edge.getToNode())) {
                throw new IllegalArgumentException("Рёбра не образуют дерево: в узел " + edge.getToNode()
                        + " входит больше одного ребра или ребро входит в корень");
            }
            childrenByNode.computeIfAbsent(edge.getFromNode(), node -> new ArrayList<>()).add(edge);
        }
        List<TreeEdge> order = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of(rootNode));
        for (int i = 0; i < queue.size(); i++) {
            for (TreeEdge child : childrenByNode.getOrDefault(queue.get(i), List.of())) {
                order.add(child);
                queue.add(child.getToNode());
            }
        }
        if (order.size() != edges.size()) {
            throw new IllegalArgumentException("Рёбра не образуют дерево: " + (edges.size() - order.size())
                    + " рёбер недостижимы от узла " + rootNode);
        }
        return order;
    }
}
