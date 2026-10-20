package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SolverProgressTest extends ParallelStrategyStateTest {
    @Test void liveParallelProgressPrecedesJoinAndTracksBothWorkers() throws Exception {
        var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var events=new CopyOnWriteArrayList<String>();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(()->new HeatnetSolver(cfg,null,(ctx,s)->{
                entered.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();cfg.cancellation.check();}
                return null;
            },StrategyTestSupport::controlledMemory).solve(scene(),HeatnetSolver.defaultStrategies(),events::add));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertTrue(events.stream().anyMatch(e->e.contains("active=joint-nearest-first,separate-connections")),events.toString());
            release.countDown();result.get(20,TimeUnit.SECONDS);
            int previous=0;
            for(String e:events){int count=Integer.parseInt(e.split("completed=")[1].split(";")[0]);assertTrue(count>=previous);previous=count;assertTrue(e.length()<512);}
            assertEquals(5,previous);assertTrue(events.get(events.size()-1).startsWith("Validating"));
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void recoveryAndValidationDoNotRegressFinishedCountOrClaimDone() {
        var events=new ArrayList<String>();var strategies=HeatnetSolver.defaultStrategies();var p=new SolverProgress(SolverConfig.defaults(),strategies,events::add);
        p.phase("Building routing graph");p.start(strategies.get(0));p.finish(strategies.get(0),false);p.recovery();p.start(strategies.get(0));p.finish(strategies.get(0),true);
        p.phase("Validating and ranking");p.start(strategies.get(0));p.finish(strategies.get(0),true);p.validated(1);
        String last=events.get(events.size()-1);assertTrue(last.startsWith("Validating"));assertTrue(last.contains("completed=1;"));assertTrue(last.contains("formed=1; validated=1; recovery=true"));
        assertFalse(events.stream().anyMatch(x->x.contains("100%")||x.contains("Done")));
    }
    @Test void observerFailureDoesNotBreakRoutingAndJobsHaveSeparateState() {
        var s=HeatnetSolver.defaultStrategies();var cfg=SolverConfig.defaults();var a=new SolverProgress(cfg,s,x->{throw new IllegalStateException("observer");});
        assertDoesNotThrow(()->{a.start(s.get(0));a.finish(s.get(0),true);});
        var output=new ArrayList<String>();new SolverProgress(cfg,s,output::add).phase("Building routing graph");assertTrue(output.get(0).contains("completed=0;"));
    }
}
