package ru.lct.heatnet.api.dto;

import java.util.List;

/** Summary of one variant as returned by GET /jobs/{id}/result. */
public class VariantSummaryDto {
    public String variantId;
    public int rank;
    public String strategy;
    public double score;
    public double constructionCost;
    public double networkConstructionCost;
    public double chamberConstructionCost;
    public int existingChamberTieInCount;
    public double existingChamberTieInCost;
    public double unconnectedPenalty;
    public double calculatedCost;
    public double newNetworkLength;
    public int connectedOksCount;
    public int newChamberCount;
    public int technicalNodeCount;
    public int lineCount;
    public List<Object> unconnectedOksIds;
    public List<String> notes;
    /** Length of sections shared by two or more consumers, m. */
    public double sharedNetworkLength;
    /** Number of distinct attachment points on the existing network. */
    public int rootCount;
    public List<String> attachmentPoints;
    /** Automatically computed difference to the rank-1 variant (null for rank 1). */
    public ru.lct.heatnet.solver.VariantMetrics.Diff diffVsBest;
    public String mode;
    public int specialCrossingCount;
    /** Depth mode figures (null in the planar mode). */
    public Double maxDepth;
    public Double averageDepth;
    public Double depthExtraCost;
    public Integer verticalCrossingCount;
    public Integer aboveCrossingCount;
    public Integer belowCrossingCount;
    public Integer depthTransitionCount;
    public Double deepNetworkLength;
    public Double shallowNetworkLength;
}
