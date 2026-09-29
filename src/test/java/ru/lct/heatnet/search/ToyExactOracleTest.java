package ru.lct.heatnet.search;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.solver.CalculationDecision;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NetLink;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.search.SearchTestSupport.*;

/**
 * PHASE 13 — brute-force oracle on toy scenes. Two consumers in small buildings above one existing main; the
 * admissible routes are straight (exit south through the building boundary, then straight to the target) or
 * L-shaped via a junction on the other consumer's trunk. The oracle enumerates every topology (separate / A joins B /
 * B joins A) and every junction height on a 0.25 m grid with RE-TYPED tables (no production cost classes) and
 * returns the exact minimum score of that option set. The production solver must reach it within 1 % (its junction
 * candidates are discrete) and must pick the same topology.
 */
class ToyExactOracleTest {

    // ---- re-typed tables of the technical annex (deliberately duplicated in the test) ----
    static final double[][] DU = { // du, capacity t/h, max length m, rub/m
            {50, 3.5, 181, 74_023}, {65, 8.3, 245, 78_631}, {80, 13.2, 327, 83_530}, {100, 22.3, 419, 89_748},
            {125, 40.2, 554, 97_275}, {150, 65.1, 696, 105_507}, {200, 152.3, 1042, 120_275}, {250, 274.9, 1379, 135_323},
            {300, 437.4, 1718, 150_022}, {400, 943.1, 2477, 190_299}};
    static final double CLEARANCE_OKS = 5.0, HALF_WIDTH_CLASS = 1.370 / 2, PROBE = 0.05;
    static final double EXIT_OFFSET = CLEARANCE_OKS + HALF_WIDTH_CLASS + PROBE; // free point beyond the boundary

    static double[] duFor(double flow, double runLength) {
        for (double[] r : DU) if (r[1] >= flow - 1e-9 && r[2] >= runLength - 1e-9) return r;
        throw new IllegalStateException("no DU");
    }

    static double chamberCost(double maxDu) { return maxDu <= 200 ? 3_000_000 : maxDu <= 500 ? 5_000_000 : maxDu <= 1000 ? 8_000_000 : 12_000_000; }

    static double score(double cost, double length) { return 0.7 * cost / 25e6 + 0.3 * length / 100; }

    /** Consumer: point (x, y), bottom side of its building at y - d, flow. */
    static final class C {
        final double x, y, d, flow;
        C(double x, double y, double d, double flow) { this.x = x; this.y = y; this.d = d; this.flow = flow; }
        double exitY() { return y - d - EXIT_OFFSET; }
    }

    static final class OracleResult {
        double score = Double.POSITIVE_INFINITY;
        String topology;
        int trunkDu;
    }

    /** Cost of a straight separate connection of c to the main at y = 0 (new chamber on the DU-300 line). */
    static double separateCost(C c) {
        double len = c.y;
        double[] du = duFor(c.flow, len);
        return Math.round(len * du[3]) + chamberCost(Math.max(du[0], 300));
    }

    static OracleResult oracle(C a, C b) {
        OracleResult best = new OracleResult();
        double sep = separateCost(a) + separateCost(b);
        best.score = score(sep, a.y + b.y);
        best.topology = "separate";
        for (int side = 0; side < 2; side++) {
            C trunk = side == 0 ? b : a, branch = side == 0 ? a : b;
            double maxJ = Math.min(branch.exitY(), trunk.exitY() - 0.5); // junction below both exits (turn <= 90°, reserved segment)
            for (double yj = 0.5; yj <= maxJ; yj += 0.25) {
                // the straight branch must keep the ОКС clearance (5 m + half pair width) from the trunk consumer's building
                if (!clearOfBuilding(branch.x, branch.exitY(), trunk.x, yj, trunk)) continue;
                double branchLen = (branch.y - branch.exitY()) + Math.hypot(trunk.x - branch.x, branch.exitY() - yj);
                double upper = trunk.y - yj, lower = yj;
                double q = a.flow + b.flow;
                double[] duBranch = duFor(branch.flow, branchLen);
                double[] duUpper = duFor(trunk.flow, upper);
                double[] duLower = duFor(q, lower);
                if (duLower[0] < duUpper[0]) duLower = duFor(q, Math.max(lower, duUpper[2] + 1)); // DU must not decrease towards the main
                double cost = Math.round(branchLen * duBranch[3]) + Math.round(upper * duUpper[3]) + Math.round(lower * duLower[3])
                        + chamberCost(Math.max(Math.max(duBranch[0], duUpper[0]), duLower[0])) + chamberCost(Math.max(duLower[0], 300));
                double s = score(cost, branchLen + trunk.y);
                if (s < best.score) { best.score = s; best.topology = "merge"; best.trunkDu = (int) duLower[0]; }
            }
        }
        return best;
    }

    /** Straight segment (x0,y0)-(x1,y1) keeps the clearance from the 10 x 14 m building of consumer c. */
    static boolean clearOfBuilding(double x0, double y0, double x1, double y1, C c) {
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Geometry seg = gf.createLineString(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(x0, y0), new org.locationtech.jts.geom.Coordinate(x1, y1)});
        double bx0 = c.x - 5, by0 = c.y - c.d, bx1 = c.x + 5, by1 = c.y - c.d + 14;
        org.locationtech.jts.geom.Geometry box = gf.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(bx0, by0), new org.locationtech.jts.geom.Coordinate(bx1, by0),
                new org.locationtech.jts.geom.Coordinate(bx1, by1), new org.locationtech.jts.geom.Coordinate(bx0, by1), new org.locationtech.jts.geom.Coordinate(bx0, by0)});
        return seg.distance(box) >= CLEARANCE_OKS + HALF_WIDTH_CLASS;
    }

    static InputModel scene(C a, C b) {
        return new SyntheticInput()
                .line(1, 300, -400, 0, 400, 0)
                .rect(10, "oks", a.x - 5, a.y - a.d, a.x + 5, a.y - a.d + 14).point("A", a.x, a.y, a.flow)
                .rect(11, "oks", b.x - 5, b.y - b.d, b.x + 5, b.y - b.d + 14).point("B", b.x, b.y, b.flow)
                .build();
    }

    private static final Pattern ALT = Pattern.compile("-> score ([0-9]+[.,][0-9]+)");

    /** Every candidate the solver reports as considered must score no better than the one it selected. */
    static void assertNoBetterConsideredCandidate(VariantResult v) {
        for (CalculationDecision d : v.trace().decisions()) {
            if (!d.type.equals("TIE_IN_SELECTED") && !d.type.equals("ROUTE_SELECTED")) continue;
            double after = ((Number) d.inputs.get("score_after")).doubleValue();
            for (String alt : d.alternatives) {
                Matcher m = ALT.matcher(alt);
                if (!m.find()) continue;
                double s = Double.parseDouble(m.group(1).replace(',', '.'));
                assertTrue(s + 5e-5 >= after, "selected " + after + " but a considered candidate scored " + s + ": " + alt);
            }
        }
    }

    static VariantResult check(C a, C b, String expectedTopology) {
        InputModel in = scene(a, b);
        SolveResult r = solve(in, false);
        VariantResult v = best(r);
        assertEquals(2, v.connectedCount(), describe(v));
        assertTrue(validate(in, v).isValid(), validate(in, v).getIssues().toString());
        OracleResult o = oracle(a, b);
        assertEquals(expectedTopology, o.topology, "oracle topology");
        assertTrue(v.summary().score() <= o.score * 1.01 + 1e-9,
                "solver " + v.summary().score() + " vs oracle exact minimum " + o.score + " (" + o.topology + ")\n" + describe(v));
        int roots = v.network().roots().size(), junctions = junctionChambers(v);
        if (o.topology.equals("merge")) { assertEquals(1, roots, describe(v)); assertEquals(1, junctions, describe(v)); }
        else { assertEquals(2, roots, describe(v)); assertEquals(0, junctions, describe(v)); }
        assertNoBetterConsideredCandidate(v);
        return v;
    }

    @Test
    void neighboursMergeIntoOneTrunk() {
        VariantResult v = check(new C(-20, 150, 4, 10), new C(20, 150, 4, 15), "merge");
        // the shared trunk carries 25 t/h -> DU 125 (capacity of DU 100 is 22.3)
        int trunkDu = 0;
        for (NetLink l : v.network().links()) if (l.parent().isRoot()) trunkDu = l.du();
        assertEquals(125, trunkDu);
        assertEquals(125, oracle(new C(-20, 150, 4, 10), new C(20, 150, 4, 15)).trunkDu);
    }

    @Test
    void distantConsumersStaySeparate() {
        check(new C(-160, 150, 4, 10), new C(160, 150, 4, 15), "separate");
    }

    @Test
    void sharedFlowRaisesTheTrunkDiameterAndCost() {
        // 20 + 21 t/h: separately DU 100 each, merged trunk DU 150 (40.2 < 41)
        VariantResult v = check(new C(-20, 120, 4, 20), new C(20, 120, 4, 21), "merge");
        int trunkDu = 0;
        for (NetLink l : v.network().links()) if (l.parent().isRoot()) trunkDu = l.du();
        assertEquals(150, trunkDu);
        // cost re-computed from the solver's own topology with the re-typed tables
        double cost = 0, len = 0;
        for (NetLink l : v.network().links()) {
            double[] du = null;
            for (double[] r : DU) if ((int) r[0] == l.du()) du = r;
            assertNotNull(du);
            cost += Math.round(l.length() * du[3]);
            len += l.length();
        }
        cost += chamberCost(150) + chamberCost(300);
        assertEquals(score(cost, len), v.summary().score(), 2e-3);
    }

    /**
     * Unequal heights: the straight branch of B must keep the clearance from A's building, which bounds the junction
     * height; the exact optimum over the admissible straight branches is compared with the solver's sampled
     * junction candidates (every 20 m from the child end).
     */
    @Test
    void differentHeightsMergeAtTheLowerExit() {
        check(new C(-15, 90, 4, 5), new C(60, 170, 4, 12), "merge");
    }

    @Test
    void twoMainsEachConsumerTakesTheNearer() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -400, 0, 400, 0).line(2, 300, -400, 300, 400, 300)
                .rect(10, "oks", -25, 96, -15, 110).point("A", -20, 100, 10.0)
                .rect(11, "oks", 15, 190, 25, 204).point("B", 20, 200, 10.0)
                .build();
        SolveResult r = solve(in, false);
        VariantResult v = best(r);
        assertEquals(2, v.connectedCount(), describe(v));
        assertTrue(validate(in, v).isValid());
        // oracle: A straight south 100 m, B straight north 100 m, two new chambers on DU-300 mains
        double[] du = duFor(10, 100);
        double exact = score(2 * (Math.round(100 * du[3]) + chamberCost(300)), 200);
        assertTrue(v.summary().score() <= exact * 1.01 + 1e-9, "solver " + v.summary().score() + " vs oracle " + exact + "\n" + describe(v));
        assertEquals(2, v.network().roots().size());
        assertNoBetterConsideredCandidate(v);
    }
}
