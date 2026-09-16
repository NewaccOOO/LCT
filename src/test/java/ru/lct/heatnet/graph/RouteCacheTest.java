package ru.lct.heatnet.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteCacheTest {
    private static final long MB = 1024 * 1024;

    @Test
    void evictsLeastRecentlyUsedWhenBytesExceedBudget() {
        RouteCache cache = new RouteCache(1);
        cache.put(List.of("a"), new double[0], MB / 2);
        cache.put(List.of("b"), new double[0], MB / 3);
        // «a» запрошена последней, поэтому при переполнении уходит «b»
        assertNotNull(cache.get(List.of("a")));
        cache.put(List.of("c"), new double[0], MB / 3);

        assertNotNull(cache.get(List.of("a")));
        assertNull(cache.get(List.of("b")));
        assertNotNull(cache.get(List.of("c")));
    }

    @Test
    void keepsEntryLargerThanBudgetAndComputesMissingOnce() {
        RouteCache cache = new RouteCache(1);
        int[] computed = {0};
        for (int i = 0; i < 3; i++) {
            cache.computeIfAbsent(List.of("big"), 2 * MB, () -> computed[0]++);
        }

        assertEquals(1, computed[0]);
        assertNotNull(cache.get(List.of("big")));
    }
}
