package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.Point;

/** oks_connection_point: an independent consumer with its own design flow. */
public final class ConnectionPoint extends InputFeature {
    private final double flowTph;
    private final double[] wgs84;

    public ConnectionPoint(JsonId id, Point metric, double flowTph, double[] wgs84, int featureIndex) {
        super(id, ObjectType.OKS_CONNECTION_POINT, metric, featureIndex);
        this.flowTph = flowTph;
        this.wgs84 = wgs84;
    }

    public Point point() { return (Point) geometry(); }
    public double flowTph() { return flowTph; }
    public double[] wgs84() { return wgs84; }
}
