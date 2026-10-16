package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class NoRouteRegressionTest {
    private final Rules rules = Rules.load();

    @Test
    void finalPieceCrossesTramTracksInFrontOfBuilding() {
        List<Variant> variants = new VariantEnumerator(read("tram-polygon-21.geojson"), rules).run().getVariants();

        assertFalse(variants.isEmpty());
        for (Variant variant : variants) {
            assertEquals(List.of(), variant.getSummary().getUnconnectedOksIds(), "вариант " + variant.getId());
            List<NewSegment> special = variant.getSegments().stream()
                    .filter(segment -> "special".equals(segment.getLayingMethod())).collect(Collectors.toList());
            assertEquals(1, special.size(), "вариант " + variant.getId());
            assertEquals(2, special.get(0).getGeometry().getNumPoints(), "спецпроход не прямой: " + special.get(0).getId());
        }
    }

    @Test
    void wideGapInRingIsPassedAndClosedRingsStayUnconnected() {
        List<Variant> variants = new VariantEnumerator(read("rings.geojson"), rules).run().getVariants();

        assertFalse(variants.isEmpty());
        for (Variant variant : variants) {
            assertEquals(Set.of("20", "22"), Set.copyOf(variant.getSummary().getUnconnectedOksIds()), "вариант " + variant.getId());
            assertTrue(variant.getSegments().stream().anyMatch(segment -> "24".equals(segment.getEndNodeId())), "вариант " + variant.getId());
        }
    }

    private InputData read(String name) {
        return VariantEnumerator.read(Path.of("src/test/resources/expert", name), rules);
    }
}
