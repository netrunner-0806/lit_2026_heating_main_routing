package ru.lct.heatnet.depth;

import ru.lct.heatnet.config.DepthRules;

/** One crossed object with its vertical rule, located along a route as the special section [from, to]. */
public final class VerticalObstacle {
    private final String type;
    private final String label;
    private final DepthRules.VerticalRule rule;
    /** Special section interval along the route, m. */
    private final double from;
    private final double to;
    /** Utility envelope height, m (existing heat network: from its DU). NaN for pass-under rules. */
    private final double heightM;
    private final double kSpec;
    /** Height of the new pair (by the DU of the link crossing this object), m. */
    private final double newHeightM;

    public VerticalObstacle(String type, String label, DepthRules.VerticalRule rule, double from, double to, double heightM, double kSpec, double newHeightM) {
        this.type = type;
        this.label = label;
        this.rule = rule;
        this.from = from;
        this.to = to;
        this.heightM = heightM;
        this.kSpec = kSpec;
        this.newHeightM = newHeightM;
    }

    public String type() { return type; }
    public String label() { return label; }
    public DepthRules.VerticalRule rule() { return rule; }
    public double from() { return from; }
    public double to() { return to; }
    public double heightM() { return heightM; }
    public double kSpec() { return kSpec; }
    public boolean isUtility() { return rule.isUtility(); }
    public double newHeightM() { return newHeightM; }
    /** Admissible depth passing ABOVE with this object's own new-pair height. */
    public DepthInterval above() { return above(newHeightM); }
    public double utilityTop() { return rule.utilityTopM(); }
    public double utilityBottom() { return rule.utilityTopM() + heightM; }

    /** Admissible depth (top of the new envelope) passing ABOVE the utility, for a new pair of height hNew. */
    public DepthInterval above(double hNew) {
        if (!isUtility()) return DepthInterval.EMPTY;
        return new DepthInterval(DepthRules.MIN_DEPTH_M, utilityTop() - rule.clearanceM() - hNew);
    }

    /** Admissible depth passing BELOW the utility. */
    public DepthInterval below() {
        if (!isUtility()) return DepthInterval.EMPTY;
        return new DepthInterval(Math.max(DepthRules.MIN_DEPTH_M, utilityBottom() + rule.clearanceM()), Double.POSITIVE_INFINITY);
    }

    /** Admissible depth for pass-under rules (road / tram): at least the required cover. */
    public DepthInterval passUnder() {
        if (isUtility()) return DepthInterval.EMPTY;
        return new DepthInterval(Math.max(DepthRules.MIN_DEPTH_M, rule.minDepthM()), Double.POSITIVE_INFINITY);
    }

    public VerticalObstacle shifted(double offset) { return new VerticalObstacle(type, label, rule, from + offset, to + offset, heightM, kSpec, newHeightM); }

    @Override
    public String toString() { return String.format(java.util.Locale.ROOT, "%s [%.1f..%.1f]", label, from, to); }
}
