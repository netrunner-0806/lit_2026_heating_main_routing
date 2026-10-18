package ru.lct.heatnet.solver;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ImprovementTraceAuditTest {
    @Test void rolledBackImprovementDoesNotDeclareConnectedConsumerUnreachable() {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("cp",0,30,1)
                .rect("left","water",-10,5,-1.4,40).rect("right","water",1.4,5,10,40).build();
        var cfg=SolverConfig.defaults();cfg.improve=true;
        var r=new HeatnetSolver(cfg,new SolverVariantValidator(in)).solve(in,HeatnetSolver.defaultStrategies().subList(0,1),null);
        var v=r.variants().get(0);assertEquals(1,v.connectedCount());
        assertFalse(v.trace().decisions().stream().anyMatch(d->"ROUTE_REJECTED".equals(d.type)),"rejected trial must not be a final decision");
    }
    @Test void truncatedJournalKeepsRankingAndExposesItsLimit() {
        var in=new SyntheticInput().line("net",300,0,0,20,0).point("cp",10,20,1).build();
        var cfg=SolverConfig.defaults();cfg.improve=false;
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(in),(ctx,strategy)->{
            var v=new ForestBuilder(ctx,strategy,Collections.emptyMap()).build();
            for(int i=0;i<610;i++) v.trace().add("TRIAL",null,"accepted","fixture",null,null);
            return v;
        }).solve(in,HeatnetSolver.defaultStrategies().subList(0,1),null);
        var trace=result.variants().get(0).trace();
        assertEquals(600,trace.decisions().size());assertTrue(trace.dropped()>0);
        assertEquals("VARIANT_RANKED",trace.decisions().get(599).type);
        var explain=(Map<?,?>)ru.lct.heatnet.application.JobRunner.explain(result).get(0);
        assertEquals(true,explain.get("truncated"));assertEquals(trace.dropped(),explain.get("droppedDecisions"));
    }
    @Test void rejectedAttemptRestoresOverflowCounterToo() {
        var trace=new CalculationTrace();
        for(int i=0;i<599;i++) trace.add("ACCEPTED",null,"kept",null,null,null);
        var checkpoint=trace.checkpoint();
        for(int i=0;i<10;i++) trace.add("TRIAL",null,"rejected",null,null,null);
        trace.rollback(checkpoint);
        assertEquals(599,trace.decisions().size());assertEquals(0,trace.dropped());
    }

}
