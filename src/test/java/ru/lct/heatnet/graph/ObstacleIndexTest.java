package ru.lct.heatnet.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.Restriction;
import ru.lct.heatnet.rules.Rules;

class ObstacleIndexTest {
    private static final Envelope AREA = new Envelope(0, 200, 0, 200);
    private static final int DN = 100;
    private final Rules rules = Rules.load();
    private final GeometryFactory factory = new GeometryFactory();

    @Test
    void returnsNearObjectsInInputOrderAndSkipsFarOnes() {
        Restriction inside = park("inside", 50, 50);
        Restriction far = park("far", 5000, 5000);
        // в 1 м за краем области: отступ парка и ширина трубы достают до области
        Restriction edge = park("edge", 201, 100);
        Restriction second = park("second", 120, 20);
        ObstacleIndex index = new ObstacleIndex(input(List.of(inside, far, edge, second)), rules);

        assertEquals(List.of(inside, edge, second), index.restrictions(AREA));
    }

    @Test
    void farObjectsDoNotChangeObstacleSet() {
        List<Restriction> near = List.of(park("a", 30, 30), park("b", 100, 60), park("c", 60, 140), park("d", 199, 199));
        // между ближними объектами по 250 дальних, порядок ближних прежний
        List<Restriction> withFar = new ArrayList<>();
        for (Restriction restriction : near) {
            for (int i = 0; i < 250; i++) {
                withFar.add(park(restriction.getId() + "-far-" + i, 3000 + i * 30, 3000 + withFar.size()));
            }
            withFar.add(restriction);
        }

        ObstacleSet expected = new ObstacleSet(input(near), rules, AREA, DN);
        ObstacleSet actual = new ObstacleSet(input(withFar), rules, AREA, DN);

        assertEquals(expected.nodes(), actual.nodes());
    }

    private Restriction park(String id, double x, double y) {
        return new Restriction(id, factory.toGeometry(new Envelope(x, x + 20, y, y + 10)), "park");
    }

    private static InputData input(List<Restriction> restrictions) {
        return new InputData(null, List.of(), List.of(), List.of(), List.of(), List.of(), restrictions, List.of(), List.of(), java.util.Set.of());
    }
}
