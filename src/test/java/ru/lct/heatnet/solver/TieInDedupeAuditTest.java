package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated index experiment. Not used by production: full-solve profiling found negligible dedupe cost. */
class TieInDedupeAuditTest {
    private static final class Cell {
        final long x,y;
        Cell(long x,long y){this.x=x;this.y=y;}
        public boolean equals(Object o){return o instanceof Cell && ((Cell)o).x==x && ((Cell)o).y==y;}
        public int hashCode(){return 31*Long.hashCode(x)+Long.hashCode(y);}
    }
    @Test void dynamicGridPreservesStrictDistanceAndFirstAcceptedOrder() {
        var input=new ArrayList<Coordinate>();
        input.addAll(List.of(new Coordinate(0,0),new Coordinate(1,0),new Coordinate(Math.nextDown(1.0),0),
                new Coordinate(-1,0),new Coordinate(-.5,-.5),new Coordinate(-1,-1)));
        var random=new Random(20261018);
        for(int i=0;i<10000;i++)input.add(new Coordinate(random.nextInt(1000)/10.0-50,random.nextInt(1000)/10.0-50));
        var linear=new ArrayList<Coordinate>();var indexed=new ArrayList<Coordinate>();
        var grid=new HashMap<Cell,List<Coordinate>>();long linearChecks=0,indexChecks=0;
        for(Coordinate p:input) {
            boolean dup=false;
            for(Coordinate q:linear){linearChecks++;if(q.distance(p)<1){dup=true;break;}}
            if(!dup)linear.add(p);
            long x=(long)Math.floor(p.x),y=(long)Math.floor(p.y);dup=false;
            outer:for(long dx=-1;dx<=1;dx++)for(long dy=-1;dy<=1;dy++)
                for(Coordinate q:grid.getOrDefault(new Cell(x+dx,y+dy),List.of())) {
                    indexChecks++;if(q.distance(p)<1){dup=true;break outer;}
                }
            if(!dup){indexed.add(p);grid.computeIfAbsent(new Cell(x,y),k->new ArrayList<>()).add(p);}
        }
        assertEquals(linear,indexed);assertTrue(indexChecks<linearChecks/10);
        assertSame(input.get(1),indexed.get(1),"distance exactly one is not a duplicate");
        System.out.println("AUDIT_DEDUPE raw="+input.size()+" accepted="+linear.size()+" linearComparisons="+linearChecks+" gridComparisons="+indexChecks);
    }
}
