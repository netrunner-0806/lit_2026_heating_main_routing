package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.routing.*;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelLifecycleTest extends ParallelStrategyStateTest {
    private static ru.lct.heatnet.domain.InputModel routingScene() {
        var input = new ru.lct.heatnet.SyntheticInput().line("main", 400, 0, 0, 1000, 0);
        for (int i = 0; i < 50; i++) input.point("consumer-" + i, 20 + (i % 10) * 90, 30 + (i / 10) * 40, 2);
        return input.build();
    }
    String diagnostics(SolveResult r)throws Exception {
        var messages=new ArrayList<>();for(var m:r.diagnostics().messages())if(!m.getCode().equals("SOLVE_SUMMARY"))messages.add(m);
        return StrategyTestSupport.JSON.writeValueAsString(messages);
    }
    @Test void localBudgetsAreDeterministicWithOppositeArrivalOrders()throws Exception {
        for(long cap:new long[]{1,300,1000,3000,10000}) {
            String expected=null,expectedDiagnostics=null;
            for(int repeat=0;repeat<3;repeat++) {
                var cfg=SolverConfig.defaults();cfg.useBudget(new ComputeBudget(10000,20000,cap,10000,1000));
                cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
                var model=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(model));
                final int delay=repeat;
                var actual=new HeatnetSolver(cfg,new SolverVariantValidator(model),(ctx,strategy)->{
                    try {Thread.sleep((HeatnetSolver.defaultStrategies().stream().map(Strategy::name).collect(java.util.stream.Collectors.toList()).indexOf(strategy.name())+delay)%2*5);}
                    catch(InterruptedException ex){Thread.currentThread().interrupt();cfg.cancellation.check();}
                    return StrategyTestSupport.build(control,ctx,strategy);
                },StrategyTestSupport::controlledMemory).solve(model);
                if(expected==null){expected=geo(actual);expectedDiagnostics=diagnostics(actual);}
                else {assertEquals(expected,geo(actual));assertEquals(expectedDiagnostics,diagnostics(actual));}
            }
        }
    }
    @Test void tokenCancellationJoinsRunningAndWaitingWorkers()throws Exception {
        var cfg=SolverConfig.defaults();CountDownLatch entered=new CountDownLatch(1);var controller=Executors.newSingleThreadExecutor();
        try {Future<?> job=controller.submit(()->{var model=scene();try(var p=new ParallelExperiment((ctx,s)->{entered.countDown();while(true)cfg.cancellation.check();})) {
            assertThrows(CancellationException.class,()->new HeatnetSolver(cfg,null,p::apply).solve(model));
        }});assertTrue(entered.await(10,TimeUnit.SECONDS));long start=System.nanoTime();cfg.cancellation.cancel();job.get(5,TimeUnit.SECONDS);
            System.out.println("TOKEN_CANCEL_JOIN_MS="+(System.nanoTime()-start)/1_000_000);
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
        }finally{controller.shutdownNow();assertTrue(controller.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void interruptCancellationJoinsWorkersBeforeReturning()throws Exception {
        var cfg=SolverConfig.defaults();CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread job=new Thread(()->{try {var model=scene();try(var p=new ParallelExperiment((ctx,s)->{entered.countDown();while(true)cfg.cancellation.check();})) {
            assertThrows(CancellationException.class,()->new HeatnetSolver(cfg,null,p::apply).solve(model));
        }assertTrue(Thread.currentThread().isInterrupted());}catch(Throwable ex){failure.set(ex);}finally{finished.countDown();}},"cancel-controller");
        job.start();assertTrue(entered.await(10,TimeUnit.SECONDS));long start=System.nanoTime();job.interrupt();assertTrue(finished.await(5,TimeUnit.SECONDS));job.join();
        System.out.println("INTERRUPT_CANCEL_JOIN_MS="+(System.nanoTime()-start)/1_000_000);assertNull(failure.get());
    }
    @Test void adaptiveFallbackSeesCancelledToken() {
        var cfg=SolverConfig.defaults();var ctx=new SolverContext(scene(),cfg);var graph=ctx.resources(ru.lct.heatnet.restrictions.ClearanceClass.SMALL).graph;cfg.cancellation.cancel();
        assertThrows(CancellationException.class,()->AdaptiveRouting.expandGrid(graph,20,100));
    }
    @Test void actualRoutingCancellationQuiescesAllWorkers()throws Exception {
        for(boolean depth:new boolean[]{false,true}) {
            var cfg=SolverConfig.defaults();cfg.depthMode=depth;cfg.useBudget(ComputeBudget.expanded());
            var input=routingScene();
            var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));var entered=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
            try {
                var job=pool.submit(()->{try(var p=new ParallelExperiment((ctx,strategy)->{entered.countDown();return StrategyTestSupport.build(control,ctx,strategy);})) {
                    assertThrows(CancellationException.class,()->new HeatnetSolver(cfg,new SolverVariantValidator(input),p::apply).solve(input));
                }});
                assertTrue(entered.await(20,TimeUnit.SECONDS));Thread.sleep(50);long start=System.nanoTime();cfg.cancellation.cancel();job.get(5,TimeUnit.SECONDS);
                System.out.println("REAL_ROUTING_CANCEL_JOIN_MS mode="+depth+" "+(System.nanoTime()-start)/1_000_000);
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test void shutdownWaitsForUninterruptibleOperationBeforeReturning()throws Exception {
        var cfg=SolverConfig.defaults();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var job=pool.submit(()->{try(var p=new ParallelExperiment((ctx,strategy)->{
                entered.countDown();boolean interrupted=false;
                while(release.getCount()>0)try{release.await();}catch(InterruptedException ex){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();cfg.cancellation.check();throw new AssertionError("must cancel");
            })) {assertThrows(CancellationException.class,()->new HeatnetSolver(cfg,null,p::apply).solve(scene()));}finally{finished.countDown();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS));cfg.cancellation.cancel();assertFalse(finished.await(100,TimeUnit.MILLISECONDS));release.countDown();job.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void largerClearanceClassKeepsOrderedResourcePreparation()throws Exception {
        java.util.function.Supplier<ru.lct.heatnet.domain.InputModel> scene=()->new ru.lct.heatnet.SyntheticInput()
                .line("net",1400,0,0,200,0).point("a",50,150,6000).point("b",150,150,6000).build();
        var cfg=SolverConfig.defaults();cfg.useBudget(ComputeBudget.expanded());var input=scene.get();
        var expected=new HeatnetSolver(cfg,new SolverVariantValidator(input)).solve(input);
        assertFalse(expected.variants().isEmpty());assertEquals(2,expected.variants().get(0).connectedCount());
        assertTrue(java.util.stream.StreamSupport.stream(expected.variants().get(0).network().links().spliterator(),false).anyMatch(l->l.du()>=900));
        var other=scene.get();var control=new HeatnetSolver(cfg,new SolverVariantValidator(other));
        try(var p=new ParallelExperiment((ctx,strategy)->StrategyTestSupport.build(control,ctx,strategy))) {
            var actual=new HeatnetSolver(cfg,new SolverVariantValidator(other),p::apply).solve(other);
            assertEquals(geo(expected),geo(actual));assertEquals(diagnostics(expected),diagnostics(actual));
        }
    }
    @Test void exactClearanceRetryRunsAfterAllWorkersFinish()throws Exception {
        double angle=Math.toRadians(195),x=1.65*Math.cos(angle),y=1.65*Math.sin(angle),tx=-Math.sin(angle),ty=Math.cos(angle);
        double cx=x-30*tx,cy=y-30*ty,rx=x+30*tx,ry=y+30*ty;
        java.util.function.Supplier<ru.lct.heatnet.domain.InputModel> scene=()->new ru.lct.heatnet.SyntheticInput()
                .line("net",400,rx,ry,rx,ry-1).chamber("ch",rx,ry).point("cp",cx,cy,900).rect("park","park",0,0,10,10).build();
        var cfg=SolverConfig.defaults();cfg.useBudget(ComputeBudget.expanded());var input=scene.get();
        var expected=new HeatnetSolver(cfg,new SolverVariantValidator(input)).solve(input);
        assertTrue(expected.diagnostics().messages().stream().anyMatch(m->m.getCode().equals("EXACT_CLEARANCE_RETRY")));
        var other=scene.get();var control=new HeatnetSolver(cfg,new SolverVariantValidator(other));
        try(var p=new ParallelExperiment((ctx,strategy)->StrategyTestSupport.build(control,ctx,strategy))) {
            var actual=new HeatnetSolver(cfg,new SolverVariantValidator(other),p::apply).solve(other);
            assertEquals(geo(expected),geo(actual));assertEquals(diagnostics(expected),diagnostics(actual));
        }
    }

    @Test void cancellationDuringActualDijkstraAndDepthJoinsWorkers()throws Exception {
        for(String phase:new String[]{"ru.lct.heatnet.routing.PathSearch","ru.lct.heatnet.depth.NetworkDepthPlanner"}) {
            var cfg=SolverConfig.defaults();cfg.depthMode=phase.contains("Depth");
            var input=routingScene();
            var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));var controller=Executors.newSingleThreadExecutor();
            try {
                var job=controller.submit(()->{try(var execution=new ParallelExperiment((ctx,strategy)->StrategyTestSupport.build(control,ctx,strategy))) {
                    assertThrows(CancellationException.class,()->new HeatnetSolver(cfg,new SolverVariantValidator(input),execution::apply).solve(input));
                }});
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean observed=false;
                while(System.nanoTime()<deadline && !observed && !job.isDone()) {
                    observed=Thread.getAllStackTraces().entrySet().stream().anyMatch(e->e.getKey().getName().equals("heatnet-strategy") && java.util.Arrays.stream(e.getValue()).anyMatch(f->f.getClassName().equals(phase)));
                    if(!observed)Thread.sleep(1);
                }
                assertTrue(observed,"must cancel during the actual "+phase+" computation");
                long start=System.nanoTime();cfg.cancellation.cancel();job.get(5,TimeUnit.SECONDS);
                System.out.println("PHASE_CANCEL_JOIN_MS "+phase+" "+(System.nanoTime()-start)/1_000_000);
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
            }finally{cfg.cancellation.cancel();controller.shutdownNow();assertTrue(controller.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

}
