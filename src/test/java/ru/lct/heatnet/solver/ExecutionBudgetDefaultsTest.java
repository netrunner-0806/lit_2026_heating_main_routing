package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.routing.ComputeBudget;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.testkit.SolveKit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionBudgetDefaultsTest {
    private ru.lct.heatnet.domain.InputModel scene() {
        return new SyntheticInput().line("net",300,0,0,100,0).point("a",30,30,1).point("b",70,35,1).build();
    }
    private void profile(ComputeBudget actual,int nodes,int edges,long checks,int grid,int candidates) {
        assertEquals(nodes,actual.navigationNodes);assertEquals(edges,actual.directedEdges);
        assertEquals(checks,actual.visibilityChecks);assertEquals(grid,actual.gridCells);
        assertEquals(candidates,actual.candidateEvaluations);
    }
    private String geo(SolveResult result)throws Exception {
        var out=new java.io.ByteArrayOutputStream();
        new ru.lct.heatnet.output.GeoJsonResultWriter().write(out,SolveKit.render(result));
        return out.toString("UTF-8");
    }
    @Test void sequentialKeepsOriginalGraphAndAdaptiveLimits() {
        var cfg=SolverConfig.defaults();var ctx=new SolverContext(scene(),cfg);
        assertEquals(SolverConfig.ExecutionMode.SEQUENTIAL,cfg.executionMode);
        profile(ctx.resources(ClearanceClass.SMALL).graph.budget(),100000,4000000,50000000,100000,1000);
        assertEquals(1000,cfg.maxCandidateEvaluations);
    }
    @Test void baseAndPrivateGraphsReceiveOneFixedProfileWithIndependentCounters() {
        var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var base=new SolverContext(scene(),cfg);
        var original=base.resources(ClearanceClass.SMALL).graph;
        var a=base.experimentalFork().resources(ClearanceClass.SMALL).graph;
        var b=base.experimentalFork().resources(ClearanceClass.SMALL).graph;
        for(var graph:new ru.lct.heatnet.routing.NavGraph[]{original,a,b}) {
            profile(graph.budget(),260000,10400000,130000000,130000,1300);
            assertSame(cfg.computeBudget,graph.budget());
        }
        long prepared=original.visibilityCheckCount();
        a.reserveFallbackChecks(1);
        assertEquals(prepared+1,a.visibilityCheckCount());
        assertEquals(prepared,b.visibilityCheckCount());assertEquals(prepared,original.visibilityCheckCount());
        assertEquals(1300,cfg.maxCandidateEvaluations);
        assertEquals(7L<<30,cfg.parallelWorkingSetBytes);
        assertEquals(9L<<30,cfg.parallelCriticalHeapBytes);assertEquals(11L<<30,cfg.parallelCriticalRssBytes);
    }
    @Test void repeatedResolutionAndModeSwitchDoNotCompoundBudgets() {
        var cfg=SolverConfig.defaults();
        for(int repeat=0;repeat<10;repeat++) {
            cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
            for(int i=0;i<10;i++)cfg.resolveExecutionBudget();
            profile(cfg.computeBudget,260000,10400000,130000000,130000,1300);assertEquals(1300,cfg.maxCandidateEvaluations);
            cfg.executionMode=SolverConfig.ExecutionMode.SEQUENTIAL;cfg.resolveExecutionBudget();
            profile(cfg.computeBudget,100000,4000000,50000000,100000,1000);assertEquals(1000,cfg.maxCandidateEvaluations);
        }
    }
    @Test void explicitProfilesTakePrecedenceEvenWhenTheyEqualOriginalDefault() {
        for(var budget:new ComputeBudget[]{ComputeBudget.production(),ComputeBudget.expanded(),
                new ComputeBudget(10000,20000,500000,10000,77)}) {
            var cfg=SolverConfig.defaults();cfg.useBudget(budget);
            for(var mode:SolverConfig.ExecutionMode.values()) {
                cfg.executionMode=mode;new SolverContext(scene(),cfg);
                assertSame(budget,cfg.computeBudget);assertEquals(budget.candidateEvaluations,cfg.maxCandidateEvaluations);
            }
        }
    }
    @Test void legacyFieldOverridesAndIndependentCandidateSettingArePreserved() {
        var cfg=SolverConfig.defaults();var custom=new ComputeBudget(10000,20000,500000,10000,77);
        cfg.computeBudget=custom;cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        cfg.resolveExecutionBudget();assertSame(custom,cfg.computeBudget);
        var separate=SolverConfig.defaults();separate.maxCandidateEvaluations=42;
        separate.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;separate.resolveExecutionBudget();
        assertEquals(42,separate.maxCandidateEvaluations);
        assertEquals(10400000,separate.computeBudget.directedEdges);
    }
    @Test void defaultParallelAndGuardFallbackKeepExplicitX2OutputAndStrategyBudgets()throws Exception {
        var explicit=SolverConfig.defaults();explicit.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        explicit.useBudget(ComputeBudget.parallel2Default());var expectedInput=scene();
        var expected=new HeatnetSolver(explicit,new SolverVariantValidator(expectedInput),null,StrategyTestSupport::controlledMemory).solve(expectedInput);
        for(boolean denyAdmission:new boolean[]{false,true}) {
            var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
            if(denyAdmission)cfg.parallelWorkingSetBytes=1;
            var input=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));
            var calls=new AtomicInteger();
            var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,strategy)->{
                calls.incrementAndGet();
                profile(ctx.resources(ClearanceClass.SMALL).graph.budget(),260000,10400000,130000000,130000,1300);
                assertEquals(1300,ctx.config().maxCandidateEvaluations);
                return StrategyTestSupport.build(control,ctx,strategy);
            },StrategyTestSupport::controlledMemory).solve(input);
            assertEquals(5,calls.get());assertEquals(2,result.variants().get(0).connectedCount());
            assertEquals(denyAdmission?"MEMORY_ADMISSION":"NONE",result.metrics().get("parallel_fallback"));
            assertEquals(geo(expected),geo(result));
            assertEquals(StrategyTestSupport.JSON.writeValueAsString(expected.variants().get(0).trace().decisions()),
                    StrategyTestSupport.JSON.writeValueAsString(result.variants().get(0).trace().decisions()));
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()
                    &&(t.getName().equals("heatnet-strategy")||t.getName().equals("heatnet-memory-guard"))));
        }
    }
}
