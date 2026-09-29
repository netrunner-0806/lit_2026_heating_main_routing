package ru.lct.heatnet.config;

import java.util.Optional;

/**
 * Section 5 of the current technical annex (depth mode) and the vertical rules of Table 2. Depth h is measured from the
 * conventional horizontal ground surface to the TOP of the design envelope of the new pair. No discrete depth step,
 * no maximum depth (the sanity guard below is not a business rule).
 */
public final class DepthRules {
    private DepthRules() {}

    /** Normal (target) depth to the top of the envelope, m. */
    public static final double NORMAL_DEPTH_M = 3.0;
    /** Minimum depth to the top of the envelope, m. */
    public static final double MIN_DEPTH_M = 0.7;
    /** Maximum |dh/ds| (metres of depth per metre of horizontal length). */
    public static final double MAX_SLOPE = 0.10;
    /** Kdepth = 1 + DEPTH_COST_RATE * (h - 3) for h > 3. */
    public static final double DEPTH_COST_RATE = 0.10;
    /** Internal numerical sanity guard only (annex sets no maximum depth); very high on purpose. */
    public static final double SANITY_MAX_DEPTH_M = 100.0;

    /** Vertical rule of a crossable object in depth mode. */
    public static final class VerticalRule {
        public enum Kind { PASS_UNDER_MIN_DEPTH, UTILITY_CLEARANCE }

        private final Kind kind;
        /** road/tram: minimum depth of the top of the new envelope inside the special section, m. */
        private final double minDepthM;
        /** utility: conventional depth to the top of the utility envelope, m (NaN for existing heat network = 3.0 with height from Table 1). */
        private final double utilityTopM;
        /** utility: envelope height, m (NaN = taken from the existing line's DU). */
        private final double utilityHeightM;
        /** utility: required vertical clearance, m. */
        private final double clearanceM;

        VerticalRule(Kind kind, double minDepthM, double utilityTopM, double utilityHeightM, double clearanceM) {
            this.kind = kind;
            this.minDepthM = minDepthM;
            this.utilityTopM = utilityTopM;
            this.utilityHeightM = utilityHeightM;
            this.clearanceM = clearanceM;
        }

        public static VerticalRule passUnder(double minDepthM) { return new VerticalRule(Kind.PASS_UNDER_MIN_DEPTH, minDepthM, Double.NaN, Double.NaN, 0); }
        public static VerticalRule utility(double topM, double heightM, double clearanceM) { return new VerticalRule(Kind.UTILITY_CLEARANCE, 0, topM, heightM, clearanceM); }

        public Kind kind() { return kind; }
        public double minDepthM() { return minDepthM; }
        public double utilityTopM() { return utilityTopM; }
        public double utilityHeightM() { return utilityHeightM; }
        public double clearanceM() { return clearanceM; }
        public boolean isUtility() { return kind == Kind.UTILITY_CLEARANCE; }
    }

    private static final VerticalRule ROAD = VerticalRule.passUnder(1.0);
    private static final VerticalRule TRAM = VerticalRule.passUnder(1.2);
    private static final VerticalRule GAS = VerticalRule.utility(2.8, 0.40, 0.20);
    private static final VerticalRule POWER = VerticalRule.utility(2.7, 0.20, 0.50);
    /** Existing heat network: top at 3.0 m, height from Table 1 by its DU (supplied per object), clearance 0.5 m. */
    private static final VerticalRule EXISTING_HEAT = VerticalRule.utility(3.0, Double.NaN, 0.50);

    public static Optional<VerticalRule> forType(String restrictionType) {
        if (restrictionType == null) return Optional.empty();
        switch (restrictionType) {
            case RestrictionRules.ROAD: return Optional.of(ROAD);
            case RestrictionRules.TRAM_TRACKS: return Optional.of(TRAM);
            case RestrictionRules.GAS_PIPELINE: return Optional.of(GAS);
            case RestrictionRules.POWER_CABLE: return Optional.of(POWER);
            case RestrictionRules.EXISTING_HEAT_NETWORK: return Optional.of(EXISTING_HEAT);
            default: return Optional.ofNullable(customVertical.get(restrictionType));
        }
    }

    private static final java.util.Map<String, VerticalRule> customVertical = new java.util.concurrent.ConcurrentHashMap<>();

    /** Registers a vertical rule for a configured custom restriction type (extension, not an official rule). */
    public static void registerCustom(String type, VerticalRule rule) { customVertical.put(type, rule); }

    /** Kdepth for a constant depth. */
    public static double kDepth(double h) {
        return h <= NORMAL_DEPTH_M + 1e-12 ? 1.0 : 1.0 + DEPTH_COST_RATE * (h - NORMAL_DEPTH_M);
    }

    /** Kdepth of a uniform ramp between two depths on the same side of 3.0 m (arithmetic mean of the end coefficients). */
    public static double kDepthRamp(double h1, double h2) { return (kDepth(h1) + kDepth(h2)) / 2.0; }
}
