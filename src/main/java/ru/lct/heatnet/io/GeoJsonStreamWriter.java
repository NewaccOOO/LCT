package ru.lct.heatnet.io;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.model.DepthCrossing;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;

/**
 * Потоковая запись результата в GeoJSON по разделу 7 технического приложения от 18.09.2026: одна фича за раз, файл
 * подменяется атомарно. Ссылки на объекты входа с числовым id пишутся числом.
 */
public class GeoJsonStreamWriter {
    private static final int COORDINATE_SCALE = 9;
    /**
     * Режим с глубиной: координаты до ~0,01 мкм. Профиль режет участки на куски от 1 см, и при 9 знаках (~0,1 мм) длина
     * куска по геометрии расходится с length на доли процента — уклон по геометрии выходил круче 0,10.
     */
    private static final int DEPTH_COORDINATE_SCALE = 13;
    private static final int MONEY_SCALE = 2;
    private static final int LENGTH_SCALE = 2;
    private static final int FLOW_SCALE = 3;
    /** Глубина до мм. */
    private static final int DEPTH_SCALE = 6;
    /** score с четырьмя знаками, как в примере п. 7.3 приложения 18.09 (0,6913). */
    private static final int SCORE_SCALE = 4;

    private static final JsonFactory JSON = new JsonFactory();

    public static void write(Result result, Path path) {
        Path target = path.toAbsolutePath();
        try {
            Path temp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
            try {
                try (JsonGenerator json = JSON.createGenerator(Files.newOutputStream(temp))) {
                    json.writeStartObject();
                    json.writeStringField("type", "FeatureCollection");
                    json.writeArrayFieldStart("features");
                    for (Variant variant : result.getVariants()) {
                        variant(json, variant, result.getNumericIds());
                    }
                    json.writeEndArray();
                    json.writeEndObject();
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось записать результат в " + target, e);
        }
    }

    private static void variant(JsonGenerator json, Variant variant, Set<String> numericIds) throws IOException {
        int scale = variant.getSegments().stream().anyMatch(s -> s.getDepthStart() != null)
                ? DEPTH_COORDINATE_SCALE : COORDINATE_SCALE;
        for (NewChamber chamber : variant.getChambers()) {
            start(json, chamber.getGeometry(), chamber.getId(), "heat_chamber", chamber.getVariantId(), scale);
            json.writeNumberField("diameter", chamber.getDiameter());
            decimal(json, "cost", chamber.getCost(), MONEY_SCALE);
            if (chamber.getDepth() != null) {
                // режим с глубиной: все трубы входят в камеру на этой глубине; не 3,0 м — сдвиг, без него профиль с
                // уклоном 0,10 не помещается (эксперты 16.10: в камеру — на заданной глубине)
                decimal(json, "depth_m", chamber.getDepth(), DEPTH_SCALE);
                if (Math.abs(chamber.getDepth() - 3.0) > 0.0105) {
                    json.writeBooleanField("depth_shifted", true);
                }
            }
            end(json);
        }
        for (NewSegment segment : variant.getSegments()) {
            start(json, segment.getGeometry(), segment.getId(), "heat_network", segment.getVariantId(), scale);
            id(json, "start_node_id", segment.getStartNodeId(), numericIds);
            id(json, "end_node_id", segment.getEndNodeId(), numericIds);
            decimal(json, "flow_tph", segment.getFlowTph(), FLOW_SCALE);
            json.writeNumberField("diameter", segment.getDiameter());
            // в режиме с глубиной длина до микрона: уклон по атрибутам length и depth_* не круче 0,10 и на коротком куске
            decimal(json, "length", segment.getLength(), segment.getDepthStart() != null ? DEPTH_SCALE : LENGTH_SCALE);
            json.writeStringField("laying_method", segment.getLayingMethod());
            // Глубина относится к дополнительному режиму, в 2D-выходе всегда явный null.
            depth(json, "depth_start", segment.getDepthStart());
            depth(json, "depth_end", segment.getDepthEnd());
            decimal(json, "cost", segment.getCost(), MONEY_SCALE);
            if (segment.getDepthCrossings() != null && !segment.getDepthCrossings().isEmpty()) {
                json.writeArrayFieldStart("depth_crossings");
                for (DepthCrossing crossing : segment.getDepthCrossings()) {
                    json.writeStartObject();
                    json.writeStringField("type", crossing.getType());
                    json.writeStringField("position", crossing.getPosition());
                    decimal(json, "vertical_clearance_m", crossing.getClearanceM(), DEPTH_SCALE);
                    json.writeEndObject();
                }
                json.writeEndArray();
            }
            if (segment.isManualReview()) {
                json.writeBooleanField("manual_review", true);
            }
            end(json);
        }
        for (TechnicalNode node : variant.getNodes()) {
            start(json, node.getGeometry(), node.getId(), "technical_node", node.getVariantId(), scale);
            if (node.getReason() != null) {
                json.writeStringField("reason", node.getReason());
            }
            end(json);
        }
        VariantSummary summary = variant.getSummary();
        start(json, null, summary.getId(), "variant_summary", summary.getVariantId());
        json.writeNumberField("rank", summary.getRank());
        decimal(json, "construction_cost", summary.getConstructionCost(), MONEY_SCALE);
        decimal(json, "chamber_construction_cost", summary.getChamberConstructionCost(), MONEY_SCALE);
        json.writeNumberField("existing_chamber_tie_in_count", summary.getExistingChamberTieInCount());
        decimal(json, "existing_chamber_tie_in_cost", summary.getExistingChamberTieInCost(), MONEY_SCALE);
        decimal(json, "unconnected_penalty", summary.getUnconnectedPenalty(), MONEY_SCALE);
        decimal(json, "calculated_cost", summary.getCalculatedCost(), MONEY_SCALE);
        decimal(json, "new_network_length", summary.getNewNetworkLength(), LENGTH_SCALE);
        decimal(json, "score", summary.getScore(), SCORE_SCALE);
        json.writeArrayFieldStart("unconnected_oks_ids");
        for (String oksId : summary.getUnconnectedOksIds()) {
            if (numericIds.contains(oksId)) {
                json.writeNumber(oksId);
            } else {
                json.writeString(oksId);
            }
        }
        json.writeEndArray();
        end(json);
    }

    private static void depth(JsonGenerator json, String field, Double value) throws IOException {
        if (value == null) {
            json.writeNullField(field);
        } else {
            decimal(json, field, value, DEPTH_SCALE);
        }
    }

    /** Ссылка на узел: число, если так был записан id объекта входа. */
    private static void id(JsonGenerator json, String field, String value, Set<String> numericIds) throws IOException {
        json.writeFieldName(field);
        if (numericIds.contains(value)) {
            json.writeNumber(value);
        } else {
            json.writeString(value);
        }
    }

    private static void start(JsonGenerator json, Geometry geometry, String id, String objectType, String variantId)
            throws IOException {
        start(json, geometry, id, objectType, variantId, COORDINATE_SCALE);
    }

    private static void start(JsonGenerator json, Geometry geometry, String id, String objectType, String variantId,
            int scale) throws IOException {
        json.writeStartObject();
        json.writeStringField("type", "Feature");
        if (geometry == null) {
            json.writeNullField("geometry");
        } else {
            geometry(json, Projector.toWgs(geometry), scale);
        }
        json.writeObjectFieldStart("properties");
        json.writeStringField("id", id);
        json.writeStringField("object_type", objectType);
        json.writeStringField("variant_id", variantId);
    }

    private static void end(JsonGenerator json) throws IOException {
        json.writeEndObject();
        json.writeEndObject();
        // фича на строке: выход города в гигабайты читается построчно, без разбора файла целиком
        json.writeRaw('\n');
    }

    // В выходной модели только Point и LineString.
    private static void geometry(JsonGenerator json, Geometry wgs, int scale) throws IOException {
        json.writeObjectFieldStart("geometry");
        json.writeStringField("type", wgs.getGeometryType());
        json.writeFieldName("coordinates");
        if (wgs instanceof Point) {
            position(json, wgs.getCoordinate(), scale);
        } else {
            json.writeStartArray();
            for (Coordinate coordinate : wgs.getCoordinates()) {
                position(json, coordinate, scale);
            }
            json.writeEndArray();
        }
        json.writeEndObject();
    }

    private static void position(JsonGenerator json, Coordinate coordinate, int scale) throws IOException {
        json.writeStartArray();
        json.writeNumber(round(coordinate.x, scale));
        json.writeNumber(round(coordinate.y, scale));
        json.writeEndArray();
    }

    private static void decimal(JsonGenerator json, String field, double value, int scale) throws IOException {
        json.writeFieldName(field);
        json.writeNumber(round(value, scale));
    }

    // Десятичное HALF_UP от кратчайшей записи double: 1.005 даёт 1.01, а Math.round дал бы 1.00.
    private static String round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
