package ru.lct.heatnet.boundary;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.topology.*;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.solver.VariantResult;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class OutputFlowPrecisionTest {
    @Test void sumJustAboveDu50CapacityIsNotRoundedDownInJson() throws Exception {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("a",-10,30,1.0004).point("b",10,30,2.5).build();
        NewNetwork n=new NewNetwork();var root=n.rootOnExistingLine(in.networkLines().get(0),c(0,0),2);var joint=n.addJunctionNode(c(0,20));
        n.addLink(joint,root,Arrays.asList(c(0,20),c(0,0)),Collections.emptyList());
        for(var p:in.connectionPoints())n.addLink(n.addConnectionNode(p),joint,Arrays.asList(p.coordinate(),c(0,20)),Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(n).feasible());
        var v=new VariantResult("test",n,Collections.emptyMap(),CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());v.setVariantId("v1");v.setRank(1);
        var trunk=new OutputBuilder().build(v).stream().filter(f->"heat_network".equals(f.prop("object_type"))&&((Number)f.prop("flow_tph")).doubleValue()>3).findFirst().orElseThrow();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(trunk.prop("flow_tph"));
        assertTrue(json.isNumber());assertEquals(3.5004,json.asDouble(),1e-12);assertEquals(65,trunk.prop("diameter"));
        assertTrue(json.asDouble()>3.5,"independent Table 1 threshold explains DU65");
    }
}
