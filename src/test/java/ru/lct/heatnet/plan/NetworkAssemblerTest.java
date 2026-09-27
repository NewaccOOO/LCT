package ru.lct.heatnet.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.model.InputData;
import ru.lct.heatnet.model.NewSegment;
import ru.lct.heatnet.model.Variant;
import ru.lct.heatnet.rules.Rules;

class NetworkAssemblerTest {
    private static final Geometry ROAD = PlanFixture.rect(-500, 50, 1000, 70);
    private final Rules rules = Rules.load();

    @Test
    void roadSpecialRunsThreeMetersAlongTraceBeyondPolygon() {
        // дорога 20 м под 60°: спецучасток — 20 / sin 60° в полигоне и по 3 м вдоль трассы (ТП разд. 4, табл. 2),
        // а не полоса 3 м поперёк границы, (20 + 6) / sin 60°
        List<NewSegment> special = specials(assemble(PlanFixture.trunk(), 60, 10));

        assertEquals(1, special.size());
        assertEquals(20 / Math.sin(Math.toRadians(60)) + 6, special.get(0).getGeometry().getLength(), 0.05);
    }

    @Test
    void roadSpecialEndsThreeMetresBeyondWhenBaseBreaksClearance() {
        // Ду 400 под 46°: в 3 м вдоль трассы за дорогой до неё 3 · sin 46° = 2,16 м, норма 1,5 + 0,685 = 2,185 м.
        // Спецучасток за 3 м не продлевается (табл. 2), и обычный участок ближе нормы сборка отвергает
        assertThrows(IllegalStateException.class, () -> assemble(PlanFixture.trunk(), 46, 500));
    }

    @Test
    void baseNearLineCrossingIsCloserThanNormOnlyInsideCircle() {
        // газопровод поперёк трассы Ду 80: норма 2 + 0,235 + 0,2 = 2,435 м больше зоны 2 м. Под 90° обычная трасса
        // ближе нормы только в круге 2,535 м у пересечения, под 60° — ещё и за ним: 2,535 · sin 60° = 2,2 м
        assertEquals(2, specials(assemble(gasAcross(), 90, 10)).size());
        assertThrows(IllegalStateException.class, () -> assemble(gasAcross(), 60, 10));
    }

    private static PlanFixture gasAcross() {
        return PlanFixture.trunk().restriction("gas-1", PlanFixture.line(0, 30, 600, 30), "gas_pipeline");
    }

    @Test
    void specialIsSplitWhereSetOfZonesChanges() {
        // газопровод в 2 м под дорогой: его зона накрывает начало зоны дороги. Kспец 1,60 на общем фрагменте и за
        // ним тот же, но на границе общего фрагмента начинается новый участок (п. 4, разъяснение 8). Трасса идёт
        // поперёк: под острым углом обычная часть у газопровода была бы ближе нормы за кругом у пересечения
        PlanFixture fixture = PlanFixture.trunk()
                .restriction("gas-1", PlanFixture.line(250, 48, 450, 48), "gas_pipeline");

        List<Double> k = specials(assemble(fixture, 90, 10)).stream()
                .map(segment -> segment.getCost() / segment.getLength()
                        / rules.diameter(segment.getDiameter()).getNewRubM())
                .collect(Collectors.toList());

        assertEquals(3, k.size(), "участки " + k);
        assertEquals(1.25, k.get(0), 0.001);
        assertEquals(1.6, k.get(1), 0.001);
        assertEquals(1.6, k.get(2), 0.001);
    }

    /** Один прямой участок от врезки на hn-2 в (300, 0) через дорогу под углом deg к ОКС с расходом flow. */
    private Variant assemble(PlanFixture fixture, double deg, double flow) {
        double rise = 120;
        fixture.restriction("road-1", ROAD, "road").oks("1", 300 + rise / Math.tan(Math.toRadians(deg)), rise, flow);
        InputData input = fixture.input();
        TieCandidate tie = new TieCandidate("hn-2", TieCandidate.HEAT_NETWORK, 200, PlanFixture.point(300, 0),
                Set.of("hn-2"), 2);
        Tree tree = new Tree(tie);
        Tree.Node cp = Tree.Node.connection(fixture.connection("1"));
        tree.edges.add(new Tree.Edge(tree.root, cp, PlanFixture.GEOMETRY.createLineString(
                new Coordinate[] {tree.root.point, cp.point})));
        NetworkAssembler assembler = new NetworkAssembler(input, rules, new SpecialObjects(input, rules),
                Map.of("1", fixture.oks.get(0)));
        return assembler.assemble("1", 1, List.of(tree), List.of());
    }

    /** Специальные участки по порядку от врезки. */
    private static List<NewSegment> specials(Variant variant) {
        return variant.getSegments().stream()
                .filter(segment -> NetworkAssembler.SPECIAL.equals(segment.getLayingMethod()))
                .sorted(Comparator.comparingDouble(segment -> segment.getGeometry().getCoordinateN(0).y))
                .collect(Collectors.toList());
    }
}
