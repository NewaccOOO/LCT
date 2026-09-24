package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.lct.heatnet.plan.PlanFixture.point;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.graph.Router;
import ru.lct.heatnet.model.ConnectionPoint;
import ru.lct.heatnet.model.ExistingOks;
import ru.lct.heatnet.model.FutureOks;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NetworkSegment;
import ru.lct.heatnet.rules.Rules;

class TreeBuilderTest {
    private static final int DN = 100;
    private static final Envelope AREA = new Envelope(-100, 700, -300, 400);

    private final Rules rules = Rules.load();

    @Test
    void treeHasOnePathToEachOksNoCrossingsAndBranchesOnlyInChambers() {
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 150, 80, 5).oks("o-2", 250, 80, 5).oks("o-3", 200, 140, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();

        Tree tree = builder(input, finder).build(new Router(input, rules, AREA, DN), DN, AREA, tie, fixture.connections);

        assertTrue(tree.unconnected.isEmpty(), "не подключены: " + tree.unconnected);
        assertEquals(3, tree.connected().size());
        Set<Tree.Node> nodes = new LinkedHashSet<>();
        Map<Tree.Node, Integer> incoming = new HashMap<>();
        for (Tree.Edge edge : tree.edges) {
            nodes.add(edge.from);
            nodes.add(edge.to);
            incoming.merge(edge.to, 1, Integer::sum);
        }
        assertEquals(nodes.size() - 1, tree.edges.size(), "рёбер на одно меньше узлов: дерево без циклов");
        assertEquals(0, incoming.getOrDefault(tree.root, 0));
        boolean branched = false;
        for (Tree.Node node : nodes) {
            if (node != tree.root) {
                assertEquals(1, incoming.get(node), "в узел " + node.key + " входит одно ребро");
            }
            if (tree.degree(node) >= 3) {
                assertEquals(Tree.Kind.JUNCTION, node.kind, "ветвление не в камере: " + node.key);
                branched = true;
            }
            if (node.kind == Tree.Kind.CONNECTION) {
                assertEquals(1, tree.degree(node), "точка подключения не проходная");
            }
        }
        assertTrue(branched, "три близких ОКС ожидаются общим деревом с камерой ветвления");
        for (int i = 0; i < tree.edges.size(); i++) {
            for (int j = i + 1; j < tree.edges.size(); j++) {
                assertTouchOnlyAtSharedNode(tree.edges.get(i), tree.edges.get(j));
            }
        }
    }

    @Test
    void extraBranchMovesFromFullTieChamberToTrunk() {
        // у hc-1 три существующих участка: в камеру можно подключить только один новый. Ближайшая к o-2 точка
        // дерева после подключения o-1 — сама камера, поэтому ответвление должно уйти на ствол.
        PlanFixture fixture = PlanFixture.trunk()
                .pipe("hn-4", 200, 0, 200, -150, DN, 10, "hc-1")
                .oks("o-1", 140, 60, 5).oks("o-2", 260, 40, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(200, 30)), DN).stream()
                .filter(TieCandidate::isChamber).findFirst().orElseThrow();
        assertEquals(1, tie.getCapacity());

        Tree tree = builder(input, finder).build(new Router(input, rules, AREA, DN), DN, AREA, tie, fixture.connections);

        assertTrue(tree.unconnected.isEmpty(), "не подключены: " + tree.unconnected);
        assertEquals(1, tree.degree(tree.root), "в камеру врезки подключён один новый участок");
        assertTrue(tree.edges.stream().anyMatch(edge -> edge.to.kind == Tree.Kind.JUNCTION && tree.degree(edge.to) == 3),
                "второй ОКС присоединён новой камерой на стволе");
    }

    @Test
    void branchPassesAsideConnectionPointLyingOnItsRoute() {
        // точки подключения на одном перпендикуляре к трубе: прямой маршрут к дальней идёт через ближнюю
        PlanFixture fixture = PlanFixture.trunk().oks("o-near", 300, 30, 1).oks("o-far", 300, 60, 1);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(300, 30)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();

        Tree tree = builder(input, finder).build(new Router(input, rules, AREA, DN), DN, AREA, tie, fixture.connections);

        assertTrue(tree.unconnected.isEmpty(), "не подключены: " + tree.unconnected);
        double length = tree.edges.stream().mapToDouble(edge -> edge.line.getLength()).sum();
        assertTrue(length < 91, "длина дерева " + length);
        for (int i = 0; i < tree.edges.size(); i++) {
            for (int j = i + 1; j < tree.edges.size(); j++) {
                assertTouchOnlyAtSharedNode(tree.edges.get(i), tree.edges.get(j));
            }
        }
    }

    @Test
    void finalPieceLeavesOwnBuildingOnceEvenWhenNearestSideFacesCourtyard() {
        // П-образное здание: точка в левом крыле у стены двора; луч через ближайшую точку контура пересёк бы правое крыло
        Geometry building = PlanFixture.rect(150, 50, 180, 60)
                .union(PlanFixture.rect(150, 60, 160, 120)).union(PlanFixture.rect(170, 60, 180, 120));
        PlanFixture fixture = PlanFixture.trunk();
        fixture.oks.add(new FutureOks("o-1", building, 5, 1.0));
        fixture.connections.add(new ConnectionPoint("cp-o-1", point(158, 70), "o-1"));
        fixture.existing.add(new ExistingOks("b-1", building));
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(100, 0)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-1")).findFirst().orElseThrow();
        TreeBuilder builder = builder(input, finder, VariantEnumerator.buildings(input));

        Tree tree = builder.build(new Router(input, rules, AREA, DN), DN, AREA, tie, fixture.connections);

        assertTrue(tree.unconnected.isEmpty(), "не подключены: " + tree.unconnected);
        Tree.Edge last = tree.edges.get(tree.edges.size() - 1);
        assertEquals(Tree.Kind.CONNECTION, last.to.kind);
        Coordinate[] coords = last.line.getCoordinates();
        LineString piece = PlanFixture.line(coords[coords.length - 2].x, coords[coords.length - 2].y, 158, 70);
        Geometry inside = piece.intersection(building);
        assertEquals(1, inside.getNumGeometries(), "финальный участок входит в здание один раз: " + inside);
        assertTrue(inside.getLength() < 9, "выход через наружную стену x=150, а не сквозь двор: " + inside.getLength());
    }

    @Test
    void cutTreeKeepsEdgeFromTieInRoot() {
        // ствол с прямым углом вдали от препятствий: срезка заменяет угол хордой, ребро должно остаться у узла врезки
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 300, 60, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(200, 30)), DN).stream()
                .filter(TieCandidate::isChamber).findFirst().orElseThrow();
        Tree tree = new Tree(tie);
        tree.edges.add(new Tree.Edge(tree.root, Tree.Node.connection(fixture.connection("o-1")), PlanFixture.GEOMETRY.createLineString(
                new Coordinate[] {tie.getPoint().getCoordinate(), new Coordinate(200, 60), new Coordinate(300, 60)})));

        Tree cut = builder(input, finder).cut(tree, new Router(input, rules, AREA, DN));

        assertTrue(cut != tree && cut.edges.get(0).line.getNumPoints() > 3, "угол срезан: " + cut.edges.get(0).line);
        assertEquals(1, cut.degree(cut.root), "ребро от узла врезки срезанного дерева");
    }

    private TreeBuilder builder(InputData input, TieInFinder finder) {
        return builder(input, finder, Map.of());
    }

    private TreeBuilder builder(InputData input, TieInFinder finder, Map<String, ExistingOks> buildings) {
        Map<String, LineString> networkById = new HashMap<>();
        for (NetworkSegment segment : input.getSegments()) {
            networkById.put(segment.getId(), segment.getGeometry());
        }
        return new TreeBuilder(finder.nodeLimit(), networkById, new SpecialObjects(input, rules), buildings);
    }

    private static void assertTouchOnlyAtSharedNode(Tree.Edge a, Tree.Edge b) {
        Geometry common = a.line.intersection(b.line);
        Set<Tree.Node> shared = new LinkedHashSet<>(List.of(a.from, a.to));
        shared.retainAll(List.of(b.from, b.to));
        for (Coordinate c : common.getCoordinates()) {
            assertTrue(shared.stream().anyMatch(node -> node.point.distance(c) < 1e-6),
                    "рёбра " + a.to.key + " и " + b.to.key + " пересекаются вне общего узла в " + c);
        }
        assertTrue(common.isEmpty() || common.getDimension() < 1, "рёбра " + a.to.key + " и " + b.to.key + " накладываются");
    }
}
