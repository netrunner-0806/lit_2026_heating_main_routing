package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrsTransformerTest {

    private static final CrsTransformer T = CrsTransformer.get();

    @Test
    void wgs84ToUtm37NMatchesProjReference() {
        // reference values computed with PROJ (pyproj) for EPSG:4326 -> EPSG:32637
        Coordinate c = T.toMetric(37.6, 55.75);
        assertEquals(412125.4591875144, c.x, 0.002);
        assertEquals(6179143.32361831, c.y, 0.002);
        Coordinate c2 = T.toMetric(37.6262685, 55.690915329577294);
        assertEquals(413643.866390186, c2.x, 0.002);
        assertEquals(6172535.3570095375, c2.y, 0.002);
        Coordinate c3 = T.toMetric(39.0, 56.0); // central meridian of zone 37 -> false easting 500000
        assertEquals(500000.0, c3.x, 0.002);
        assertEquals(6206079.587252156, c3.y, 0.002);
    }

    @Test
    void roundTripIsStable() {
        double[] ll = T.toWgs84(414596.7065736831, 6173789.945979644);
        Coordinate back = T.toMetric(ll[0], ll[1]);
        assertEquals(414596.7065736831, back.x, 1e-6);
        assertEquals(6173789.945979644, back.y, 1e-6);
    }

    @Test
    void exampleLineOfTheAnnexIs100Metres() {
        // Section 7.3 of the technical annex: the example line is 100 m long
        LineString wgs = CrsTransformer.WGS84_FACTORY.createLineString(new Coordinate[]{
                new Coordinate(37.600000000, 55.750000000), new Coordinate(37.599967825, 55.750898263)});
        LineString utm = (LineString) T.toMetric(wgs);
        assertEquals(100.0, utm.getLength(), 0.01);
    }
}
