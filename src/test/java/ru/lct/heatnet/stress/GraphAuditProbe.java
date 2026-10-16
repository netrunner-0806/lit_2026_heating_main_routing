package ru.lct.heatnet.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatnet.routing.NavGraph;
import java.nio.file.*;
import java.util.*;

/** Test-only observer. Hooks are compiled into copies under target, never into production sources. */
public final class GraphAuditProbe {
    private static final Map<NavGraph, Profile> PROFILES = new IdentityHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final class Profile {
        final int id;
        final Map<Integer, String> kinds = new HashMap<>();
        final int[] scanned = new int[NavGraph.MAX_NAVIGATION_NODES];
        final List<Map<String,Object>> additions = new ArrayList<>();
        String stage = "initialization";
        Set<Integer> terminals = Collections.emptySet();
        Profile(int id) { this.id=id; }
    }
    private static synchronized Profile profile(NavGraph g) {
        return PROFILES.computeIfAbsent(g, key -> new Profile(PROFILES.size()));
    }
    private static String origin() {
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            if (f.getClassName().contains("AdaptiveRouting")) return "adaptive:"+f.getMethodName();
            if (f.getClassName().contains("SolverContext$ClassResources"))
                return f.getMethodName().equals("buildTieIns") ? "tie_in" : "oks_approach";
            if (f.getClassName().contains("ForestBuilder"))
                return f.getMethodName().equals("vertexFor") ? "junction" : "adaptive_approach";
        }
        return "other";
    }
    public static void added(NavGraph g, int v, int outgoing, int incoming) {
        Profile p=profile(g);String kind=origin();p.kinds.put(v,kind);
        p.additions.add(Map.of("vertex",v,"kind",kind,"outgoing",outgoing,"incoming",incoming));
    }
    public static void search(NavGraph g, Set<Integer> terminals) { profile(g).terminals=terminals; }
    public static void scanned(NavGraph g,int v,int length) {
        Profile p=profile(g);p.scanned[v]=Math.max(p.scanned[v],length);
    }
    public static void begin(NavGraph g,String stage) {
        Profile p=profile(g);p.stage=stage;p.kinds.keySet().removeIf(v -> v>=g.size());
        Arrays.fill(p.scanned,0);p.additions.clear();p.terminals=Collections.emptySet();
        snapshot(g,"strategy_start",0);
    }
    public static void end() { for(NavGraph g:new ArrayList<>(PROFILES.keySet()))snapshot(g,"strategy_end",0); }
    public static void limit(NavGraph g,int rejectedEdges) { snapshot(g,"edge_limit",rejectedEdges); }
    public static void snapshot(NavGraph g,String event,int rejectedEdges) {
        try {
            Profile p=profile(g);int n=g.size();long edges=0,duplicates=0,scanned=0,terminalEdges=0;
            Map<String,long[]> groups=new TreeMap<>();List<Map<String,Object>> rows=new ArrayList<>();
            Map<String,Integer> exact=new HashMap<>(),millimetre=new HashMap<>();int exactDup=0,nearDup=0;
            BitSet seen=new BitSet(n);long[] pairs=new long[g.directedEdgeCount()];int pi=0;
            for(int u=0;u<n;u++) {
                String kind=u<g.staticCount()?"static":p.kinds.getOrDefault(u,"unknown");
                int[] nb=g.neighbours(u);seen.clear();
                for(int v:nb) {
                    if(seen.get(v))duplicates++;seen.set(v);
                    pairs[pi++]=((long)Math.min(u,v)<<32)|(Math.max(u,v)&0xffffffffL);
                }
                int touched=Math.min(nb.length,p.scanned[u]);scanned+=touched;edges+=nb.length;
                if(p.terminals.contains(u))terminalEdges+=nb.length;
                long[] group=groups.computeIfAbsent(kind,k->new long[4]);group[0]++;group[1]+=nb.length;group[2]+=touched;group[3]=Math.max(group[3],nb.length);
                rows.add(Map.of("vertex",u,"kind",kind,"outgoing_edges",nb.length,"scanned_edges",touched,"terminal",p.terminals.contains(u)));
                var c=g.coord(u);
                if(exact.put(Double.doubleToLongBits(c.x)+":"+Double.doubleToLongBits(c.y),u)!=null)exactDup++;
                if(millimetre.put(Math.round(c.x*1000)+":"+Math.round(c.y*1000),u)!=null)nearDup++;
            }
            if(pi!=pairs.length)throw new AssertionError("Edge accounting differs from adjacency");
            Arrays.sort(pairs);long unique=0;for(int i=0;i<pi;i++)if(i==0||pairs[i]!=pairs[i-1])unique++;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("graph",p.id);report.put("stage",p.stage);report.put("event",event);
            report.put("static_vertices",g.staticCount());report.put("dynamic_vertices",n-g.staticCount());
            report.put("directed_edges",edges);report.put("unique_undirected_connections",unique);
            report.put("duplicate_directed_edges",duplicates);report.put("exact_coordinate_duplicates",exactDup);report.put("millimetre_coordinate_duplicates",nearDup);
            report.put("edges_enumerated_by_dijkstra",scanned);report.put("edges_not_enumerated_by_dijkstra",edges-scanned);
            report.put("current_terminal_outgoing_edges",terminalEdges);report.put("rejected_addition_edges",rejectedEdges);
            report.put("full_visibility_checks",g.geometryCheckCount());report.put("candidate_pairs",g.pairCandidateCount());
            report.put("dynamic_insertions_cumulative",g.dynamicInsertionCount());report.put("traversals_cumulative",g.traversalCount());
            Map<String,Object> byKind=new TreeMap<>();groups.forEach((kind,a)->byKind.put(kind,Map.of("vertices",a[0],"edges",a[1],"scanned",a[2],"max_out_degree",a[3])));
            report.put("by_vertex_kind",byKind);report.put("vertices",rows);report.put("additions_since_strategy_start",p.additions);
            Path dir=Path.of(System.getProperty("heatnet.graphAudit"));Files.createDirectories(dir);
            JSON.writeValue(dir.resolve(p.id+"-"+p.stage+"-"+event+".json").toFile(),report);
            System.out.println("GRAPH_AUDIT "+p.stage+" "+event+" nodes="+n+" edges="+edges+" duplicates="+duplicates);
        } catch(Exception ex) { throw new IllegalStateException("Graph audit failed",ex); }
    }
}
