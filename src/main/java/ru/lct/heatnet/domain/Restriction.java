package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.config.RestrictionRule;

/** Spatial restriction (any geometry type) with its resolved rule (may be null = ignored). */
public final class Restriction extends InputFeature {
    private final String restrictionType;
    private final RestrictionRule rule;

    public Restriction(JsonId id, Geometry metric, String restrictionType, RestrictionRule rule, int featureIndex) {
        super(id, ObjectType.RESTRICTION, metric, featureIndex);
        this.restrictionType = restrictionType;
        this.rule = rule;
    }

    public String restrictionType() { return restrictionType; }
    /** Resolved rule or null when the type is unknown and ignored. */
    public RestrictionRule rule() { return rule; }
    public boolean isPolygonal() { return geometry() instanceof org.locationtech.jts.geom.Polygonal; }
    public boolean isOks() { return ru.lct.heatnet.config.RestrictionRules.OKS.equals(restrictionType); }
}
