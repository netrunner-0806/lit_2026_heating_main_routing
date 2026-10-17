package ru.lct.heatnet.solver;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.ClearanceClass;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class ChamberJunctionDiameterTest {
    @Test void junctionCandidateUsesLargestIncidentDiameterInEitherInputOrder() {
        for(boolean reverse:new boolean[]{false,true}) {
            SyntheticInput b=new SyntheticInput().point("cp",0,30,1);
            if(reverse)b.line("large",800,0,0,30,0).line("small",300,-30,0,0,0);
            else b.line("small",300,-30,0,0,0).line("large",800,0,0,30,0);
            var r=new SolverContext(b.build(),SolverConfig.defaults()).resources(ClearanceClass.SMALL);
            var t=r.tieIns.stream().filter(x->x.point().distance(c(0,0))<1e-6).findFirst().orElseThrow();
            assertEquals(800,t.line().diameter());
        }
    }
}
