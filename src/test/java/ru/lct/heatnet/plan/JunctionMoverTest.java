package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.plan.PlanFixture.line;
import static ru.lct.heatnet.plan.PlanFixture.point;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.rules.Rules;

class JunctionMoverTest {
    private static final int DN = 100;
    private static final Envelope AREA = new Envelope(-100, 700, -300, 400);

    private final Rules rules = Rules.load();

    @Test
    void teeJunctionMovesTowardSteinerPointAndKeepsTopology() {
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 240, 120, 5).oks("o-2", 360, 120, 5);
        InputData input = fixture.input();
        TieCandidate tie = new TieInFinder(input, rules).find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        Coordinate root = tie.getPoint().getCoordinate();
        // буква Т: ствол от врезки вверх на 120 м, ветки к точкам по горизонтали
        Tree tree = new Tree(tie);
        Tree.Node junction = Tree.Node.junction(new Coordinate(root.x, 120));
        tree.edges.add(new Tree.Edge(tree.root, junction, line(root.x, root.y, root.x, 120)));
        tree.edges.add(new Tree.Edge(junction, Tree.Node.connection(fixture.connection("o-1")), line(root.x, 120, 240, 120)));
        tree.edges.add(new Tree.Edge(junction, Tree.Node.connection(fixture.connection("o-2")), line(root.x, 120, 360, 120)));
        Map<String, FutureOks> oksById = fixture.oks.stream().collect(Collectors.toMap(FutureOks::getId, Function.identity()));
        JunctionMover mover = new JunctionMover(rules, new SpecialObjects(input, rules), Map.of(), oksById);

        List<Tree> moves = mover.moves(tree, junction, new Router(input, rules, AREA, DN).obstacles(), AREA, DN, 1);

        assertEquals(1, moves.size());
        Tree moved = moves.get(0);
        // развилка уходит к врезке, ветки идут наискось: сеть короче, узлы и рёбра те же
        assertTrue(moved.length() < tree.length() - 10, "длина " + moved.length() + " против " + tree.length());
        assertEquals(3, moved.edges.size());
        Tree.Node shifted = JunctionMover.junctions(moved).get(0);
        assertTrue(shifted.point.y < 120);
        assertSame(moved.root, moved.edges.get(0).from);
        assertEquals(tree.connected(), moved.connected());
        for (Tree.Edge edge : moved.edges) {
            assertTrue(edge.line.getStartPoint().getCoordinate().equals2D(edge.from.point));
            assertTrue(edge.line.getEndPoint().getCoordinate().equals2D(edge.to.point));
        }
    }
}
