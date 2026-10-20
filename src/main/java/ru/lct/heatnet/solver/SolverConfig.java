package ru.lct.heatnet.solver;

/** Tunable solver parameters (not rules of the annex). */
public final class SolverConfig {
    public enum ExecutionMode { SEQUENTIAL, PARALLEL_2 }
    public ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;
    /** Soft admission forecast, with a separate critical running-resource stop. No JVM settings are changed. */
    public long parallelWorkingSetBytes = 7L << 30;
    public long parallelCriticalHeapBytes = 9L << 30;
    public long parallelCriticalRssBytes = 11L << 30;
    public long parallelOverlayReserveBytes = 128L << 20;
    private final ru.lct.heatnet.routing.ComputeBudget sequentialDefault = ru.lct.heatnet.routing.ComputeBudget.production();
    private final ru.lct.heatnet.routing.ComputeBudget parallelDefault = ru.lct.heatnet.routing.ComputeBudget.parallel2Default();
    private ru.lct.heatnet.routing.ComputeBudget automaticBudget = sequentialDefault;
    private boolean explicitBudget;
    /** Resolved at execution/context creation. Configure explicit overrides through useBudget(). */
    public ru.lct.heatnet.routing.ComputeBudget computeBudget = automaticBudget;
    public final ru.lct.heatnet.routing.ComputeCancellation cancellation = new ru.lct.heatnet.routing.ComputeCancellation();
    public void useBudget(ru.lct.heatnet.routing.ComputeBudget budget) { computeBudget=java.util.Objects.requireNonNull(budget);explicitBudget=true;maxCandidateEvaluations=budget.candidateEvaluations; }

    /** Select a profile once, never multiply an existing budget. Configure before starting a job. */
    public synchronized void resolveExecutionBudget() {
        // Preserve both explicit useBudget() and legacy direct field overrides.
        if(explicitBudget || computeBudget!=automaticBudget)return;
        var selected=executionMode==ExecutionMode.PARALLEL_2?parallelDefault:sequentialDefault;
        if(selected==automaticBudget)return;
        if(maxCandidateEvaluations==automaticBudget.candidateEvaluations)
            maxCandidateEvaluations=selected.candidateEvaluations;
        computeBudget=selected;automaticBudget=selected;
    }

    /** Max distance between static navigation vertices connected by a visibility edge, m. */
    public double staticRadius = 300;
    /** Spacing of helper free-space vertices ensuring connectivity across open areas, m (0 = none). */
    public double helperGridSpacing = 80;
    /** Select navigation candidates locally only on larger scenes; all restrictions remain in the check index. */
    public int localGraphMinimumObstacles = 2048;
    /** Initial working margin around ALL consumers, existing network lines and chambers; failure expands it. */
    public double localGraphMargin = 600;
    /** Visibility radius of terminals (connection points, tie-in and junction candidates), m. */
    public double terminalRadius = 600;
    /** Fallback sampling step of tie-in candidates along existing lines, m (geometric candidates are added on top). */
    public double tieInStep = 4;
    /** Visibility radius of tie-in candidate vertices, m (connection points keep {@link #terminalRadius}). */
    public double tieInRadius = 300;
    /** Tie-in candidates closer than this to an already accepted candidate are dropped, m. */
    public double tieInDedupe = 1.0;
    /** Navigation vertices closer than this to an existing line contribute their projection as a candidate, m. */
    public double tieInProjectionDistance = 60;
    /** Offset of corridor-boundary candidates from the edge of a forbidden clearance zone along the line, m. */
    public double tieInCorridorOffset = 0.3;
    /** Sampling step of junction candidates along new links, m. */
    public double junctionStep = 20;
    /** Number of best candidates (by estimate) evaluated exactly per connection point. */
    public int topK = 12;
    /** Small penalty per turn (metres of equivalent length) to prefer straighter routes. */
    public double turnPenalty = 0.3;
    /** Hard rule between distinct new lines: no intersection/overlap outside a common node (numerical tolerance, m). */
    public double newLineHardDistance = ru.lct.heatnet.geo.GeometryTolerance.NEW_LINE_HARD_DISTANCE_M;
    /** Soft routing preference: extra separation between distinct new lines beyond the full pair width, m. */
    public double separationExtra = 1.0;
    /** Max variants to return. */
    public int maxVariants = ru.lct.heatnet.config.Constants.MAX_VARIANTS;
    /** Run the local improvement pass after the greedy construction. */
    public boolean improve = true;
    /** Max reroute iterations for clearance class upgrades. */
    public int classIterations = 3;
    /** Depth mode: vertical profiles, Kdepth and depth-aware candidate evaluation (separate calculation mode). */
    public boolean depthMode = false;
    /** Depth mode: initial number of alternative horizontal routes (avoiding the crossings of the previous one) evaluated per target. */
    public int depthRouteAlternatives = 2;
    /** Depth mode: the alternatives widen 2 -> 4 -> 8 while every route of a candidate is vertically infeasible, up to this cap. */
    public int depthRouteAlternativesMax = 8;
    /**
     * Safety budget: hard cap of exactly evaluated attachment candidates per approach. Stage 1 evaluates {@link #topK}
     * candidates (continuing up to 8 x topK while none is feasible); if a point would otherwise stay unconnected, stage 2
     * widens the search over all reachable candidates up to this deterministic cap.
     */
    public int maxCandidateEvaluations = 1000;
    /** Legacy configuration field, ignored: wall-clock limits made routing CPU-load dependent. */
    @Deprecated
    public long wideningTimeBudgetMs = 15_000;
    /** Local improvement: max passes of leaf re-attachment (each pass stops early when nothing improves). */
    public int improvePasses = 1;

    public static SolverConfig defaults() { return new SolverConfig(); }
}
