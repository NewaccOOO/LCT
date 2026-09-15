package ru.lct.heatnet.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {
    private static final double EPS = 1e-9;
    private final Rules rules = Rules.load();

    @Test
    void diameterForIncludesExactCapacity() {
        assertEquals(50, rules.diameterFor(0).getDn());
        assertEquals(50, rules.diameterFor(3.5).getDn());
        assertEquals(65, rules.diameterFor(3.51).getDn());
        assertEquals(1400, rules.diameterFor(22501.9).getDn());
    }

    @Test
    void diameterForSwitchesRightAboveEveryCapacity() {
        List<Diameter> table = rules.diameters();
        for (int i = 0; i + 1 < table.size(); i++) {
            Diameter diameter = table.get(i);
            assertEquals(diameter, rules.diameterFor(diameter.getCapacityTph()));
            assertEquals(table.get(i + 1), rules.diameterFor(Math.nextUp(diameter.getCapacityTph())));
        }
    }

    @Test
    void diameterForAboveMaximumThrows() {
        assertThrows(IllegalArgumentException.class, () -> rules.diameterFor(22502));
    }

    @Test
    void diametersAscendAndNextDiameterStepsOneRow() {
        List<Diameter> table = rules.diameters();
        assertEquals(18, table.size());
        for (int i = 0; i + 1 < table.size(); i++) {
            assertTrue(table.get(i).getDn() < table.get(i + 1).getDn());
        }
        assertEquals(65, rules.nextDiameter(50).getDn());
        assertEquals(1000, rules.nextDiameter(900).getDn());
        assertEquals(1400, rules.nextDiameter(1200).getDn());
        assertNull(rules.nextDiameter(1400));
        assertEquals(0.88, rules.diameter(200).getWidthM(), EPS);
        assertThrows(IllegalArgumentException.class, () -> rules.diameter(40));
    }

    @Test
    void chamberCostFollowsRangesInclusive() {
        assertEquals(3_000_000, rules.chamberCost(50), EPS);
        assertEquals(3_000_000, rules.chamberCost(200), EPS);
        assertEquals(5_000_000, rules.chamberCost(250), EPS);
        assertEquals(5_000_000, rules.chamberCost(500), EPS);
        assertEquals(8_000_000, rules.chamberCost(600), EPS);
        assertEquals(8_000_000, rules.chamberCost(1000), EPS);
        assertEquals(12_000_000, rules.chamberCost(1200), EPS);
        assertEquals(12_000_000, rules.chamberCost(1400), EPS);
        assertThrows(IllegalArgumentException.class, () -> rules.chamberCost(220));
    }

    @Test
    void oksExistingClearanceDependsOnDiameter() {
        RestrictionRule oks = rules.restriction("oks_existing");
        assertTrue(oks.forbid());
        assertEquals(5, oks.clearanceM(400), EPS);
        assertEquals(7, oks.clearanceM(500), EPS);
        assertEquals(7, oks.clearanceM(800), EPS);
        assertEquals(9, oks.clearanceM(900), EPS);
    }

    @Test
    void restrictionOptionalFieldsAreNullWhenAbsent() {
        RestrictionRule road = rules.restriction("road");
        assertFalse(road.forbid());
        assertEquals(1.5, road.clearanceM(1400), EPS);
        assertEquals(45, road.getMinAngleDeg(), EPS);
        assertEquals(3, road.getMarginM(), EPS);
        assertEquals(1.6, road.getKSpecial(), EPS);
        assertNull(road.getHalfWidthM());

        RestrictionRule gas = rules.restriction("gas_pipeline");
        assertNull(gas.getMinAngleDeg());
        assertEquals(0.2, gas.getHalfWidthM(), EPS);

        RestrictionRule park = rules.restriction("park");
        assertTrue(park.forbid());
        assertNull(park.getKSpecial());
        assertNull(park.getMarginM());

    }

    @Test
    void unknownTypeGetsFallbackRule() {
        assertTrue(rules.isKnown("metro"));
        assertFalse(rules.isKnown("bridge"));
        assertFalse(rules.isKnown("_fallback"));

        RestrictionRule bridge = rules.restriction("bridge");
        assertTrue(bridge.forbid());
        assertEquals(1.0, bridge.clearanceM(1400), EPS);
        assertNull(bridge.getHalfWidthM());
        assertEquals(bridge, rules.restriction("_comment"));

        RestrictionRule railway = rules.restriction("railway");
        assertFalse(railway.forbid());
        assertEquals(60, railway.getMinAngleDeg(), EPS);
        assertEquals(10, railway.getMarginM(), EPS);
        assertEquals(2.0, railway.getKSpecial(), EPS);
        assertEquals(0.0, rules.restriction("sewer").getHalfWidthM(), EPS);
    }

    @Test
    void penaltyTieInAndChamberRule() {
        assertEquals(100_000_000, rules.penalty(0), EPS);
        assertEquals(106_250_000, rules.penalty(12.5), EPS);
        assertEquals(5_000_000, rules.tieInCost(), EPS);
        assertEquals(10, rules.chamberRule().getMaxDistM(), EPS);
        assertEquals(4, rules.chamberRule().getMaxSegments());
        assertEquals(3, rules.chamberRule().getMaxBranches());
    }

    @Test
    void scoreWeightsCostAndLength() {
        assertEquals(1.0, rules.score(25_000_000, 100), EPS);
        assertEquals(0.7 * 2 + 0.3 * 2.5, rules.score(50_000_000, 250), EPS);
        assertEquals(0, rules.score(0, 0), EPS);
    }
}
