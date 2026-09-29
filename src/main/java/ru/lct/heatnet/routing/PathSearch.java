package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.restrictions.Exemptions;

import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Dijkstra over the navigation graph with lazily evaluated dynamic obstacles (already built new lines) and a small
 * per-vertex penalty that prefers routes with fewer turns among near-equal alternatives.
 */
public final class PathSearch {

    public static final class Result {
        public final double[] dist;
        public final int[] prev;
        Result(double[] dist, int[] prev) { this.dist = dist; this.prev = prev; }
        public boolean reached(int v) { return v < dist.length && dist[v] < Double.POSITIVE_INFINITY; }
    }

    /** Provides exemption discs (for the dynamic obstacle check) of a vertex: pairs [Coordinate, Double radius]. */
    public interface DynamicExemptionProvider {
        List<Object[]> discs(int vertex);
    }

    private final NavGraph graph;
    private final DynamicObstacles dynamic;
    private final DynamicExemptionProvider dynEx;
    private final double turnPenalty;
    private final Set<Long> bannedEdges;
    /** Obstacle geometries (metric) that no edge of the search may intersect (corridor-diverse alternatives). */
    private List<org.locationtech.jts.geom.Geometry> bannedGeometries;
    private Set<Integer> terminals = java.util.Collections.emptySet();

    public PathSearch(NavGraph graph, DynamicObstacles dynamic, DynamicExemptionProvider dynEx, double turnPenalty, Set<Long> bannedEdges) {
        this.graph = graph;
        this.dynamic = dynamic;
        this.dynEx = dynEx;
        this.turnPenalty = turnPenalty;
        this.bannedEdges = bannedEdges;
    }

    /** Vertices that may be reached but never passed through (attachment targets carry exemptions valid only as ends). */
    public PathSearch bannedGeometries(List<org.locationtech.jts.geom.Geometry> geoms) {
        this.bannedGeometries = geoms == null || geoms.isEmpty() ? null : geoms;
        return this;
    }

    /** True if the segment intersects one of the banned obstacle geometries. */
    public static boolean crossesAny(List<org.locationtech.jts.geom.Geometry> geoms, Coordinate a, Coordinate b) {
        if (geoms == null || geoms.isEmpty()) return false;
        org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope(a, b);
        org.locationtech.jts.geom.LineString seg = null;
        for (org.locationtech.jts.geom.Geometry g : geoms) {
            if (!g.getEnvelopeInternal().intersects(env)) continue;
            if (seg == null) seg = ru.lct.heatnet.geo.GeometryUtils.GF.createLineString(new Coordinate[]{a, b});
            if (g.intersects(seg)) return true;
        }
        return false;
    }

    public PathSearch terminals(Set<Integer> terminals) {
        this.terminals = terminals == null ? java.util.Collections.emptySet() : terminals;
        return this;
    }

    public static long edgeKey(int u, int v) { return (((long) u) << 32) | (v & 0xffffffffL); }

    public Result run(int source) { return run(source, null); }

    /** Runs from the source; if targets is non-null, stops once all targets are settled. */
    public Result run(int source, Set<Integer> targets) {
        int n = graph.size();
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        boolean[] done = new boolean[n];
        dist[source] = 0;
        PriorityQueue<double[]> pq = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        pq.add(new double[]{0, source});
        int remaining = targets == null ? -1 : targets.size();
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[1];
            if (done[u]) continue;
            done[u] = true;
            if (targets != null && targets.contains(u) && --remaining <= 0) break;
            if (u != source && terminals.contains(u)) continue;
            int[] nb = graph.neighbours(u);
            double[] w = graph.weights(u);
            for (int k = 0; k < nb.length; k++) {
                int v = nb[k];
                if (done[v] || !graph.isActive(v)) continue;
                if (bannedEdges != null && (bannedEdges.contains(edgeKey(u, v)) || bannedEdges.contains(edgeKey(v, u)))) continue;
                double nd = dist[u] + w[k] + (u == source ? 0 : turnPenalty);
                if (nd >= dist[v]) continue;
                if (bannedGeometries != null && crossesAny(bannedGeometries, graph.coord(u), graph.coord(v))) continue;
                if (dynamic != null && !dynamic.isEmpty()) {
                    Coordinate cu = graph.coord(u), cv = graph.coord(v);
                    List<Object[]> ex = null;
                    if (dynEx != null) {
                        List<Object[]> eu = dynEx.discs(u), ev = dynEx.discs(v);
                        if (eu != null || ev != null) {
                            ex = new java.util.ArrayList<>();
                            if (eu != null) ex.addAll(eu);
                            if (ev != null) ex.addAll(ev);
                        }
                    }
                    if (!dynamic.allows(cu, cv, ex)) continue;
                    nd += dynamic.proximityPenalty(cu, cv, ex);
                    if (nd >= dist[v]) continue;
                }
                dist[v] = nd;
                prev[v] = u;
                pq.add(new double[]{nd, v});
            }
        }
        return new Result(dist, prev);
    }

    public static int[] pathTo(Result r, int target) {
        if (!r.reached(target)) return null;
        int len = 0;
        for (int v = target; v != -1; v = r.prev[v]) len++;
        int[] path = new int[len];
        int i = len - 1;
        for (int v = target; v != -1; v = r.prev[v]) path[i--] = v;
        return path;
    }

    static Exemptions ex(NavGraph g, int v) { return g.exemptions(v); }
}
