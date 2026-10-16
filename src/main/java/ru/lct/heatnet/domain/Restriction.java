package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.config.RestrictionRule;

/** Spatial restriction (any geometry type) with its resolved rule (may be null = ignored). */
public final class Restriction extends InputFeature {
    private final String restrictionType;
    private final RestrictionRule rule;
    private final Geometry originalGeometry;

    public Restriction(JsonId id, Geometry metric, String restrictionType, RestrictionRule rule, int featureIndex) {
        this(id, metric, restrictionType, rule, featureIndex, metric);
    }

    public Restriction(JsonId id, Geometry metric, String restrictionType, RestrictionRule rule, int featureIndex, Geometry originalGeometry) {
        super(id, ObjectType.RESTRICTION, metric, featureIndex);
        this.restrictionType = restrictionType;
        this.rule = rule;
        this.originalGeometry = originalGeometry;
    }

    public String restrictionType() { return restrictionType; }
    /** Original metric geometry, retained when an invalid polygon was repaired for explicit diagnostics/review. */
    public Geometry originalGeometry() { return originalGeometry; }
    /** Resolved rule or null when the type is unknown and ignored. */
    public RestrictionRule rule() { return rule; }
    public boolean isPolygonal() { return geometry() instanceof org.locationtech.jts.geom.Polygonal; }
    public boolean isOks() { return ru.lct.heatnet.config.RestrictionRules.OKS.equals(restrictionType); }
}
