package ru.lct.heatnet.search;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.solver.VariantResult;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.search.SearchTestSupport.*;

/**
 * PHASE 11 — route diversity / real cost. The navigation graph minimises the EFFECTIVE length (Kspec applied to
 * special sections on both the cost and the length share of the score), so with a single attachment target the
 * solver evaluates exactly one corridor: the Dijkstra-shortest one. The real score weighs Kspec only on the cost
 * term (0.7·C/25e6) while the length term (0.3·L/100) counts plain metres, therefore a shorter corridor with a
 * special crossing may have the better score although its effective length is larger.
 */
class RouteDiversityTest {

    /**
     * Wall of buildings with two gaps. Corridor A (straight, x=0) crosses a 30 m wide road (special section 36 m,
     * Kspec 1.6); corridor B (x≈34) is longer but has no crossing. The only attachment target is the existing chamber
     * at (20,0): every other point of the line lies in the clearance zone of a park.
     */
    static InputModel twoCorridors(boolean gapB) {
        SyntheticInput s = new SyntheticInput()
                .line(1, 200, -100, 0, 20, 0).chamber(2, 20, 0)
                .rect(30, "park", -100, -20, 17, -1)
                .rect(10, "oks", -150, 30, -7, 70)
                .rect(11, "oks", 7, 30, gapB ? 27 : 200, 70);
        if (gapB) s.rect(12, "oks", 41, 30, 200, 70);
        s.rect(20, "road", -30, 35, 30, 65);
        return s.rect(3, "oks", -5, 106, 5, 120).point("A", 0, 110, 10.0).build();
    }

    /**
     * Independent re-computation of the score of a one-link variant from its own coordinates: lengths from the
     * geometry, the road crossed as one straight special section (polygon extent + 3 m on each side, Kspec 1.6),
     * DU 80 (10 t/h) at 83 530 rub/m, tie-in into the existing chamber 5 000 000 rub, S = 0.7·C/25e6 + 0.3·L/100.
     */
    static double scoreByHand(VariantResult v, double roadX0, double roadY0, double roadX1, double roadY1) {
        ru.lct.heatnet.topology.NetLink l = v.network().links().iterator().next();
        java.util.List<org.locationtech.jts.geom.Coordinate> cs = l.coords();
        double length = 0, inRoad = 0;
        org.locationtech.jts.geom.Geometry road = ru.lct.heatnet.geo.GeometryUtils.GF.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                SyntheticInput.c(roadX0, roadY0), SyntheticInput.c(roadX1, roadY0), SyntheticInput.c(roadX1, roadY1), SyntheticInput.c(roadX0, roadY1), SyntheticInput.c(roadX0, roadY0)});
        for (int i = 1; i < cs.size(); i++) {
            length += cs.get(i - 1).distance(cs.get(i));
            inRoad += road.intersection(ru.lct.heatnet.geo.GeometryUtils.GF.createLineString(new org.locationtech.jts.geom.Coordinate[]{cs.get(i - 1), cs.get(i)})).getLength();
        }
        double special = inRoad > 0 ? inRoad + 6 : 0;
        double cpm = 83_530;
        double cost = Math.round((length - special) * cpm) + Math.round(special * cpm * 1.6) + 5_000_000;
        return 0.7 * cost / 25e6 + 0.3 * length / 100;
    }

    /** Control: with corridor A alone the solver's score equals the hand-computed real score of corridor A. */
    @Test
    void corridorAAloneMatchesHandScore() {
        VariantResult onlyA = best(solve(twoCorridors(false), false));
        assertEquals(1, onlyA.connectedCount());
        assertTrue(validate(twoCorridors(false), onlyA).isValid());
        assertEquals(scoreByHand(onlyA, -30, 35, 30, 65), onlyA.summary().score(), 1e-4, describe(onlyA));
    }

    /**
     * Two targets, two corridors: when the corridors lead to DIFFERENT attachment candidates both are evaluated exactly
     * and the real score decides (this already works: the road corridor wins over a 12 m longer detour).
     */
    @Test
    void differentTargetsAreComparedByRealScore() {
        SyntheticInput s = new SyntheticInput()
                .line(1, 200, -100, 0, 100, 0)
                .rect(10, "oks", -150, 30, -7, 70).rect(11, "oks", 7, 30, 31, 70).rect(12, "oks", 45, 30, 200, 70)
                .rect(20, "road", -30, 40, 30, 60)
                .rect(3, "oks", -5, 106, 5, 120).point("A", 0, 110, 10.0);
        InputModel in = s.build();
        VariantResult v = best(solve(in, false));
        assertEquals(1, v.connectedCount());
        assertTrue(validate(in, v).isValid());
        double rootX = rootOf(v, "A").x;
        assertTrue(Math.abs(rootX) < 3, "straight corridor through the road (real score) expected, root x=" + rootX + "\n" + describe(v));
        boolean special = v.network().links().iterator().next().passages().size() >= 1;
        assertTrue(special, "the chosen route crosses the road as a special passage");
    }
}
