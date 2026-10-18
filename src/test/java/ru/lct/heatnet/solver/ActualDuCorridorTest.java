package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.validation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class ActualDuCorridorTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,50,0.2,false","5,65,0.215,false","1,50,0.2,true","5,65,0.215,true"})
    void actualDuWitnessFitsWhereClass400DoesNot(double flow,int du,double halfWidth,boolean depth) {
        var in=new SyntheticInput().source("s",-10,0).line("net",300,-10,0,10,0).point("cp",0,30,flow)
                .rect("left","water",-10,5,-1.4,40).rect("right","water",1.4,5,10,40).build();
        // Independent Table 1 widths and Table 2 water clearance, in metres.
        assertTrue(1.4 >= 1 + halfWidth);
        NewNetwork n=new NewNetwork();var root=n.rootOnExistingLine(in.networkLines().get(0),c(0,0),2);
        n.addLink(n.addConnectionNode(in.connectionPoints().get(0)),root,Arrays.asList(c(0,30),c(0,0)),Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(n).feasible());assertEquals(du,n.links().iterator().next().du());
        var witness=new VariantResult("witness",n,Collections.emptyMap(),CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());
        witness.setVariantId("v1");witness.setRank(1);
        var report=new ResultValidator(in).validate(new OutputBuilder().build(witness));
        assertTrue(report.isValid(),report.getIssues().toString());
        SolverConfig cfg=SolverConfig.defaults();cfg.improve=false;cfg.depthMode=depth;
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(in)).solve(in,HeatnetSolver.defaultStrategies().subList(0,1),null);
        assertFalse(result.variants().isEmpty());assertEquals(1,result.variants().get(0).connectedCount());
        var actual=result.variants().get(0);
        assertTrue(new ResultValidator(in).validate(new OutputBuilder(in).build(actual)).isValid());
        for(var link:actual.network().links()) for(var water:in.restrictions())
            assertTrue(ru.lct.heatnet.geo.GeometryUtils.line(link.coords()).distance(water.geometry()) >= 1+halfWidth-1e-6);
        assertTrue(actual.notes().stream().anyMatch(note->note.contains("exact-DU")));
    }
    @Test void fallbackDoesNotAllowAnUndersizedCorridor() {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("cp",0,30,1)
                .rect("left","water",-10,5,-1.19,40).rect("right","water",1.19,5,10,40).build();
        SolverConfig cfg=SolverConfig.defaults();cfg.improve=false;
        var r=new HeatnetSolver(cfg,new SolverVariantValidator(in)).solve(in,HeatnetSolver.defaultStrategies().subList(0,1),null);
        assertFalse(r.variants().isEmpty());assertEquals(0,r.variants().get(0).connectedCount());
        assertTrue(new ResultValidator(in).validate(new OutputBuilder(in).build(r.variants().get(0))).isValid());
    }
    @Test void fallbackBudgetIsReportedAsComplexityNotImpossibility() {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("cp",0,30,1)
                .rect("left","water",-10,5,-1.4,40).rect("right","water",1.4,5,10,40).build();
        SolverConfig cfg=SolverConfig.defaults();cfg.improve=false;cfg.maxCandidateEvaluations=1;
        var r=new HeatnetSolver(cfg,new SolverVariantValidator(in)).solve(in,HeatnetSolver.defaultStrategies().subList(0,1),null);
        assertFalse(r.variants().isEmpty());assertEquals(0,r.variants().get(0).connectedCount());
        assertTrue(r.variants().get(0).unconnected().values().iterator().next().startsWith("COMPLEXITY_LIMIT"));
    }
}
