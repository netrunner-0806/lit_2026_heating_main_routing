package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityReservationTest {
    private NavGraph graph(long limit) {
        return new NavGraph(new ObstacleSpace(new SyntheticInput().build(),ClearanceClass.SMALL),
                100,0,new ComputeBudget(1000,1000,limit,1000,100),new ComputeCancellation());
    }
    @Test void failedLargeReservationDoesNotPoisonLaterSmallRequest() {
        var g=graph(10);assertEquals(0,g.visibilityCheckCount());
        assertThrows(ComplexityLimitException.class,()->g.reserveFallbackChecks(11));
        assertEquals(0,g.visibilityCheckCount());g.reserveFallbackChecks(9);g.reserveFallbackChecks(1);
        assertEquals(10,g.visibilityCheckCount());
        assertThrows(ComplexityLimitException.class,()->g.reserveFallbackChecks(1));
        assertEquals(10,g.visibilityCheckCount());
    }
    @Test void longBoundaryAndNegativeReservationCannotOverflowOrRefund() {
        var g=graph(Long.MAX_VALUE);g.reserveFallbackChecks(Long.MAX_VALUE-1);
        assertThrows(ComplexityLimitException.class,()->g.reserveFallbackChecks(2));
        assertThrows(IllegalArgumentException.class,()->g.reserveFallbackChecks(-1));
        assertEquals(Long.MAX_VALUE-1,g.visibilityCheckCount());
        g.reserveFallbackChecks(1);g.reserveFallbackChecks(0);assertEquals(Long.MAX_VALUE,g.visibilityCheckCount());
    }
    @Test void concurrentReservationsStopAtTheExactCap()throws Exception {
        var g=graph(1000);var pool=Executors.newFixedThreadPool(8);var accepted=new AtomicInteger();
        try {
            List<Future<?>> work=new ArrayList<>();
            for(int i=0;i<8;i++)work.add(pool.submit(()->{
                for(int j=0;j<300;j++)try{g.reserveFallbackChecks(1);accepted.incrementAndGet();}
                catch(ComplexityLimitException expected){assertEquals(1000,g.visibilityCheckCount());}
            }));
            for(var f:work)f.get(5,TimeUnit.SECONDS);
            assertEquals(1000,accepted.get());assertEquals(1000,g.visibilityCheckCount());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
