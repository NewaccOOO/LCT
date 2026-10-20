package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.rules.Rules;

class DiameterPlannerTest {
    @Test
    void constantFlowChainGetsOneDiameter() {
        List<TreeEdge> edges = List.of(new TreeEdge("e1", "tie", "c1", 100), new TreeEdge("e2", "c1", "o1", 200));

        Map<String, Integer> dn = new DiameterPlanner(Rules.load()).plan(edges, "tie", Map.of("e1", 2.0, "e2", 2.0));

        assertEquals(Map.of("e1", 80, "e2", 80), dn);
    }
}
