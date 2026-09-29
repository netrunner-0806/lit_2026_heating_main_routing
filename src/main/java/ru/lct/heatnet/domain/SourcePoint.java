package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.Point;

public final class SourcePoint extends InputFeature {
    private final double[] wgs84;

    public SourcePoint(JsonId id, Point metric, double[] wgs84, int featureIndex) {
        super(id, ObjectType.SOURCE, metric, featureIndex);
        this.wgs84 = wgs84;
    }

    public double[] wgs84() { return wgs84; }
}
