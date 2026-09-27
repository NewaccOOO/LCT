package ru.lct.heatnet.io;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.RayCrossingCounter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import org.locationtech.proj4j.ProjectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * docs/interpretation.md): файл читается блоками, фичи разбирают нити пула, в памяти одновременно только блоки
 * в работе; геометрия сразу переводится в EPSG:32637. Чего нет во входе датасета (направление сети, текущий расход,
 * диаметр камеры, перспективные ОКС), выводится после чтения. Ошибки данных возвращаются диагностиками.
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
    // Приложение 18.09, п. 1.1: ограничение любого типа приходит линией или полигоном; правило дальше применяется
    // по размерности геометрии.
    private static final List<String> ANY_RESTRICTION = List.of(
            "Point", "LineString", "MultiLineString", "Polygon", "MultiPolygon");
    private static final List<String> UPSTREAM_TYPES = List.of(HEAT_NETWORK, HEAT_CHAMBER, SOURCE);

    private static final Logger log = LoggerFactory.getLogger(GeoJsonStreamReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Упакованная XY-последовательность хранит точку в 16 байтах вместо объекта Coordinate в 40 байт.
    private static final PackedCoordinateSequenceFactory PACKED = PackedCoordinateSequenceFactory.DOUBLE_FACTORY;
    private static final GeometryFactory GEOMETRY = new GeometryFactory(PACKED);
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
    /** Фич в пачке чтения и в задаче пула, см. Scan.features. */
    private static final int BATCH = 8192;
    private static final int CHUNK = 512;
    // главный поток при чтении строками только читает блоки и сливает части, ядро ему не нужно
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, task -> {
        Thread thread = new Thread(task, "geojson-geometry");
        thread.setDaemon(true);
        return thread;
    });
    /** Блок файла, который главный поток режет на куски; фич в куске срезов; кусков разом в пуле. См. Scan.lines. */
    private static final int BLOCK = 1 << 20;
    private static final int SLICE = 2048;
    private static final int IN_FLIGHT = 4 * THREADS;
    /** Строка длиннее — файл не по строкам, см. Scan.lines. */
    private static final int LINE_LIMIT = 16 << 20;
    private static final byte[] REST_PREFIX = "{\"features\":[]".getBytes(StandardCharsets.UTF_8);
    /** Степени десяти, точные в double, как SMALL_10_POW в jdk.internal.math.FloatingDecimal, см. decimal. */
    private static final double[] TENS = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    public static InputData read(Path path) {
        int expected = expectedFeatures(path);
        return scan(path, () -> new Scan(false, null, expected));
    }

    /**
     * Сколько фич ждать по размеру файла, чтобы общие таблицы id не перестраивались по ходу чтения: фича города
     * около 500 байт. Мельче — таблица дорастёт сама, крупнее — останутся пустые слоты.
     */
    private static int expectedFeatures(Path path) {
        return (int) Math.min(1 << 26, path.toFile().length() / 512);
    }

    /**
     * Чтение в два прохода без дальних препятствий. Первый проход берёт всё, кроме зданий и ограничений, и по нему
     * {@code obstacleExtent} считает прямоугольник, вне которого препятствия на расчёт не влияют; null — оставить
     * все. Второй проход проверяет каждое здание и ограничение как обычно, но кладёт в память только пересекающие
     * прямоугольник. Остальные объекты второй проход берёт из первого: без диагностик в первом проходе они и их
     * проверки те же. Если диагностики есть, второй проход читает и проверяет всё заново, как чтение в один
     * проход. На городе, где ОКС в одном районе, куча не растёт с числом зданий.
     */
    public static InputData read(Path path, Function<InputData, Envelope> obstacleExtent) {
        long started = System.nanoTime();
        int expected = expectedFeatures(path);
        List<Scan> firsts = new ArrayList<>();
        InputData partial = scan(path, () -> {
            firsts.add(new Scan(true, null, expected));
            return firsts.get(firsts.size() - 1);
        });
        long first = System.nanoTime();
        InputData input;
        if (partial.getDiagnostics().isEmpty()) {
            Envelope extent = obstacleExtent.apply(partial);
            Scan clean = firsts.get(firsts.size() - 1);
            input = scan(path, () -> new Scan(clean, extent));
        } else {
            input = scan(path, () -> new Scan(false, null, expected));
        }
        log.info("read: first pass {}s, second pass {}s", (first - started) / 1_000_000_000L,
                (System.nanoTime() - first) / 1_000_000_000L);
        return input;
    }

    /** Как читается массив features: строками в пуле, срезами по границам фич или деревьями на главном потоке. */
    private enum Mode { LINES, SLICES, TREE }

    /**
     * Чтение строками (см. Scan.lines); если фичи переходят через перевод строки — срезами (см. Scan.sliced); если
     * срезы не сошлись с разбором, файл читается деревьями на главном потоке, как без пула.
     */
    private static InputData scan(Path path, Supplier<Scan> scans) {
        try {
            try {
                return scan(path, scans.get(), Mode.LINES);
            } catch (LinesMismatch e) {
                log.info("read: фичи не по строкам, файл читается срезами: {}", e.getMessage());
                return scan(path, scans.get(), Mode.SLICES);
            }
        } catch (SliceMismatch e) {
            log.warn("read: срезы фич не совпали с разбором, файл читается без них: {}", e.getMessage());
            return scan(path, scans.get(), Mode.TREE);
        }
    }

    private static InputData scan(Path path, Scan scan, Mode mode) {
        boolean sliced = mode != Mode.TREE;
        try (JsonParser parser = MAPPER.getFactory().createParser(path.toFile());
                FileChannel channel = sliced ? FileChannel.open(path, StandardOpenOption.READ) : null) {
            scan.channel = channel;
            scan.lines = mode == Mode.LINES;
            scan.read(parser);
        } catch (JsonProcessingException e) {
            if (sliced) {
                // место ошибки в файле даёт только чтение без срезов
                throw new SliceMismatch(e.getOriginalMessage());
            }
            JsonLocation at = e.getLocation();
            String where = at == null ? "" : ", строка " + at.getLineNr() + ", столбец " + at.getColumnNr();
            List<Diagnostic> broken = List.of(new Diagnostic(FILE_ID, "json", "файл не разбирается как JSON" + where));
            return new InputData(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), broken, List.of(), Set.of());
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
        /** Общие с частями чтения, см. sliced. */
        final Map<String, String> typeById;
        /** Числовые id точек подключения и камер: в выходе ссылки на них пишутся числом. */
        final Set<String> numericIds;
        final Map<String, String> upstreamById = new LinkedHashMap<>();
        final Map<String, Integer> unknownRestrictionTypes = new LinkedHashMap<>();
        final List<Ref> refs = new ArrayList<>();
        /** Байт «]» массива features, как только его нашёл главный поток при чтении строками, см. region. */
        volatile long featuresEnd = Long.MAX_VALUE;
        /** Первый байт самого дальнего куска строк, который начал разбор фич, см. region. */
        final AtomicLong farthestPart = new AtomicLong(-1);
        final boolean skipObstacles;
        /** Второй проход после чистого первого: читаются только препятствия, id уже проверены, см. read. */
        final boolean obstaclesOnly;
        final Envelope extent;
        /** Геометрия текущей фичи, построенная в пуле, см. features; null — строится на месте. */
        Parsed prepared;
        /** Геометрия текущей фичи из быстрого разбора, см. part; null — строится из дерева или байтов. */
        Shape shape;
        /** Байты geometry текущей фичи, которую быстрый разбор отложил или не осилил, см. Fast.feature. */
        byte[] geometryBytes;
        int geometryFrom;
        int geometryTo;
        /** Файл для чтения строками или срезами, см. lines и sliced; null — чтение деревьями на главном потоке. */
        FileChannel channel;
        boolean lines;
        Source source;
        int sources;
        int ordinal;
        int duplicates;

        Scan(boolean skipObstacles, Envelope extent, int expected) {
            this(skipObstacles, false, extent, new ConcurrentHashMap<>(expected), ConcurrentHashMap.newKeySet(expected / 2));
        }

        /** Часть чтения для куска фич в пуле: свои списки, общие typeById и numericIds. */
        Scan(Scan whole) {
            this(whole.skipObstacles, whole.obstaclesOnly, whole.extent, whole.typeById, whole.numericIds);
        }

        /** Второй проход после первого без диагностик: всё, кроме препятствий, из первого. */
        Scan(Scan first, Envelope extent) {
            this(false, true, extent, first.typeById, first.numericIds);
            rawSegments.addAll(first.rawSegments);
            rawChambers.addAll(first.rawChambers);
            consumers.addAll(first.consumers);
            futureOks.addAll(first.futureOks);
            connectionPoints.addAll(first.connectionPoints);
            upstreamById.putAll(first.upstreamById);
            refs.addAll(first.refs);
            source = first.source;
            sources = first.sources;
        }

        private Scan(boolean skipObstacles, boolean obstaclesOnly, Envelope extent, Map<String, String> typeById,
                Set<String> numericIds) {
            this.skipObstacles = skipObstacles;
            this.obstaclesOnly = obstaclesOnly;
            this.extent = extent;
            this.typeById = typeById;
            this.numericIds = numericIds;
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
                    if (channel != null) {
                        parser = lines ? lines(parser) : sliced(parser);
                    } else {
                        features(parser);
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

        void feature(JsonNode node) throws IOException {
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
            boolean numericId = id != null && props.get("id").isNumber();
            if (!"Feature".equals(node.path("type").textValue())) {
                add(featureId, "type", "ожидается type = Feature");
            }
            String objectType = string(props, featureId, "object_type");
            if (objectType != null && OBJECT_TYPES.contains(objectType)) {
                // Своя строка типа у каждой фичи стоит десятки мегабайт на входе из миллионов объектов.
                objectType = objectType.intern();
            }
            if (id != null && !obstaclesOnly && typeById.putIfAbsent(id, objectType == null ? "" : objectType) != null) {
                duplicates++;
                add(featureId, "id", "id повторяется");
            }
            boolean obstacle = OKS_EXISTING.equals(objectType) || RESTRICTION.equals(objectType);
            if (objectType == null || (skipObstacles ? obstacle : obstaclesOnly && !obstacle)) {
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
                        if (numericId) {
                            numericIds.add(id);
                        }
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
                            if (numericId) {
                                numericIds.add(id);
                            }
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
                        if (numericId) {
                            numericIds.add(id);
                        }
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
                    if (restrictionType != null) {
                        if (RULES.isKnown(restrictionType)) {
                            restrictionType = restrictionType.intern();
                        } else {
                            unknownRestrictionTypes.merge(restrictionType, 1, Integer::sum);
                        }
                    }
                    Geometry geometry = geometry(node, featureId, ANY_RESTRICTION);
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
            List<String> warnings = new ArrayList<>();
            network(segments, chambers, warnings);
            // списки прочитанного не меняются: по ним второй проход, см. read
            List<FutureOks> future = new ArrayList<>(futureOks);
            List<ConnectionPoint> points = new ArrayList<>(connectionPoints);
            List<ExistingOks> existing = new ArrayList<>(existingOks);
            consumers(future, points, existing);
            unknownRestrictionTypes.forEach((type, count) -> warnings.add(unknownTypeWarning(type, count)));
            return new InputData(source, segments, chambers, future, points, existing, restrictions,
                    diagnostics, warnings, numericIds);
        }

        /**
         * Направление сети обходом от источника, диаметры камер и текущий расход там, где их нет во входе. Участок и
         * камера, до которых обход не дошёл, остаются в сети без upstream: приложение не требует связи сети с
         * источником (п. 1.1 — у heat_network обязательны id, object_type и diameter; п. 2.4 — расход сети не
         * считается; разъяснение 11 — врезка в любой допустимой точке heat_network), а направление расчёт не читает.
         * О таких объектах предупреждает одна строка warnings.
         */
        void network(List<NetworkSegment> segments, List<Chamber> chambers, List<String> warnings) {
            if (source != null && (rawSegments.stream().anyMatch(r -> r.upstream == null)
                    || rawChambers.stream().anyMatch(r -> r.upstream == null))) {
                traverse();
            }
            int apartSegments = 0;
            int apartChambers = 0;
            for (RawSegment raw : rawSegments) {
                apartSegments += raw.upstream == null ? 1 : 0;
                // текущий расход существующей сети в расчёте не участвует (приложение 18.09, п. 2.4)
                double flow = raw.flow != null ? raw.flow : 0.0;
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
                } else {
                    apartChambers += raw.upstream == null ? 1 : 0;
                    chambers.add(new Chamber(raw.id, raw.geometry, diameter, raw.upstream));
                }
            }
            if (source != null && apartSegments + apartChambers > 0) {
                warnings.add(String.format(RUSSIAN, "ПРЕДУПРЕЖДЕНИЕ: не связаны с источником по стыкам участков "
                        + "heat_network: %d, камер: %d; расчёт идёт, врезка в них допустима", apartSegments, apartChambers));
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
            Coordinate at = source.getGeometry().getCoordinate();
            for (int[] end : near(ends, at)) {
                reach(end, source.getId(), reached, queue);
            }
            STRtree lines = null;
            for (int head = 0; ; ) {
                for (; head < queue.size(); head++) {
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
                if (allReached(reached)) {
                    break;
                }
                lines = lines == null ? lineIndex() : lines;
                if (!tees(at, lines, reached, queue)) {
                    break;
                }
            }
            // камера на середине пройденного участка без разреза тоже в сети
            for (RawChamber chamber : rawChambers) {
                if (chamber.upstream != null) {
                    continue;
                }
                lines = lines == null ? lineIndex() : lines;
                Coordinate c = chamber.geometry.getCoordinate();
                for (Object item : lines.query(new Envelope(c))) {
                    int j = (Integer) item;
                    if (reached[j] && Distance.pointToSegmentString(c, rawSegments.get(j).geometry.getCoordinates()) <= JOINT_M) {
                        chamber.upstream = rawSegments.get(j).id;
                        break;
                    }
                }
            }
        }

        static boolean allReached(boolean[] reached) {
            for (boolean one : reached) {
                if (!one) {
                    return false;
                }
            }
            return true;
        }

        /** Участки по рамкам, расширенным на JOINT_M: запрос точкой находит участки не дальше JOINT_M от неё. */
        STRtree lineIndex() {
            STRtree index = new STRtree();
            for (int i = 0; i < rawSegments.size(); i++) {
                Envelope envelope = new Envelope(rawSegments.get(i).geometry.getEnvelopeInternal());
                envelope.expandBy(JOINT_M);
                index.insert(envelope, i);
            }
            return index;
        }

        /**
         * Т-стыки: участок, до которого обход по концам не дошёл, связан с пройденным, если конец одного лежит на
         * другом не дальше JOINT_M, а разреза там нет; так же — участок, на середине которого стоит источник.
         * Выгрузки рабочих систем (разъяснение 17) так пишут ответвление от середины трубы. Ищутся, только когда
         * очередь обхода кончилась, а пройдены не все участки; false — связанных так не нашлось.
         */
        boolean tees(Coordinate at, STRtree lines, boolean[] reached, List<int[]> queue) {
            int before = queue.size();
            for (int i = 0; i < reached.length; i++) {
                if (reached[i]) {
                    continue;
                }
                LineString line = rawSegments.get(i).geometry;
                if (line.getEnvelopeInternal().distance(new Envelope(at)) <= JOINT_M
                        && Distance.pointToSegmentString(at, line.getCoordinates()) <= JOINT_M) {
                    // источник на середине участка: обход идёт от обоих его концов
                    reach(new int[] {i, 0}, source.getId(), reached, queue);
                    queue.add(new int[] {i, 1});
                    continue;
                }
                for (Object item : lines.query(line.getEnvelopeInternal())) {
                    int j = (Integer) item;
                    if (!reached[j] || reached[i]) {
                        continue;
                    }
                    LineString other = rawSegments.get(j).geometry;
                    for (int k = 0; k < 2 && !reached[i]; k++) {
                        // конец участка i на участке j: обход идёт дальше от другого конца i
                        if (Distance.pointToSegmentString(endpoint(line, k), other.getCoordinates()) <= JOINT_M) {
                            reach(new int[] {i, k}, rawSegments.get(j).id, reached, queue);
                        }
                    }
                    for (int k = 0; k < 2 && !reached[i]; k++) {
                        // конец участка j на середине участка i: обход идёт от обоих концов i
                        if (Distance.pointToSegmentString(endpoint(other, k), line.getCoordinates()) <= JOINT_M) {
                            reach(new int[] {i, 0}, rawSegments.get(j).id, reached, queue);
                            queue.add(new int[] {i, 1});
                        }
                    }
                }
            }
            return queue.size() > before;
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

        /**
         * Точки подключения датасета становятся перспективными ОКС. Здание с точкой внутри — геометрия такого ОКС
         * (по ней строится финальный прямой участок), и оно же остаётся препятствием, как все полигоны ОКС
         * (приложение 18.09, п. 2.2).
         */
        void consumers(List<FutureOks> futureOks, List<ConnectionPoint> connectionPoints, List<ExistingOks> existingOks) {
            long started = System.nanoTime();
            STRtree index = new STRtree();
            for (int i = 0; i < buildings.size(); i++) {
                index.insert(buildings.get(i).getGeometry().getEnvelopeInternal(), i);
            }
            // дерево строится до параллельных запросов: ленивая сборка при первом запросе не потокобезопасна
            index.build();
            // здание каждой точки ищется параллельно: точки друг от друга не зависят, а их на городе миллионы
            int[] buildingOf = new int[consumers.size()];
            IntStream.range(0, consumers.size()).parallel().forEach(k -> {
                Consumer consumer = consumers.get(k);
                int building = -1;
                for (Object item : index.query(consumer.geometry.getEnvelopeInternal())) {
                    int i = (Integer) item;
                    if ((building < 0 || i < building)
                            && covers(buildings.get(i).getGeometry(), consumer.geometry.getCoordinate())) {
                        building = i;
                    }
                }
                buildingOf[k] = building;
            });
            for (int k = 0; k < consumers.size(); k++) {
                Consumer consumer = consumers.get(k);
                Geometry geometry = buildingOf[k] >= 0 ? buildings.get(buildingOf[k]).getGeometry() : consumer.geometry;
                futureOks.add(new FutureOks(consumer.id, geometry, consumer.flow, null));
                connectionPoints.add(new ConnectionPoint(consumer.id, consumer.geometry, consumer.id));
            }
            existingOks.addAll(buildings);
            if (!consumers.isEmpty()) {
                log.info("read: {} connection points to buildings {}s", consumers.size(), (System.nanoTime() - started) / 1_000_000_000L);
            }
        }

        /**
         * Точка внутри полигона здания или на его границе, как Geometry.covers. covers идёт через RelateOp, а
         * SimplePointInAreaLocator копирует упакованные координаты в кэш: на городе в миллионы зданий первое занимало
         * минуты, второе — гигабайты кучи.
         */
        static boolean covers(Geometry area, Coordinate point) {
            for (int i = 0; i < area.getNumGeometries(); i++) {
                Polygon polygon = (Polygon) area.getGeometryN(i);
                int shell = RayCrossingCounter.locatePointInRing(point, polygon.getExteriorRing().getCoordinateSequence());
                if (shell == Location.BOUNDARY) {
                    return true;
                }
                if (shell == Location.EXTERIOR) {
                    continue;
                }
                int inHole = Location.EXTERIOR;
                for (int h = 0; h < polygon.getNumInteriorRing() && inHole == Location.EXTERIOR; h++) {
                    inHole = RayCrossingCounter.locatePointInRing(point, polygon.getInteriorRingN(h).getCoordinateSequence());
                }
                if (inHole != Location.INTERIOR) {
                    return true;
                }
            }
            return false;
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

        /**
         * Идентификатор: непустая строка или любое число JSON, в том числе 11.0 и 11.5 (приложение 18.09, разд. 1: id
         * «могут быть строковыми или числовыми» и «не интерпретируется по его формату»). Число становится строкой
         * Jackson с тем же значением (11.0 остаётся 11.0) и так же пишется в выход, см. numericIds.
         */
        String identifier(JsonNode props, String featureId, String field) {
            JsonNode value = required(props, featureId, field);
            if (value == null) {
                return null;
            }
            if (value.isNumber()) {
                return value.asText();
            }
            if (!value.isTextual() || value.textValue().isEmpty()) {
                add(featureId, field, "ожидается непустая строка или число, получено " + describe(value));
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

        Geometry geometry(JsonNode feature, String featureId, List<String> allowed) throws IOException {
            if (shape == null && geometryBytes != null) {
                shape = shape(geometryBytes, geometryFrom, geometryTo);
            }
            Parsed parsed;
            if (prepared != null && prepared.allowed == allowed) {
                parsed = prepared;
            } else if (shape != null) {
                parsed = checked(shape.type, () -> shape.geometry, allowed);
            } else if (geometryBytes != null) {
                parsed = parseGeometry(MAPPER.readTree(geometryBytes, geometryFrom, geometryTo - geometryFrom), allowed);
            } else {
                parsed = parseGeometry(feature.get("geometry"), allowed);
            }
            if (parsed.problem != null) {
                add(featureId, "geometry", parsed.problem);
            }
            return parsed.geometry;
        }

        /**
         * Фичи массива features пачками: главный поток разбирает JSON и применяет фичи по порядку, а пул заранее
         * строит их геометрию (разбор координат, проверка валидности, перевод в UTM), пока разбирается следующая
         * пачка. Геометрия не зависит от состояния чтения, поэтому диагностики и порядок объектов те же.
         */
        void features(JsonParser parser) throws IOException {
            List<JsonNode> batch = new ArrayList<>();
            List<JsonNode> pendingNodes = List.of();
            List<Future<Parsed[]>> pending = List.of();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                batch.add(MAPPER.readTree(parser));
                if (batch.size() == BATCH) {
                    List<Future<Parsed[]>> submitted = prepare(batch);
                    apply(pendingNodes, pending);
                    pendingNodes = batch;
                    pending = submitted;
                    batch = new ArrayList<>();
                }
            }
            apply(pendingNodes, pending);
            apply(batch, prepare(batch));
        }

        /**
         * Фичи массива features срезами файла. Главный поток читает файл блоками и находит границы фич по скобкам
         * с учётом строк, пул разбирает куски фич (см. part) и применяет их к своей части чтения, главный поток
         * сливает части по порядку. Синтаксис фич проверяет разбор в пуле. Всё, в чём части могут разойтись с
         * чтением подряд (не объект в массиве, ошибка JSON, повтор id, второй источник), — SliceMismatch: файл
         * читается заново без срезов, и диагностики те же. Возвращает разбор корня после массива.
         */
        JsonParser sliced(JsonParser parser) throws IOException {
            long open = parser.getTokenLocation().getByteOffset();
            if (open < 0) {
                // UTF-16 и UTF-32: байтовых смещений нет
                features(parser);
                return parser;
            }
            byte[] block = new byte[BLOCK];
            long offset = open;
            int length = fill(block, 0, offset);
            if (block[0] != '[') {
                throw new SliceMismatch("массив features не с байта " + open);
            }
            int at = 1;
            int total = 0;
            int count = 0;
            int[] bounds = new int[2 * SLICE];
            ArrayDeque<Future<Scan>> parts = new ArrayDeque<>();
            try {
                while (true) {
                    int start = space(block, at, length);
                    if (start < length && block[start] == ']') {
                        offset += start + 1;
                        break;
                    }
                    if (start < length && total > 0) {
                        if (block[start] != ',') {
                            throw new SliceMismatch("после фичи нет запятой, байт " + (offset + start));
                        }
                        start = space(block, start + 1, length);
                    }
                    if (start < length && block[start] != '{') {
                        throw new SliceMismatch("элемент features не объект, байт " + (offset + start));
                    }
                    int end = start < length ? objectEnd(block, start, length) : -1;
                    if (end < 0) {
                        // фича не уместилась в блок: кусок уходит в пул, недочитанный хвост переносится в новый блок
                        submit(parts, block, bounds, count, ordinal + total - count);
                        count = 0;
                        int tail = length - at;
                        byte[] next = new byte[Math.max(BLOCK, 2 * tail)];
                        System.arraycopy(block, at, next, 0, tail);
                        offset += at;
                        block = next;
                        length = tail + fill(block, tail, offset + tail);
                        at = 0;
                        continue;
                    }
                    bounds[2 * count] = start;
                    bounds[2 * count + 1] = end;
                    count++;
                    total++;
                    at = end;
                    if (count == SLICE) {
                        submit(parts, block, bounds, count, ordinal + total - count);
                        count = 0;
                    }
                }
                submit(parts, block, bounds, count, ordinal + total - count);
                while (!parts.isEmpty()) {
                    merge(take(parts.poll()));
                }
            } finally {
                parts.forEach(part -> part.cancel(false));
            }
            ordinal += total;
            return rest(offset);
        }

        /**
         * Фичи массива features строками: главный поток читает файл блоками и режет их по последнему переводу строки,
         * нить пула сама находит фичи в своём куске строк (см. region) и разбирает их, главный поток сливает части по
         * порядку и сверяет запятые на стыках (см. Joint). Сырого перевода строки внутри строки JSON не бывает,
         * поэтому кусок, первый байт которого между фичами, начинается вне строки; следующий кусок начинается между
         * фичами, если ни одна фича предыдущего не перешла через его конец. Иначе (JSON с отступами, строка длиннее
         * LINE_LIMIT) или пул начал разбор строк за концом массива — LinesMismatch, и файл читается срезами, см. sliced.
         * Возвращает разбор корня после массива.
         */
        JsonParser lines(JsonParser parser) throws IOException {
            long open = parser.getTokenLocation().getByteOffset();
            if (open < 0) {
                features(parser);
                return parser;
            }
            byte[] block = new byte[BLOCK];
            long offset = open;
            int length = fill(block, 0, offset);
            if (block[0] != '[') {
                throw new SliceMismatch("массив features не с байта " + open);
            }
            int at = 1;
            boolean eof = false;
            Joint joint = new Joint();
            ArrayDeque<Future<Region>> parts = new ArrayDeque<>();
            try {
                while (joint.close < 0 && !(eof && at == length)) {
                    int cut = eof ? length : lastLine(block, at, length);
                    if (cut < 0) {
                        // в блоке нет конца строки: хвост переносится в новый блок, при нужде больший
                        int tail = length - at;
                        if (tail >= LINE_LIMIT) {
                            throw new LinesMismatch("строка длиннее " + LINE_LIMIT + " байт, байт " + (offset + at));
                        }
                        byte[] next = new byte[Math.max(BLOCK, 2 * tail)];
                        System.arraycopy(block, at, next, 0, tail);
                        offset += at;
                        block = next;
                        at = 0;
                        int read = channel.read(ByteBuffer.wrap(block, tail, block.length - tail), offset + tail);
                        eof = read < 0;
                        length = tail + Math.max(read, 0);
                        continue;
                    }
                    byte[] lines = block;
                    int from = at;
                    long start = offset;
                    parts.add(POOL.submit(() -> region(lines, from, cut, start)));
                    at = cut;
                    while (!parts.isEmpty() && joint.close < 0 && (parts.peek().isDone() || parts.size() > IN_FLIGHT)) {
                        joint.add(take(parts.poll()));
                    }
                }
                while (!parts.isEmpty() && joint.close < 0) {
                    joint.add(take(parts.poll()));
                }
            } finally {
                parts.forEach(part -> part.cancel(false));
            }
            if (joint.close < 0) {
                throw new SliceMismatch("файл оборвался в массиве features");
            }
            featuresEnd = joint.close;
            if (farthestPart.get() > joint.close) {
                throw new LinesMismatch("пул начал разбор строк за концом features, байт " + farthestPart.get());
            }
            ordinal += joint.features;
            return rest(joint.close + 1);
        }

        /**
         * Кусок строк массива features с первого байта между фичами: фичи по скобкам, запятые между ними, конец
         * массива, затем разбор фич, как в part. Номера фич куску неизвестны, поэтому part считает их с нуля, а
         * Joint не берёт часть, у которой номер попал в диагностику. Кусок, который кончается внутри фичи или
         * начинается не между фичами, — LinesMismatch.
         */
        Region region(byte[] block, int from, int to, long offset) throws IOException {
            Region region = new Region(offset);
            int[] bounds = new int[256];
            int count = 0;
            int commas = 0;
            int at = from;
            while ((at = space(block, at, to)) < to) {
                byte c = block[at];
                if (c == ',') {
                    commas++;
                    at++;
                    continue;
                }
                if (c == ']') {
                    region.close = offset + at;
                    break;
                }
                int end = c == '{' ? objectEnd(block, at, to) : -1;
                if (end < 0) {
                    throw new LinesMismatch("фича не в своих строках, байт " + (offset + at));
                }
                if (count == 0) {
                    region.before = commas;
                } else if (commas != 1) {
                    throw new SliceMismatch("между фичами не одна запятая, байт " + (offset + at));
                }
                commas = 0;
                if (2 * count == bounds.length) {
                    bounds = Arrays.copyOf(bounds, 2 * bounds.length);
                }
                bounds[2 * count] = at;
                bounds[2 * count + 1] = end;
                count++;
                at = end;
            }
            if (count == 0) {
                region.before = commas;
            } else {
                region.after = commas;
            }
            region.features = count;
            // Кусок за концом features пул может взять раньше, чем главный поток найдёт «]», а part пишет в общие
            // typeById и numericIds. Кусок сначала отмечается, потом сверяется с концом массива: либо он видит конец
            // и не разбирается, либо главный поток видит его отметку и читает файл заново срезами, см. lines.
            if (count > 0) {
                farthestPart.accumulateAndGet(offset + from, Math::max);
                if (offset + from > featuresEnd) {
                    return region;
                }
            }
            region.part = part(block, Arrays.copyOf(bounds, 2 * count), 0);
            return region;
        }

        /** Слияние кусков строк по порядку: запятые на стыках, число фич и конец массива features. */
        final class Joint {
            int features;
            int commas;
            long close = -1;

            void add(Region region) throws IOException {
                if (region.part.diagnostics.stream().anyMatch(d -> d.getFeatureId().startsWith("#"))) {
                    throw new LinesMismatch("в диагностике номер фичи, а куску строк номера неизвестны");
                }
                if (region.features > 0) {
                    if (commas + region.before != (features > 0 ? 1 : 0)) {
                        throw new SliceMismatch("запятые между фичами на стыке строк, байт " + region.offset);
                    }
                    commas = region.after;
                } else {
                    commas += region.before;
                }
                if (region.close >= 0 && commas > 0) {
                    throw new SliceMismatch("запятая перед концом features, байт " + region.close);
                }
                features += region.features;
                merge(region.part);
                close = region.close;
            }
        }

        /**
         * Остаток корня после массива features разбирает Jackson с байта offset, перед ним пустой массив на месте
         * прочитанного. Второй массив features, если он есть, читается деревьями на главном потоке.
         */
        JsonParser rest(long offset) throws IOException {
            FileChannel file = channel;
            channel = null;
            file.position(offset);
            JsonParser rest = MAPPER.getFactory().createParser(new SequenceInputStream(
                    new ByteArrayInputStream(REST_PREFIX), Channels.newInputStream(file)));
            for (int i = 0; i < 4; i++) {
                rest.nextToken();
            }
            return rest;
        }

        /** Дочитывает блок с места pos; конец файла внутри массива features — срез не сходится с файлом. */
        int fill(byte[] block, int pos, long fileOffset) throws IOException {
            int read = channel.read(ByteBuffer.wrap(block, pos, block.length - pos), fileOffset);
            if (read <= 0) {
                throw new SliceMismatch("файл оборвался в массиве features");
            }
            return read;
        }

        /** Кусок фич блока в пул; готовые части сливаются по порядку, не больше IN_FLIGHT частей в работе. */
        void submit(ArrayDeque<Future<Scan>> parts, byte[] block, int[] bounds, int count, int first) throws IOException {
            if (count > 0) {
                int[] mine = Arrays.copyOf(bounds, 2 * count);
                parts.add(POOL.submit(() -> part(block, mine, first)));
            }
            while (!parts.isEmpty() && (parts.peek().isDone() || parts.size() > IN_FLIGHT)) {
                merge(take(parts.poll()));
            }
        }

        /**
         * Фичи куска по порядку в своей части чтения; first — сколько элементов features перед куском. Фича
         * разбирается байтами (см. Fast), а если она не в быстром виде, — деревом Jackson, как раньше.
         */
        Scan part(byte[] block, int[] bounds, int first) throws IOException {
            Scan part = new Scan(this);
            part.ordinal = first;
            for (int i = 0; i < bounds.length; i += 2) {
                JsonNode node = fast(part, block, bounds[i], bounds[i + 1]);
                part.feature(node != null ? node : MAPPER.readTree(block, bounds[i], bounds[i + 1] - bounds[i]));
            }
            return part;
        }

        /** Результат задачи пула; её SliceMismatch и LinesMismatch бросаются как есть. */
        <T> T take(Future<T> future) throws IOException {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Чтение прервано", e);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof SliceMismatch ? (SliceMismatch) e.getCause()
                        : new SliceMismatch(String.valueOf(e.getCause()));
            }
        }

        /**
         * Часть в общее чтение. Повтор id между частями видит только общий typeById, и диагностика досталась бы
         * части, которая успела позже; второй источник часть не видит вовсе. Оба случая читаются подряд.
         */
        void merge(Scan part) {
            if (part.duplicates > 0 || sources + part.sources > 1) {
                throw new SliceMismatch("повтор id или второй источник");
            }
            rawSegments.addAll(part.rawSegments);
            rawChambers.addAll(part.rawChambers);
            consumers.addAll(part.consumers);
            buildings.addAll(part.buildings);
            futureOks.addAll(part.futureOks);
            connectionPoints.addAll(part.connectionPoints);
            existingOks.addAll(part.existingOks);
            restrictions.addAll(part.restrictions);
            diagnostics.addAll(part.diagnostics);
            part.upstreamById.forEach(upstreamById::putIfAbsent);
            part.unknownRestrictionTypes.forEach((type, count) -> unknownRestrictionTypes.merge(type, count, Integer::sum));
            refs.addAll(part.refs);
            sources += part.sources;
            if (source == null) {
                source = part.source;
            }
        }

        List<Future<Parsed[]>> prepare(List<JsonNode> batch) {
            List<Future<Parsed[]>> parts = new ArrayList<>();
            for (int from = 0; from < batch.size(); from += CHUNK) {
                List<JsonNode> chunk = batch.subList(from, Math.min(batch.size(), from + CHUNK));
                parts.add(POOL.submit(() -> {
                    Parsed[] result = new Parsed[chunk.size()];
                    for (int i = 0; i < result.length; i++) {
                        List<String> allowed = expectedGeometry(chunk.get(i), skipObstacles);
                        result[i] = allowed == null ? null : parseGeometry(chunk.get(i).get("geometry"), allowed);
                    }
                    return result;
                }));
            }
            return parts;
        }

        void apply(List<JsonNode> nodes, List<Future<Parsed[]>> parts) throws IOException {
            int next = 0;
            for (Future<Parsed[]> part : parts) {
                Parsed[] parsed;
                try {
                    parsed = part.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Чтение прервано", e);
                } catch (ExecutionException e) {
                    // геометрия этой пачки посчитается на месте, как без пула
                    parsed = new Parsed[Math.min(CHUNK, nodes.size() - next)];
                }
                for (Parsed one : parsed) {
                    prepared = one;
                    feature(nodes.get(next++));
                }
            }
            prepared = null;
        }

        void add(String featureId, String field, String problem) {
            diagnostics.add(new Diagnostic(featureId, field, problem));
        }
    }

    /** Кусок строк массива features: часть чтения, число фич, запятые до первой и после последней, конец массива. */
    private static final class Region {
        final long offset;
        Scan part;
        int features;
        int before;
        int after;
        long close = -1;

        Region(long offset) {
            this.offset = offset;
        }
    }

    /** Фичи файла не по строкам: он читается срезами, см. Scan.lines. */
    private static final class LinesMismatch extends SliceMismatch {
        LinesMismatch(String message) {
            super(message);
        }
    }

    /** Срез файла не совпал с фичей: кодировка или BOM сдвинули байтовые смещения разбора. */
    private static class SliceMismatch extends RuntimeException {
        SliceMismatch(String message) {
            super(message);
        }
    }

    /** Геометрия фичи из потокового разбора: type и геометрия в WGS 84 по нему, ещё без проверки валидности. */
    private static final class Shape {
        final String type;
        final Geometry geometry;

        Shape(String type, Geometry geometry) {
            this.type = type;
            this.geometry = geometry;
        }
    }

    /** Геометрия фичи или текст ошибки для диагностики поля geometry. */
    private static final class Parsed {
        final List<String> allowed;
        final Geometry geometry;
        final String problem;

        Parsed(List<String> allowed, Geometry geometry, String problem) {
            this.allowed = allowed;
            this.geometry = geometry;
            this.problem = problem;
        }
    }

    /**
     * Фича байтовым разбором без Jackson (см. Fast), геометрия — в part.shape или байтами в part.geometryBytes;
     * null — фича не в быстром виде, и её разбирает Jackson.
     */
    static JsonNode fast(Scan part, byte[] bytes, int from, int to) {
        part.shape = null;
        part.geometryBytes = null;
        try {
            return new Fast(bytes, from, to).feature(part);
        } catch (NotFast e) {
            part.shape = null;
            part.geometryBytes = null;
            return null;
        }
    }

    /** Узел фичи из байтов быстрым разбором, как в чтении за один проход; null — не в быстром виде. Для тестов. */
    static JsonNode fastFeature(byte[] bytes) {
        int from = space(bytes, 0, bytes.length);
        int to = objectEnd(bytes, from, bytes.length);
        return to < 0 || space(bytes, to, bytes.length) != bytes.length ? null
                : fast(new Scan(false, null, 0), bytes, from, to);
    }

    /** Геометрия из байтов значения geometry быстрым разбором; null — пусть разберёт дерево. */
    private static Shape shape(byte[] bytes, int from, int to) {
        try {
            Fast fast = new Fast(bytes, from, to);
            Shape shape = fast.shape();
            fast.end();
            return shape;
        } catch (NotFast e) {
            return null;
        }
    }

    /** Фича не в том виде, который разбирает Fast. Без стека: на обычных входах не бросается. */
    private static final class NotFast extends RuntimeException {
        NotFast() {
            super(null, null, false, false);
        }
    }

    private static final NotFast NOT_FAST = new NotFast();
    /** Частые ключи и значения: быстрый разбор отдаёт эти строки вместо новых, см. Fast.string. */
    private static final List<String> KNOWN = List.of("type", "Feature", "geometry", "properties", "coordinates",
            "Point", "LineString", "Polygon", "MultiLineString", "MultiPolygon", "id", "object_type", "restriction_type",
            "flow_tph", "diameter", "upstream_object_id", "oks_id", "heat_load", SOURCE, HEAT_NETWORK, HEAT_CHAMBER,
            OKS_FUTURE, CONNECTION_POINT, OKS_EXISTING, RESTRICTION, BUILDING);
    private static final byte[][] KNOWN_BYTES = KNOWN.stream()
            .map(known -> known.getBytes(StandardCharsets.US_ASCII)).toArray(byte[][]::new);

    /**
     * Фича в частом виде без Jackson: ключи и строки ASCII без экранирования, числа по грамматике JSON, в properties
     * и других ключах фичи — скаляры, geometry — объект с type до coordinates из чисел, разбор которого по дереву
     * удался бы. Принимает только корректный JSON и строит те же узлы и геометрию, что readTree и parse с
     * настройками MAPPER; на остальном бросает NOT_FAST. Исключение — отложенная геометрия первого прохода, см. feature.
     */
    private static final class Fast {
        final byte[] bytes;
        final int end;
        int at;

        Fast(byte[] bytes, int from, int end) {
            this.bytes = bytes;
            this.at = from;
            this.end = end;
        }

        /** Узел фичи без geometry. В первом проходе геометрия откладывается байтами, см. Scan.geometry. */
        ObjectNode feature(Scan part) {
            ObjectNode node = MAPPER.createObjectNode();
            boolean geometry = false;
            expect('{');
            do {
                String name = key();
                if (!name.equals("geometry")) {
                    node.set(name, value());
                    continue;
                }
                if (geometry) {
                    throw NOT_FAST;
                }
                geometry = true;
                space();
                int start = at;
                if (!part.skipObstacles) {
                    try {
                        part.shape = shape();
                        continue;
                    } catch (NotFast e) {
                        at = start;
                    }
                }
                if (part.skipObstacles && at < end && (bytes[at] == '{' || bytes[at] == '[')) {
                    // первому проходу хватает конца геометрии по скобкам: синтаксис той, что он не разберёт,
                    // проверит второй проход, он читает каждую фичу целиком
                    at = objectEnd(bytes, at, end);
                    if (at < 0) {
                        throw NOT_FAST;
                    }
                } else {
                    skip();
                }
                part.geometryBytes = bytes;
                part.geometryFrom = start;
                part.geometryTo = at;
            } while (next(','));
            expect('}');
            end();
            return node;
        }

        Shape shape() {
            String type = null;
            Geometry geometry = null;
            expect('{');
            do {
                String name = key();
                if (name.equals("type") && type == null) {
                    space();
                    type = string();
                } else if (name.equals("coordinates") && type != null && geometry == null) {
                    geometry = coordinates(type);
                } else if (name.equals("type") || name.equals("coordinates")) {
                    throw NOT_FAST;
                } else {
                    skip();
                }
            } while (next(','));
            expect('}');
            if (geometry == null) {
                throw NOT_FAST;
            }
            return new Shape(type, geometry);
        }

        Geometry coordinates(String type) {
            try {
                switch (type) {
                    case "Point": {
                        double[] xy = new double[2];
                        position(xy, 0);
                        return GEOMETRY.createPoint(PACKED.create(xy, 2));
                    }
                    case "LineString":
                        return GEOMETRY.createLineString(positions(2));
                    case "Polygon":
                        return polygon();
                    case "MultiLineString": {
                        List<LineString> lines = new ArrayList<>();
                        expect('[');
                        do {
                            lines.add(GEOMETRY.createLineString(positions(2)));
                        } while (next(','));
                        expect(']');
                        return GEOMETRY.createMultiLineString(lines.toArray(new LineString[0]));
                    }
                    case "MultiPolygon": {
                        List<Polygon> polygons = new ArrayList<>();
                        expect('[');
                        do {
                            polygons.add(polygon());
                        } while (next(','));
                        expect(']');
                        return GEOMETRY.createMultiPolygon(polygons.toArray(new Polygon[0]));
                    }
                    default:
                        throw NOT_FAST;
                }
            } catch (IllegalArgumentException e) {
                throw NOT_FAST;
            }
        }

        Polygon polygon() {
            List<LinearRing> rings = new ArrayList<>();
            expect('[');
            do {
                CoordinateSequence ring = positions(4);
                int last = ring.size() - 1;
                if (ring.getX(0) != ring.getX(last) || ring.getY(0) != ring.getY(last)) {
                    throw NOT_FAST;
                }
                rings.add(GEOMETRY.createLinearRing(ring));
            } while (next(','));
            expect(']');
            return GEOMETRY.createPolygon(rings.get(0), rings.subList(1, rings.size()).toArray(new LinearRing[0]));
        }

        /** Позиции в упакованную XY-последовательность, как фабрика GEOMETRY строит её из Coordinate[]. */
        CoordinateSequence positions(int min) {
            double[] xy = new double[16];
            int size = 0;
            expect('[');
            do {
                if (2 * size == xy.length) {
                    xy = Arrays.copyOf(xy, 2 * xy.length);
                }
                position(xy, 2 * size++);
            } while (next(','));
            expect(']');
            if (size < min) {
                throw NOT_FAST;
            }
            return PACKED.create(Arrays.copyOf(xy, 2 * size), 2);
        }

        /** [долгота, широта, числа...] в допустимом диапазоне — в xy[i], xy[i + 1]. */
        void position(double[] xy, int i) {
            expect('[');
            double lon = coordinate();
            expect(',');
            double lat = coordinate();
            while (next(',')) {
                coordinate();
            }
            expect(']');
            if (!(Math.abs(lon) <= 180 && Math.abs(lat) <= 90)) {
                throw NOT_FAST;
            }
            xy[i] = lon;
            xy[i + 1] = lat;
        }

        /** Число как doubleValue узла дерева. */
        double coordinate() {
            space();
            int from = at;
            if (number()) {
                return integer(from);
            }
            double value = decimal(bytes, from, at);
            if (Double.isNaN(value)) {
                throw NOT_FAST;
            }
            return value;
        }

        /** Скаляр или объект из скаляров, как узел readTree. */
        JsonNode value() {
            space();
            if (at >= end) {
                throw NOT_FAST;
            }
            switch (bytes[at]) {
                case '{': {
                    ObjectNode node = MAPPER.createObjectNode();
                    at++;
                    if (next('}')) {
                        return node;
                    }
                    do {
                        String name = key();
                        space();
                        if (at < end && (bytes[at] == '{' || bytes[at] == '[')) {
                            throw NOT_FAST;
                        }
                        node.set(name, value());
                    } while (next(','));
                    expect('}');
                    return node;
                }
                case '"':
                    return TextNode.valueOf(string());
                case 't':
                    literal("true");
                    return BooleanNode.TRUE;
                case 'f':
                    literal("false");
                    return BooleanNode.FALSE;
                case 'n':
                    literal("null");
                    return NullNode.getInstance();
                default: {
                    int from = at;
                    if (number()) {
                        long value = integer(from);
                        return value == (int) value ? IntNode.valueOf((int) value) : LongNode.valueOf(value);
                    }
                    double value = decimal(bytes, from, at);
                    if (Double.isNaN(value)) {
                        throw NOT_FAST;
                    }
                    return DoubleNode.valueOf(value);
                }
            }
        }

        /** Любое значение JSON с проверкой синтаксиса. */
        void skip() {
            space();
            if (at >= end) {
                throw NOT_FAST;
            }
            switch (bytes[at]) {
                case '{':
                    at++;
                    if (!next('}')) {
                        do {
                            key();
                            skip();
                        } while (next(','));
                        expect('}');
                    }
                    return;
                case '[':
                    at++;
                    if (!next(']')) {
                        do {
                            skip();
                        } while (next(','));
                        expect(']');
                    }
                    return;
                case '"':
                    stringEnd();
                    return;
                case 't':
                    literal("true");
                    return;
                case 'f':
                    literal("false");
                    return;
                case 'n':
                    literal("null");
                    return;
                default:
                    number();
            }
        }

        /** Число по грамматике JSON с at; true — целое, без дроби и степени. */
        boolean number() {
            if (at < end && bytes[at] == '-') {
                at++;
            }
            if (at < end && bytes[at] == '0') {
                at++;
            } else if (at < end && bytes[at] >= '1' && bytes[at] <= '9') {
                digits();
            } else {
                throw NOT_FAST;
            }
            boolean integral = true;
            if (at < end && bytes[at] == '.') {
                integral = false;
                at++;
                if (digits() == 0) {
                    throw NOT_FAST;
                }
            }
            if (at < end && (bytes[at] == 'e' || bytes[at] == 'E')) {
                integral = false;
                at++;
                if (at < end && (bytes[at] == '+' || bytes[at] == '-')) {
                    at++;
                }
                if (digits() == 0) {
                    throw NOT_FAST;
                }
            }
            return integral;
        }

        int digits() {
            int from = at;
            while (at < end && bytes[at] >= '0' && bytes[at] <= '9') {
                at++;
            }
            return at - from;
        }

        /** Целое с from до at, до 18 цифр — точно в long; длиннее — пусть читает Jackson. */
        long integer(int from) {
            boolean negative = bytes[from] == '-';
            int i = negative ? from + 1 : from;
            if (at - i > 18) {
                throw NOT_FAST;
            }
            long value = 0;
            for (; i < at; i++) {
                value = value * 10 + (bytes[i] - '0');
            }
            return negative ? -value : value;
        }

        void literal(String word) {
            for (int i = 0; i < word.length(); i++, at++) {
                if (at >= end || bytes[at] != word.charAt(i)) {
                    throw NOT_FAST;
                }
            }
        }

        String key() {
            space();
            String name = string();
            expect(':');
            return name;
        }

        String string() {
            int from = at + 1;
            stringEnd();
            int to = at - 1;
            for (int i = 0; i < KNOWN_BYTES.length; i++) {
                if (KNOWN_BYTES[i].length == to - from && Arrays.equals(bytes, from, to, KNOWN_BYTES[i], 0, to - from)) {
                    return KNOWN.get(i);
                }
            }
            return new String(bytes, from, to - from, StandardCharsets.ISO_8859_1);
        }

        /** Строка ASCII без экранирования и управляющих символов; остальное читает Jackson. */
        void stringEnd() {
            if (at >= end || bytes[at] != '"') {
                throw NOT_FAST;
            }
            for (at++; at < end; at++) {
                byte c = bytes[at];
                if (c == '"') {
                    at++;
                    return;
                }
                // отрицательные байты — не ASCII
                if (c < 0x20 || c == '\\') {
                    throw NOT_FAST;
                }
            }
            throw NOT_FAST;
        }

        void expect(char c) {
            if (!next(c)) {
                throw NOT_FAST;
            }
        }

        boolean next(char c) {
            space();
            if (at < end && bytes[at] == c) {
                at++;
                return true;
            }
            return false;
        }

        void space() {
            at = GeoJsonStreamReader.space(bytes, at, end);
        }

        /** Разбор дошёл ровно до конца среза. */
        void end() {
            space();
            if (at != end) {
                throw NOT_FAST;
            }
        }
    }

    /**
     * Double.parseDouble числа JSON из байтов [from, to) там, где JDK считает одной операцией: до 15 значащих цифр и
     * степень десяти не больше 22 по модулю — мантисса умножается или делится на точную степень десяти, как в
     * FloatingDecimal. Иначе NaN, и число читает Jackson.
     */
    static double decimal(byte[] text, int from, int to) {
        int i = from;
        boolean negative = text[i] == '-';
        if (negative) {
            i++;
        }
        long mantissa = 0;
        int digits = 0;
        int zeros = 0;
        int fraction = 0;
        boolean point = false;
        for (; i < to; i++) {
            byte c = text[i];
            if (c == '.') {
                point = true;
                continue;
            }
            if (c < '0' || c > '9') {
                break;
            }
            if (point) {
                fraction++;
            }
            if (c == '0') {
                // нули после значащей цифры войдут в мантиссу, только если за ними будет ещё значащая
                if (digits > 0) {
                    zeros++;
                }
                continue;
            }
            digits += zeros + 1;
            if (digits > 15) {
                return Double.NaN;
            }
            for (; zeros > 0; zeros--) {
                mantissa *= 10;
            }
            mantissa = mantissa * 10 + (c - '0');
        }
        int exponent = 0;
        if (i < to) {
            // e или E, знак и не больше трёх цифр
            i++;
            boolean minus = text[i] == '-';
            if (minus || text[i] == '+') {
                i++;
            }
            if (to - i > 3) {
                return Double.NaN;
            }
            for (; i < to; i++) {
                exponent = exponent * 10 + (text[i] - '0');
            }
            if (minus) {
                exponent = -exponent;
            }
        }
        if (digits == 0) {
            return Double.NaN;
        }
        int exp = exponent - fraction + zeros;
        double value = mantissa;
        if (exp > 0 && exp < TENS.length) {
            value *= TENS[exp];
        } else if (exp < 0 && -exp < TENS.length) {
            value /= TENS[-exp];
        } else if (exp != 0) {
            return Double.NaN;
        }
        return negative ? -value : value;
    }

    /** Геометрия по дереву значения geometry фичи; null — ключа нет. */
    private static Parsed parseGeometry(JsonNode raw, List<String> allowed) {
        if (raw == null || raw.isNull()) {
            return new Parsed(allowed, null, "нет геометрии");
        }
        String type = raw.path("type").textValue();
        return checked(type, () -> parse(type, raw.path("coordinates")), allowed);
    }

    /** Тип, валидность и перевод в UTM геометрии, которую строит geometry; ошибки разбора — IllegalArgumentException. */
    private static Parsed checked(String type, Supplier<Geometry> geometry, List<String> allowed) {
        if (type == null || !allowed.contains(type)) {
            return new Parsed(allowed, null, "ожидается геометрия " + String.join(" или ", allowed) + ", получено " + type);
        }
        try {
            Geometry parsed = geometry.get();
            TopologyValidationError error = new IsValidOp(parsed).getValidationError();
            if (error != null) {
                return new Parsed(allowed, null, "невалидная геометрия: " + INVALID_REASONS.getOrDefault(
                        error.getErrorType(), error.getMessage()) + " у точки " + error.getCoordinate());
            }
            return new Parsed(allowed, Projector.toUtm(parsed), null);
        } catch (IllegalArgumentException e) {
            return new Parsed(allowed, null, "некорректные координаты " + type + ": " + e.getMessage());
        } catch (ProjectionException e) {
            return new Parsed(allowed, null, "координаты не переводятся в EPSG:32637");
        }
    }

    /**
     * Какую геометрию запросит Scan.feature у этой фичи, по тем же полям; null — никакую. Если догадка разойдётся
     * с feature, геометрия просто построится на месте: Scan.geometry сверяет список допустимых типов.
     */
    private static List<String> expectedGeometry(JsonNode node, boolean skipObstacles) {
        JsonNode props = node.get("properties");
        if (props == null || !props.isObject()) {
            return null;
        }
        String objectType = props.path("object_type").textValue();
        if (objectType == null) {
            return null;
        }
        switch (objectType) {
            case SOURCE:
            case HEAT_CHAMBER:
            case CONNECTION_POINT:
                return POINT;
            case HEAT_NETWORK:
                return LINE;
            case OKS_FUTURE:
                return POLYGONS;
            case OKS_EXISTING:
                return skipObstacles ? null : POLYGONS;
            case RESTRICTION: {
                if (skipObstacles) {
                    return null;
                }
                String restrictionType = props.path("restriction_type").textValue();
                if (BUILDING.equals(restrictionType)) {
                    return POLYGONS;
                }
                return ANY_RESTRICTION;
            }
            default:
                return null;
        }
    }

    /** Индекс за последним переводом строки в [from, to) или -1. */
    private static int lastLine(byte[] bytes, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (bytes[i] == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    /** Первый байт с from, который не пробельный символ JSON. */
    private static int space(byte[] bytes, int from, int limit) {
        while (from < limit && (bytes[from] == ' ' || bytes[from] == '\n' || bytes[from] == '\r' || bytes[from] == '\t')) {
            from++;
        }
        return from;
    }

    /**
     * Конец объекта с bytes[from] = '{' по глубине скобок с учётом строк: индекс за закрывающей скобкой или -1, если
     * до limit объект не закрылся. Виды скобок не сверяются, это делает разбор среза.
     */
    private static int objectEnd(byte[] bytes, int from, int limit) {
        int depth = 0;
        boolean string = false;
        for (int i = from; i < limit; i++) {
            byte b = bytes[i];
            if (string) {
                if (b == '\\') {
                    i++;
                } else if (b == '"') {
                    string = false;
                }
            } else if (b == '"') {
                string = true;
            } else if (b == '{' || b == '[') {
                depth++;
            } else if ((b == '}' || b == ']') && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
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
