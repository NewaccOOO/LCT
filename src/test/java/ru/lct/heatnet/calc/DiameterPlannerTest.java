package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.rules.Rules;

class DiameterPlannerTest {
    @Test
    void constantFlowChainGetsOneDiameter() {
        // tie -e1 (100 м)-> c1 -e2 (200 м)-> o1, 2 т/ч: Ду 50 не проходит по длине (181 м), Ду 65 на всю цепочку
        // 300 м тоже (245 м). Разъяснение 20 от 29.09: Ду 80 на обоих рёбрах, а не Ду 65 внизу и Ду 80 вверху
        List<TreeEdge> edges = List.of(new TreeEdge("e1", "tie", "c1", 100), new TreeEdge("e2", "c1", "o1", 200));

        Map<String, Integer> dn = new DiameterPlanner(Rules.load()).plan(edges, "tie", Map.of("e1", 2.0, "e2", 2.0));

        assertEquals(Map.of("e1", 80, "e2", 80), dn);
    }
}
