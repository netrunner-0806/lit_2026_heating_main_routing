package ru.lct.heatnet.search;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.solver.CalculationDecision;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NetNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.search.SearchTestSupport.*;

/**
 * PHASE 10 — "false unconnected" attack: a connection point must never be reported unreachable only because the
 * first heuristic candidates were rejected or because the navigation graph has no vertex on the only corridor.
 * Cases that fail against the current solver are disabled with the observed evidence.
 */
class FalseUnconnectedTest {

    private static final Pattern REJECTED = Pattern.compile("evaluated (\\d+), rejected (\\d+)");

    /** Largest "rejected" counter of the exact evaluation recorded for the connection point. */
    static int maxRejected(VariantResult v, String cpId) {
        int max = 0;
        for (CalculationDecision d : v.trace().decisions()) {
            if (!cpId.equals(d.objectId)) continue;
            for (String a : d.alternatives) {
                Matcher m = REJECTED.matcher(a);
                if (m.find()) max = Math.max(max, Integer.parseInt(m.group(2)));
            }
        }
        return max;
    }

    static void assertAllConnected(InputModel in, SolveResult r, int n) {
        VariantResult v = best(r);
        assertEquals(n, v.connectedCount(), "unconnected: " + v.unconnected() + "\n" + describe(v));
        assertTrue(validate(in, v).isValid(), validate(in, v).getIssues().toString());
    }

    /**
     * CASE 1. A's trunk runs south from (0,200); B (east of it) leaves its building northwards, so the junction
     * candidates it reaches first arrive heading north into the southbound trunk (turn > 90° through the chamber):
     * they are rejected only by the exact evaluation, and a farther junction / tie-in is valid.
     */
    @Test
    void case1_firstJunctionCandidatesRejectedByTurnRule() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -200, 0, 200, 0)
                .rect(10, "oks", -5, 196, 5, 210).point("A", 0, 200, 10.0)
                .rect(11, "oks", 35, 110, 45, 130).point("B", 40, 126, 10.0)
                .build();
        SolveResult r = solve(in, false);
        assertAllConnected(in, r, 2);
        VariantResult v = best(r);
        assertTrue(maxRejected(v, "B") >= 2, "exact evaluation rejected at least two candidates for B:\n" + traceOf(v, "B"));
        assertEquals(1, v.network().roots().size(), "joint connection expected\n" + describe(v));
    }

    /** CASE 2. Same mechanism with a longer trunk: more than four rejected candidates before a valid one. */
    @Test
    void case2_fiveRejectedCandidatesThenValid() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -200, 0, 200, 0)
                .rect(10, "oks", -5, 296, 5, 310).point("A", 0, 300, 10.0)
                .rect(11, "oks", 35, 60, 45, 80).point("B", 40, 76, 10.0)
                .build();
        SolveResult r = solve(in, false);
        assertAllConnected(in, r, 2);
        VariantResult v = best(r);
        assertTrue(maxRejected(v, "B") >= 4, "exact evaluation rejected at least four candidates for B:\n" + traceOf(v, "B"));
    }

    /** CASE 3. The nearest tie-in points lie inside the clearance zone of a park; a farther point is valid. */
    @Test
    void case3_nearestTieInsForbiddenFartherValid() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -300, 0, 300, 0)
                .rect(20, "park", -60, -20, 60, 0.5)
                .rect(3, "oks", -5, 96, 5, 110).point("A", 0, 100, 10.0)
                .build();
        SolveResult r = solve(in, false);
        assertAllConnected(in, r, 1);
        Coordinate root = rootOf(best(r), "A");
        assertTrue(Math.abs(root.x) > 61.5, "attachment outside the park clearance zone, got x=" + root.x);
    }

    /** CASE 4. The nearest existing chamber is full (line through it + two lines ending in it = 4); a new chamber 8 m from it is valid. */
    @Test
    void case4_fullExistingChamberNewChamberOnLine() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -200, 0, 200, 0)
                .line(2, 300, 0, -200, 0, 0).line(4, 300, 0, 0, 150, -150)
                .chamber(5, 0, 0)
                .rect(3, "oks", 3, 28, 13, 42).point("A", 8, 30, 10.0)
                .build();
        SolveResult r = solve(in, false);
        assertAllConnected(in, r, 1);
        VariantResult v = best(r);
        Coordinate root = rootOf(v, "A");
        assertTrue(root.distance(new Coordinate(0, 0)) < 10.0, "new chamber within 10 m of the full chamber, got " + root);
        for (NetNode n : v.network().roots()) assertEquals(NetNode.Kind.NEW_CHAMBER_ON_EXISTING, n.kind());
    }

    /**
     * CASE 5 (depth). Short existing line ending in a chamber; a gas pipeline shields the whole line 3 m north of it
     * up to 2 m before the chamber. Every tie-in on the line needs a crossing right before the root (ramp does not
     * fit); the chamber is reachable without any crossing by a route passing east of the pipeline's end — a
     * geometrically distinct corridor. PLANAR connects straight through the pipeline (special crossing).
     */
    @Test
    void case5_depthThirdRouteAroundThePipelineEnd() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -150, 0, 0, 0)
                .chamber(2, 0, 0)
                .polyline(3, "gas_pipeline", -150, 3, -2, 3)
                .rect(4, "oks", -105, 96, -95, 110).point("A", -100, 100, 10.0)
                .build();
        assertAllConnected(in, solve(in, false), 1);
        SolveResult dr = solve(in, true);
        assertAllConnected(in, dr, 1);
        VariantResult v = best(dr);
        // the corridor around the pipeline end (existing chamber, no crossing) scores ~0.96; the solver may also find an
        // oblique crossing whose ramp fits — any valid connection must not be worse than the known crossing-free corridor
        assertTrue(v.summary().score() <= 0.97, describe(v));
    }

    /**
     * CASE 6 (depth, many candidates). A gas pipeline runs parallel to the main 3 m away for a kilometre; the consumer
     * stands 210 m north. Every candidate closer than ~350 m is vertically infeasible (crossing too close to the root),
     * feasible ones exist (oblique crossings beyond 350 m, or beyond the pipeline's end at x > 400) but more than
     * 12·8 = 96 infeasible candidates rank before them, so the hard cap of the exact evaluation is hit.
     */
    @Test
    void case6_depthMoreThan96InfeasibleCandidatesBeforeTheFeasibleOne() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -600, 0, 600, 0)
                .polyline(2, "gas_pipeline", -600, 3, 400, 3)
                .rect(3, "oks", -5, 206, 5, 220).point("A", 0, 210, 10.0)
                .build();
        assertAllConnected(in, solve(in, false), 1);
        assertAllConnected(in, solve(in, true), 1);
    }

    /** CASE 6b: the same scene 100 m lower is solved by the current window (87 evaluated) — must keep working. */
    @Test
    void case6b_depthObliqueCrossingFoundWithinTheWindow() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -400, 0, 300, 0)
                .polyline(2, "gas_pipeline", -400, 3, 200, 3)
                .rect(3, "oks", -5, 106, 5, 120).point("A", 0, 110, 10.0)
                .build();
        assertAllConnected(in, solve(in, true), 1);
    }

    /** CASE 7c: a 450 m straight corridor is solved today (direct terminal edge) — regression guard. */
    @Test
    void case7c_mediumNarrowCorridorStaysConnected() {
        InputModel in = new SyntheticInput()
                .line(1, 300, -300, -30, 300, -30)
                .rect(10, "oks", -200, 0, -6.5, 800)
                .rect(11, "oks", 6.5, 0, 200, 800)
                .rect(3, "oks", -5, 420, 5, 434).point("A", 0, 424, 10.0)
                .build();
        SolveResult r = solve(in, false);
        assertAllConnected(in, r, 1);
        assertEquals(454.0, best(r).summary().newLength(), 0.5, "straight route through the corridor");
    }
}
