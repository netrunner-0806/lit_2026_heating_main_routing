package ru.lct.heatnet.restrictions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of checking one straight segment against the obstacle space. */
public final class SegmentCheck {
    private final boolean valid;
    private final boolean validReverse;
    private final String reason;
    private final List<SpecialPassage> passages;
    private final double length;
    private final double effectiveLength;

    private SegmentCheck(boolean valid, boolean validReverse, String reason, List<SpecialPassage> passages, double length, double effectiveLength) {
        this.valid = valid;
        this.validReverse = validReverse;
        this.reason = reason;
        this.passages = passages;
        this.length = length;
        this.effectiveLength = effectiveLength;
    }

    public static SegmentCheck invalid(String reason) {
        return new SegmentCheck(false, false, reason, Collections.emptyList(), 0, Double.POSITIVE_INFINITY);
    }

    public static SegmentCheck valid(double length, List<SpecialPassage> passages) {
        return new SegmentCheck(true, true, null, passages, length, effectiveLength(length, passages));
    }

    /**
     * Segment whose validity depends on the traversal direction (road/tram entry angle rule): {@code forward} is
     * a->b, {@code reverse} is b->a.
     */
    public static SegmentCheck directional(boolean forward, boolean reverse, String reason, double length, List<SpecialPassage> passages) {
        return new SegmentCheck(forward, reverse, forward ? null : reason, passages, length, effectiveLength(length, passages));
    }

    /** Length weighted by Kspec on special sections (overlaps merged with the max coefficient). */
    public static double effectiveLength(double length, List<SpecialPassage> passages) {
        if (passages.isEmpty()) return length;
        List<double[]> pts = new ArrayList<>();
        for (SpecialPassage p : passages) { pts.add(new double[]{p.from(), 1}); pts.add(new double[]{p.to(), -1}); }
        // piecewise: evaluate max K over each elementary interval
        List<Double> bounds = new ArrayList<>();
        for (SpecialPassage p : passages) { bounds.add(p.from()); bounds.add(p.to()); }
        Collections.sort(bounds);
        double extra = 0;
        for (int i = 0; i + 1 < bounds.size(); i++) {
            double a = bounds.get(i), b = bounds.get(i + 1);
            if (b - a <= 1e-9) continue;
            double mid = (a + b) / 2, k = 1.0;
            for (SpecialPassage p : passages) if (p.from() <= mid && mid <= p.to()) k = Math.max(k, p.kSpec());
            extra += (b - a) * (k - 1.0);
        }
        return length + extra;
    }

    /** Valid when traversed from a to b (consumer -> existing network direction). */
    public boolean valid() { return valid; }
    /** Valid when traversed from b to a. */
    public boolean validReverse() { return validReverse; }
    public String reason() { return reason; }
    public List<SpecialPassage> passages() { return passages; }
    public double length() { return length; }
    public double effectiveLength() { return effectiveLength; }
    public boolean hasSpecial() { return !passages.isEmpty(); }
}
