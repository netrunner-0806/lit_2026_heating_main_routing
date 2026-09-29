package ru.lct.heatnet.config;

import java.util.Objects;

/**
 * Rule for one restriction type (Table 2 of the technical annex) in the 2D mode.
 */
public final class RestrictionRule {

    public enum Kind {
        /** Crossing forbidden; keep the minimal horizontal clearance. */
        FORBIDDEN,
        /** Crossing allowed only as a special passage (single straight section, Kspec). */
        SPECIAL
    }

    /** How the special section boundaries are defined. */
    public enum SectionMode {
        /** No special section (FORBIDDEN objects). */
        NONE,
        /** Polygon (or line) of the object plus {@code sectionExtentM} beyond its boundary on both sides. */
        POLYGON_PLUS_EXTENT,
        /** {@code sectionExtentM} to each side of the crossing point (linear objects). */
        POINT_PLUS_EXTENT
    }

    private final String type;
    private final Kind kind;
    /** Base horizontal clearance, metres (for oks depends on DU; see {@link #clearanceM(int)}). */
    private final double clearanceM;
    private final boolean clearanceDependsOnDu;
    /** Minimum crossing angle in degrees, or 0 if not required. */
    private final double minCrossingAngleDeg;
    private final SectionMode sectionMode;
    private final double sectionExtentM;
    private final double kSpec;
    /** Own design envelope width of the object (linear objects), metres; 0 if none. */
    private final double ownWidthM;

    public RestrictionRule(String type, Kind kind, double clearanceM, boolean clearanceDependsOnDu,
                           double minCrossingAngleDeg, SectionMode sectionMode, double sectionExtentM,
                           double kSpec, double ownWidthM) {
        this.type = Objects.requireNonNull(type);
        this.kind = kind;
        this.clearanceM = clearanceM;
        this.clearanceDependsOnDu = clearanceDependsOnDu;
        this.minCrossingAngleDeg = minCrossingAngleDeg;
        this.sectionMode = sectionMode;
        this.sectionExtentM = sectionExtentM;
        this.kSpec = kSpec;
        this.ownWidthM = ownWidthM;
    }

    public String type() { return type; }
    public Kind kind() { return kind; }
    public boolean isForbidden() { return kind == Kind.FORBIDDEN; }
    public boolean isSpecial() { return kind == Kind.SPECIAL; }
    public double minCrossingAngleDeg() { return minCrossingAngleDeg; }
    public SectionMode sectionMode() { return sectionMode; }
    public double sectionExtentM() { return sectionExtentM; }
    public double kSpec() { return kSpec; }
    public double ownWidthM() { return ownWidthM; }
    public boolean clearanceDependsOnDu() { return clearanceDependsOnDu; }

    /** Minimal horizontal clearance (between envelopes / from the object geometry) for a new line of the given DU. */
    public double clearanceM(int du) {
        if (!clearanceDependsOnDu) return clearanceM;
        return oksClearance(du);
    }

    /** ОКС: 5 m for DU < 500, 7 m for 500..800, 9 m for DU >= 900. */
    public static double oksClearance(int du) {
        if (du < 500) return 5.0;
        if (du <= 800) return 7.0;
        return 9.0;
    }

    /**
     * Required distance from the axis of the new line to the object geometry, metres:
     * clearance + half width of the new pair + half width of the object envelope (if any).
     */
    public double requiredAxisDistanceM(int du) {
        return clearanceM(du) + DiameterTable.requireDu(du).halfWidthM() + ownWidthM / 2.0;
    }

    public double requiredAxisDistanceM(int du, double objectHalfWidthM) {
        return clearanceM(du) + DiameterTable.requireDu(du).halfWidthM() + objectHalfWidthM;
    }

    /** Representative (largest) DU of the clearance class of the given DU: 400 / 800 / 1400. */
    public static int clearanceClassDu(int du) {
        if (du < 500) return 400;
        if (du <= 800) return 800;
        return 1400;
    }

    /**
     * Radius around a crossing point inside which the alongside clearance of this object is not checked
     * (special section extent + twice the class clearance): the crossing must leave the clearance zone within it.
     */
    public double crossingCoverRadiusM(int du, double objectHalfWidthM) {
        return sectionExtentM + 2.0 * requiredAxisDistanceM(clearanceClassDu(du), objectHalfWidthM);
    }

    /** Radius around a tie-in point inside which the existing network is not checked for clearance (twice the class clearance). */
    public double tieInExemptRadiusM(int du, double objectHalfWidthM) {
        return 2.0 * requiredAxisDistanceM(clearanceClassDu(du), objectHalfWidthM);
    }

    @Override
    public String toString() { return type + "/" + kind; }
}
