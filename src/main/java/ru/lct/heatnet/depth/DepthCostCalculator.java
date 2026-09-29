package ru.lct.heatnet.depth;

import ru.lct.heatnet.config.DepthRules;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

/** Kdepth-related cost helpers (section 6 of the annex, depth mode). */
public final class DepthCostCalculator {
    private DepthCostCalculator() {}

    /** Kdepth of a piece with depths h1 -> h2 that does not cross 3.0 m (mean of the end coefficients). */
    public static double kDepth(double h1, double h2) { return DepthRules.kDepthRamp(h1, h2); }

    /** Extra construction cost of a profile relative to Kdepth = 1, integrating piecewise with splits at 3.0 m. */
    public static double extraCost(DepthProfile profile, DoubleUnaryOperator costPerMetre) {
        double sum = 0;
        List<Double> splits = profile.splitPositions();
        for (int i = 0; i + 1 < splits.size(); i++) {
            double a = splits.get(i), b = splits.get(i + 1);
            if (b - a < 1e-9) continue;
            double ha = profile.depthAt(a), hb = profile.depthAt(b);
            double k = kDepth(ha, hb);
            sum += (b - a) * costPerMetre.applyAsDouble((a + b) / 2) * (k - 1.0);
        }
        return sum;
    }

    /** Length-weighted absolute deviation from the normal depth (secondary objective). */
    public static double deviationFromNormal(DepthProfile profile) {
        double sum = 0;
        for (DepthProfileSegment s : profile.segments()) {
            double a = s.depthStart - DepthRules.NORMAL_DEPTH_M, b = s.depthEnd - DepthRules.NORMAL_DEPTH_M;
            if (a * b >= 0) sum += s.length() * (Math.abs(a) + Math.abs(b)) / 2;
            else { double t = Math.abs(a) / (Math.abs(a) + Math.abs(b)); sum += s.length() * (t * Math.abs(a) / 2 + (1 - t) * Math.abs(b) / 2); }
        }
        return sum;
    }
}
