package ru.lct.heatnet.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;

class GeoJsonStreamWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final String HEAD = "id object_type variant_id ";
    private static final Map<String, String> KEYS = Map.of(
            "heat_network", HEAD + "start_node_id end_node_id flow_tph diameter length laying_method depth_start depth_end cost",
            "heat_chamber", HEAD + "diameter cost",
            "technical_node", HEAD.trim(),
            "variant_summary", HEAD + "rank construction_cost chamber_construction_cost existing_chamber_tie_in_count "
                    + "existing_chamber_tie_in_cost unconnected_penalty calculated_cost new_network_length score unconnected_oks_ids");

    @TempDir
    Path dir;

    @Test
    void writesEveryObjectTypeWithItsOwnKeysAndRounding() throws IOException {
        Path out = dir.resolve("result.geojson");
        GeoJsonStreamWriter.write(new Result(List.of(variant("1", 1))), out);

        JsonNode root = MAPPER.readTree(out.toFile());
        assertEquals("FeatureCollection", root.get("type").textValue());
        Map<String, JsonNode> byType = new HashMap<>();
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            String type = props.get("object_type").textValue();
            assertNull(byType.put(type, feature),"по одному объекту каждого типа: " + type);
            assertEquals(KEYS.get(type), String.join(" ", names(props)), type);
            names(props).stream()
                    .filter(name -> props.get(name).isNull())
                    .forEach(name -> assertTrue(name.equals("depth_start") || name.equals("depth_end"), type + "." + name));
        }
        assertEquals(KEYS.keySet(), byType.keySet());

        JsonNode segment = byType.get("heat_network");
        assertTrue(segment.get("properties").get("depth_start").isNull());
        assertTrue(segment.get("properties").get("depth_end").isNull());
        assertEquals("LineString", segment.get("geometry").get("type").textValue());
        assertDecimal("123.46", segment.get("properties").get("length"));
        assertDecimal("14860000.01", segment.get("properties").get("cost"));
        assertDecimal("12.346", segment.get("properties").get("flow_tph"));
        assertTrue(segment.get("properties").get("diameter").isInt());
        JsonNode start = segment.get("geometry").get("coordinates").get(0);
        assertEquals(9, start.get(0).decimalValue().scale());
        assertEquals(37.62, start.get(0).doubleValue(), 1e-9);
        assertEquals(55.75, start.get(1).doubleValue(), 1e-9);

        JsonNode summary = byType.get("variant_summary");
        assertTrue(summary.get("geometry").isNull());
        assertDecimal("1.235", summary.get("properties").get("score"));
        assertDecimal("0.01", summary.get("properties").get("new_network_length"));
        assertTrue(summary.get("properties").get("existing_chamber_tie_in_count").isInt());
        assertEquals("O7", summary.get("properties").get("unconnected_oks_ids").get(0).textValue());
        assertEquals("Point", byType.get("heat_chamber").get("geometry").get("type").textValue());
    }

    @Test
    void numericInputIdsStayNumbersInReferences() throws IOException {
        Path out = dir.resolve("result.geojson");
        Variant variant = variant("1", 1);
        NewSegment segment = variant.getSegments().get(0);
        Variant numeric = new Variant("1", List.of(new NewSegment(segment.getId(), "1", segment.getGeometry(), "42", "c1",
                1, 100, 10, "base", null, null, 1)), variant.getChambers(), variant.getNodes(),
                new VariantSummary("summary_1", "1", 1, 1, 2, 1, 5, 6, 21, 0.005, 1.2345, List.of("7", "O7")));
        GeoJsonStreamWriter.write(new Result(List.of(numeric), Set.of("42", "7")), out);

        Map<String, JsonNode> byType = new HashMap<>();
        for (JsonNode feature : MAPPER.readTree(out.toFile()).get("features")) {
            byType.put(feature.get("properties").get("object_type").textValue(), feature.get("properties"));
        }
        assertEquals(42, byType.get("heat_network").get("start_node_id").intValue(), "числовой id узла остаётся числом");
        assertEquals("c1", byType.get("heat_network").get("end_node_id").textValue());
        JsonNode unconnected = byType.get("variant_summary").get("unconnected_oks_ids");
        assertEquals(7, unconnected.get(0).intValue());
        assertEquals("O7", unconnected.get(1).textValue());
    }

    @Test
    void writesVariantsInOrderAndReplacesFileAtomically() throws IOException {
        Path out = dir.resolve("result.geojson");
        Files.writeString(out, "old");

        GeoJsonStreamWriter.write(new Result(List.of(variant("2", 1), variant("1", 2))), out);

        List<String> variantIds = new ArrayList<>();
        for (JsonNode feature : MAPPER.readTree(out.toFile()).get("features")) {
            variantIds.add(feature.get("properties").get("variant_id").textValue());
        }
        assertEquals(Stream.concat(Stream.generate(() -> "2").limit(4), Stream.generate(() -> "1").limit(4))
                .collect(Collectors.toList()), variantIds);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(out), files.collect(Collectors.toList()), "временный файл не остался");
        }
    }

    private static Variant variant(String variantId, int rank) {
        Point tie = (Point) Projector.toUtm(GEOMETRY.createPoint(new Coordinate(37.62, 55.75)));
        LineString line = (LineString) Projector.toUtm(GEOMETRY.createLineString(
                new Coordinate[] {new Coordinate(37.62, 55.75), new Coordinate(37.621, 55.751)}));
        return new Variant(variantId,
                List.of(new NewSegment("s1", variantId, line, "t1", "c1", 12.3456, 100, 123.455, "base", null, null, 14_860_000.005)),
                List.of(new NewChamber("c1", variantId, tie, 200, 3_000_000)),
                List.of(new TechnicalNode("n1", variantId, tie)),
                new VariantSummary("summary-" + variantId, variantId, rank, 1, 2, 1, 5, 6, 21, 0.005, 1.2345, List.of("O7")));
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        BigDecimal value = actual.decimalValue();
        assertEquals(new BigDecimal(expected), value, "значение и число знаков");
    }
}
