package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelMetricsTest {
    @Test void sharedReservationsAreNotCountedFiveTimesAndRecoveryWorkIsVisible() {
        var base=Map.<String,Object>of("visibility_checks",100L,"graph_nodes",20,"candidate_count",0);
        var a=Map.<String,Object>of("visibility_checks",130L,"inherited_visibility_checks",100L,"graph_nodes",25,"candidate_count",3);
        var b=Map.<String,Object>of("visibility_checks",150L,"inherited_visibility_checks",100L,"graph_nodes",24,"candidate_count",5);
        var recovery=Map.<String,Object>of("visibility_checks",120L,"graph_nodes",22,"candidate_count",7);
        var totals=ParallelMetrics.aggregate(recovery,base,Arrays.asList(a,b));
        assertEquals(300L,totals.get("visibility_checks"));assertEquals(15L,totals.get("candidate_count"));
        assertEquals(25L,totals.get("graph_nodes"));assertEquals(base,totals.get("shared_graph_metrics"));
        assertEquals(recovery,totals.get("recovery_graph_metrics"));
        assertEquals(true,totals.get("operation_counts_complete"));
    }
    @Test void failedGraphConstructionIsExplicitlyMarkedAsIncompleteAccounting() {
        var input=new ru.lct.heatnet.SyntheticInput().line("net",300,0,0,100,0).point("cp",30,30,1).build();
        var cfg=SolverConfig.defaults();
        cfg.useBudget(new ru.lct.heatnet.routing.ComputeBudget(1,1,1,1,1));
        var ctx=new SolverContext(input,cfg);
        assertThrows(ru.lct.heatnet.routing.ComplexityLimitException.class,
                ()->ctx.resources(ru.lct.heatnet.restrictions.ClearanceClass.SMALL));
        var totals=ParallelMetrics.aggregate(Collections.emptyMap(),ctx.graphStats(),Collections.emptyList());
        assertEquals(false,totals.get("operation_counts_complete"));
    }
}
