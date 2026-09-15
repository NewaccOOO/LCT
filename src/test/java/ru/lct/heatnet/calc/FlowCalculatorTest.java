package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowCalculatorTest {
    private static final double EPS = 1e-9;

    @Test
    void edgeFlowIsSumOfOksBelowInBranchedTree() {
        // tie -e1-> c1; c1 -e2-> o1; c1 -e3-> c2; c2 -e4-> o2; c2 -e5-> o3
        List<TreeEdge> edges = List.of(
                new TreeEdge("e1", "tie", "c1", 10),
                new TreeEdge("e2", "c1", "o1", 20),
                new TreeEdge("e3", "c1", "c2", 30),
                new TreeEdge("e4", "c2", "o2", 40),
                new TreeEdge("e5", "c2", "o3", 50));
        Map<String, Double> flows = FlowCalculator.flows(edges, "tie", Map.of("o1", 1.2, "o2", 2.3, "o3", 0.5));

        assertEquals(List.of("e1", "e2", "e3", "e4", "e5"), List.copyOf(flows.keySet()));
        assertEquals(1.2 + 2.3 + 0.5, flows.get("e1"), EPS); // 4.0
        assertEquals(1.2, flows.get("e2"), EPS);
        assertEquals(2.3 + 0.5, flows.get("e3"), EPS); // 2.8
        assertEquals(2.3, flows.get("e4"), EPS);
        assertEquals(0.5, flows.get("e5"), EPS);
    }

    @Test
    void notATreeThrows() {
        List<TreeEdge> twoParents = List.of(
                new TreeEdge("e1", "tie", "a", 10),
                new TreeEdge("e2", "tie", "b", 10),
                new TreeEdge("e3", "a", "o", 10),
                new TreeEdge("e4", "b", "o", 10));
        assertThrows(IllegalArgumentException.class, () -> FlowCalculator.flows(twoParents, "tie", Map.of("o", 1.0)));

        List<TreeEdge> detachedCycle = List.of(
                new TreeEdge("e1", "tie", "o", 10),
                new TreeEdge("e2", "x", "y", 10),
                new TreeEdge("e3", "y", "x", 10));
        assertThrows(IllegalArgumentException.class,
                () -> FlowCalculator.flows(detachedCycle, "tie", Map.of("o", 1.0)));

        List<TreeEdge> intoRoot = List.of(new TreeEdge("e1", "o", "tie", 10));
        assertThrows(IllegalArgumentException.class, () -> FlowCalculator.flows(intoRoot, "tie", Map.of()));
    }
}
