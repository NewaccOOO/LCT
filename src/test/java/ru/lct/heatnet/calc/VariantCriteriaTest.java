package ru.lct.heatnet.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.TechnicalNode;
import ru.lct.heatnet.model.TieIn;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.model.VariantSummary;
import ru.lct.heatnet.rules.Rules;

class VariantCriteriaTest {
    private static final int DN = 65;
    private final Rules rules = Rules.load();
    private final GeometryFactory factory = new GeometryFactory();
    private final double rub = rules.diameter(DN).getNewRubM();

    @Test
    void countsCrossingsTurnsSurchargeAndUnitCost() {
        // врезка в (0, 0), переход дороги x 40..60 спецучастком между узлами, в конце излом 53° к точке подключения
        Restriction road = new Restriction("road-1", factory.toGeometry(new Envelope(40, 60, -100, 100)), "road");
        Restriction gas = new Restriction("gas-1", line(-100, 200, 300, 200), "gas_pipeline");
        NetworkSegment pipe = new NetworkSegment("hn-1", line(-50, 0, 0, 0), 100, 10.0, "src");
        FutureOks oks = new FutureOks("oks-1", factory.createPoint(new Coordinate(120, 40)), 5, null);
        InputData input = new InputData(null, List.of(pipe), List.of(), List.of(oks), List.of(), List.of(),
                List.of(road, gas), List.of(), List.of());

        NewSegment toRoad = segment("s1", "tie-1", "n-1", line(0, 0, 37, 0), 37, "base", 1);
        NewSegment crossing = segment("s2", "n-1", "n-2", line(37, 0, 63, 0), 26, "special", 1.6);
        NewSegment toOks = segment("s3", "n-2", "cp-1", factory.createLineString(new Coordinate[] {
                new Coordinate(63, 0), new Coordinate(90, 0), new Coordinate(120, 40)}), 77, "base", 1.5);
        double construction = toRoad.getCost() + crossing.getCost() + toOks.getCost();
        VariantSummary summary = new VariantSummary("summary_1", "1", 1, construction, 3_000_000, 5_000_000, 0, 0, 0,
                construction + 8_000_000, 140, 0, 140, 1.0, List.of());
        Variant variant = new Variant("1", List.of(toRoad, crossing, toOks),
                List.of(new TieIn("tie-1", "1", factory.createPoint(new Coordinate(0, 0)), "hn-1", "heat_network", 100, DN, 5_000_000)),
                List.of(), List.of(new NewChamber("ch-1", "1", factory.createPoint(new Coordinate(0, 0)), 100, 3_000_000)),
                List.of(), List.of(new TechnicalNode("n-1", "1", factory.createPoint(new Coordinate(37, 0))),
                        new TechnicalNode("n-2", "1", factory.createPoint(new Coordinate(63, 0)))),
                summary);

        Map<String, Object> criteria = new VariantCriteria(input, rules).of(variant);

        assertEquals(1, criteria.get("connected_oks"));
        assertEquals(new BigDecimal("5.000"), criteria.get("connected_flow_tph"));
        assertEquals(1, criteria.get("tie_ins"));
        assertEquals(1, criteria.get("new_chambers"));
        assertEquals(2, criteria.get("technical_nodes"));
        assertEquals(1, criteria.get("special_segments"));
        assertEquals(new BigDecimal("26.00"), criteria.get("special_length_m"));
        assertEquals(Map.of("road", 1), criteria.get("crossed_objects"));
        assertEquals(1, criteria.get("turns"));
        assertEquals(1, criteria.get("nonstandard_turns"));
        // надбавка: 26 м × 0,6 за спецпереход и 77 м × 0,5 за нестандартный угол
        assertEquals(BigDecimal.valueOf(26 * rub * 0.6 + 77 * rub * 0.5).setScale(2, java.math.RoundingMode.HALF_UP),
                criteria.get("surcharge_cost"));
        assertEquals(new BigDecimal("0.000"), criteria.get("reconstruction_share"));
        assertEquals(BigDecimal.valueOf(construction + 8_000_000).setScale(2, java.math.RoundingMode.HALF_UP),
                criteria.get("cost_per_oks"));
    }

    private NewSegment segment(String id, String from, String to, LineString line, double length, String laying, double k) {
        return new NewSegment(id, "1", line, from, to, 5, DN, length, laying, null, null,
                CostCalculator.round2(length * rub * k));
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return factory.createLineString(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
