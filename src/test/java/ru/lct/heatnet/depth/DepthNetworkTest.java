package ru.lct.heatnet.depth;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationIssue;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

/** Depth mode on whole networks: depth-aware horizontal rerouting, shared trunks, continuity at junctions. */
class DepthNetworkTest {

    static SolveResult solve(InputModel in, boolean depth) {
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = depth;
        return new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
    }

    static List<OutputFeature> render(VariantResult v) { return new ArrayList<>(new OutputBuilder().build(v)); }

    static double rootX(VariantResult v) { return v.network().roots().get(0).coord().x - SyntheticInput.OX; }

    /**
     * Critical scenario: the existing line runs along y = 0; a gas pipeline runs parallel 3 m away for x in 0..150.
     * Any tie-in at x <= 150 needs a gas crossing 3 m before the tie-in: neither the ABOVE ramp (5.8 m) nor the
     * BELOW ramp (4 m) fits, so the depth solver must attach farther east (x > 150) although the planar route is
     * shorter.
     */
    @Test
    void depthSolverChoosesTheLongerVerticallyFeasibleRoute() {
        InputModel in = new SyntheticInput()
                .line(1, 400, 0, 0, 300, 0)
                .polyline(2, "gas_pipeline", 0, 3, 150, 3)
                .rect(3, "oks", 50, 95, 70, 105).point("A", 60, 100, 10.0)
                .build();
        VariantResult planar = solve(in, false).variants().get(0);
        assertEquals(1, planar.connectedCount());
        assertTrue(rootX(planar) < 100, "planar ties in straight below the building: x=" + rootX(planar));
        boolean planarCrossesGas = render(planar).stream().anyMatch(f -> "heat_network".equals(f.objectType()) && String.valueOf(f.prop("crossed_restrictions")).contains("gas"));
        assertTrue(planarCrossesGas);

        SolveResult dr = solve(in, true);
        assertFalse(dr.variants().isEmpty(), dr.diagnostics().messages().toString());
        VariantResult depth = dr.variants().get(0);
        assertEquals(1, depth.connectedCount(), "the point must stay connected: " + depth.unconnected());
        // the attachment lies outside the extent of the pipeline (x in 0..150): east of its end, or at the line start
        // west of it (both corridors avoid the pipeline; the cheaper one wins)
        assertTrue(rootX(depth) > 150 || rootX(depth) <= 0 + 1e-6, "depth-aware route attaches outside the extent of the gas pipeline: x=" + rootX(depth));
        List<OutputFeature> fs = render(depth);
        assertTrue(fs.stream().noneMatch(f -> "heat_network".equals(f.objectType()) && String.valueOf(f.prop("crossed_restrictions")).contains("gas")));
        assertTrue(depth.summary().newLength() > planar.summary().newLength(), "longer in plan, but vertically feasible");
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        for (OutputFeature f : fs) if ("heat_network".equals(f.objectType())) assertEquals(3.0, ((Number) f.prop("depth_start")).doubleValue(), 1e-9);
    }

    /** Two consumers share a trunk that crosses a gas pipeline: one physical profile, branch depths match at the junction. */
    @Test
    void sharedTrunkHasOneProfileAndBranchesMatchTheJunctionDepth() {
        InputModel in = new SyntheticInput()
                .line(1, 400, 0, 0, 400, 0)
                .rect(1, "oks", 0, 100, 170, 340).rect(2, "oks", 250, 100, 400, 340)
                .rect(3, "oks", 180, 300, 200, 320).point("A", 190, 310, 20.0)
                .rect(4, "oks", 220, 300, 240, 320).point("B", 230, 310, 20.0)
                .polyline(5, "gas_pipeline", 170, 60, 260, 60)
                .build();
        SolveResult r = solve(in, true);
        assertFalse(r.variants().isEmpty());
        VariantResult v = r.variants().get(0);
        assertEquals(2, v.connectedCount());
        assertEquals(1, v.network().roots().size(), "shared trunk");
        List<OutputFeature> fs = render(v);
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        assertTrue(rep.getChecksPerformed().getOrDefault("depth:continuity", 0) >= 1);
        // the gas crossing sits on the trunk (flow 40) and has a constant plateau above the pipe
        OutputFeature crossing = fs.stream().filter(f -> "heat_network".equals(f.objectType()) && String.valueOf(f.prop("crossed_restrictions")).contains("gas")).findFirst().orElse(null);
        assertNotNull(crossing);
        assertEquals(40.0, ((Number) crossing.prop("flow_tph")).doubleValue(), 1e-6);
        assertEquals(crossing.prop("depth_start"), crossing.prop("depth_end"));
        assertEquals(2.60 - DiameterTable.requireDu(((Number) crossing.prop("diameter")).intValue()).heightM(), ((Number) crossing.prop("depth_start")).doubleValue(), 1e-3);
        // every node: all lines meeting there report the same depth
        java.util.Map<Object, java.util.Set<Double>> atNode = new java.util.HashMap<>();
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType())) continue;
            atNode.computeIfAbsent(f.prop("start_node_id"), k -> new java.util.HashSet<>()).add(((Number) f.prop("depth_start")).doubleValue());
            atNode.computeIfAbsent(f.prop("end_node_id"), k -> new java.util.HashSet<>()).add(((Number) f.prop("depth_end")).doubleValue());
        }
        for (java.util.Map.Entry<Object, java.util.Set<Double>> e : atNode.entrySet()) assertEquals(1, e.getValue().size(), "depth mismatch at node " + e.getKey() + ": " + e.getValue());
    }

    private static double[] ll(double x, double y) { return CrsTransformer.get().toWgs84(c(x, y).x, c(x, y).y); }

    /** Hand-built output with a depth jump at a technical node is rejected by the validator. */
    @Test
    void depthJumpAtANodeIsInvalid() {
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 200, 0).point("A", 100, 100, 10).build();
        double cpm = DiameterTable.requireDu(80).costPerMeter();
        OutputFeature n1 = OutputFeature.line(Arrays.asList(ll(100, 100), ll(100, 50))).prop("id", "n1").prop("object_type", "heat_network").prop("variant_id", "v1")
                .prop("start_node_id", "A").prop("end_node_id", "t1").prop("flow_tph", 10.0).prop("diameter", 80).prop("length", 50.0).prop("laying_method", "base")
                .prop("depth_start", 3.0).prop("depth_end", 3.0).prop("cost", Math.round(50 * cpm));
        OutputFeature n2 = OutputFeature.line(Arrays.asList(ll(100, 50), ll(100, 0))).prop("id", "n2").prop("object_type", "heat_network").prop("variant_id", "v1")
                .prop("start_node_id", "t1").prop("end_node_id", "R").prop("flow_tph", 10.0).prop("diameter", 80).prop("length", 50.0).prop("laying_method", "base")
                .prop("depth_start", 4.2).prop("depth_end", 4.2).prop("cost", Math.round(50 * cpm * 1.12));
        OutputFeature t1 = OutputFeature.point(ll(100, 50)).prop("id", "t1").prop("object_type", "technical_node").prop("variant_id", "v1");
        OutputFeature r = OutputFeature.point(ll(100, 0)).prop("id", "R").prop("object_type", "heat_chamber").prop("variant_id", "v1").prop("diameter", 300).prop("cost", 5_000_000);
        OutputFeature s = OutputFeature.noGeometry().prop("id", "v1_summary").prop("object_type", "variant_summary").prop("variant_id", "v1").prop("rank", 1)
                .prop("mode", "depth").prop("construction_cost", 0).prop("chamber_construction_cost", 0).prop("existing_chamber_tie_in_count", 0).prop("existing_chamber_tie_in_cost", 0)
                .prop("unconnected_penalty", 0).prop("calculated_cost", 0).prop("new_network_length", 100).prop("score", 0).prop("unconnected_oks_ids", new ArrayList<>());
        ValidationReport rep = new ResultValidator(in).validate(Arrays.asList(n1, n2, t1, r, s));
        assertTrue(rep.getIssues().stream().anyMatch(i -> i.getCode().equals("DEPTH_CONTINUITY")), rep.getIssues().toString());
        // and a slope violation on a feature is reported as well
        n2.prop("depth_start", 3.0).prop("depth_end", 4.2).prop("cost", Math.round(50 * cpm * 1.06));
        ValidationReport rep2 = new ResultValidator(in).validate(Arrays.asList(n1, n2, t1, r, s));
        assertFalse(rep2.getIssues().stream().anyMatch(i -> i.getCode().equals("DEPTH_CONTINUITY")));
        n2.prop("depth_end", 9.0);
        ValidationReport rep3 = new ResultValidator(in).validate(Arrays.asList(n1, n2, t1, r, s));
        assertTrue(rep3.getIssues().stream().anyMatch(i -> i.getCode().equals("DEPTH_SLOPE")), rep3.getIssues().toString());
    }

    /** Without any vertical object every feature stays at the normal depth of 3.0 m. */
    @Test
    void plainRouteStaysAtNormalDepth() {
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 200, 0).rect(2, "oks", 90, 90, 110, 110).point("A", 100, 100, 10).build();
        VariantResult v = solve(in, true).variants().get(0);
        for (OutputFeature f : render(v)) {
            if (!"heat_network".equals(f.objectType())) continue;
            assertEquals(3.0, ((Number) f.prop("depth_start")).doubleValue(), 1e-9);
            assertEquals(3.0, ((Number) f.prop("depth_end")).doubleValue(), 1e-9);
            assertEquals(1.0, ((Number) f.prop("k_depth")).doubleValue(), 1e-9);
        }
    }
}
