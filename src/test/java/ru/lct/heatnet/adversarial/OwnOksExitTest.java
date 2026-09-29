package ru.lct.heatnet.adversarial;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.ObstacleSpace;
import ru.lct.heatnet.solver.Approach;
import ru.lct.heatnet.solver.ApproachPlanner;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.adversarial.AdversarialSupport.*;

/**
 * Phase 5: the exit point from the own ОКС polygon. Our interpretation of the annex ("from the nearest boundary
 * point") is "the nearest geometrically FEASIBLE exit point": the nearest boundary point is used whenever the straight
 * final segment through it can leave the own clearance zone without entering the own polygon again or a foreign
 * forbidden zone; otherwise the next nearest feasible boundary point is used; if none exists the point stays unconnected.
 */
class OwnOksExitTest {

    private static List<Approach> plan(InputModel in) {
        ObstacleSpace space = new ObstacleSpace(in, ClearanceClass.SMALL);
        ConnectionPoint cp = in.connectionPoints().get(0);
        return new ApproachPlanner(space).plan(cp);
    }

    private static Coordinate rel(Coordinate abs) { return new Coordinate(abs.x - OX, abs.y - OY); }

    /** A: convex building, the geometrically nearest boundary point (south wall, 4 m) is used. */
    @Test
    void convexBuildingUsesTheNearestBoundaryPoint() throws Exception {
        Run r = run("05-ring-ccw");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        assertTrue(r.best.notes().isEmpty(), r.best.notes().toString());
        List<Approach> aps = plan(r.input);
        assertFalse(aps.isEmpty());
        assertTrue(aps.get(0).nearestBoundary());
        Coordinate b = rel(aps.get(0).boundaryPoint());
        assertEquals(0.0, b.distance(new Coordinate(50, 95)), 1e-3, "nearest boundary point " + b);
        Coordinate a = rel(aps.get(0).freePoint());
        assertEquals(50.0, a.x, 1e-3);
        assertEquals(95 - 5.735, a.y, 1e-3, "free point just outside the own clearance zone (5 + 0.685 m for the SMALL class)");
        // the whole route is straight (P, A and the tie-in are collinear): the boundary point lies on the first segment
        List<double[]> ll = r.lines().get(0).line();
        Coordinate p0 = metric(ll.get(0)), p1 = metric(ll.get(1));
        assertEquals(0.0, org.locationtech.jts.algorithm.Distance.pointToSegment(new Coordinate(50, 95), p0, p1), 1e-3);
    }

    /** B: the nearest boundary point lies on the wall of a deep 4 m niche: the ray re-enters the polygon, so the next feasible point is used. */
    @Test
    void nicheWallIsSkippedForTheNextFeasibleBoundaryPoint() throws Exception {
        Run r = run("22-deep-niche");
        assertEquals(1, r.best.connectedCount(), r.best.unconnected().toString());
        assertTrue(r.report.isValid(), r.issues());
        assertTrue(r.best.notes().stream().anyMatch(n -> n.contains("non-nearest boundary point")));
        List<Approach> aps = plan(r.input);
        assertFalse(aps.isEmpty());
        assertFalse(aps.get(0).nearestBoundary());
        Coordinate p = rel(r.input.connectionPoints().get(0).coordinate());
        Coordinate b = rel(aps.get(0).boundaryPoint());
        assertTrue(p.distance(b) > 2.0 + 1e-6, "the 2 m niche wall is not the exit: " + b);
        assertEquals(140.0, b.y, 1e-3, "exit through the north wall (12 m), the nearest feasible one");
        // the straight final segment beyond the boundary never re-enters the own polygon
        Coordinate a = rel(aps.get(0).freePoint());
        Geometry own = r.input.restrictions().get(0).geometry();
        Geometry outside = GeometryUtils.line(new Coordinate(OX + b.x, OY + b.y), new Coordinate(OX + a.x, OY + a.y));
        assertFalse(own.crosses(outside) || own.contains(outside));
    }

    /** C: several feasible exits; the nearest (north, 4 m) is blocked by a park, so the minimum-distance feasible one (south, 6 m) is chosen. */
    @Test
    void minimumDistanceFeasibleExitIsChosen() throws Exception {
        Run r = run("51-own-oks-blocked-nearest-exit");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        List<Approach> aps = plan(r.input);
        assertFalse(aps.get(0).nearestBoundary(), "the north wall is 4 m away but leads into the park zone");
        Coordinate p = rel(r.input.connectionPoints().get(0).coordinate());
        Coordinate b = rel(aps.get(0).boundaryPoint());
        assertEquals(95.0, b.y, 1e-3, "south wall: " + b);
        assertEquals(6.0, p.distance(b), 1e-3);
        // independent brute force: no feasible boundary point is closer than the chosen one (up to the 2 m sampling of the planner)
        ObstacleSpace space = new ObstacleSpace(r.input, ClearanceClass.SMALL);
        Obstacle own = space.obstacles().stream().filter(Obstacle::isOks).findFirst().get();
        double required = space.requiredDistance(own);
        Polygon poly = (Polygon) own.geometry();
        LengthIndexedLine ring = new LengthIndexedLine(poly.getExteriorRing());
        double bestFeasible = Double.MAX_VALUE;
        Coordinate pAbs = r.input.connectionPoints().get(0).coordinate();
        for (double s = 0; s < poly.getExteriorRing().getLength(); s += 0.25) {
            Coordinate bb = ring.extractPoint(s);
            double[] dir = GeometryUtils.unit(pAbs, bb);
            Coordinate aa = GeometryUtils.offset(bb, dir, required + 0.05);
            if (own.geometry().distance(GeometryUtils.point(aa)) < required) continue;
            Exemptions ex = new Exemptions().ignore(Collections.singleton(own.index()));
            if (space.insideForbidden(aa, ex)) continue;
            if (!space.check(pAbs, aa, ex).valid()) continue;
            bestFeasible = Math.min(bestFeasible, pAbs.distance(bb));
        }
        assertTrue(p.distance(b) <= bestFeasible + 2.0 + 1e-6, "chosen " + p.distance(b) + " vs best feasible " + bestFeasible);
        assertTrue(bestFeasible > 4.0 + 1e-6, "the geometrically nearest point (4 m) is indeed infeasible");
    }

    /** D: no feasible boundary point at all (building enclosed by a foreign building): unconnected, no violation. */
    @Test
    void noFeasibleExitMeansUnconnected() throws Exception {
        Run r = run("52-own-oks-no-exit");
        assertEquals(0, r.best.connectedCount());
        assertTrue(plan(r.input).isEmpty(), "no approach at all");
        assertTrue(r.report.isValid(), r.issues());
        assertTrue(r.best.unconnected().values().iterator().next().contains("no feasible approach"));
        assertTrue(r.lines().isEmpty());
    }

    /** E: the nearest exit (east wall, 4 m) leads into the clearance zone of the neighbouring building: only the OWN polygon is exempt. */
    @Test
    void exemptionAppliesOnlyToTheOwnPolygon() throws Exception {
        Run r = run("53-own-oks-neighbour");
        assertEquals(1, r.best.connectedCount(), r.best.unconnected().toString());
        assertTrue(r.report.isValid(), r.issues());
        assertFalse(r.hasIssue("CLEARANCE"));
        assertTrue(r.best.notes().stream().anyMatch(n -> n.contains("non-nearest boundary point")));
        List<Approach> aps = plan(r.input);
        Coordinate b = rel(aps.get(0).boundaryPoint());
        assertEquals(40.0, b.x, 1e-3, "exit through the west wall (6 m), away from the neighbour: " + b);
        Coordinate a = metric(r.lines().get(0).line().get(1));
        assertTrue(a.x < 40, "free point west of the building: " + a);
        // the foreign building keeps its full clearance from the whole route (validator: no CLEARANCE error above)
        Geometry foreign = r.input.restrictions().stream().filter(x -> x.id().text().equals("oks_B")).findFirst().get().geometry();
        for (double[] ll : r.lines().get(0).line()) {
            Coordinate c = ru.lct.heatnet.geo.CrsTransformer.get().toMetric(ll[0], ll[1]);
            assertTrue(foreign.distance(GeometryUtils.point(c)) >= 5.0 + 0.235 - 1e-3);
        }
    }
}
