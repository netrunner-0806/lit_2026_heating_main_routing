package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.algorithm.Distance;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundarySegmentsTest {
    @Test void indexedAnglesMatchExhaustiveSearchIncludingCornersAndFarQueries() {
        Coordinate[] ring=new Coordinate[721];
        for(int i=0;i<720;i++){double t=2*Math.PI*i/720;ring[i]=new Coordinate(400000+20*Math.cos(t),6170000+20*Math.sin(t));}ring[720]=ring[0];
        Geometry g=GeometryUtils.GF.createPolygon(ring);BoundarySegments indexed=new BoundarySegments(g);
        List<Coordinate> queries=new ArrayList<>(Arrays.asList(ring));queries.add(new Coordinate(400000,6170000));queries.add(new Coordinate(399950,6170100));
        Coordinate a=new Coordinate(399000,6169000),b=new Coordinate(401000,6171000);
        for(Coordinate p:queries){double min=Double.MAX_VALUE,angle=90;
            for(int i=1;i<ring.length;i++){double d=Distance.pointToSegment(p,ring[i-1],ring[i]);if(d<min){min=d;angle=GeometryUtils.acuteAngleDeg(b.x-a.x,b.y-a.y,ring[i].x-ring[i-1].x,ring[i].y-ring[i-1].y);}}
            assertEquals(angle,indexed.angle(a,b,p),0,"exact nearest edge and corner tie");
        }
    }
}
