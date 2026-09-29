package ru.lct.heatnet.depth;

/** Closed interval of admissible depths [lo, hi] (hi may be +inf); empty when lo > hi. */
public final class DepthInterval {
    public static final DepthInterval EMPTY = new DepthInterval(1, 0);
    public final double lo;
    public final double hi;

    public DepthInterval(double lo, double hi) { this.lo = lo; this.hi = hi; }

    public boolean isEmpty() { return lo > hi + 1e-9; }
    public boolean contains(double h) { return h >= lo - 1e-9 && h <= hi + 1e-9; }

    public DepthInterval intersect(DepthInterval o) {
        return new DepthInterval(Math.max(lo, o.lo), Math.min(hi, o.hi));
    }

    /** Depths reachable from this interval within a ramp of the given maximal change. */
    public DepthInterval expand(double delta) { return new DepthInterval(lo - delta, hi + delta); }

    /** Point of the interval closest to the target depth. */
    public double closestTo(double target) {
        if (target < lo) return lo;
        if (target > hi) return hi;
        return target;
    }

    @Override
    public String toString() { return String.format(java.util.Locale.ROOT, "[%.2f, %s]", lo, hi == Double.POSITIVE_INFINITY ? "inf" : String.format(java.util.Locale.ROOT, "%.2f", hi)); }
}
