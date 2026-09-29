package ru.lct.heatnet.depth;

import ru.lct.heatnet.config.DepthRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Depth of the top of the new envelope along a route (piecewise linear, breakpoints sorted by distance). */
public final class DepthProfile {
    private final double[] s;
    private final double[] h;
    private final List<PlateauDecision> decisions;

    /** Record of how one plateau was chosen (for explainability / inspector). */
    public static final class PlateauDecision {
        public final double from, to, depth;
        public final List<CrossingDepthOption> options;
        public PlateauDecision(double from, double to, double depth, List<CrossingDepthOption> options) {
            this.from = from; this.to = to; this.depth = depth; this.options = Collections.unmodifiableList(options);
        }
    }

    public DepthProfile(List<double[]> points, List<PlateauDecision> decisions) {
        List<double[]> pts = new ArrayList<>();
        for (double[] p : points) if (pts.isEmpty() || p[0] > pts.get(pts.size() - 1)[0] + 1e-9) pts.add(p);
            else if (Math.abs(p[0] - pts.get(pts.size() - 1)[0]) <= 1e-9) pts.set(pts.size() - 1, p);
        this.s = new double[pts.size()];
        this.h = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) { s[i] = pts.get(i)[0]; h[i] = pts.get(i)[1]; }
        this.decisions = decisions == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(decisions));
    }

    public static DepthProfile flat(double length, double depth) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0, depth});
        pts.add(new double[]{length, depth});
        return new DepthProfile(pts, null);
    }

    public int size() { return s.length; }
    public double length() { return s[s.length - 1]; }
    public double sAt(int i) { return s[i]; }
    public double hAt(int i) { return h[i]; }
    public List<PlateauDecision> decisions() { return decisions; }

    /** Depth at distance x (linear interpolation, clamped to the ends). */
    public double depthAt(double x) {
        if (x <= s[0]) return h[0];
        if (x >= s[s.length - 1]) return h[h.length - 1];
        int i = 1;
        while (i < s.length && s[i] < x) i++;
        double t = (x - s[i - 1]) / (s[i] - s[i - 1]);
        return h[i - 1] + (h[i] - h[i - 1]) * t;
    }

    public double startDepth() { return h[0]; }
    public double endDepth() { return h[h.length - 1]; }

    public double maxDepth() { double m = 0; for (double v : h) m = Math.max(m, v); return m; }
    public double minDepth() { double m = Double.MAX_VALUE; for (double v : h) m = Math.min(m, v); return m; }

    /** Breakpoint distances plus the points where the profile crosses the normal depth (Kdepth formula changes). */
    public List<Double> splitPositions() {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < s.length; i++) {
            out.add(s[i]);
            if (i + 1 < s.length) {
                double a = h[i] - DepthRules.NORMAL_DEPTH_M, b = h[i + 1] - DepthRules.NORMAL_DEPTH_M;
                if ((a < -1e-9 && b > 1e-9) || (a > 1e-9 && b < -1e-9)) out.add(s[i] + (s[i + 1] - s[i]) * (a / (a - b)));
            }
        }
        Collections.sort(out);
        return out;
    }

    public List<DepthProfileSegment> segments() {
        List<DepthProfileSegment> out = new ArrayList<>();
        for (int i = 0; i + 1 < s.length; i++) out.add(new DepthProfileSegment(s[i], s[i + 1], h[i], h[i + 1]));
        return out;
    }

    /** Number of maximal runs with non-zero slope. */
    public int transitionCount() {
        int n = 0;
        boolean inRamp = false;
        for (DepthProfileSegment seg : segments()) {
            if (!seg.isFlat()) { if (!inRamp) n++; inRamp = true; } else inRamp = false;
        }
        return n;
    }

    /** Maximal slope over all segments. */
    public double maxSlope() { double m = 0; for (DepthProfileSegment seg : segments()) m = Math.max(m, seg.slope()); return m; }

    /** Sub-profile for [from, to] re-based to start at 0. */
    public DepthProfile sub(double from, double to) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0, depthAt(from)});
        for (int i = 0; i < s.length; i++) if (s[i] > from + 1e-9 && s[i] < to - 1e-9) pts.add(new double[]{s[i] - from, h[i]});
        pts.add(new double[]{to - from, depthAt(to)});
        List<PlateauDecision> ds = new ArrayList<>();
        for (PlateauDecision d : decisions) {
            if (d.to <= from + 1e-9 || d.from >= to - 1e-9) continue;
            ds.add(new PlateauDecision(Math.max(0, d.from - from), Math.min(to - from, d.to - from), d.depth, shiftOptions(d.options, -from)));
        }
        return new DepthProfile(pts, ds);
    }

    /** Concatenation: this profile followed by the other one (other's s re-based after this length). */
    public DepthProfile concat(DepthProfile other) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < s.length; i++) pts.add(new double[]{s[i], h[i]});
        double off = length();
        for (int i = 0; i < other.s.length; i++) pts.add(new double[]{off + other.s[i], other.h[i]});
        List<PlateauDecision> ds = new ArrayList<>(decisions);
        for (PlateauDecision d : other.decisions) ds.add(new PlateauDecision(d.from + off, d.to + off, d.depth, shiftOptions(d.options, off)));
        return new DepthProfile(pts, ds);
    }

    /** Options with their obstacle sections moved by the given offset (obstacles are positioned on the same axis as the profile). */
    private static List<CrossingDepthOption> shiftOptions(List<CrossingDepthOption> options, double offset) {
        if (Math.abs(offset) < 1e-12) return options;
        List<CrossingDepthOption> out = new ArrayList<>();
        for (CrossingDepthOption o : options) out.add(new CrossingDepthOption(o.obstacle().shifted(offset), o.method(), o.interval()));
        return out;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("profile");
        for (int i = 0; i < s.length; i++) b.append(String.format(java.util.Locale.ROOT, " (%.1f,%.2f)", s[i], h[i]));
        return b.toString();
    }
}
