package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.solver.ExistingNetworkIndex;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.SolverContext;
import ru.lct.heatnet.solver.TieInCandidate;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;

/**
 * Rule: annex 2.1 — «К одной камере может примыкать не более четырёх линейных участков»; an existing line ending in
 * the chamber = 1 adjacency, an existing line passing through = 2.
 * <pre>
 * degree after connection | 3       | 4       | 5
 * result                  | allowed | allowed | forbidden (CHAMBER_DEGREE, not a tie-in candidate, spareDegree 0)
 * </pre>
 */
class ChamberDegreeBoundaryTest {

    /** Existing adjacency 2 (one line passes through the chamber) / 3 (passing + ending) / 4 (two lines pass through). */
    private static InputModel withAdjacency(int adjacency) {
        SyntheticInput s = new SyntheticInput().line(1, 300, 0, 0, 200, 0).chamber(200, 100, 0).point("A", 60, 150, 10).point("B", 100, 150, 10).point("C", 140, 150, 10);
        if (adjacency == 3) s.line(2, 300, 100, 0, 100, -80);
        if (adjacency == 4) s.line(2, 300, 100, -80, 100, 80);
        return s.build();
    }

    @Test
    void endingLineCountsOneAndPassingLineCountsTwo() {
        ExistingNetworkIndex idx = new ExistingNetworkIndex(withAdjacency(2));
        assertEquals(2, idx.existingAdjacency(idx.input().chambers().get(0)));
        assertEquals(3, new ExistingNetworkIndex(withAdjacency(3)).existingAdjacencyAt(SyntheticInput.c(100, 0)));
        assertEquals(4, new ExistingNetworkIndex(withAdjacency(4)).existingAdjacencyAt(SyntheticInput.c(100, 0)));
        assertEquals(1, idx.existingAdjacencyAt(SyntheticInput.c(0, 0)), "free end of a line = 1");
    }

    @Test
    void chamberAcceptsOneMoreLineOnlyWhileTheDegreeStaysAtMostFour() {
        for (int adjacency : new int[]{2, 3, 4}) {
            InputModel in = withAdjacency(adjacency);
            ExistingNetworkIndex idx = new ExistingNetworkIndex(in);
            ExistingChamber ch = in.chambers().get(0);
            boolean usable = adjacency + 1 <= 4;
            assertEquals(usable, !idx.usableChambersNear(ch.coordinate()).isEmpty(), "adjacency " + adjacency);
            List<TieInCandidate> cands = new SolverContext(in, SolverConfig.defaults()).resources(ClearanceClass.SMALL).tieIns;
            assertEquals(usable ? 1 : 0, cands.stream().filter(t -> t.kind() == TieInCandidate.Kind.EXISTING_CHAMBER).count(), "adjacency " + adjacency);
            NetNode root = new NewNetwork().rootAtExistingChamber(ch, adjacency);
            assertEquals(4 - adjacency, root.spareDegree());
        }
    }

    @Test
    void validatorFlagsFiveAdjacenciesButAcceptsFour() {
        for (int adjacency : new int[]{2, 3}) {
            for (int newLines = 1; newLines <= 3; newLines++) {
                int degree = adjacency + newLines;
                if (degree < 3 || degree > 5) continue;
                InputModel in = withAdjacency(adjacency);
                List<OutputFeature> fs = new ArrayList<>();
                String[] ids = {"A", "B", "C"};
                double[] xs = {60, 100, 140};
                for (int k = 0; k < newLines; k++) fs.add(line("n" + k, ids[k], 200, 10, 80, false, 1.0, xs[k], 150, 100, 0));
                fs.add(summary(fs, newLines));
                ValidationReport rep = validate(in, fs);
                assertEquals(degree > 4, has(rep, "CHAMBER_DEGREE"), "adjacency " + adjacency + " + " + newLines + " new lines = " + degree + ": " + issues(rep));
                if (degree <= 4) assertFalse(has(rep, "CHAMBER_DEGREE"));
            }
        }
    }
}
