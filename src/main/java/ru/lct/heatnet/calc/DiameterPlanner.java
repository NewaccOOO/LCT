package ru.lct.heatnet.calc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.lct.heatnet.rules.Diameter;
import ru.lct.heatnet.rules.Rules;

public final class DiameterPlanner {
    private static final double EPS = 1e-9;
    private static final double FLOW_EPS = 1e-3;

    private final Rules rules;

    public DiameterPlanner(Rules rules) {
        this.rules = rules;
    }

    public Map<String, Integer> plan(List<TreeEdge> edges, String rootNode, Map<String, Double> flowByEdge) {
        List<TreeEdge> order = FlowCalculator.orderFromRoot(edges, rootNode);
        Map<String, List<TreeEdge>> childrenByNode = new HashMap<>();
        for (TreeEdge edge : edges) {
            childrenByNode.computeIfAbsent(edge.getFromNode(), node -> new java.util.ArrayList<>()).add(edge);
        }
        Map<String, Integer> floorByEdge = new HashMap<>();
        while (true) {
            Map<String, Integer> dnByEdge = plan(order, childrenByNode, flowByEdge, floorByEdge);
            boolean raised = false;
            for (TreeEdge edge : order) {
                List<TreeEdge> children = childrenByNode.getOrDefault(edge.getToNode(), List.of());
                if (children.size() != 1) {
                    continue;
                }
                TreeEdge child = children.get(0);
                int dn = dnByEdge.get(edge.getId());
                if (Math.abs(flowByEdge.get(child.getId()) - flowByEdge.get(edge.getId())) <= FLOW_EPS
                        && dnByEdge.get(child.getId()) < dn) {
                    floorByEdge.put(child.getId(), dn);
                    raised = true;
                }
            }
            if (!raised) {
                return dnByEdge;
            }
        }
    }

    private Map<String, Integer> plan(List<TreeEdge> order, Map<String, List<TreeEdge>> childrenByNode,
            Map<String, Double> flowByEdge, Map<String, Integer> floorByEdge) {
        Map<String, Integer> dnByEdge = new HashMap<>();
        Map<String, Double> runByEdge = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            TreeEdge edge = order.get(i);
            Double flow = flowByEdge.get(edge.getId());
            if (flow == null) {
                throw new IllegalArgumentException("Нет расхода для ребра " + edge.getId());
            }
            Diameter dn = rules.diameterFor(flow);
            Integer floor = floorByEdge.get(edge.getId());
            if (floor != null && floor > dn.getDn()) {
                dn = rules.diameter(floor);
            }
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
