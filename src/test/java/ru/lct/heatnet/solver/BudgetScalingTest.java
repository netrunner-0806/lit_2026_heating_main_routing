package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.routing.*;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.testkit.SolveKit;
import static org.junit.jupiter.api.Assertions.*;

class BudgetScalingTest {
    @Test void scalingLeavesUnrelatedBoundsUntouched() {
        var b=ComputeBudget.expanded();
        for(int i=1;i<=3;i++) {
            var x=BudgetTestProfiles.scale(b,i,i,i);
            assertEquals(130000*i,x.navigationNodes);assertEquals(5200000*i,x.directedEdges);
            assertEquals(65000000L*i,x.visibilityChecks);
            assertEquals(b.gridCells,x.gridCells);assertEquals(b.candidateEvaluations,x.candidateEvaluations);
        }
        assertThrows(IllegalArgumentException.class,()->BudgetTestProfiles.scale(b,4,1,1));
    }
    @Test void unusedComputationalCapsDoNotChangeOverlayForecast() {
        var input=new SyntheticInput().build();var cfg=SolverConfig.defaults();
        cfg.useBudget(ComputeBudget.expanded());
        long reference=ParallelMemoryGuard.additionalBytes(input,cfg,9000,4000000);
        assertEquals(7L<<30,cfg.parallelWorkingSetBytes);
        for(int factor=1;factor<=3;factor++){
            cfg.useBudget(BudgetTestProfiles.scale(ComputeBudget.expanded(),factor,factor,factor));
            assertEquals(reference,ParallelMemoryGuard.additionalBytes(input,cfg,9000,4000000));
        }
    }
    @Test void actualGraphGrowthIncreasesForecast() {
        var input=new SyntheticInput().build();var cfg=SolverConfig.defaults();
        assertTrue(ParallelMemoryGuard.additionalBytes(input,cfg,9000,4000000)
                >ParallelMemoryGuard.additionalBytes(input,cfg,1000,10000));
    }
    @Test void deniedProfilesRunDeterministicallyWithoutWorkersOrQualityLoss()throws Exception {
        String reference=null;
        for(int factor:new int[]{1,2,3,3,2}) {
            var input=new SyntheticInput().line("net",300,0,0,100,0).point("cp",30,30,1).build();
            var cfg=SolverConfig.defaults();cfg.useBudget(BudgetTestProfiles.scale(ComputeBudget.expanded(),factor,factor,factor));
            cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;cfg.parallelWorkingSetBytes=1;
            var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),null,StrategyTestSupport::controlledMemory).solve(input);
            assertEquals(1,result.variants().get(0).connectedCount());
            assertEquals("MEMORY_ADMISSION",result.metrics().get("parallel_fallback")); // Deliberate soft admission denial, independent of test JVM size.
            var bytes=new java.io.ByteArrayOutputStream();
            new ru.lct.heatnet.output.GeoJsonResultWriter().write(bytes,SolveKit.render(result));
            String output=bytes.toString("UTF-8");
            if(reference==null)reference=output;else assertEquals(reference,output);
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
        }
    }
}
