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
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
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

/** Потоковая запись результата в GeoJSON по разделу 13 CONSTRAINTS.md: одна фича за раз, файл подменяется атомарно. */
public class GeoJsonStreamWriter {
    private static final int COORDINATE_SCALE = 9;
    private static final int MONEY_SCALE = 2;
    private static final int LENGTH_SCALE = 2;
    private static final int FLOW_SCALE = 3;
    private static final int SCORE_SCALE = 3;

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
                        variant(json, variant);
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

    private static void variant(JsonGenerator json, Variant variant) throws IOException {
        for (TieIn tieIn : variant.getTieIns()) {
            start(json, tieIn.getGeometry(), tieIn.getId(), "tie_in", tieIn.getVariantId());
            json.writeStringField("existing_object_id", tieIn.getExistingObjectId());
            json.writeStringField("existing_object_type", tieIn.getExistingObjectType());
            json.writeNumberField("existing_diameter", tieIn.getExistingDiameter());
            json.writeNumberField("required_diameter", tieIn.getRequiredDiameter());
            decimal(json, "cost", tieIn.getCost(), MONEY_SCALE);
            end(json);
        }
        for (NewChamber chamber : variant.getChambers()) {
            start(json, chamber.getGeometry(), chamber.getId(), "heat_chamber", chamber.getVariantId());
            json.writeNumberField("diameter", chamber.getDiameter());
            decimal(json, "cost", chamber.getCost(), MONEY_SCALE);
            end(json);
        }
        for (NewSegment segment : variant.getSegments()) {
            start(json, segment.getGeometry(), segment.getId(), "heat_network", segment.getVariantId());
            json.writeStringField("start_node_id", segment.getStartNodeId());
            json.writeStringField("end_node_id", segment.getEndNodeId());
            decimal(json, "flow_tph", segment.getFlowTph(), FLOW_SCALE);
            json.writeNumberField("diameter", segment.getDiameter());
            decimal(json, "length", segment.getLength(), LENGTH_SCALE);
            json.writeStringField("laying_method", segment.getLayingMethod());
            // Глубина относится к дополнительной задаче (NG-2), в 2D-выходе всегда явный null.
            json.writeNullField("depth_start");
            json.writeNullField("depth_end");
            decimal(json, "cost", segment.getCost(), MONEY_SCALE);
            end(json);
        }
        for (TechnicalNode node : variant.getNodes()) {
            start(json, node.getGeometry(), node.getId(), "technical_node", node.getVariantId());
            end(json);
        }
        for (Reconstruction part : variant.getReconstructions()) {
            start(json, part.getGeometry(), part.getId(), "heat_network_reconstruction", part.getVariantId());
            json.writeStringField("existing_object_id", part.getExistingObjectId());
            decimal(json, "existing_flow_tph", part.getExistingFlowTph(), FLOW_SCALE);
            decimal(json, "added_flow_tph", part.getAddedFlowTph(), FLOW_SCALE);
            decimal(json, "calculated_flow_tph", part.getCalculatedFlowTph(), FLOW_SCALE);
            json.writeNumberField("existing_diameter", part.getExistingDiameter());
            json.writeNumberField("required_diameter", part.getRequiredDiameter());
            decimal(json, "length", part.getLength(), LENGTH_SCALE);
            decimal(json, "cost", part.getCost(), MONEY_SCALE);
            end(json);
        }
        for (ChamberReconstruction chamber : variant.getChamberReconstructions()) {
            start(json, chamber.getGeometry(), chamber.getId(), "heat_chamber_reconstruction", chamber.getVariantId());
            json.writeStringField("existing_object_id", chamber.getExistingObjectId());
            json.writeNumberField("existing_diameter", chamber.getExistingDiameter());
            json.writeNumberField("required_diameter", chamber.getRequiredDiameter());
            decimal(json, "cost", chamber.getCost(), MONEY_SCALE);
            end(json);
        }
        VariantSummary summary = variant.getSummary();
        start(json, null, summary.getId(), "variant_summary", summary.getVariantId());
        json.writeNumberField("rank", summary.getRank());
        decimal(json, "construction_cost", summary.getConstructionCost(), MONEY_SCALE);
        decimal(json, "chamber_construction_cost", summary.getChamberConstructionCost(), MONEY_SCALE);
        decimal(json, "tie_in_cost", summary.getTieInCost(), MONEY_SCALE);
        decimal(json, "reconstruction_cost", summary.getReconstructionCost(), MONEY_SCALE);
        decimal(json, "chamber_reconstruction_cost", summary.getChamberReconstructionCost(), MONEY_SCALE);
        decimal(json, "unconnected_penalty", summary.getUnconnectedPenalty(), MONEY_SCALE);
        decimal(json, "calculated_cost", summary.getCalculatedCost(), MONEY_SCALE);
        decimal(json, "new_network_length", summary.getNewNetworkLength(), LENGTH_SCALE);
        decimal(json, "reconstruction_length", summary.getReconstructionLength(), LENGTH_SCALE);
        decimal(json, "length", summary.getLength(), LENGTH_SCALE);
        decimal(json, "score", summary.getScore(), SCORE_SCALE);
        json.writeArrayFieldStart("unconnected_oks_ids");
        for (String oksId : summary.getUnconnectedOksIds()) {
            json.writeString(oksId);
        }
        json.writeEndArray();
        end(json);
    }

    private static void start(JsonGenerator json, Geometry geometry, String id, String objectType, String variantId)
            throws IOException {
        json.writeStartObject();
        json.writeStringField("type", "Feature");
        if (geometry == null) {
            json.writeNullField("geometry");
        } else {
            geometry(json, Projector.toWgs(geometry));
        }
        json.writeObjectFieldStart("properties");
        json.writeStringField("id", id);
        json.writeStringField("object_type", objectType);
        json.writeStringField("variant_id", variantId);
    }

    private static void end(JsonGenerator json) throws IOException {
        json.writeEndObject();
        json.writeEndObject();
    }

    // В выходной модели только Point и LineString.
    private static void geometry(JsonGenerator json, Geometry wgs) throws IOException {
        json.writeObjectFieldStart("geometry");
        json.writeStringField("type", wgs.getGeometryType());
        json.writeFieldName("coordinates");
        if (wgs instanceof Point) {
            position(json, wgs.getCoordinate());
        } else {
            json.writeStartArray();
            for (Coordinate coordinate : wgs.getCoordinates()) {
                position(json, coordinate);
            }
            json.writeEndArray();
        }
        json.writeEndObject();
    }

    private static void position(JsonGenerator json, Coordinate coordinate) throws IOException {
        json.writeStartArray();
        json.writeNumber(round(coordinate.x, COORDINATE_SCALE));
        json.writeNumber(round(coordinate.y, COORDINATE_SCALE));
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
