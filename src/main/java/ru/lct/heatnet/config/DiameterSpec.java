package ru.lct.heatnet.config;

/**
 * One row of Table 1 of the technical annex: nominal diameter (DU), capacity, max continuous length,
 * construction cost per metre, pair width and height (design envelope).
 */
public final class DiameterSpec {
    private final int du;
    private final double capacityTph;
    private final double maxLengthM;
    private final double costPerMeter;
    private final double pairWidthM;
    private final double heightM;

    DiameterSpec(int du, double capacityTph, double maxLengthM, double costPerMeter, double pairWidthM, double heightM) {
        this.du = du;
        this.capacityTph = capacityTph;
        this.maxLengthM = maxLengthM;
        this.costPerMeter = costPerMeter;
        this.pairWidthM = pairWidthM;
        this.heightM = heightM;
    }

    public int du() { return du; }
    public double capacityTph() { return capacityTph; }
    public double maxLengthM() { return maxLengthM; }
    public double costPerMeter() { return costPerMeter; }
    public double pairWidthM() { return pairWidthM; }
    public double heightM() { return heightM; }
    /** Half of the design width of the pipe pair: distance from the axis to the outer edge of the envelope. */
    public double halfWidthM() { return pairWidthM / 2.0; }

    @Override
    public String toString() { return "DU" + du; }
}
