package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.config.DiameterSpec;

/** Existing heat network line: a pair of pipes of one DU, no reconstruction in the current model. */
public final class ExistingNetworkLine extends InputFeature {
    private final int diameter;
    private final DiameterSpec spec;
    private final boolean diameterMissing;

    public ExistingNetworkLine(JsonId id, LineString metric, int diameter, DiameterSpec spec, boolean diameterMissing, int featureIndex) {
        super(id, ObjectType.HEAT_NETWORK, metric, featureIndex);
        this.diameter = diameter;
        this.spec = spec;
        this.diameterMissing = diameterMissing;
    }

    public LineString line() { return (LineString) geometry(); }
    public int diameter() { return diameter; }
    /** Nearest Table-1 spec, used for the envelope width when crossing / passing alongside. */
    public DiameterSpec spec() { return spec; }
    public boolean diameterMissing() { return diameterMissing; }
    public double halfWidthM() { return spec.halfWidthM(); }
}
