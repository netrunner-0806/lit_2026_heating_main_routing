package ru.lct.heatnet.solver;

import java.util.*;
import java.util.function.BiFunction;

/** Test adapter for exercising lifecycle independently of heap admission. */
final class ParallelExperiment implements AutoCloseable {
    private final BiFunction<SolverContext,Strategy,VariantResult> runner;
    private ParallelStrategies execution;
    private List<ParallelStrategies.Outcome> outcomes;
    private int next;
    private ru.lct.heatnet.routing.ComputeCancellation cancellation;
    long preparationMs;
    ParallelExperiment(BiFunction<SolverContext,Strategy,VariantResult> runner) {this.runner=runner;}
    VariantResult apply(SolverContext ctx,Strategy strategy) {
        if(execution==null) {
            cancellation=ctx.config().cancellation;
            execution=new ParallelStrategies(ctx,HeatnetSolver.defaultStrategies(),runner);
            outcomes=execution.run();preparationMs=execution.preparationMs;
        }
        if(next<outcomes.size())return outcomes.get(next++).get();
        return runner.apply(ctx,strategy);
    }
    void requireAdmissible() { if(cancellation!=null)cancellation.check(); }
    public void close() {if(execution!=null)execution.close();}
}
