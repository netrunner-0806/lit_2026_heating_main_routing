package ru.lct.heatnet.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static ru.lct.heatnet.config.RestrictionRule.Kind.FORBIDDEN;
import static ru.lct.heatnet.config.RestrictionRule.Kind.SPECIAL;
import static ru.lct.heatnet.config.RestrictionRule.SectionMode.NONE;
import static ru.lct.heatnet.config.RestrictionRule.SectionMode.POINT_PLUS_EXTENT;
import static ru.lct.heatnet.config.RestrictionRule.SectionMode.POLYGON_PLUS_EXTENT;

/**
 * Table 2 of the current technical annex (rules by restriction type), plus the pseudo-type for
 * crossing an existing heat network without tie-in. Single source of truth for restriction numbers.
 */
public final class RestrictionRules {

    public static final String OKS = "oks";
    public static final String PARK = "park";
    public static final String SOCIAL_AREA = "social_area";
    public static final String PROHIBITED_SITE = "prohibited_site";
    public static final String WATER = "water";
    public static final String RAILWAY = "railway";
    public static final String ROAD = "road";
    public static final String TRAM_TRACKS = "tram_tracks";
    public static final String GAS_PIPELINE = "gas_pipeline";
    public static final String POWER_CABLE = "power_cable";
    /** Pseudo restriction type: existing heat network crossed without tie-in. */
    public static final String EXISTING_HEAT_NETWORK = "heat_network";

    /** Conventional envelope widths of linear objects (2D uses only the width), metres. */
    public static final double GAS_PIPELINE_WIDTH_M = 0.40;
    public static final double POWER_CABLE_WIDTH_M = 0.20;

    private static final Map<String, RestrictionRule> RULES;
    /** Configured custom types (extension only; not official rules of the annex). */
    private static final Map<String, RestrictionRule> CUSTOM = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        Map<String, RestrictionRule> m = new LinkedHashMap<>();
        m.put(OKS, new RestrictionRule(OKS, FORBIDDEN, 5.0, true, 0, NONE, 0, 1.0, 0));
        m.put(PARK, new RestrictionRule(PARK, FORBIDDEN, 1.0, false, 0, NONE, 0, 1.0, 0));
        m.put(SOCIAL_AREA, new RestrictionRule(SOCIAL_AREA, FORBIDDEN, 1.0, false, 0, NONE, 0, 1.0, 0));
        m.put(PROHIBITED_SITE, new RestrictionRule(PROHIBITED_SITE, FORBIDDEN, 1.0, false, 0, NONE, 0, 1.0, 0));
        m.put(WATER, new RestrictionRule(WATER, FORBIDDEN, 1.0, false, 0, NONE, 0, 1.0, 0));
        m.put(RAILWAY, new RestrictionRule(RAILWAY, FORBIDDEN, 1.0, false, 0, NONE, 0, 1.0, 0));
        m.put(ROAD, new RestrictionRule(ROAD, SPECIAL, 1.5, false, 45.0, POLYGON_PLUS_EXTENT, 3.0, 1.60, 0));
        m.put(TRAM_TRACKS, new RestrictionRule(TRAM_TRACKS, SPECIAL, 1.5, false, 45.0, POLYGON_PLUS_EXTENT, 3.0, 1.75, 0));
        m.put(GAS_PIPELINE, new RestrictionRule(GAS_PIPELINE, SPECIAL, 2.0, false, 0, POINT_PLUS_EXTENT, 2.0, 1.25, GAS_PIPELINE_WIDTH_M));
        m.put(POWER_CABLE, new RestrictionRule(POWER_CABLE, SPECIAL, 2.0, false, 0, POINT_PLUS_EXTENT, 2.0, 1.15, POWER_CABLE_WIDTH_M));
        // own width of the existing heat network depends on its DU (Table 1) and is supplied per object
        m.put(EXISTING_HEAT_NETWORK, new RestrictionRule(EXISTING_HEAT_NETWORK, SPECIAL, 1.0, false, 0, POINT_PLUS_EXTENT, 2.0, 1.05, 0));
        RULES = Collections.unmodifiableMap(m);
    }

    private RestrictionRules() {}

    public static Optional<RestrictionRule> forType(String restrictionType) {
        if (restrictionType == null) return Optional.empty();
        String t = restrictionType.trim().toLowerCase();
        RestrictionRule r = RULES.get(t);
        return Optional.ofNullable(r != null ? r : CUSTOM.get(t));
    }

    /** Registers a configured custom rule (application config); standard types cannot be overridden. */
    public static void registerCustom(RestrictionRule rule) {
        String t = rule.type().trim().toLowerCase();
        if (RULES.containsKey(t)) throw new IllegalArgumentException("standard restriction type '" + t + "' cannot be overridden by configuration");
        CUSTOM.put(t, rule);
    }

    public static Map<String, RestrictionRule> custom() { return Collections.unmodifiableMap(CUSTOM); }
    public static void clearCustom() { CUSTOM.clear(); }

    public static RestrictionRule require(String type) {
        return forType(type).orElseThrow(() -> new IllegalArgumentException("No rule for restriction type " + type));
    }

    public static RestrictionRule existingHeatNetwork() { return RULES.get(EXISTING_HEAT_NETWORK); }

    public static Map<String, RestrictionRule> all() { return RULES; }

    /** True for the types of Table 2 (the immutable standard configuration). */
    public static boolean isStandard(String type) { return type != null && RULES.containsKey(type.trim().toLowerCase()); }

    /** Default rule applied to unknown restriction types when the policy is FORBIDDEN. */
    public static RestrictionRule unknownAsForbidden(String type) {
        return new RestrictionRule(type, FORBIDDEN, 1.0, false, 0, NONE, 0, 1.0, 0);
    }
}
