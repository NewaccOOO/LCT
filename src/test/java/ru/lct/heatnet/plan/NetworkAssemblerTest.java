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
    private static final Geometry ROAD_AXIS = PlanFixture.line(-500, 60, 1000, 60);
    private final Rules rules = Rules.load();

    @Test
    void roadSpecialRunsThreeMetersAlongTraceBeyondPolygon() {
        List<NewSegment> special = specials(assemble(PlanFixture.trunk(), 60, 10));

        assertEquals(1, special.size());
        assertEquals(20 / Math.sin(Math.toRadians(60)) + 6, special.get(0).getGeometry().getLength(), 0.05);
    }

    @Test
    void roadSpecialEndsThreeMetresBeyondWhenBaseBreaksClearance() {
        assertThrows(IllegalStateException.class, () -> assemble(PlanFixture.trunk(), 46, 500));
    }

    @Test
    void baseNearLineCrossingIsCloserThanNormOnlyInsideCircle() {
        assertEquals(2, specials(assemble(gasAcross(), 90, 10)).size());
        assertThrows(IllegalStateException.class, () -> assemble(gasAcross(), 60, 10));
    }

    private static PlanFixture gasAcross() {
        return PlanFixture.trunk().restriction("gas-1", PlanFixture.line(0, 30, 600, 30), "gas_pipeline");
    }

    @Test
    void roadAxisSpecialRunsThreeMetersEachSideAlongTrace() {
        List<NewSegment> special = specials(assemble(PlanFixture.trunk(), ROAD_AXIS, 60, 10));

        assertEquals(1, special.size());
        assertEquals(6, special.get(0).getGeometry().getLength(), 0.05);
    }

    @Test
    void specialIsSplitWhereSetOfZonesChanges() {
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

    @Test
    void zoneEndAndNextZoneStartCentimetresApartShareOneCutBetweenThem() {
        PlanFixture fixture = PlanFixture.trunk()
                .restriction("gas-1", PlanFixture.line(250, 75.001, 450, 75.001), "gas_pipeline");

        List<NewSegment> special = specials(assemble(fixture, 90, 10));
        double cut = special.get(special.size() - 1).getGeometry().getCoordinateN(0).y;

        assertEquals(2, special.size());
        assertEquals(73.0005, cut, 0.0005);
    }

    @Test
    void overlapOfZonesTooShortForOwnPieceAndTooLongToShareCutIsRejected() {
        PlanFixture fixture = PlanFixture.trunk()
                .restriction("gas-1", PlanFixture.line(250, 74.97, 450, 74.97), "gas_pipeline");

        assertThrows(IllegalStateException.class, () -> assemble(fixture, 90, 10));
    }

    private Variant assemble(PlanFixture fixture, double deg, double flow) {
        return assemble(fixture, ROAD, deg, flow);
    }

    private Variant assemble(PlanFixture fixture, Geometry road, double deg, double flow) {
        double rise = 120;
        double leave = 3;
        fixture.restriction("road-1", road, "road").oks("1", 300 + (rise - leave) / Math.tan(Math.toRadians(deg)), rise, flow);
        InputData input = fixture.input();
        TieCandidate tie = new TieCandidate("hn-2", TieCandidate.HEAT_NETWORK, 200, PlanFixture.point(300, 0),
                Set.of("hn-2"), 2);
        Tree tree = new Tree(tie);
        Tree.Node cp = Tree.Node.connection(fixture.connection("1"));
        tree.edges.add(new Tree.Edge(tree.root, cp, PlanFixture.GEOMETRY.createLineString(
                deg == 90 ? new Coordinate[] {tree.root.point, cp.point}
                        : new Coordinate[] {tree.root.point, new Coordinate(300, leave), cp.point})));
        NetworkAssembler assembler = new NetworkAssembler(input, rules, new SpecialObjects(input, rules),
                Map.of("1", fixture.oks.get(0)));
        return assembler.assemble("1", 1, List.of(tree), List.of());
    }

    private static List<NewSegment> specials(Variant variant) {
        return variant.getSegments().stream()
                .filter(segment -> NetworkAssembler.SPECIAL.equals(segment.getLayingMethod()))
                .sorted(Comparator.comparingDouble(segment -> segment.getGeometry().getCoordinateN(0).y))
                .collect(Collectors.toList());
    }
}
