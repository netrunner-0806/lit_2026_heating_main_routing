package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.routing.*;
import ru.lct.heatnet.restrictions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ParallelMemoryGuardTest {
    @Test void admissionUsesPreparedGraphAndIgnoresUnusedCaps() {
        List<Long> forecasts=new ArrayList<>();
        for(int factor=1;factor<=3;factor++){
            var input=new SyntheticInput().line("net",300,0,0,100,0).point("cp",30,30,1).build();
            var cfg=SolverConfig.defaults();cfg.useBudget(BudgetTestProfiles.scale(ComputeBudget.expanded(),factor,factor,factor));
            try(var guard=new ParallelMemoryGuard(input,cfg,()->100L<<20,()->-1,false)){
                var base=new SolverContext(input,cfg);base.separateDistances();guard.admit(base);
                var decisions=(List<?>)guard.metrics().get("decisions");var event=(Map<?,?>)decisions.get(0);
                assertEquals("ADMIT",event.get("decision"));assertTrue(((Number)event.get("directed_edges")).longValue()>0);
                assertEquals(0,event.get("active_strategies"));forecasts.add((Long)event.get("forecast_bytes"));
                base.releaseExperimentalResources();
            }
        }
        assertEquals(1,new HashSet<>(forecasts).size());
    }
    @Test void criticalPressureStopsWorkersAndRecoveryWaitsForExit()throws Exception {
        var input=new SyntheticInput().build();var cfg=SolverConfig.defaults();var heap=new AtomicLong(100L<<20);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(var guard=new ParallelMemoryGuard(input,cfg,heap::get,()->-1,false)){
            CountDownLatch started=new CountDownLatch(2);
            Callable<Void> work=()->{guard.started();started.countDown();try{
                assertThrows(ParallelMemoryGuard.Pressure.class,()->{while(true){cfg.cancellation.check();Thread.yield();}});
            }finally{guard.finished();}return null;};
            Future<Void>a=pool.submit(work),b=pool.submit(work);assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,guard::prepareFallback);
            heap.set(ParallelMemoryGuard.criticalHeap(cfg)+1);guard.sample();a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);
            assertFalse(cfg.cancellation.isCancelled());assertTrue(guard.pressured());
            assertThrows(ParallelMemoryGuard.Pressure.class,guard::prepareFallback);
            heap.set(100L<<20);guard.prepareFallback();cfg.cancellation.check();assertFalse(guard.pressured());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void rssLimitAndGraphGrowthAreIndependentOfHeapSample() {
        var input=new SyntheticInput().build();var cfg=SolverConfig.defaults();
        try(var guard=new ParallelMemoryGuard(input,cfg,()->1,()->cfg.parallelCriticalRssBytes+1,false)){
            guard.sample();assertTrue(guard.pressured());
            assertTrue(assertThrows(ParallelMemoryGuard.Pressure.class,cfg.cancellation::check).getMessage().contains("CRITICAL_RSS"));
        }
        try(var guard=new ParallelMemoryGuard(input,cfg,()->1,()->-1,false)){
            cfg.cancellation.registerMemory(this,()->ParallelMemoryGuard.criticalHeap(cfg));
            guard.sample();assertTrue(guard.pressured());
            assertTrue(assertThrows(ParallelMemoryGuard.Pressure.class,cfg.cancellation::check).getMessage().contains("CRITICAL_GRAPH_GROWTH"));
        }
        cfg.cancellation.check();assertEquals(0,cfg.cancellation.estimatedGraphBytes());
    }
    @Test void basePrimitiveStorageIsNotChargedAgainToEachOverlay() {
        var cfg=SolverConfig.defaults();cfg.cancellation.trackMemory(true);
        var input=new SyntheticInput().line("net",300,0,0,100,0).build();
        var base=new SolverContext(input,cfg);var graph=base.resources(ClearanceClass.SMALL).graph;
        long owner=graph.estimatedOwnedBytes(),edges=graph.directedEdgeCount();
        var fork=graph.experimentalFork(new ObstacleSpace(input,ClearanceClass.SMALL));
        assertTrue(edges>0);assertEquals(owner-48L*edges,fork.estimatedOwnedBytes());
        assertEquals(owner+fork.estimatedOwnedBytes(),cfg.cancellation.estimatedGraphBytes());
        fork.releaseMemoryTracking();base.releaseExperimentalResources();assertEquals(0,cfg.cancellation.estimatedGraphBytes());
    }
    @Test void monitorTerminatesAndDoesNotClearUserCancellation() {
        var input=new SyntheticInput().build();var cfg=SolverConfig.defaults();
        try(var guard=new ParallelMemoryGuard(input,cfg)){
            cfg.cancellation.cancel();
        }
        assertThrows(CancellationException.class,cfg.cancellation::check);
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-memory-guard")));
    }
    @Test void stillUnsafeJobFailsAsComplexityLimitWithoutPublishingPartialResult() {
        var input=new SyntheticInput().line("net",300,0,0,100,0).point("cp",30,30,1).build();
        var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        cfg.parallelCriticalHeapBytes=1;
        assertThrows(ComplexityLimitException.class,()->new HeatnetSolver(cfg,
                new ru.lct.heatnet.validation.SolverVariantValidator(input)).solve(input));
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()
                &&(t.getName().equals("heatnet-strategy")||t.getName().equals("heatnet-memory-guard"))));
        var fresh=SolverConfig.defaults();fresh.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var next=new SyntheticInput().line("net",300,0,0,100,0).point("cp",30,30,1).build();
        assertEquals(1,new HeatnetSolver(fresh,new ru.lct.heatnet.validation.SolverVariantValidator(next),null,StrategyTestSupport::controlledMemory)
                .solve(next).variants().get(0).connectedCount());
    }
    @Test void failedGraphConstructionDoesNotRemainRetainedByAccounting() {
        var cfg=SolverConfig.defaults();cfg.useBudget(new ComputeBudget(10000,1,500000,10000,1000));
        cfg.cancellation.trackMemory(true);
        var input=new SyntheticInput().line("net",300,0,0,100,0).build();
        var base=new SolverContext(input,cfg);
        assertThrows(ComplexityLimitException.class,()->base.resources(ClearanceClass.SMALL));
        assertEquals(0,cfg.cancellation.estimatedGraphBytes());
        base.releaseExperimentalResources();
    }
    @Test void recoveryWaitsBrieflyForMeasuredReclamationRatherThanAssumingReferencesAreMemory()throws Exception {
        var cfg=SolverConfig.defaults();var heap=new AtomicLong(ParallelMemoryGuard.criticalHeap(cfg)+1);
        try(var guard=new ParallelMemoryGuard(new SyntheticInput().build(),cfg,heap::get,()->-1,false)){
            guard.sample();
            Thread reclaim=new Thread(()->{try{Thread.sleep(60);}catch(InterruptedException ex){throw new AssertionError(ex);}heap.set(64L<<20);});
            reclaim.start();
            try {guard.prepareFallback();cfg.cancellation.check();assertFalse(guard.pressured());}
            finally {reclaim.join();}
            var events=(List<?>)guard.metrics().get("decisions");
            assertEquals("RECOVERY_ADMIT",((Map<?,?>)events.get(events.size()-1)).get("decision"));
        }
    }
}
