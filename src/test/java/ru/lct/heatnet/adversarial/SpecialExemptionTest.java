package ru.lct.heatnet.adversarial;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.adversarial.AdversarialSupport.*;

/**
 * Phase 6: the neighbourhood of a special crossing where the alongside clearance is not checked. Invariant: the
 * exemption exists only around an actual crossing and never allows a long run parallel to the object inside its
 * clearance zone.
 */
class SpecialExemptionTest {

    private static List<OutputFeature> special(Run r) {
        return r.lines().stream().filter(f -> "special".equals(f.prop("laying_method"))).collect(Collectors.toList());
    }

    /** 1. perpendicular road crossing: one straight special section = road width + 3 m on each side, Kspec 1.6. */
    @Test
    void perpendicularRoadCrossingIsValid() throws Exception {
        Run r = run("61-road-perpendicular");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        List<OutputFeature> sp = special(r);
        assertEquals(1, sp.size());
        assertEquals(16.0, ((Number) sp.get(0).prop("length")).doubleValue(), 1e-6);
        assertEquals(1.6, ((Number) sp.get(0).prop("k_spec")).doubleValue(), 1e-9);
        assertEquals(2, sp.get(0).line().size(), "special feature is one straight LineString");
    }

    /** 2. diagonal crossing at 60 degrees is legitimate; at 30 degrees the entry angle rule is violated. */
    @Test
    void diagonalRoadCrossingAngle() throws Exception {
        InputModel in = load("61-road-perpendicular");   // road y in [40, 50]
        // 60 degrees: direction (0.5, -0.866); inside length 11.547 m; section = 3 m before entry .. 3 m after exit
        double dx = 0.5, dy = -Math.sqrt(3) / 2;
        double ex = 52 + 10 / Math.tan(Math.toRadians(60));
        double[] t1 = {52 - 3 * dx, 50 - 3 * dy}, t2 = {ex + 3 * dx, 40 + 3 * dy};
        List<OutputFeature> ok = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 52, 99, 52, 89.265, t1[0], t1[1]),
                line("n2", "t1", "t2", 10, 80, true, 1.6, t1[0], t1[1], t2[0], t2[1]),
                line("n3", "t2", "R", 10, 80, false, 1.0, t2[0], t2[1], t2[0], 0),
                tn("t1", t1[0], t1[1]), tn("t2", t2[0], t2[1]), chamber("R", t2[0], 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, ok);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        assertFalse(has(rep, "CROSSING_ANGLE"));
        // 30 degrees: direction (0.866, -0.5)
        dx = Math.sqrt(3) / 2; dy = -0.5;
        ex = 52 + 10 / Math.tan(Math.toRadians(30));
        double[] u1 = {52 - 3 * dx, 50 - 3 * dy}, u2 = {ex + 3 * dx, 40 + 3 * dy};
        List<OutputFeature> bad = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 52, 99, 52, 89.265, u1[0], u1[1]),
                line("n2", "t1", "t2", 10, 80, true, 1.6, u1[0], u1[1], u2[0], u2[1]),
                line("n3", "t2", "R", 10, 80, false, 1.0, u2[0], u2[1], u2[0], 0),
                tn("t1", u1[0], u1[1]), tn("t2", u2[0], u2[1]), chamber("R", u2[0], 0, 300, 5_000_000)));
        ValidationReport rep2 = validate(in, bad);
        assertTrue(has(rep2, "CROSSING_ANGLE"), rep2.getIssues().toString());
    }

    /** 3. after a legitimate crossing the route runs along the gas pipeline 2.2 m away (required 2.435 m): the exemption does not cover it. */
    @Test
    void runningAlongTheObjectAfterACrossingIsInvalid() throws Exception {
        InputModel in = load("07-multilinestring-restriction");   // gas pipeline along y = 50
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 50, 99, 50, 89.265, 50, 52),
                line("n2", "t1", "t2", 10, 80, true, 1.25, 50, 52, 50, 48),
                line("n3", "t2", "R", 10, 80, false, 1.0, 50, 48, 60, 47.8, 100, 47.8, 100, 0),   // 40 m parallel run at 2.2 m
                tn("t1", 50, 52), tn("t2", 50, 48), chamber("R", 100, 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, fs);
        assertTrue(has(rep, "CLEARANCE"), rep.getIssues().toString());
        assertTrue(rep.getIssues().stream().anyMatch(i -> i.getCode().equals("CLEARANCE") && i.getMessage().contains("gas_pipeline")));
        // the same crossing followed by a straight continuation is valid
        List<OutputFeature> ok = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 50, 99, 50, 89.265, 50, 52),
                line("n2", "t1", "t2", 10, 80, true, 1.25, 50, 52, 50, 48),
                line("n3", "t2", "R", 10, 80, false, 1.0, 50, 48, 50, 0),
                tn("t1", 50, 52), tn("t2", 50, 48), chamber("R", 50, 0, 300, 5_000_000)));
        assertTrue(validate(in, ok).isValid(), validate(in, ok).getIssues().toString());
        // a parallel run without any crossing is invalid as well (no exemption without a crossing)
        List<OutputFeature> along = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 50, 99, 50, 89.265, 50, 52.2, 100, 52.2, 100, 52.5, 130, 60, 130, 0),
                chamber("R", 130, 0, 300, 5_000_000)));
        ValidationReport rep3 = validate(in, along);
        assertTrue(has(rep3, "CLEARANCE") || has(rep3, "CROSSING_NOT_SPECIAL") || !rep3.isValid(), rep3.getIssues().toString());
    }

    /** 4. the route enters and leaves the neighbourhood of the same object twice: correct split accepted, wrong split rejected. */
    @Test
    void twoPassesThroughTheSameNeighbourhoodAreSplitCorrectly() throws Exception {
        InputModel in = load("65-gas-crossed-twice");   // gas: (30,30)-(74,30)-(74,60)-(30,60)
        List<OutputFeature> ok = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 52, 99, 52, 89.265, 52, 62),
                line("n2", "t1", "t2", 10, 80, true, 1.25, 52, 62, 52, 58),
                line("n3", "t2", "t3", 10, 80, false, 1.0, 52, 58, 52, 32),
                line("n4", "t3", "t4", 10, 80, true, 1.25, 52, 32, 52, 28),
                line("n5", "t4", "R", 10, 80, false, 1.0, 52, 28, 52, 0),
                tn("t1", 52, 62), tn("t2", 52, 58), tn("t3", 52, 32), tn("t4", 52, 28), chamber("R", 52, 0, 300, 5_000_000)));
        ValidationReport rep = validate(in, ok);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        assertEquals(0, rep.getWarningCount(), rep.getIssues().toString());
        // both crossings inside one base line: rejected
        List<OutputFeature> bad = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 52, 99, 52, 89.265, 52, 0),
                chamber("R", 52, 0, 300, 5_000_000)));
        assertTrue(has(validate(in, bad), "CROSSING_NOT_SPECIAL"));
        // the second crossing left as base: rejected
        List<OutputFeature> half = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 52, 99, 52, 89.265, 52, 62),
                line("n2", "t1", "t2", 10, 80, true, 1.25, 52, 62, 52, 58),
                line("n3", "t2", "R", 10, 80, false, 1.0, 52, 58, 52, 0),
                tn("t1", 52, 62), tn("t2", 52, 58), chamber("R", 52, 0, 300, 5_000_000)));
        assertTrue(has(validate(in, half), "CROSSING_NOT_SPECIAL"));
    }

    /** 5. the solver crosses the same object twice: two separate special passages of 4 m each. */
    @Test
    void twoCrossingsOfTheSameObjectAreTwoPassages() throws Exception {
        Run r = run("65-gas-crossed-twice");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        List<OutputFeature> sp = special(r);
        assertEquals(2, sp.size());
        for (OutputFeature f : sp) {
            assertEquals(4.0, ((Number) f.prop("length")).doubleValue(), 1e-6);
            assertTrue(String.valueOf(f.prop("crossed_restrictions")).contains("gas_pipeline#1"));
        }
        assertEquals(5, r.lines().size(), "base / special / base / special / base");
    }

    /** 6. crossing a road that contains a gas pipeline: overlapping special sections, max Kspec on the overlap. */
    @Test
    void overlappingSpecialObjects() throws Exception {
        Run r = run("66-road-with-gas");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        List<OutputFeature> sp = special(r);
        assertEquals(3, sp.size(), "road only / road + gas / road only");
        OutputFeature both = sp.stream().filter(f -> String.valueOf(f.prop("crossed_restrictions")).contains("gas_pipeline")).findFirst().get();
        assertEquals(1.6, ((Number) both.prop("k_spec")).doubleValue(), 1e-9);
        assertEquals(4.0, ((Number) both.prop("length")).doubleValue(), 1e-6);
        double total = sp.stream().mapToDouble(f -> ((Number) f.prop("length")).doubleValue()).sum();
        assertEquals(16.0, total, 1e-6);
        assertEquals(0, r.report.getWarningCount(), "technical nodes justified by the change of the crossed set: " + r.issues());
    }

    /** 7. a gas pipeline 1.5 m from the existing line: the special section cannot end at the tie-in chamber. */
    @Test
    void specialZoneCannotEndAtTheChamber() throws Exception {
        Run r = run("67-gas-near-tie-in");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        Coordinate root = metric(r.chambers().get(0).point());
        assertTrue(root.x > 74 + 1e-6 || root.x < 30 - 1e-6, "tie-in outside the extent of the pipeline: " + root);
        assertTrue(special(r).isEmpty());
        // hand-built straight route: the 2 m section extends 0.5 m beyond the tie-in point
        List<OutputFeature> bad = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 52, 99, 52, 89.265, 52, 3.5),
                line("n2", "t1", "R", 10, 80, true, 1.25, 52, 3.5, 52, 0),
                tn("t1", 52, 3.5), chamber("R", 52, 0, 300, 5_000_000)));
        ValidationReport rep = validate(r.input, bad);
        assertTrue(has(rep, "SECTION_OUTSIDE"), rep.getIssues().toString());
    }

    /** 8. two special zones 0.5 m apart: gas section, a 0.5 m base piece between technical nodes, road section. */
    @Test
    void specialZoneEndingNextToATechnicalNode() throws Exception {
        Run r = run("68-road-then-gas");
        assertEquals(1, r.best.connectedCount());
        assertTrue(r.report.isValid(), r.issues());
        assertEquals(0, r.report.getWarningCount(), r.issues());
        List<OutputFeature> ls = r.lines();
        assertEquals(5, ls.size());
        List<String> methods = ls.stream().map(f -> String.valueOf(f.prop("laying_method"))).collect(Collectors.toList());
        assertEquals(Arrays.asList("base", "special", "base", "special", "base"), methods);
        assertEquals(0.5, ((Number) ls.get(2).prop("length")).doubleValue(), 1e-6);
        assertEquals(4.0, ((Number) ls.get(1).prop("length")).doubleValue(), 1e-6);
        assertEquals(16.0, ((Number) ls.get(3).prop("length")).doubleValue(), 1e-6);
    }
}
