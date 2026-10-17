package ru.lct.heatnet.search;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class AuditSearchHypothesesTest {
    private SolveResult solve(InputModel in,long budget,boolean depth) {
        SolverConfig cfg=SolverConfig.defaults();cfg.topK=1;cfg.improve=false;cfg.depthMode=depth;cfg.wideningTimeBudgetMs=budget;
        return new HeatnetSolver(cfg,new SolverVariantValidator(in)).solve(in,
                Collections.singletonList(HeatnetSolver.defaultStrategies().get(0)),null);
    }
    @Test void wideningHasSameConnectivityWithOneMillisecondAndDefaultBudget() {
        InputModel in=new SyntheticInput().line(1,300,-600,0,600,0)
                .polyline(2,"gas_pipeline",-600,3,400,3)
                .rect(3,"oks",-5,206,5,220).point("A",0,210,10).build();
        SolveResult small=solve(in,1,true),normal=solve(in,15000,true);
        assertFalse(normal.variants().isEmpty()); assertEquals(1,normal.variants().get(0).connectedCount());
        assertFalse(small.variants().isEmpty()); assertEquals(1,small.variants().get(0).connectedCount());
        assertEquals(normal.variants().get(0).summary().score(),small.variants().get(0).summary().score(),1e-9);
    }
    @Test void diameterClassUpgradePreservesBothConsumersInNarrowCorridor() {
        InputModel in=new SyntheticInput().line("net",1000,-80,0,80,0)
                .rect("west","oks",-100,5,-6.4,100).rect("east","oks",6.4,5,100,100)
                .point("A",-0.2,90,700).point("B",0.2,70,300).build();
        var result=new HeatnetSolver(SolverConfig.defaults(),new SolverVariantValidator(in)).solve(in);
        assertFalse(result.variants().isEmpty());
        assertTrue(result.variants().stream().anyMatch(v->v.connectedCount()==2),result.diagnostics().messages().toString());
    }
}
