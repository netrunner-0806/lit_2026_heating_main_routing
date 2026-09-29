package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.SegmentCheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a Dijkstra vertex path into a clean {@link Route}: greedy shortcutting (a vertex is removed only if the
 * direct segment stays valid against static and dynamic obstacles and the turn angles stay <= 90°), then a final
 * turn-angle check. If a turn > 90° cannot be removed, the search is repeated with the offending edge banned.
 */
public final class RouteBuilder {

    private final NavGraph graph;
    private final DynamicObstacles dynamic;
    private final PathSearch.DynamicExemptionProvider dynEx;
    private final double turnPenalty;
    private Set<Integer> terminals = java.util.Collections.emptySet();
    private List<org.locationtech.jts.geom.Geometry> bannedGeometries;

    public RouteBuilder terminals(Set<Integer> t) { this.terminals = t == null ? java.util.Collections.emptySet() : t; return this; }

    public RouteBuilder(NavGraph graph, DynamicObstacles dynamic, PathSearch.DynamicExemptionProvider dynEx, double turnPenalty) {
        this.graph = graph;
        this.dynamic = dynamic;
        this.dynEx = dynEx;
        this.turnPenalty = turnPenalty;
    }

    /**
     * Builds a route from source to target. {@code headingAtSource} is the direction of the fixed segment preceding
     * the source (null if none). Returns null if no valid route exists.
     */
    public Route build(int source, int target, NavGraph.Heading headingAtSource) { return build(source, target, headingAtSource, null, null); }

    /** Builds a route with the given directed edges excluded (used for depth-aware horizontal alternatives). */
    public Route build(int source, int target, NavGraph.Heading headingAtSource, Set<Long> initiallyBanned) { return build(source, target, headingAtSource, initiallyBanned, null); }

    /**
     * Builds a route with the given directed edges excluded. {@code continuationAtTarget} is the direction of the
     * flow path after the target (first segment of the parent link at a junction), null if unconstrained: the turn
     * from the last route segment into it must be <= 90°, otherwise the last edge is banned and the search repeats
     * (bounded), so that a target is not given up only because the shortest arrival direction is unusable.
     */
    public Route build(int source, int target, NavGraph.Heading headingAtSource, Set<Long> initiallyBanned, NavGraph.Heading continuationAtTarget) {
        return build(source, target, headingAtSource, initiallyBanned, continuationAtTarget, null);
    }

    /** As above; additionally no segment of the route may intersect one of {@code bannedGeometries} (avoid an obstacle entirely). */
    public Route build(int source, int target, NavGraph.Heading headingAtSource, Set<Long> initiallyBanned, NavGraph.Heading continuationAtTarget,
                       List<org.locationtech.jts.geom.Geometry> bannedGeometries) {
        Set<Long> banned = new HashSet<>();
        if (initiallyBanned != null) banned.addAll(initiallyBanned);
        this.bannedGeometries = bannedGeometries == null || bannedGeometries.isEmpty() ? null : bannedGeometries;
        for (int attempt = 0; attempt < MAX_REROUTE_ATTEMPTS; attempt++) {
            PathSearch search = new PathSearch(graph, dynamic, dynEx, turnPenalty, banned).terminals(terminals).bannedGeometries(this.bannedGeometries);
            PathSearch.Result r = search.run(source, java.util.Collections.singleton(target));
            int[] path = PathSearch.pathTo(r, target);
            if (path == null) return null;
            Route route = finish(path, headingAtSource, continuationAtTarget);
            if (route != null) return route;
            // find the first bad turn and ban the outgoing edge there (for a bad final turn: the last edge)
            List<Coordinate> cs = new ArrayList<>();
            for (int v : path) cs.add(graph.coord(v));
            int bad = firstBadTurn(cs, headingAtSource, continuationAtTarget);
            if (bad < 0) return null;
            banned.add(PathSearch.edgeKey(path[bad], path[bad + 1]));
        }
        return null;
    }

    /** Bound on re-routing attempts after banning an edge with an unusable turn (safety limit). */
    public static final int MAX_REROUTE_ATTEMPTS = 6;

    /** Builds the route from an existing search result (multi-target evaluation). */
    public Route buildFrom(PathSearch.Result r, int target, NavGraph.Heading headingAtSource) { return buildFrom(r, target, headingAtSource, null); }

    /** Builds the route from an existing search result; null if the (shortcut) path violates a turn constraint. */
    public Route buildFrom(PathSearch.Result r, int target, NavGraph.Heading headingAtSource, NavGraph.Heading continuationAtTarget) {
        this.bannedGeometries = null;
        int[] path = PathSearch.pathTo(r, target);
        if (path == null) return null;
        return finish(path, headingAtSource, continuationAtTarget);
    }

    private Route finish(int[] path, NavGraph.Heading heading, NavGraph.Heading continuation) {
        List<Integer> vs = new ArrayList<>();
        for (int v : path) vs.add(v);
        List<Integer> simplified = shortcut(vs, heading, continuation);
        List<Coordinate> cs = new ArrayList<>();
        for (int v : simplified) cs.add(graph.coord(v));
        if (firstBadTurn(cs, heading, continuation) >= 0) return null;
        List<SegmentCheck> checks = new ArrayList<>();
        for (int i = 1; i < simplified.size(); i++) {
            int u = simplified.get(i - 1), v = simplified.get(i);
            SegmentCheck sc = graph.space().check(graph.coord(u), graph.coord(v), Exemptions.merge(graph.exemptions(u), graph.exemptions(v)));
            if (!sc.valid()) return null;
            checks.add(sc);
        }
        return new Route(cs, checks);
    }

    /** Index of the vertex with the first turn > 90° (the edge leaving it is banned); for a bad turn into the continuation: the last edge's start. */
    private int firstBadTurn(List<Coordinate> cs, NavGraph.Heading heading, NavGraph.Heading continuation) {
        for (int i = 0; i + 1 < cs.size(); i++) {
            Coordinate prev;
            if (i == 0) {
                if (heading == null) continue;
                prev = new Coordinate(cs.get(0).x - heading.dx, cs.get(0).y - heading.dy);
            } else {
                prev = cs.get(i - 1);
            }
            if (GeometryUtils.turnAngleDeg(prev, cs.get(i), cs.get(i + 1)) > Constants.MAX_TURN_ANGLE_DEG + 1e-6) return i;
        }
        if (continuation != null && cs.size() >= 2) {
            Coordinate at = cs.get(cs.size() - 1);
            Coordinate next = new Coordinate(at.x + continuation.dx, at.y + continuation.dy);
            if (GeometryUtils.turnAngleDeg(cs.get(cs.size() - 2), at, next) > Constants.MAX_TURN_ANGLE_DEG + 1e-6) return cs.size() - 2;
        }
        return -1;
    }

    private List<Integer> shortcut(List<Integer> vs, NavGraph.Heading heading, NavGraph.Heading continuation) {
        if (vs.size() <= 2) return vs;
        List<Integer> out = new ArrayList<>();
        int i = 0;
        out.add(vs.get(0));
        while (i < vs.size() - 1) {
            int best = i + 1;
            for (int j = vs.size() - 1; j > i + 1; j--) {
                if (segmentOk(vs.get(i), vs.get(j)) && turnsOk(out, vs, i, j, heading, continuation)) { best = j; break; }
            }
            out.add(vs.get(best));
            i = best;
        }
        return out;
    }

    private boolean turnsOk(List<Integer> out, List<Integer> vs, int i, int j, NavGraph.Heading heading, NavGraph.Heading continuation) {
        Coordinate ci = graph.coord(vs.get(i)), cj = graph.coord(vs.get(j));
        Coordinate prev = null;
        if (out.size() >= 2) prev = graph.coord(out.get(out.size() - 2));
        else if (heading != null) prev = new Coordinate(ci.x - heading.dx, ci.y - heading.dy);
        if (prev != null && GeometryUtils.turnAngleDeg(prev, ci, cj) > Constants.MAX_TURN_ANGLE_DEG + 1e-6) return false;
        if (j + 1 < vs.size()) {
            Coordinate next = graph.coord(vs.get(j + 1));
            if (GeometryUtils.turnAngleDeg(ci, cj, next) > Constants.MAX_TURN_ANGLE_DEG + 1e-6) return false;
        } else if (continuation != null) {
            Coordinate next = new Coordinate(cj.x + continuation.dx, cj.y + continuation.dy);
            if (GeometryUtils.turnAngleDeg(ci, cj, next) > Constants.MAX_TURN_ANGLE_DEG + 1e-6) return false;
        }
        return true;
    }

    private boolean segmentOk(int u, int v) {
        Coordinate cu = graph.coord(u), cv = graph.coord(v);
        if (bannedGeometries != null && PathSearch.crossesAny(bannedGeometries, cu, cv)) return false; // a shortcut must not jump across a banned obstacle
        SegmentCheck sc = graph.space().check(cu, cv, Exemptions.merge(graph.exemptions(u), graph.exemptions(v)));
        if (!sc.valid()) return false;
        if (dynamic != null && !dynamic.isEmpty()) {
            List<Object[]> ex = null;
            if (dynEx != null) {
                List<Object[]> eu = dynEx.discs(u), ev = dynEx.discs(v);
                if (eu != null || ev != null) {
                    ex = new ArrayList<>();
                    if (eu != null) ex.addAll(eu);
                    if (ev != null) ex.addAll(ev);
                }
            }
            return dynamic.allows(cu, cv, ex);
        }
        return true;
    }
}
