package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.config.ChamberCostTable;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.DiameterSpec;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.cost.CostSummary;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.SpecialPassage;
import ru.lct.heatnet.routing.DynamicObstacles;
import ru.lct.heatnet.routing.NavGraph;
import ru.lct.heatnet.routing.PathSearch;
import ru.lct.heatnet.routing.Route;
import ru.lct.heatnet.routing.RouteBuilder;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy rooted Steiner-forest heuristic: connection points are attached one by one, each to the attachment target
 * (existing chamber, new chamber on an existing line, existing new chamber, or a new junction chamber on an already
 * built link) that minimises the increase of the variant score with the full cost model (hydraulics, chambers,
 * tie-ins) evaluated on a tentative copy of the network. An optional improvement pass re-attaches each point.
 */
public final class ForestBuilder {

    private static final Logger log = LoggerFactory.getLogger(ForestBuilder.class);
    private static final double JUNCTION_END_MARGIN = 3.0;
    private static final double JUNCTION_SPECIAL_MARGIN = 8.0;

    private final SolverContext ctx;
    private final Strategy strategy;
    private final Map<JsonId, ClearanceClass> classFloor;
    private final SolverConfig cfg;

    private NewNetwork net = new NewNetwork();
    private DynamicObstacles dyn;
    private final Map<ClearanceClass, Map<Integer, Target>> targets = new EnumMap<>(ClearanceClass.class);
    private final Map<ClearanceClass, Map<Integer, Object[]>> discs = new EnumMap<>(ClearanceClass.class);
    private final Map<ClearanceClass, Map<String, Integer>> vertexByKey = new EnumMap<>(ClearanceClass.class);
    private final Map<ConnectionPoint, String> unconnected = new LinkedHashMap<>();
    private final java.util.Set<String> notes = new java.util.LinkedHashSet<>();
    private final CalculationTrace trace = new CalculationTrace();
    private double currentScore = 0;

    public ForestBuilder(SolverContext ctx, Strategy strategy, Map<JsonId, ClearanceClass> classFloor) {
        this.ctx = ctx;
        this.strategy = strategy;
        this.classFloor = classFloor == null ? Collections.emptyMap() : classFloor;
        this.cfg = ctx.config();
    }

    private ClearanceClass classOf(ConnectionPoint cp) {
        return classFloor.getOrDefault(cp.id(), ClearanceClass.SMALL);
    }

    public VariantResult build() {
        long t0 = System.currentTimeMillis();
        ClearanceClass maxCls = ClearanceClass.SMALL;
        for (ConnectionPoint cp : ctx.input().connectionPoints()) maxCls = maxCls.max(classOf(cp));
        dyn = new DynamicObstacles(cfg.newLineHardDistance, ctx.resources(maxCls).softSeparation);
        for (ClearanceClass cls : ClearanceClass.values()) {
            if (cls.ordinal() > maxCls.ordinal()) continue;
            boolean used = false;
            for (ConnectionPoint cp : ctx.input().connectionPoints()) if (classOf(cp) == cls) used = true;
            if (!used) continue;
            SolverContext.ClassResources r = ctx.resources(cls);
            r.graph.truncate(r.baseSize);
            targets.put(cls, new HashMap<>());
            discs.put(cls, new HashMap<>());
            vertexByKey.put(cls, new HashMap<>());
            for (TieInCandidate t : r.tieIns) {
                targets.get(cls).put(t.vertex(), Target.tieIn(t));
                discs.get(cls).put(t.vertex(), new Object[]{t.point(), 2.0 * dyn.softDistance(), null});
            }
        }
        List<ConnectionPoint> order = order();
        for (ConnectionPoint cp : order) attach(cp);
        if (strategy.improve() && cfg.improve) improve(order);
        List<Double> penalties = new ArrayList<>();
        for (ConnectionPoint cp : unconnected.keySet()) penalties.add(cp.flowTph());
        HydraulicCalculator.Result h = HydraulicCalculator.compute(net);
        if (!h.feasible()) notes.add("hydraulic infeasibility: " + h.problem());
        if (cfg.depthMode) {
            ru.lct.heatnet.depth.NetworkDepthPlanner.Result d = new ru.lct.heatnet.depth.NetworkDepthPlanner().plan(net);
            if (!d.feasible) notes.add("depth infeasibility: " + d.problem);
        }
        CostSummary summary = CostCalculator.summarize(net, penalties);
        explainFinalNetwork();
        log.info("strategy {}: {} connected, {} unconnected, {} ({} ms)", strategy, net.connectionNodes().size(), unconnected.size(), summary, System.currentTimeMillis() - t0);
        VariantResult vr = new VariantResult(strategy.name(), net, unconnected, summary, new ArrayList<>(notes));
        vr.setTrace(trace);
        return vr;
    }

    private List<ConnectionPoint> order() {
        List<ConnectionPoint> cps = new ArrayList<>(ctx.input().connectionPoints());
        switch (strategy.order()) {
            case NEAREST_FIRST: {
                Map<JsonId, Double> d = ctx.separateDistances();
                cps.sort(Comparator.comparingDouble(cp -> d.getOrDefault(cp.id(), Double.POSITIVE_INFINITY)));
                break;
            }
            case FARTHEST_FIRST: {
                Map<JsonId, Double> d = ctx.separateDistances();
                cps.sort(Comparator.comparingDouble((ConnectionPoint cp) -> {
                    double v = d.getOrDefault(cp.id(), Double.POSITIVE_INFINITY);
                    return v == Double.POSITIVE_INFINITY ? -1 : -v;
                }));
                break;
            }
            case LARGEST_FLOW_FIRST:
                cps.sort(Comparator.comparingDouble((ConnectionPoint cp) -> -cp.flowTph()));
                break;
            default:
                break;
        }
        return cps;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final class Attempt {
        final NewNetwork net;
        final CostSummary summary;
        final Approach approach;
        final Route route;
        final Target target;
        final List<Coordinate> coords;
        List<String> alternatives = new ArrayList<>();

        Attempt(NewNetwork net, CostSummary summary, Approach approach, Route route, Target target, List<Coordinate> coords) {
            this.net = net; this.summary = summary; this.approach = approach; this.route = route; this.target = target; this.coords = coords;
        }
    }

    private boolean attach(ConnectionPoint cp) {
        ClearanceClass cls = classOf(cp);
        SolverContext.ClassResources r = ctx.resources(cls);
        refreshJunctionTargets(cls);
        List<Approach> approaches = r.approaches(cp);
        String reason = approaches.isEmpty() ? "no feasible approach to the point from outside its ОКС polygon" : "no route to any attachment point";
        StringBuilder evaluation = new StringBuilder();
        Attempt best = null;
        // stage 1: top-K exact evaluation per approach; stage 2 (only if the point would stay unconnected): staged widening
        // over every reachable candidate within the safety budgets — a point is never declared unconnected while an
        // unexplored candidate remains within the budget
        for (boolean widen : new boolean[]{false, true}) {
            for (boolean relaxed : new boolean[]{false, true}) {
                if (relaxed && strategy.allowJunctions() && strategy.tieInPolicy() == Strategy.TieInPolicy.ANY) break; // nothing to relax
                for (Approach ap : approaches) {
                    best = evaluateApproach(cp, ap, r, relaxed, widen, evaluation);
                    if (best != null) break;
                }
                if (best != null) {
                    if (relaxed) notes.add("connection point " + cp.id() + ": strategy policy relaxed to keep the point connected");
                    if (widen) notes.add("connection point " + cp.id() + ": attachment found by the widened candidate search");
                    break;
                }
            }
            if (best != null) break;
        }
        if (best == null) {
            unconnected.put(cp, reason);
            trace.add("ROUTE_REJECTED", cp.id().text(), "unconnected", reason + (lastDepthRejection != null ? "; last vertical rejection: " + lastDepthRejection : ""),
                    CalculationTrace.inputs("approaches", approaches.size(), "details", evaluation.toString().trim()), null);
            log.info("  {}: UNCONNECTED ({}){}", cp.id(), reason, evaluation);
            return false;
        }
        commit(best);
        return true;
    }

    /** Diameter and depth decisions of the final network (recorded once per variant). */
    private void explainFinalNetwork() {
        for (NetLink l : net.links()) {
            DiameterSpec byFlow = DiameterTable.minByFlow(l.flow()).orElse(DiameterTable.largest());
            String why;
            if (l.du() == byFlow.du()) why = "minimum DU whose capacity covers the flow; max continuous length satisfied";
            else {
                boolean monotonic = false;
                for (NetLink c : l.child().children()) if (c.du() >= l.du()) monotonic = true;
                why = monotonic ? "DU" + byFlow.du() + " capacity is sufficient, but the DU must not decrease towards the existing network (downstream section is DU" + l.du() + ")"
                        : "DU" + byFlow.du() + " capacity is sufficient, but the maximum continuous length of that DU would be exceeded on a consumer path";
            }
            trace.add("DIAMETER_SELECTED", "link#" + l.id(), "DU" + l.du(), why,
                    CalculationTrace.inputs("flow_tph", Math.round(l.flow() * 1000) / 1000.0, "min_du_by_flow", byFlow.du(), "length_m", Math.round(l.length() * 10) / 10.0,
                            "from", l.child().kind() == NetNode.Kind.CONNECTION_POINT ? l.child().connectionPoint().id().text() : l.child().toString(), "to", l.parent().toString()), null);
            if (l.profile() != null) {
                for (ru.lct.heatnet.depth.DepthProfile.PlateauDecision d : l.profile().decisions()) {
                    for (ru.lct.heatnet.depth.CrossingDepthOption o : d.options) {
                        String type = o.method() == ru.lct.heatnet.depth.CrossingDepthOption.Method.BELOW ? "DEPTH_BELOW_SELECTED" : o.method() == ru.lct.heatnet.depth.CrossingDepthOption.Method.ABOVE ? "DEPTH_ABOVE_SELECTED" : "DEPTH_UNDER_SELECTED";
                        List<String> alts = new ArrayList<>();
                        if (o.obstacle().isUtility()) {
                            ru.lct.heatnet.depth.DepthInterval other = o.method() == ru.lct.heatnet.depth.CrossingDepthOption.Method.ABOVE ? o.obstacle().below() : o.obstacle().above();
                            alts.add((o.method() == ru.lct.heatnet.depth.CrossingDepthOption.Method.ABOVE ? "BELOW " : "ABOVE ") + (other.isEmpty() ? "impossible (min depth 0.7 m)" : other.toString()));
                        }
                        trace.add(type, "link#" + l.id(), o.method() + " " + o.obstacle().label() + " at " + Math.round(d.depth * 100) / 100.0 + " m",
                                o.obstacle().isUtility() ? "admissible depth " + o.interval() + "; the plateau depth closest to 3.0 m that fits the ramps is chosen" : "cover >= " + o.obstacle().rule().minDepthM() + " m under the object",
                                CalculationTrace.inputs("plateau_from_m", Math.round(d.from * 10) / 10.0, "plateau_to_m", Math.round(d.to * 10) / 10.0, "k_spec", o.obstacle().kSpec(),
                                        "new_pair_height_m", o.obstacle().newHeightM()), alts);
                    }
                }
            }
        }
    }

    /**
     * Best attachment of one approach: Dijkstra to all targets, exact evaluation of the best candidates by estimate,
     * plus the direct attachment. {@code widen} = stage 2: the evaluation limit doubles while nothing is feasible up to
     * {@link SolverConfig#maxCandidateEvaluations} / {@link SolverConfig#wideningTimeBudgetMs}.
     */
    private Attempt evaluateApproach(ConnectionPoint cp, Approach ap, SolverContext.ClassResources r, boolean relaxed, boolean widen, StringBuilder evaluation) {
        Map<Integer, Target> tg = targets.get(r.cls);
        Map<Integer, Object[]> dc = discs.get(r.cls);
        PathSearch.DynamicExemptionProvider dex = v -> {
            Object[] d = dc.get(v);
            return d == null ? null : Collections.singletonList(d);
        };
        DiameterSpec cpSpec = DiameterTable.minByFlow(cp.flowTph()).orElse(DiameterTable.largest());
        Attempt best = directAttach(ap, r, relaxed);
        PathSearch search = new PathSearch(r.graph, dyn, dex, cfg.turnPenalty, null).terminals(tg.keySet());
        PathSearch.Result res = search.run(ap.vertex());
        List<double[]> ranked = new ArrayList<>();
        for (Map.Entry<Integer, Target> e : tg.entrySet()) {
            int v = e.getKey();
            if (!res.reached(v)) continue;
            Target t = e.getValue();
            if (!allowedByPolicy(t, relaxed)) continue;
            ranked.add(new double[]{estimate(res.dist[v] + ap.finalLength(), t, cpSpec), v});
        }
        ranked.sort(Comparator.comparingDouble(x -> x[0]));
        RouteBuilder rb = new RouteBuilder(r.graph, dyn, dex, cfg.turnPenalty).terminals(tg.keySet());
        int evaluated = 0, noRoute = 0, rejected = 0, rerouted = 0;
        int limit = cfg.topK;
        int stage1Cap = cfg.topK * 8;
        long t0 = System.currentTimeMillis();
        boolean budgetExceeded = false;
        List<Attempt> considered = new ArrayList<>();
        List<String> rejectedNotes = new ArrayList<>();
        lastDepthRejection = null;
        for (double[] cand : ranked) {
            if (evaluated >= limit) {
                if (best != null) break;
                if (!widen && evaluated >= stage1Cap) break;
                if (evaluated >= cfg.maxCandidateEvaluations) { budgetExceeded = true; break; }
                if (widen && System.currentTimeMillis() - t0 > cfg.wideningTimeBudgetMs) { budgetExceeded = true; break; }
                limit = Math.min(limit * 2, widen ? cfg.maxCandidateEvaluations : stage1Cap); // staged widening while nothing is feasible
            }
            int v = (int) cand[1];
            Target t = tg.get(v);
            NavGraph.Heading cont = continuationAt(t);
            Route route = rb.buildFrom(res, v, ap.heading());
            if (route == null) { noRoute++; continue; }
            evaluated++;
            ctx.countCandidate();
            Attempt a = tryAttach(ap, route, t, dc.get(v));
            if (a == null && widen && cont != null && TURN_REJECTION.equals(lastRejection)) {
                // widened search only (keeps the baseline greedy choices bit-identical): the shortest arrival direction
                // cannot turn into the parent link -> bounded re-routing with the bad edge banned, so that a target is
                // not given up only because of the arrival direction of the shortest path
                Route alt = rb.build(ap.vertex(), v, ap.heading(), null, cont);
                if (alt != null) { rerouted++; a = tryAttach(ap, alt, t, dc.get(v)); if (a != null) route = alt; }
            }
            boolean feasible = a != null;
            if (a == null) { rejected++; if (rejectedNotes.size() < 3) rejectedNotes.add(t + " (" + lastRejection + ")"); }
            else {
                if (best == null || a.summary.score() < best.summary.score()) { if (best != null) considered.add(best); best = a; } else considered.add(a);
            }
            // depth mode: alternative horizontal routes avoiding the vertical crossings of the previous route (stage 1:
            // exactly depthRouteAlternatives edge-ban alternatives, as in the baseline). Only in the widened stage 2 the
            // number of alternatives grows 2 -> 4 -> 8 while every route of this candidate is vertically infeasible and
            // even alternatives avoid the crossed objects entirely (geometrically distinct corridor)
            if (cfg.depthMode) {
                java.util.Set<Long> banned = new java.util.HashSet<>();
                List<org.locationtech.jts.geom.Geometry> bannedGeoms = new ArrayList<>();
                Route prev = route;
                int altLimit = Math.max(0, cfg.depthRouteAlternatives);
                int alt = 0;
                while (alt < altLimit && prev != null) {
                    java.util.Set<Long> crossingEdges = verticalCrossingEdges(prev, r.graph);
                    if (crossingEdges.isEmpty()) break;
                    // geometrically distinct alternatives: odd alternatives cross the same objects elsewhere (the crossing
                    // edges of the previous route are banned), even alternatives avoid those objects entirely
                    banned.addAll(crossingEdges);
                    if (widen && alt % 2 == 1) for (org.locationtech.jts.geom.Geometry g : verticalCrossingGeometries(prev)) if (!bannedGeoms.contains(g)) bannedGeoms.add(g);
                    Route altRoute = rb.build(ap.vertex(), v, ap.heading(), banned, null, bannedGeoms);
                    if (altRoute == null) break;
                    Attempt b = tryAttach(ap, altRoute, t, dc.get(v));
                    if (b == null && widen && cont != null && TURN_REJECTION.equals(lastRejection)) {
                        Route alt2 = rb.build(ap.vertex(), v, ap.heading(), banned, cont, bannedGeoms);
                        if (alt2 != null) { rerouted++; b = tryAttach(ap, alt2, t, dc.get(v)); if (b != null) altRoute = alt2; }
                    }
                    alt++;
                    if (b != null) {
                        feasible = true;
                        if (best == null || b.summary.score() < best.summary.score()) { if (best != null) considered.add(best); best = b; } else considered.add(b);
                    }
                    if (widen && alt >= altLimit && !feasible && altLimit < cfg.depthRouteAlternativesMax) altLimit = Math.min(altLimit * 2, cfg.depthRouteAlternativesMax);
                    prev = altRoute;
                }
            }
        }
        evaluation.append(String.format(java.util.Locale.ROOT, " [approach %s%s%s: %d reached, %d without clean route, %d evaluated, %d rejected, %d rerouted%s%s]",
                ap.nearestBoundary() ? "nearest" : "fallback", relaxed ? "/relaxed" : "", widen ? "/widened" : "", ranked.size(), noRoute, evaluated, rejected, rerouted,
                budgetExceeded ? ", budget exhausted" : "",
                best != null && best.route.coords().size() == 2 && best.target.type == Target.Type.LINK_POINT ? ", direct" : ""));
        if (best != null) {
            considered.sort(Comparator.comparingDouble(x -> x.summary.score()));
            List<String> alts = new ArrayList<>();
            for (int i = 0; i < Math.min(3, considered.size()); i++) alts.add(considered.get(i).target + " -> score " + String.format(java.util.Locale.ROOT, "%.4f", considered.get(i).summary.score()));
            for (String rn : rejectedNotes) alts.add("rejected: " + rn);
            alts.add(String.format(java.util.Locale.ROOT, "candidates reached %d, evaluated %d, rejected %d%s", ranked.size(), evaluated, rejected, widen ? " (widened search)" : ""));
            best.alternatives = alts;
        }
        return best;
    }

    /** Direction of the flow path after a junction target (first segment of the parent link); null for tie-ins. */
    private NavGraph.Heading continuationAt(Target t) {
        if (t.type == Target.Type.LINK_POINT) {
            NetLink link = net.link(t.linkId);
            if (link == null) return null;
            List<Coordinate> cs = link.coords();
            double acc = 0;
            for (int i = 1; i < cs.size(); i++) {
                double seg = cs.get(i - 1).distance(cs.get(i));
                if (t.position <= acc + seg + 1e-9 || i == cs.size() - 1) {
                    if (seg < 1e-9) return null;
                    return new NavGraph.Heading((cs.get(i).x - cs.get(i - 1).x) / seg, (cs.get(i).y - cs.get(i - 1).y) / seg);
                }
                acc += seg;
            }
            return null;
        }
        if (t.type == Target.Type.NODE) {
            NetNode node = net.node(t.nodeId);
            if (node == null || node.parent() == null) return null;
            List<Coordinate> cs = node.parent().coords();
            if (cs.size() < 2) return null;
            double seg = cs.get(0).distance(cs.get(1));
            if (seg < 1e-9) return null;
            return new NavGraph.Heading((cs.get(1).x - cs.get(0).x) / seg, (cs.get(1).y - cs.get(0).y) / seg);
        }
        return null;
    }

    /**
     * Direct attachment: the final straight segment is extended beyond the free point until it meets an already
     * built link; a junction chamber is created there. Handles points whose exit corridor is occupied by a trunk.
     */
    private Attempt directAttach(Approach ap, SolverContext.ClassResources r, boolean relaxed) {
        if (!(strategy.allowJunctions() || relaxed) || !ap.hasFinalSegment() || dyn.isEmpty()) return null;
        Coordinate p = ap.target(), a = ap.freePoint();
        double[] u = ru.lct.heatnet.geo.GeometryUtils.unit(p, a);
        double pa = p.distance(a);
        double startT = ap.boundaryPoint() == null ? 0 : p.distance(ap.boundaryPoint());
        double maxDist = 30;
        // the ray runs from the own polygon boundary through A and 30 m beyond: a trunk hugging the building
        // (between the boundary and A) or lying just beyond A can be joined by the straight final segment
        Coordinate rayStart = ru.lct.heatnet.geo.GeometryUtils.offset(p, u, startT);
        Coordinate far = ru.lct.heatnet.geo.GeometryUtils.offset(a, u, maxDist);
        org.locationtech.jts.geom.LineSegment ray = new org.locationtech.jts.geom.LineSegment(rayStart, far);
        java.util.Set<Integer> ignoreOwn = ap.ownPolygon() == null ? Collections.emptySet() : Collections.singleton(ap.ownPolygon().index());
        Attempt best = null;
        for (DynamicObstacles.Seg sg : dyn.near(a, pa + maxDist)) {
            Coordinate x = ray.intersection(new org.locationtech.jts.geom.LineSegment(sg.a, sg.b));
            if (x == null) continue;
            double ang = ru.lct.heatnet.geo.GeometryUtils.acuteAngleDeg(u[0], u[1], sg.b.x - sg.a.x, sg.b.y - sg.a.y);
            if (ang < 30) continue;
            if (!(sg.owner instanceof Integer)) continue;
            NetLink link = net.link((Integer) sg.owner);
            if (link == null) continue;
            org.locationtech.jts.geom.LineString ls = ru.lct.heatnet.geo.GeometryUtils.line(link.coords());
            double pos = ru.lct.heatnet.geo.GeometryUtils.project(ls, x);
            if (pos < Math.max(link.reservedFromChild(), JUNCTION_END_MARGIN) || pos > link.length() - JUNCTION_END_MARGIN) continue;
            if (NewNetwork.insideSpecial(link, pos, JUNCTION_SPECIAL_MARGIN)) continue;
            Coordinate xl = ru.lct.heatnet.geo.GeometryUtils.extractPoint(ls, pos);
            if (xl.distance(a) < 0.05 || xl.distance(p) < 0.05) continue;
            if (r.space.insideAnyZone(xl, ignoreOwn)) continue;
            Object[] disc = new Object[]{xl, 2.0 * dyn.softDistance(), link.id()};
            Attempt at;
            if (p.distance(xl) < pa - 0.05) {
                // junction inside the approach corridor: the whole branch is the final straight segment P -> X
                ru.lct.heatnet.restrictions.SegmentCheck sc = r.space.check(p, xl, new Exemptions().ignore(ignoreOwn));
                if (!sc.valid()) continue;
                Approach direct = new Approach(ap.cp(), ap.ownPolygon(), ap.boundaryPoint(), xl, sc, ap.nearestBoundary());
                Route route = new Route(Collections.singletonList(xl), Collections.emptyList());
                at = tryAttach(direct, route, Target.linkPoint(link, pos), disc);
            } else {
                ru.lct.heatnet.restrictions.SegmentCheck sc = r.space.check(a, xl, Exemptions.NONE);
                if (!sc.valid()) continue;
                if (!dyn.allows(a, xl, Collections.singletonList(disc))) continue;
                Route route = new Route(java.util.Arrays.asList(a, xl), Collections.singletonList(sc));
                at = tryAttach(ap, route, Target.linkPoint(link, pos), disc);
            }
            if (at != null && (best == null || at.summary.score() < best.summary.score())) best = at;
        }
        return best;
    }

    private boolean allowedByPolicy(Target t, boolean relaxed) {
        if (t.type == Target.Type.TIE_IN) {
            if (relaxed) return true;
            if (strategy.tieInPolicy() == Strategy.TieInPolicy.NEW_CHAMBERS_ONLY && t.tieIn.kind() == TieInCandidate.Kind.EXISTING_CHAMBER) return false;
            if (strategy.tieInPolicy() == Strategy.TieInPolicy.EXISTING_CHAMBERS_ONLY && t.tieIn.kind() != TieInCandidate.Kind.EXISTING_CHAMBER) return false;
            return true;
        }
        return strategy.allowJunctions() || relaxed;
    }

    /** Rough score increase used to pre-rank candidates before exact evaluation. */
    private double estimate(double effLen, Target t, DiameterSpec cpSpec) {
        double fixed;
        switch (t.type) {
            case TIE_IN:
                if (t.tieIn.kind() == TieInCandidate.Kind.EXISTING_CHAMBER) fixed = ChamberCostTable.EXISTING_CHAMBER_TIE_IN_COST;
                else fixed = ChamberCostTable.newChamberCost(Math.max(cpSpec.du(), t.tieIn.line().diameter()));
                break;
            case LINK_POINT:
                fixed = ChamberCostTable.newChamberCost(cpSpec.du());
                break;
            default:
                fixed = 0;
        }
        if (strategy.tieInPolicy() == Strategy.TieInPolicy.PREFER_EXISTING_CHAMBERS && t.type == Target.Type.TIE_IN
                && t.tieIn.kind() == TieInCandidate.Kind.NEW_CHAMBER_ON_LINE) fixed += 5_000_000;
        return CostCalculator.score(effLen * cpSpec.costPerMeter() + fixed, effLen);
    }

    private Attempt tryAttach(Approach ap, Route route, Target t, Object[] targetDisc) {
        NewNetwork n = net.copy();
        NetNode parent;
        switch (t.type) {
            case TIE_IN: {
                TieInCandidate c = t.tieIn;
                parent = c.kind() == TieInCandidate.Kind.EXISTING_CHAMBER
                        ? n.rootAtExistingChamber(c.chamber(), c.existingAdjacency())
                        : n.rootOnExistingLine(c.line(), c.point(), c.existingAdjacency());
                if (parent.spareDegree() < 1) return reject("chamber degree");
                break;
            }
            case NODE: {
                parent = n.node(t.nodeId);
                if (parent == null || parent.spareDegree() < 1) return reject("chamber degree");
                break;
            }
            default: {
                NetLink link = n.link(t.linkId);
                if (link == null) return reject("link gone");
                if (t.position < link.reservedFromChild() + 1e-6 || t.position > link.length() - 1e-6) return reject("junction position");
                if (NewNetwork.insideSpecial(link, t.position, JUNCTION_SPECIAL_MARGIN)) return reject("junction inside a special section");
                parent = n.splitLink(link, t.position);
            }
        }
        if (ap.hasFinalSegment() && !dyn.allows(ap.target(), ap.freePoint(), targetDisc == null ? null : Collections.singletonList(targetDisc))) return reject("final segment blocked by the new network");
        NetNode cpNode = n.addConnectionNode(ap.cp());
        List<Coordinate> coords = new ArrayList<>();
        List<SpecialPassage> passages = new ArrayList<>();
        double offset = 0;
        if (ap.hasFinalSegment()) {
            coords.add(ap.target());
            passages.addAll(ap.finalSegment().passages()); // already measured from P (traversal P -> A)
            offset = ap.finalLength();
        }
        coords.addAll(route.coords());
        for (SpecialPassage p : route.passagesAlongRoute()) passages.add(p.shifted(offset));
        if (coords.get(coords.size() - 1).distance(parent.coord()) > 1e-6) return reject("route does not end at the target");
        coords = cleanCoords(coords);
        // turn rule along the flow path through the chamber: last branch segment -> first segment of the parent link
        NetLink parentLink = parent.parent();
        if (parentLink != null && coords.size() >= 2 && parentLink.coords().size() >= 2) {
            Coordinate prev = coords.get(coords.size() - 2), at = coords.get(coords.size() - 1), next = parentLink.coords().get(1);
            if (ru.lct.heatnet.geo.GeometryUtils.turnAngleDeg(prev, at, next) > ru.lct.heatnet.config.Constants.MAX_TURN_ANGLE_DEG + 1e-6) return reject(TURN_REJECTION);
        }
        NetLink l = n.addLink(cpNode, parent, coords, passages);
        l.setApproachInfo(ap.hasFinalSegment() ? ap.finalLength() + 0.5 : 0,
                ap.ownPolygon() == null ? -1 : ap.ownPolygon().index());
        HydraulicCalculator.Result h = HydraulicCalculator.compute(n);
        if (!h.feasible()) return reject("hydraulics: " + h.problem());
        if (cfg.depthMode) {
            // vertical profile of the whole tentative network (DU-dependent); vertically infeasible candidates are rejected
            ru.lct.heatnet.depth.NetworkDepthPlanner.Result d = new ru.lct.heatnet.depth.NetworkDepthPlanner().plan(n);
            if (!d.feasible) { lastDepthRejection = d.problem; return reject("vertical: " + d.problem); }
        }
        CostSummary s = CostCalculator.summarize(n, Collections.emptyList());
        lastRejection = null;
        return new Attempt(n, s, ap, route, t, coords);
    }

    private String lastDepthRejection;
    /** Reason of the last rejected exact evaluation (explainability / diagnostics). */
    private String lastRejection;

    static final String TURN_REJECTION = "turn > 90° through the chamber";

    private Attempt reject(String why) { lastRejection = why; return null; }

    /** Metric geometries of the objects with a vertical rule crossed by the route (in route order, no duplicates). */
    private static List<org.locationtech.jts.geom.Geometry> verticalCrossingGeometries(Route route) {
        List<org.locationtech.jts.geom.Geometry> out = new ArrayList<>();
        for (SpecialPassage p : route.passagesAlongRoute()) {
            if (!ru.lct.heatnet.config.DepthRules.forType(p.obstacle().rule().type()).isPresent()) continue;
            org.locationtech.jts.geom.Geometry g = p.obstacle().geometry();
            if (!out.contains(g)) out.add(g);
        }
        return out;
    }

    /** Directed graph edges of the route (vertices matched by coordinate) whose segment crosses an object with a vertical rule. */
    private static java.util.Set<Long> verticalCrossingEdges(Route route, NavGraph graph) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        List<Coordinate> cs = route.coords();
        for (int i = 1; i < cs.size(); i++) {
            boolean vertical = false;
            for (SpecialPassage p : route.segments().get(i - 1).passages())
                if (ru.lct.heatnet.config.DepthRules.forType(p.obstacle().rule().type()).isPresent()) vertical = true;
            if (!vertical) continue;
            int u = graph.indexOf(cs.get(i - 1)), v = graph.indexOf(cs.get(i));
            if (u >= 0 && v >= 0) { out.add(PathSearch.edgeKey(u, v)); out.add(PathSearch.edgeKey(v, u)); }
        }
        return out;
    }

    /** Removes duplicate and exactly collinear interior vertices (does not change the geometry). */
    static List<Coordinate> cleanCoords(List<Coordinate> in) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : in) if (out.isEmpty() || out.get(out.size() - 1).distance(c) > 1e-6) out.add(c);
        if (out.size() < 3) return out;
        List<Coordinate> res = new ArrayList<>();
        res.add(out.get(0));
        for (int i = 1; i < out.size() - 1; i++) {
            if (ru.lct.heatnet.geo.GeometryUtils.turnAngleDeg(res.get(res.size() - 1), out.get(i), out.get(i + 1)) > 1e-4) res.add(out.get(i));
        }
        res.add(out.get(out.size() - 1));
        return res;
    }

    private void commit(Attempt a) {
        net = a.net;
        double before = currentScore;
        currentScore = a.summary.score();
        rebuildDynamic();
        String targetKind = a.target.type == Target.Type.TIE_IN ? (a.target.tieIn.kind() == TieInCandidate.Kind.EXISTING_CHAMBER ? "existing chamber " + a.target.tieIn.chamber().id() : "new chamber on line " + a.target.tieIn.line().id())
                : a.target.type == Target.Type.NODE ? "existing junction chamber" : "new junction chamber on link#" + a.target.linkId;
        trace.add(a.target.type == Target.Type.TIE_IN ? "TIE_IN_SELECTED" : "ROUTE_SELECTED", a.approach.cp().id().text(), targetKind,
                "lowest variant score among the evaluated attachment candidates (" + (a.target.type == Target.Type.TIE_IN ? "direct attachment to the existing network" : "joint connection via the new network") + ")",
                CalculationTrace.inputs("route_length_m", Math.round(a.route.length() * 10) / 10.0, "route_vertices", a.route.coords().size(),
                        "score_before", Math.round(before * 10000) / 10000.0, "score_after", Math.round(currentScore * 10000) / 10000.0,
                        "boundary_point", a.approach.nearestBoundary() ? "nearest" : "next feasible", "special_passages", a.route.passagesAlongRoute().size()),
                a.alternatives);
        if (a.target.type == Target.Type.LINK_POINT || (a.target.type == Target.Type.TIE_IN && a.target.tieIn.kind() == TieInCandidate.Kind.NEW_CHAMBER_ON_LINE))
            trace.add("CHAMBER_CREATED", a.approach.cp().id().text(), a.target.type == Target.Type.LINK_POINT ? "junction chamber" : "tie-in chamber on the existing line",
                    a.target.type == Target.Type.LINK_POINT ? "branching is allowed only in a heat chamber" : "no existing chamber within 10 m with a spare adjacency slot", null, null);
        if (!a.approach.nearestBoundary()) notes.add("connection point " + a.approach.cp().id() + ": final segment uses a non-nearest boundary point");
        log.info("  {} -> {} via {} (route {} m, {} vertices); score {}", a.approach.cp().id(), a.target, a.approach.nearestBoundary() ? "nearest boundary" : "fallback boundary",
                Math.round(a.route.length()), a.route.coords().size(), String.format(java.util.Locale.ROOT, "%.4f", currentScore));
    }

    /** Rebuilds the junction-target map of the class graph from the current network (adds missing vertices). */
    private void refreshJunctionTargets(ClearanceClass cls) {
        SolverContext.ClassResources r = ctx.resources(cls);
        Map<Integer, Target> tg = targets.get(cls);
        Map<Integer, Object[]> dc = discs.get(cls);
        Map<String, Integer> keys = vertexByKey.get(cls);
        // drop old dynamic targets
        tg.entrySet().removeIf(e -> e.getValue().type != Target.Type.TIE_IN);
        dc.entrySet().removeIf(e -> !tg.containsKey(e.getKey()));
        double disc = 2.0 * dyn.softDistance();
        for (NetNode node : net.nodes()) {
            if (node.kind() == NetNode.Kind.NEW_CHAMBER_JUNCTION && node.spareDegree() >= 1) {
                int v = vertexFor(r, keys, node.coord(), disc);
                tg.put(v, Target.node(node));
                dc.put(v, new Object[]{node.coord(), disc, null});
            }
        }
        for (NetLink link : net.links()) {
            for (double pos : junctionPositions(link)) {
                Coordinate c = CostCalculator.sub(link.coords(), pos, pos).get(0);
                if (r.space.insideAnyZone(c)) continue;
                int v = vertexFor(r, keys, c, disc);
                if (tg.containsKey(v)) continue;
                tg.put(v, Target.linkPoint(link, pos));
                dc.put(v, new Object[]{c, disc, link.id()});
            }
        }
    }

    private int vertexFor(SolverContext.ClassResources r, Map<String, Integer> keys, Coordinate c, double disc) {
        String key = Math.round(c.x * 1000) + ":" + Math.round(c.y * 1000);
        Integer v = keys.get(key);
        if (v != null && v < r.graph.size()) return v;
        int nv = r.graph.addVertex(c, Exemptions.NONE, cfg.terminalRadius * 0.5, null);
        keys.put(key, nv);
        return nv;
    }

    private List<Double> junctionPositions(NetLink link) {
        List<Double> out = new ArrayList<>();
        double L = link.length();
        double lo = Math.max(link.reservedFromChild(), JUNCTION_END_MARGIN), hi = L - JUNCTION_END_MARGIN;
        if (hi <= lo) return out;
        double acc = 0;
        List<Coordinate> cs = link.coords();
        for (int i = 1; i < cs.size() - 1; i++) {
            acc += cs.get(i - 1).distance(cs.get(i));
            if (acc >= lo && acc <= hi) out.add(acc);
        }
        for (double p = Math.ceil(lo / cfg.junctionStep) * cfg.junctionStep; p <= hi; p += cfg.junctionStep) {
            boolean dup = false;
            for (double q : out) if (Math.abs(q - p) < cfg.junctionStep * 0.3) { dup = true; break; }
            if (!dup) out.add(p);
        }
        out.removeIf(p -> NewNetwork.insideSpecial(link, p, JUNCTION_SPECIAL_MARGIN));
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Re-attaches every connected point (bounded number of passes); keeps a change only if the score strictly improves. */
    private void improve(List<ConnectionPoint> order) {
        for (int pass = 0; pass < Math.max(1, cfg.improvePasses); pass++) {
            if (!improvePass(order)) break;
        }
        currentScore = CostCalculator.summarize(net, Collections.emptyList()).score();
    }

    /** One leaf re-attachment pass; returns true if anything improved. */
    private boolean improvePass(List<ConnectionPoint> order) {
        boolean improved = false;
        for (ConnectionPoint cp : order) {
            NetNode leaf = null;
            for (NetNode n : net.connectionNodes()) if (n.connectionPoint() == cp) leaf = n;
            if (leaf == null) continue;
            NewNetwork backup = net.copy();
            double before = CostCalculator.summarize(net, Collections.emptyList()).score();
            net.removeLeaf(leaf);
            rebuildDynamic();
            boolean ok = attach(cp);
            double after = ok ? CostCalculator.summarize(net, Collections.emptyList()).score() : Double.POSITIVE_INFINITY;
            if (!ok || after >= before - 1e-9) {
                net = backup;
                unconnected.remove(cp);
                rebuildDynamic();
            } else {
                improved = true;
                log.info("  improvement: {} re-attached, score {} -> {}", cp.id(), String.format(java.util.Locale.ROOT, "%.4f", before), String.format(java.util.Locale.ROOT, "%.4f", after));
            }
        }
        return improved;
    }

    private void rebuildDynamic() {
        dyn.clear();
        for (NetLink l : net.links()) dyn.addPolyline(l.coords(), l.id());
    }

    public NewNetwork network() { return net; }
}
