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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKBWriter;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.Diagnostic;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.Rules;

class GeoJsonStreamReaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Rules RULES = Rules.load();
    private static final Path SAMPLE = Path.of("data/samples/small-1.geojson");
    private static final long LARGE_FILE_BYTES = 50L * 1024 * 1024;
    private static final WKBWriter WKB = new WKBWriter();

    @TempDir
    Path dir;

    @Test
    void readsSampleWithoutDiagnostics() throws IOException {
        Path file = Files.exists(SAMPLE) ? SAMPLE : write(validFeatures());
        InputData data = GeoJsonStreamReader.read(file);

        assertEquals(List.of(), data.getDiagnostics());
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode feature : MAPPER.readTree(file.toFile()).get("features")) {
            JsonNode properties = feature.get("properties");
            String type = properties.get("object_type").textValue();
            if (type.equals("restriction") && "oks".equals(properties.path("restriction_type").textValue())) {
                type = "building";
            } else if (type.equals("oks_connection_point") && !properties.has("oks_id")) {
                counts.merge("consumer", 1, Integer::sum);
            }
            counts.merge(type, 1, Integer::sum);
        }
        assertEquals(1, counts.get("source"));
        assertNotNull(data.getSource());
        assertEquals(counts.getOrDefault("heat_network", 0), data.getSegments().size());
        assertEquals(counts.getOrDefault("heat_chamber", 0), data.getChambers().size());
        assertEquals(counts.getOrDefault("oks_future", 0) + counts.getOrDefault("consumer", 0), data.getFutureOks().size());
        assertEquals(counts.getOrDefault("oks_connection_point", 0), data.getConnectionPoints().size());
        assertTrue(data.getExistingOks().size() >= counts.getOrDefault("oks_existing", 0));
        assertTrue(data.getExistingOks().size() <= counts.getOrDefault("oks_existing", 0) + counts.getOrDefault("building", 0));
        assertEquals(counts.getOrDefault("restriction", 0), data.getRestrictions().size());
    }

    @Test
    void skipsFarObstaclesButStillChecksThem() throws IOException {
        ArrayNode features = validFeatures();
        features.add(feature("Polygon", new double[][][] {{{38.50, 55.76}, {38.51, 55.76}, {38.51, 55.77}, {38.50, 55.76}}},
                "id", "far", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("Polygon", new double[][][] {{{38.52, 55.76}, {38.53, 55.76}, {38.53, 55.77}, {38.52, 55.76}}},
                "id", "v1_far", "object_type", "oks_existing"));
        Path file = write(features);
        List<InputData> partials = new ArrayList<>();

        InputData data = GeoJsonStreamReader.read(file, partial -> {
            partials.add(partial);
            Envelope extent = partial.getConnectionPoints().get(0).getGeometry().getEnvelopeInternal();
            extent.expandBy(10_000);
            return extent;
        });

        assertEquals(List.of(), data.getDiagnostics());
        assertEquals(List.of(), partials.get(0).getRestrictions());
        assertEquals(List.of(), partials.get(0).getExistingOks());
        assertEquals(List.of("E1", "v1_far"), ids(data.getExistingOks(), ExistingOks::getId));
        assertEquals(List.of("R1", "R2"), ids(data.getRestrictions(), Restriction::getId));
        assertEquals(List.of("R1", "R2", "far"), ids(GeoJsonStreamReader.read(file).getRestrictions(), Restriction::getId));

        features.add(feature("Polygon", new double[][][] {{{38.60, 55.76}, {38.61, 55.77}, {38.61, 55.76}, {38.60, 55.77}, {38.60, 55.76}}},
                "id", "far-bowtie", "object_type", "restriction", "restriction_type", "park"));
        InputData broken = GeoJsonStreamReader.read(write(features), partial -> new Envelope());
        assertEquals(List.of("far-bowtie"), ids(broken.getDiagnostics(), Diagnostic::getFeatureId));
    }

    private static <T> List<String> ids(List<T> items, Function<T, String> id) {
        return items.stream().map(id).collect(Collectors.toList());
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
        props(features, "N1").remove("diameter");

        assertOnly(features, "N1", "diameter");
    }

    @Test
    void readsOrganizerDatasetFormat() throws IOException {
        InputData data = GeoJsonStreamReader.read(write(datasetFeatures()));

        assertEquals(List.of(), data.getDiagnostics());
        Map<String, NetworkSegment> segments = data.getSegments().stream()
                .collect(Collectors.toMap(NetworkSegment::getId, segment -> segment));
        assertEquals("1", segments.get("10").getUpstreamId(), "первый участок идёт от источника");
        assertEquals("20", segments.get("11").getUpstreamId(), "за камерой следующий к источнику объект — камера");
        assertEquals("11", segments.get("12").getUpstreamId(), "стык без камеры — предыдущий участок");
        assertEquals(0.0, segments.get("10").getFlowTph(), "текущий расход сети в расчёте не участвует");
        Chamber chamber = data.getChambers().get(0);
        assertEquals("10", chamber.getUpstreamId());
        assertEquals(300, chamber.getDiameter(), "наибольший Ду примыкающих участков");
        assertEquals(List.of("30", "31", "32"),
                data.getFutureOks().stream().map(FutureOks::getId).collect(Collectors.toList()));
        assertEquals(24.87, data.getFutureOks().get(0).getFlowTph());
        assertEquals(data.getFutureOks().get(1).getGeometry(), data.getFutureOks().get(2).getGeometry(),
                "два ввода одного здания — два ОКС с общей геометрией");
        assertEquals("30", data.getConnectionPoints().get(0).getOksId());
        assertEquals(List.of("40", "41", "42"), data.getExistingOks().stream().map(ExistingOks::getId).collect(Collectors.toList()),
                "все полигоны ОКС — препятствия, и с точками подключения тоже");
        assertEquals(Set.of("30", "31", "32", "20"), data.getNumericIds());
        assertEquals(List.of("railway"), data.getRestrictions().stream().map(Restriction::getType).collect(Collectors.toList()));
    }

    @Test
    void datasetSegmentOffNetwork() throws IOException {
        ArrayNode features = datasetFeatures();
        features.add(feature("LineString", new double[][] {{37.70, 55.70}, {37.71, 55.70}},
                "id", 13, "object_type", "heat_network", "diameter", 300));

        assertOnly(features, "13", "upstream_object_id");
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
        // приложение 18.09, п. 1.1: ограничение любого типа может прийти линией
        ArrayNode features = validFeatures();
        ((ObjectNode) features.get(7)).set("geometry", geometry("LineString", new double[][] {{37.6, 55.7}, {37.7, 55.8}}));
        assertTrue(GeoJsonStreamReader.read(write(features)).getDiagnostics().isEmpty());

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
    void heatLoadIsOptional() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "O1").remove("heat_load");
        assertTrue(GeoJsonStreamReader.read(write(features)).getDiagnostics().isEmpty());
    }

    @Test
    void selfIntersectingPolygonIsReported() throws IOException {
        ArrayNode features = validFeatures();
        ((ObjectNode) features.get(4)).set("geometry", geometry("Polygon", new double[][][] {
            {{37.6, 55.7}, {37.61, 55.71}, {37.61, 55.7}, {37.6, 55.71}, {37.6, 55.7}}}));
        assertOnly(features, "O1", "geometry");
        String problem = GeoJsonStreamReader.read(write(features)).getDiagnostics().get(0).getProblem();
        assertTrue(problem.startsWith("невалидная геометрия: самопересечение"), problem);
    }

    @Test
    void unknownRestrictionTypeGivesOneWarningPerType() throws IOException {
        ArrayNode features = validFeatures();
        props(features, "R2").put("restriction_type", "fence");
        features.add(feature("Point", new double[] {37.65, 55.74},
                "id", "R3", "object_type", "restriction", "restriction_type", "fence"));
        features.add(feature("Point", new double[] {37.66, 55.74},
                "id", "R4", "object_type", "restriction", "restriction_type", "power_line_support"));

        InputData data = GeoJsonStreamReader.read(write(features));

        assertEquals(List.of(), data.getDiagnostics());
        assertEquals(4, data.getRestrictions().size());
        assertEquals(List.of("ПРЕДУПРЕЖДЕНИЕ: restriction_type \"fence\" нет в справочнике, объектов: 2, "
                + "применено правило запрета с отступом 1,0 м"), data.getWarnings());
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

    @Test
    void decimalMatchesParseDouble() {
        java.util.Random random = new java.util.Random(7);
        int fast = 0;
        for (int n = 0; n < 200_000; n++) {
            StringBuilder text = new StringBuilder(random.nextBoolean() ? "-" : "");
            text.append(random.nextInt(4) == 0 ? "0" : String.valueOf(1 + random.nextInt(999)));
            text.append('.');
            int fraction = 1 + random.nextInt(random.nextBoolean() ? 9 : 18);
            for (int i = 0; i < fraction; i++) {
                text.append(random.nextInt(3) == 0 ? '0' : (char) ('0' + random.nextInt(10)));
            }
            if (random.nextInt(4) == 0) {
                text.append(random.nextBoolean() ? 'e' : 'E').append(random.nextBoolean() ? "-" : "+").append(random.nextInt(40));
            }
            String number = text.toString();
            double value = GeoJsonStreamReader.decimal(number.getBytes(StandardCharsets.US_ASCII), 0, number.length());
            if (!Double.isNaN(value)) {
                fast++;
                assertEquals(Double.doubleToRawLongBits(Double.parseDouble(number)), Double.doubleToRawLongBits(value), number);
            }
        }
        assertTrue(fast > 100_000, "быстрый разбор берёт обычные координаты: " + fast);
    }

    // Быстрый разбор берёт только корректный JSON и строит те же узлы, что readTree; остальное отдаёт Jackson.
    @Test
    void fastMatchesReadTreeOrGivesUp() throws IOException {
        String geometry = "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}";
        List<String> fast = List.of(
                "{\"type\":\"Feature\"," + geometry + ",\"properties\":{\"id\":7,\"big\":12345678901,\"max\":2147483647,"
                        + "\"over\":2147483648,\"min\":-2147483648,\"under\":-2147483649,\"long\":123456789012345678,"
                        + "\"zero\":-0,\"flow\":2.50,\"e\":1e3,\"E\":-1.5E-2,\"s\":\"x\",\"empty\":\"\",\"t\":true,"
                        + "\"f\":false,\"n\":null,\"id\":8}}",
                " { \"properties\" : { } ,\n\t" + geometry + " , \"type\" : 1 , \"extra\" : null } ",
                "{" + geometry + ",\"properties\":\"abc\"}");
        List<String> slow = List.of(
                "{" + geometry + ",\"properties\":{\"s\":\"ТЭЦ\"}}",
                "{" + geometry + ",\"properties\":{\"s\":\"a\\u0041\"}}",
                "{" + geometry + ",\"properties\":{\"n\":1234567890123456789}}",
                "{" + geometry + ",\"properties\":{\"n\":[1]}}",
                "{" + geometry + ",\"properties\":{\"n\":{}}}",
                "{" + geometry + "," + geometry + "}",
                "{" + geometry + ",\"properties\":{\"n\":01}}",
                "{" + geometry + ",\"properties\":{\"n\":1.}}",
                "{" + geometry + ",\"properties\":{\"n\":.5}}",
                "{" + geometry + ",\"properties\":{\"n\":+1}}",
                "{" + geometry + ",\"properties\":{\"n\":tru}}",
                "{" + geometry + ",\"properties\":{\"n\":nulll}}",
                "{" + geometry + ",\"properties\":{\"n\":1,}}",
                "{" + geometry + ",\"properties\":{\"n\" 1}}",
                "{" + geometry + ",\"properties\":{\"n\":\"a\tb\"}}",
                "{" + geometry + ",\"properties\":{\"n\":1}} x",
                "{\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7,]},\"properties\":{}}");
        for (String json : fast) {
            JsonNode expected = MAPPER.readTree(json);
            ((ObjectNode) expected).remove("geometry");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            assertEquals(expected, GeoJsonStreamReader.fastFeature(bytes), json);
        }
        for (String json : slow) {
            assertNull(GeoJsonStreamReader.fastFeature(json.getBytes(StandardCharsets.UTF_8)), json);
        }
    }

    // Чтение срезами и потоком (UTF-8) и чтение деревьями на главном потоке (UTF-16, без байтовых смещений)
    // дают один и тот же вход и те же диагностики, в том числе там, где поток отдаёт фичу дереву.
    @Test
    void streamedReadMatchesTreeRead() throws IOException {
        ArrayNode features = validFeatures();
        features.add(feature("Point", new int[] {37, 55}, "id", "I1", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("LineString", new double[][] {{37.6, 55.7, 120.5}, {3.77e1, 5.58e1}},
                "id", "Z1", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("MultiLineString", new double[][][] {{{37.6, 55.7}, {37.7, 55.8}}, {{37.61, 55.71}, {37.71, 55.81}}},
                "id", "M1", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("MultiPolygon", new double[][][][] {
            {{{37.60, 55.60}, {37.70, 55.60}, {37.70, 55.70}, {37.60, 55.60}}},
            {{{37.80, 55.60}, {37.90, 55.60}, {37.90, 55.70}, {37.80, 55.70}, {37.80, 55.60}},
                {{37.82, 55.62}, {37.84, 55.62}, {37.84, 55.64}, {37.82, 55.62}}}},
                "id", "M2", "object_type", "oks_existing"));
        ObjectNode reordered = feature("Point", new double[] {37.65, 55.75}, "id", "T1", "object_type", "restriction",
                "restriction_type", "park");
        reordered.set("geometry", MAPPER.createObjectNode().put("foo", 1)
                .<ObjectNode>set("coordinates", MAPPER.valueToTree(new double[] {37.65, 55.75})).put("type", "Point"));
        features.add(reordered);
        ((ObjectNode) features.get(features.size() - 1).get("geometry")).putArray("bbox").add(1);
        ObjectNode future = feature("Polygon", new double[0], "id", "O2", "object_type", "oks_future", "flow_tph", 1.5);
        future.set("geometry", MAPPER.createObjectNode().<ObjectNode>set("coordinates", MAPPER.valueToTree(
                new double[][][] {{{37.62, 55.76}, {37.63, 55.76}, {37.63, 55.77}, {37.62, 55.76}}})).put("type", "Polygon"));
        features.add(future);
        features.add(feature("Point", new double[] {190, 55}, "id", "B1", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("Point", new double[] {37.6}, "id", "B2", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("Polygon", new double[][][] {{{37.6, 55.7}, {37.61, 55.7}, {37.61, 55.71}, {37.6, 55.71}}},
                "id", "B3", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("Polygon", new double[][][] {{{37.6, 55.7}, {37.61, 55.71}, {37.61, 55.7}, {37.6, 55.71}, {37.6, 55.7}}},
                "id", "B4", "object_type", "restriction", "restriction_type", "park"));
        features.add(feature("LineString", new double[][] {{37.6, 55.7}}, "id", "B5", "object_type", "restriction", "restriction_type", "park"));
        ObjectNode strings = feature("Point", new double[] {37.6, 55.7}, "id", "B6", "object_type", "restriction", "restriction_type", "park");
        ((ArrayNode) strings.get("geometry").get("coordinates")).insert(0, "x");
        features.add(strings);
        features.add(feature("GeometryCollection", new double[0], "id", "B7", "object_type", "restriction", "restriction_type", "park"));
        ObjectNode bare = features.get(7).deepCopy();
        bare.remove("geometry");
        ((ObjectNode) bare.get("properties")).put("id", "B8");
        features.add(bare);
        String json = MAPPER.writeValueAsString(collection(features));

        assertSameRead(json, 8);
        assertSameRead("{\"type\":\"FeatureCollection\",\"features\":[1," + json.substring(json.indexOf('[') + 1), 9);
        assertSameRead("{\"features\":[]}", 2);
        features.add(features.get(1).deepCopy());
        assertSameRead(MAPPER.writeValueAsString(collection(features)), 9);
        ObjectNode second = features.get(0).deepCopy();
        ((ObjectNode) second.get("properties")).put("id", "S2");
        features.add(second);
        assertSameRead(MAPPER.writeValueAsString(collection(features)), 10);
    }

    private void assertSameRead(String json, int diagnostics) throws IOException {
        Path streamed = dir.resolve("utf8.geojson");
        Path tree = dir.resolve("utf16.geojson");
        Path bom = dir.resolve("bom.geojson");
        Files.writeString(streamed, json, StandardCharsets.UTF_8);
        Files.writeString(tree, json, StandardCharsets.UTF_16LE);
        Files.writeString(bom, "﻿" + json, StandardCharsets.UTF_8);
        InputData expected = GeoJsonStreamReader.read(tree);
        assertEquals(diagnostics, expected.getDiagnostics().size(), expected.getDiagnostics().toString());
        InputData expectedTwoPass = GeoJsonStreamReader.read(tree, GeoJsonStreamReaderTest::nearConnections);
        assertEquals(expected.getDiagnostics(), expectedTwoPass.getDiagnostics(), "второй проход проверяет всё, как один");
        for (Path file : List.of(streamed, bom)) {
            assertSameInput(expected, GeoJsonStreamReader.read(file));
            assertSameInput(expectedTwoPass, GeoJsonStreamReader.read(file, GeoJsonStreamReaderTest::nearConnections));
        }
    }

    private static void assertSameInput(InputData expected, InputData actual) {
        assertEquals(expected.getDiagnostics(), actual.getDiagnostics());
        assertEquals(expected.getWarnings(), actual.getWarnings());
        assertEquals(expected.getNumericIds(), actual.getNumericIds());
        assertEquals(describe(expected), describe(actual));
    }

    private static Envelope nearConnections(InputData partial) {
        Envelope extent = new Envelope();
        partial.getConnectionPoints().forEach(c -> extent.expandToInclude(c.getGeometry().getCoordinate()));
        extent.expandBy(2000);
        return extent.isNull() ? null : extent;
    }

    private static List<String> describe(InputData data) {
        List<String> lines = new ArrayList<>();
        data.getSegments().forEach(s -> lines.add(s.getId() + " " + s.getUpstreamId() + " " + WKBWriter.toHex(WKB.write(s.getGeometry()))));
        data.getChambers().forEach(c -> lines.add(c.getId() + " " + c.getDiameter() + " " + WKBWriter.toHex(WKB.write(c.getGeometry()))));
        data.getFutureOks().forEach(o -> lines.add(o.getId() + " " + o.getFlowTph() + " " + WKBWriter.toHex(WKB.write(o.getGeometry()))));
        data.getConnectionPoints().forEach(c -> lines.add(c.getId() + " " + c.getOksId() + " " + WKBWriter.toHex(WKB.write(c.getGeometry()))));
        data.getExistingOks().forEach(e -> lines.add(e.getId() + " " + WKBWriter.toHex(WKB.write(e.getGeometry()))));
        data.getRestrictions().forEach(r -> lines.add(r.getId() + " " + r.getType() + " " + WKBWriter.toHex(WKB.write(r.getGeometry()))
                + (r.getGeometry() instanceof LineString ? " " + ((LineString) r.getGeometry()).getCoordinateSequence().getDimension() : "")));
        return lines;
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

    // Формат датасета организаторов: числовые id, расход на точке подключения, здания ограничением oks,
    // у сети нет flow_tph и upstream_object_id, у камеры нет diameter.
    private static ArrayNode datasetFeatures() {
        ArrayNode features = MAPPER.createArrayNode();
        features.add(feature("Point", new double[] {37.60, 55.75}, "id", 1, "object_type", "source", "name", "ТЭЦ"));
        features.add(feature("LineString", new double[][] {{37.61, 55.75}, {37.60, 55.75}},
                "id", 10, "object_type", "heat_network", "diameter", 300));
        features.add(feature("Point", new double[] {37.61, 55.75}, "id", 20, "object_type", "heat_chamber"));
        features.add(feature("LineString", new double[][] {{37.61, 55.75}, {37.62, 55.75}},
                "id", 11, "object_type", "heat_network", "diameter", 200));
        features.add(feature("LineString", new double[][] {{37.62, 55.75}, {37.62, 55.76}},
                "id", 12, "object_type", "heat_network", "diameter", 150));
        features.add(feature("Point", new double[] {37.635, 55.765}, "id", 30, "object_type", "oks_connection_point", "flow_tph", 24.87));
        features.add(feature("Point", new double[] {37.655, 55.765}, "id", 31, "object_type", "oks_connection_point", "flow_tph", 10));
        features.add(feature("Point", new double[] {37.656, 55.766}, "id", 32, "object_type", "oks_connection_point", "flow_tph", 12));
        features.add(feature("MultiPolygon", new double[][][][] {{{{37.63, 55.76}, {37.64, 55.76}, {37.64, 55.77}, {37.63, 55.77}, {37.63, 55.76}}}},
                "id", 40, "object_type", "restriction", "restriction_type", "oks"));
        features.add(feature("MultiPolygon", new double[][][][] {{{{37.65, 55.76}, {37.66, 55.76}, {37.66, 55.77}, {37.65, 55.77}, {37.65, 55.76}}}},
                "id", 41, "object_type", "restriction", "restriction_type", "oks"));
        features.add(feature("MultiPolygon", new double[][][][] {{{{37.67, 55.76}, {37.68, 55.76}, {37.68, 55.77}, {37.67, 55.77}, {37.67, 55.76}}}},
                "id", 42, "object_type", "restriction", "restriction_type", "oks", "address", "ул. Примерная, 1"));
        features.add(feature("MultiPolygon", new double[][][][] {{{{37.60, 55.73}, {37.70, 55.73}, {37.70, 55.735}, {37.60, 55.735}, {37.60, 55.73}}}},
                "id", 50, "object_type", "restriction", "restriction_type", "railway"));
        return features;
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
