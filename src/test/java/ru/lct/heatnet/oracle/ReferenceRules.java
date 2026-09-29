package ru.lct.heatnet.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reference numeric tables of the CURRENT technical annex («Техническое приложение ЛЦТ.docx», sections 3–6) and the
 * clarifications («Разъяснения по вопросам ЛЦТ.docx»), re-typed by hand for the independent test oracle.
 *
 * <p>This class intentionally duplicates production constants: the oracle must not import anything from
 * {@code ru.lct.heatnet.config}, {@code cost}, {@code hydraulic} or {@code depth}, so that a wrong constant or
 * formula in production is caught instead of being reproduced.</p>
 */
final class ReferenceRules {

    private ReferenceRules() {}

    /** One row of table 1 of the annex. */
    static final class Du {
        final int du;
        final double capacityTph;
        final double maxLengthM;
        final double costPerMetre;
        final double widthM;
        final double heightM;

        Du(int du, double capacityTph, double maxLengthM, double costPerMetre, double widthM, double heightM) {
            this.du = du; this.capacityTph = capacityTph; this.maxLengthM = maxLengthM; this.costPerMetre = costPerMetre;
            this.widthM = widthM; this.heightM = heightM;
        }
    }

    /** Table 1 (ДУ, пропускная способность т/ч, предельная длина м, руб./м, ширина пары м, высота м). */
    static final List<Du> TABLE_1;
    static {
        List<Du> t = new ArrayList<>();
        t.add(new Du(50, 3.5, 181, 74_023, 0.400, 0.125));
        t.add(new Du(65, 8.3, 245, 78_631, 0.430, 0.140));
        t.add(new Du(80, 13.2, 327, 83_530, 0.470, 0.160));
        t.add(new Du(100, 22.3, 419, 89_748, 0.510, 0.180));
        t.add(new Du(125, 40.2, 554, 97_275, 0.600, 0.225));
        t.add(new Du(150, 65.1, 696, 105_507, 0.650, 0.250));
        t.add(new Du(200, 152.3, 1_042, 120_275, 0.880, 0.315));
        t.add(new Du(250, 274.9, 1_379, 135_323, 1.050, 0.400));
        t.add(new Du(300, 437.4, 1_718, 150_022, 1.150, 0.450));
        t.add(new Du(400, 943.1, 2_477, 190_299, 1.370, 0.560));
        t.add(new Du(500, 1_663.4, 3_245, 224_137, 1.670, 0.710));
        t.add(new Du(600, 2_627.7, 4_037, 264_790, 1.850, 0.800));
        t.add(new Du(700, 3_735.1, 4_775, 324_298, 2.050, 0.900));
        t.add(new Du(800, 5_296.8, 5_644, 325_996, 2.250, 1.000));
        t.add(new Du(900, 7_165.0, 6_518, 327_693, 2.450, 1.100));
        t.add(new Du(1000, 9_391.8, 7_419, 418_777, 2.650, 1.200));
        t.add(new Du(1200, 15_012.8, 9_288, 428_074, 3.100, 1.425));
        t.add(new Du(1400, 22_501.9, 11_276, 683_417, 3.450, 1.600));
        TABLE_1 = Collections.unmodifiableList(t);
    }

    static Du byDu(int du) {
        for (Du d : TABLE_1) if (d.du == du) return d;
        return null;
    }

    static boolean isOfficialDu(int du) { return byDu(du) != null; }

    /** Smallest DU whose capacity covers the flow (null if none). */
    static Du minByFlow(double flowTph) {
        for (Du d : TABLE_1) if (d.capacityTph >= flowTph) return d;
        return null;
    }

    /** Next smaller table DU below {@code du} (null at the bottom). */
    static Du nextSmaller(int du) {
        Du prev = null;
        for (Du d : TABLE_1) { if (d.du == du) return prev; prev = d; }
        return null;
    }

    /** Existing networks with a non-table diameter: the nearest table row (only used for the vertical height of an existing main). */
    static Du nearestExisting(int diameter) {
        Du best = TABLE_1.get(0);
        for (Du d : TABLE_1) if (Math.abs(d.du - diameter) < Math.abs(best.du - diameter)) best = d;
        return best;
    }

    // ---- section 3.2: chambers and tie-ins ----

    static long chamberCost(int maxAdjacentDu) {
        if (maxAdjacentDu <= 200) return 3_000_000L;
        if (maxAdjacentDu <= 500) return 5_000_000L;
        if (maxAdjacentDu <= 1000) return 8_000_000L;
        return 12_000_000L;
    }

    static final long EXISTING_CHAMBER_TIE_IN_COST = 5_000_000L;
    static final int MAX_CHAMBER_ADJACENCY = 4;
    static final double EXISTING_CHAMBER_RADIUS_M = 10.0;

    // ---- section 6: penalties and score ----

    static double penalty(double flowTph) { return 100_000_000.0 + 500_000.0 * flowTph; }

    static double score(double calculatedCost, double newLength) { return 0.7 * (calculatedCost / 25_000_000.0) + 0.3 * (newLength / 100.0); }

    // ---- table 2: restriction rules ----

    /** Kspec for special passages; NaN for types that are not special passages / unknown. */
    static double kSpec(String type) {
        switch (type) {
            case "road": return 1.60;
            case "tram_tracks": return 1.75;
            case "gas_pipeline": return 1.25;
            case "power_cable": return 1.15;
            case "heat_network": return 1.05;
            default: return Double.NaN;
        }
    }

    static boolean crossingForbidden(String type) {
        switch (type) {
            case "oks": case "park": case "social_area": case "prohibited_site": case "water": case "railway": return true;
            default: return false;
        }
    }

    /** Minimum horizontal distance (m) from the object to the outer edge of the new pair envelope. */
    static double clearance(String type, int du) {
        switch (type) {
            case "oks": return du < 500 ? 5.0 : du <= 800 ? 7.0 : 9.0;
            case "park": case "social_area": case "prohibited_site": case "water": case "railway": return 1.0;
            case "road": case "tram_tracks": return 1.5;
            case "gas_pipeline": case "power_cable": return 2.0;
            case "heat_network": return 1.0;
            default: return Double.NaN;
        }
    }

    /** Special section extent: beyond the polygon border (road/tram) or each side of the crossing point (utilities). */
    static double specialExtent(String type) {
        switch (type) {
            case "road": case "tram_tracks": return 3.0;
            case "gas_pipeline": case "power_cable": case "heat_network": return 2.0;
            default: return Double.NaN;
        }
    }

    static double minCrossingAngleDeg(String type) {
        switch (type) {
            case "road": case "tram_tracks": return 45.0;
            default: return 0.0;
        }
    }

    static final double MAX_TURN_DEG = 90.0;

    // ---- section 5: depth ----

    static final double NORMAL_DEPTH_M = 3.0;
    static final double MIN_DEPTH_M = 0.7;
    static final double MAX_SLOPE = 0.10;

    static double kDepth(double h) { return h <= NORMAL_DEPTH_M ? 1.0 : 1.0 + 0.10 * (h - NORMAL_DEPTH_M); }

    /** Uniform ramp: arithmetic mean of Kгл at both ends. */
    static double kDepthRamp(double h1, double h2) { return (kDepth(h1) + kDepth(h2)) / 2.0; }

    /** Minimal depth of the top of the new envelope under a road / tram polygon. */
    static double minDepthUnder(String type) {
        switch (type) {
            case "road": return 1.0;
            case "tram_tracks": return 1.2;
            default: return Double.NaN;
        }
    }

    /** Conventional envelope of a crossed utility: depth to its top, its height, and the required vertical clearance. */
    static double utilityTop(String type, int existingDu) {
        switch (type) {
            case "gas_pipeline": return 2.8;
            case "power_cable": return 2.7;
            case "heat_network": return 3.0;
            default: return Double.NaN;
        }
    }

    static double utilityHeight(String type, int existingDu) {
        switch (type) {
            case "gas_pipeline": return 0.40;
            case "power_cable": return 0.20;
            case "heat_network": return nearestExisting(existingDu).heightM;
            default: return Double.NaN;
        }
    }

    static double verticalClearance(String type) {
        switch (type) {
            case "gas_pipeline": return 0.2;
            case "power_cable": return 0.5;
            case "heat_network": return 0.5;
            default: return Double.NaN;
        }
    }

    static boolean isUtility(String type) { return "gas_pipeline".equals(type) || "power_cable".equals(type) || "heat_network".equals(type); }
}
