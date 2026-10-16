package ru.lct.heatnet.hydraulic;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.topology.*;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.output.*;
import ru.lct.heatnet.validation.*;
import ru.lct.heatnet.testkit.SolveKit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class BranchedPathConformanceTest {
    private static void link(NewNetwork n,NetNode child,NetNode parent) {
        n.addLink(child,parent,Arrays.asList(child.coord(),parent.coord()),Collections.emptyList());
    }
    private static List<OutputFeature> output(NewNetwork n) {
        VariantResult v=new VariantResult("hand-calculated witness",n,Collections.emptyMap(),CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());
        v.setVariantId("v1");v.setRank(1);return new OutputBuilder().build(v);
    }
    private static void valid(InputModel m,NewNetwork n)throws Exception {
        List<OutputFeature> f=output(n);var report=new ResultValidator(m).validate(SolveKit.roundTrip(f));
        assertTrue(report.isValid(),report.getIssues().toString());assertEquals(0,report.getWarningCount());
        assertEquals(Collections.emptyList(),ru.lct.heatnet.oracle.StressEngineeringOracle.check(m,f,false));
    }
    @Test void common100AndParallel80ArePathsOf180NotAPathOf260()throws Exception {
        InputModel m=new SyntheticInput().source(1,0,-100).line(2,300,-20,-100,20,-100).chamber(3,0,-100)
                .point(4,-80,0,1).point(5,80,0,1).rect(6,"oks",-82,-2,-78,2).rect(7,"oks",78,-2,82,2).build();
        NewNetwork n=new NewNetwork();NetNode root=n.rootAtExistingChamber(m.chambers().get(0),2),j=n.addJunctionNode(c(0,0));
        link(n,j,root);for(var cp:m.connectionPoints())link(n,n.addConnectionNode(cp),j);
        assertTrue(HydraulicCalculator.compute(n).feasible());
        for(NetLink l:n.links())assertEquals(50,l.du(),"Each path is 180 <= 181 m, total network length is irrelevant");
        valid(m,n);
    }
    @Test void technicalNodeDoesNotResetA200MetreConstantFlowSection()throws Exception {
        InputModel m=new SyntheticInput().source(1,0,0).line(2,300,-20,0,20,0).chamber(3,0,0)
                .point(4,0,200,2).rect(5,"oks",-2,198,2,202).build();
        NewNetwork n=new NewNetwork();link(n,n.addConnectionNode(m.connectionPoints().get(0)),n.rootAtExistingChamber(m.chambers().get(0),2));
        assertTrue(HydraulicCalculator.compute(n).feasible());assertEquals(65,n.links().iterator().next().du());
        List<OutputFeature> f=output(n);OutputFeature original=f.stream().filter(x->"heat_network".equals(x.objectType())).findFirst().orElseThrow();
        double[] mid=ru.lct.heatnet.geo.CrsTransformer.get().toWgs84(c(0,100).x,c(0,100).y);
        OutputFeature first=OutputFeature.line(Arrays.asList(original.line().get(0),mid));first.properties().putAll(original.properties());
        first.prop("id","first").prop("end_node_id","mid").prop("length",100.).prop("cost",((Number)original.prop("cost")).doubleValue()/2);
        OutputFeature second=OutputFeature.line(Arrays.asList(mid,original.line().get(original.line().size()-1)));second.properties().putAll(original.properties());
        second.prop("id","second").prop("start_node_id","mid").prop("length",100.).prop("cost",((Number)original.prop("cost")).doubleValue()/2);
        f.remove(original);f.add(first);f.add(second);f.add(OutputFeature.point(mid).prop("id","mid").prop("object_type","technical_node").prop("variant_id","v1"));
        var report=new ResultValidator(m).validate(SolveKit.roundTrip(f));
        assertTrue(report.isValid(),report.getIssues().toString());
        // This intentionally redundant split may be warned about; it must never reset the hydraulic length.
        first.prop("diameter",50);second.prop("diameter",50);
        var wrong=new ResultValidator(m).validate(SolveKit.roundTrip(f));
        assertTrue(wrong.getIssues().stream().anyMatch(x->"MAX_LENGTH".equals(x.getCode())),wrong.getIssues().toString());
    }
    @Test void multipleBranchesShareA100MetreTrunkButLengthsAreNotSummedAcrossLeaves()throws Exception {
        InputModel m=new SyntheticInput().source(1,0,-100).line(2,300,-20,-100,20,-100).chamber(3,0,-100)
                .point(4,80,0,.8).point(5,40,40,.6).point(6,-80,0,.7)
                .rect(7,"oks",78,-2,82,2).rect(8,"oks",38,38,42,42).rect(9,"oks",-82,-2,-78,2).build();
        NewNetwork n=new NewNetwork();NetNode root=n.rootAtExistingChamber(m.chambers().get(0),2),j=n.addJunctionNode(c(0,0)),k=n.addJunctionNode(c(40,0));
        link(n,j,root);link(n,k,j);link(n,n.addConnectionNode(m.connectionPoints().get(0)),k);
        link(n,n.addConnectionNode(m.connectionPoints().get(1)),k);link(n,n.addConnectionNode(m.connectionPoints().get(2)),j);
        assertTrue(HydraulicCalculator.compute(n).feasible());
        for(NetLink l:n.links())assertEquals(50,l.du(),"All three paths are 180 m, although the network totals 300 m");
        valid(m,n);
    }
}
