package ru.lct.heatnet.depth;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRules;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Vertical profile rules of the depth mode, checked on the optimizer directly (no grid, continuous depths). */
class DepthProfileOptimizerTest {

    private static final double CPM = 100_000;
    private static final double H100 = DiameterTable.requireDu(100).heightM();   // 0.18
    private static final double H400 = DiameterTable.requireDu(400).heightM();   // 0.56

    static VerticalObstacle gas(double from, double to, double hNew) {
        return new VerticalObstacle(RestrictionRules.GAS_PIPELINE, "gas", DepthRules.forType(RestrictionRules.GAS_PIPELINE).get(), from, to, 0.40, 1.25, hNew);
    }
    static VerticalObstacle cable(double from, double to, double hNew) {
        return new VerticalObstacle(RestrictionRules.POWER_CABLE, "cable", DepthRules.forType(RestrictionRules.POWER_CABLE).get(), from, to, 0.20, 1.15, hNew);
    }
    static VerticalObstacle existing(double from, double to, int existingDu, double hNew) {
        return new VerticalObstacle(RestrictionRules.EXISTING_HEAT_NETWORK, "heat_network", DepthRules.forType(RestrictionRules.EXISTING_HEAT_NETWORK).get(), from, to,
                DiameterTable.requireDu(existingDu).heightM(), 1.05, hNew);
    }
    static VerticalObstacle road(double from, double to) {
        return new VerticalObstacle(RestrictionRules.ROAD, "road", DepthRules.forType(RestrictionRules.ROAD).get(), from, to, Double.NaN, 1.60, H100);
    }
    static VerticalObstacle tram(double from, double to) {
        return new VerticalObstacle(RestrictionRules.TRAM_TRACKS, "tram", DepthRules.forType(RestrictionRules.TRAM_TRACKS).get(), from, to, Double.NaN, 1.75, H100);
    }

    static DepthProfileOptimizer.Result run(double length, List<VerticalObstacle> obs, Double start, Double end) {
        return new DepthProfileOptimizer().optimize(length, obs, start, end, s -> CPM);
    }

    static double plateau(DepthProfileOptimizer.Result r, int i) { return r.profile.decisions().get(i).depth; }
    static CrossingDepthOption.Method method(DepthProfileOptimizer.Result r, int plateau, int obj) { return r.profile.decisions().get(plateau).options.get(obj).method(); }

    @Test
    void defaultDepthIsThreeMetres() {
        DepthProfileOptimizer.Result r = run(100, Collections.emptyList(), null, 3.0);
        assertTrue(r.feasible);
        for (double s = 0; s <= 100; s += 10) assertEquals(3.0, r.profile.depthAt(s), 1e-9);
        assertEquals(0, r.extraCost, 1e-9);
        assertEquals(0, r.profile.transitionCount());
    }

    @Test
    void minDepthIsRespected_shallowAboveIsRejected() {
        // a huge new pair (height 2.0 m) cannot pass above the gas pipe within 0.7 m: only BELOW is admissible
        VerticalObstacle g = gas(50, 54, 2.0);
        assertTrue(g.above().isEmpty(), "top would have to be at 0.6 m < 0.7 m");
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(g), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(CrossingDepthOption.Method.BELOW, method(r, 0, 0));
        assertEquals(3.4, plateau(r, 0), 1e-9);
        assertTrue(r.profile.minDepth() >= DepthRules.MIN_DEPTH_M);
    }

    @Test
    void slopeLimitOneMetrePerTenMetres() {
        assertFalse(run(8, Collections.emptyList(), 3.0, 4.0).feasible, "1 m of depth change needs 10 m");
        assertTrue(run(10, Collections.emptyList(), 3.0, 4.0).feasible);
        // BELOW plateau at 3.4 ending 46 m before the fixed 3.0 end: the ramp back takes exactly 4 m
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(gas(50, 54, 2.0)), null, 3.0);
        assertTrue(r.feasible);
        assertTrue(r.profile.maxSlope() <= DepthRules.MAX_SLOPE + 1e-9);
        assertEquals(3.4, r.profile.depthAt(54), 1e-9);
        assertEquals(3.0, r.profile.depthAt(58), 1e-9);
        assertEquals(3.2, r.profile.depthAt(56), 1e-9);
    }

    @Test
    void gasAbove() {
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(gas(50, 54, H100)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(CrossingDepthOption.Method.ABOVE, method(r, 0, 0));
        assertEquals(2.8 - 0.2 - H100, plateau(r, 0), 1e-9, "as close to 3.0 as the clearance allows");
        assertEquals(0, r.extraCost, 1e-9, "shallower than 3 m costs nothing extra");
        assertEquals(3.0, r.profile.depthAt(0), 1e-9);
        assertEquals(3.0, r.profile.depthAt(100), 1e-9);
    }

    @Test
    void gasBelow() {
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(gas(50, 54, 2.0)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(CrossingDepthOption.Method.BELOW, method(r, 0, 0));
        assertEquals(2.8 + 0.4 + 0.2, plateau(r, 0), 1e-9);
        // plateau 4 m at K 1.04 plus two 4 m ramps at mean K 1.02
        assertEquals(4 * CPM * 0.04 + 2 * 4 * CPM * 0.02, r.extraCost, 1e-6);
    }

    @Test
    void gasAboveDepthDependsOnNewDuHeight() {
        double d100 = plateau(run(100, Collections.singletonList(gas(50, 54, H100)), null, 3.0), 0);
        double d400 = plateau(run(100, Collections.singletonList(gas(50, 54, H400)), null, 3.0), 0);
        assertEquals(2.60 - H100, d100, 1e-9);
        assertEquals(2.60 - H400, d400, 1e-9);
        assertTrue(d400 < d100);
    }

    @Test
    void cableAboveAndBelow() {
        DepthProfileOptimizer.Result above = run(100, Collections.singletonList(cable(50, 54, H100)), null, 3.0);
        assertEquals(CrossingDepthOption.Method.ABOVE, method(above, 0, 0));
        assertEquals(2.7 - 0.5 - H100, plateau(above, 0), 1e-9);
        DepthProfileOptimizer.Result below = run(100, Collections.singletonList(cable(50, 54, 2.0)), null, 3.0);
        assertEquals(CrossingDepthOption.Method.BELOW, method(below, 0, 0));
        assertEquals(2.9 + 0.5, plateau(below, 0), 1e-9);
    }

    @Test
    void existingHeatNetworkAboveBelowAndDiameter() {
        DepthProfileOptimizer.Result above = run(100, Collections.singletonList(existing(50, 54, 400, H100)), null, 3.0);
        assertEquals(CrossingDepthOption.Method.ABOVE, method(above, 0, 0));
        assertEquals(3.0 - 0.5 - H100, plateau(above, 0), 1e-9);
        DepthProfileOptimizer.Result below400 = run(100, Collections.singletonList(existing(50, 54, 400, 2.0)), null, 3.0);
        assertEquals(CrossingDepthOption.Method.BELOW, method(below400, 0, 0));
        assertEquals(3.0 + H400 + 0.5, plateau(below400, 0), 1e-9);
        DepthProfileOptimizer.Result below800 = run(100, Collections.singletonList(existing(50, 54, 800, 2.0)), null, 3.0);
        assertEquals(3.0 + DiameterTable.requireDu(800).heightM() + 0.5, plateau(below800, 0), 1e-9, "Hexisting depends on the existing DU");
        assertTrue(plateau(below800, 0) > plateau(below400, 0));
    }

    @Test
    void roadAndTramMinimumCoverAtNormalDepth() {
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(road(40, 60)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(CrossingDepthOption.Method.UNDER, method(r, 0, 0));
        assertEquals(3.0, plateau(r, 0), 1e-9, "normal depth already satisfies >= 1.0 m");
        assertTrue(road(40, 60).passUnder().lo >= 1.0);
        assertTrue(tram(40, 60).passUnder().lo >= 1.2);
        assertTrue(new DepthInterval(0.7, 1.1).intersect(tram(40, 60).passUnder()).isEmpty(), "1.1 m is too shallow under tram tracks");
    }

    @Test
    void overlapRoadAndGasIntersectsConstraints() {
        // road 40..60 (>= 1.0) and gas 48..52: one plateau 40..60 at min(2.42, ...) above the gas, still >= 1.0
        DepthProfileOptimizer.Result r = run(120, Arrays.asList(road(40, 60), gas(48, 52, H100)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(1, r.profile.decisions().size(), "overlapping sections form one plateau");
        DepthProfile.PlateauDecision d = r.profile.decisions().get(0);
        assertEquals(40, d.from, 1e-9);
        assertEquals(60, d.to, 1e-9);
        assertEquals(2.60 - H100, d.depth, 1e-9);
        assertEquals(r.profile.depthAt(41), r.profile.depthAt(59), 1e-9, "constant depth over the whole plateau");
        assertEquals(2, d.options.size());
    }

    @Test
    void overlapMultipleUtilitiesUsesTheTightestInterval() {
        DepthProfileOptimizer.Result r = run(150, Arrays.asList(gas(50, 54, H100), cable(52, 56, H100), existing(55, 59, 400, H100)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(1, r.profile.decisions().size());
        assertEquals(2.7 - 0.5 - H100, plateau(r, 0), 1e-9, "the power cable is the tightest ABOVE constraint");
        for (CrossingDepthOption o : r.profile.decisions().get(0).options) assertEquals(CrossingDepthOption.Method.ABOVE, o.method());
    }

    @Test
    void impossibleCombinationsAreDiscarded() {
        // gas ABOVE would need <= 2.42, tram needs >= 1.2: compatible; but with a 2.0 m pair ABOVE gas is impossible,
        // so the only combination is tram UNDER + gas BELOW at 3.4
        DepthProfileOptimizer.Result r = run(120, Arrays.asList(tram(40, 60), gas(48, 52, 2.0)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(3.4, plateau(r, 0), 1e-9);
        assertEquals(1, r.profile.decisions().get(0).options.stream().filter(o -> o.method() == CrossingDepthOption.Method.BELOW).count());
        // an infeasible interval per object is reported as such
        VerticalObstacle deep = new VerticalObstacle("x", "x", DepthRules.VerticalRule.utility(0.8, 0.2, 0.2), 50, 54, 0.2, 1.0, H100);
        assertTrue(deep.above().isEmpty());
        assertFalse(deep.below().isEmpty());
    }

    @Test
    void closeCrossingsKeepTheAlteredDepth() {
        // gas above at 2.42 (ramp 5.8 m) and cable above at 2.02 (ramp 9.8 m) only 6 m apart: no return to 3.0 in between
        DepthProfileOptimizer.Result r = run(150, Arrays.asList(gas(50, 54, H100), cable(60, 64, H100)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(2.42, r.profile.depthAt(54), 1e-9);
        assertEquals(2.02, r.profile.depthAt(60), 1e-9);
        assertTrue(r.profile.depthAt(57) < 2.5, "single ramp between the plateaus instead of a pointless return to 3.0");
        assertTrue(r.profile.maxSlope() <= DepthRules.MAX_SLOPE + 1e-9);
    }

    @Test
    void returnsToBaselineBetweenDistantCrossings() {
        DepthProfileOptimizer.Result r = run(200, Arrays.asList(gas(50, 54, H100), cable(100, 104, H100)), null, 3.0);
        assertTrue(r.feasible);
        assertEquals(3.0, r.profile.depthAt(77), 1e-9, "profile returns to the normal depth between distant crossings");
        assertEquals(3.0, r.profile.depthAt(150), 1e-9);
        assertEquals(3.0, r.profile.depthAt(0), 1e-9);
    }

    @Test
    void crossingHasAConstantDepthPlateauNotASinglePoint() {
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(gas(50, 54, 2.0)), null, 3.0);
        assertEquals(r.profile.depthAt(50), r.profile.depthAt(52), 1e-9);
        assertEquals(r.profile.depthAt(50), r.profile.depthAt(54), 1e-9);
        boolean flatSegmentAt34 = r.profile.segments().stream().anyMatch(s -> s.isFlat() && Math.abs(s.depthStart - 3.4) < 1e-9 && s.length() >= 4 - 1e-9);
        assertTrue(flatSegmentAt34, r.profile.toString());
    }

    @Test
    void kDepthConstantAndRampAverage() {
        assertEquals(1.00, DepthRules.kDepth(3.0), 1e-12);
        assertEquals(1.00, DepthRules.kDepth(2.0), 1e-12);
        assertEquals(1.04, DepthRules.kDepth(3.4), 1e-12);
        assertEquals(1.10, DepthRules.kDepth(4.0), 1e-12);
        assertEquals(1.20, DepthRules.kDepth(5.0), 1e-12);
        assertEquals(1.05, DepthRules.kDepthRamp(3.0, 4.0), 1e-12);
        assertEquals(1.05, DepthCostCalculator.kDepth(4.0, 3.0), 1e-12);
    }

    @Test
    void splitAtThreeMetresAndRampCost() {
        DepthProfile p = new DepthProfile(Arrays.asList(new double[]{0, 2.4}, new double[]{16, 4.0}), null);
        List<Double> splits = p.splitPositions();
        assertTrue(splits.stream().anyMatch(x -> Math.abs(x - 6.0) < 1e-9), "2.4 -> 4.0 crosses 3.0 at 6 m: " + splits);
        // only the deep part 6..16 (3.0 -> 4.0, mean K 1.05) costs extra
        assertEquals(10 * CPM * 0.05, DepthCostCalculator.extraCost(p, s -> CPM), 1e-6);
    }

    @Test
    void insufficientRampLengthMakesTheRouteInfeasible() {
        // BELOW plateau ending 2 m before a tie-in fixed at 3.0: the 4 m ramp does not fit
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(gas(94, 98, 2.0)), null, 3.0);
        assertFalse(r.feasible);
        assertNotNull(r.reason);
        // with 4 m it fits exactly
        assertTrue(run(100, Collections.singletonList(gas(92, 96, 2.0)), null, 3.0).feasible);
    }

    @Test
    void junctionDepthIsHonouredAtTheParentEnd() {
        // branch must arrive at a junction whose trunk lies at 3.4 m: ramp down within the last 4 m
        DepthProfileOptimizer.Result r = run(50, Collections.emptyList(), null, 3.4);
        assertTrue(r.feasible);
        assertEquals(3.0, r.profile.depthAt(0), 1e-9);
        assertEquals(3.4, r.profile.depthAt(50), 1e-9);
        assertEquals(3.0, r.profile.depthAt(46), 1e-9);
    }
}
