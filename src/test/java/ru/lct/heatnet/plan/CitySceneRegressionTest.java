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

/**
 * Малые сцены Москвы 18.09 (data/cities/moscow/input_1809.geojson, квадрат 500 м вокруг точки, сеть без
 * upstream_object_id), на которых ветка real-cities нарушала обязательные отступы (полный check18, 28.09.2026).
 */
class CitySceneRegressionTest {
    private final Rules rules = Rules.load();

    @Test
    void finalPieceKeepsClearanceFromTieInNetwork() {
        // cp-w1464206668: камера врезки на hn-15427, выход из здания лежал в 0,36 м от той же трубы (B11). Отступ до сети
        // врезки снимается только на звене от самой врезки, финальный участок выход–точка его держит
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
        // cp-w754085639 без трассы от ближних кандидатов: запасной поиск брал врезку за коридором района, где роутер
        // не видит зданий, и трасса шла сквозь офисное здание w46353971 (B3) и через дорогу под 26,6° (B9)
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

    /** Участки, которые кончаются в точке подключения. */
    private static List<NewSegment> toConnections(Variant variant, InputData input) {
        List<String> ids = input.getConnectionPoints().stream().map(ConnectionPoint::getId).collect(Collectors.toList());
        return variant.getSegments().stream().filter(segment -> ids.contains(segment.getEndNodeId())).collect(Collectors.toList());
    }
}
