package ru.lct.heatnet.routing;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One token per calculation. Never reused for a subsequent job. */
public final class ComputeCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile RuntimeException resourceStop;
    private volatile boolean trackMemory;
    private final java.util.concurrent.ConcurrentHashMap<Object,java.util.function.LongSupplier> memory =
            new java.util.concurrent.ConcurrentHashMap<>();
    public void trackMemory(boolean enabled) { trackMemory=enabled;if(!enabled)memory.clear(); }
    public void registerMemory(Object owner, java.util.function.LongSupplier estimate) { if(trackMemory)memory.put(owner,estimate); }
    public void releaseMemory(Object owner) { memory.remove(owner); }
    public long estimatedGraphBytes() { return memory.values().stream().mapToLong(java.util.function.LongSupplier::getAsLong).sum(); }
    public void resourceStop(RuntimeException reason) { resourceStop=reason; }
    /** Only the coordinator may clear this after every worker has exited. User cancellation is never cleared. */
    public void clearResourceStop() { resourceStop=null; }
    private static final ThreadLocal<ComputeCancellation> CURRENT = new ThreadLocal<>();
    public static ComputeCancellation currentOrNew() { ComputeCancellation token=CURRENT.get();return token==null?new ComputeCancellation():token; }
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void check() {
        if (Thread.currentThread().isInterrupted()) cancel();
        if (cancelled.get()) throw new CancellationException("Solver calculation cancelled");
        RuntimeException stop=resourceStop;if(stop!=null)throw stop;
    }
    public static void checkpoint() {
        ComputeCancellation token=CURRENT.get();
        if (token!=null) token.check();
        else if (Thread.currentThread().isInterrupted()) throw new CancellationException("Solver thread interrupted");
    }
    public Scope bind() { ComputeCancellation previous=CURRENT.get();CURRENT.set(this);return new Scope(previous); }
    public static final class Scope implements AutoCloseable {
        private final ComputeCancellation previous;
        private Scope(ComputeCancellation previous) { this.previous=previous; }
        public void close() { if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
    }
}
