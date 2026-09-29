package ru.lct.heatnet.adversarial;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.adversarial.AdversarialSupport.*;

/**
 * Phase 4: geometry adversarial suite. Every fixture in src/test/resources/adversarial goes through the real parsing
 * path, the solver, the output builder and the independent validator (see {@link AdversarialFixtureGenerator} for the
 * scene descriptions). Nothing here depends on the fixture coordinates in production code.
 */
class GeometryAdversarialTest {

    /** The whole route of variant v1 as one metric LineString in absolute EPSG:32637 coordinates. */
    private static List<LineString> routeLines(Run r) {
        List<LineString> out = new ArrayList<>();
        for (OutputFeature f : r.lines()) {
            Coordinate[] cs = f.line().stream().map(ll -> CrsTransformer.get().toMetric(ll[0], ll[1])).toArray(Coordinate[]::new);
            out.add(GeometryUtils.GF.createLineString(cs));
        }
        return out;
    }

    private static double minDistance(Run r, Geometry g) {
        double d = Double.MAX_VALUE;
        for (LineString l : routeLines(r)) d = Math.min(d, g.distance(l));
        return d;
    }

    private static Restriction restriction(InputModel in, String id) {
        return in.restrictions().stream().filter(x -> x.id().text().equals(id)).findFirst().orElseThrow(() -> new AssertionError("restriction " + id));
    }

    private static boolean diag(Run r, String code) {
        return r.input.diagnostics().messages().stream().anyMatch(m -> m.getCode().equals(code));
    }

    private static void assertConnectedAndValid(Run r) {
        assertEquals(r.input.connectionPoints().size(), r.best.connectedCount(), "unconnected: " + r.best.unconnected());
        assertTrue(r.report.isValid(), r.issues());
    }

    @Test
    void polygonWithHoleCannotBeCrossedThroughTheHole() throws Exception {
        Run r = run("01-polygon-with-hole");
        assertConnectedAndValid(r);
        Geometry park = restriction(r.input, "1").geometry();
        assertEquals(1, park.getNumGeometries());
        assertTrue(minDistance(r, park) >= 1.0 + 0.235 - 1e-3, "route keeps the park clearance (DU80): " + minDistance(r, park));
        assertTrue(r.newLength() > 100, "the ring forces a detour: " + r.newLength());
    }

    @Test
    void connectionPointInsideACourtyardHoleIsUnconnectedWithoutViolations() throws Exception {
        Run r = run("02-point-inside-hole");
        assertEquals(0, r.best.connectedCount());
        assertEquals(1, r.best.unconnected().size());
        assertTrue(r.report.isValid(), r.issues());
        List<?> ids = (List<?>) r.summary().prop("unconnected_oks_ids");
        assertEquals(Arrays.asList("A"), ids);
        assertEquals(100_000_000L + 5_000_000L, ((Number) r.summary().prop("unconnected_penalty")).longValue());
        assertTrue(r.lines().isEmpty() && r.chambers().isEmpty(), "no partial network for an unconnected point");
    }

    @Test
    void connectionPointExactlyOnItsBuildingBoundary() throws Exception {
        Run r = run("03-point-on-boundary");
        assertConnectedAndValid(r);
        assertEquals(95.0, r.newLength(), 0.05, "final segment 5.735 m + straight route 89.265 m");
        assertFalse(r.hasIssue("OWN_OKS_CROSSED"));
        assertFalse(r.hasIssue("OWN_OKS_CLEARANCE"));
    }

    @Test
    void ringOrientationDoesNotChangeTheResult() throws Exception {
        Run cw = run("04-ring-cw"), ccw = run("05-ring-ccw");
        assertConnectedAndValid(cw);
        assertConnectedAndValid(ccw);
        assertEquals(cw.score(), ccw.score(), 1e-9);
        assertEquals(cw.newLength(), ccw.newLength(), 1e-6);
        List<Coordinate> a = cw.metricVertices(), b = ccw.metricVertices();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertEquals(0.0, a.get(i).distance(b.get(i)), 1e-3, "vertex " + i);
    }

    @Test
    void multiPolygonBuildingIsHandledAsOneOwnPolygon() throws Exception {
        Run r = run("06-multipolygon-oks");
        assertConnectedAndValid(r);
        assertEquals(99.0, r.newLength(), 0.05);
    }

    @Test
    void multiLineStringRestrictionIsCrossedAsASpecialPassage() throws Exception {
        Run r = run("07-multilinestring-restriction");
        assertConnectedAndValid(r);
        List<OutputFeature> special = r.lines().stream().filter(f -> "special".equals(f.prop("laying_method"))).collect(Collectors.toList());
        assertEquals(1, special.size());
        assertEquals(1.25, ((Number) special.get(0).prop("k_spec")).doubleValue(), 1e-9);
        assertEquals(4.0, ((Number) special.get(0).prop("length")).doubleValue(), 1e-6, "2 m to each side of the crossing point");
        assertTrue(r.crosses("gas_pipeline#1"));
    }

    @Test
    void multiLineStringExistingNetworkKeepsOneId() throws Exception {
        Run r = run("08-multiline-existing");
        assertTrue(diag(r, "MULTILINE_NETWORK"));
        assertEquals(2, r.input.networkLines().size(), "each part is a line with the same id");
        assertConnectedAndValid(r);
        assertEquals(1, r.chambers().size());
        assertEquals(100, r.chambers().get(0).prop("existing_line_id"));
        assertEquals(99.0, r.newLength(), 0.05);
    }

    @Test
    void duplicateConsecutiveCoordinatesAreHarmless() throws Exception {
        Run r = run("09-duplicate-coordinates");
        assertConnectedAndValid(r);
        assertEquals(99.0, r.newLength(), 0.05);
    }

    @Test
    void almostZeroLengthSegmentsAreHarmless() throws Exception {
        Run r = run("10-almost-zero-segment");
        assertConnectedAndValid(r);
        assertEquals(99.0, r.newLength(), 0.05);
    }

    @Test
    void collinearVerticesDoNotChangeTheResult() throws Exception {
        Run a = run("11a-collinear-segments"), b = run("11b-collinear-plain");
        assertConnectedAndValid(a);
        assertConnectedAndValid(b);
        assertEquals(b.score(), a.score(), 1e-9);
        assertEquals(b.newLength(), a.newLength(), 1e-6);
    }

    @Test
    void routeExactlyTangentToAClearanceZoneIsAccepted() throws Exception {
        Run r = run("12-tangent-buffer");
        assertConnectedAndValid(r);
        assertEquals(99.0, r.newLength(), 0.05, "the straight route at exactly the required distance is used");
        assertEquals(1, r.lines().size());
        assertEquals(3, r.lines().get(0).line().size(), "P, free point A, tie-in: no detour vertex");
        Geometry park = restriction(r.input, "1").geometry();
        double d = minDistance(r, park);
        assertEquals(1.685, d, 1e-3, "distance to the park equals the SMALL-class requirement");
    }

    @Test
    void restrictionEndingExactlyOnTheRouteLine() throws Exception {
        Run r = run("13-touch-one-point");
        assertConnectedAndValid(r);
        // hand-built straight route through the end point of the gas pipeline: a special section is required there
        InputModel in = r.input;
        List<OutputFeature> ok = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 52, 99, 52, 89.265, 52, 52),
                line("n2", "t1", "t2", 10, 80, true, 1.25, 52, 52, 52, 48),
                line("n3", "t2", "R", 10, 80, false, 1.0, 52, 48, 52, 0),
                tn("t1", 52, 52), tn("t2", 52, 48), chamber("R", 52, 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, ok);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        List<OutputFeature> bad = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 52, 99, 52, 89.265, 52, 0),
                chamber("R", 52, 0, 300, 5_000_000)));
        assertTrue(has(validate(in, bad), "CROSSING_NOT_SPECIAL"));
    }

    @Test
    void newLinesMeetOnlyAtALegitimateJunctionNode() throws Exception {
        Run r = run("14-common-node");
        assertConnectedAndValid(r);
        long junctions = r.chambers().stream().filter(f -> "junction".equals(f.prop("chamber_kind"))).count();
        assertEquals(1, junctions);
        assertFalse(r.hasIssue("LINES_INTERSECT"));
        assertEquals(1, r.best.network().roots().size());
    }

    @Test
    void newLinesTouchingOutsideANodeAreRejected() throws Exception {
        InputModel in = load("14-common-node");
        // branch B crosses branch A's trunk at (35, 60) without a node there
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 35, 99, 35, 89.265, 35, 0),
                line("n2", "B", "R2", 10, 80, false, 1.0, 65, 99, 65, 89.265, 20, 60, 20, 0),
                chamber("R", 35, 0, 300, 5_000_000), chamber("R2", 20, 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, fs);
        assertTrue(has(rep, "LINES_INTERSECT"), rep.getIssues().toString());
    }

    @Test
    void collinearOverlapOfNewLinesIsRejected() throws Exception {
        InputModel in = load("14-common-node");
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 35, 99, 35, 89.265, 35, 60, 35, 0),
                line("n2", "B", "R", 10, 80, false, 1.0, 65, 99, 65, 89.265, 35, 60, 35, 0),   // shares 35,60 -> 35,0 with n1
                chamber("R", 35, 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, fs);
        assertTrue(has(rep, "LINES_INTERSECT"), rep.getIssues().toString());
    }

    /** Two new lines running 0.02 mm apart for 60 m (no exact intersection) must not pass as "not intersecting". */
    @Test
    void nearOverlapOfNewLinesIsRejected() throws Exception {
        InputModel in = load("14-common-node");
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 35, 99, 35, 89.265, 35, 0),
                line("n2", "B", "R", 10, 80, false, 1.0, 65, 99, 65, 89.265, 35.00002, 60, 35, 0),
                chamber("R", 35, 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, fs);
        assertTrue(has(rep, "LINES_INTERSECT") || has(rep, "LINES_TOO_CLOSE"), rep.getIssues().toString());
    }

    @Test
    void crossingAnotherExistingLineExactlyAtItsVertex() throws Exception {
        Run r = run("17-cross-existing-at-vertex");
        assertConnectedAndValid(r);
        assertTrue(r.crosses("heat_network#101"), "crossed at the interior vertex of line 101");
        assertTrue(r.crosses("road#1"));
        OutputFeature both = r.lines().stream().filter(f -> String.valueOf(f.prop("crossed_restrictions")).contains("heat_network#101")).findFirst().get();
        assertEquals(1.6, ((Number) both.prop("k_spec")).doubleValue(), 1e-9, "max Kspec of the overlapping objects");
        assertEquals(4.0, ((Number) both.prop("length")).doubleValue(), 1e-6);
        assertEquals(1, r.best.network().roots().size());
        assertEquals(100, r.chambers().get(0).prop("existing_line_id"), "the tie-in is on line 100, not on the crossed line 101");
    }

    @Test
    void tieInExactlyAtTheEndOfTheExistingLine() throws Exception {
        Run r = run("18-tie-in-at-endpoint");
        assertConnectedAndValid(r);
        assertEquals(1, r.chambers().size());
        Coordinate c = metric(r.chambers().get(0).point());
        assertEquals(0.0, c.distance(new Coordinate(100, 0)), 0.5, "chamber at the line end " + c);
        assertEquals(99.0, r.newLength(), 0.05);
    }

    @Test
    void tieInNextToAnExistingChamberUsesTheChamber() throws Exception {
        Run r = run("19-tie-in-near-chamber");
        assertConnectedAndValid(r);
        assertTrue(r.chambers().isEmpty(), "no new chamber 0.6 m from the existing one");
        assertTrue(r.lines().stream().anyMatch(f -> Integer.valueOf(200).equals(f.prop("end_node_id"))), "line ends in existing chamber 200");
        assertFalse(r.hasIssue("EXISTING_CHAMBER_WITHIN_10M"));
        assertEquals(1, ((Number) r.summary().prop("existing_chamber_tie_in_count")).intValue());
    }

    private static boolean passesCorridor(Run r, double x0, double x1, double y) {
        LineString gate = GeometryUtils.line(new Coordinate(OX + x0, OY + y), new Coordinate(OX + x1, OY + y));
        for (LineString l : routeLines(r)) if (l.intersects(gate)) return true;
        return false;
    }

    @Test
    void narrowCorridorJustWideEnoughIsUsed() throws Exception {
        Run r = run("20a-narrow-corridor-fits");
        assertConnectedAndValid(r);
        assertTrue(passesCorridor(r, 50.3, 53.7, 50), "route passes between the parks");
        assertEquals(99.0, r.newLength(), 0.05);
    }

    @Test
    void narrowCorridorTooNarrowIsBypassed() throws Exception {
        Run r = run("20b-narrow-corridor-blocked");
        assertConnectedAndValid(r);
        assertFalse(passesCorridor(r, 50.5, 53.5, 50), "3 m corridor is narrower than 2 x 1.685 m");
        assertTrue(r.newLength() > 105);
    }

    @Test
    void concaveBuilding() throws Exception {
        Run r = run("21-concave-oks");
        assertConnectedAndValid(r);
    }

    @Test
    void deepNicheIsNotUsedAsTheExit() throws Exception {
        Run r = run("22-deep-niche");
        assertConnectedAndValid(r);
        assertTrue(r.best.notes().stream().anyMatch(n -> n.contains("non-nearest boundary point")), r.best.notes().toString());
        Geometry own = restriction(r.input, "oks_A").geometry();
        // the final segment leaves through the roof (y = 140), not through the 4 m wide niche
        Coordinate a = metric(r.lines().get(0).line().get(1));
        assertTrue(a.y > 140, "free point beyond the north wall: " + a);
        assertFalse(r.hasIssue("OWN_OKS_CROSSED"));
    }

    @Test
    void nestedBuildingsMakeThePointUnreachable() throws Exception {
        Run r = run("23a-nested-oks");
        assertEquals(0, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
    }

    /** Strict reading of the annex: the clearance exemption covers only the own polygon; a point 4 m from the neighbour cannot be served. */
    @Test
    void pointWithinTheClearanceOfAnAdjacentBuildingIsUnconnected() throws Exception {
        Run r = run("23b-adjacent-oks");
        assertEquals(0, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        assertTrue(r.best.unconnected().values().iterator().next().contains("approach"), r.best.unconnected().toString());
    }

    @Test
    void selfIntersectingPolygonIsRepairedAndRespected() throws Exception {
        Run r = run("24-self-intersecting");
        assertTrue(diag(r, "INVALID_GEOMETRY_REPAIRED"));
        Geometry park = restriction(r.input, "1").geometry();
        assertTrue(park.isValid());
        assertConnectedAndValid(r);
        assertTrue(minDistance(r, park) >= 1.235 - 1e-3, "clearance to the repaired geometry: " + minDistance(r, park));
    }

    /** The bow-tie (20,30)-(60,70)-(60,30)-(20,70) consists of two lobes; a repair must keep both, not drop one. */
    @Test
    void bowTieRepairKeepsBothLobes() throws Exception {
        Run r = run("24-self-intersecting");
        Geometry park = restriction(r.input, "1").geometry();
        assertEquals(2, park.getNumGeometries(), "bow-tie repaired into two triangles: " + park);
        Geometry left = GeometryUtils.GF.createPolygon(new Coordinate[]{abs(20, 30), abs(40, 50), abs(20, 70), abs(20, 30)});
        Geometry right = GeometryUtils.GF.createPolygon(new Coordinate[]{abs(60, 70), abs(40, 50), abs(60, 30), abs(60, 70)});
        assertTrue(minDistance(r, left) >= 1.235 - 1e-3, "clearance to the left lobe: " + minDistance(r, left));
        assertTrue(minDistance(r, right) >= 1.235 - 1e-3, "clearance to the right lobe: " + minDistance(r, right));
    }

    private static Coordinate abs(double x, double y) { return new Coordinate(OX + x, OY + y); }

    @Test
    void sliverPolygonIsAvoided() throws Exception {
        Run r = run("25-sliver");
        assertConnectedAndValid(r);
        Geometry sliver = restriction(r.input, "1").geometry();
        assertTrue(minDistance(r, sliver) >= 1.235 - 1e-3, "distance to the 1 cm sliver: " + minDistance(r, sliver));
    }

    @Test
    void edgeAlmostParallelToTheRoute() throws Exception {
        Run r = run("26-almost-parallel");
        assertConnectedAndValid(r);
        Geometry park = restriction(r.input, "1").geometry();
        assertTrue(minDistance(r, park) >= 1.235 - 1e-3);
        assertFalse(r.hasIssue("CLEARANCE"));
    }

    @Test
    void twoRestrictionsCrossingAtTheSamePoint() throws Exception {
        Run r = run("27-shared-crossing-point");
        assertConnectedAndValid(r);
        List<OutputFeature> special = r.lines().stream().filter(f -> "special".equals(f.prop("laying_method"))).collect(Collectors.toList());
        assertEquals(1, special.size(), "one special piece covers both crossings");
        String crossed = String.valueOf(special.get(0).prop("crossed_restrictions"));
        assertTrue(crossed.contains("gas_pipeline#1") && crossed.contains("power_cable#2"), crossed);
        assertEquals(1.25, ((Number) special.get(0).prop("k_spec")).doubleValue(), 1e-9, "max of 1.25 and 1.15");
    }

    @Test
    void specialSectionsStartingFiveMillimetresApartDoNotProduceDegeneratePieces() throws Exception {
        Run r = run("28a-near-coincident-sections");
        assertConnectedAndValid(r);
        assertEquals(0, r.report.getWarningCount(), r.issues());
        for (OutputFeature f : r.lines()) assertTrue(((Number) f.prop("length")).doubleValue() >= 0.02, "no piece shorter than the snap tolerance: " + f.prop("length"));
        List<OutputFeature> special = r.lines().stream().filter(f -> "special".equals(f.prop("laying_method"))).collect(Collectors.toList());
        assertEquals(1, special.size(), "the two near-coincident sections form one special piece");
        String crossed = String.valueOf(special.get(0).prop("crossed_restrictions"));
        assertTrue(crossed.contains("gas_pipeline#1") && crossed.contains("gas_pipeline#2"), crossed);
    }

    @Test
    void specialSectionsHalfAMetreApartAreSplitCorrectly() throws Exception {
        Run r = run("28b-close-sections");
        assertConnectedAndValid(r);
        List<OutputFeature> special = r.lines().stream().filter(f -> "special".equals(f.prop("laying_method"))).collect(Collectors.toList());
        assertEquals(3, special.size(), "gas1 only / both / gas2 only");
        double total = special.stream().mapToDouble(f -> ((Number) f.prop("length")).doubleValue()).sum();
        assertEquals(4.5, total, 1e-6);
    }

    @Test
    void allFixturesProduceValidOutputOrACleanUnconnectedResult() throws Exception {
        for (AdversarialFixtureGenerator.Scene s : AdversarialFixtureGenerator.scenes()) {
            Run r = run(s.name);
            assertTrue(r.report.isValid(), s.name + ": " + r.issues());
            assertEquals(1, r.best.rank());
            for (OutputFeature f : r.lines()) {
                double len = ((Number) f.prop("length")).doubleValue();
                assertTrue(len > 0 && Double.isFinite(len), s.name + ": length " + len);
                assertTrue(((Number) f.prop("cost")).doubleValue() >= 0);
            }
            assertTrue(r.input.diagnostics().messages().stream().noneMatch(m -> m.getSeverity() == Diagnostics.Severity.ERROR), s.name + ": " + r.input.diagnostics().messages());
        }
    }
}
