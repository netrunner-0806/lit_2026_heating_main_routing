package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;
import static org.junit.jupiter.api.Assertions.*;

class RobustGeometryTest {
    @Test void numericalOverlayFailureRetriesWithoutChangingInputs() throws Exception {
        // Minimal published JTS regression: https://github.com/locationtech/jts/issues/288
        // Fixed high precision is deliberate to exercise the numeric failure, not a production precision policy.
        WKTReader r=new WKTReader(new GeometryFactory(new PrecisionModel(2E10)));
        Geometry a=r.read("POLYGON ((-25 700, -25 327.5217935613, -24.9999982443 71.51813717395, 2000 70, -25 700))");
        Geometry b=r.read("POLYGON ((-24.9999998349 674.30377216845, -25 674.30377216845, -24.99999893265 171.8884408023, -24.99999871375 139.9692190221, -25 139.9692190221, -25 20, 900 800, -25 700, -24.9999998349 674.30377216845))");
        assertTrue(a.isValid());assertTrue(b.isValid());String aw=a.toText(),bw=b.toText();
        assertThrows(TopologyException.class,()->a.intersection(b));
        RobustGeometry.Stats stats=new RobustGeometry.Stats();
        Geometry intersection=RobustGeometry.intersection(a,b,"test near-coincident boundary",stats);
        assertTrue(intersection.isValid());assertTrue(intersection.getArea()>0);
        Geometry union=RobustGeometry.union(java.util.Arrays.asList(a,b),"test union",stats);
        assertTrue(union.isValid());assertEquals(a.getArea()+b.getArea(),union.getArea()+intersection.getArea(),1e-5);
        assertEquals(aw,a.toText());assertEquals(bw,b.toText());assertTrue(stats.retries.sum()>0);
    }

    @Test void ordinaryOverlayIsByteEquivalentToLegacy() throws Exception {
        WKTReader r=new WKTReader();Geometry a=r.read("POLYGON ((0 0,10 0,10 10,0 10,0 0))");
        Geometry b=r.read("LINESTRING (-1 5,11 5)");RobustGeometry.Stats stats=new RobustGeometry.Stats();
        assertEquals(a.intersection(b).toText(),RobustGeometry.intersection(a,b,"ordinary",stats).toText());
        assertEquals(0,stats.retries.sum());assertEquals(1,stats.intersections.sum());
    }
}
