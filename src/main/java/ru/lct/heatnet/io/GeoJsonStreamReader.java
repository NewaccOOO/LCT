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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
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
import ru.lct.heatnet.rules.RestrictionRule;
import ru.lct.heatnet.rules.Rules;

/**
 * Потоковое чтение входного GeoJSON (раздел 12 CONSTRAINTS.md и формат датасета организаторов,
 * docs/interpretation.md): в памяти одновременно только одна фича в виде дерева, геометрия сразу переводится
 * в EPSG:32637. Чего нет во входе датасета (направление сети, текущий расход, диаметр камеры, перспективные ОКС),
 * выводится после чтения. Ошибки данных возвращаются диагностиками.
 */
public class GeoJsonStreamReader {
    private static final String SOURCE = "source";
    private static final String HEAT_NETWORK = "heat_network";
    private static final String HEAT_CHAMBER = "heat_chamber";
    private static final String OKS_FUTURE = "oks_future";
    private static final String CONNECTION_POINT = "oks_connection_point";
    private static final String OKS_EXISTING = "oks_existing";
    private static final String RESTRICTION = "restriction";
    // здание в датасете организаторов: перспективный ОКС, если в полигоне лежит точка подключения, иначе существующий
    private static final String BUILDING = "oks";
    // конец участка совпадает с источником, камерой или концом другого участка (A-9)
    private static final double JOINT_M = 0.5;
    private static final String FILE_ID = "#0";
    private static final List<String> OBJECT_TYPES = List.of(
            SOURCE, HEAT_NETWORK, HEAT_CHAMBER, OKS_FUTURE, CONNECTION_POINT, OKS_EXISTING, RESTRICTION);

    private static final List<String> POINT = List.of("Point");
    private static final List<String> LINE = List.of("LineString");
    private static final List<String> POLYGONS = List.of("Polygon", "MultiPolygon");
    private static final List<String> LINES = List.of("LineString", "MultiLineString");
    private static final List<String> ANY_RESTRICTION = List.of(
            "Point", "LineString", "MultiLineString", "Polygon", "MultiPolygon");
    // Геометрия по типу задана только для типов из таблицы ТЗ; у новых и неизвестных типов её во входе не угадать.
    private static final Map<String, List<String>> RESTRICTION_GEOMETRY = Map.of(
            "park", POLYGONS, "social_area", POLYGONS, "prohibited_site", POLYGONS, "water", POLYGONS,
            "road", POLYGONS, "tram_tracks", POLYGONS, "gas_pipeline", LINES, "power_cable", LINES);
    private static final List<String> UPSTREAM_TYPES = List.of(HEAT_NETWORK, HEAT_CHAMBER, SOURCE);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Упакованная XY-последовательность хранит точку в 16 байтах вместо объекта Coordinate в 40 байт.
    private static final GeometryFactory GEOMETRY = new GeometryFactory(PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
    private static final Rules RULES = Rules.load();
    private static final Map<Integer, String> INVALID_REASONS = Map.ofEntries(
            Map.entry(TopologyValidationError.REPEATED_POINT, "повторяющаяся точка"),
            Map.entry(TopologyValidationError.HOLE_OUTSIDE_SHELL, "дырка вне внешнего контура"),
            Map.entry(TopologyValidationError.NESTED_HOLES, "дырка внутри дырки"),
            Map.entry(TopologyValidationError.DISCONNECTED_INTERIOR, "внутренность полигона не связна"),
            Map.entry(TopologyValidationError.SELF_INTERSECTION, "самопересечение"),
            Map.entry(TopologyValidationError.RING_SELF_INTERSECTION, "самопересечение контура"),
            Map.entry(TopologyValidationError.NESTED_SHELLS, "контур внутри другого контура"),
            Map.entry(TopologyValidationError.DUPLICATE_RINGS, "повторяющиеся контуры"),
            Map.entry(TopologyValidationError.TOO_FEW_POINTS, "слишком мало точек"),
            Map.entry(TopologyValidationError.INVALID_COORDINATE, "недопустимая координата"),
            Map.entry(TopologyValidationError.RING_NOT_CLOSED, "контур не замкнут"));
    private static final Locale RUSSIAN = new Locale("ru");

    public static InputData read(Path path) {
        return scan(path, new Scan(false, null));
    }

    /**
     * Чтение в два прохода без дальних препятствий. Первый проход берёт всё, кроме зданий и ограничений, и по нему
     * {@code obstacleExtent} считает прямоугольник, вне которого препятствия на расчёт не влияют; null — оставить
     * все. Второй проход проверяет каждый объект как обычно, но кладёт в память только здания и ограничения,
     * пересекающие прямоугольник. На городе, где ОКС в одном районе, куча не растёт с числом зданий.
     */
    public static InputData read(Path path, Function<InputData, Envelope> obstacleExtent) {
        InputData partial = scan(path, new Scan(true, null));
        Envelope extent = partial.getDiagnostics().isEmpty() ? obstacleExtent.apply(partial) : null;
        return scan(path, new Scan(false, extent));
    }

    private static InputData scan(Path path, Scan scan) {
        try (JsonParser parser = MAPPER.getFactory().createParser(path.toFile())) {
            scan.read(parser);
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            String where = at == null ? "" : ", строка " + at.getLineNr() + ", столбец " + at.getColumnNr();
            List<Diagnostic> broken = List.of(new Diagnostic(FILE_ID, "json", "файл не разбирается как JSON" + where));
            return new InputData(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), broken, List.of());
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

    private static final class RawSegment {
        final String id;
        final LineString geometry;
        final int diameter;
        final Double flow;
        String upstream;

        RawSegment(String id, LineString geometry, int diameter, Double flow, String upstream) {
            this.id = id;
            this.geometry = geometry;
            this.diameter = diameter;
            this.flow = flow;
            this.upstream = upstream;
        }
    }

    private static final class RawChamber {
        final String id;
        final Point geometry;
        final Integer diameter;
        String upstream;

        RawChamber(String id, Point geometry, Integer diameter, String upstream) {
            this.id = id;
            this.geometry = geometry;
            this.diameter = diameter;
            this.upstream = upstream;
        }
    }

    /** Точка подключения датасета: без oks_id, со своим расходом. */
    private static final class Consumer {
        final String id;
        final Point geometry;
        final double flow;

        Consumer(String id, Point geometry, double flow) {
            this.id = id;
            this.geometry = geometry;
            this.flow = flow;
        }
    }

    private static final class Scan {
        final List<RawSegment> rawSegments = new ArrayList<>();
        final List<RawChamber> rawChambers = new ArrayList<>();
        final List<Consumer> consumers = new ArrayList<>();
        final List<ExistingOks> buildings = new ArrayList<>();
        final List<FutureOks> futureOks = new ArrayList<>();
        final List<ConnectionPoint> connectionPoints = new ArrayList<>();
        final List<ExistingOks> existingOks = new ArrayList<>();
        final List<Restriction> restrictions = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final Map<String, String> typeById = new HashMap<>();
        final Map<String, String> upstreamById = new LinkedHashMap<>();
        final Map<String, Integer> unknownRestrictionTypes = new LinkedHashMap<>();
        final List<Ref> refs = new ArrayList<>();
        final boolean skipObstacles;
        final Envelope extent;
        Source source;
        int sources;
        int ordinal;

        Scan(boolean skipObstacles, Envelope extent) {
            this.skipObstacles = skipObstacles;
            this.extent = extent;
        }

        /**
         * Препятствие нужно расчёту: пересекает прямоугольник или extent не задан. ID на «v» остаются всегда: по ним
         * сборщик выбирает префикс выходных ID, см. NetworkAssembler.
         */
        boolean keep(String id, Geometry geometry) {
            return extent == null || id.startsWith("v") || geometry.getEnvelopeInternal().intersects(extent);
        }

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
            String id = identifier(props, ordinalId, "id");
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
            if (objectType == null || skipObstacles && (objectType.equals(OKS_EXISTING) || objectType.equals(RESTRICTION))) {
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
                    Double flow = present(props, "flow_tph") ? flow(props, featureId) : null;
                    String upstream = present(props, "upstream_object_id") ? upstream(props, id, featureId) : null;
                    Geometry geometry = geometry(node, featureId, LINE);
                    if (diagnostics.size() == before) {
                        rawSegments.add(new RawSegment(id, (LineString) geometry, diameter, flow, upstream));
                    }
                    break;
                }
                case HEAT_CHAMBER: {
                    Integer diameter = present(props, "diameter") ? diameter(props, featureId) : null;
                    String upstream = present(props, "upstream_object_id") ? upstream(props, id, featureId) : null;
                    Geometry geometry = geometry(node, featureId, POINT);
                    if (diagnostics.size() == before) {
                        rawChambers.add(new RawChamber(id, (Point) geometry, diameter, upstream));
                    }
                    break;
                }
                case OKS_FUTURE: {
                    Double flow = flow(props, featureId);
                    // справочный атрибут (CONSTRAINTS §12), в расчёте не участвует
                    Double heatLoad = present(props, "heat_load") ? number(props, featureId, "heat_load") : null;
                    Geometry geometry = geometry(node, featureId, POLYGONS);
                    if (diagnostics.size() == before) {
                        futureOks.add(new FutureOks(id, geometry, flow, heatLoad));
                    }
                    break;
                }
                case CONNECTION_POINT: {
                    if (!present(props, "oks_id") && present(props, "flow_tph")) {
                        Double flow = flow(props, featureId);
                        Geometry geometry = geometry(node, featureId, POINT);
                        if (diagnostics.size() == before) {
                            consumers.add(new Consumer(id, (Point) geometry, flow));
                        }
                        break;
                    }
                    String oksId = identifier(props, featureId, "oks_id");
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
                    if (diagnostics.size() == before && keep(id, geometry)) {
                        existingOks.add(new ExistingOks(id, geometry));
                    }
                    break;
                }
                case RESTRICTION: {
                    String restrictionType = string(props, featureId, "restriction_type");
                    if (BUILDING.equals(restrictionType)) {
                        Geometry geometry = geometry(node, featureId, POLYGONS);
                        if (diagnostics.size() == before && keep(id, geometry)) {
                            buildings.add(new ExistingOks(id, geometry));
                        }
                        break;
                    }
                    List<String> allowed = ANY_RESTRICTION;
                    if (restrictionType != null) {
                        allowed = RESTRICTION_GEOMETRY.getOrDefault(restrictionType, ANY_RESTRICTION);
                        if (RULES.isKnown(restrictionType)) {
                            restrictionType = restrictionType.intern();
                        } else {
                            unknownRestrictionTypes.merge(restrictionType, 1, Integer::sum);
                        }
                    }
                    Geometry geometry = geometry(node, featureId, allowed);
                    if (diagnostics.size() == before && keep(id, geometry)) {
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
            List<NetworkSegment> segments = new ArrayList<>();
            List<Chamber> chambers = new ArrayList<>();
            network(segments, chambers);
            consumers();
            List<String> warnings = new ArrayList<>();
            unknownRestrictionTypes.forEach((type, count) -> warnings.add(unknownTypeWarning(type, count)));
            return new InputData(source, segments, chambers, futureOks, connectionPoints, existingOks, restrictions,
                    diagnostics, warnings);
        }

        /** Направление сети обходом от источника, диаметры камер и текущий расход там, где их нет во входе. */
        void network(List<NetworkSegment> segments, List<Chamber> chambers) {
            if (source != null && (rawSegments.stream().anyMatch(r -> r.upstream == null)
                    || rawChambers.stream().anyMatch(r -> r.upstream == null))) {
                traverse();
            }
            for (RawSegment raw : rawSegments) {
                if (raw.upstream == null) {
                    add(raw.id, "upstream_object_id", "нет upstream_object_id, и обход сети от источника по стыкам до участка не дошёл");
                    continue;
                }
                double flow = raw.flow != null ? raw.flow : RULES.defaultExistingFlow(raw.diameter);
                segments.add(new NetworkSegment(raw.id, raw.geometry, raw.diameter, flow, raw.upstream));
            }
            STRtree ends = endIndex();
            for (RawChamber raw : rawChambers) {
                Integer diameter = raw.diameter;
                if (diameter == null) {
                    for (int[] end : near(ends, raw.geometry.getCoordinate())) {
                        diameter = Math.max(diameter == null ? 0 : diameter, rawSegments.get(end[0]).diameter);
                    }
                }
                if (diameter == null) {
                    add(raw.id, "diameter", "нет diameter, и к камере не примыкает ни один участок сети");
                } else if (raw.upstream == null) {
                    add(raw.id, "upstream_object_id", "нет upstream_object_id, и обход сети от источника до камеры не дошёл");
                } else {
                    chambers.add(new Chamber(raw.id, raw.geometry, diameter, raw.upstream));
                }
            }
        }

        // Обход в ширину от источника: участок получает следующим к источнику объектом камеру в точке стыка
        // или участок, от дальнего конца которого до него дошли; камера — участок, который первым дошёл до неё.
        void traverse() {
            STRtree ends = endIndex();
            STRtree chamberIndex = new STRtree();
            for (RawChamber chamber : rawChambers) {
                chamberIndex.insert(chamber.geometry.getEnvelopeInternal(), chamber);
            }
            boolean[] reached = new boolean[rawSegments.size()];
            List<int[]> queue = new ArrayList<>();
            for (int[] end : near(ends, source.getGeometry().getCoordinate())) {
                reach(end, source.getId(), reached, queue);
            }
            for (int head = 0; head < queue.size(); head++) {
                int[] from = queue.get(head);
                RawSegment segment = rawSegments.get(from[0]);
                Coordinate far = endpoint(segment.geometry, 1 - from[1]);
                RawChamber chamber = chamberAt(chamberIndex, far);
                if (chamber != null && chamber.upstream == null) {
                    chamber.upstream = segment.id;
                }
                String upstream = chamber != null ? chamber.id : segment.id;
                for (int[] end : near(ends, far)) {
                    reach(end, upstream, reached, queue);
                }
            }
        }

        void reach(int[] end, String upstream, boolean[] reached, List<int[]> queue) {
            if (reached[end[0]]) {
                return;
            }
            reached[end[0]] = true;
            RawSegment segment = rawSegments.get(end[0]);
            if (segment.upstream == null) {
                segment.upstream = upstream;
            }
            queue.add(end);
        }

        STRtree endIndex() {
            STRtree index = new STRtree();
            for (int i = 0; i < rawSegments.size(); i++) {
                for (int k = 0; k < 2; k++) {
                    index.insert(new Envelope(endpoint(rawSegments.get(i).geometry, k)), new int[] {i, k});
                }
            }
            return index;
        }

        List<int[]> near(STRtree ends, Coordinate at) {
            Envelope envelope = new Envelope(at);
            envelope.expandBy(JOINT_M);
            List<int[]> found = new ArrayList<>();
            for (Object item : ends.query(envelope)) {
                int[] end = (int[]) item;
                if (endpoint(rawSegments.get(end[0]).geometry, end[1]).distance(at) <= JOINT_M) {
                    found.add(end);
                }
            }
            found.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            return found;
        }

        RawChamber chamberAt(STRtree chamberIndex, Coordinate at) {
            Envelope envelope = new Envelope(at);
            envelope.expandBy(JOINT_M);
            RawChamber best = null;
            for (Object item : chamberIndex.query(envelope)) {
                RawChamber chamber = (RawChamber) item;
                if (chamber.geometry.getCoordinate().distance(at) <= JOINT_M
                        && (best == null || rawChambers.indexOf(chamber) < rawChambers.indexOf(best))) {
                    best = chamber;
                }
            }
            return best;
        }

        /** Точки подключения датасета становятся перспективными ОКС, здание с точкой внутри — их геометрией. */
        void consumers() {
            STRtree index = new STRtree();
            for (int i = 0; i < buildings.size(); i++) {
                index.insert(buildings.get(i).getGeometry().getEnvelopeInternal(), i);
            }
            Set<Integer> future = new HashSet<>();
            for (Consumer consumer : consumers) {
                Integer building = null;
                for (Object item : index.query(consumer.geometry.getEnvelopeInternal())) {
                    int i = (Integer) item;
                    if ((building == null || i < building) && buildings.get(i).getGeometry().covers(consumer.geometry)) {
                        building = i;
                    }
                }
                Geometry geometry = consumer.geometry;
                if (building != null) {
                    future.add(building);
                    geometry = buildings.get(building).getGeometry();
                }
                futureOks.add(new FutureOks(consumer.id, geometry, consumer.flow, null));
                connectionPoints.add(new ConnectionPoint(consumer.id, consumer.geometry, consumer.id));
            }
            for (int i = 0; i < buildings.size(); i++) {
                if (!future.contains(i)) {
                    existingOks.add(buildings.get(i));
                }
            }
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
            String upstream = identifier(props, featureId, "upstream_object_id");
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

        boolean present(JsonNode props, String field) {
            JsonNode value = props.get(field);
            return value != null && !value.isNull();
        }

        /** Идентификатор: непустая строка или целое число, как в датасете организаторов. */
        String identifier(JsonNode props, String featureId, String field) {
            JsonNode value = required(props, featureId, field);
            if (value == null) {
                return null;
            }
            if (value.isIntegralNumber()) {
                return value.asText();
            }
            if (!value.isTextual() || value.textValue().isEmpty()) {
                add(featureId, field, "ожидается непустая строка или целое число, получено " + describe(value));
                return null;
            }
            return value.textValue();
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
                Geometry parsed = parse(type, raw.path("coordinates"));
                TopologyValidationError error = new IsValidOp(parsed).getValidationError();
                if (error != null) {
                    add(featureId, "geometry", "невалидная геометрия: " + INVALID_REASONS.getOrDefault(
                            error.getErrorType(), error.getMessage()) + " у точки " + error.getCoordinate());
                    return null;
                }
                return Projector.toUtm(parsed);
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

    private static String unknownTypeWarning(String type, int count) {
        RestrictionRule rule = RULES.restriction(type);
        String kind = rule.forbid() ? "запрета" : "специального прохода";
        return String.format(RUSSIAN, "ПРЕДУПРЕЖДЕНИЕ: restriction_type \"%s\" нет в справочнике, объектов: %d, "
                + "применено правило %s с отступом %.1f м", type, count, kind, rule.clearanceM(Integer.MAX_VALUE));
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

    private static Coordinate endpoint(LineString line, int end) {
        return line.getCoordinateN(end == 0 ? 0 : line.getNumPoints() - 1);
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
