package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.ObstacleSpace;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class TieInCandidatesTest {

    /**
     * Existing line y=0 from x=0..100 is covered by two prohibited sites except a gap; with the 1.685 m clearance the
     * admissible interval on the line is x in (32.1, 35.9): every point of a 10 m grid (0,10,...,100) and every line
     * vertex lies inside a forbidden zone, so the old sampling had no candidate at all.
     */
    private static InputModel input() {
        return new SyntheticInput()
                .line(1, 300, 0, 0, 100, 0)
                .rect(2, "prohibited_site", -10, -5, 30.415, 5)
                .rect(3, "prohibited_site", 37.585, -5, 110, 5)
                .point("A", 34, 80, 10.0)
                .build();
    }

    @Test
    void tenMetreGridHasNoAdmissiblePoint() {
        ObstacleSpace space = new ObstacleSpace(input(), ClearanceClass.SMALL);
        for (double x = 0; x <= 100; x += 10) assertTrue(space.insideForbidden(c(x, 0)), "x=" + x);
        assertFalse(space.insideForbidden(c(34, 0)));
    }

    @Test
    void geometricCandidatesFindTheOnlyAdmissibleCorridor() {
        InputModel in = input();
        SolverContext ctx = new SolverContext(in, SolverConfig.defaults());
        List<TieInCandidate> cands = ctx.resources(ClearanceClass.SMALL).tieIns;
        assertFalse(cands.isEmpty(), "candidates inside the 3.8 m corridor must exist");
        for (TieInCandidate t : cands) {
            assertTrue(t.point().x - SyntheticInput.OX > 32.0 && t.point().x - SyntheticInput.OX < 36.0, "candidate outside the corridor: " + t);
        }
        // and the point actually gets connected there
        SolveResult r = new HeatnetSolver(SolverConfig.defaults(), new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
        assertFalse(r.variants().isEmpty());
        VariantResult v = r.variants().get(0);
        assertEquals(1, v.connectedCount(), v.unconnected().toString());
        Coordinate root = v.network().roots().get(0).coord();
        assertTrue(root.x - SyntheticInput.OX > 32.0 && root.x - SyntheticInput.OX < 36.0);
        List<OutputFeature> fs = new ArrayList<>(new OutputBuilder().build(v));
        assertTrue(new ResultValidator(in).validate(fs).isValid());
    }
}
