package ru.lct.heatnet.config;

/**
 * Section 3.2 of the technical annex: cost of a new heat chamber by the largest DU of adjacent sections,
 * and the tie-in cost into an existing chamber.
 */
public final class ChamberCostTable {
    private ChamberCostTable() {}

    /** Cost of one tie-in (each new line ending in an EXISTING chamber), rub. */
    public static final double EXISTING_CHAMBER_TIE_IN_COST = 5_000_000d;

    public static double newChamberCost(int maxAdjacentDu) {
        if (maxAdjacentDu <= 200) return 3_000_000d;
        if (maxAdjacentDu <= 500) return 5_000_000d;
        if (maxAdjacentDu <= 1000) return 8_000_000d;
        return 12_000_000d;
    }
}
