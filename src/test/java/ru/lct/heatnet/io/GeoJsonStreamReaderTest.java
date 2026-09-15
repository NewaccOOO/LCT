package ru.lct.heatnet.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.model.Diagnostic;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;

class GeoJsonStreamReaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SAMPLE = Path.of("data/samples/small-1.geojson");
    private static final long LARGE_FILE_BYTES = 50L * 1024 * 1024;

    @TempDir
    Path dir;

    @Test
    void readsSampleWithoutDiagnostics() throws IOException {
        Path file = Files.exists(SAMPLE) ? SAMPLE : write(validFeatures());
        InputData data = GeoJsonStreamReader.read(file);

        assertEquals(List.of(), data.getDiagnostics());
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode feature : MAPPER.readTree(file.toFile()).get("features")) {
            counts.merge(feature.get("properties").get("object_type").textValue(), 1, Integer::sum);
        }
        assertEquals(1, counts.get("source"));
        assertNotNull(data.getSource());
        assertEquals(counts.getOrDefault("heat_network", 0), data.getSegments().size());
        assertEquals(counts.getOrDefault("heat_chamber", 0), data.getChambers().size());
        assertEquals(counts.getOrDefault("oks_future", 0), data.getFutureOks().size());
        assertEquals(counts.getOrDefault("oks_connection_point", 0), data.getConnectionPoints().size());
        assertEquals(counts.getOrDefault("oks_existing", 0), data.getExistingOks().size());
        assertEquals(counts.getOrDefault("restriction", 0), data.getRestrictions().size());
    }

    @Test
    void mapsAttributesAndProjectsToUtm() throws IOException {
        InputData data = GeoJsonStreamReader.read(write(validFeatures()));

        NetworkSegment segment = data.getSegments().get(0);
        assertEquals("N1", segment.getId());
        assertEquals(200, segment.getDiameter());
        assertEquals(50.5, segment.getFlowTph());
        assertEquals("S", segment.getUpstreamId());
        assertEquals(2, segment.getGeometry().getNumPoints());
        assertEquals(2, segment.getGeometry().getCoordinateSequence().getDimension(), "координаты хранятся как XY");
        assertTrue(segment.getGeometry().getLength() > 600 && segment.getGeometry().getLength() < 650,
                "0,01 градуса долготы на широте Москвы около 627 м: " + segment.getGeometry().getLength());
        assertEquals("O1", data.getConnectionPoints().get(0).getOksId());
        assertEquals(3.2, data.getFutureOks().get(0).getHeatLoad());
        assertEquals("MultiPolygon", data.getExistingOks().get(0).getGeometry().getGeometryType());
        assertEquals(List.of("park", "gas_pipeline"),
                data.getRestrictions().stream().map(Restriction::getType).collect(Collectors.toList()));
    }

    @Test
    void missingRequiredAttribute() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "N1").remove("flow_tph");

        assertOnly(features, "N1", "flow_tph");
    }

    @Test
    void fractionalDiameter() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "N1").put("diameter", 12.5);

        assertOnly(features, "N1", "diameter");
    }

    @Test
    void stringDiameter() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "K1").put("diameter", "12");

        assertOnly(features, "K1", "diameter");
    }

    @Test
    void duplicateId() throws IOException {
        ArrayNode features = validFeatures();
        features.add(features.get(7).deepCopy());

        assertOnly(features, "R1", "id");
    }

    @Test
    void danglingOksId() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "C1").put("oks_id", "missing");
        assertOnly(features, "C1", "oks_id");

        props(features, "C1").put("oks_id", "E1");
        assertOnly(features, "C1", "oks_id");
    }

    @Test
    void danglingUpstreamObjectId() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "N2").put("upstream_object_id", "missing");
        assertOnly(features, "N2", "upstream_object_id");

        props(features, "N2").put("upstream_object_id", "O1");
        assertOnly(features, "N2", "upstream_object_id");
    }

    @Test
    void upstreamCycleIsReportedOnce() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "N1").put("upstream_object_id", "K1");

        assertOnly(features, "N1", "upstream_object_id");
    }

    @Test
    void featureWithoutIdGetsOrdinal() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "R1").remove("id");

        assertOnly(features, "#8", "id");
    }

    @Test
    void geometryAndTypeProblems() throws IOException {
        ArrayNode features = validFeatures();
        ((ObjectNode) features.get(7)).set("geometry", geometry("LineString", new double[][] {{37.6, 55.7}, {37.7, 55.8}}));
        assertOnly(features, "R1", "geometry");

        features = validFeatures();
        props(features, "R2").put("restriction_type", "fence");
        assertOnly(features, "R2", "restriction_type");

        features = validFeatures();
        props(features, "E1").put("object_type", "tree");
        assertOnly(features, "E1", "object_type");

        features = validFeatures();
        ((ObjectNode) features.get(1)).remove("geometry");
        assertOnly(features, "N1", "geometry");

        features = validFeatures();
        ((ObjectNode) features.get(5)).set("geometry", geometry("Point", new double[] {37.6}));
        assertOnly(features, "C1", "geometry");

        features = validFeatures();
        ((ObjectNode) features.get(4)).set("geometry", geometry("Polygon", new double[][][] {{{37.6, 55.7}, {37.61, 55.7}, {37.61, 55.71}, {37.6, 55.71}}}));
        assertOnly(features, "O1", "geometry");

        features = validFeatures();
        features.add(features.get(0).deepCopy());
        ((ObjectNode) features.get(9).get("properties")).put("id", "S3");
        assertOnly(features, "S3", "object_type");

        features = validFeatures();
        features.remove(0);
        List<String> problems = GeoJsonStreamReader.read(write(features)).getDiagnostics().stream()
                .map(d -> d.getFeatureId() + " " + d.getField())
                .collect(Collectors.toList());
        assertEquals(List.of("#0 object_type", "N1 upstream_object_id"), problems);
    }

    @Test
    void featuresBeforeTypeAndUnknownKeysAreSkipped() throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", "порядок ключей");
        root.set("features", validFeatures());
        root.putObject("crs").putObject("properties").putArray("nested").add(MAPPER.createObjectNode().put("features", 1));
        root.put("type", "FeatureCollection");
        Path file = dir.resolve("order.geojson");
        MAPPER.writeValue(file.toFile(), root);

        InputData data = GeoJsonStreamReader.read(file);

        assertEquals(List.of(), data.getDiagnostics());
        assertEquals(2, data.getSegments().size());
        assertEquals(2, data.getRestrictions().size());
    }

    @Test
    void brokenJsonGivesSingleDiagnostic() throws IOException {
        Path file = dir.resolve("broken.geojson");
        String text = MAPPER.writeValueAsString(collection(validFeatures()));
        Files.writeString(file, text.substring(0, text.length() / 2), StandardCharsets.UTF_8);

        InputData data = GeoJsonStreamReader.read(file);

        assertEquals(1, data.getDiagnostics().size(), data.getDiagnostics().toString());
        assertEquals("#0", data.getDiagnostics().get(0).getFeatureId());
        assertNull(data.getSource());
        assertTrue(data.getSegments().isEmpty());
    }

    @Test
    void streamsLargeFileWithSmallHeap() throws IOException {
        Path file = dir.resolve("large.geojson");
        int parks = 0;
        try (JsonGenerator json = MAPPER.getFactory().createGenerator(file.toFile(), JsonEncoding.UTF8)) {
            json.writeStartObject();
            json.writeStringField("type", "FeatureCollection");
            json.writeArrayFieldStart("features");
            json.writeTree(validFeatures().get(0));
            while (parks % 1000 != 0 || Files.size(file) < LARGE_FILE_BYTES) {
                double lon = 36.0 + (parks % 400) * 0.01;
                double lat = 54.5 + (parks / 400 % 200) * 0.01;
                json.writeStartObject();
                json.writeStringField("type", "Feature");
                json.writeObjectFieldStart("properties");
                json.writeStringField("id", "park-" + parks);
                json.writeStringField("object_type", "restriction");
                json.writeStringField("restriction_type", "park");
                json.writeEndObject();
                json.writeObjectFieldStart("geometry");
                json.writeStringField("type", "Polygon");
                json.writeArrayFieldStart("coordinates");
                json.writeStartArray();
                double[][] ring = {{lon, lat}, {lon + 0.004123456, lat}, {lon + 0.004123456, lat + 0.003123456}, {lon, lat + 0.003123456}, {lon, lat}};
                for (double[] position : ring) {
                    json.writeArray(position, 0, 2);
                }
                json.writeEndArray();
                json.writeEndArray();
                json.writeEndObject();
                json.writeEndObject();
                parks++;
                if (parks % 1000 == 0) {
                    json.flush();
                }
            }
            json.writeEndArray();
            json.writeEndObject();
        }
        assertTrue(Files.size(file) >= LARGE_FILE_BYTES);
        assertTrue(Runtime.getRuntime().maxMemory() <= 300L * 1024 * 1024, "тест должен идти с -Xmx256m");

        InputData data = GeoJsonStreamReader.read(file);

        assertEquals(List.of(), data.getDiagnostics());
        assertEquals(parks, data.getRestrictions().size());
    }

    private void assertOnly(ArrayNode features, String featureId, String field) throws IOException {
        List<Diagnostic> diagnostics = GeoJsonStreamReader.read(write(features)).getDiagnostics();
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(featureId, diagnostics.get(0).getFeatureId(), diagnostics.toString());
        assertEquals(field, diagnostics.get(0).getField(), diagnostics.toString());
        assertFalse(diagnostics.get(0).getProblem().isBlank());
    }

    private Path write(ArrayNode features) throws IOException {
        Path file = Files.createTempFile(dir, "input", ".geojson");
        MAPPER.writeValue(file.toFile(), collection(features));
        return file;
    }

    private static ObjectNode collection(ArrayNode features) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "FeatureCollection");
        root.set("features", features);
        return root;
    }

    private static ObjectNode props(ArrayNode features, String id) {
        for (JsonNode feature : features) {
            if (id.equals(feature.get("properties").path("id").textValue())) {
                return (ObjectNode) feature.get("properties");
            }
        }
        throw new IllegalArgumentException(id);
    }

    // Тесты обращаются к фичам по позиции: R1 восьмая, её порядковый номер #8.
    private static ArrayNode validFeatures() {
        ArrayNode features = MAPPER.createArrayNode();
        features.add(feature("Point", new double[] {37.60, 55.75}, "id", "S", "object_type", "source"));
        features.add(feature("LineString", new double[][] {{37.60, 55.75}, {37.61, 55.75}},
                "id", "N1", "object_type", "heat_network", "diameter", 200, "flow_tph", 50.5, "upstream_object_id", "S"));
        features.add(feature("Point", new double[] {37.61, 55.75},
                "id", "K1", "object_type", "heat_chamber", "diameter", 200, "upstream_object_id", "N1"));
        features.add(feature("LineString", new double[][] {{37.61, 55.76}, {37.61, 55.75}},
                "id", "N2", "object_type", "heat_network", "diameter", 150, "flow_tph", 20, "upstream_object_id", "K1"));
        features.add(feature("Polygon", new double[][][] {{{37.62, 55.76}, {37.63, 55.76}, {37.63, 55.77}, {37.62, 55.76}}},
                "id", "O1", "object_type", "oks_future", "flow_tph", 5.5, "heat_load", 3.2, "name", "лишний атрибут"));
        features.add(feature("Point", new double[] {37.62, 55.76}, "id", "C1", "object_type", "oks_connection_point", "oks_id", "O1"));
        features.add(feature("MultiPolygon", new double[][][][] {{{{37.64, 55.76}, {37.65, 55.76}, {37.65, 55.77}, {37.64, 55.76}}}},
                "id", "E1", "object_type", "oks_existing"));
        features.add(feature("Polygon", new double[][][] {{{37.66, 55.76}, {37.67, 55.76}, {37.67, 55.77}, {37.66, 55.76}}},
                "id", "R1", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("LineString", new double[][] {{37.60, 55.74}, {37.70, 55.74}},
                "id", "R2", "object_type", "restriction", "restriction_type", "gas_pipeline"));
        return features;
    }

    private static ObjectNode feature(String geometryType, Object coordinates, Object... props) {
        ObjectNode feature = MAPPER.createObjectNode();
        feature.put("type", "Feature");
        ObjectNode properties = feature.putObject("properties");
        for (int i = 0; i < props.length; i += 2) {
            properties.set((String) props[i], MAPPER.valueToTree(props[i + 1]));
        }
        feature.set("geometry", geometry(geometryType, coordinates));
        return feature;
    }

    private static ObjectNode geometry(String type, Object coordinates) {
        ObjectNode geometry = MAPPER.createObjectNode();
        geometry.put("type", type);
        geometry.set("coordinates", MAPPER.valueToTree(coordinates));
        return geometry;
    }
}
