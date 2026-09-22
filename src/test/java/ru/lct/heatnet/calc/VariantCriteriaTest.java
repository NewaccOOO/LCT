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
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.model.NewChamber;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.model.TechnicalNode;
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
                List.of(road, gas), List.of(), List.of(), java.util.Set.of());

        NewSegment toRoad = segment("s1", "tie-1", "n-1", line(0, 0, 37, 0), 37, "base", 1);
        NewSegment crossing = segment("s2", "n-1", "n-2", line(37, 0, 63, 0), 26, "special", 1.6);
        NewSegment toOks = segment("s3", "n-2", "cp-1", factory.createLineString(new Coordinate[] {
                new Coordinate(63, 0), new Coordinate(90, 0), new Coordinate(120, 40)}), 77, "base", 1.5);
        double construction = toRoad.getCost() + crossing.getCost() + toOks.getCost();
        VariantSummary summary = new VariantSummary("summary_1", "1", 1, construction + 8_000_000, 3_000_000, 1, 5_000_000, 0,
                construction + 8_000_000, 140, 1.0, List.of());
        Variant variant = new Variant("1", List.of(toRoad, crossing, toOks),
                List.of(new NewChamber("ch-1", "1", factory.createPoint(new Coordinate(0, 0)), 100, 3_000_000)),
                List.of(new TechnicalNode("n-1", "1", factory.createPoint(new Coordinate(37, 0))),
                        new TechnicalNode("n-2", "1", factory.createPoint(new Coordinate(63, 0)))),
                summary);

        Map<String, Object> criteria = new VariantCriteria(input, rules).of(variant);

        assertEquals(1, criteria.get("connected_oks"));
        assertEquals(new BigDecimal("5.000"), criteria.get("connected_flow_tph"));
        assertEquals(1, criteria.get("existing_chamber_tie_ins"));
        assertEquals(1, criteria.get("new_chambers"));
        assertEquals(2, criteria.get("technical_nodes"));
        assertEquals(1, criteria.get("special_segments"));
        assertEquals(new BigDecimal("26.00"), criteria.get("special_length_m"));
        assertEquals(Map.of("road", 1), criteria.get("crossed_objects"));
        assertEquals(1, criteria.get("turns"));
        assertEquals(new BigDecimal("53.1"), criteria.get("max_turn_deg"));
        // надбавка: 26 м × 0,6 за спецпереход и 77 м × 0,5 у последнего участка, посчитанного с коэффициентом 1,5
        assertEquals(BigDecimal.valueOf(26 * rub * 0.6 + 77 * rub * 0.5).setScale(2, java.math.RoundingMode.HALF_UP),
                criteria.get("surcharge_cost"));
        assertEquals(BigDecimal.valueOf(construction + 8_000_000).setScale(2, java.math.RoundingMode.HALF_UP),
                criteria.get("cost_per_oks"));
    }

    @Test
    void explainsWhyOksIsUnconnected() {
        // ОКС a в кольце воды, точка b внутри парка, у c нет точки подключения, d на открытом месте
        Restriction water = new Restriction("water-1", factory.toGeometry(new Envelope(-50, 50, -50, 50))
                .difference(factory.toGeometry(new Envelope(-20, 20, -20, 20))), "water");
        Restriction park = new Restriction("park-1", factory.toGeometry(new Envelope(200, 260, -30, 30)), "park");
        List<FutureOks> oks = List.of(oks("oks-a"), oks("oks-b"), oks("oks-c"), oks("oks-d"));
        List<ConnectionPoint> points = List.of(point("oks-a", 0, 0), point("oks-b", 230, 0), point("oks-d", 600, 0));
        InputData input = new InputData(null, List.of(), List.of(), oks, points, List.of(), List.of(water, park),
                List.of(), List.of(), java.util.Set.of());
        VariantSummary summary = new VariantSummary("summary_1", "1", 1, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of("oks-a", "oks-b", "oks-c", "oks-d"));
        Variant variant = new Variant("1", List.of(), List.of(), List.of(), summary);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) new VariantCriteria(input, rules).of(variant)
                .get("unconnected_reasons");

        assertEquals(List.of("enclosed", "inside_forbidden_zone", "no_connection_point", "no_route"),
                reasons.stream().map(r -> r.get("reason")).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("water-1"), reasons.get(0).get("object_ids"));
        assertEquals(List.of("park-1"), reasons.get(1).get("object_ids"));
    }

    private FutureOks oks(String id) {
        return new FutureOks(id, factory.createPoint(new Coordinate(0, 0)), 5, null);
    }

    private ConnectionPoint point(String oksId, double x, double y) {
        return new ConnectionPoint("cp-" + oksId, factory.createPoint(new Coordinate(x, y)), oksId);
    }

    private NewSegment segment(String id, String from, String to, LineString line, double length, String laying, double k) {
        return new NewSegment(id, "1", line, from, to, 5, DN, length, laying, null, null,
                CostCalculator.round2(length * rub * k));
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return factory.createLineString(new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
