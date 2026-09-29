package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.restrictions.ClearanceClass;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class ExistingNetworkIndexTest {

    @Test
    void chamberAdjacencyCountsEndingAndPassingLines() {
        InputModel in = new SyntheticInput()
                .line(1, 300, 0, 0, 100, 0).line(2, 300, 100, 0, 200, 0)   // both end at (100,0)
                .line(3, 300, 300, -50, 300, 50)                           // passes through (300,0)
                .chamber(10, 100, 0).chamber(11, 300, 0).chamber(12, 500, 0).build();
        ExistingNetworkIndex idx = new ExistingNetworkIndex(in);
        assertEquals(2, idx.existingAdjacency(in.chambers().get(0)));
        assertEquals(2, idx.existingAdjacency(in.chambers().get(1)), "a line passing through takes two slots");
        assertEquals(0, idx.existingAdjacency(in.chambers().get(2)));
        assertEquals(2, idx.existingAdjacencyAt(c(50, 0)), "interior point of a line");
        assertEquals(1, idx.existingAdjacencyAt(c(0, 0)), "free end of a line");
    }

    @Test
    void tenMetreRuleSelectsExistingChamberCandidates() {
        InputModel in = new SyntheticInput()
                .line(1, 300, 0, 0, 200, 0).chamber(10, 100, 0).point("p", 100, 300, 5).build();
        SolverContext ctx = new SolverContext(in, SolverConfig.defaults());
        List<TieInCandidate> cands = ctx.resources(ClearanceClass.SMALL).tieIns;
        long chambers = cands.stream().filter(t -> t.kind() == TieInCandidate.Kind.EXISTING_CHAMBER).count();
        assertEquals(1, chambers);
        for (TieInCandidate t : cands) {
            if (t.kind() == TieInCandidate.Kind.NEW_CHAMBER_ON_LINE)
                assertTrue(t.point().distance(c(100, 0)) > 10.0, "points within 10 m of a usable chamber are replaced by the chamber: " + t);
        }
        assertTrue(cands.size() > 10);
        // a full chamber (4 existing lines) is not a candidate and does not absorb nearby points
        InputModel full = new SyntheticInput()
                .line(1, 300, 0, 0, 100, 0).line(2, 300, 100, 0, 200, 0).line(3, 300, 100, 0, 100, 100).line(4, 300, 100, 0, 100, -100)
                .chamber(10, 100, 0).point("p", 100, 300, 5).build();
        List<TieInCandidate> c2 = new SolverContext(full, SolverConfig.defaults()).resources(ClearanceClass.SMALL).tieIns;
        assertEquals(0, c2.stream().filter(t -> t.kind() == TieInCandidate.Kind.EXISTING_CHAMBER).count());
        assertTrue(c2.stream().anyMatch(t -> t.point().distance(c(100, 0)) <= 10.0));
    }
}
