package ru.lct.heatnet.validation;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.topology.NewNetwork;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnOksTransitValidationTest {
    private static Polygon rectangle(double x0, double y0, double x1, double y1) {
        return GeometryUtils.GF.createPolygon(new Coordinate[]{SyntheticInput.c(x0,y0),SyntheticInput.c(x1,y0),
                SyntheticInput.c(x1,y1),SyntheticInput.c(x0,y1),SyntheticInput.c(x0,y0)});
    }
    private static InputModel input() {
        MultiPolygon building=GeometryUtils.GF.createMultiPolygon(new Polygon[]{rectangle(-5,95,5,105),rectangle(12,95,14,105)});
        assertTrue(building.isValid());
        return new SyntheticInput().source("source",40,80).line("network",300,40,80,40,120)
                .point("consumer",4,100,4).restriction("own","oks",building).build();
    }
    @Test void straightFinalApproachCannotTransitAnotherPartOfOwnMultipolygon() {
        InputModel input=input();NewNetwork net=new NewNetwork();
        net.addLink(net.addConnectionNode(input.connectionPoints().get(0)),
                net.rootOnExistingLine(input.networkLines().get(0),SyntheticInput.c(40,100),2),
                Arrays.asList(SyntheticInput.c(4,100),SyntheticInput.c(40,100)),Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(net).feasible());
        VariantResult variant=new VariantResult("transit",net,Collections.emptyMap(),CostCalculator.summarize(net,Collections.emptyList()),Collections.emptyList());
        variant.setVariantId("v1");variant.setRank(1);
        ValidationReport report=new ResultValidator(input).validate(new OutputBuilder().build(variant));
        assertFalse(report.isValid(),"own-building exemption must not allow leaving and re-entering the building");
        assertTrue(report.getIssues().stream().anyMatch(i->"OWN_OKS_CROSSED".equals(i.getCode())),report.getIssues().toString());
    }
    @Test void approachPlannerDoesNotJumpOverAnotherWingToFindClearance() {
        InputModel input=input();ObstacleSpace space=new ObstacleSpace(input,ClearanceClass.SMALL);
        var own=space.obstacles().stream().filter(Obstacle::isOks).findFirst().get();
        for(boolean expanded:new boolean[]{false,true}) {
            ApproachPlanner planner=new ApproachPlanner(space);
            List<Approach> approaches=expanded?planner.planExpanded(input.connectionPoints().get(0)):planner.plan(input.connectionPoints().get(0));
            assertFalse(approaches.isEmpty(),"other exterior exits are available");
            for(Approach approach:approaches) {
                Coordinate b=approach.boundaryPoint(),a=approach.freePoint();
                Coordinate probe=GeometryUtils.offset(b,GeometryUtils.unit(b,a),0.01);
                assertFalse(own.geometry().intersects(GeometryUtils.line(probe,a)),"approach re-enters own building");
                Geometry crossed=own.geometry().intersection(GeometryUtils.line(approach.target(),a));
                assertEquals(1,crossed.getNumGeometries(),"actual final segment must have a single own-building interval");
            }
        }
    }
}
