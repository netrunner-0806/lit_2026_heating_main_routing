package ru.lct.heatnet.domain;

import java.util.Collections;
import java.util.List;

/** Normalised input in EPSG:32637 plus the diagnostics collected while parsing. */
public final class InputModel {
    private final List<SourcePoint> sources;
    private final List<ExistingNetworkLine> networkLines;
    private final List<ExistingChamber> chambers;
    private final List<ConnectionPoint> connectionPoints;
    private final List<Restriction> restrictions;
    private final Diagnostics diagnostics;
    private final int featureCount;

    public InputModel(List<SourcePoint> sources, List<ExistingNetworkLine> networkLines, List<ExistingChamber> chambers,
                      List<ConnectionPoint> connectionPoints, List<Restriction> restrictions, Diagnostics diagnostics, int featureCount) {
        this.sources = Collections.unmodifiableList(sources);
        this.networkLines = Collections.unmodifiableList(networkLines);
        this.chambers = Collections.unmodifiableList(chambers);
        this.connectionPoints = Collections.unmodifiableList(connectionPoints);
        this.restrictions = Collections.unmodifiableList(restrictions);
        this.diagnostics = diagnostics;
        this.featureCount = featureCount;
    }

    public List<SourcePoint> sources() { return sources; }
    public List<ExistingNetworkLine> networkLines() { return networkLines; }
    public List<ExistingChamber> chambers() { return chambers; }
    public List<ConnectionPoint> connectionPoints() { return connectionPoints; }
    public List<Restriction> restrictions() { return restrictions; }
    public Diagnostics diagnostics() { return diagnostics; }
    public int featureCount() { return featureCount; }
}
