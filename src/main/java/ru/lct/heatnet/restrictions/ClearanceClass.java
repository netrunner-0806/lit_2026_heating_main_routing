package ru.lct.heatnet.restrictions;

import ru.lct.heatnet.config.DiameterTable;

/**
 * Clearance classes of the ОКС rule (5 / 7 / 9 m). Routing is done per class with the envelope half-width of the
 * largest DU of the class, so any DU of the class satisfies the clearance found by routing.
 */
public enum ClearanceClass {
    SMALL(400),    // DU < 500
    MEDIUM(800),   // DU 500..800
    LARGE(1400);   // DU >= 900

    private final int representativeDu;

    ClearanceClass(int representativeDu) { this.representativeDu = representativeDu; }

    /** Largest DU of the class: its half width is an upper bound for the class. */
    public int representativeDu() { return representativeDu; }

    public double halfWidthM() { return DiameterTable.requireDu(representativeDu).halfWidthM(); }

    public static ClearanceClass forDu(int du) {
        if (du < 500) return SMALL;
        if (du <= 800) return MEDIUM;
        return LARGE;
    }

    public ClearanceClass max(ClearanceClass other) { return ordinal() >= other.ordinal() ? this : other; }
}
