package ru.lct.heatnet.depth;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Depth adversarial matrix (network level): whole-network runs in DEPTH mode on synthetic scenes. Every solved case is
 * validated by the independent validator and by the invariants of {@link #checkInvariants} (continuity at nodes, one
 * profile per link, Kdepth per piece, constant plateaus, crossing details recomputed with hand-typed vertical rules).
 * Optimizer / validator-level cases live in {@link DepthAdversarialProfileTest}.
 */
class DepthAdversarialTest {

    // ---- hand-typed vertical rules of the annex (Table 2, section 5), independent of DepthRules ----
    private static final double GAS_TOP = 2.8, GAS_H = 0.4, GAS_CLEAR = 0.2;
    private static final double CABLE_TOP = 2.7, CABLE_H = 0.2, CABLE_CLEAR = 0.5;
    private static final double HEAT_TOP = 3.0, HEAT_CLEAR = 0.5;
    private static final double ROAD_MIN = 1.0, TRAM_MIN = 1.2;
    private static final double MIN_DEPTH = 0.7, NORMAL = 3.0, MAX_SLOPE = 0.10;

    static double h(int du) { return DiameterTable.requireDu(du).heightM(); }

    static SolveResult solve(InputModel in) {
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = true;
        return new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
    }

    static final class Solved {
        final InputModel in;
        final VariantResult v;
        final List<OutputFeature> fs;
        final ValidationReport rep;
        Solved(InputModel in, VariantResult v, List<OutputFeature> fs, ValidationReport rep) { this.in = in; this.v = v; this.fs = fs; this.rep = rep; }
        List<OutputFeature> lines() { List<OutputFeature> out = new ArrayList<>(); for (OutputFeature f : fs) if ("heat_network".equals(f.objectType())) out.add(f); return out; }
        OutputFeature summary() { for (OutputFeature f : fs) if ("variant_summary".equals(f.objectType())) return f; return null; }
    }

    /** Solves in depth mode, renders the best variant, validates it and checks the invariants. */
    static Solved run(InputModel in, int expectedConnected) {
        SolveResult r = solve(in);
        assertFalse(r.variants().isEmpty(), r.diagnostics().messages().toString());
        VariantResult v = r.variants().get(0);
        assertEquals(expectedConnected, v.connectedCount(), "unconnected: " + v.unconnected());
        List<OutputFeature> fs = new ArrayList<>(new OutputBuilder().build(v));
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        Solved s = new Solved(in, v, fs, rep);
        checkInvariants(s);
        return s;
    }

    // ---- accessors ----
    static double d0(OutputFeature f) { return ((Number) f.prop("depth_start")).doubleValue(); }
    static double d1(OutputFeature f) { return ((Number) f.prop("depth_end")).doubleValue(); }
    static double len(OutputFeature f) { return ((Number) f.prop("length")).doubleValue(); }
    static double flow(OutputFeature f) { return ((Number) f.prop("flow_tph")).doubleValue(); }
    static int du(OutputFeature f) { return ((Number) f.prop("diameter")).intValue(); }
    static boolean special(OutputFeature f) { return "special".equals(f.prop("laying_method")); }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> crossings(OutputFeature f) {
        Object c = f.prop("crossings");
        return c == null ? new ArrayList<>() : (List<Map<String, Object>>) c;
    }
    static boolean crosses(OutputFeature f, String type) { for (Map<String, Object> c : crossings(f)) if (type.equals(c.get("type"))) return true; return false; }
    static Map<String, Object> crossing(OutputFeature f, String type) { for (Map<String, Object> c : crossings(f)) if (type.equals(c.get("type"))) return c; return null; }
    static double num(Map<String, Object> m, String k) { return ((Number) m.get(k)).doubleValue(); }
    static List<OutputFeature> crossingLines(Solved s, String type) { List<OutputFeature> out = new ArrayList<>(); for (OutputFeature f : s.lines()) if (crosses(f, type)) out.add(f); return out; }

    /** Metric coordinates (relative to the synthetic origin) of the root the consumer is attached to. */
    static Coordinate rootOf(VariantResult v, String cpId) {
        for (NetNode n : v.network().connectionNodes()) {
            if (!n.connectionPoint().id().text().equals(cpId)) continue;
            NetNode r = n;
            while (r.parent() != null) r = r.parent().parent();
            return new Coordinate(r.coord().x - SyntheticInput.OX, r.coord().y - SyntheticInput.OY);
        }
        return null;
    }

    /** Independent recomputation of a vertical crossing with hand-typed rule numbers and the FINAL DU of the line. */
    static void assertVerticalRule(InputModel in, OutputFeature f, Map<String, Object> c) {
        String type = (String) c.get("type");
        double top = d0(f), bottom = top + h(du(f));
        assertEquals(top, num(c, "new_top"), 1e-6, "new_top must equal the piece depth");
        assertEquals(h(du(f)), num(c, "new_height"), 1e-6, "new_height must be the height of the final DU");
        assertEquals(bottom, num(c, "new_bottom"), 1e-6);
        switch (type) {
            case "gas_pipeline": checkUtility(c, top, bottom, GAS_TOP, GAS_TOP + GAS_H, GAS_CLEAR); break;
            case "power_cable": checkUtility(c, top, bottom, CABLE_TOP, CABLE_TOP + CABLE_H, CABLE_CLEAR); break;
            case "heat_network": {
                String label = String.valueOf(c.get("object"));
                int existingDu = -1;
                for (ExistingNetworkLine l : in.networkLines()) if (label.endsWith("#" + l.id().text())) existingDu = l.diameter();
                assertTrue(existingDu > 0, "crossed existing line not found for " + label);
                checkUtility(c, top, bottom, HEAT_TOP, HEAT_TOP + h(DiameterTable.nearestForExisting(existingDu).du()), HEAT_CLEAR);
                break;
            }
            case "road": assertEquals("UNDER", c.get("method")); assertTrue(top >= ROAD_MIN - 1e-6, "road cover " + top); assertEquals(top, num(c, "actual_depth"), 1e-6); break;
            case "tram_tracks": assertEquals("UNDER", c.get("method")); assertTrue(top >= TRAM_MIN - 1e-6, "tram cover " + top); break;
            default: fail("unexpected crossing type " + type);
        }
    }

    private static void checkUtility(Map<String, Object> c, double top, double bottom, double uTop, double uBottom, double clear) {
        assertEquals(uTop, num(c, "utility_top"), 1e-6);
        assertEquals(uBottom, num(c, "utility_bottom"), 1e-6);
        assertEquals(clear, num(c, "required_clearance"), 1e-9);
        String m = (String) c.get("method");
        if ("ABOVE".equals(m)) {
            assertTrue(uTop - bottom >= clear - 1e-6, "ABOVE clearance " + (uTop - bottom) + " < " + clear);
            assertEquals(uTop - bottom, num(c, "actual_clearance"), 2e-3);
        } else if ("BELOW".equals(m)) {
            assertTrue(top - uBottom >= clear - 1e-6, "BELOW clearance " + (top - uBottom) + " < " + clear);
            assertEquals(top - uBottom, num(c, "actual_clearance"), 2e-3);
        } else fail("utility crossing must be ABOVE or BELOW: " + m);
    }

    /** Invariants of every solved depth-mode network. */
    static void checkInvariants(Solved s) {
        Map<Object, Set<Long>> depthAtNode = new HashMap<>();
        Map<Integer, List<OutputFeature>> byLink = new TreeMap<>();
        for (OutputFeature f : s.lines()) {
            double a = d0(f), b = d1(f), L = len(f);
            assertTrue(a >= MIN_DEPTH - 1e-6 && b >= MIN_DEPTH - 1e-6, "min depth " + f.properties());
            assertTrue(Math.abs(b - a) / L <= MAX_SLOPE + 1e-6, "slope " + f.properties());
            assertFalse((a < NORMAL - 1e-6 && b > NORMAL + 1e-6) || (a > NORMAL + 1e-6 && b < NORMAL - 1e-6), "piece crosses 3.0 m inside: " + f.properties());
            double kExp = (kd(a) + kd(b)) / 2.0;
            assertEquals(kExp, ((Number) f.prop("k_depth")).doubleValue(), 1e-4, "Kdepth of piece " + f.prop("id"));
            if (special(f)) {
                assertEquals(a, b, 1e-9, "constant plateau on special piece " + f.prop("id"));
                assertNotNull(f.prop("crossings"));
                for (Map<String, Object> c : crossings(f)) assertVerticalRule(s.in, f, c);
            } else {
                assertNull(f.prop("crossings"));
            }
            depthAtNode.computeIfAbsent(f.prop("start_node_id"), k -> new HashSet<>()).add(Math.round(a * 1e6));
            depthAtNode.computeIfAbsent(f.prop("end_node_id"), k -> new HashSet<>()).add(Math.round(b * 1e6));
            byLink.computeIfAbsent(((Number) f.prop("link_id")).intValue(), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<Object, Set<Long>> e : depthAtNode.entrySet()) assertEquals(1, e.getValue().size(), "depth continuity at node " + e.getKey() + ": " + e.getValue());
        // one profile per link: pieces are emitted in order from the child end; consecutive pieces share node and depth
        for (Map.Entry<Integer, List<OutputFeature>> e : byLink.entrySet()) {
            List<OutputFeature> ps = e.getValue();
            for (int i = 0; i + 1 < ps.size(); i++) {
                assertEquals(ps.get(i).prop("end_node_id"), ps.get(i + 1).prop("start_node_id"), "pieces of link " + e.getKey() + " are not chained");
                assertEquals(d1(ps.get(i)), d0(ps.get(i + 1)), 1e-9, "profile discontinuity inside link " + e.getKey());
                assertEquals(du(ps.get(i)), du(ps.get(i + 1)), "one DU per link");
            }
        }
        // summary counts match the pieces
        OutputFeature sum = s.summary();
        assertEquals("depth", sum.prop("mode"));
        // physical crossings: one per (link, crossed object); a section split into several pieces is still one crossing
        int vertical = 0, above = 0, below = 0;
        double max = 0;
        Set<String> seen = new HashSet<>();
        for (OutputFeature f : s.lines()) {
            max = Math.max(max, Math.max(d0(f), d1(f)));
            for (Map<String, Object> c : crossings(f)) {
                if ("UNDER".equals(c.get("method"))) continue;
                if (!seen.add(f.prop("link_id") + "|" + c.get("object"))) continue;
                vertical++;
                if ("ABOVE".equals(c.get("method"))) above++; else below++;
            }
        }
        assertEquals(vertical, ((Number) sum.prop("vertical_crossing_count")).intValue());
        assertEquals(above, ((Number) sum.prop("above_crossing_count")).intValue());
        assertEquals(below, ((Number) sum.prop("below_crossing_count")).intValue());
        assertEquals(max, ((Number) sum.prop("max_depth")).doubleValue(), 2e-3);
    }

    static double kd(double h) { return h <= NORMAL + 1e-9 ? 1.0 : 1.0 + 0.10 * (h - NORMAL); }

    // ---- scene helpers (metric coordinates relative to (400000, 6170000)) ----
    static SyntheticInput line(double half) { return new SyntheticInput().line(1, 400, -half, 0, half, 0); }
    /** Consumer whose nearest own-boundary side is the south side (exit towards the existing line at y = 0). */
    static SyntheticInput south(SyntheticInput b, String id, double x, double y, double flow) { return b.rect("oks_" + id, "oks", x - 10, y - 5, x + 10, y + 15).point(id, x, y, flow); }
    /** Consumer whose nearest own-boundary side is the west side. */
    static SyntheticInput west(SyntheticInput b, String id, double x, double y, double flow) { return b.rect("oks_" + id, "oks", x - 5, y - 8, x + 15, y + 8).point(id, x, y, flow); }

    static OutputFeature only(List<OutputFeature> l, String what) { assertEquals(1, l.size(), what + ": " + l.size() + " pieces"); return l.get(0); }

    // ============================================================================================================

    /**
     * Case 1: the straight corridor crosses a gas pipeline 3 m before the tie-in (plateau ends 1 m before the
     * root: neither ramp fits), the gap in the gas wall carries a power cable with the same problem, only the
     * corridor around the end of the wall is vertically feasible.
     */
    @Test
    void case01_firstTwoCorridorsInfeasibleThirdFeasible() {
        InputModel in = south(line(300), "A", 0, 100, 10)
                .polyline(10, "gas_pipeline", -100, 3, 40, 3)
                .polyline(11, "gas_pipeline", 60, 3, 100, 3)
                .polyline(12, "power_cable", 40, 3, 60, 3)
                .build();
        Solved s = run(in, 1);
        assertTrue(crossingLines(s, "gas_pipeline").isEmpty(), "no vertically infeasible gas crossing");
        assertTrue(crossingLines(s, "power_cable").isEmpty(), "no vertically infeasible cable crossing");
        Coordinate root = rootOf(s.v, "A");
        assertTrue(Math.abs(root.x) > 100 - 1e-6, "attached beyond the end of the wall: x=" + root.x);
        for (OutputFeature f : s.lines()) assertEquals(3.0, d0(f), 1e-9);
    }

    /**
     * Case 1b (candidate widening probe): a gas pipeline 2.5 m before the existing line over 600 m makes every
     * tie-in candidate along it vertically infeasible (plateau ends 0.5 m before the root; an oblique approach
     * violates the alongside clearance). More than topK*8 such candidates rank before the first feasible one, but a
     * correct solver must still connect the consumer around the end of the wall (route ~420 m, DU125 max 554 m).
     */
    @Test
    void case01b_longInfeasibleWallStillConnectedAroundTheEnd() {
        InputModel in = south(line(600), "A", 0, 100, 30)
                .polyline(10, "gas_pipeline", -400, 2.5, 400, 2.5)
                .build();
        Solved s = run(in, 1);
        assertTrue(crossingLines(s, "gas_pipeline").isEmpty());
        assertTrue(Math.abs(rootOf(s.v, "A").x) > 400 - 1e-6);
    }

    /** Case 4: both ABOVE and BELOW feasible far from the ends; ABOVE (no extra cost) is chosen at 2.6 - Hnew. */
    @Test
    void case04_bothFeasibleAboveIsCheaper() {
        InputModel in = south(line(200), "A", 0, 100, 10).polyline(10, "gas_pipeline", -200, 50, 200, 50).build();
        Solved s = run(in, 1);
        OutputFeature g = only(crossingLines(s, "gas_pipeline"), "gas crossing");
        assertEquals("ABOVE", crossing(g, "gas_pipeline").get("method"));
        assertEquals(GAS_TOP - GAS_CLEAR - h(du(g)), d0(g), 1e-3);
        assertEquals(0L, ((Number) s.summary().prop("depth_extra_cost")).longValue());
        assertEquals(3.0, d0(s.lines().get(0)), 1e-9, "starts at the normal depth");
        assertEquals(3.0, ((Number) s.summary().prop("max_depth")).doubleValue(), 1e-9);
    }

    /**
     * Case 5: both options are feasible for the network, but ABOVE needs a 5.6 m ramp that does not fit into the
     * 4.5 m gap before the tie-in of the straight route, whereas BELOW (4 m ramp) does: BELOW on the straight route
     * is cheaper than a longer route with ABOVE.
     */
    @Test
    void case05_belowCheaperBecauseOfTheRampBeforeTheTieIn() {
        InputModel in = south(line(200), "A", 0, 100, 10).polyline(10, "gas_pipeline", -200, 6.5, 200, 6.5).build();
        Solved s = run(in, 1);
        OutputFeature g = only(crossingLines(s, "gas_pipeline"), "gas crossing");
        assertEquals("BELOW", crossing(g, "gas_pipeline").get("method"));
        assertEquals(GAS_TOP + GAS_H + GAS_CLEAR, d0(g), 1e-3);
        assertTrue(Math.abs(rootOf(s.v, "A").x) < 1.0, "straight route kept (no detour to make ABOVE fit): x=" + rootOf(s.v, "A").x);
        assertTrue(((Number) s.summary().prop("depth_extra_cost")).longValue() > 0);
        assertEquals(3.4, ((Number) s.summary().prop("max_depth")).doubleValue(), 1e-3);
    }

    /** Case 6: gas pipeline inside a road: one merged plateau (road section 37..47) above the gas, cover >= 1.0 m. */
    @Test
    void case06_gasAndRoadOverlap() {
        InputModel in = south(line(200), "A", 0, 100, 10)
                .rect(10, "road", -200, 40, 200, 44)
                .polyline(11, "gas_pipeline", -200, 42, 200, 42)
                .build();
        Solved s = run(in, 1);
        List<OutputFeature> sp = new ArrayList<>();
        for (OutputFeature f : s.lines()) if (special(f)) sp.add(f);
        assertFalse(sp.isEmpty());
        double plateau = d0(sp.get(0));
        double total = 0;
        for (OutputFeature f : sp) { assertEquals(plateau, d0(f), 1e-9, "one merged plateau"); total += len(f); }
        assertEquals(10.0, total, 0.05, "road section = polygon + 3 m on both sides");
        assertEquals(GAS_TOP - GAS_CLEAR - h(du(sp.get(0))), plateau, 1e-3);
        OutputFeature both = null;
        for (OutputFeature f : sp) if (crosses(f, "road") && crosses(f, "gas_pipeline")) both = f;
        assertNotNull(both, "a piece crossing both the road and the gas pipeline");
        assertEquals("UNDER", crossing(both, "road").get("method"));
        assertEquals("ABOVE", crossing(both, "gas_pipeline").get("method"));
    }

    /** Case 7: gas and power cable 3 m apart: overlapping sections form one plateau at the tighter cable limit. */
    @Test
    void case07_gasAndCableOverlap() {
        InputModel in = south(line(200), "A", 0, 100, 10)
                .polyline(10, "gas_pipeline", -200, 50, 200, 50)
                .polyline(11, "power_cable", -200, 53, 200, 53)
                .build();
        Solved s = run(in, 1);
        List<OutputFeature> sp = new ArrayList<>();
        for (OutputFeature f : s.lines()) if (special(f)) sp.add(f);
        assertFalse(sp.isEmpty());
        double plateau = CABLE_TOP - CABLE_CLEAR - h(du(sp.get(0)));
        for (OutputFeature f : sp) assertEquals(plateau, d0(f), 1e-3, "cable ABOVE is the tightest constraint of the merged plateau");
        // the gas section 48..52 is split at 51 where the cable section starts: both pieces list the gas crossing
        List<OutputFeature> gas = crossingLines(s, "gas_pipeline"), cable = crossingLines(s, "power_cable");
        assertFalse(gas.isEmpty()); assertFalse(cable.isEmpty());
        for (OutputFeature f : gas) assertEquals("ABOVE", crossing(f, "gas_pipeline").get("method"));
        for (OutputFeature f : cable) assertEquals("ABOVE", crossing(f, "power_cable").get("method"));
        double gasLen = 0, cableLen = 0;
        for (OutputFeature f : gas) gasLen += len(f);
        for (OutputFeature f : cable) cableLen += len(f);
        assertEquals(4.0, gasLen, 0.05, "gas section = crossing +- 2 m");
        assertEquals(4.0, cableLen, 0.05, "cable section = crossing +- 2 m");
        assertEquals(2, ((Number) s.summary().prop("vertical_crossing_count")).intValue(), "two physical crossings, not one per piece");
    }

    /** Case 8: gas + cable + road overlap in one plateau at the cable limit, still >= 1.0 m under the road. */
    @Test
    void case08_gasCableRoadOverlap() {
        InputModel in = south(line(200), "A", 0, 100, 10)
                .rect(10, "road", -200, 40, 200, 44)
                .polyline(11, "gas_pipeline", -200, 42, 200, 42)
                .polyline(12, "power_cable", -200, 45.5, 200, 45.5)
                .build();
        Solved s = run(in, 1);
        List<OutputFeature> sp = new ArrayList<>();
        for (OutputFeature f : s.lines()) if (special(f)) sp.add(f);
        double plateau = CABLE_TOP - CABLE_CLEAR - h(du(sp.get(0)));
        for (OutputFeature f : sp) assertEquals(plateau, d0(f), 1e-3);
        assertTrue(plateau >= ROAD_MIN);
        assertFalse(crossingLines(s, "road").isEmpty());
        assertFalse(crossingLines(s, "gas_pipeline").isEmpty());
        assertFalse(crossingLines(s, "power_cable").isEmpty());
        assertEquals(1, ((Number) s.summary().prop("pass_under_count")).intValue());
        assertEquals(2, ((Number) s.summary().prop("above_crossing_count")).intValue());
    }

    /** Case 10: cable (2.04) and gas (2.44) 6 m apart: the altered depth is held with a single ramp, no return to 3.0. */
    @Test
    void case10_depthHeldToTheNextCrossing() {
        InputModel in = south(line(200), "A", 0, 100, 10)
                .polyline(10, "power_cable", -200, 50, 200, 50)
                .polyline(11, "gas_pipeline", -200, 40, 200, 40)
                .build();
        Solved s = run(in, 1);
        OutputFeature cable = only(crossingLines(s, "power_cable"), "cable"), gas = only(crossingLines(s, "gas_pipeline"), "gas");
        OutputFeature between = null;
        for (OutputFeature f : s.lines()) if (cable.prop("end_node_id").equals(f.prop("start_node_id")) && f.prop("end_node_id").equals(gas.prop("start_node_id"))) between = f;
        assertNotNull(between, "one base piece between the two crossings");
        assertEquals(6.0, len(between), 0.02);
        assertEquals(d0(cable), d0(between), 1e-9);
        assertEquals(d0(gas), d1(between), 1e-9);
        assertTrue(d0(between) < 2.9 && d1(between) < 2.9, "no return to 3.0 m between the close crossings");
    }

    /**
     * Case 15: a branch joins the trunk inside the altered-depth run between two close crossings; the junction
     * takes the trunk depth there (not 3.0) and the branch arrives at exactly that depth.
     */
    @Test
    void case15_branchStartsInsideAlteredDepthSection() {
        SyntheticInput b = south(line(200), "A", 0, 100, 10);
        InputModel in = west(b, "B", 35, 50, 10)
                .polyline(10, "power_cable", -200, 60, 200, 60)
                .polyline(11, "gas_pipeline", -200, 40, 200, 40)
                .build();
        Solved s = run(in, 2);
        assertEquals(1, s.v.network().roots().size(), "shared trunk");
        OutputFeature junction = null;
        for (OutputFeature f : s.fs) if ("heat_chamber".equals(f.objectType()) && "junction".equals(f.prop("chamber_kind"))) junction = f;
        assertNotNull(junction);
        double jy = ru.lct.heatnet.geo.CrsTransformer.get().toMetric(junction.point()[0], junction.point()[1]).y - SyntheticInput.OY;
        assertTrue(jy > 42 && jy < 58, "junction lies between the two crossings: y=" + jy);
        double jd = Double.NaN;
        int atJunction = 0;
        for (OutputFeature f : s.lines()) {
            if (junction.prop("id").equals(f.prop("end_node_id"))) { jd = d1(f); atJunction++; }
            if (junction.prop("id").equals(f.prop("start_node_id"))) { assertEquals(jd, d0(f), 1e-9); atJunction++; }
        }
        assertEquals(3, atJunction, "trunk in, trunk out and the branch meet at the junction");
        assertTrue(jd > 2.05 && jd < 2.43, "junction depth is the interpolated altered depth, not 3.0: " + jd);
        // the branch of B starts at the normal depth and ramps to the junction depth
        OutputFeature bStart = null;
        for (OutputFeature f : s.lines()) if ("B".equals(f.prop("start_node_id"))) bStart = f;
        assertNotNull(bStart);
        assertEquals(3.0, d0(bStart), 1e-9);
    }

    /** Case 16: shared trunk; branch A crosses a cable (2.04), branch B a gas pipe (2.44); both meet the trunk at 3.0. */
    @Test
    void case16_sharedTrunkWithBranchesOfDifferentDepths() {
        SyntheticInput b = south(line(200), "A", 0, 100, 10);
        InputModel in = west(b, "B", 35, 70, 10)
                .polyline(10, "power_cable", -10, 85, 10, 85)
                .polyline(11, "gas_pipeline", 15, 60, 15, 80)
                .build();
        Solved s = run(in, 2);
        assertEquals(1, s.v.network().roots().size(), "shared trunk");
        OutputFeature cable = only(crossingLines(s, "power_cable"), "cable"), gas = only(crossingLines(s, "gas_pipeline"), "gas");
        assertEquals(10.0, flow(cable), 1e-6, "cable crossing on A's own branch");
        assertEquals(10.0, flow(gas), 1e-6, "gas crossing on B's own branch");
        assertEquals(CABLE_TOP - CABLE_CLEAR - h(du(cable)), d0(cable), 1e-3);
        assertEquals(GAS_TOP - GAS_CLEAR - h(du(gas)), d0(gas), 1e-3);
        for (OutputFeature f : s.lines()) if (flow(f) > 19) { assertEquals(3.0, d0(f), 1e-9, "trunk at normal depth"); assertEquals(3.0, d1(f), 1e-9); }
        OutputFeature junction = null;
        for (OutputFeature f : s.fs) if ("heat_chamber".equals(f.objectType()) && "junction".equals(f.prop("chamber_kind"))) junction = f;
        assertNotNull(junction);
        for (OutputFeature f : s.lines()) {
            if (junction.prop("id").equals(f.prop("end_node_id"))) assertEquals(3.0, d1(f), 1e-9);
            if (junction.prop("id").equals(f.prop("start_node_id"))) assertEquals(3.0, d0(f), 1e-9);
        }
    }

    /**
     * Cases 17 + 18: a gas pipeline 8 m before the tie-in. Alone, A (20 t/h, DU100) passes ABOVE at 2.42 m (5.8 m ramp
     * fits into the 6 m gap). After B merges into A's trunk the flow is 40 t/h, the trunk becomes DU125 and the ABOVE
     * plateau would need a 6.25 m ramp: the profile must be re-planned with the final DU (BELOW at 3.4 m), never kept.
     */
    @Test
    void case17_18_duIncreaseInvalidatesAboveAndProfileIsReplanned() {
        SyntheticInput alone = south(line(300), "A", 0, 100, 20).polyline(10, "gas_pipeline", -300, 8, 300, 8);
        Solved a = run(alone.build(), 1);
        OutputFeature ga = only(crossingLines(a, "gas_pipeline"), "gas (A alone)");
        assertEquals(100, du(ga));
        assertEquals("ABOVE", crossing(ga, "gas_pipeline").get("method"));
        assertEquals(GAS_TOP - GAS_CLEAR - h(100), d0(ga), 1e-3);

        SyntheticInput both = south(line(300), "A", 0, 100, 20).polyline(10, "gas_pipeline", -300, 8, 300, 8);
        Solved s = run(west(both, "B", 35, 60, 20).build(), 2);
        assertEquals(1, s.v.network().roots().size(), "B merges into A's trunk");
        OutputFeature trunk = null;
        for (OutputFeature f : s.lines()) if (flow(f) > 39) trunk = f;
        assertNotNull(trunk);
        assertEquals(125, du(trunk), "trunk DU grows with the merged flow");
        OutputFeature g = only(crossingLines(s, "gas_pipeline"), "gas (merged)");
        assertEquals(40.0, flow(g), 1e-6, "the crossing lies on the trunk");
        assertEquals(125, du(g));
        Map<String, Object> c = crossing(g, "gas_pipeline");
        assertEquals(h(125), num(c, "new_height"), 1e-6, "crossing recomputed with the final DU height");
        assertEquals("BELOW", c.get("method"), "ABOVE would need a 6.25 m ramp into a 6 m gap");
        assertEquals(GAS_TOP + GAS_H + GAS_CLEAR, d0(g), 1e-3);
        // case 18: every link's profile is consistent with its final DU (checked by checkInvariants via assertVerticalRule);
        // the stale ABOVE depth of the single-consumer run must not survive anywhere
        for (OutputFeature f : s.lines()) assertNotEquals(GAS_TOP - GAS_CLEAR - h(100), d0(f), 1e-6, "stale profile depth found on " + f.prop("id"));
    }

    /** Case 19: the route meets the existing network exactly at the tie-in: a tie-in, not a crossing. */
    @Test
    void case19_tieInIsNotACrossing() {
        Solved s = run(south(line(200), "A", 0, 100, 10).build(), 1);
        Coordinate root = rootOf(s.v, "A");
        assertEquals(0.0, root.y, 1e-6, "new chamber on the existing line");
        for (OutputFeature f : s.lines()) {
            assertFalse(special(f), "no special piece: " + f.properties());
            assertNull(f.prop("crossings"));
            assertEquals(3.0, d0(f), 1e-9);
            assertEquals(3.0, d1(f), 1e-9);
        }
        assertEquals(0, ((Number) s.summary().prop("vertical_crossing_count")).intValue());
        assertEquals(0, ((Number) s.summary().prop("pass_under_count")).intValue());
        assertEquals(0, ((Number) s.summary().prop("depth_transition_count")).intValue());
    }

    /**
     * Case 20: another existing line (DU300) runs under a road 12 m before the target line; it cannot be tied into
     * (inside the road) and must be crossed as a special passage with vertical clearance (ABOVE at 2.5 - Hnew).
     */
    @Test
    void case20_crossingAnotherExistingLineJustBeforeTheRoot() {
        InputModel in = south(line(300), "A", 0, 100, 10)
                .rect(10, "road", -300, 10, 300, 14)
                .line(2, 300, -290, 12, 290, 12)
                .build();
        Solved s = run(in, 1);
        Coordinate root = rootOf(s.v, "A");
        assertEquals(0.0, root.y, 1e-6, "tie-in on line 1, not on the line under the road");
        OutputFeature x = only(crossingLines(s, "heat_network"), "existing line crossing");
        Map<String, Object> c = crossing(x, "heat_network");
        assertEquals("ABOVE", c.get("method"));
        assertTrue(num(c, "actual_clearance") >= HEAT_CLEAR - 1e-3);
        assertEquals(HEAT_TOP - HEAT_CLEAR - h(du(x)), d0(x), 1e-3);
        assertTrue(crosses(x, "road"), "road and existing line share the plateau");
        assertTrue(d0(x) >= ROAD_MIN);
        assertTrue(((Number) s.summary().prop("vertical_crossing_count")).intValue() >= 1);
        assertEquals(1.05 * 1.60 >= 1.60 ? 1.60 : 1.05, ((Number) x.prop("k_spec")).doubleValue(), 1e-9, "max Kspec of the overlapping objects");
    }
}
