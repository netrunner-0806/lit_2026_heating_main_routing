package ru.lct.heatnet.routing;

/** Immutable computational bounds, independent of engineering rules. */
public final class ComputeBudget {
    public final int navigationNodes, directedEdges, gridCells, candidateEvaluations;
    public final long visibilityChecks;
    public ComputeBudget(int nodes, int edges, long checks, int cells, int candidates) {
        if (nodes < 1 || edges < 1 || checks < 1 || cells < 1 || candidates < 1)
            throw new IllegalArgumentException("Computational budgets must be positive");
        navigationNodes=nodes; directedEdges=edges; visibilityChecks=checks; gridCells=cells; candidateEvaluations=candidates;
    }
    public static ComputeBudget production() { return new ComputeBudget(100_000,4_000_000,50_000_000,100_000,1000); }
    public static ComputeBudget expanded() { return new ComputeBudget(130_000,5_200_000,65_000_000,130_000,1300); }
    /** Measured ×2 profile: only nodes, edges and visibility are doubled relative to expanded(). */
    public static ComputeBudget parallel2Default() { return new ComputeBudget(260_000,10_400_000,130_000_000,130_000,1300); }
}
