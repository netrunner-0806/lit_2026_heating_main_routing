package ru.lct.heatnet.cost;

/** Cost breakdown of one variant (values in rubles / metres). */
public final class CostSummary {
    private final double lineCost;
    private final double chamberCost;
    private final int tieInCount;
    private final double tieInCost;
    private final double penalty;
    private final double constructionCost;
    private final double calculatedCost;
    private final double newLength;
    private final double score;

    public CostSummary(double lineCost, double chamberCost, int tieInCount, double tieInCost, double penalty,
                       double constructionCost, double calculatedCost, double newLength, double score) {
        this.lineCost = lineCost;
        this.chamberCost = chamberCost;
        this.tieInCount = tieInCount;
        this.tieInCost = tieInCost;
        this.penalty = penalty;
        this.constructionCost = constructionCost;
        this.calculatedCost = calculatedCost;
        this.newLength = newLength;
        this.score = score;
    }

    public double lineCost() { return lineCost; }
    public double chamberCost() { return chamberCost; }
    public int tieInCount() { return tieInCount; }
    public double tieInCost() { return tieInCost; }
    public double penalty() { return penalty; }
    public double constructionCost() { return constructionCost; }
    public double calculatedCost() { return calculatedCost; }
    public double newLength() { return newLength; }
    public double score() { return score; }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "cost=%.0f (lines %.0f, chambers %.0f, tie-ins %d/%.0f, penalty %.0f) length=%.1f score=%.4f",
                calculatedCost, lineCost, chamberCost, tieInCount, tieInCost, penalty, newLength, score);
    }
}
