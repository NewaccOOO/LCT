package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.graph.ObstacleIndex;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.rules.Rules;

class DirectTiesTest {
    private final Rules rules = Rules.load();

    @Test
    void pointAtPipeInsideItsBuildingGetsTieShiftedAlongPipe() {
        // точка в метре от hn-2 внутри своего здания, по которому идёт труба; врезка у проекции (299,34; 0) ближе
        // отступа к концу соседней трубы hn-q, левее она касается hn-q, сдвинутая на полметра вправо проходит
        PlanFixture fixture = PlanFixture.trunk()
                .pipe("hn-q", 250, -0.3, 298.2, -0.3, 50, 0, "hn-1")
                .oks("1", 300, 1, 10);
        Geometry building = PlanFixture.rect(290, -12, 310, 12);
        fixture.existing.add(new ExistingOks("b-1", building));
        InputData input = fixture.input();
        ConnectionPoint connection = fixture.connection("1");
        Map<String, FutureOks> oksById = Map.of("1", fixture.oks.get(0));
        DirectTies direct = new DirectTies(input, rules, new ObstacleIndex(input, rules), new TieInFinder(input, rules),
                Map.of(connection.getId(), new ExistingOks("b-1", building)),
                new NetworkAssembler(input, rules, new SpecialObjects(input, rules), oksById));
        List<ConnectionPoint> rest = new ArrayList<>();

        List<List<Tree>> trees = direct.connect(List.of(connection), oksById, 1, rest);

        assertTrue(rest.isEmpty(), "точка осталась без прямого подключения");
        Tree tree = trees.get(0).get(0);
        assertEquals("hn-2", tree.tie.getExistingObjectId());
        assertEquals(300.5, tree.root.point.x, 1e-6);
        assertEquals(0, tree.root.point.y, 1e-6);
    }
}
