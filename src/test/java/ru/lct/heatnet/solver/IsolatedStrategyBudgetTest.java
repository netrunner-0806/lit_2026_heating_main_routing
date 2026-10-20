package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.routing.*;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedStrategyBudgetTest extends ParallelStrategyStateTest {
    SolverConfig smallConfig() {
        var cfg=SolverConfig.defaults();cfg.useBudget(new ComputeBudget(10000,20000,500000,10000,1000));
        cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;return cfg;
    }
    @Test void forkBudgetDoesNotCompeteAndIncludesPreparation()throws Exception {
        var base=new SolverContext(scene(),smallConfig());var a=base.experimentalFork().resources(ClearanceClass.SMALL).graph;
        var b=base.experimentalFork().resources(ClearanceClass.SMALL).graph;var original=base.resources(ClearanceClass.SMALL).graph;
        long prepared=original.visibilityCheckCount(),cap=a.budget().visibilityChecks;
        assertTrue(prepared>0);var pool=Executors.newFixedThreadPool(2);
        try {var x=pool.submit(()->{a.reserveFallbackChecks(cap-prepared);assertThrows(ComplexityLimitException.class,()->a.reserveFallbackChecks(1));});
            var y=pool.submit(()->{b.reserveFallbackChecks(cap-prepared);assertThrows(ComplexityLimitException.class,()->b.reserveFallbackChecks(1));});
            x.get();y.get();assertEquals(prepared,original.visibilityCheckCount());assertEquals(cap,a.visibilityCheckCount());assertEquals(cap,b.visibilityCheckCount());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void localFailureReplaysOnceAfterWorkersExitAndRecoversOriginalOutput()throws Exception {
        var expectedInput=scene();var expected=new HeatnetSolver(SolverConfig.defaults(),new SolverVariantValidator(expectedInput)).solve(expectedInput);
        var cfg=smallConfig();var model=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(model));
        var parallelCalls=new AtomicInteger();var sequentialCalls=new AtomicInteger();var active=new AtomicInteger();
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(model),(ctx,s)->{
            if(Thread.currentThread().getName().equals("heatnet-strategy")) {
                active.incrementAndGet();parallelCalls.incrementAndGet();
                try {throw new ComplexityLimitException("injected simultaneous local limit");}finally{active.decrementAndGet();}
            }
            assertEquals(0,active.get());assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
            sequentialCalls.incrementAndGet();return StrategyTestSupport.build(control,ctx,s);
        },StrategyTestSupport::controlledMemory).solve(model);
        assertEquals(5,parallelCalls.get(),result.metrics().toString());assertEquals(5,sequentialCalls.get());assertEquals("LOCAL_COMPLEXITY_LIMIT",result.metrics().get("parallel_fallback"));
        assertEquals(geo(expected),geo(result));
    }
    @Test void memoryGuardChoosesOriginalPathBeforeLaunchingWorkers()throws Exception {
        var cfg=smallConfig();cfg.parallelWorkingSetBytes=1;var input=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
            assertNotEquals("heatnet-strategy",Thread.currentThread().getName());return StrategyTestSupport.build(control,ctx,s);
        },StrategyTestSupport::controlledMemory).solve(input);
        assertEquals("MEMORY_ADMISSION",result.metrics().get("parallel_fallback"));assertEquals(2,result.variants().get(0).connectedCount());
    }
    @Test void lateStrategiesSurviveEarlierOrdinaryFailuresAndAtMostTwoAreActive() {
        var cfg=smallConfig();var input=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));var active=new AtomicInteger();var peak=new AtomicInteger();
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try {if(s.name().equals("joint-nearest-first")||s.name().equals("joint-farthest-first"))throw new IllegalArgumentException("injected strategy failure");return StrategyTestSupport.build(control,ctx,s);}
            finally{active.decrementAndGet();}
        },StrategyTestSupport::controlledMemory).solve(input);
        assertEquals(0,active.get());assertTrue(peak.get()<=2);assertEquals(2,result.variants().get(0).connectedCount());assertEquals("NONE",result.metrics().get("parallel_fallback"));
        assertEquals(2,result.diagnostics().errors().size());
    }
    @Test void cancelledCalculationDoesNotPoisonNextJob() {
        var cancelled=smallConfig();cancelled.cancellation.cancel();assertThrows(CancellationException.class,()->new HeatnetSolver(cancelled,null).solve(scene()));
        var fresh=smallConfig();var input=scene();var result=new HeatnetSolver(fresh,new SolverVariantValidator(input),null,StrategyTestSupport::controlledMemory).solve(input);
        assertEquals(2,result.variants().get(0).connectedCount());assertEquals("NONE",result.metrics().get("parallel_fallback"));
    }
    @Test void preparationFailureUsesCleanSequentialRecovery()throws Exception {
        var raw=scene();var cfg=smallConfig();
        var point=new org.locationtech.jts.geom.Point(new org.locationtech.jts.geom.impl.CoordinateArraySequence(new org.locationtech.jts.geom.Coordinate[]{ru.lct.heatnet.SyntheticInput.c(0,0)}),ru.lct.heatnet.geo.GeometryUtils.GF) {
            boolean first=true;
            @Override public org.locationtech.jts.geom.Envelope getEnvelopeInternal() {
                if(first){first=false;throw new IllegalStateException("injected preparation failure");}
                return super.getEnvelopeInternal();
            }
        };
        var source=new ru.lct.heatnet.domain.SourcePoint(ru.lct.heatnet.domain.JsonId.ofString("source"),point,new double[]{37,55},999);
        var input=new ru.lct.heatnet.domain.InputModel(java.util.Collections.singletonList(source),raw.networkLines(),raw.chambers(),raw.connectionPoints(),raw.restrictions(),raw.diagnostics(),raw.featureCount()+1);
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),null,StrategyTestSupport::controlledMemory).solve(input);
        assertEquals("PARALLEL_PREPARATION_FAILURE",result.metrics().get("parallel_fallback"));assertEquals(2,result.variants().get(0).connectedCount());
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
        var expectedInput=scene();var expected=new HeatnetSolver(SolverConfig.defaults(),new SolverVariantValidator(expectedInput)).solve(expectedInput);
        assertEquals(geo(expected),geo(result));
    }

}
