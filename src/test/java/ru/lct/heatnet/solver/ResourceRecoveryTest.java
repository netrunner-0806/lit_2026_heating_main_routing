package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.routing.ComplexityLimitException;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.testkit.SolveKit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourceRecoveryTest {
    private ru.lct.heatnet.domain.InputModel scene() {
        return new SyntheticInput().line("net",300,0,0,100,0)
                .point("a",30,30,1).point("b",70,35,1).build();
    }
    @Test void localLimitMustNotDiscardOtherCompletedStrategiesOrRepeatIdenticalWork() {
        var input=scene();var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));var calls=new AtomicInteger();
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
            calls.incrementAndGet();
            if(s.name().equals("joint-nearest-first"))throw new ComplexityLimitException("deterministic local cap");
            assertEquals("heatnet-strategy",Thread.currentThread().getName(),"successful strategies must not be replayed");
            return StrategyTestSupport.build(control,ctx,s);
        },StrategyTestSupport::controlledMemory).solve(input);
        assertEquals(5,calls.get());assertFalse(result.variants().isEmpty());
        assertEquals(2,result.variants().get(0).connectedCount());
        assertTrue(new ResultValidator(input).validate(SolveKit.render(result)).isValid());
        assertEquals(1,result.diagnostics().errors().size());
    }
    @Test void ordinaryAttachmentLimitRetainsLastCommittedTopology() {
        var input=scene();var cfg=SolverConfig.defaults();cfg.improve=false;
        final ForestBuilder[] builder=new ForestBuilder[1];
        var ctx=new SolverContext(input,cfg);
        builder[0]=new ForestBuilder(ctx,HeatnetSolver.defaultStrategies().get(0),null,()->{
            if(builder[0].network().connectionNodes().size()==1) {
                var graph=ctx.resources(ru.lct.heatnet.restrictions.ClearanceClass.SMALL).graph;
                graph.reserveFallbackChecks(graph.budget().visibilityChecks-graph.visibilityCheckCount());
                graph.reserveFallbackChecks(1);
            }
        });
        var variant=builder[0].build();
        assertEquals(1,variant.connectedCount());assertEquals(1,variant.unconnected().size());
        assertTrue(variant.unconnected().values().iterator().next().startsWith("COMPLEXITY_LIMIT"));
        assertTrue(new SolverVariantValidator(input).validateIssues(variant).isEmpty());
        variant.setVariantId("v1");variant.setRank(1);
        assertTrue(ru.lct.heatnet.oracle.StressEngineeringOracle.check(input,
                new ru.lct.heatnet.output.OutputBuilder(input).build(variant),false).isEmpty());
    }
    @Test void parallelMetricsIncludePrivateCandidateWork() {
        var input=scene();var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),null,StrategyTestSupport::controlledMemory).solve(input);
        assertTrue(((Number)result.metrics().get("candidate_count")).longValue()>0);
        assertTrue(((Number)result.metrics().get("candidate_topology_copies")).longValue()>0);
    }
    @Test void failedImprovementRestoresBothTopologyAndExplanation() {
        var input=scene();var cfg=SolverConfig.defaults();var calls=new AtomicInteger();
        var builder=new ForestBuilder(new SolverContext(input,cfg),HeatnetSolver.defaultStrategies().get(0),null,()->{
            if(calls.incrementAndGet()==3)throw new ComplexityLimitException("improvement witness");
        });
        var variant=builder.build();
        assertEquals(2,variant.connectedCount());assertTrue(variant.unconnected().isEmpty());
        assertTrue(new SolverVariantValidator(input).validateIssues(variant).isEmpty());
        assertTrue(variant.notes().stream().anyMatch(s->s.contains("improvement witness")));
        assertTrue(variant.trace().decisions().stream().noneMatch(d->d.type.equals("ROUTE_REJECTED")));
    }
    @Test void recoveredMemoryAllowsCleanSequentialRetryWithoutPoisoningTheToken() {
        for(boolean recover:new boolean[]{true,false}) {
            var input=scene();var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
            var heap=new java.util.concurrent.atomic.AtomicLong(64L<<20);
            final ParallelMemoryGuard[] guard=new ParallelMemoryGuard[1];
            var sequential=new AtomicInteger();var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));
            var solver=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
                if(Thread.currentThread().getName().equals("heatnet-strategy")) {
                    heap.set(ParallelMemoryGuard.criticalHeap(cfg)+1);guard[0].sample();
                    try {cfg.cancellation.check();}
                    finally {if(recover)heap.set(64L<<20);}
                    fail("pressure must stop this attempt");
                }
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
                sequential.incrementAndGet();return StrategyTestSupport.build(control,ctx,s);
            },(m,c)->guard[0]=new ParallelMemoryGuard(m,c,heap::get,()->-1,false));
            if(recover) {
                var result=solver.solve(input);assertEquals(2,result.variants().get(0).connectedCount());
                assertEquals("MEMORY_PRESSURE",result.metrics().get("parallel_fallback"));
                assertEquals(5,sequential.get());cfg.cancellation.check();
            } else {
                assertThrows(ComplexityLimitException.class,()->solver.solve(input));assertEquals(0,sequential.get());
            }
            assertFalse(cfg.cancellation.isCancelled());
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("heatnet-strategy")));
        }
    }
    @Test void completedResultSurvivesPressureWhileOnlyInterruptedStrategiesRecover() {
        var input=scene();var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var heap=new java.util.concurrent.atomic.AtomicLong(64L<<20);
        final ParallelMemoryGuard[] guard=new ParallelMemoryGuard[1];
        var completed=new java.util.concurrent.CountDownLatch(1);
        var separateCalls=new AtomicInteger();var retries=new AtomicInteger();
        var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
            if(s.name().equals("separate-connections")) {
                separateCalls.incrementAndGet();
                var variant=StrategyTestSupport.build(control,ctx,s);completed.countDown();return variant;
            }
            if(Thread.currentThread().getName().equals("heatnet-strategy")) {
                try {assertTrue(completed.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException ex){throw new AssertionError(ex);}
                heap.set(ParallelMemoryGuard.criticalHeap(cfg)+1);guard[0].sample();
                try {cfg.cancellation.check();}finally{heap.set(64L<<20);}
            }
            retries.incrementAndGet();return StrategyTestSupport.build(control,ctx,s);
        },(m,c)->guard[0]=new ParallelMemoryGuard(m,c,heap::get,()->-1,false)).solve(input);
        assertEquals(1,separateCalls.get());assertEquals(4,retries.get());
        assertEquals(4,result.metrics().get("recovery_strategies"));
        assertEquals(5,result.metrics().get("variants_evaluated"));assertEquals(2,result.variants().get(0).connectedCount());
        assertTrue(new ResultValidator(input).validate(SolveKit.render(result)).isValid());
    }
    @Test void recoveredStrategiesDoNotSpendEachOthersVisibilityAllowance() {
        var input=scene();var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        var heap=new java.util.concurrent.atomic.AtomicLong(64L<<20);
        final ParallelMemoryGuard[] guard=new ParallelMemoryGuard[1];
        var retries=new AtomicInteger();var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
            if(Thread.currentThread().getName().equals("heatnet-strategy")) {
                heap.set(ParallelMemoryGuard.criticalHeap(cfg)+1);guard[0].sample();
                try {cfg.cancellation.check();}finally{heap.set(64L<<20);}
            }
            retries.incrementAndGet();
            var graph=ctx.resources(ru.lct.heatnet.restrictions.ClearanceClass.SMALL).graph;
            assertTrue(graph.visibilityCheckCount()<graph.budget().visibilityChecks,
                    "another recovered strategy must not exhaust this strategy's allowance");
            var variant=StrategyTestSupport.build(control,ctx,s);
            graph.reserveFallbackChecks(graph.budget().visibilityChecks-graph.visibilityCheckCount());
            return variant;
        },(m,c)->guard[0]=new ParallelMemoryGuard(m,c,heap::get,()->-1,false)).solve(input);
        assertEquals(5,retries.get());assertEquals(5,result.metrics().get("variants_evaluated"));
        assertTrue(result.diagnostics().errors().isEmpty());
        assertTrue(new ResultValidator(input).validate(SolveKit.render(result)).isValid());
    }
    @Test void limitBeforeFirstCommittedAttachmentIsNotPublishedAsASuccess() {
        var cfg=SolverConfig.defaults();
        var builder=new ForestBuilder(new SolverContext(scene(),cfg),HeatnetSolver.defaultStrategies().get(0),null,
                ()->{throw new ComplexityLimitException("no committed topology");});
        assertThrows(ComplexityLimitException.class,builder::build);
        assertTrue(builder.network().connectionNodes().isEmpty());
    }
    @Test void sharedPreparationLimitKeepsItsFailureMemoInsteadOfBuildingTheSameGraphAgain() {
        var input=scene();var cfg=SolverConfig.defaults();cfg.executionMode=SolverConfig.ExecutionMode.PARALLEL_2;
        cfg.useBudget(new ru.lct.heatnet.routing.ComputeBudget(1,1,1,1,1));
        var calls=new AtomicInteger();
        var failure=new java.util.concurrent.atomic.AtomicReference<ComplexityLimitException>();
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,s)->{
            calls.incrementAndGet();
            assertEquals(false,ctx.graphStats().get("operation_counts_complete"),
                    "reuse the context that already recorded this deterministic preparation failure");
            var ex=assertThrows(ComplexityLimitException.class,
                    ()->ctx.resources(ru.lct.heatnet.restrictions.ClearanceClass.SMALL));
            if(failure.get()==null)failure.set(ex);else assertSame(failure.get(),ex);
            throw ex;
        },StrategyTestSupport::controlledMemory).solve(input);
        assertEquals(5,calls.get());assertTrue(result.variants().isEmpty());
        assertEquals(5,result.diagnostics().errors().stream().filter(d->d.getCode().equals("COMPLEXITY_LIMIT")).count());
    }
}
