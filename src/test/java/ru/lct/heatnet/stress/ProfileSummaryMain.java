package ru.lct.heatnet.stress;

import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Streams JFR events instead of materialising multi-gigabyte textual stack dumps. */
public final class ProfileSummaryMain {
    public static void main(String[] args) throws Exception {
        Map<String,Long> events=new TreeMap<>(), cpu=new HashMap<>(), allocation=new HashMap<>(),threads=new HashMap<>(), leaves=new HashMap<>();
        long samples=0,allocated=0;
        try(RecordingFile file=new RecordingFile(Paths.get(args[0]))) {
            while(file.hasMoreEvents()) {
                RecordedEvent e=file.readEvent();String type=e.getEventType().getName();events.merge(type,1L,Long::sum);
                boolean execution=type.equals("jdk.ExecutionSample");
                boolean alloc=type.equals("jdk.ObjectAllocationInNewTLAB")||type.equals("jdk.ObjectAllocationOutsideTLAB");
                if(!execution&&!alloc)continue;
                long weight=execution?1:e.getLong(type.endsWith("InNewTLAB")?"tlabSize":"allocationSize");
                if(execution){samples++;RecordedThread t=e.getThread("sampledThread");if(t!=null)threads.merge(t.getJavaName(),1L,Long::sum);}
                else allocated+=weight;
                RecordedStackTrace trace=e.getStackTrace();if(trace==null)continue;
                if(execution&&!trace.getFrames().isEmpty()) {
                    RecordedFrame f=trace.getFrames().get(0);RecordedMethod m=f.getMethod();
                    leaves.merge(m.getType().getName()+"."+m.getName()+":"+f.getLineNumber(),1L,Long::sum);
                }
                Set<String> seen=new HashSet<>();
                for(RecordedFrame f:trace.getFrames()) {
                    RecordedMethod m=f.getMethod();String name=m.getType().getName()+"."+m.getName();
                    if(seen.add(name))(execution?cpu:allocation).merge(name,weight,Long::sum);
                }
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("execution_samples",samples);out.put("tlab_sampled_bytes",allocated);
        out.put("events",events);out.put("threads",sorted(threads));out.put("inclusive_cpu_samples",sorted(cpu));out.put("inclusive_allocation_bytes",sorted(allocation));
        out.put("exclusive_cpu_samples_by_line",sorted(leaves));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Paths.get(args[1]).toFile(),out);
        System.out.println("samples="+samples+" sampled allocation bytes="+allocated);
    }
    private static Map<String,Long> sorted(Map<String,Long> input) {
        Map<String,Long> r=new LinkedHashMap<>();input.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).forEach(e->r.put(e.getKey(),e.getValue()));return r;
    }
}
