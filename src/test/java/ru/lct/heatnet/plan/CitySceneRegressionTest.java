package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineSegment;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Result;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class CitySceneRegressionTest {
    private final Rules rules = Rules.load();

    @Test
    void finalPieceKeepsClearanceFromTieInNetwork() {
        InputData input = read("moscow-tie-clearance.geojson");

        Result result = new VariantEnumerator(input, rules).run();

        for (Variant variant : result.getVariants()) {
            for (NewSegment segment : toConnections(variant, input)) {
                Coordinate[] c = segment.getGeometry().getCoordinates();
                LineSegment last = new LineSegment(c[c.length - 2], c[c.length - 1]);
                for (NetworkSegment pipe : input.getSegments()) {
                    double d = last.toGeometry(PlanFixture.GEOMETRY).distance(pipe.getGeometry());
                    assertTrue(c.length == 2 || d >= 1.0, segment.getId() + " финальный участок в " + d + " м от " + pipe.getId());
                }
            }
        }
    }

    @Test
    void cityScanTieStaysInsideDistrictCorridor() {
        InputData input = read("moscow-city-corridor.geojson");

        Result result = new VariantEnumerator(input, rules).city();

        List<ExistingOks> foreign = input.getExistingOks().stream()
                .filter(oks -> input.getConnectionPoints().stream().noneMatch(cp -> oks.getGeometry().covers(cp.getGeometry())))
                .collect(Collectors.toList());
        for (Variant variant : result.getVariants()) {
            for (NewSegment segment : variant.getSegments()) {
                for (ExistingOks oks : foreign) {
                    assertFalse(segment.getGeometry().intersects(oks.getGeometry()), segment.getId() + " идёт сквозь " + oks.getId());
                }
            }
        }
    }

    private InputData read(String name) {
        return VariantEnumerator.read(Path.of("src/test/resources/cities", name), rules);
    }

    private static List<NewSegment> toConnections(Variant variant, InputData input) {
        List<String> ids = input.getConnectionPoints().stream().map(ConnectionPoint::getId).collect(Collectors.toList());
        return variant.getSegments().stream().filter(segment -> ids.contains(segment.getEndNodeId())).collect(Collectors.toList());
    }
}
