package ru.lct.heatnet.depth;

import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.cost.LinkPiece;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NewNetwork;

/** Summary figures of a depth-mode network (from the output pieces, so they match the emitted features). */
public final class DepthMetrics {
    public double maxDepth;
    public double minDepth = Double.MAX_VALUE;
    public double averageDepth;
    public double deepLength;
    public double shallowLength;
    public int transitionCount;
    public int verticalCrossingCount;
    public int aboveCount;
    public int belowCount;
    public int underCount;
    public double extraCost;

    public static DepthMetrics of(NewNetwork net) {
        DepthMetrics m = new DepthMetrics();
        double weighted = 0, total = 0;
        for (NetLink l : net.links()) {
            if (l.profile() == null) continue;
            for (LinkPiece p : CostCalculator.pieces(l)) {
                double mid = (p.depthStart() + p.depthEnd()) / 2;
                weighted += mid * p.length();
                total += p.length();
                m.maxDepth = Math.max(m.maxDepth, Math.max(p.depthStart(), p.depthEnd()));
                m.minDepth = Math.min(m.minDepth, Math.min(p.depthStart(), p.depthEnd()));
                if (mid > DepthRules.NORMAL_DEPTH_M + 1e-6) m.deepLength += p.length();
                else if (mid < DepthRules.NORMAL_DEPTH_M - 1e-6) m.shallowLength += p.length();
                double base = Math.round(p.length() * DiameterTable.requireDu(l.du()).costPerMeter() * (p.special() ? p.kSpec() : 1.0));
                m.extraCost += p.cost() - base;
            }
            m.transitionCount += l.profile().transitionCount();
            for (DepthProfile.PlateauDecision d : l.profile().decisions()) {
                for (CrossingDepthOption o : d.options) {
                    if (!o.obstacle().isUtility()) { m.underCount++; continue; }
                    m.verticalCrossingCount++;
                    if (o.method() == CrossingDepthOption.Method.ABOVE) m.aboveCount++; else m.belowCount++;
                }
            }
        }
        m.averageDepth = total > 0 ? weighted / total : 0;
        if (m.minDepth == Double.MAX_VALUE) m.minDepth = 0;
        return m;
    }
}
