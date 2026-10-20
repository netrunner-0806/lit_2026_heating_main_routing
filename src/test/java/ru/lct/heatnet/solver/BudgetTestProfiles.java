package ru.lct.heatnet.solver;

import ru.lct.heatnet.routing.ComputeBudget;

/** Explicit profiles used to verify budget and guard boundaries. */
final class BudgetTestProfiles {
    static ComputeBudget scale(ComputeBudget base, int nodes, int edges, int visibility) {
        for (int factor : new int[]{nodes, edges, visibility})
            if (factor < 1 || factor > 3) throw new IllegalArgumentException("Test multiplier must be 1..3");
        return new ComputeBudget(Math.multiplyExact(base.navigationNodes, nodes),
                Math.multiplyExact(base.directedEdges, edges),
                Math.multiplyExact(base.visibilityChecks, visibility), base.gridCells, base.candidateEvaluations);
    }
}
