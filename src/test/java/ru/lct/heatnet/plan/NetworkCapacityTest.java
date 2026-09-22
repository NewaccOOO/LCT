package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.rules.Rules;

/** Магистраль hn-1 → hn-2 → hn-3 от источника; у hn-1 запас до Ду 1400 — 22 501,9 − 60 = 22 441,9 т/ч. */
class NetworkCapacityTest {
    private final Rules rules = Rules.load();

    @Test
    void takesNearestToSourceWhileTrunkHasRoom() {
        PlanFixture fixture = PlanFixture.trunk()
                .oks("far", 500, 5, 12000)
                .oks("near", 100, 5, 12000)
                .oks("middle", 300, 5, 10000);

        // near, потом middle: у hn-1 остаётся 441,9 т/ч, дальнему far не хватает
        assertEquals(List.of("middle", "near"), selected(fixture, 12000, 12000, 10000));
    }

    @Test
    void takesAllWhenNetworkHasRoom() {
        PlanFixture fixture = PlanFixture.trunk().oks("a", 500, 5, 10).oks("b", 100, 5, 10);

        assertEquals(List.of("a", "b"), selected(fixture, 10, 10));
    }

    private List<String> selected(PlanFixture fixture, double... flows) {
        InputData input = fixture.input();
        return new NetworkCapacity(input, rules).select(input.getConnectionPoints(), flows).stream()
                .map(ConnectionPoint::getOksId).sorted().collect(Collectors.toList());
    }
}
