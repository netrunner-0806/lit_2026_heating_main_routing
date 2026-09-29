package ru.lct.heatnet.restrictions;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class ObstacleSpaceTest {

    /** Building 100x40 at x 100..200, y 100..140; DU<500 clearance 5 m + half width of DU400 (0.685). */
    private static ObstacleSpace withBuilding() {
        return new ObstacleSpace(new SyntheticInput().rect(1, "oks", 100, 100, 200, 140).build(), ClearanceClass.SMALL);
    }

    @Test
    void oksCannotBeCrossedAndNeedsClearance() {
        ObstacleSpace s = withBuilding();
        assertFalse(s.check(c(50, 120), c(250, 120)).valid(), "crossing the building");
        assertFalse(s.check(c(50, 95), c(250, 95)).valid(), "5 m from the wall: less than 5 + 0.685");
        assertFalse(s.check(c(50, 94.4), c(250, 94.4)).valid(), "5.6 m: still less than 5.685");
        assertTrue(s.check(c(50, 94.0), c(250, 94.0)).valid(), "6 m from the wall is fine for DU < 500");
        assertTrue(s.check(c(50, 94.31), c(250, 94.31)).valid(), "exactly at the required distance passes");
    }

    @Test
    void oksClearanceGrowsWithDiameterClass() {
        ObstacleSpace medium = new ObstacleSpace(new SyntheticInput().rect(1, "oks", 100, 100, 200, 140).build(), ClearanceClass.MEDIUM);
        assertFalse(medium.check(c(50, 94.0), c(250, 94.0)).valid(), "DU 500..800: 7 m + 1.125");
        assertTrue(medium.check(c(50, 91.5), c(250, 91.5)).valid());
        ObstacleSpace large = new ObstacleSpace(new SyntheticInput().rect(1, "oks", 100, 100, 200, 140).build(), ClearanceClass.LARGE);
        assertFalse(large.check(c(50, 91.5), c(250, 91.5)).valid(), "DU >= 900: 9 m + 1.725");
        assertTrue(large.check(c(50, 89.0), c(250, 89.0)).valid());
    }

    @Test
    void ownPolygonIsExemptOnTheFinalSegmentOnly() {
        ObstacleSpace s = withBuilding();
        Obstacle own = s.obstacles().get(0);
        Exemptions ex = new Exemptions().ignore(Collections.singleton(own.index()));
        assertTrue(s.check(c(150, 90), c(150, 120), ex).valid(), "final segment into the own polygon");
        assertFalse(s.check(c(150, 90), c(150, 120)).valid(), "same segment without the exemption");
    }

    @Test
    void waterAndRailwayAreForbiddenWithOneMetre() {
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput()
                .rect(1, "water", 0, 0, 100, 50)
                .rect(2, "railway", 200, 0, 300, 50).build(), ClearanceClass.SMALL);
        assertFalse(s.check(c(-10, 25), c(110, 25)).valid(), "crossing water");
        assertFalse(s.check(c(-10, 51.2), c(110, 51.2)).valid(), "1.2 m from water < 1 + 0.685");
        assertTrue(s.check(c(-10, 51.8), c(110, 51.8)).valid());
        assertFalse(s.check(c(190, 25), c(310, 25)).valid(), "crossing railway");
        assertTrue(s.check(c(190, 51.8), c(310, 51.8)).valid());
        for (String t : new String[]{"park", "social_area", "prohibited_site"}) {
            ObstacleSpace o = new ObstacleSpace(new SyntheticInput().rect(1, t, 0, 0, 100, 50).build(), ClearanceClass.SMALL);
            assertFalse(o.check(c(-10, 25), c(110, 25)).valid(), t);
            assertTrue(o.check(c(-10, 51.8), c(110, 51.8)).valid(), t);
        }
    }

    @Test
    void roadNeedsAtLeast45DegreesAndBecomesSpecialSection() {
        // road polygon: strip y 100..110 from x 0..300
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput().rect(1, "road", 0, 100, 300, 110).build(), ClearanceClass.SMALL);
        SegmentCheck perpendicular = s.check(c(150, 50), c(150, 160));
        assertTrue(perpendicular.valid());
        assertEquals(1, perpendicular.passages().size());
        SpecialPassage p = perpendicular.passages().get(0);
        assertEquals(1.60, p.kSpec());
        assertEquals(50 - 3, p.from(), 1e-6, "section starts 3 m before the polygon");
        assertEquals(60 + 3, p.to(), 1e-6, "section ends 3 m after the polygon");
        assertEquals(90, p.crossingAngleDeg(), 1e-6);
        assertEquals(110 + 16 * 0.60, perpendicular.effectiveLength(), 1e-6);
        // 45 degrees exactly is allowed
        SegmentCheck diag45 = s.check(c(100, 50), c(220, 170));
        assertTrue(diag45.valid());
        assertEquals(45, diag45.passages().get(0).crossingAngleDeg(), 1e-6);
        // 30 degrees is rejected
        SegmentCheck shallow = s.check(c(0, 50), c(0 + 120 / Math.tan(Math.toRadians(30)), 170));
        assertFalse(shallow.valid());
        assertTrue(shallow.reason().contains("angle"));
        // a turn inside the special section is not allowed: the section must fit into one straight segment
        assertFalse(s.check(c(150, 50), c(150, 111)).valid(), "segment ends 1 m after the road: section extends beyond the end");
        // passing alongside needs 1.5 m + half width
        assertFalse(s.check(c(0, 98.5), c(300, 98.5)).valid());
        assertTrue(s.check(c(0, 97.5), c(300, 97.5)).valid());
    }

    @Test
    void roadAngleIsCheckedAtTheEntryOnly() {
        // trapezoid road: bottom edge y=100 (entry at 90° when going north), left edge (100,110)-(200,400) is steep:
        // exiting through it at x=150 makes 19° with the edge. Northbound traversal is valid, southbound is not.
        org.locationtech.jts.geom.Polygon poly = ru.lct.heatnet.geo.GeometryUtils.GF.createPolygon(new Coordinate[]{
                c(0, 100), c(300, 100), c(300, 400), c(200, 400), c(100, 110), c(0, 100)});
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput().restriction(1, "road", poly).build(), ClearanceClass.SMALL);
        SegmentCheck north = s.check(c(150, 50), c(150, 450));
        assertTrue(north.valid(), "entry through the perpendicular edge is fine: " + north.reason());
        assertFalse(north.validReverse(), "the reverse traversal enters through the steep edge at 19°");
        assertEquals(1, north.passages().size());
        assertEquals(90, north.passages().get(0).crossingAngleDeg(), 1e-6);
        SegmentCheck south = s.check(c(150, 450), c(150, 50));
        assertFalse(south.valid());
        assertTrue(south.validReverse());
        assertTrue(south.reason().contains("entry angle"));
    }

    @Test
    void tramTracksUseCoefficient175() {
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput().rect(1, "tram_tracks", 0, 100, 300, 110).build(), ClearanceClass.SMALL);
        SegmentCheck sc = s.check(c(150, 50), c(150, 160));
        assertTrue(sc.valid());
        assertEquals(1.75, sc.passages().get(0).kSpec());
    }

    @Test
    void gasPipelineAndPowerCableSpecialSections() {
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput()
                .polyline(1, "gas_pipeline", 0, 100, 300, 100)
                .polyline(2, "power_cable", 0, 200, 300, 200).build(), ClearanceClass.SMALL);
        SegmentCheck sc = s.check(c(150, 50), c(150, 250));
        assertTrue(sc.valid());
        assertEquals(2, sc.passages().size());
        SpecialPassage gas = sc.passages().get(0).obstacle().rule().type().equals("gas_pipeline") ? sc.passages().get(0) : sc.passages().get(1);
        SpecialPassage cable = gas == sc.passages().get(0) ? sc.passages().get(1) : sc.passages().get(0);
        assertEquals(1.25, gas.kSpec());
        assertEquals(48, gas.from(), 1e-6);
        assertEquals(52, gas.to(), 1e-6);
        assertEquals(1.15, cable.kSpec());
        assertEquals(148, cable.from(), 1e-6);
        assertEquals(152, cable.to(), 1e-6);
        // alongside: 2.0 + 0.685 + 0.2 = 2.885 m from the gas axis
        assertFalse(s.check(c(0, 102.5), c(300, 102.5)).valid());
        assertTrue(s.check(c(0, 103.0), c(300, 103.0)).valid());
        // very shallow crossing (10 degrees) runs alongside beyond the section: rejected
        assertFalse(s.check(c(0, 90), c(120, 90 + 120 * Math.tan(Math.toRadians(10))) ).valid());
    }

    @Test
    void existingHeatNetworkCrossingIsSpecialWith105() {
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput().line(7, 400, 0, 100, 300, 100).build(), ClearanceClass.SMALL);
        SegmentCheck sc = s.check(c(150, 50), c(150, 150));
        assertTrue(sc.valid());
        assertEquals(1, sc.passages().size());
        assertEquals(1.05, sc.passages().get(0).kSpec());
        assertEquals(48, sc.passages().get(0).from(), 1e-6);
        assertEquals(52, sc.passages().get(0).to(), 1e-6);
        assertEquals(100 + 4 * 0.05, sc.effectiveLength(), 1e-9);
        // termination on the line at a tie-in point is not a crossing when exempt at that end
        Obstacle net = s.obstacles().get(0);
        Exemptions ex = new Exemptions().disc(c(150, 100), 5.0, o -> o.isExistingNetwork());
        SegmentCheck end = s.check(c(150, 50), c(150, 100), ex);
        assertTrue(end.valid());
        assertTrue(end.passages().isEmpty());
        // without the exemption the segment would end inside the clearance zone
        assertFalse(s.check(c(150, 50), c(150, 100)).valid());
        // alongside: 1.0 + 0.685 + 0.685 (DU400 half width) = 2.37 m
        assertFalse(s.check(c(0, 102.0), c(300, 102.0)).valid());
        assertTrue(s.check(c(0, 102.6), c(300, 102.6)).valid());
    }

    @Test
    void overlappingSpecialPassagesUseMaxCoefficient() {
        // road strip y 100..110 and a gas pipeline at y 105 inside it
        ObstacleSpace s = new ObstacleSpace(new SyntheticInput()
                .rect(1, "road", 0, 100, 300, 110)
                .polyline(2, "gas_pipeline", 0, 105, 300, 105).build(), ClearanceClass.SMALL);
        SegmentCheck sc = s.check(c(150, 50), c(150, 160));
        assertTrue(sc.valid());
        assertEquals(2, sc.passages().size());
        // road section 47..63 (K 1.6) fully contains the gas section 53..57 (K 1.25): extra = 16 * 0.6
        assertEquals(110 + 16 * 0.60, sc.effectiveLength(), 1e-6);
    }

    @Test
    void navigationVerticesAreOutsideZones() {
        ObstacleSpace s = withBuilding();
        for (Coordinate v : s.navigationVertices()) {
            assertFalse(s.insideForbidden(v));
            assertTrue(s.distance(s.obstacles().get(0), v) >= 5.685, "vertex at " + v);
        }
        assertTrue(s.navigationVertices().size() >= 4);
    }
}
