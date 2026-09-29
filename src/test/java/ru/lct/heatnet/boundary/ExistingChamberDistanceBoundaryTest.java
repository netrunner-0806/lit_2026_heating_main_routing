package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.solver.ExistingNetworkIndex;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.SolverContext;
import ru.lct.heatnet.solver.TieInCandidate;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;
import static ru.lct.heatnet.SyntheticInput.c;

/**
 * Rule: annex 2.4 — «не далее 10 м от существующей тепловой камеры ... используется эта камера» → the boundary
 * belongs to the rule (d ≤ 10.000 m uses the chamber, d = 10.001 m does not).
 * <pre>
 * distance  | 9.999 | 10.000 | 10.001
 * chamber   | used  | used   | new chamber allowed
 * </pre>
 * Checked on ExistingNetworkIndex (metric), on the tie-in candidate generation (points absorbed by the chamber) and
 * on the validator (EXISTING_CHAMBER_WITHIN_10M for a new chamber that should have been the existing one).
 */
class ExistingChamberDistanceBoundaryTest {

    private static final double[] D = {9.999, 10.000, 10.001};
    private static final boolean[] USES_CHAMBER = {true, true, false};

    @Test
    void indexTreatsTenMetresAsInclusive() {
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 200, 0).chamber(10, 100, 0).build();
        ExistingNetworkIndex idx = new ExistingNetworkIndex(in);
        for (int i = 0; i < D.length; i++) {
            Coordinate p = c(100 + D[i], 0);
            assertEquals(D[i], p.distance(c(100, 0)), 1e-9);
            assertEquals(USES_CHAMBER[i], !idx.usableChambersNear(p).isEmpty(), "d=" + D[i]);
        }
    }

    @Test
    void tieInCandidatesWithinTenMetresAreReplacedByTheChamber() {
        for (int i = 0; i < D.length; i++) {
            double x = 100 + D[i];
            // the vertex shared by the two lines is a candidate source; the chamber at (100,0) passes through line 1
            InputModel in = new SyntheticInput().line(1, 300, 0, 0, x, 0).line(2, 300, x, 0, 300, 0).chamber(10, 100, 0).point("p", 150, 300, 5).build();
            List<TieInCandidate> cands = new SolverContext(in, SolverConfig.defaults()).resources(ClearanceClass.SMALL).tieIns;
            boolean chamberCandidate = cands.stream().anyMatch(t -> t.kind() == TieInCandidate.Kind.EXISTING_CHAMBER);
            assertTrue(chamberCandidate, "the existing chamber is always a candidate");
            boolean vertexCandidate = cands.stream().anyMatch(t -> t.kind() == TieInCandidate.Kind.NEW_CHAMBER_ON_LINE && t.point().distance(c(x, 0)) < 1e-6);
            assertEquals(!USES_CHAMBER[i], vertexCandidate, "vertex at " + D[i] + " m from the chamber is a new-chamber candidate?");
            for (TieInCandidate t : cands)
                if (t.kind() == TieInCandidate.Kind.NEW_CHAMBER_ON_LINE) assertTrue(t.point().distance(c(100, 0)) > 10.0, "no new-chamber candidate within 10 m: " + t);
        }
    }

    @Test
    void validatorRequiresTheExistingChamberUpToTenMetres() {
        for (int i = 0; i < D.length; i++) {
            double x = 100 + D[i];
            InputModel in = new SyntheticInput().line(1, 300, 0, 0, 300, 0).chamber(200, 100, 0).point("A", x, 200, 10).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    line("n1", "A", "R", 10, 80, false, 1.0, x, 200, x, 0),
                    chamber("R", x, 0, 300)));
            fs.add(summary(fs, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(USES_CHAMBER[i], has(rep, "EXISTING_CHAMBER_WITHIN_10M"), "d=" + D[i] + ": " + issues(rep));
            if (!USES_CHAMBER[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }

    @Test
    void validatorToleranceDoesNotStretchTheTenMetres() {
        // 10.10 m is not "10 m": a new chamber there is legitimate (no 0.05 m snap in this rule)
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 300, 0).chamber(200, 100, 0).point("A", 110.1, 200, 10).build();
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 110.1, 200, 110.1, 0),
                chamber("R", 110.1, 0, 300)));
        fs.add(summary(fs, 0));
        ValidationReport rep = validate(in, fs);
        assertFalse(has(rep, "EXISTING_CHAMBER_WITHIN_10M"), issues(rep));
        // and 9.95 m is flagged (no tolerance in the other direction either)
        InputModel in2 = new SyntheticInput().line(1, 300, 0, 0, 300, 0).chamber(200, 100, 0).point("A", 109.95, 200, 10).build();
        List<OutputFeature> fs2 = new ArrayList<>(Arrays.asList(
                line("n1", "A", "R", 10, 80, false, 1.0, 109.95, 200, 109.95, 0),
                chamber("R", 109.95, 0, 300)));
        fs2.add(summary(fs2, 0));
        assertTrue(has(validate(in2, fs2), "EXISTING_CHAMBER_WITHIN_10M"));
    }
}
