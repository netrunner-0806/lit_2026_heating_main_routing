package ru.lct.heatnet.domain;

import org.locationtech.jts.geom.Point;

public final class ExistingChamber extends InputFeature {
    private final double[] wgs84;

    public ExistingChamber(JsonId id, Point metric, double[] wgs84, int featureIndex) {
        super(id, ObjectType.HEAT_CHAMBER, metric, featureIndex);
        this.wgs84 = wgs84;
    }

    public Point point() { return (Point) geometry(); }
    /** Original WGS84 coordinate [lon, lat] as given in the input (used verbatim in the output). */
    public double[] wgs84() { return wgs84; }
}
