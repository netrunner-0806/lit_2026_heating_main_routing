package ru.lct.heatnet.cost;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.config.ChamberCostTable;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.SpecialPassage;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Section 6 of the technical annex: link costs (base / special with Kspec), chambers, tie-ins, penalties, score. */
public final class CostCalculator {

    private CostCalculator() {}

    /** Boundaries of special sections closer than this to a link end are snapped to the end (numerical noise only). */
    public static final double SNAP_TO_END_M = ru.lct.heatnet.geo.GeometryTolerance.SPECIAL_SNAP_TO_END_M;
    /** Interior vertices with a larger change of direction split a special piece (special features must be straight). */
    public static final double STRAIGHT_TOL_DEG = ru.lct.heatnet.geo.GeometryTolerance.STRAIGHT_TOL_DEG;

    /**
     * Splits the link into pieces of constant laying method / Kspec and computes the cost of each piece:
     * C = L * c(DU) * Kspec (Kdepth = 1 in 2D).
     */
    public static List<LinkPiece> pieces(NetLink link) {
        double L = link.length();
        double cpm = DiameterTable.requireDu(link.du()).costPerMeter();
        TreeSet<Double> bounds = new TreeSet<>();
        bounds.add(0.0);
        bounds.add(L);
        List<double[]> ivs = new ArrayList<>();
        for (SpecialPassage p : link.passages()) {
            double f = Math.max(0, p.from()), t = Math.min(L, p.to());
            if (f < SNAP_TO_END_M) f = 0;
            if (L - t < SNAP_TO_END_M) t = L;
            ivs.add(new double[]{f, t, p.kSpec()});
            bounds.add(f);
            bounds.add(t);
        }
        // a special feature must be one straight LineString: polyline turns inside special sections split the piece
        java.util.Set<Double> hardBounds = new java.util.HashSet<>();
        List<Coordinate> cs = link.coords();
        double acc = 0;
        for (int v = 1; v < cs.size() - 1; v++) {
            acc += cs.get(v - 1).distance(cs.get(v));
            if (ru.lct.heatnet.geo.GeometryUtils.turnAngleDeg(cs.get(v - 1), cs.get(v), cs.get(v + 1)) <= STRAIGHT_TOL_DEG) continue;
            for (double[] iv : ivs) if (acc > iv[0] + 1e-9 && acc < iv[1] - 1e-9) { bounds.add(acc); hardBounds.add(acc); }
        }
        // depth mode: split at profile breakpoints and where the profile crosses 3.0 m (Kdepth formula changes)
        ru.lct.heatnet.depth.DepthProfile profile = link.profile();
        if (profile != null) {
            for (double pos : profile.splitPositions()) {
                if (pos > 1e-9 && pos < L - 1e-9) { bounds.add(pos); hardBounds.add(pos); }
            }
        }
        // near-coincident interior bounds (e.g. two utilities crossing the route at the same point, or a few millimetres
        // apart) are merged: a piece shorter than the snap tolerance is numerical noise and would break the
        // straightness / coverage checks (degenerate lines, technical nodes of degree 4)
        List<Double> bs = new ArrayList<>();
        for (double b : bounds) {
            if (!bs.isEmpty() && b < L - 1e-9 && b - bs.get(bs.size() - 1) < SNAP_TO_END_M) {
                if (hardBounds.contains(b)) hardBounds.add(bs.get(bs.size() - 1));
                continue;
            }
            bs.add(b);
        }
        // merge consecutive elementary intervals with identical obstacle set (never across a hard bound)
        List<LinkPiece> out = new ArrayList<>();
        int i = 0;
        while (i + 1 < bs.size()) {
            double a = bs.get(i);
            int j = i + 1;
            List<Obstacle> set = crossedAt(link, ivs, (a + bs.get(j)) / 2);
            while (j + 1 < bs.size() && !hardBounds.contains(bs.get(j)) && crossedAt(link, ivs, (bs.get(j) + bs.get(j + 1)) / 2).equals(set)) j++;
            double b = bs.get(j);
            if (b - a > 1e-9) {
                double k = 1.0;
                for (int q = 0; q < ivs.size(); q++) {
                    double[] iv = ivs.get(q);
                    double mid = (a + b) / 2;
                    if (iv[0] <= mid && mid <= iv[1]) k = Math.max(k, iv[2]);
                }
                boolean special = !set.isEmpty();
                List<Coordinate> coords = sub(link.coords(), a, b);
                double len = roundLength(b - a);
                if (profile == null) {
                    double cost = Math.round(len * cpm * Constants.K_DEPTH_2D * (special ? k : 1.0)); // whole rubles
                    out.add(new LinkPiece(coords, a, b, special, special ? k : 1.0, set, cost));
                } else {
                    double e0 = profile.depthAt(a), e1 = profile.depthAt(b);
                    double d0 = roundDepth(e0), d1 = roundDepth(e1);
                    double kd = ru.lct.heatnet.depth.DepthCostCalculator.kDepth(d0, d1);
                    double cost = Math.round(len * cpm * kd * (special ? k : 1.0));
                    double slope = b - a > 1e-9 ? Math.abs(e1 - e0) / (b - a) : 0; // exact profile slope (not from rounded values)
                    out.add(new LinkPiece(coords, a, b, special, special ? k : 1.0, set, cost, d0, d1, kd, slope));
                }
            }
            i = j;
        }
        return out;
    }

    /** Lengths are reported with 3 decimals; costs are computed from the reported length so the output is self-consistent. */
    public static double roundLength(double len) { return Math.round(len * 1000.0) / 1000.0; }

    /** Depths are reported with 4 decimals; Kdepth and costs are computed from the reported depths. */
    public static double roundDepth(double v) { return Math.round(v * 10000.0) / 10000.0; }

    /** @deprecated kept for callers that round other 3-decimal quantities; depths use {@link #roundDepth}. */
    @Deprecated
    public static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static List<Obstacle> crossedAt(NetLink link, List<double[]> ivs, double pos) {
        List<Obstacle> set = new ArrayList<>();
        List<SpecialPassage> ps = link.passages();
        for (int q = 0; q < ivs.size(); q++) {
            double[] iv = ivs.get(q);
            if (iv[0] <= pos && pos <= iv[1]) set.add(ps.get(q).obstacle());
        }
        set.sort((x, y) -> Integer.compare(x.index(), y.index()));
        return set;
    }

    /** Sub-polyline between two length indices (exact interpolation on segments). */
    public static List<Coordinate> sub(List<Coordinate> cs, double from, double to) {
        List<Coordinate> out = new ArrayList<>();
        double acc = 0;
        for (int i = 0; i < cs.size() - 1; i++) {
            Coordinate a = cs.get(i), b = cs.get(i + 1);
            double seg = a.distance(b);
            double s0 = acc, s1 = acc + seg;
            if (s1 < from - 1e-9) { acc = s1; continue; }
            if (s0 > to + 1e-9) break;
            if (out.isEmpty()) {
                double f = seg == 0 ? 0 : Math.max(0, Math.min(1, (from - s0) / seg));
                out.add(f <= 1e-12 ? a : new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f));
            }
            if (s1 <= to + 1e-9) {
                if (out.get(out.size() - 1).distance(b) > 1e-9) out.add(b);
                if (to - s1 <= 1e-9) break; // interval ends exactly at this vertex
            } else {
                double f = seg == 0 ? 0 : Math.max(0, Math.min(1, (to - s0) / seg));
                Coordinate end = f >= 1 - 1e-12 ? b : new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
                if (out.get(out.size() - 1).distance(end) > 1e-9) out.add(end);
                break;
            }
            acc = s1;
        }
        // exact endpoints
        if (Math.abs(from) < 1e-9) out.set(0, cs.get(0));
        if (Math.abs(to - ru.lct.heatnet.geo.GeometryUtils.length(cs)) < 1e-9) out.set(out.size() - 1, cs.get(cs.size() - 1));
        return out;
    }

    public static double linkCost(NetLink link) {
        double s = 0;
        for (LinkPiece p : pieces(link)) s += p.cost();
        return s;
    }

    public static double newChamberCost(NetNode chamber) {
        return ChamberCostTable.newChamberCost(chamber.maxAdjacentDu());
    }

    public static double penalty(double flowTph) {
        return Constants.UNCONNECTED_PENALTY_FIXED + Constants.UNCONNECTED_PENALTY_PER_TPH * flowTph;
    }

    public static double score(double calculatedCost, double newLength) {
        return Constants.SCORE_COST_WEIGHT * (calculatedCost / Constants.SCORE_COST_BASE)
                + Constants.SCORE_LENGTH_WEIGHT * (newLength / Constants.SCORE_LENGTH_BASE);
    }

    /** Full cost breakdown of a network (penalties for the given unconnected flows). */
    public static CostSummary summarize(NewNetwork net, List<Double> unconnectedFlows) {
        double lines = 0, length = 0, chambers = 0, tieIns = 0;
        int tieInCount = 0;
        for (NetLink l : net.links()) for (LinkPiece p : pieces(l)) { lines += p.cost(); length += p.length(); }
        for (NetNode n : net.nodes()) {
            if (n.isNewChamber()) chambers += newChamberCost(n);
            if (n.kind() == NetNode.Kind.EXISTING_CHAMBER) {
                tieInCount += n.children().size();
                tieIns += n.children().size() * ChamberCostTable.EXISTING_CHAMBER_TIE_IN_COST;
            }
        }
        double penalty = 0;
        for (double q : unconnectedFlows) penalty += penalty(q);
        double construction = lines + chambers + tieIns;
        double calculated = construction + penalty;
        return new CostSummary(lines, chambers, tieInCount, tieIns, penalty, construction, calculated, length, score(calculated, length));
    }
}
