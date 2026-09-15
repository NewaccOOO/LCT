package ru.lct.heatnet.io;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.proj4j.ProjectionException;
import ru.lct.heatnet.model.Chamber;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.Diagnostic;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.Source;
import ru.lct.heatnet.rules.Rules;

/**
 * Потоковое чтение входного GeoJSON (раздел 12 CONSTRAINTS.md): в памяти одновременно только одна фича
 * в виде дерева, геометрия сразу переводится в EPSG:32637. Ошибки данных возвращаются диагностиками.
 */
public class GeoJsonStreamReader {
    private static final String SOURCE = "source";
    private static final String HEAT_NETWORK = "heat_network";
    private static final String HEAT_CHAMBER = "heat_chamber";
    private static final String OKS_FUTURE = "oks_future";
    private static final String CONNECTION_POINT = "oks_connection_point";
    private static final String OKS_EXISTING = "oks_existing";
    private static final String RESTRICTION = "restriction";
    private static final String FILE_ID = "#0";
    private static final List<String> OBJECT_TYPES = List.of(
            SOURCE, HEAT_NETWORK, HEAT_CHAMBER, OKS_FUTURE, CONNECTION_POINT, OKS_EXISTING, RESTRICTION);

    private static final List<String> POINT = List.of("Point");
    private static final List<String> LINE = List.of("LineString");
    private static final List<String> POLYGONS = List.of("Polygon", "MultiPolygon");
    private static final List<String> LINES = List.of("LineString", "MultiLineString");
    private static final List<String> ANY_RESTRICTION = List.of("Polygon", "MultiPolygon", "LineString", "MultiLineString");
    private static final Map<String, List<String>> RESTRICTION_GEOMETRY = Map.of(
            "park", POLYGONS, "social_area", POLYGONS, "prohibited_site", POLYGONS, "water", POLYGONS,
            "road", POLYGONS, "tram_tracks", POLYGONS, "gas_pipeline", LINES, "power_cable", LINES);
    private static final List<String> UPSTREAM_TYPES = List.of(HEAT_NETWORK, HEAT_CHAMBER, SOURCE);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Упакованная XY-последовательность хранит точку в 16 байтах вместо объекта Coordinate в 40 байт.
    private static final GeometryFactory GEOMETRY = new GeometryFactory(PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
    private static final Rules RULES = Rules.load();

    public static InputData read(Path path) {
        Scan scan = new Scan();
        try (JsonParser parser = MAPPER.getFactory().createParser(path.toFile())) {
            scan.read(parser);
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            String where = at == null ? "" : ", строка " + at.getLineNr() + ", столбец " + at.getColumnNr();
            List<Diagnostic> broken = List.of(new Diagnostic(FILE_ID, "json", "файл не разбирается как JSON" + where));
            return new InputData(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), broken);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать входной файл " + path, e);
        }
        return scan.finish();
    }

    private static final class Ref {
        final String featureId;
        final String field;
        final String target;
        final List<String> allowedTypes;

        Ref(String featureId, String field, String target, List<String> allowedTypes) {
            this.featureId = featureId;
            this.field = field;
            this.target = target;
            this.allowedTypes = allowedTypes;
        }
    }

    private static final class Scan {
        final List<NetworkSegment> segments = new ArrayList<>();
        final List<Chamber> chambers = new ArrayList<>();
        final List<FutureOks> futureOks = new ArrayList<>();
        final List<ConnectionPoint> connectionPoints = new ArrayList<>();
        final List<ExistingOks> existingOks = new ArrayList<>();
        final List<Restriction> restrictions = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Map<String, String> typeById = new HashMap<>();
        final Map<String, String> upstreamById = new LinkedHashMap<>();
        final List<Ref> refs = new ArrayList<>();
        Source source;
        int sources;
        int ordinal;

        void read(JsonParser parser) throws IOException {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                add(FILE_ID, "type", "корень файла должен быть объектом FeatureCollection");
                return;
            }
            boolean hasFeatures = false;
            String type = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.getCurrentName();
                JsonToken value = parser.nextToken();
                if (name.equals("features") && value == JsonToken.START_ARRAY) {
                    hasFeatures = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        feature(MAPPER.readTree(parser));
                    }
                } else if (name.equals("type") && value == JsonToken.VALUE_STRING) {
                    type = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                add(FILE_ID, "json", "после FeatureCollection в файле есть лишние данные");
            }
            if (!"FeatureCollection".equals(type)) {
                add(FILE_ID, "type", "ожидается type = FeatureCollection");
            }
            if (!hasFeatures) {
                add(FILE_ID, "features", "нет массива features");
            }
        }

        void feature(JsonNode node) {
            ordinal++;
            String ordinalId = "#" + ordinal;
            if (node == null || !node.isObject()) {
                add(ordinalId, "feature", "элемент features должен быть объектом Feature");
                return;
            }
            JsonNode props = node.get("properties");
            if (props == null || !props.isObject()) {
                add(ordinalId, "properties", "нет объекта properties");
                return;
            }
            int before = diagnostics.size();
            String id = string(props, ordinalId, "id");
            String featureId = id == null ? ordinalId : id;
            if (!"Feature".equals(node.path("type").textValue())) {
                add(featureId, "type", "ожидается type = Feature");
            }
            String objectType = string(props, featureId, "object_type");
            if (objectType != null && OBJECT_TYPES.contains(objectType)) {
                // Своя строка типа у каждой фичи стоит десятки мегабайт на входе из миллионов объектов.
                objectType = objectType.intern();
            }
            if (id != null && typeById.putIfAbsent(id, objectType == null ? "" : objectType) != null) {
                add(featureId, "id", "id повторяется");
            }
            if (objectType == null) {
                return;
            }
            switch (objectType) {
                case SOURCE: {
                    sources++;
                    if (sources > 1) {
                        add(featureId, "object_type", "во входе больше одного источника source");
                    }
                    Geometry geometry = geometry(node, featureId, POINT);
                    if (diagnostics.size() == before && source == null) {
                        source = new Source(id, (Point) geometry);
                    }
                    break;
                }
                case HEAT_NETWORK: {
                    Integer diameter = diameter(props, featureId);
                    Double flow = flow(props, featureId);
                    String upstream = upstream(props, id, featureId);
                    Geometry geometry = geometry(node, featureId, LINE);
                    if (diagnostics.size() == before) {
                        segments.add(new NetworkSegment(id, (LineString) geometry, diameter, flow, upstream));
                    }
                    break;
                }
                case HEAT_CHAMBER: {
                    Integer diameter = diameter(props, featureId);
                    String upstream = upstream(props, id, featureId);
                    Geometry geometry = geometry(node, featureId, POINT);
                    if (diagnostics.size() == before) {
                        chambers.add(new Chamber(id, (Point) geometry, diameter, upstream));
                    }
                    break;
                }
                case OKS_FUTURE: {
                    Double flow = flow(props, featureId);
                    Double heatLoad = number(props, featureId, "heat_load");
                    Geometry geometry = geometry(node, featureId, POLYGONS);
                    if (diagnostics.size() == before) {
                        futureOks.add(new FutureOks(id, geometry, flow, heatLoad));
                    }
                    break;
                }
                case CONNECTION_POINT: {
                    String oksId = string(props, featureId, "oks_id");
                    if (oksId != null) {
                        refs.add(new Ref(featureId, "oks_id", oksId, List.of(OKS_FUTURE)));
                    }
                    Geometry geometry = geometry(node, featureId, POINT);
                    if (diagnostics.size() == before) {
                        connectionPoints.add(new ConnectionPoint(id, (Point) geometry, oksId));
                    }
                    break;
                }
                case OKS_EXISTING: {
                    Geometry geometry = geometry(node, featureId, POLYGONS);
                    if (diagnostics.size() == before) {
                        existingOks.add(new ExistingOks(id, geometry));
                    }
                    break;
                }
                case RESTRICTION: {
                    String restrictionType = string(props, featureId, "restriction_type");
                    List<String> allowed = ANY_RESTRICTION;
                    if (restrictionType != null) {
                        allowed = RESTRICTION_GEOMETRY.get(restrictionType);
                        if (allowed == null) {
                            add(featureId, "restriction_type", "неизвестный тип ограничения " + restrictionType);
                            allowed = ANY_RESTRICTION;
                        } else {
                            restrictionType = restrictionType.intern();
                        }
                    }
                    Geometry geometry = geometry(node, featureId, allowed);
                    if (diagnostics.size() == before) {
                        restrictions.add(new Restriction(id, geometry, restrictionType));
                    }
                    break;
                }
                default:
                    add(featureId, "object_type", "неизвестный тип объекта " + objectType);
            }
        }

        InputData finish() {
            if (sources == 0) {
                add(FILE_ID, "object_type", "во входе нет источника source");
            }
            for (Ref ref : refs) {
                String type = typeById.get(ref.target);
                if (type == null) {
                    add(ref.featureId, ref.field, "ссылается на несуществующий объект " + ref.target);
                } else if (!ref.allowedTypes.contains(type)) {
                    add(ref.featureId, ref.field, "ссылается на объект " + ref.target + " типа " + type
                            + ", ожидается " + String.join(" или ", ref.allowedTypes));
                }
            }
            checkUpstreamCycles();
            return new InputData(source, segments, chambers, futureOks, connectionPoints, existingOks, restrictions, diagnostics);
        }

        // Расчёт реконструкции идёт по upstream_object_id до source, на цикле он зациклится.
        void checkUpstreamCycles() {
            Set<String> checked = new HashSet<>();
            for (String start : upstreamById.keySet()) {
                Set<String> path = new HashSet<>();
                String current = start;
                while (upstreamById.containsKey(current) && !checked.contains(current) && path.add(current)) {
                    current = upstreamById.get(current);
                }
                if (path.contains(current)) {
                    add(current, "upstream_object_id", "цепочка upstream_object_id замкнута в цикл и не доходит до source");
                }
                checked.addAll(path);
            }
        }

        String upstream(JsonNode props, String id, String featureId) {
            String upstream = string(props, featureId, "upstream_object_id");
            if (upstream != null) {
                refs.add(new Ref(featureId, "upstream_object_id", upstream, UPSTREAM_TYPES));
                if (id != null) {
                    upstreamById.putIfAbsent(id, upstream);
                }
            }
            return upstream;
        }

        Integer diameter(JsonNode props, String featureId) {
            JsonNode value = required(props, featureId, "diameter");
            if (value == null) {
                return null;
            }
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                add(featureId, "diameter", "ожидается целое число, получено " + describe(value));
                return null;
            }
            int dn = value.intValue();
            if (RULES.diameters().stream().noneMatch(d -> d.getDn() == dn)) {
                add(featureId, "diameter", "диаметра " + dn + " нет в таблице диаметров");
                return null;
            }
            return dn;
        }

        Double flow(JsonNode props, String featureId) {
            Double flow = number(props, featureId, "flow_tph");
            if (flow != null && flow < 0) {
                add(featureId, "flow_tph", "расход не может быть отрицательным");
                return null;
            }
            return flow;
        }

        Double number(JsonNode props, String featureId, String field) {
            JsonNode value = required(props, featureId, field);
            if (value == null) {
                return null;
            }
            if (!value.isNumber()) {
                add(featureId, field, "ожидается число, получено " + describe(value));
                return null;
            }
            return value.doubleValue();
        }

        String string(JsonNode props, String featureId, String field) {
            JsonNode value = required(props, featureId, field);
            if (value == null) {
                return null;
            }
            if (!value.isTextual() || value.textValue().isEmpty()) {
                add(featureId, field, "ожидается непустая строка, получено " + describe(value));
                return null;
            }
            return value.textValue();
        }

        JsonNode required(JsonNode props, String featureId, String field) {
            JsonNode value = props.get(field);
            if (value == null || value.isNull()) {
                add(featureId, field, "нет обязательного атрибута");
                return null;
            }
            return value;
        }

        Geometry geometry(JsonNode feature, String featureId, List<String> allowed) {
            JsonNode raw = feature.get("geometry");
            if (raw == null || raw.isNull()) {
                add(featureId, "geometry", "нет геометрии");
                return null;
            }
            String type = raw.path("type").textValue();
            if (type == null || !allowed.contains(type)) {
                add(featureId, "geometry", "ожидается геометрия " + String.join(" или ", allowed) + ", получено " + type);
                return null;
            }
            try {
                return Projector.toUtm(parse(type, raw.path("coordinates")));
            } catch (IllegalArgumentException e) {
                add(featureId, "geometry", "некорректные координаты " + type + ": " + e.getMessage());
            } catch (ProjectionException e) {
                add(featureId, "geometry", "координаты не переводятся в EPSG:32637");
            }
            return null;
        }

        void add(String featureId, String field, String problem) {
            diagnostics.add(new Diagnostic(featureId, field, problem));
        }
    }

    private static String describe(JsonNode value) {
        return value.isValueNode() ? value.toString() : value.getNodeType().name().toLowerCase();
    }

    private static Geometry parse(String type, JsonNode coordinates) {
        switch (type) {
            case "Point":
                return GEOMETRY.createPoint(position(coordinates));
            case "LineString":
                return GEOMETRY.createLineString(positions(coordinates, 2));
            case "Polygon":
                return polygon(coordinates);
            case "MultiLineString": {
                LineString[] lines = new LineString[parts(coordinates).size()];
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = GEOMETRY.createLineString(positions(coordinates.get(i), 2));
                }
                return GEOMETRY.createMultiLineString(lines);
            }
            case "MultiPolygon": {
                Polygon[] polygons = new Polygon[parts(coordinates).size()];
                for (int i = 0; i < polygons.length; i++) {
                    polygons[i] = polygon(coordinates.get(i));
                }
                return GEOMETRY.createMultiPolygon(polygons);
            }
            default:
                throw new IllegalStateException("Разбор геометрии " + type + " не поддержан");
        }
    }

    private static Polygon polygon(JsonNode node) {
        LinearRing[] rings = new LinearRing[parts(node).size()];
        for (int i = 0; i < rings.length; i++) {
            Coordinate[] ring = positions(node.get(i), 4);
            if (!ring[0].equals2D(ring[ring.length - 1])) {
                throw new IllegalArgumentException("кольцо полигона не замкнуто");
            }
            rings[i] = GEOMETRY.createLinearRing(ring);
        }
        return GEOMETRY.createPolygon(rings[0], Arrays.copyOfRange(rings, 1, rings.length));
    }

    private static JsonNode parts(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("ожидается непустой массив");
        }
        return node;
    }

    private static Coordinate[] positions(JsonNode node, int min) {
        if (!node.isArray() || node.size() < min) {
            throw new IllegalArgumentException("ожидается массив не меньше чем из " + min + " позиций");
        }
        Coordinate[] result = new Coordinate[node.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = position(node.get(i));
        }
        return result;
    }

    private static Coordinate position(JsonNode node) {
        if (!node.isArray() || node.size() < 2 || !node.get(0).isNumber() || !node.get(1).isNumber()) {
            throw new IllegalArgumentException("позиция должна быть массивом [долгота, широта]");
        }
        double lon = node.get(0).doubleValue();
        double lat = node.get(1).doubleValue();
        if (!(Math.abs(lon) <= 180 && Math.abs(lat) <= 90)) {
            throw new IllegalArgumentException("позиция [" + lon + ", " + lat + "] вне диапазона долготы и широты");
        }
        return new CoordinateXY(lon, lat);
    }
}
