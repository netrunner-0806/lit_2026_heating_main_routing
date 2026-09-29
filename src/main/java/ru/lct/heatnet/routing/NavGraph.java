package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.ObstacleSpace;
import ru.lct.heatnet.restrictions.SegmentCheck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Visibility graph over the free space of an {@link ObstacleSpace}. Static vertices are the convex corners of the
 * clearance zones (plus a sparse helper grid); terminals (connection points, tie-in candidates, junction candidates)
 * are added dynamically with their own exemptions. Edge weight = effective length (Kspec applied on special sections).
 */
public final class NavGraph {

    /** Direction constraint for edges leaving a vertex: the turn from the incoming direction must be <= 90°. */
    public static final class Heading {
        final double dx, dy;
        public Heading(double dx, double dy) { this.dx = dx; this.dy = dy; }
    }

    private final ObstacleSpace space;
    private final double staticRadius;
    private final List<Coordinate> coords = new ArrayList<>();
    private final List<Exemptions> exemptions = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private final List<int[]> adj = new ArrayList<>();
    private final List<double[]> weights = new ArrayList<>();
    private final List<boolean[]> special = new ArrayList<>();
    /** For dynamic vertices: vertices holding an edge towards them (needed to remove one-directional edges). */
    private final List<List<Integer>> incoming = new ArrayList<>();
    private final BitSet active = new BitSet();
    private final STRtree staticIndex = new STRtree();
    private int staticCount;

    public NavGraph(ObstacleSpace space, double staticRadius, double helperGridSpacing) {
        this.space = space;
        this.staticRadius = staticRadius;
        List<Coordinate> vs = new ArrayList<>(space.navigationVertices());
        if (helperGridSpacing > 0) vs.addAll(helperGrid(space, helperGridSpacing));
        for (Coordinate c : vs) {
            coords.add(c);
            exemptions.add(Exemptions.NONE);
            headings.add(null);
            adj.add(new int[0]);
            weights.add(new double[0]);
            special.add(new boolean[0]);
            incoming.add(null);
            active.set(coords.size() - 1);
        }
        staticCount = coords.size();
        for (int i = 0; i < staticCount; i++) staticIndex.insert(new Envelope(coords.get(i)), i);
        staticIndex.build();
        buildStaticEdges();
    }

    private static List<Coordinate> helperGrid(ObstacleSpace space, double spacing) {
        List<Coordinate> out = new ArrayList<>();
        Envelope env = space.vertexZoneUnion().getEnvelopeInternal();
        if (env.isNull()) return out;
        env.expandBy(spacing);
        for (double x = env.getMinX(); x <= env.getMaxX(); x += spacing) {
            for (double y = env.getMinY(); y <= env.getMaxY(); y += spacing) {
                Coordinate c = new Coordinate(x, y);
                if (!space.vertexZoneUnion().intersects(GeometryUtils.point(c))) out.add(c);
            }
        }
        return out;
    }

    private void buildStaticEdges() {
        int n = staticCount;
        List<List<int[]>> found = new ArrayList<>();
        List<List<double[]>> foundW = new ArrayList<>();
        for (int i = 0; i < n; i++) { found.add(null); foundW.add(null); }
        int[][] res = new int[n][];
        double[][] resW = new double[n][];
        boolean[][] resS = new boolean[n][];
        boolean[][] resF = new boolean[n][];   // i->j allowed
        boolean[][] resB = new boolean[n][];   // j->i allowed
        IntStream.range(0, n).parallel().forEach(i -> {
            Coordinate ci = coords.get(i);
            Envelope env = new Envelope(ci);
            env.expandBy(staticRadius);
            @SuppressWarnings("unchecked")
            List<Integer> cand = staticIndex.query(env);
            List<Integer> js = new ArrayList<>();
            List<Double> ws = new ArrayList<>();
            List<Boolean> ss = new ArrayList<>();
            List<Boolean> fs = new ArrayList<>();
            List<Boolean> bs = new ArrayList<>();
            for (int j : cand) {
                if (j <= i) continue;
                Coordinate cj = coords.get(j);
                double d = ci.distance(cj);
                if (d > staticRadius || d < 1e-6) continue;
                SegmentCheck sc = space.check(ci, cj);
                if (!sc.valid() && !sc.validReverse()) continue;
                js.add(j);
                ws.add(sc.effectiveLength());
                ss.add(sc.hasSpecial());
                fs.add(sc.valid());
                bs.add(sc.validReverse());
            }
            res[i] = js.stream().mapToInt(Integer::intValue).toArray();
            resW[i] = ws.stream().mapToDouble(Double::doubleValue).toArray();
            boolean[] sb = new boolean[ss.size()], fb = new boolean[ss.size()], bb = new boolean[ss.size()];
            for (int k = 0; k < sb.length; k++) { sb[k] = ss.get(k); fb[k] = fs.get(k); bb[k] = bs.get(k); }
            resS[i] = sb; resF[i] = fb; resB[i] = bb;
        });
        // directed adjacency (edges may be one-directional because of the road/tram entry angle rule)
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) for (int k = 0; k < res[i].length; k++) { if (resF[i][k]) deg[i]++; if (resB[i][k]) deg[res[i][k]]++; }
        int[][] a = new int[n][];
        double[][] w = new double[n][];
        boolean[][] s = new boolean[n][];
        int[] fill = new int[n];
        for (int i = 0; i < n; i++) { a[i] = new int[deg[i]]; w[i] = new double[deg[i]]; s[i] = new boolean[deg[i]]; }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < res[i].length; k++) {
                int j = res[i][k];
                if (resF[i][k]) { a[i][fill[i]] = j; w[i][fill[i]] = resW[i][k]; s[i][fill[i]] = resS[i][k]; fill[i]++; }
                if (resB[i][k]) { a[j][fill[j]] = i; w[j][fill[j]] = resW[i][k]; s[j][fill[j]] = resS[i][k]; fill[j]++; }
            }
        }
        for (int i = 0; i < n; i++) { adj.set(i, a[i]); weights.set(i, w[i]); special.set(i, s[i]); }
    }

    private final java.util.Map<String, Integer> coordIndex = new java.util.HashMap<>();

    private static String key(Coordinate c) { return Math.round(c.x * 1000) + ":" + Math.round(c.y * 1000); }

    /** Vertex index at the coordinate (1 mm), or -1. */
    public synchronized int indexOf(Coordinate c) {
        if (coordIndex.size() != coords.size()) {
            coordIndex.clear();
            for (int i = 0; i < coords.size(); i++) coordIndex.put(key(coords.get(i)), i);
        }
        Integer v = coordIndex.get(key(c));
        return v == null ? -1 : v;
    }

    public ObstacleSpace space() { return space; }
    public int size() { return coords.size(); }
    public int staticCount() { return staticCount; }
    public Coordinate coord(int v) { return coords.get(v); }
    public Exemptions exemptions(int v) { return exemptions.get(v); }
    public boolean isActive(int v) { return active.get(v); }
    public int[] neighbours(int v) { return adj.get(v); }
    public double[] weights(int v) { return weights.get(v); }
    public int edgeCount() {
        long c = 0;
        for (int[] a : adj) c += a.length;
        return (int) (c / 2);
    }

    /**
     * Adds a terminal vertex connected (by visibility) to all active vertices within the radius. The heading, if
     * given, restricts outgoing edges to turns <= 90° from it (used for the fixed final segment of a connection point).
     * Edges to other dynamic vertices are checked with merged exemptions.
     */
    public synchronized int addVertex(Coordinate c, Exemptions ex, double radius, Heading heading) {
        int v = coords.size();
        coords.add(c);
        exemptions.add(ex == null ? Exemptions.NONE : ex);
        headings.add(heading);
        incoming.add(new ArrayList<>());
        List<Integer> js = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        List<Boolean> ss = new ArrayList<>();
        List<Object[]> reverse = new ArrayList<>();
        List<Integer> cand = new ArrayList<>();
        Envelope env = new Envelope(c);
        env.expandBy(radius);
        @SuppressWarnings("unchecked")
        List<Integer> stat = staticIndex.query(env);
        cand.addAll(stat);
        for (int j = staticCount; j < v; j++) if (active.get(j) && env.contains(coords.get(j))) cand.add(j);
        final Exemptions exV = exemptions.get(v);
        List<Object[]> found = cand.parallelStream().map(j -> {
            if (!active.get(j)) return null;
            Coordinate cj = coords.get(j);
            double d = c.distance(cj);
            if (d > radius || d < 1e-6) return null;
            if (heading != null && GeometryUtils.turnAngleDeg(new Coordinate(c.x - heading.dx, c.y - heading.dy), c, cj) > 90.0 + 1e-9) return null;
            Heading hj = headings.get(j);
            if (hj != null && GeometryUtils.turnAngleDeg(new Coordinate(cj.x - hj.dx, cj.y - hj.dy), cj, c) > 90.0 + 1e-9) return null;
            SegmentCheck sc = space.check(c, cj, Exemptions.merge(exV, exemptions.get(j)));
            if (!sc.valid() && !sc.validReverse()) return null;
            return new Object[]{j, sc.effectiveLength(), sc.hasSpecial(), sc.valid(), sc.validReverse()};
        }).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
        for (Object[] f : found) {
            if ((Boolean) f[3]) { js.add((Integer) f[0]); ws.add((Double) f[1]); ss.add((Boolean) f[2]); }
            if ((Boolean) f[4]) reverse.add(f);
        }
        int[] a = js.stream().mapToInt(Integer::intValue).toArray();
        double[] w = ws.stream().mapToDouble(Double::doubleValue).toArray();
        boolean[] s = new boolean[a.length];
        for (int k = 0; k < a.length; k++) s[k] = ss.get(k);
        adj.add(a);
        weights.add(w);
        special.add(s);
        // edges towards the new vertex (valid when traversed j -> v)
        for (Object[] f : reverse) {
            int j = (Integer) f[0];
            appendEdge(j, v, (Double) f[1], (Boolean) f[2]);
            incoming.get(v).add(j);
        }
        active.set(v);
        return v;
    }

    private void appendEdge(int from, int to, double w, boolean s) {
        int[] a = adj.get(from);
        double[] ww = weights.get(from);
        boolean[] ss = special.get(from);
        int[] na = Arrays.copyOf(a, a.length + 1);
        double[] nw = Arrays.copyOf(ww, ww.length + 1);
        boolean[] ns = Arrays.copyOf(ss, ss.length + 1);
        na[a.length] = to; nw[a.length] = w; ns[a.length] = s;
        adj.set(from, na); weights.set(from, nw); special.set(from, ns);
    }

    /** Disables a vertex (its edges are skipped by searches). */
    public synchronized void deactivate(int v) { active.clear(v); }

    public synchronized void activate(int v) { active.set(v); }

    /** Removes all dynamic vertices added after the given size (they must be the most recently added). */
    public synchronized void truncate(int size) {
        if (size >= coords.size()) return;
        for (int v = coords.size() - 1; v >= size; v--) {
            List<Integer> in = incoming.get(v);
            if (in != null) for (int j : in) removeEdge(j, v);
            coords.remove(v); exemptions.remove(v); headings.remove(v); adj.remove(v); weights.remove(v); special.remove(v);
            incoming.remove(v);
            active.clear(v);
        }
    }

    private void removeEdge(int from, int to) {
        int[] a = adj.get(from);
        int idx = -1;
        for (int k = 0; k < a.length; k++) if (a[k] == to) { idx = k; break; }
        if (idx < 0) return;
        int[] na = new int[a.length - 1];
        double[] nw = new double[a.length - 1];
        boolean[] ns = new boolean[a.length - 1];
        double[] w = weights.get(from);
        boolean[] s = special.get(from);
        for (int k = 0, m = 0; k < a.length; k++) {
            if (k == idx) continue;
            na[m] = a[k]; nw[m] = w[k]; ns[m] = s[k]; m++;
        }
        adj.set(from, na); weights.set(from, nw); special.set(from, ns);
    }
}
