package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelBudgetWitnessTest {
    NavGraph graph(long checks) {return new NavGraph(new ObstacleSpace(new SyntheticInput().build(),ClearanceClass.SMALL),100,0,100,checks);}
    @Test void saturatedCounterDoesNotOverflowOrChargeRejectedReservation() {
        NavGraph g=graph(Long.MAX_VALUE);g.reserveFallbackChecks(Long.MAX_VALUE-1);
        assertThrows(ComplexityLimitException.class,()->g.reserveFallbackChecks(2));
        assertEquals(Long.MAX_VALUE-1,g.visibilityCheckCount());g.reserveFallbackChecks(1);
        assertThrows(ComplexityLimitException.class,()->g.reserveFallbackChecks(1));assertEquals(Long.MAX_VALUE,g.visibilityCheckCount());
    }
    @Test void concurrentReservationsNeverExceedGlobalCap()throws Exception {
        NavGraph base=graph(1000);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> charge=()->{int n=0;for(int i=0;i<1000;i++)try{base.reserveFallbackChecks(1);n++;}catch(ComplexityLimitException ignored){}return n;};
            Future<Integer> a=pool.submit(charge),b=pool.submit(charge);assertEquals(1000,a.get()+b.get());assertEquals(1000,base.visibilityCheckCount());
        }finally{pool.shutdown();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void interruptStopsWithoutChargingBudget()throws Exception {
        NavGraph g=graph(5);ExecutorService pool=Executors.newSingleThreadExecutor();
        try {pool.submit(()->{Thread.currentThread().interrupt();try{assertThrows(CancellationException.class,()->g.reserveFallbackChecks(1));assertEquals(0,g.visibilityCheckCount());}finally{Thread.interrupted();}}).get();}
        finally{pool.shutdown();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void explicitTokenStopsDijkstraBeforeAllocation() {
        ComputeCancellation token=new ComputeCancellation();var g=new NavGraph(new ObstacleSpace(new SyntheticInput().build(),ClearanceClass.SMALL),100,0,ComputeBudget.expanded(),token);
        token.cancel();assertThrows(CancellationException.class,()->new PathSearch(g,null,null,0,null).run(0));assertEquals(0,g.traversalCount());
    }
    @Test void profilesChangeOnlyFiveApprovedLimits() {
        var a=ComputeBudget.production();var b=ComputeBudget.expanded();assertEquals(130000,b.navigationNodes);assertEquals(5200000,b.directedEdges);assertEquals(65000000,b.visibilityChecks);assertEquals(130000,b.gridCells);assertEquals(1300,b.candidateEvaluations);
        assertEquals(4000000,a.directedEdges);assertThrows(IllegalArgumentException.class,()->new ComputeBudget(0,1,1,1,1));
    }
    @Test void edgeReservationDoesNotWrapOrExposeOverLimitCount()throws Exception {
        NavGraph g=new NavGraph(new ObstacleSpace(new SyntheticInput().build(),ClearanceClass.SMALL),100,0,Integer.MAX_VALUE,1);
        var field=NavGraph.class.getDeclaredField("directedEdges");field.setAccessible(true);
        var count=(java.util.concurrent.atomic.AtomicInteger)field.get(g);count.set(Integer.MAX_VALUE-1);
        var reserve=NavGraph.class.getDeclaredMethod("reserveEdges",int.class);reserve.setAccessible(true);
        var ex=assertThrows(java.lang.reflect.InvocationTargetException.class,()->reserve.invoke(g,2));
        assertTrue(ex.getCause() instanceof ComplexityLimitException);assertEquals(Integer.MAX_VALUE-1,count.get());
        reserve.invoke(g,1);assertEquals(Integer.MAX_VALUE,count.get());
    }

    @Test void cancellationJoinsNestedGraphTasks()throws Exception {
        ComputeCancellation token=new ComputeCancellation();
        var input=new SyntheticInput().line("net",300,0,0,1000,0).build();
        var space=new ObstacleSpace(input,ClearanceClass.SMALL);var pool=Executors.newSingleThreadExecutor();
        try {
            var task=pool.submit(()->assertThrows(CancellationException.class,()->new NavGraph(space,300,5,ComputeBudget.expanded(),token)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean building=false;
            while(System.nanoTime()<deadline && !building) {
                building=Thread.getAllStackTraces().values().stream().anyMatch(stack->java.util.Arrays.stream(stack).anyMatch(f->f.getClassName().equals(NavGraph.class.getName()) && f.getMethodName().equals("buildStaticEdges")));
                if(!building)Thread.sleep(1);
            }
            assertTrue(building);long start=System.nanoTime();token.cancel();task.get(5,TimeUnit.SECONDS);
            System.out.println("GRAPH_CANCEL_JOIN_MS="+(System.nanoTime()-start)/1_000_000);
            assertTrue(Thread.getAllStackTraces().values().stream().noneMatch(stack->java.util.Arrays.stream(stack).anyMatch(f->f.getClassName().equals(NavGraph.class.getName()) && f.getMethodName().equals("buildStaticEdges"))));
        }finally{token.cancel();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void simultaneousStaticLimitsHaveDeterministicFailure() {
        java.util.Set<String> messages=new java.util.HashSet<>();
        for(int i=0;i<40;i++) {
            var input=new SyntheticInput().line("net",300,0,0,100,0).build();
            var space=new ObstacleSpace(input,ClearanceClass.SMALL);
            var error=assertThrows(ComplexityLimitException.class,()->new NavGraph(space,300,80,new ComputeBudget(100000,1,1,100000,1000),new ComputeCancellation()));
            messages.add(error.getMessage());
        }
        assertEquals(1,messages.size(),"Concurrent static resource limits must not change diagnostics: "+messages);
    }

}
