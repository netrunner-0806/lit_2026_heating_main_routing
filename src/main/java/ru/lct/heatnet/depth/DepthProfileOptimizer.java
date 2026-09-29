package ru.lct.heatnet.depth;

import ru.lct.heatnet.config.DepthRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Builds the minimum-cost feasible depth profile of one physical route (a chain of links traversed from the
 * consumer towards the existing network):
 * <ol>
 *   <li>special sections of crossed objects are merged into constant-depth plateaus (overlaps form one plateau);</li>
 *   <li>for every plateau the finite ABOVE / BELOW / UNDER choices of its objects are enumerated and their depth
 *   intervals intersected (empty = impossible combination);</li>
 *   <li>combinations over plateaus are enumerated (bounded) and checked by interval propagation under the slope
 *   limit, including the fixed depths at the route ends (tie-in / junction);</li>
 *   <li>plateau depths are chosen closest to the normal depth 3.0 m; between plateaus the profile returns to
 *   3.0 m when the ramps and a minimal run fit into the gap, otherwise the altered depth is kept / bridged by a
 *   single ramp;</li>
 *   <li>the cheapest combination wins (then least deviation from 3.0 m, then fewest transitions).</li>
 * </ol>
 * Depths are continuous: no 0.5 m grid.
 */
public final class DepthProfileOptimizer {

    /** Minimal length of a run at the normal depth between two ramps for a return to 3.0 m to be worthwhile, m. */
    public static final double MIN_BASELINE_RUN_M = 5.0;
    private static final int MAX_ENUMERATED_COMBOS = 4096;

    public static final class Result {
        public final boolean feasible;
        public final DepthProfile profile;
        public final String reason;
        public final double extraCost;

        Result(boolean feasible, DepthProfile profile, String reason, double extraCost) {
            this.feasible = feasible; this.profile = profile; this.reason = reason; this.extraCost = extraCost;
        }

        public static Result infeasible(String reason) { return new Result(false, null, reason, Double.POSITIVE_INFINITY); }
    }

    /** Merged constant-depth plateau with its admissible depth options. */
    static final class Plateau {
        final double from, to;
        final List<VerticalObstacle> obstacles = new ArrayList<>();
        final List<List<CrossingDepthOption>> options = new ArrayList<>();  // one list of per-object choices per option
        final List<DepthInterval> intervals = new ArrayList<>();
        Plateau(double from, double to) { this.from = from; this.to = to; }
        double length() { return to - from; }
    }

    private final double maxSlope;
    private final double target;
    private final double minRun;

    public DepthProfileOptimizer() { this(DepthRules.MAX_SLOPE, DepthRules.NORMAL_DEPTH_M, MIN_BASELINE_RUN_M); }

    public DepthProfileOptimizer(double maxSlope, double targetDepth, double minRun) {
        this.maxSlope = maxSlope;
        this.target = targetDepth;
        this.minRun = minRun;
    }

    /**
     * @param length      horizontal route length, m
     * @param obstacles   crossed objects with their special sections along the route
     * @param startFixed  depth required at s = 0 (null = free, prefers the normal depth)
     * @param endFixed    depth required at s = length (null = free)
     * @param costPerMetre construction cost per metre at position s (for the extra-cost objective)
     */
    public Result optimize(double length, List<VerticalObstacle> obstacles, Double startFixed, Double endFixed, DoubleUnaryOperator costPerMetre) {
        List<Plateau> plateaus = buildPlateaus(length, obstacles);
        for (Plateau p : plateaus) {
            if (p.options.isEmpty()) return Result.infeasible("no feasible vertical combination for " + p.obstacles);
        }
        long combos = 1;
        for (Plateau p : plateaus) { combos *= p.options.size(); if (combos > MAX_ENUMERATED_COMBOS) break; }
        List<int[]> choiceSets = combos <= MAX_ENUMERATED_COMBOS ? enumerate(plateaus) : greedy(plateaus);
        Result best = null;
        double[] bestKey = null;
        for (int[] choice : choiceSets) {
            Candidate c = solve(length, plateaus, choice, startFixed, endFixed, costPerMetre);
            if (c == null) continue;
            double[] key = {c.extraCost, c.deviation, c.transitions};
            if (best == null || lexLess(key, bestKey)) {
                best = new Result(true, c.profile, null, c.extraCost);
                bestKey = key;
            }
        }
        if (best == null) return Result.infeasible(plateaus.isEmpty() ? "route ends cannot be connected within the slope limit"
                : "no plateau combination satisfies the slope limit between crossings and route ends");
        return best;
    }

    private static boolean lexLess(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i] - 1e-9) return true;
            if (a[i] > b[i] + 1e-9) return false;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------

    static List<Plateau> buildPlateaus(double length, List<VerticalObstacle> obstacles) {
        List<VerticalObstacle> sorted = new ArrayList<>(obstacles);
        sorted.sort((a, b) -> Double.compare(a.from(), b.from()));
        List<Plateau> out = new ArrayList<>();
        for (VerticalObstacle o : sorted) {
            double f = Math.max(0, o.from()), t = Math.min(length, o.to());
            if (t - f < 1e-6) continue;
            Plateau last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && f <= last.to + 1e-6) {
                Plateau merged = new Plateau(last.from, Math.max(last.to, t));
                merged.obstacles.addAll(last.obstacles);
                merged.obstacles.add(o);
                out.set(out.size() - 1, merged);
            } else {
                Plateau p = new Plateau(f, t);
                p.obstacles.add(o);
                out.add(p);
            }
        }
        for (Plateau p : out) enumerateOptions(p);
        return out;
    }

    /** All non-empty intersections of per-object ABOVE/BELOW/UNDER choices, de-duplicated by interval. */
    private static void enumerateOptions(Plateau p) {
        List<List<CrossingDepthOption>> perObject = new ArrayList<>();
        for (VerticalObstacle o : p.obstacles) {
            List<CrossingDepthOption> ch = new ArrayList<>();
            if (o.isUtility()) {
                DepthInterval a = clamp(o.above()), b = clamp(o.below());
                if (!a.isEmpty()) ch.add(new CrossingDepthOption(o, CrossingDepthOption.Method.ABOVE, a));
                if (!b.isEmpty()) ch.add(new CrossingDepthOption(o, CrossingDepthOption.Method.BELOW, b));
            } else {
                DepthInterval u = clamp(o.passUnder());
                if (!u.isEmpty()) ch.add(new CrossingDepthOption(o, CrossingDepthOption.Method.UNDER, u));
            }
            perObject.add(ch);
        }
        List<List<CrossingDepthOption>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (List<CrossingDepthOption> ch : perObject) {
            List<List<CrossingDepthOption>> next = new ArrayList<>();
            for (List<CrossingDepthOption> c : combos) for (CrossingDepthOption o : ch) { List<CrossingDepthOption> n = new ArrayList<>(c); n.add(o); next.add(n); }
            combos = next;
            if (combos.size() > 512) break; // pruning for very large overlap groups
        }
        for (List<CrossingDepthOption> c : combos) {
            DepthInterval iv = new DepthInterval(DepthRules.MIN_DEPTH_M, DepthRules.SANITY_MAX_DEPTH_M);
            for (CrossingDepthOption o : c) iv = iv.intersect(o.interval());
            if (iv.isEmpty()) continue;
            boolean dup = false;
            for (DepthInterval e : p.intervals) if (Math.abs(e.lo - iv.lo) < 1e-9 && Math.abs(e.hi - iv.hi) < 1e-9) { dup = true; break; }
            if (dup) continue;
            p.options.add(c);
            p.intervals.add(iv);
        }
        // prefer options whose interval is closest to the normal depth first (helps the greedy fallback)
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < p.intervals.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(Math.abs(p.intervals.get(a).closestTo(DepthRules.NORMAL_DEPTH_M) - DepthRules.NORMAL_DEPTH_M),
                Math.abs(p.intervals.get(b).closestTo(DepthRules.NORMAL_DEPTH_M) - DepthRules.NORMAL_DEPTH_M)));
        List<List<CrossingDepthOption>> so = new ArrayList<>();
        List<DepthInterval> si = new ArrayList<>();
        for (int i : order) { so.add(p.options.get(i)); si.add(p.intervals.get(i)); }
        p.options.clear(); p.options.addAll(so);
        p.intervals.clear(); p.intervals.addAll(si);
    }

    private static DepthInterval clamp(DepthInterval iv) {
        return iv.intersect(new DepthInterval(DepthRules.MIN_DEPTH_M, DepthRules.SANITY_MAX_DEPTH_M));
    }

    private static List<int[]> enumerate(List<Plateau> plateaus) {
        List<int[]> out = new ArrayList<>();
        int n = plateaus.size();
        int[] idx = new int[n];
        if (n == 0) { out.add(idx); return out; }
        while (true) {
            out.add(idx.clone());
            int k = n - 1;
            while (k >= 0) {
                idx[k]++;
                if (idx[k] < plateaus.get(k).options.size()) break;
                idx[k] = 0;
                k--;
            }
            if (k < 0) break;
        }
        return out;
    }

    /** Fallback for very many plateaus: the closest-to-normal option everywhere plus one-change neighbours. */
    private static List<int[]> greedy(List<Plateau> plateaus) {
        List<int[]> out = new ArrayList<>();
        int n = plateaus.size();
        out.add(new int[n]);
        for (int i = 0; i < n; i++) {
            for (int o = 1; o < plateaus.get(i).options.size(); o++) { int[] c = new int[n]; c[i] = o; out.add(c); }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final class Candidate {
        DepthProfile profile;
        double extraCost, deviation;
        int transitions;
    }

    private Candidate solve(double length, List<Plateau> plateaus, int[] choice, Double startFixed, Double endFixed, DoubleUnaryOperator cpm) {
        int n = plateaus.size();
        DepthInterval free = new DepthInterval(DepthRules.MIN_DEPTH_M, DepthRules.SANITY_MAX_DEPTH_M);
        // forward reachable sets
        DepthInterval[] reach = new DepthInterval[n];
        DepthInterval prev = startFixed == null ? free : new DepthInterval(startFixed, startFixed);
        double prevPos = 0;
        for (int i = 0; i < n; i++) {
            Plateau p = plateaus.get(i);
            double gap = p.from - prevPos;
            reach[i] = plateaus.get(i).intervals.get(choice[i]).intersect(prev.expand(maxSlope * gap + 1e-9));
            if (reach[i].isEmpty()) return null;
            prev = reach[i];
            prevPos = p.to;
        }
        DepthInterval endSet = (endFixed == null ? free : new DepthInterval(endFixed, endFixed)).intersect(prev.expand(maxSlope * (length - prevPos) + 1e-9));
        if (endSet.isEmpty()) return null;
        // backward assignment: closest to the target depth, constrained by the next depth
        double[] depth = new double[n];
        double endDepth = endFixed != null ? endFixed : endSet.closestTo(target);
        double nextDepth = endDepth, nextPos = length;
        for (int i = n - 1; i >= 0; i--) {
            Plateau p = plateaus.get(i);
            double gap = nextPos - p.to;
            DepthInterval allowed = reach[i].intersect(new DepthInterval(nextDepth - maxSlope * gap - 1e-9, nextDepth + maxSlope * gap + 1e-9));
            if (allowed.isEmpty()) return null;
            depth[i] = allowed.closestTo(target);
            nextDepth = depth[i];
            nextPos = p.from;
        }
        double startDepth;
        if (startFixed != null) startDepth = startFixed;
        else if (n == 0) startDepth = endFixed != null ? free.intersect(new DepthInterval(endFixed - maxSlope * length, endFixed + maxSlope * length)).closestTo(target) : target;
        else startDepth = new DepthInterval(depth[0] - maxSlope * plateaus.get(0).from, depth[0] + maxSlope * plateaus.get(0).from).intersect(free).closestTo(target);
        // build the profile
        List<double[]> pts = new ArrayList<>();
        List<DepthProfile.PlateauDecision> decisions = new ArrayList<>();
        pts.add(new double[]{0, startDepth});
        double curPos = 0, curDepth = startDepth;
        for (int i = 0; i < n; i++) {
            Plateau p = plateaus.get(i);
            if (!connect(pts, curPos, curDepth, p.from, depth[i])) return null;
            pts.add(new double[]{p.to, depth[i]});
            decisions.add(new DepthProfile.PlateauDecision(p.from, p.to, depth[i], p.options.get(choice[i])));
            curPos = p.to;
            curDepth = depth[i];
        }
        if (!connect(pts, curPos, curDepth, length, endDepth)) return null;
        DepthProfile profile = new DepthProfile(pts, decisions);
        if (profile.maxSlope() > maxSlope + 1e-6) return null;
        Candidate c = new Candidate();
        c.profile = profile;
        c.extraCost = DepthCostCalculator.extraCost(profile, cpm);
        c.deviation = DepthCostCalculator.deviationFromNormal(profile);
        c.transitions = profile.transitionCount();
        return c;
    }

    /**
     * Appends the connecting profile from (s1, d1) to (s2, d2): a return to the normal depth when both ramps and a
     * minimal run fit into the gap, otherwise a single ramp (altered depth kept between close crossings).
     */
    private boolean connect(List<double[]> pts, double s1, double d1, double s2, double d2) {
        double gap = s2 - s1;
        if (gap < -1e-9) return false;
        if (gap < 1e-9) return Math.abs(d1 - d2) < 1e-6;
        double r1 = Math.abs(d1 - target) / maxSlope, r2 = Math.abs(d2 - target) / maxSlope;
        boolean sameSideOrAtTarget = (d1 - target) * (d2 - target) >= -1e-12;
        if (r1 + r2 + minRun <= gap + 1e-9 && (r1 > 1e-9 || r2 > 1e-9)) {
            if (r1 > 1e-9) pts.add(new double[]{s1 + r1, target});
            if (r2 > 1e-9) pts.add(new double[]{s2 - r2, target});
            pts.add(new double[]{s2, d2});
            return true;
        }
        if (Math.abs(d2 - d1) <= maxSlope * gap + 1e-9) {
            pts.add(new double[]{s2, d2});
            return sameSideOrAtTarget || true;
        }
        return false;
    }
}
