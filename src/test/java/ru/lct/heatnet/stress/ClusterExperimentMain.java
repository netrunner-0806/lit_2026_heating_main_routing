package ru.lct.heatnet.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatnet.domain.*;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.routing.PathSearch;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.topology.*;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.validation.*;
import ru.lct.heatnet.testkit.SolveKit;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Experimental variants ONLY. Clusters use obstacle-aware graph distances, never a coordinate grid.
 * Independent cluster forests are merged and validated against the FULL input, so collisions/degrees are visible. */
public final class ClusterExperimentMain {
    public static void main(String[] args)throws Exception {
        Path input=Paths.get(args[0]),report=Paths.get(args[1]);int repeats=Integer.parseInt(args[2]);
        List<Map<String,Object>> results=new ArrayList<>();ObjectMapper json=new ObjectMapper();
        Files.createDirectories(report.getParent());
        for(int iteration=0;iteration<=repeats;iteration++) for(String kind:new String[]{"global","independent-clusters","cluster-order-global-topology"}) {
            System.gc();Thread.sleep(100);
            AtomicLong peakHeap=new AtomicLong(),peakRss=new AtomicLong();
            Thread sample=new Thread(()->{while(!Thread.currentThread().isInterrupted()) {
                peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                try {for(String line:Files.readAllLines(Paths.get("/proc/self/status")))if(line.startsWith("VmRSS:"))
                    peakRss.accumulateAndGet(Long.parseLong(line.trim().split("\\s+")[1])*1024,Math::max);
                    Thread.sleep(10);
                }catch(InterruptedException ex){return;}catch(Exception ex){throw new IllegalStateException(ex);}
            }});sample.setDaemon(true);sample.start();
            Map<String,Object> row=new LinkedHashMap<>();row.put("run",iteration);row.put("warmup",iteration==0);row.put("variant",kind);
            long start=System.nanoTime();Exception failure=null;
            try {
                InputModel m=new GeoJsonStreamReader(ParseOptions.defaults()).read(input);
                row.put("objects",m.featureCount());row.put("consumers",m.connectionPoints().size());
                VariantResult result;
                if(kind.equals("global")) result=solve(m);
                else {
                    long groupStart=System.nanoTime();List<List<ConnectionPoint>> groups=groups(m);
                    row.put("cluster_ms",(System.nanoTime()-groupStart)/1_000_000);row.put("cluster_sizes",groups.stream().mapToInt(List::size).toArray());
                    if(kind.equals("cluster-order-global-topology")) {
                        List<ConnectionPoint> order=new ArrayList<>();groups.forEach(order::addAll);
                        result=solve(subset(m,order)); // same global strategies, cross-cluster joints and local improvement remain enabled
                    } else {
                        NewNetwork merged=new NewNetwork();Map<ConnectionPoint,String> unconnected=new LinkedHashMap<>();
                        for(List<ConnectionPoint> group:groups) {VariantResult part=solve(subset(m,group));merge(merged,part.network());unconnected.putAll(part.unconnected());}
                        HydraulicCalculator.Result hydraulic=HydraulicCalculator.compute(merged);
                        if(!hydraulic.feasible())throw new IllegalStateException(hydraulic.problem());
                        List<Double> penalty=new ArrayList<>();unconnected.keySet().forEach(cp->penalty.add(cp.flowTph()));
                        result=new VariantResult(kind,merged,unconnected,CostCalculator.summarize(merged,penalty),Collections.emptyList());
                    }
                }
                result.setVariantId("v1");result.setRank(1);
                var features=new OutputBuilder().build(result);
                var validation=new ResultValidator(m).validate(SolveKit.roundTrip(features));
                row.put("connected",result.connectedCount());row.put("score",result.summary().score());row.put("cost",result.summary().calculatedCost());
                row.put("length",result.summary().newLength());row.put("chambers",result.network().newChambers().size());
                row.put("valid",validation.isValid());row.put("errors",validation.getErrorCount());row.put("warnings",validation.getWarningCount());row.put("issues",validation.getIssues());
                row.put("status",validation.isValid()?"VALID":"INVALID_COMBINED_NETWORK");
            }catch(Exception ex){row.put("status","FAILED");row.put("exception",ex.toString());failure=ex;ex.printStackTrace();}
            finally {
                row.put("wall_ms",(System.nanoTime()-start)/1_000_000);sample.interrupt();sample.join();
                row.put("peak_heap_bytes",peakHeap.get());row.put("peak_rss_bytes",peakRss.get());results.add(row);
                json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),results);System.out.println(json.writeValueAsString(row));
            }
            if(failure!=null)throw failure;
        }
        if(results.stream().anyMatch(r->!"VALID".equals(r.get("status"))))
            throw new IllegalStateException("EXPERIMENT_REJECTED: independent validation rejected a combined cluster network; inspect every variant row");
    }
    private static VariantResult solve(InputModel m) {
        SolveResult r=new HeatnetSolver(SolverConfig.defaults(),new SolverVariantValidator(m)).solve(m);
        if(r.variants().isEmpty()||r.diagnostics().hasErrors())throw new IllegalStateException(r.diagnostics().messages().toString());
        return r.variants().get(0);
    }
    private static InputModel subset(InputModel m,List<ConnectionPoint> points) {
        return new InputModel(m.sources(),m.networkLines(),m.chambers(),points,m.restrictions(),new Diagnostics(),m.featureCount()-m.connectionPoints().size()+points.size());
    }
    private static List<List<ConnectionPoint>> groups(InputModel m) {
        SolverConfig cfg=SolverConfig.defaults();SolverContext context=new SolverContext(m,cfg);
        var resources=context.resources(ClearanceClass.SMALL);List<ConnectionPoint> cps=m.connectionPoints();
        int k=Math.min(4,cps.size());List<double[]> distances=new ArrayList<>();double[] nearest=new double[cps.size()];Arrays.fill(nearest,Double.POSITIVE_INFINITY);
        int seed=0;Set<Integer> selected=new HashSet<>();
        for(int cluster=0;cluster<k;cluster++) {
            selected.add(seed);var approaches=resources.approaches(cps.get(seed));
            if(approaches.isEmpty())throw new IllegalStateException("No feasible exit for clustering seed "+cps.get(seed).id());
            var path=new PathSearch(resources.graph,null,null,cfg.turnPenalty,null).run(approaches.get(0).vertex());
            double[] distance=new double[cps.size()];Arrays.fill(distance,Double.POSITIVE_INFINITY);
            for(int i=0;i<cps.size();i++) {
                for(var a:resources.approaches(cps.get(i)))if(path.reached(a.vertex()))distance[i]=Math.min(distance[i],path.dist[a.vertex()]);
                nearest[i]=Math.min(nearest[i],distance[i]);
            }
            distances.add(distance);double farthest=-1;
            for(int i=0;i<cps.size();i++)if(!selected.contains(i)&&nearest[i]>farthest){farthest=nearest[i];seed=i;}
        }
        List<List<ConnectionPoint>> groups=new ArrayList<>();for(int i=0;i<k;i++)groups.add(new ArrayList<>());
        for(int i=0;i<cps.size();i++) {int best=0;for(int c=1;c<k;c++)if(distances.get(c)[i]<distances.get(best)[i])best=c;groups.get(best).add(cps.get(i));}
        groups.removeIf(List::isEmpty);return groups;
    }
    private static void merge(NewNetwork into,NewNetwork part) {
        Map<Integer,NetNode> nodes=new HashMap<>();
        for(NetNode n:part.nodes()) {
            NetNode target;
            switch(n.kind()) {
                case CONNECTION_POINT:target=into.addConnectionNode(n.connectionPoint());break;
                case EXISTING_CHAMBER:target=into.rootAtExistingChamber(n.existingChamber(),n.existingAdjacency());break;
                case NEW_CHAMBER_ON_EXISTING:target=into.rootOnExistingLine(n.existingLine(),n.coord(),n.existingAdjacency());break;
                default:target=into.addJunctionNode(n.coord());
            }
            nodes.put(n.id(),target);
        }
        for(NetLink link:part.links()) {
            NetLink copy=into.addLink(nodes.get(link.child().id()),nodes.get(link.parent().id()),link.coords(),link.passages());
            copy.setApproachInfo(link.reservedFromChild(),link.ownPolygonIndex());
        }
    }
}
