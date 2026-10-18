package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicObstacleIndexTest {
    @Test void indexedDecisionsAndPenaltyBitsMatchTheFrozenLinearScan() {
        Random r=new Random(20261018);DynamicObstacles indexed=new DynamicObstacles(.05,2.5);
        LinearDynamicObstaclesOracle linear=new LinearDynamicObstaclesOracle(.05,2.5);
        List<Coordinate> ends=new ArrayList<>();
        for(int i=0;i<600;i++) {
            Coordinate a=point(r),b=point(r);ends.add(a);
            indexed.add(a,b,i%7);linear.add(a,b,i%7);
        }
        for(int i=0;i<1500;i++) {
            Coordinate a=point(r),b=point(r);
            List<Object[]> exempt=i%3==0?Arrays.asList(new Object[]{ends.get(i%600),3.0,i%7},new Object[]{a,1.5,null}):null;
            assertEquals(linear.allows(a,b,exempt),indexed.allows(a,b,exempt));
            assertEquals(Double.doubleToLongBits(linear.proximityPenalty(a,b,exempt)),Double.doubleToLongBits(indexed.proximityPenalty(a,b,exempt)));
            var expected=linear.near(a,5);var actual=indexed.near(a,5);
            assertEquals(expected.size(),actual.size());
            for(int j=0;j<actual.size();j++){assertSame(expected.get(j).a,actual.get(j).a);assertEquals(expected.get(j).owner,actual.get(j).owner);}
        }
    }
    @Test void addClearCopyAndLegacyMutableViewCannotLeaveStaleEntries() {
        DynamicObstacles d=new DynamicObstacles(.05,2.5);
        for(int i=0;i<40;i++)d.add(new Coordinate(i,100),new Coordinate(i,101),i);
        Coordinate a=new Coordinate(-1,0),b=new Coordinate(1,0);
        assertTrue(d.allows(a,b,null));
        DynamicObstacles copy=d.copy();
        d.add(new Coordinate(0,-1),new Coordinate(0,1),"new");
        assertFalse(d.allows(a,b,null));assertTrue(copy.allows(a,b,null));
        d.clear();assertTrue(d.allows(a,b,null));assertEquals(0,d.proximityPenalty(a,b,null));
        copy.segments().addAll(d.segments());
        copy.segments().clear();assertTrue(copy.allows(a,b,null));
        copy.add(new Coordinate(0,-1),new Coordinate(0,1),"after-view");assertFalse(copy.allows(a,b,null));
    }
    private Coordinate point(Random r){return new Coordinate(414000+r.nextDouble()*400,6173000+r.nextDouble()*400);}
}
