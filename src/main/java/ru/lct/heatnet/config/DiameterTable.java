package ru.lct.heatnet.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Immutable Table 1 from the current technical annex (ЛЦТ 2026):
 * nominal diameter, capacity (t/h), max continuous length (m), construction cost (rub/m), pair width (m), height (m).
 * This is the single source of truth for diameter-related numbers in the project.
 */
public final class DiameterTable {

    private static final List<DiameterSpec> ROWS = Collections.unmodifiableList(java.util.Arrays.asList(
            new DiameterSpec(50, 3.5, 181, 74_023, 0.400, 0.125),
            new DiameterSpec(65, 8.3, 245, 78_631, 0.430, 0.140),
            new DiameterSpec(80, 13.2, 327, 83_530, 0.470, 0.160),
            new DiameterSpec(100, 22.3, 419, 89_748, 0.510, 0.180),
            new DiameterSpec(125, 40.2, 554, 97_275, 0.600, 0.225),
            new DiameterSpec(150, 65.1, 696, 105_507, 0.650, 0.250),
            new DiameterSpec(200, 152.3, 1_042, 120_275, 0.880, 0.315),
            new DiameterSpec(250, 274.9, 1_379, 135_323, 1.050, 0.400),
            new DiameterSpec(300, 437.4, 1_718, 150_022, 1.150, 0.450),
            new DiameterSpec(400, 943.1, 2_477, 190_299, 1.370, 0.560),
            new DiameterSpec(500, 1_663.4, 3_245, 224_137, 1.670, 0.710),
            new DiameterSpec(600, 2_627.7, 4_037, 264_790, 1.850, 0.800),
            new DiameterSpec(700, 3_735.1, 4_775, 324_298, 2.050, 0.900),
            new DiameterSpec(800, 5_296.8, 5_644, 325_996, 2.250, 1.000),
            new DiameterSpec(900, 7_165.0, 6_518, 327_693, 2.450, 1.100),
            new DiameterSpec(1000, 9_391.8, 7_419, 418_777, 2.650, 1.200),
            new DiameterSpec(1200, 15_012.8, 9_288, 428_074, 3.100, 1.425),
            new DiameterSpec(1400, 22_501.9, 11_276, 683_417, 3.450, 1.600)
    ));

    private DiameterTable() {}

    public static List<DiameterSpec> rows() { return ROWS; }

    public static DiameterSpec largest() { return ROWS.get(ROWS.size() - 1); }

    public static Optional<DiameterSpec> byDu(int du) {
        for (DiameterSpec s : ROWS) if (s.du() == du) return Optional.of(s);
        return Optional.empty();
    }

    public static DiameterSpec requireDu(int du) {
        return byDu(du).orElseThrow(() -> new IllegalArgumentException("Unknown nominal diameter DU=" + du));
    }

    public static int indexOf(int du) {
        for (int i = 0; i < ROWS.size(); i++) if (ROWS.get(i).du() == du) return i;
        throw new IllegalArgumentException("Unknown nominal diameter DU=" + du);
    }

    /** Minimal DU whose capacity is not less than the flow. Empty if the flow exceeds the largest DU. */
    public static Optional<DiameterSpec> minByFlow(double flowTph) {
        for (DiameterSpec s : ROWS) if (s.capacityTph() + 1e-9 >= flowTph) return Optional.of(s);
        return Optional.empty();
    }

    /** Minimal DU that is >= the given DU index and satisfies flow and max length. */
    public static Optional<DiameterSpec> minSatisfying(double flowTph, double continuousLengthM, int minDu) {
        for (DiameterSpec s : ROWS) {
            if (s.du() < minDu) continue;
            if (s.capacityTph() + 1e-9 >= flowTph && s.maxLengthM() + 1e-6 >= continuousLengthM) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Next larger DU or empty if the given one is the largest. */
    public static Optional<DiameterSpec> next(int du) {
        int i = indexOf(du);
        return i + 1 < ROWS.size() ? Optional.of(ROWS.get(i + 1)) : Optional.empty();
    }

    /** Nearest known DU for an existing network line (input diameters may be arbitrary integers). */
    public static DiameterSpec nearestForExisting(int diameter) {
        DiameterSpec best = ROWS.get(0);
        for (DiameterSpec s : ROWS) {
            if (Math.abs(s.du() - diameter) < Math.abs(best.du() - diameter)) best = s;
        }
        return best;
    }
}
