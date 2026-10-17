package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConvexSegmentClipTest {
    private final GeometryFactory gf=new GeometryFactory();
    @Test void differentialAgainstOverlayForConvexBuffersAndBothWindings() {
        Random random=new Random(20261018);
        for(int shape=0;shape<12;shape++) {
            Coordinate[] points=new Coordinate[12];
            for(int i=0;i<points.length;i++)points[i]=new Coordinate(414000+random.nextDouble()*50,6173000+random.nextDouble()*50);
            Geometry zone=gf.createMultiPointFromCoords(points).convexHull().buffer(1.685,3);
            if(shape%2==0)zone=zone.reverse();
            ConvexSegmentClip clip=ConvexSegmentClip.prepare(zone);assertNotSame(ConvexSegmentClip.UNSUPPORTED,clip);
            for(int n=0;n<1000;n++) {
                Coordinate a=new Coordinate(413980+random.nextDouble()*90,6172980+random.nextDouble()*90);
                Coordinate b=new Coordinate(413980+random.nextDouble()*90,6172980+random.nextDouble()*90);
                equivalent(zone,clip,a,b);
            }
            Coordinate[] ring=zone.getCoordinates();
            for(int i=0;i<ring.length-1;i++) {
                equivalent(zone,clip,ring[i],ring[i+1]);
                equivalent(zone,clip,new Coordinate(ring[i].x-100,ring[i].y),new Coordinate(ring[i].x+100,ring[i].y));
            }
        }
    }
    private void equivalent(Geometry zone,ConvexSegmentClip clip,Coordinate a,Coordinate b) {
        Geometry expected=zone.intersection(gf.createLineString(new Coordinate[]{a,b}));
        double[] actual=clip.interval(a,b,a.distance(b));assertNotNull(actual);
        if(expected.isEmpty()){assertEquals(0,actual.length);return;}
        double lo=Double.POSITIVE_INFINITY,hi=0;
        for(Coordinate c:expected.getCoordinates()){double d=a.distance(c);lo=Math.min(lo,d);hi=Math.max(hi,d);}
        assertEquals(2,actual.length);assertEquals(lo,actual[0],1e-7);assertEquals(hi,actual[1],1e-7);
    }
    @Test void unsupportedShapesKeepTheOverlayFallback() throws Exception {
        var reader=new org.locationtech.jts.io.WKTReader();
        for(String wkt:List.of("POLYGON ((0 0, 10 0, 10 10, 5 5, 0 10, 0 0))",
                "POLYGON ((0 0,10 0,10 10,0 10,0 0),(2 2,2 4,4 4,4 2,2 2))",
                "MULTIPOLYGON (((0 0,1 0,1 1,0 0)),((3 3,4 3,4 4,3 3)))", "LINESTRING (0 0,10 0)", "POLYGON EMPTY"))
            assertSame(ConvexSegmentClip.UNSUPPORTED,ConvexSegmentClip.prepare(reader.read(wkt)));
        assertNull(ConvexSegmentClip.UNSUPPORTED.interval(new Coordinate(0,0),new Coordinate(1,1),Math.sqrt(2)));
    }
}
