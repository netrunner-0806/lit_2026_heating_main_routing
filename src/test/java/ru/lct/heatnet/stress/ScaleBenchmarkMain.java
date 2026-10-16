package ru.lct.heatnet.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatnet.domain.*;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.routing.NavGraph;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Warm-up + repeated stage measurements. Failures are recorded AND produce a non-zero exit status. */
public final class ScaleBenchmarkMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path input=Paths.get(args[0]), output=Paths.get(args[2]);
        String phase=args[1]; int repeats=Integer.parseInt(args[3]);
        Files.createDirectories(output.getParent());
        List<Map<String,Object>> runs=new ArrayList<>();
        for(int i=0;i<=repeats;i++) {
            System.gc(); Thread.sleep(150);
            AtomicLong heap=new AtomicLong(),rss=new AtomicLong();
            Thread sampler=new Thread(()->{
                while(!Thread.currentThread().isInterrupted()) {
                    heap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                    rss.accumulateAndGet(rss(),Math::max);
                    try {Thread.sleep(10);}catch(InterruptedException ex){return;}
                }
            });sampler.setDaemon(true);sampler.start();
            Map<String,Object> row=new LinkedHashMap<>();row.put("run",i);row.put("warmup",i==0);
            long start=System.nanoTime();
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpuStart=os.getProcessCpuTime(); Exception failure=null;
            try {
                row.putAll(run(input,phase,output,i));
                if(row.containsKey("output_sha256")&&!runs.isEmpty()
                        &&!row.get("output_sha256").equals(runs.get(0).get("output_sha256")))
                    throw new IllegalStateException("Non-deterministic output");
                row.put("status","OK");
            }
            catch(Exception ex) {failure=ex;row.put("status","FAILED");row.put("exception",ex.toString());ex.printStackTrace();}
            finally {
                row.put("cpu_ms", (os.getProcessCpuTime()-cpuStart)/1_000_000);
                row.put("wall_ms",(System.nanoTime()-start)/1_000_000);
                sampler.interrupt();sampler.join();row.put("peak_heap_bytes",heap.get());row.put("peak_rss_bytes",rss.get());
                runs.add(row);JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),runs);
                System.out.println("BENCHMARK "+JSON.writeValueAsString(row));
            }
            if(failure!=null)throw failure;
        }
    }
    private static Map<String,Object> run(Path input,String phase,Path output,int iteration)throws Exception {
        Map<String,Object> r=new LinkedHashMap<>();long start=System.nanoTime();
        if(phase.equals("PLANAR")||phase.equals("DEPTH")) {
            Path geo=Paths.get(output+"."+iteration+".geojson");
            StressMain.main(new String[]{input.toString(),phase,geo.toString()});
            Map<String,Object> result=JSON.readValue(Paths.get(geo+".report.json").toFile(),Map.class);
            r.putAll(result);
            if(!"DONE".equals(result.get("status")))throw new IllegalStateException("Solver run failed: "+result.get("exception"));
            if(!Boolean.TRUE.equals(result.get("valid"))||((Number)result.get("errors")).longValue()!=0
                    ||((Number)result.get("warnings")).longValue()!=0
                    ||!((List<?>)result.get("engineering_oracle_issues")).isEmpty())
                throw new IllegalStateException("Independent validation failed; see "+geo+".report.json");
            return r;
        }
        InputModel m=new GeoJsonStreamReader(ParseOptions.defaults()).read(input);
        r.put("parse_ms",(System.nanoTime()-start)/1_000_000);r.put("objects",m.featureCount());
        r.put("restrictions",m.restrictions().size());
        long points=m.restrictions().stream().mapToLong(x->x.geometry().getNumPoints()).sum()
                +m.networkLines().stream().mapToLong(x->x.geometry().getNumPoints()).sum()+m.connectionPoints().size()+m.chambers().size()+m.sources().size();
        r.put("coordinates",points);
        if(m.diagnostics().hasErrors())throw new IllegalStateException("INVALID_INPUT: "+m.diagnostics().errors());
        System.gc();Thread.sleep(100);
        r.put("retained_heap_after_parse",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        r.put("objects_after_gc",m.featureCount());
        if(phase.equals("context")) {
            start=System.nanoTime();
            var context=new ru.lct.heatnet.solver.SolverContext(m,ru.lct.heatnet.solver.SolverConfig.defaults());
            var resources=context.resources(ClearanceClass.SMALL);
            r.put("context_build_ms",(System.nanoTime()-start)/1_000_000);
            r.putAll(context.graphStats());
            long edges=0;for(int i=0;i<resources.graph.size();i++)edges+=resources.graph.neighbours(i).length;
            r.put("directed_edges",edges);
            var checks=NavGraph.class.getDeclaredField("visibilityChecks");checks.setAccessible(true);
            Object counter=checks.get(resources.graph);
            r.put("visibility_checks", counter instanceof java.util.concurrent.atomic.AtomicLong ? ((java.util.concurrent.atomic.AtomicLong) counter).get() : counter);
            r.put("graph_sha256", graphHash(resources.graph));
            return r;
        }
        if(!phase.equals("parse")) {
            start=System.nanoTime();ObstacleSpace s=new ObstacleSpace(m,ClearanceClass.SMALL);
            r.put("restriction_prepare_ms",(System.nanoTime()-start)/1_000_000);
            r.put("prepared_objects",s.obstacles().size());
            try {r.put("check_buffers_prepared",ObstacleSpace.class.getMethod("preparedObjectCount").invoke(s));}
            catch(NoSuchMethodException legacy) {r.put("check_buffers_prepared",s.obstacles().size());}
            if(phase.equals("graph")) {
                start=System.nanoTime();NavGraph graph=new NavGraph(s,300,80);
                r.put("graph_build_ms",(System.nanoTime()-start)/1_000_000);r.put("nodes",graph.size());
                long edges=0;for(int i=0;i<graph.size();i++)edges+=graph.neighbours(i).length;
                r.put("directed_edges",edges);
            }
            r.put("intersection_calls",s.geometryStats().intersections.sum());
        }
        return r;
    }
    private static long rss() {
        try {for(String l:Files.readAllLines(Paths.get("/proc/self/status")))
            if(l.startsWith("VmRSS:"))return Long.parseLong(l.trim().split("\\s+")[1])*1024;}
        catch(Exception ignored) { }
        return -1;
    }
    /** Exact vertex ids/order, coordinates, directed adjacency, weights and special flags, not just graph size. */
    private static String graphHash(NavGraph graph) throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        var field=NavGraph.class.getDeclaredField("special");field.setAccessible(true);
        @SuppressWarnings("unchecked") List<boolean[]> flags=(List<boolean[]>)field.get(graph);
        try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))) {
            out.writeInt(graph.size());
            for(int i=0;i<graph.size();i++) {
                out.writeDouble(graph.coord(i).x);out.writeDouble(graph.coord(i).y);out.writeBoolean(graph.isActive(i));
                int[] nodes=graph.neighbours(i);double[] weights=graph.weights(i);out.writeInt(nodes.length);
                for(int j=0;j<nodes.length;j++){out.writeInt(nodes[j]);out.writeDouble(weights[j]);out.writeBoolean(flags.get(i)[j]);}
            }
        }
        StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format("%02x",b));return text.toString();
    }
}
