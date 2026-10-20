package ru.lct.heatnet.solver;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;

/** Shared regression access to one strategy, without the benchmark harness. */
final class StrategyTestSupport {
    static ParallelMemoryGuard controlledMemory(ru.lct.heatnet.domain.InputModel input, SolverConfig cfg) {
        // Keep all guard logic, graph accounting and its monitor; only external memory samples are controlled.
        return new ParallelMemoryGuard(input, cfg, () -> 64L << 20, () -> -1L, true);
    }
    static final ObjectMapper JSON = new ObjectMapper();
    static final Method BUILD;
    static { try { BUILD=HeatnetSolver.class.getDeclaredMethod("buildWithClassUpgrades",SolverContext.class,Strategy.class); BUILD.setAccessible(true); }
        catch(Exception ex){throw new ExceptionInInitializerError(ex);} }
    static VariantResult build(HeatnetSolver solver, SolverContext context, Strategy strategy) {
        try{return (VariantResult) BUILD.invoke(solver,context,strategy);}
        catch(InvocationTargetException e) { Throwable t=e.getCause(); if(t instanceof Error)throw (Error)t;throw (RuntimeException)t; }
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
}
