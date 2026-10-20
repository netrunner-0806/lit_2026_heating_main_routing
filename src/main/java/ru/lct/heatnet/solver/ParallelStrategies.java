package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.routing.ComputeCancellation;
import ru.lct.heatnet.routing.ComplexityLimitException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Two private graph overlays over a frozen base. A bounded fixed queue limits live state; merge is ordinal. */
final class ParallelStrategies implements AutoCloseable {
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> new Thread(r, "heatnet-strategy"));
    private final List<Future<Outcome>> pending = new ArrayList<>();
    private final SolverContext base;
    private final ComputeCancellation cancellation;
    private final BiFunction<SolverContext, Strategy, VariantResult> runner;
    private final List<Strategy> strategies;
    private final List<Outcome> outcomes = new ArrayList<>();
    long preparationMs;
    private ParallelMemoryGuard memory;

    static final class Outcome {
        VariantResult result;
        RuntimeException failure;
        Map<String,Object> metrics;
        VariantResult get() { if (failure != null) throw failure; return result; }
    }

    ParallelStrategies(SolverContext base, List<Strategy> strategies, BiFunction<SolverContext,Strategy,VariantResult> runner) {
        this.base=base;this.strategies=strategies;this.runner=runner;this.cancellation=base.config().cancellation;
    }

    /** Pre-preparation estimate for diagnostics; actual admission follows common graph preparation. */
    static long workingSetEstimate(InputModel input, SolverConfig cfg) {
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                + ParallelMemoryGuard.additionalBytes(input,cfg,0,0);
    }
    static boolean admitted(InputModel input, SolverConfig cfg) {
        return workingSetEstimate(input,cfg)<=ParallelMemoryGuard.softLimit(cfg);
    }

    ParallelStrategies withMemoryGuard(ParallelMemoryGuard guard) { memory=guard;return this; }

    List<Outcome> run() {
        long start=System.nanoTime();warm(base.input());base.separateDistances();
        preparationMs=(System.nanoTime()-start)/1_000_000;
        if(memory!=null)memory.admit(base);
        // Start the historically longest independent strategy first; output order never follows completion.
        // Only two tasks can own contexts. Queued tasks capture no graph; each worker releases its graph
        // before accepting the next task. The order is fixed, never selected using measured job timings.
        List<Integer> order=new ArrayList<>();
        for(int i=0;i<strategies.size();i++)order.add(i);
        order.sort(Comparator.comparingInt(i->strategies.get(i).name().equals("separate-connections")?0:1));
        for(int i=0;i<strategies.size();i++)pending.add(null);
        for(int ordinal:order) {
            Strategy strategy=strategies.get(ordinal);
            pending.set(ordinal,executor.submit(()->{
                SolverContext local=null;
                if(memory!=null)memory.started();
                try(ComputeCancellation.Scope ignored=cancellation.bind()) {
                    cancellation.check();local=base.experimentalFork();
                    Outcome out=new Outcome();
                    try {out.result=runner.apply(local,strategy);}
                    catch(CancellationException ex) {throw ex;}
                    catch(RuntimeException ex) {out.failure=ex;}
                    out.metrics=local.graphStats();return out;
                } finally {if(local!=null)local.releaseExperimentalResources();if(memory!=null)memory.finished();}
            }));
        }
        for(Future<Outcome> future:pending) outcomes.add(await(future));
        return outcomes;
    }
    boolean needsRecovery() {
        return outcomes.stream().anyMatch(o->o.failure instanceof ComplexityLimitException
                || o.result!=null && o.result.unconnected().values().stream().anyMatch(u->u.startsWith("COMPLEXITY_LIMIT")));
    }
    private Outcome await(Future<Outcome> future) {
        try { return future.get(); }
        catch(InterruptedException ex) {cancel();Thread.currentThread().interrupt();throw new CancellationException("Solver interrupted");}
        catch(ExecutionException ex) {
            Throwable cause=ex.getCause();
            if(cause instanceof Error) {cancel();throw (Error)cause;}
            if(cause instanceof ParallelMemoryGuard.Pressure)throw (ParallelMemoryGuard.Pressure)cause;
            if(cause instanceof CancellationException) {cancel();throw (CancellationException)cause;}
            throw new IllegalStateException("Strategy task failed outside isolation",cause);
        }
    }
    void cancel() {cancellation.cancel();pending.forEach(f->{if(f!=null)f.cancel(true);});executor.shutdownNow();}
    public void close() {
        executor.shutdown();boolean interrupted=Thread.interrupted();
        if(interrupted||cancellation.isCancelled())cancel();
        while(!executor.isTerminated())try {executor.awaitTermination(100,TimeUnit.MILLISECONDS);}
        catch(InterruptedException ex){interrupted=true;cancel();}
        pending.clear();if(interrupted)Thread.currentThread().interrupt();
    }
    private static void warm(InputModel m) {
        m.inputIds();m.sources().forEach(x->warmGeometry(x.geometry()));m.chambers().forEach(x->warmGeometry(x.geometry()));
        m.networkLines().forEach(x->warmGeometry(x.geometry()));m.connectionPoints().forEach(x->warmGeometry(x.geometry()));
        m.restrictions().forEach(x->{warmGeometry(x.geometry());warmGeometry(x.originalGeometry());});
    }
    private static void warmGeometry(Geometry g) {
        g.getEnvelopeInternal();
        if(g instanceof Polygon) {Polygon p=(Polygon)g;warmGeometry(p.getExteriorRing());for(int i=0;i<p.getNumInteriorRing();i++)warmGeometry(p.getInteriorRingN(i));}
        else if(g instanceof org.locationtech.jts.geom.GeometryCollection)for(int i=0;i<g.getNumGeometries();i++)warmGeometry(g.getGeometryN(i));
    }
}
