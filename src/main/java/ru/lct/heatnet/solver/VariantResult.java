package ru.lct.heatnet.solver;

import ru.lct.heatnet.cost.CostSummary;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One built variant before validation/ranking. */
public final class VariantResult {
    private final String strategy;
    private final NewNetwork network;
    private final Map<ConnectionPoint, String> unconnected;
    private final CostSummary summary;
    private final List<String> notes;
    private String variantId;
    private int rank;
    private VariantMetrics metrics;
    private VariantMetrics.Diff diffVsBest;
    private CalculationTrace trace;

    public VariantResult(String strategy, NewNetwork network, Map<ConnectionPoint, String> unconnected, CostSummary summary, List<String> notes) {
        this.strategy = strategy;
        this.network = network;
        this.unconnected = Collections.unmodifiableMap(new LinkedHashMap<>(unconnected));
        this.summary = summary;
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    public String strategy() { return strategy; }
    public NewNetwork network() { return network; }
    public Map<ConnectionPoint, String> unconnected() { return unconnected; }
    public CostSummary summary() { return summary; }
    public List<String> notes() { return notes; }
    public String variantId() { return variantId; }
    public int rank() { return rank; }
    public void setVariantId(String id) { this.variantId = id; }
    public void setRank(int rank) { this.rank = rank; }
    public int connectedCount() { return network.connectionNodes().size(); }
    public VariantMetrics metrics() {
        if (metrics == null) metrics = VariantMetrics.of(network);
        return metrics;
    }
    public VariantMetrics.Diff diffVsBest() { return diffVsBest; }
    public CalculationTrace trace() { return trace; }
    /** Mode of the calculation that produced the variant (also when no link exists, e.g. every consumer unreachable). */
    public boolean depthMode() { return depthMode; }
    public void setDepthMode(boolean depthMode) { this.depthMode = depthMode; }
    private boolean depthMode;
    public void setTrace(CalculationTrace t) { this.trace = t; }
    public void setDiffVsBest(VariantMetrics.Diff d) { this.diffVsBest = d; }
}
