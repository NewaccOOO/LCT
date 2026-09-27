package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void finalPieceDoesNotComeBackIntoOwnClearanceZone() {
        // луч вышел из зоны отступа стены y=10 и проходит в 3,58 м от крюка того же здания: при отступе 5,3 м он
        // входит в зону снова, как у ОКС 10 датасета, при 3,5 м нет
        Geometry building = PlanFixture.rect(0, 0, 30, 10).union(PlanFixture.rect(25, 10, 30, 30))
                .union(PlanFixture.rect(13.58, 25, 30, 30));
        Coordinate cp = new Coordinate(10, 8);
        Coordinate exit = new Coordinate(10, 40);

        assertFalse(TreeBuilder.leavesOnce(building, cp, exit, 5.3));
        assertTrue(TreeBuilder.leavesOnce(building, cp, exit, 3.5));
        assertTrue(TreeBuilder.leavesOnce(building, cp, new Coordinate(10, 16), 5.3), "луч кончается до крюка");
    }

    @Test
    void finalPieceDoesNotApproachAnotherWallInsideClearanceZone() {
        // луч от стены y=10 к крылу x=20 того же здания в зоне отступа подходит к нему, как у ОКС 10 датасета при
        // входе между стенами: такой вход недопустим; луч прямо от стены удаляется от здания
        Geometry building = PlanFixture.rect(0, 0, 30, 10).union(PlanFixture.rect(20, 10, 30, 30));
        Coordinate cp = new Coordinate(10, 8);

        assertFalse(TreeBuilder.recedes(building, cp, new Coordinate(19, 12.5), 5.3));
        assertTrue(TreeBuilder.recedes(building, cp, new Coordinate(10, 20), 5.3));
    }

    @Test
    void exitLinkSplitsTurnSteeperThan90IntoTwoAllowedTurns() {
        // маршрут от выхода уходит назад к стене на 0,41° круче перпендикуляра, как у точки 4 датасета
        Coordinate cp = new Coordinate(0, 0);
        Coordinate exit = new Coordinate(0, 10);
        Coordinate next = new Coordinate(-20, 10 - 20 * Math.tan(Math.toRadians(0.41)));
        Coordinate[] route = {cp, exit, next, new Coordinate(-60, 30)};
        assertTrue(TreeBuilder.deflectionDeg(cp, exit, next) > Router.MAX_TURN_DEG);

        Coordinate[] linked = TreeBuilder.exitLink(route, 1.5);

        assertEquals(route.length + 1, linked.length);
        assertEquals(exit, linked[1], "финальный участок точка–выход тот же");
        assertEquals(1.5, exit.distance(linked[2]), 1e-9);
        for (int i = 1; i + 1 < linked.length; i++) {
            double turn = TreeBuilder.deflectionDeg(linked[i - 1], linked[i], linked[i + 1]);
            assertTrue(turn >= TreeBuilder.MIN_TURN_DEG && turn <= Router.MAX_TURN_DEG, "поворот " + turn + "° в вершине " + i);
        }
    }

    @Test
    void exitFacesNeighbourByDiameterOfOwnPieceNotOfTreeGraph() {
        // сосед в 12 м за ближней (южной) стеной, до восточной 5 м: по графу Ду500 (отступ 7,835 м) выход лёг бы в его зону, по Ду
        // участка точки (5 т/ч, отступ около 5,2 м) проход есть, и финальный участок идёт от ближней стены
        Geometry building = PlanFixture.rect(140, 40, 180, 80);
        PlanFixture fixture = PlanFixture.trunk();
        fixture.oks.add(new FutureOks("o-1", building, 5, 1.0));
        fixture.connections.add(new ConnectionPoint("cp-o-1", point(175, 44), "o-1"));
        fixture.existing.add(new ExistingOks("b-1", building));
        fixture.existing.add(new ExistingOks("b-2", PlanFixture.rect(165, 20, 185, 28)));
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        int graphDn = 500;
        TieCandidate tie = finder.find(List.of(point(220, 0)), graphDn).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        TreeBuilder builder = builder(input, finder, VariantEnumerator.buildings(input));
        TreeBuilder.Graphs graphs = new TreeBuilder.Graphs() {
            @Override
            public int dn(List<ConnectionPoint> connections) {
                return rules.diameterFor(5).getDn();
            }

            @Override
            public ru.lct.heatnet.graph.ObstacleSet obstacles(int dn) {
                return router(dn).obstacles();
            }

            @Override
            public Router router(int dn) {
                return new Router(input, rules, AREA, dn);
            }
        };

        Tree wide = builder.build(new Router(input, rules, AREA, graphDn), graphDn, AREA, tie, fixture.connections, 0, 0, false, null);
        Tree narrow = builder.build(new Router(input, rules, AREA, graphDn), graphDn, AREA, tie, fixture.connections, 0, 0, false, graphs);

        assertTrue(wide.unconnected.isEmpty() && narrow.unconnected.isEmpty());
        assertTrue(finalPiece(wide).getCoordinateN(0).y > 40, "по графу Ду500 выход не через южную стену: " + finalPiece(wide));
        assertTrue(narrow.narrow, "ветка по графу Ду участка");
        LineString piece = finalPiece(narrow);
        assertEquals(175, piece.getCoordinateN(0).x, 1e-6);
        assertTrue(piece.getCoordinateN(0).y < 40 && piece.getLength() < 10, "выход через южную стену: " + piece);
    }

    private static LineString finalPiece(Tree tree) {
        Coordinate[] coords = tree.edges.stream().filter(edge -> edge.to.kind == Tree.Kind.CONNECTION).findFirst()
                .orElseThrow().line.getCoordinates();
        return PlanFixture.line(coords[coords.length - 2].x, coords[coords.length - 2].y,
                coords[coords.length - 1].x, coords[coords.length - 1].y);
    }

    @Test
    void cutTreeKeepsEdgeFromTieInRoot() {
        // ствол с прямым углом вдали от препятствий: срезка и форма спрямляют угол, ребро должно остаться у узла врезки
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 300, 60, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(200, 30)), DN).stream()
                .filter(TieCandidate::isChamber).findFirst().orElseThrow();
        Tree tree = new Tree(tie);
        tree.edges.add(new Tree.Edge(tree.root, Tree.Node.connection(fixture.connection("o-1")), PlanFixture.GEOMETRY.createLineString(
                new Coordinate[] {tie.getPoint().getCoordinate(), new Coordinate(200, 60), new Coordinate(300, 60)})));

        Tree cut = builder(input, finder).cut(tree, new Router(input, rules, AREA, DN), DN, null, TreeBuilder.CUT_PASSES);

        assertTrue(cut != tree && cut.edges.get(0).line.getLength() < tree.edges.get(0).line.getLength() - 1,
                "угол спрямлён: " + cut.edges.get(0).line);
        assertEquals(1, cut.degree(cut.root), "ребро от узла врезки срезанного дерева");
    }

    @Test
    void junctionSlidesAlongTrunkToStraightenKinkedBranch() {
        // ствол идёт вверх через камеру, ветка к o-2 ломается в 3,6 м от камеры: на продолжении её дальнего звена
        // камера стоит на 2,33 м выше по стволу, и ветка оттуда прямая
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 300, 200, 5).oks("o-2", 362, 143, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        Coordinate root = tie.getPoint().getCoordinate();
        Tree tree = new Tree(tie);
        Tree.Node junction = Tree.Node.junction(new Coordinate(root.x, 120));
        tree.edges.add(new Tree.Edge(tree.root, junction, PlanFixture.line(root.x, root.y, root.x, 120)));
        tree.edges.add(new Tree.Edge(junction, Tree.Node.connection(fixture.connection("o-1")),
                PlanFixture.line(root.x, 120, 300, 200)));
        tree.edges.add(new Tree.Edge(junction, Tree.Node.connection(fixture.connection("o-2")), PlanFixture.GEOMETRY
                .createLineString(new Coordinate[] {new Coordinate(root.x, 120), new Coordinate(302, 123), new Coordinate(362, 143)})));
        Map<Tree.Edge, Integer> dnByEdge = new java.util.IdentityHashMap<>();
        Map<Tree.Edge, Double> priceRub = new java.util.IdentityHashMap<>();
        tree.edges.forEach(edge -> {
            dnByEdge.put(edge, DN);
            priceRub.put(edge, 1.0);
        });
        TreeBuilder builder = builder(input, finder);
        Router router = new Router(input, rules, AREA, DN);

        List<TreeBuilder.Slide> slides = builder.unkinks(tree, edge -> router.obstacles(), dnByEdge, priceRub, 0);

        assertEquals(1, slides.size());
        Tree moved = builder.moved(tree, slides.get(0));
        assertEquals(122.333, JunctionMover.junctions(moved).get(0).point.y, 1e-3);
        assertEquals(2, moved.edges.get(2).line.getNumPoints(), "ветка прямая: " + moved.edges.get(2).line);
        assertTrue(moved.length() < tree.length() - 1, "длина " + moved.length() + " против " + tree.length());
    }

    @Test
    void tieSlidesAlongPipeToDropVertexNotNeededForClearance() {
        // ветка от врезки на трубе ломается на 10,5° в 1,5 м перед поворотом на 80°: прямая от врезки к дальней вершине
        // дала бы там 90,3°, а от места на 0,8 м левее по трубе — не круче 89,9° и короче прежней ветки
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 340, 96, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        Coordinate root = tie.getPoint().getCoordinate();
        double bend = Math.toRadians(10.5);
        Coordinate kink = new Coordinate(root.x, root.y + 95);
        Coordinate turn = new Coordinate(kink.x + 1.5 * Math.sin(bend), kink.y + 1.5 * Math.cos(bend));
        Tree tree = new Tree(tie);
        tree.edges.add(new Tree.Edge(tree.root, Tree.Node.connection(fixture.connection("o-1")), PlanFixture.GEOMETRY
                .createLineString(new Coordinate[] {root, kink, turn, new Coordinate(turn.x + 40, turn.y - 0.35)})));
        Map<Tree.Edge, Double> priceRub = Map.of(tree.edges.get(0), 1.0);
        TreeBuilder builder = builder(input, finder);
        Router router = new Router(input, rules, AREA, DN);

        assertEquals(null, builder.retie(tree, tree.edges.get(0), new Coordinate(root.x - 0.1, root.y), priceRub, 0),
                "поворот круче 89,9°");
        TieCandidate moved = finder.shifted(tie, root, -0.8, DN);
        TreeBuilder.Slide slide = builder.retie(tree, tree.edges.get(0), moved.getPoint().getCoordinate(), priceRub, 0);

        assertTrue(slide != null && slide.gain < 0, "сдвиг дешевле");
        assertEquals(3, slide.lines.get(tree.edges.get(0)).length, "вершина излома ушла");
        assertTrue(builder.retieClear(tree, slide, moved.getIgnored(), edge -> router.obstacles()));
        Tree retied = builder.moved(tree, slide, moved);
        assertEquals(root.x - 0.8, retied.root.point.x, 1e-9);
        assertEquals(moved.nodeKey(), retied.root.key);
    }

    @Test
    void branchLinkBendsAtJunctionSoPathTurnIsNotSteeperThan90() {
        // ветка к o-2 уходит от камеры назад, на 116,6° к стволу: звено у камеры поворачивается, и путь точки к врезке
        // поворачивает в камере не круче 89,9°
        PlanFixture fixture = PlanFixture.trunk().oks("o-1", 340, 100, 5).oks("o-2", 240, 30, 5);
        InputData input = fixture.input();
        TieInFinder finder = new TieInFinder(input, rules);
        TieCandidate tie = finder.find(List.of(point(300, 60)), DN).stream()
                .filter(c -> c.getExistingObjectId().equals("hn-2")).findFirst().orElseThrow();
        Coordinate root = tie.getPoint().getCoordinate();
        Tree tree = new Tree(tie);
        Tree.Node junction = Tree.Node.junction(new Coordinate(root.x, 60));
        tree.edges.add(new Tree.Edge(tree.root, junction, PlanFixture.line(root.x, root.y, root.x, 60)));
        tree.edges.add(new Tree.Edge(junction, Tree.Node.connection(fixture.connection("o-1")), PlanFixture.line(root.x, 60, 340, 100)));
        tree.edges.add(new Tree.Edge(junction, Tree.Node.connection(fixture.connection("o-2")), PlanFixture.line(root.x, 60, 240, 30)));
        Map<Tree.Edge, Double> priceRub = new java.util.IdentityHashMap<>();
        tree.edges.forEach(edge -> priceRub.put(edge, 1.0));
        Router router = new Router(input, rules, AREA, DN);

        List<TreeBuilder.Slide> bends = builder(input, finder).bends(tree, junction, edge -> router.obstacles(), priceRub, 100);

        assertFalse(bends.isEmpty());
        Tree bent = builder(input, finder).moved(tree, bends.get(0));
        Tree.Node at = JunctionMover.junctions(bent).get(0);
        Coordinate[] trunk = bent.edges.get(0).line.getCoordinates();
        for (Tree.Edge edge : bent.edges.subList(1, 3)) {
            double turn = Router.deflectionDeg(trunk[trunk.length - 2], at.point, edge.line.getCoordinateN(1));
            assertTrue(turn <= Router.MAX_TURN_DEG, edge.to.key + " " + turn);
        }
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
