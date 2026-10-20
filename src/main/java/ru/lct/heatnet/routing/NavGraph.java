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
    /** Resource guards, independent of routing/geometric rules. */
    public static final int MAX_GRID_NODES = 100_000;
    public static final int MAX_NAVIGATION_NODES = 100_000;
    /** Adjacency primitives use <=52 MB at this bound; intermediate edge collections are also bounded. */
    public static final int MAX_DIRECTED_EDGES = 4_000_000;
    public static final long MAX_VISIBILITY_CHECKS = 50_000_000;
    private final java.util.concurrent.atomic.AtomicInteger directedEdges = new java.util.concurrent.atomic.AtomicInteger();
    private java.util.concurrent.atomic.AtomicLong visibilityChecks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.LongAdder interiorRejections = new java.util.concurrent.atomic.LongAdder();
    public long interiorRejectionCount() { return interiorRejections.sum(); }
    private final java.util.concurrent.atomic.LongAdder geometryChecks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder pairCandidates = new java.util.concurrent.atomic.LongAdder();
    public long pairCandidateCount() { return pairCandidates.sum(); }
    private long dynamicInsertions, traversals;
    public void countTraversal() { traversals++; }
    public long traversalCount() { return traversals; }
    public long geometryCheckCount() { return geometryChecks.sum(); }
    public long dynamicInsertionCount() { return dynamicInsertions; }
    private final ComputeBudget budget;
    private final ComputeCancellation cancellation;
    public ComputeBudget budget() { return budget; }
    public void checkCancelled() { cancellation.check(); }
    private void beforeMutation() { cancellation.check(); }
    private final int maxEdges;
    private final long maxVisibilityChecks;

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
    private volatile int memoryNodes;
    private int sharedBaseEdges;
    /** Includes COW replacement/incoming rows; shared primitive rows are owned by the base only. */
    public long estimatedOwnedBytes() {
        long edges=directedEdges.get();
        return 512L*memoryNodes+32L*sharedBaseEdges+80L*Math.max(0,edges-sharedBaseEdges);
    }
    public void releaseMemoryTracking() { cancellation.releaseMemory(this); }

    public NavGraph(ObstacleSpace space, double staticRadius, double helperGridSpacing) {
        this(space, staticRadius, helperGridSpacing, MAX_DIRECTED_EDGES, MAX_VISIBILITY_CHECKS);
    }

    NavGraph(ObstacleSpace space, double staticRadius, double helperGridSpacing, int maxEdges, long maxVisibilityChecks) {
        this(space,staticRadius,helperGridSpacing,new ComputeBudget(MAX_NAVIGATION_NODES,maxEdges,maxVisibilityChecks,MAX_GRID_NODES,1000),new ComputeCancellation());
    }

    public NavGraph(ObstacleSpace space, double staticRadius, double helperGridSpacing, ComputeBudget budget, ComputeCancellation cancellation) {
        this.budget=budget;this.cancellation=cancellation;
        this.maxEdges = budget.directedEdges;
        this.maxVisibilityChecks = budget.visibilityChecks;
        this.space = space;
        this.staticRadius = staticRadius;
        cancellation.registerMemory(this,this::estimatedOwnedBytes);
        try {
        // Estimate the full bbox grid before collecting or allocating its vertices.
        if (helperGridSpacing > 0) checkedGridSize(space.navigationEnvelope(), helperGridSpacing,budget.gridCells);
        List<Coordinate> vs = space.navigationVertices(budget.navigationNodes);
        if (helperGridSpacing > 0) vs.addAll(helperGrid(space, helperGridSpacing, budget.navigationNodes - vs.size()));
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
        memoryNodes=staticCount;
        for (int i = 0; i < staticCount; i++) staticIndex.insert(new Envelope(coords.get(i)), i);
        staticIndex.build();
        buildStaticEdges();
        } catch(RuntimeException|Error failure) {releaseMemoryTracking();throw failure;}
    }

    /** Research-only copy-on-write graph fork. Caller must freeze the source at the common base. */
    public NavGraph experimentalFork(ObstacleSpace localSpace) {
        return new NavGraph(this, localSpace);
    }
    private NavGraph(NavGraph source, ObstacleSpace localSpace) {
        budget=source.budget;cancellation=source.cancellation;
        maxEdges=source.maxEdges; maxVisibilityChecks=source.maxVisibilityChecks;
        space=localSpace;staticRadius=source.staticRadius;staticCount=source.staticCount;
        // Arrays are never edited in place by NavGraph: append/remove replace complete rows.
        coords.addAll(source.coords); exemptions.addAll(source.exemptions); headings.addAll(source.headings);
        adj.addAll(source.adj); weights.addAll(source.weights); special.addAll(source.special);
        for (List<Integer> row: source.incoming) incoming.add(row==null?null:new ArrayList<>(row));
        active.or(source.active);directedEdges.set(source.directedEdges.get());
        visibilityChecks=new java.util.concurrent.atomic.AtomicLong(source.visibilityChecks.get()); // independent strategy allowance, including immutable preparation
        sharedBaseEdges=source.directedEdges.get();memoryNodes=coords.size();
        cancellation.registerMemory(this,this::estimatedOwnedBytes);
        try {
        for(int i=0;i<staticCount;i++)staticIndex.insert(new Envelope(coords.get(i)),i);
        staticIndex.build();
        } catch(RuntimeException|Error failure) {releaseMemoryTracking();throw failure;}
    }

    static long checkedGridSize(Envelope source, double spacing) { return checkedGridSize(source,spacing,MAX_GRID_NODES); }
    static long checkedGridSize(Envelope source, double spacing,int limit) {
        if (!Double.isFinite(spacing) || spacing <= 0) throw new IllegalArgumentException("Grid spacing must be finite and positive");
        if (source.isNull()) return 0;
        Envelope env = new Envelope(source);
        env.expandBy(spacing);
        if (env.getMinX() + spacing <= env.getMinX() || env.getMinY() + spacing <= env.getMinY())
            throw new IllegalArgumentException("Navigation grid coordinates cannot be represented at the requested spacing");
        double nx = Math.floor(env.getWidth() / spacing) + 1;
        double ny = Math.floor(env.getHeight() / spacing) + 1;
        double cells = nx * ny;
        if (!Double.isFinite(cells) || cells > limit)
            throw new ComplexityLimitException("Navigation grid exceeds " + limit
                    + " cells; the input extent is too large for automatic routing");
        return (long) cells;
    }

    private List<Coordinate> helperGrid(ObstacleSpace space, double spacing, int remaining) {
        checkedGridSize(space.vertexZoneUnion().getEnvelopeInternal(), spacing,budget.gridCells);
        List<Coordinate> out = new ArrayList<>();
        Envelope env = space.vertexZoneUnion().getEnvelopeInternal();
        if (env.isNull()) return out;
        env.expandBy(spacing);
        // The same immutable zone is queried for every grid point; prepare its spatial index once.
        org.locationtech.jts.geom.prep.PreparedGeometry vertexZone =
                org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(space.vertexZoneUnion());
        for (double x = env.getMinX(); x <= env.getMaxX(); x += spacing) {
            for (double y = env.getMinY(); y <= env.getMaxY(); y += spacing) {
                cancellation.check();
                Coordinate c = new Coordinate(x, y);
                if (!vertexZone.intersects(GeometryUtils.point(c))) {
                    if (out.size() >= remaining) throw navigationLimit();
                    out.add(c);
                }
            }
        }
        return out;
    }

    private IllegalArgumentException navigationLimit() {
        return new ComplexityLimitException("Navigation graph exceeds " + budget.navigationNodes
                + " nodes; the input geometry is too complex for automatic routing");
    }

    private void buildStaticEdges() {
        int n = staticCount;
        int[][] res = new int[n][];
        double[][] resW = new double[n][];
        boolean[][] resS = new boolean[n][];
        boolean[][] resF = new boolean[n][];   // i->j allowed
        boolean[][] resB = new boolean[n][];   // j->i allowed
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure=new java.util.concurrent.atomic.AtomicReference<>();
        IntStream.range(0, n).parallel().forEach(i -> {
            if(failure.get()!=null)return;
            try (ComputeCancellation.Scope scope=cancellation.bind()) {
            cancellation.check();
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
                cancellation.check();
                if(failure.get()!=null)return;
                if (j <= i) continue;
                Coordinate cj = coords.get(j);
                double d = ci.distance(cj);
                if (d > staticRadius || d < 1e-6) continue;
                pairCandidates.increment();
                if (space.definitelyBlocked(ci, cj, Exemptions.NONE)) { interiorRejections.increment(); continue; }
                reserveVisibilityChecks(1);
                geometryChecks.increment();
                SegmentCheck sc = space.check(ci, cj);
                if (!sc.valid() && !sc.validReverse()) continue;
                reserveEdges((sc.valid() ? 1 : 0) + (sc.validReverse() ? 1 : 0));
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
            } catch(RuntimeException ex) { failure.compareAndSet(null,ex); }
        });
        cancellation.check();
        if(failure.get() instanceof ComplexityLimitException) {
            // Static tasks have no logical first operation. The deterministic failure is the
            // construction envelope, not whichever of its atomic counters happened to reject first.
            // Counters still stop before exceeding either bound; no partial graph is published.
            throw new ComplexityLimitException("Navigation graph static resource envelope exhausted"
                    + " (directed edges <= " + maxEdges + ", visibility checks <= " + maxVisibilityChecks + ")");
        }
        if(failure.get()!=null)throw failure.get();
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
    public int directedEdgeCount() { return directedEdges.get(); }
    public long visibilityCheckCount() { return visibilityChecks.get(); }
    public int edgeCount() {
        long c = 0;
        for (int[] a : adj) c += a.length;
        return (int) (c / 2);
    }

    private void reserveEdges(int count) {
        cancellation.check();
        for (;;) {
            int used=directedEdges.get();
            if(count > maxEdges-used)throw new ComplexityLimitException("Navigation graph exceeds " + maxEdges + " directed edges");
            if(directedEdges.compareAndSet(used,used+count))return;
        }
    }

    /** Charge bounded fallback checks to the same class budget; never reset it for a retry. */
    public void reserveFallbackChecks(long count) {
        beforeMutation();
        if (count < 0) throw new IllegalArgumentException("negative checks");
        reserveVisibilityChecks(count);
    }

    private void reserveVisibilityChecks(long count) {
        cancellation.check();
        for (;;) {
            long used=visibilityChecks.get();
            if (count > maxVisibilityChecks-used)
                throw new ComplexityLimitException("Navigation visibility exceeds " + maxVisibilityChecks + " pair checks per clearance class");
            if (visibilityChecks.compareAndSet(used,used+count)) return;
        }
    }

    /**
     * Adds a terminal vertex connected (by visibility) to all active vertices within the radius. The heading, if
     * given, restricts outgoing edges to turns <= 90° from it (used for the fixed final segment of a connection point).
     * Edges to other dynamic vertices are checked with merged exemptions.
     */
    public synchronized int addVertex(Coordinate c, Exemptions ex, double radius, Heading heading) {
        if (coords.size() >= budget.navigationNodes) throw navigationLimit();
        beforeMutation();
        dynamicInsertions++;
        int v = coords.size();
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
        pairCandidates.add(cand.size());
        final Exemptions exV = ex == null ? Exemptions.NONE : ex;
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure=new java.util.concurrent.atomic.AtomicReference<>();
        List<Object[]> found = cand.parallelStream().map(j -> {
            if(failure.get()!=null)return null;
            try (ComputeCancellation.Scope scope=cancellation.bind()) {
            cancellation.check();
            if (!active.get(j)) return null;
            Coordinate cj = coords.get(j);
            double d = c.distance(cj);
            if (d > radius || d < 1e-6) return null;
            if (heading != null && GeometryUtils.turnAngleDeg(new Coordinate(c.x - heading.dx, c.y - heading.dy), c, cj) > 90.0 + 1e-9) return null;
            Heading hj = headings.get(j);
            if (hj != null && GeometryUtils.turnAngleDeg(new Coordinate(cj.x - hj.dx, cj.y - hj.dy), cj, c) > 90.0 + 1e-9) return null;
            Exemptions merged = Exemptions.merge(exV, exemptions.get(j));
            if (space.definitelyBlocked(c, cj, merged)) { interiorRejections.increment(); return null; }
            reserveVisibilityChecks(1);
            geometryChecks.increment();
            SegmentCheck sc = space.check(c, cj, merged);
            if (!sc.valid() && !sc.validReverse()) return null;
            return new Object[]{j, sc.effectiveLength(), sc.hasSpecial(), sc.valid(), sc.validReverse()};
            } catch(RuntimeException error) { failure.compareAndSet(null,error);return null; }
        }).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
        if(failure.get()!=null)throw failure.get();
        cancellation.check();
        for (Object[] f : found) {
            if ((Boolean) f[3]) { js.add((Integer) f[0]); ws.add((Double) f[1]); ss.add((Boolean) f[2]); }
            if ((Boolean) f[4]) reverse.add(f);
        }
        // Check before changing graph state: a failed fallback must leave the graph usable.
        reserveEdges(js.size() + reverse.size());
        coords.add(c);
        memoryNodes=coords.size();
        exemptions.add(exV);
        headings.add(heading);
        incoming.add(new ArrayList<>());
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

    /** Remove only edges rejected by exact class clearance. All surviving order, weights and budgets are kept. */
    public synchronized void recheckClearances() {
        beforeMutation();
        for (int from=0;from<coords.size();from++) {
            int[] targets=adj.get(from).clone();
            for (int to : targets) {
                reserveVisibilityChecks(1); geometryChecks.increment();
                if (!space.check(coords.get(from),coords.get(to),Exemptions.merge(exemptions.get(from),exemptions.get(to))).valid())
                    removeEdge(from,to);
            }
        }
    }

    /** Disables a vertex (its edges are skipped by searches). */
    public synchronized void deactivate(int v) { active.clear(v); }

    public synchronized void activate(int v) { active.set(v); }

    /** Removes all dynamic vertices added after the given size (they must be the most recently added). */
    public synchronized void truncate(int size) {
        if (size >= coords.size()) return;
        for (int v = coords.size() - 1; v >= size; v--) {
            directedEdges.addAndGet(-adj.get(v).length);
            List<Integer> in = incoming.get(v);
            if (in != null) for (int j : in) removeEdge(j, v);
            coords.remove(v); exemptions.remove(v); headings.remove(v); adj.remove(v); weights.remove(v); special.remove(v);
            memoryNodes=coords.size();
            incoming.remove(v);
            active.clear(v);
        }
    }

    private void removeEdge(int from, int to) {
        int[] a = adj.get(from);
        int idx = -1;
        for (int k = 0; k < a.length; k++) if (a[k] == to) { idx = k; break; }
        if (idx < 0) return;
        directedEdges.decrementAndGet();
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
