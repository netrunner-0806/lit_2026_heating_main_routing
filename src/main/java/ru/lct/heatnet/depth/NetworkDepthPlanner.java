package ru.lct.heatnet.depth;

import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.SpecialPassage;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Assigns a depth profile to every link of a network. Physical pipes are planned as whole chains: starting at each
 * root (tie-in, depth 3.0 m), a chain follows the collinear continuation through junction chambers (the trunk split
 * by a junction is one pipe), so a junction never truncates a ramp; branches turning off at a junction are planned
 * afterwards with the junction depth fixed at their parent end. Requires flows/DU to be computed first.
 */
public final class NetworkDepthPlanner {

    public static final class Result {
        public final boolean feasible;
        public final String problem;
        public final NetLink failedLink;
        Result(boolean feasible, String problem, NetLink failedLink) { this.feasible = feasible; this.problem = problem; this.failedLink = failedLink; }
    }

    private final DepthProfileOptimizer optimizer = new DepthProfileOptimizer();

    public Result plan(NewNetwork net) {
        for (NetLink l : net.links()) l.setProfile(null);
        for (NetNode root : net.roots()) {
            for (NetLink top : root.children()) {
                Result r = planChain(top, DepthRules.NORMAL_DEPTH_M);
                if (!r.feasible) return r;
            }
        }
        for (NetLink l : net.links()) if (l.profile() == null) return new Result(false, "link without profile: " + l, l);
        return new Result(true, null, null);
    }

    /** Plans the physical chain starting with {@code parentLink} (its parent end is fixed), then its side branches. */
    private Result planChain(NetLink parentLink, double parentEndDepth) {
        // chain = list of links from the far consumer end towards the parent end: [child-most ... parentLink]
        List<NetLink> chain = new ArrayList<>();
        NetLink cur = parentLink;
        while (true) {
            chain.add(0, cur);
            NetNode child = cur.child();
            NetLink cont = collinearContinuation(child, cur);
            if (cont == null) break;
            cur = cont;
        }
        // concatenate geometry / obstacles along the chain (s from the consumer end)
        double total = 0;
        List<VerticalObstacle> obstacles = new ArrayList<>();
        List<double[]> cpmRanges = new ArrayList<>();
        for (NetLink l : chain) {
            double hNew = DiameterTable.requireDu(l.du()).heightM();
            for (SpecialPassage p : l.passages()) {
                VerticalObstacle vo = toVertical(p, hNew);
                if (vo != null) obstacles.add(vo.shifted(total));
            }
            cpmRanges.add(new double[]{total, total + l.length(), DiameterTable.requireDu(l.du()).costPerMeter()});
            total += l.length();
        }
        final double L = total;
        DoubleUnaryOperator cpm = s -> {
            for (double[] r : cpmRanges) if (s >= r[0] - 1e-9 && s <= r[1] + 1e-9) return r[2];
            return cpmRanges.get(cpmRanges.size() - 1)[2];
        };
        DepthProfileOptimizer.Result res = optimizer.optimize(L, obstacles, null, parentEndDepth, cpm);
        if (!res.feasible) return new Result(false, res.reason + " on " + chain.get(0), chain.get(0));
        // cut the chain profile into link profiles and plan side branches
        double off = 0;
        for (NetLink l : chain) {
            l.setProfile(res.profile.sub(off, off + l.length()));
            off += l.length();
        }
        for (int i = 0; i < chain.size(); i++) {
            NetLink l = chain.get(i);
            NetNode child = l.child();
            if (child.kind() == NetNode.Kind.CONNECTION_POINT) continue;
            double junctionDepth = l.profile().startDepth();
            for (NetLink branch : child.children()) {
                if (i > 0 && branch == chain.get(i - 1)) continue; // the collinear continuation belongs to this chain
                Result r = planChain(branch, junctionDepth);
                if (!r.feasible) return r;
            }
        }
        return new Result(true, null, null);
    }

    /** Child link of the node that continues the given link's pipe straight through the node (turn ~ 0°), or null. */
    static NetLink collinearContinuation(NetNode node, NetLink outgoing) {
        if (node.kind() == NetNode.Kind.CONNECTION_POINT) return null;
        List<org.locationtech.jts.geom.Coordinate> oc = outgoing.coords();
        if (oc.size() < 2) return null;
        NetLink best = null;
        double bestTurn = 1.0;
        for (NetLink c : node.children()) {
            List<org.locationtech.jts.geom.Coordinate> cc = c.coords();
            if (cc.size() < 2) continue;
            double turn = GeometryUtils.turnAngleDeg(cc.get(cc.size() - 2), cc.get(cc.size() - 1), oc.get(1));
            if (turn < bestTurn) { bestTurn = turn; best = c; }
        }
        return best;
    }

    /** Vertical obstacle for a special passage, or null if the crossed object has no vertical rule. */
    public static VerticalObstacle toVertical(SpecialPassage p, double hNew) {
        Obstacle o = p.obstacle();
        return DepthRules.forType(o.rule().type()).map(rule -> {
            double height = rule.isUtility() ? (Double.isNaN(rule.utilityHeightM()) ? o.existingHeightM() : rule.utilityHeightM()) : Double.NaN;
            return new VerticalObstacle(o.rule().type(), o.describe(), rule, p.from(), p.to(), height, p.kSpec(), hNew);
        }).orElse(null);
    }
}
