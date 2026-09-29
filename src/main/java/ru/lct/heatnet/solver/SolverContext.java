package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.ObstacleSpace;
import ru.lct.heatnet.routing.NavGraph;
import ru.lct.heatnet.routing.PathSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-job resources shared by all variants: obstacle spaces, navigation graphs, tie-in candidates, approaches. */
public final class SolverContext {

    private static final Logger log = LoggerFactory.getLogger(SolverContext.class);

    /** Resources for one clearance class. */
    public final class ClassResources {
        public final ClearanceClass cls;
        public final ObstacleSpace space;
        public final NavGraph graph;
        public final List<TieInCandidate> tieIns;
        public final Map<JsonId, List<Approach>> approaches = new LinkedHashMap<>();
        public final int baseSize;
        public final double tieInDiscRadius;
        /** Soft separation between distinct new lines (full pair width of the class + extra). */
        public final double softSeparation;

        ClassResources(ClearanceClass cls) {
            long t0 = System.currentTimeMillis();
            this.cls = cls;
            this.space = new ObstacleSpace(input, cls);
            this.graph = new NavGraph(space, config.staticRadius, config.helperGridSpacing);
            double maxExistingHalf = 0;
            for (ExistingNetworkLine l : input.networkLines()) maxExistingHalf = Math.max(maxExistingHalf, l.halfWidthM());
            this.tieInDiscRadius = RestrictionRules.existingHeatNetwork().tieInExemptRadiusM(cls.representativeDu(), maxExistingHalf);
            this.softSeparation = 2.0 * cls.halfWidthM() + config.separationExtra;
            this.tieIns = buildTieIns();
            ApproachPlanner planner = new ApproachPlanner(space);
            for (ConnectionPoint cp : input.connectionPoints()) {
                List<Approach> aps = planner.plan(cp);
                for (Approach a : aps) {
                    int v = graph.addVertex(a.freePoint(), Exemptions.NONE, config.terminalRadius, a.heading());
                    a.setVertex(v);
                }
                approaches.put(cp.id(), aps);
            }
            this.baseSize = graph.size();
            graphBuildMs += System.currentTimeMillis() - t0;
            log.info("class {}: obstacles={}, static vertices={}, edges={}, tie-in candidates={}, base graph size={} ({} ms)",
                    cls, space.obstacles().size(), graph.staticCount(), graph.edgeCount(), tieIns.size(), baseSize, System.currentTimeMillis() - t0);
        }

        /**
         * Tie-in candidates on the existing network, in priority order: existing chambers with a spare slot; then per
         * line its vertices, orthogonal projections of every connection point, projections of nearby navigation
         * vertices (obstacle corners), points just outside the boundaries of forbidden clearance zones crossing the
         * line (admissible corridor edges) and finally a fallback sampling. Candidates inside forbidden zones, within
         * 10 m of a usable existing chamber (that chamber is used instead) or closer than the dedupe distance to an
         * accepted candidate are dropped.
         */
        private List<TieInCandidate> buildTieIns() {
            List<TieInCandidate> out = new ArrayList<>();
            org.locationtech.jts.index.strtree.STRtree accepted = new org.locationtech.jts.index.strtree.STRtree();
            List<Coordinate> acceptedPts = new ArrayList<>();
            for (ExistingChamber ch : input.chambers()) {
                int adj = netIndex.existingAdjacency(ch);
                if (adj + 1 > Constants.MAX_CHAMBER_DEGREE) continue;
                if (space.insideForbidden(ch.coordinate())) continue;
                out.add(new TieInCandidate(TieInCandidate.Kind.EXISTING_CHAMBER, ch.coordinate(), ch, null, adj));
                acceptedPts.add(ch.coordinate());
            }
            Map<String, Integer> sourceStats = new LinkedHashMap<>();
            for (ExistingNetworkLine line : input.networkLines()) {
                org.locationtech.jts.geom.LineString ls = line.line();
                org.locationtech.jts.linearref.LengthIndexedLine lil = new org.locationtech.jts.linearref.LengthIndexedLine(ls);
                List<Object[]> cands = new ArrayList<>(); // [Coordinate, source]
                for (Coordinate v : ls.getCoordinates()) cands.add(new Object[]{v, "vertex"});
                for (ConnectionPoint cp : input.connectionPoints())
                    cands.add(new Object[]{lil.extractPoint(lil.project(cp.coordinate())), "connection point projection"});
                org.locationtech.jts.geom.Envelope env = ls.getEnvelopeInternal();
                env.expandBy(config.tieInProjectionDistance);
                for (int vi = 0; vi < graph.staticCount(); vi++) {
                    Coordinate v = graph.coord(vi);
                    if (!env.contains(v)) continue;
                    if (ls.distance(ru.lct.heatnet.geo.GeometryUtils.point(v)) > config.tieInProjectionDistance) continue;
                    cands.add(new Object[]{lil.extractPoint(lil.project(v)), "navigation vertex projection"});
                }
                for (double[] iv : forbiddenIntervals(ls)) {
                    cands.add(new Object[]{lil.extractPoint(Math.max(0, iv[0] - config.tieInCorridorOffset)), "corridor boundary"});
                    cands.add(new Object[]{lil.extractPoint(Math.min(ls.getLength(), iv[1] + config.tieInCorridorOffset)), "corridor boundary"});
                }
                for (Coordinate t : ExistingNetworkIndex.samplePoints(ls, config.tieInStep)) cands.add(new Object[]{t, "sampling"});
                for (Object[] cand : cands) {
                    Coordinate t = (Coordinate) cand[0];
                    if (t == null) continue;
                    if (!netIndex.usableChambersNear(t).isEmpty()) continue; // the chamber itself is the candidate
                    if (space.insideForbidden(t)) continue;
                    boolean dup = false;
                    for (Coordinate a : acceptedPts) if (a.distance(t) < config.tieInDedupe) { dup = true; break; }
                    if (dup) continue;
                    int adj = netIndex.existingAdjacencyAt(t);
                    if (adj + 1 > Constants.MAX_CHAMBER_DEGREE) continue;
                    out.add(new TieInCandidate(TieInCandidate.Kind.NEW_CHAMBER_ON_LINE, t, null, line, adj));
                    acceptedPts.add(t);
                    sourceStats.merge((String) cand[1], 1, Integer::sum);
                }
            }
            log.info("class {}: tie-in candidates by source: chambers={}, {}", cls, out.size() - sourceStats.values().stream().mapToInt(Integer::intValue).sum(), sourceStats);
            for (TieInCandidate c : out) {
                Exemptions ex = new Exemptions().disc(c.point(), tieInDiscRadius, o -> o.isExistingNetwork());
                c.setVertex(graph.addVertex(c.point(), ex, c.kind() == TieInCandidate.Kind.EXISTING_CHAMBER ? config.terminalRadius : config.tieInRadius, null));
            }
            return Collections.unmodifiableList(out);
        }

        /** Length-index intervals of the line lying inside forbidden clearance zones (edges of the admissible corridors). */
        private List<double[]> forbiddenIntervals(org.locationtech.jts.geom.LineString ls) {
            List<double[]> out = new ArrayList<>();
            org.locationtech.jts.linearref.LengthIndexedLine lil = new org.locationtech.jts.linearref.LengthIndexedLine(ls);
            for (ru.lct.heatnet.restrictions.Obstacle o : space.query(ls.getEnvelopeInternal())) {
                if (!o.isForbidden()) continue;
                org.locationtech.jts.geom.Geometry zone = o.geometry().buffer(space.requiredDistance(o));
                org.locationtech.jts.geom.Geometry inter = zone.intersection(ls);
                for (int i = 0; i < inter.getNumGeometries(); i++) {
                    org.locationtech.jts.geom.Geometry g = inter.getGeometryN(i);
                    if (g.isEmpty() || g.getLength() < 1e-6) continue;
                    double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                    for (Coordinate c : g.getCoordinates()) { double t = lil.project(c); mn = Math.min(mn, t); mx = Math.max(mx, t); }
                    out.add(new double[]{mn, mx});
                }
            }
            return out;
        }

        public List<Approach> approaches(ConnectionPoint cp) { return approaches.get(cp.id()); }
    }

    private final InputModel input;
    private final ExistingNetworkIndex netIndex;
    private final SolverConfig config;
    private final Map<ClearanceClass, ClassResources> resources = new EnumMap<>(ClearanceClass.class);
    private Map<JsonId, Double> separateDistance;
    private final java.util.concurrent.atomic.AtomicInteger candidatesEvaluated = new java.util.concurrent.atomic.AtomicInteger();
    private long graphBuildMs;

    public void countCandidate() { candidatesEvaluated.incrementAndGet(); }
    public int candidatesEvaluated() { return candidatesEvaluated.get(); }
    public long graphBuildMs() { return graphBuildMs; }
    public Map<String, Object> graphStats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        int nodes = 0, edges = 0, tieIns = 0;
        for (ClassResources r : resources.values()) { nodes += r.graph.size(); edges += r.graph.edgeCount(); tieIns += r.tieIns.size(); }
        m.put("graph_nodes", nodes);
        m.put("graph_edges", edges);
        m.put("tie_in_candidates", tieIns);
        m.put("candidate_count", candidatesEvaluated.get());
        m.put("routing_graph_time_ms", graphBuildMs);
        return m;
    }

    public SolverContext(InputModel input, SolverConfig config) {
        this.input = input;
        this.config = config;
        this.netIndex = new ExistingNetworkIndex(input);
    }

    public InputModel input() { return input; }
    public ExistingNetworkIndex netIndex() { return netIndex; }
    public SolverConfig config() { return config; }

    public synchronized ClassResources resources(ClearanceClass cls) {
        return resources.computeIfAbsent(cls, ClassResources::new);
    }

    /** Effective route length of the best separate tie-in per connection point (for ordering), +inf if none. */
    public synchronized Map<JsonId, Double> separateDistances() {
        if (separateDistance != null) return separateDistance;
        ClassResources r = resources(ClearanceClass.SMALL);
        Map<JsonId, Double> out = new HashMap<>();
        for (ConnectionPoint cp : input.connectionPoints()) {
            double best = Double.POSITIVE_INFINITY;
            for (Approach a : r.approaches(cp)) {
                PathSearch.Result res = new PathSearch(r.graph, null, null, config.turnPenalty, null).run(a.vertex());
                for (TieInCandidate t : r.tieIns)
                    if (res.reached(t.vertex())) best = Math.min(best, res.dist[t.vertex()] + a.finalLength());
                if (best < Double.POSITIVE_INFINITY) break;
            }
            out.put(cp.id(), best);
        }
        separateDistance = out;
        return out;
    }
}
