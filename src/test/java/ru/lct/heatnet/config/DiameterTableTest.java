package ru.lct.heatnet.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiameterTableTest {

    @Test
    void tableMatchesTheAnnex() {
        assertEquals(18, DiameterTable.rows().size());
        DiameterSpec du100 = DiameterTable.requireDu(100);
        assertEquals(22.3, du100.capacityTph());
        assertEquals(419, du100.maxLengthM());
        assertEquals(89_748, du100.costPerMeter());
        assertEquals(0.510, du100.pairWidthM());
        assertEquals(0.255, du100.halfWidthM(), 1e-12);
        DiameterSpec du1400 = DiameterTable.largest();
        assertEquals(1400, du1400.du());
        assertEquals(22_501.9, du1400.capacityTph());
        assertEquals(683_417, du1400.costPerMeter());
    }

    @Test
    void minimalDuByFlow() {
        assertEquals(50, DiameterTable.minByFlow(3.5).get().du());
        assertEquals(65, DiameterTable.minByFlow(3.51).get().du());
        assertEquals(100, DiameterTable.minByFlow(20.0).get().du());
        assertEquals(125, DiameterTable.minByFlow(22.31).get().du());
        assertEquals(400, DiameterTable.minByFlow(488.7).get().du());
        assertFalse(DiameterTable.minByFlow(30_000).isPresent());
    }

    @Test
    void minimalDuByFlowAndLength() {
        // flow 4 t/h -> DU65 (245 m); at 400 m the first DU satisfying both is DU100 (419 m)
        assertEquals(100, DiameterTable.minSatisfying(4.0, 400, 0).get().du());
        assertEquals(65, DiameterTable.minSatisfying(4.0, 200, 0).get().du());
        assertEquals(125, DiameterTable.minSatisfying(4.0, 500, 0).get().du());
    }

    @Test
    void chamberCostScale() {
        assertEquals(3_000_000, ChamberCostTable.newChamberCost(50));
        assertEquals(3_000_000, ChamberCostTable.newChamberCost(200));
        assertEquals(5_000_000, ChamberCostTable.newChamberCost(250));
        assertEquals(5_000_000, ChamberCostTable.newChamberCost(500));
        assertEquals(8_000_000, ChamberCostTable.newChamberCost(600));
        assertEquals(8_000_000, ChamberCostTable.newChamberCost(1000));
        assertEquals(12_000_000, ChamberCostTable.newChamberCost(1200));
        assertEquals(12_000_000, ChamberCostTable.newChamberCost(1400));
        assertEquals(5_000_000, ChamberCostTable.EXISTING_CHAMBER_TIE_IN_COST);
    }

    @Test
    void restrictionRules() {
        RestrictionRule oks = RestrictionRules.require("oks");
        assertTrue(oks.isForbidden());
        assertEquals(5.0, oks.clearanceM(400));
        assertEquals(7.0, oks.clearanceM(500));
        assertEquals(7.0, oks.clearanceM(800));
        assertEquals(9.0, oks.clearanceM(900));
        assertEquals(5.0 + 0.255, oks.requiredAxisDistanceM(100), 1e-9);
        RestrictionRule road = RestrictionRules.require("road");
        assertTrue(road.isSpecial());
        assertEquals(1.5, road.clearanceM(100));
        assertEquals(45.0, road.minCrossingAngleDeg());
        assertEquals(3.0, road.sectionExtentM());
        assertEquals(1.60, road.kSpec());
        assertEquals(1.75, RestrictionRules.require("tram_tracks").kSpec());
        RestrictionRule gas = RestrictionRules.require("gas_pipeline");
        assertEquals(2.0, gas.clearanceM(100));
        assertEquals(2.0, gas.sectionExtentM());
        assertEquals(1.25, gas.kSpec());
        assertEquals(0.40, gas.ownWidthM());
        assertEquals(2.0 + 0.255 + 0.20, gas.requiredAxisDistanceM(100), 1e-9);
        assertEquals(1.15, RestrictionRules.require("power_cable").kSpec());
        RestrictionRule net = RestrictionRules.existingHeatNetwork();
        assertEquals(1.05, net.kSpec());
        assertEquals(1.0, net.clearanceM(100));
        for (String t : new String[]{"park", "social_area", "prohibited_site", "water", "railway"}) {
            assertTrue(RestrictionRules.require(t).isForbidden(), t);
            assertEquals(1.0, RestrictionRules.require(t).clearanceM(1400), t);
        }
        assertFalse(RestrictionRules.forType("metro").isPresent());
    }
}
