package ru.lct.heatnet.solver;

import ru.lct.heatnet.domain.InputModel;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Forecast from prepared data, plus an independent critical stop while any computation is running.
 * Estimates are conservative accounting, not an exact JVM object-size or OOM guarantee.
 */
final class ParallelMemoryGuard implements AutoCloseable {
    static final class Admission extends RuntimeException {
        Admission(String reason) { super(reason); }
    }
    static final class Pressure extends RuntimeException {
        Pressure(String reason) { super("Memory resource guard: "+reason); }
    }
    private final SolverConfig cfg;
    private final InputModel input;
    private final long geometryBytes;
    private final LongSupplier heap, rss;
    private final Thread monitor;
    private volatile Pressure pressure;
    private volatile long peakHeap, peakGraph;
    private volatile long peakRss=-1;
    private final AtomicInteger active=new AtomicInteger(), peakActive=new AtomicInteger();
    private final List<Map<String,Object>> decisions=Collections.synchronizedList(new ArrayList<>());

    ParallelMemoryGuard(InputModel input,SolverConfig cfg) {
        this(input,cfg,()->ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                ParallelMemoryGuard::linuxRss,true);
    }
    ParallelMemoryGuard(InputModel input,SolverConfig cfg,LongSupplier heap,LongSupplier rss,boolean start) {
        if(cfg.parallelWorkingSetBytes<1||cfg.parallelCriticalHeapBytes<1||cfg.parallelCriticalRssBytes<1
                ||cfg.parallelOverlayReserveBytes<1)throw new IllegalArgumentException("Memory guard thresholds must be positive");
        this.input=input;this.cfg=cfg;this.heap=heap;this.rss=rss;
        this.geometryBytes=geometryBytes(input);
        cfg.cancellation.trackMemory(true);
        monitor=new Thread(()->{
            while(!Thread.currentThread().isInterrupted()) {
                sample();
                try {Thread.sleep(50);}catch(InterruptedException ex){return;}
            }
        },"heatnet-memory-guard");
        monitor.setDaemon(true);
        if(start)monitor.start();
    }
    static long geometryBytes(InputModel input) {
        long points=0;
        for(var x:input.sources())points+=x.geometry().getNumPoints();
        for(var x:input.networkLines())points+=x.geometry().getNumPoints();
        for(var x:input.chambers())points+=x.geometry().getNumPoints();
        for(var x:input.connectionPoints())points+=x.geometry().getNumPoints();
        for(var x:input.restrictions())points+=x.geometry().getNumPoints()+x.originalGeometry().getNumPoints();
        return points*1024L;
    }
    static long transientReserve() {return Math.min(1L<<30,Runtime.getRuntime().maxMemory()/8);}
    static long softLimit(SolverConfig cfg) {
        return Math.min(cfg.parallelWorkingSetBytes,Math.min(Runtime.getRuntime().maxMemory()*60/100,criticalHeap(cfg)*8/10));
    }
    static long criticalHeap(SolverConfig cfg) {return Math.min(cfg.parallelCriticalHeapBytes,Runtime.getRuntime().maxMemory()*75/100);}
    /** Base and input are already in used heap. Reserve only private overlay growth/geometry plus scratch.
     * Four times the prepared edge population is a forecast, not a cap; the runtime guard covers growth beyond it.
     */
    static long additionalBytes(InputModel input,SolverConfig cfg,long nodes,long edges) {
        long forkRows=512L*nodes+32L*edges;
        long growth=Math.max(cfg.parallelOverlayReserveBytes,4L*80*edges+2L*512*nodes);
        return 2L*(forkRows+growth+geometryBytes(input))+transientReserve();
    }
    void admit(SolverContext base) {
        sample();cfg.cancellation.check();
        Map<String,Object> stats=base.graphStats();
        long nodes=((Number)stats.get("graph_nodes")).longValue(),edges=((Number)stats.get("graph_directed_edges")).longValue();
        long used=heap.getAsLong(),resident=rss.getAsLong(),extra=additionalBytes(input,cfg,nodes,edges);
        long projected=Math.max(used,cfg.cancellation.estimatedGraphBytes()+geometryBytes(input))+extra;
        String reason=projected>softLimit(cfg)?"FORECAST_HEAP":
                resident>=0 && resident+extra>cfg.parallelCriticalRssBytes-transientReserve()?"FORECAST_RSS":"ADMIT";
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("decision",reason);event.put("heap_bytes",used);event.put("rss_bytes",resident);
        event.put("additional_bytes",extra);event.put("forecast_bytes",projected);
        event.put("soft_limit_bytes",softLimit(cfg));event.put("nodes",nodes);event.put("directed_edges",edges);
        event.put("active_strategies",active.get());decisions.add(event);
        if(!reason.equals("ADMIT"))throw new Admission(reason);
    }
    synchronized void sample() {
        long used=heap.getAsLong(),resident=rss.getAsLong(),graphs=cfg.cancellation.estimatedGraphBytes();
        peakHeap=Math.max(peakHeap,used);peakRss=Math.max(peakRss,resident);peakGraph=Math.max(peakGraph,graphs);
        String reason=used>criticalHeap(cfg)?"CRITICAL_HEAP":
                graphs+geometryBytes+transientReserve()>criticalHeap(cfg)?"CRITICAL_GRAPH_GROWTH":
                resident>=0&&resident>cfg.parallelCriticalRssBytes?"CRITICAL_RSS":null;
        if(reason!=null&&pressure==null) {
            pressure=new Pressure(reason);
            Map<String,Object> event=new LinkedHashMap<>();event.put("decision",reason);
            event.put("heap_bytes",used);event.put("rss_bytes",resident);event.put("graph_bytes",graphs);
            event.put("active_strategies",active.get());decisions.add(event);
            cfg.cancellation.resourceStop(pressure);
        }
    }
    void started() {peakActive.accumulateAndGet(active.incrementAndGet(),Math::max);}
    void finished() {active.decrementAndGet();}
    boolean pressured() {return pressure!=null;}
    /** Called only after executor join and release of the discarded base. Bounded re-admission, no forced GC. */
    synchronized void prepareFallback() {
        if(active.get()!=0)throw new IllegalStateException("Fallback before worker termination");
        // This entire attempt is discarded, including graphs whose constructors failed.
        cfg.cancellation.trackMemory(false);cfg.cancellation.trackMemory(true);
        // Re-sample even after a soft admission refusal. Give natural GC a bounded opportunity to
        // reclaim discarded overlays, without forcing GC or equating dropped references with free memory.
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
        for (;;) {
            if(Thread.currentThread().isInterrupted()||cfg.cancellation.isCancelled()) {
                cfg.cancellation.cancel();cfg.cancellation.check();
            }
            pressure=null;cfg.cancellation.clearResourceStop();sample();
            if(pressure==null||System.nanoTime()>=deadline)break;
            try {Thread.sleep(25);} catch(InterruptedException ex) {
                Thread.currentThread().interrupt();cfg.cancellation.cancel();cfg.cancellation.check();
            }
        }
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("decision",pressure==null?"RECOVERY_ADMIT":"RECOVERY_DENIED");
        event.put("heap_bytes",heap.getAsLong());event.put("rss_bytes",rss.getAsLong());
        event.put("active_strategies",active.get());decisions.add(event);
        if(pressure!=null)throw pressure;
    }
    Map<String,Object> metrics() {
        Map<String,Object> m=new LinkedHashMap<>();
        synchronized(decisions){m.put("decisions",new ArrayList<>(decisions));}
        m.put("peak_heap_bytes",peakHeap);m.put("peak_rss_bytes",peakRss);
        m.put("peak_graph_estimate_bytes",peakGraph);m.put("peak_active_strategies",peakActive.get());
        m.put("critical_heap_bytes",criticalHeap(cfg));m.put("critical_rss_bytes",cfg.parallelCriticalRssBytes);
        return m;
    }
    public void close() {
        monitor.interrupt();boolean interrupted=false;
        while(monitor.isAlive())try{monitor.join();}catch(InterruptedException ex){interrupted=true;}
        cfg.cancellation.trackMemory(false);cfg.cancellation.clearResourceStop();
        if(interrupted)Thread.currentThread().interrupt();
    }
    private static long linuxRss() {
        Path status=Path.of("/proc/self/status");
        if(!Files.isReadable(status))return -1;
        try {
            for(String line:Files.readAllLines(status))if(line.startsWith("VmRSS:"))
                return Long.parseLong(line.substring(6).trim().split("\\s+")[0])*1024;
        }catch(java.io.IOException|NumberFormatException ignored){/* unavailable on this platform; heap/graph protection stays active */}
        return -1;
    }
}
