package ru.lct.heatnet.depth;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.ValidationIssue;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

/**
 * Depth adversarial matrix, optimizer- and validator-level cases (2, 3, 9, 11, 12, 13, 14). Network-level cases are in
 * {@link DepthAdversarialTest}.
 */
class DepthAdversarialProfileTest {

    private static final double CPM = 100_000;
    private static final double H80 = DiameterTable.requireDu(80).heightM();      // 0.16
    private static final double H100 = DiameterTable.requireDu(100).heightM();    // 0.18

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
    static VerticalObstacle tram(double from, double to) {
        return new VerticalObstacle(RestrictionRules.TRAM_TRACKS, "tram", DepthRules.forType(RestrictionRules.TRAM_TRACKS).get(), from, to, Double.NaN, 1.75, H100);
    }
    static DepthProfileOptimizer.Result run(double length, List<VerticalObstacle> obs, Double start, Double end) {
        return new DepthProfileOptimizer().optimize(length, obs, start, end, s -> CPM);
    }
    static CrossingDepthOption.Method method(DepthProfileOptimizer.Result r, int plateau, int obj) { return r.profile.decisions().get(plateau).options.get(obj).method(); }
    static double plateau(DepthProfileOptimizer.Result r, int i) { return r.profile.decisions().get(i).depth; }

    /** Case 2: ABOVE feasible, BELOW infeasible (existing DU1400: BELOW at 5.1 m needs a 21 m ramp into a 7 m gap). */
    @Test
    void case02_aboveFeasibleBelowInfeasible() {
        VerticalObstacle big = existing(89, 93, 1400, H100);
        assertEquals(3.0 + 1.6 + 0.5, big.below().lo, 1e-9);
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(big), null, 3.0);
        assertTrue(r.feasible, r.reason);
        assertEquals(CrossingDepthOption.Method.ABOVE, method(r, 0, 0));
        assertEquals(2.5 - H100, plateau(r, 0), 1e-9);
        assertEquals(3.0, r.profile.depthAt(100), 1e-9);
        assertTrue(r.profile.maxSlope() <= 0.1 + 1e-9);
        // proof that BELOW alone is infeasible here: an object whose ABOVE interval is empty (huge new pair) in the same place
        VerticalObstacle belowOnly = existing(89, 93, 1400, 1.9);
        assertTrue(belowOnly.above().isEmpty());
        DepthProfileOptimizer.Result rb = run(100, Collections.singletonList(belowOnly), null, 3.0);
        assertFalse(rb.feasible, "BELOW at 5.1 m cannot ramp back to 3.0 within 7 m");
        assertNotNull(rb.reason);
    }

    /** Case 3: BELOW feasible, ABOVE infeasible (a 1.6 m pair above a cable would put the top at 0.6 m < 0.7 m). */
    @Test
    void case03_belowFeasibleAboveInfeasible() {
        VerticalObstacle cb = cable(50, 54, 1.6);
        assertTrue(cb.above().isEmpty(), "top would be 2.2 - 1.6 = 0.6 m");
        assertFalse(cb.below().isEmpty());
        DepthProfileOptimizer.Result r = run(100, Collections.singletonList(cb), null, 3.0);
        assertTrue(r.feasible, r.reason);
        assertEquals(CrossingDepthOption.Method.BELOW, method(r, 0, 0));
        assertEquals(2.7 + 0.2 + 0.5, plateau(r, 0), 1e-9);
        assertEquals(1, r.profile.decisions().get(0).options.size());
    }

    /** Case 9: tram (>= 1.2) + cable of a 1.1 m pair: ABOVE would need <= 1.1 m -> empty intersection -> BELOW. */
    @Test
    void case09_overlapWithEmptyAboveIntersection() {
        VerticalObstacle t = tram(40, 60), cb = cable(48, 52, 1.1);
        assertFalse(cb.above().isEmpty(), "cable ABOVE alone is possible (top <= 1.1 m)");
        assertTrue(cb.above().intersect(t.passUnder()).isEmpty(), "but not together with the tram cover");
        DepthProfileOptimizer.Result r = run(120, Arrays.asList(t, cb), null, 3.0);
        assertTrue(r.feasible, r.reason);
        assertEquals(1, r.profile.decisions().size());
        DepthProfile.PlateauDecision d = r.profile.decisions().get(0);
        assertEquals(3.4, d.depth, 1e-9);
        boolean under = false, below = false;
        for (CrossingDepthOption o : d.options) { if (o.method() == CrossingDepthOption.Method.UNDER) under = true; if (o.method() == CrossingDepthOption.Method.BELOW) below = true; }
        assertTrue(under && below);
        // with a small pair the ABOVE combination is available and chosen (no extra cost)
        DepthProfileOptimizer.Result r2 = run(120, Arrays.asList(t, cable(48, 52, H100)), null, 3.0);
        assertEquals(2.2 - H100, plateau(r2, 0), 1e-9);
    }

    /** Case 11: two deep (BELOW-only) crossings 36 m apart: returning to 3.0 m between them is cheaper than holding 3.4 m. */
    @Test
    void case11_returnToNormalDepthIsCheaper() {
        DepthProfileOptimizer.Result r = run(150, Arrays.asList(gas(50, 54, 2.0), gas(90, 94, 2.0)), null, 3.0);
        assertTrue(r.feasible, r.reason);
        assertEquals(3.4, plateau(r, 0), 1e-9);
        assertEquals(3.4, plateau(r, 1), 1e-9);
        assertEquals(3.0, r.profile.depthAt(72), 1e-9, "back at the normal depth between the crossings");
        assertEquals(3.0, r.profile.depthAt(58), 1e-9);
        assertEquals(3.0, r.profile.depthAt(86), 1e-9);
        double ramp = 4 * CPM * 0.02, plateauCost = 4 * CPM * 0.04;
        assertEquals(2 * plateauCost + 4 * ramp, r.extraCost, 1e-6);
        double holdCost = 2 * plateauCost + 2 * ramp + 36 * CPM * 0.04;
        assertTrue(r.extraCost < holdCost, "return (" + r.extraCost + ") cheaper than holding 3.4 m (" + holdCost + ")");
        assertEquals(4, r.profile.transitionCount());
    }

    /** Case 12: the same crossings only 10 m apart: two ramps plus a 5 m run do not fit, the depth is held at 3.4 m. */
    @Test
    void case12_returnImpossibleNextCrossingTooClose() {
        DepthProfileOptimizer.Result r = run(150, Arrays.asList(gas(50, 54, 2.0), gas(64, 68, 2.0)), null, 3.0);
        assertTrue(r.feasible, r.reason);
        assertEquals(3.4, r.profile.depthAt(59), 1e-9, "held between the plateaus");
        assertEquals(3.4, r.profile.depthAt(54), 1e-9);
        assertEquals(3.4, r.profile.depthAt(64), 1e-9);
        double ramp = 4 * CPM * 0.02;
        assertEquals(18 * CPM * 0.04 + 2 * ramp, r.extraCost, 1e-6, "one 18 m plateau at K 1.04 plus two ramps");
        assertEquals(2, r.profile.transitionCount());
        assertEquals(3.0, r.profile.depthAt(40), 1e-9);
        assertEquals(3.0, r.profile.depthAt(80), 1e-9);
    }

    /** Case 13: a slope of exactly 0.10 is accepted by the optimizer and by the validator; 0.1001 is not. */
    @Test
    void case13_exactSlopeLimit() {
        DepthProfileOptimizer.Result exact = run(10.0, Collections.emptyList(), 3.0, 4.0);
        assertTrue(exact.feasible);
        assertEquals(0.10, exact.profile.maxSlope(), 1e-12);
        assertFalse(run(9.99, Collections.emptyList(), 3.0, 4.0).feasible);
        // BELOW plateau ending exactly 4 m before the fixed end: the 0.4 m ramp uses the full slope
        DepthProfileOptimizer.Result tight = run(100, Collections.singletonList(gas(92, 96, 2.0)), null, 3.0);
        assertTrue(tight.feasible);
        assertEquals(0.10, tight.profile.maxSlope(), 1e-9);
        assertFalse(run(100, Collections.singletonList(gas(92.1, 96.1, 2.0)), null, 3.0).feasible);
        // validator: 3.0 -> 4.0 over 10.000 m passes, over 9.990 m fails
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 200, 0).point("A", 100, 60, 10).build();
        ValidationReport ok = new ResultValidator(in).validate(handBuilt(new double[][]{{60, 50, 3.0, 3.0}, {50, 40, 3.0, 4.0}, {40, 0, 4.0, 4.0}}, null));
        assertFalse(has(ok, "DEPTH_SLOPE"), ok.getIssues().toString());
        assertFalse(has(ok, "DEPTH_CONTINUITY"));
        ValidationReport bad = new ResultValidator(in).validate(handBuilt(new double[][]{{60, 50.01, 3.0, 3.0}, {50.01, 40.02, 3.0, 4.0}, {40.02, 0, 4.0, 4.0}}, null));
        assertTrue(has(bad, "DEPTH_SLOPE"), bad.getIssues().toString());
    }

    /**
     * Case 14: a crossing "plateau" of almost zero length (1 mm special piece inside the 4 m gas section, the rest of
     * the section laid as base) is rejected: the section must be covered by special features.
     */
    @Test
    void case14_almostZeroLengthPlateauIsInvalid() {
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 200, 0).point("A", 100, 100, 10)
                .polyline(5, "gas_pipeline", 0, 50, 200, 50).build();
        // ramps 3.0 -> 2.44 over 5.6 m around a 1 mm plateau at 2.44 (ABOVE, clearance satisfied)
        double top = 2.6 - H80;
        double[][] segs = {{100, 55.6005, 3.0, 3.0}, {55.6005, 50.0005, 3.0, top}, {50.0005, 49.9995, top, top}, {49.9995, 44.3995, top, 3.0}, {44.3995, 0, 3.0, 3.0}};
        ValidationReport rep = new ResultValidator(in).validate(handBuilt(segs, 2));
        assertFalse(rep.isValid());
        assertTrue(has(rep, "CROSSING_NOT_SPECIAL"), "base pieces cover the gas section: " + rep.getIssues());
        // the same geometry with the full 4 m section as the plateau is accepted by the crossing checks
        double[][] good = {{100, 57.6, 3.0, 3.0}, {57.6, 52.0, 3.0, top}, {52.0, 48.0, top, top}, {48.0, 42.4, top, 3.0}, {42.4, 0, 3.0, 3.0}};
        ValidationReport rep2 = new ResultValidator(in).validate(handBuilt(good, 2));
        assertFalse(has(rep2, "CROSSING_NOT_SPECIAL"), rep2.getIssues().toString());
        assertFalse(has(rep2, "GAS_VERTICAL_CLEARANCE"), rep2.getIssues().toString());
        assertFalse(has(rep2, "CONSTANT_CROSSING_DEPTH"));
    }

    // ------------------------------------------------------------------------------------------------------------

    static boolean has(ValidationReport rep, String code) {
        for (ValidationIssue i : rep.getIssues()) if (code.equals(i.getCode())) return true;
        return false;
    }

    private static double[] ll(double x, double y) { return CrsTransformer.get().toWgs84(c(x, y).x, c(x, y).y); }

    /**
     * Hand-built depth-mode output along x = 100 from A (top) down to a new chamber R on the existing line:
     * segments {yFrom, yTo, depthStart, depthEnd}; the segment with index {@code specialIndex} is a special (gas) piece.
     */
    static List<OutputFeature> handBuilt(double[][] segs, Integer specialIndex) {
        int du = 80;
        double cpm = DiameterTable.requireDu(du).costPerMeter();
        List<OutputFeature> out = new ArrayList<>();
        double totalLen = 0, lineCost = 0, maxDepth = 0, weighted = 0, deep = 0, shallow = 0, extra = 0;
        int transitions = 0;
        for (int i = 0; i < segs.length; i++) {
            double[] s = segs[i];
            String from = i == 0 ? "A" : "t" + i, to = i == segs.length - 1 ? "R" : "t" + (i + 1);
            double len = Math.round(Math.abs(s[0] - s[1]) * 1000) / 1000.0;
            boolean special = specialIndex != null && specialIndex == i;
            double k = special ? 1.25 : 1.0;
            double kd = (DepthRules.kDepth(s[2]) + DepthRules.kDepth(s[3])) / 2;
            long cost = Math.round(len * cpm * k * kd);
            out.add(OutputFeature.line(Arrays.asList(ll(100, s[0]), ll(100, s[1]))).prop("id", "n" + (i + 1)).prop("object_type", "heat_network").prop("variant_id", "v1")
                    .prop("start_node_id", from).prop("end_node_id", to).prop("flow_tph", 10.0).prop("diameter", du).prop("length", len)
                    .prop("laying_method", special ? "special" : "base").prop("depth_start", s[2]).prop("depth_end", s[3]).prop("cost", cost)
                    .prop("k_spec", k).prop("k_depth", kd));
            if (i > 0) out.add(OutputFeature.point(ll(100, s[0])).prop("id", from).prop("object_type", "technical_node").prop("variant_id", "v1"));
            totalLen += len; lineCost += cost; maxDepth = Math.max(maxDepth, Math.max(s[2], s[3]));
            double mid = (s[2] + s[3]) / 2; weighted += mid * len;
            if (mid > 3.0 + 1e-6) deep += len; else if (mid < 3.0 - 1e-6) shallow += len;
            if (Math.abs(s[3] - s[2]) > 1e-3) transitions++;
            extra += cost - Math.round(len * cpm * k);
        }
        double chamber = ru.lct.heatnet.config.ChamberCostTable.newChamberCost(300);
        out.add(OutputFeature.point(ll(100, 0)).prop("id", "R").prop("object_type", "heat_chamber").prop("variant_id", "v1").prop("diameter", 300)
                .prop("cost", Math.round(chamber)).prop("chamber_kind", "tie_in_on_existing_network").prop("existing_line_id", 1));
        double construction = lineCost + chamber;
        int vertical = specialIndex == null ? 0 : 1;
        out.add(OutputFeature.noGeometry().prop("id", "v1_summary").prop("object_type", "variant_summary").prop("variant_id", "v1").prop("rank", 1)
                .prop("mode", "depth").prop("construction_cost", Math.round(construction)).prop("chamber_construction_cost", Math.round(chamber))
                .prop("network_construction_cost", Math.round(lineCost)).prop("existing_chamber_tie_in_count", 0).prop("existing_chamber_tie_in_cost", 0)
                .prop("unconnected_penalty", 0).prop("calculated_cost", Math.round(construction)).prop("new_network_length", Math.round(totalLen * 1000) / 1000.0)
                .prop("score", Math.round((0.7 * construction / 25_000_000 + 0.3 * totalLen / 100) * 10000) / 10000.0).prop("unconnected_oks_ids", new ArrayList<>())
                .prop("connected_oks_count", 1)
                .prop("max_depth", maxDepth).prop("min_depth", 2.0).prop("average_depth", weighted / totalLen).prop("deep_network_length", deep)
                .prop("shallow_network_length", shallow).prop("depth_transition_count", transitions).prop("vertical_crossing_count", vertical)
                .prop("above_crossing_count", vertical).prop("below_crossing_count", 0).prop("pass_under_count", 0).prop("depth_extra_cost", Math.round(extra)));
        return out;
    }
}
