package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

/** Base class of a parsed input feature: id, metric geometry (EPSG:32637) and the original WGS84 coordinates. */
public abstract class InputFeature {
    private final JsonId id;
    private final ObjectType objectType;
    private final Geometry metricGeometry;
    private final int featureIndex;

    protected InputFeature(JsonId id, ObjectType objectType, Geometry metricGeometry, int featureIndex) {
        this.id = id;
        this.objectType = objectType;
        this.metricGeometry = metricGeometry;
        this.featureIndex = featureIndex;
    }

    public JsonId id() { return id; }
    public ObjectType objectType() { return objectType; }
    /** Geometry in EPSG:32637. */
    public Geometry geometry() { return metricGeometry; }
    public int featureIndex() { return featureIndex; }

    /** Point-like features expose their metric coordinate. */
    public Coordinate coordinate() { return metricGeometry.getCoordinate(); }

    @Override
    public String toString() { return objectType.code() + "#" + id; }
}
