package ru.lct.heatnet.depth;

/** Piecewise-linear profile segment along the route: [from, to] with depths at both ends. */
public final class DepthProfileSegment {
    public final double from;
    public final double to;
    public final double depthStart;
    public final double depthEnd;

    public DepthProfileSegment(double from, double to, double depthStart, double depthEnd) {
        this.from = from;
        this.to = to;
        this.depthStart = depthStart;
        this.depthEnd = depthEnd;
    }

    public double length() { return to - from; }
    public boolean isFlat() { return Math.abs(depthEnd - depthStart) < 1e-9; }
    public double slope() { return length() <= 0 ? 0 : Math.abs(depthEnd - depthStart) / length(); }

    @Override
    public String toString() { return String.format(java.util.Locale.ROOT, "[%.1f..%.1f] %.2f->%.2f", from, to, depthStart, depthEnd); }
}
