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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.ChamberReconstruction;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Reconstruction;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.TieIn;
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
            "tie_in", HEAD + "existing_object_id existing_object_type existing_diameter required_diameter cost",
            "heat_network_reconstruction", HEAD + "existing_object_id existing_flow_tph added_flow_tph calculated_flow_tph "
                    + "existing_diameter required_diameter length cost",
            "heat_chamber", HEAD + "diameter cost",
            "heat_chamber_reconstruction", HEAD + "existing_object_id existing_diameter required_diameter cost",
            "technical_node", HEAD.trim(),
            "variant_summary", HEAD + "rank construction_cost chamber_construction_cost tie_in_cost reconstruction_cost "
                    + "chamber_reconstruction_cost unconnected_penalty calculated_cost new_network_length reconstruction_length "
                    + "length score unconnected_oks_ids");

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
        assertDecimal("0.01", summary.get("properties").get("length"));
        assertEquals("O7", summary.get("properties").get("unconnected_oks_ids").get(0).textValue());
        assertDecimal("0.500", byType.get("heat_network_reconstruction").get("properties").get("added_flow_tph"));
        assertEquals("Point", byType.get("tie_in").get("geometry").get("type").textValue());
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
        assertEquals(Stream.concat(Stream.generate(() -> "2").limit(7), Stream.generate(() -> "1").limit(7))
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
                List.of(new TieIn("t1", variantId, tie, "N1", "heat_network", 150, 200, 5_000_000)),
                List.of(new Reconstruction("r1", variantId, line, "N1", 60.0004, 0.4996, 60.5, 150, 200, 88.8, 16_141_000.8)),
                List.of(new NewChamber("c1", variantId, tie, 200, 3_000_000)),
                List.of(new ChamberReconstruction("cr1", variantId, tie, "K1", 150, 200, 3_000_000)),
                List.of(new TechnicalNode("n1", variantId, tie)),
                new VariantSummary("summary-" + variantId, variantId, rank, 1, 2, 3, 4, 5, 6, 21, 0.004, 0.005, 0.009,
                        1.2345, List.of("O7")));
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
