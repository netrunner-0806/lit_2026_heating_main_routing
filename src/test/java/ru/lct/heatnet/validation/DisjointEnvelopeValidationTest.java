package ru.lct.heatnet.validation;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NewNetwork;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DisjointEnvelopeValidationTest {
    @Test void disjointBentLinesWithOverlappingEnvelopesAreNotIntersections() {
        ValidationReport report = validateBentLines(80);
        assertTrue(report.isValid(),report.getIssues().toString());assertEquals(0,report.getWarningCount());
    }

    @Test void actualIntersectionOutsideCommonNodeStillFails() {
        ValidationReport report = validateBentLines(110);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().stream().anyMatch(i -> "LINES_INTERSECT".equals(i.getCode())));
    }

    private static ValidationReport validateBentLines(double bendX) {
        var input=new SyntheticInput().source("source",-50,0).line("network",300,-50,0,150,0)
                .point("A",20,100,4).point("B",0,80,4).build();
        var net=new NewNetwork();
        var a=net.addLink(net.addConnectionNode(input.connectionPoints().get(0)),net.rootOnExistingLine(input.networkLines().get(0),SyntheticInput.c(100,0),2),
                Arrays.asList(SyntheticInput.c(20,100),SyntheticInput.c(100,100),SyntheticInput.c(100,0)),Collections.emptyList());
        var b=net.addLink(net.addConnectionNode(input.connectionPoints().get(1)),net.rootOnExistingLine(input.networkLines().get(0),SyntheticInput.c(bendX,0),2),
                Arrays.asList(SyntheticInput.c(0,80),SyntheticInput.c(bendX,80),SyntheticInput.c(bendX,0)),Collections.emptyList());
        var ag=ru.lct.heatnet.geo.GeometryUtils.line(a.coords());var bg=ru.lct.heatnet.geo.GeometryUtils.line(b.coords());
        assertTrue(ag.getEnvelopeInternal().intersects(bg.getEnvelopeInternal()));
        if (bendX == 80) { assertTrue(ag.intersection(bg).isEmpty());assertEquals("LineString",ag.intersection(bg).getGeometryType()); }
        assertTrue(HydraulicCalculator.compute(net).feasible());
        var variant=new VariantResult("witness",net,Collections.emptyMap(),CostCalculator.summarize(net,Collections.emptyList()),Collections.emptyList());
        variant.setVariantId("v1");variant.setRank(1);
        return new ResultValidator(input).validate(new OutputBuilder().build(variant));
    }
}
