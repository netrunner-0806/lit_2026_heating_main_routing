package ru.lct.heatnet.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.*;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.output.OutputBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class LocalCrossingClearanceTest {
    private static ValidationReport validate(InputModel input,List<Coordinate> coords,double crossingDistance) {
        return validate(input,coords,crossingDistance,"gas_pipeline");
    }
    private static ValidationReport validate(InputModel input,List<Coordinate> coords,double crossingDistance,String type) {
        ObstacleSpace space=new ObstacleSpace(input,ClearanceClass.SMALL);
        Obstacle crossed=space.obstacles().stream().filter(o->o.rule().type().equals(type)&&o.geometry().distance(GeometryUtils.point(c(0,0)))<0.001).findFirst().orElseThrow();
        NewNetwork net=new NewNetwork();
        net.addLink(net.addConnectionNode(input.connectionPoints().get(0)),net.rootOnExistingLine(input.networkLines().get(0),coords.get(coords.size()-1),2),coords,
                Collections.singletonList(new SpecialPassage(crossed,crossingDistance-2,crossingDistance+2,crossed.rule().kSpec(),c(0,0),90)));
        assertTrue(HydraulicCalculator.compute(net).feasible());
        VariantResult v=new VariantResult("witness",net,Collections.emptyMap(),CostCalculator.summarize(net,Collections.emptyList()),Collections.emptyList());v.setVariantId("v1");v.setRank(1);
        var fs=new OutputBuilder().build(v);
        double specialLength=fs.stream().filter(f->"special".equals(f.prop("laying_method"))).mapToDouble(f->((Number)f.prop("length")).doubleValue()).sum();
        assertEquals(4,specialLength,1e-6,"transition exemption must not change special length or Kspec extent");
        return new ResultValidator(input).validate(fs);
    }
    static java.util.stream.Stream<Arguments> crossings() {
        return java.util.stream.Stream.of("gas_pipeline","power_cable","heat_network").flatMap(t->java.util.stream.Stream.of(90.,45.,10.).map(a->Arguments.of(t,a)));
    }
    @ParameterizedTest @MethodSource("crossings")
    void actualStraightCrossingKeepsFourMetreSpecialSection(String type,double angle) {
        double dx=40*Math.cos(Math.toRadians(angle)),dy=40*Math.sin(Math.toRadians(angle));
        SyntheticInput builder=new SyntheticInput().source("s",dx-dy/4,-dy-dx/4).line("net",300,dx-dy/4,-dy-dx/4,dx+dy/4,-dy+dx/4)
                .point("cp",-dx,dy,4);
        if(type.equals("heat_network"))builder.line("crossed-net",300,-100,0,100,0);
        else builder.polyline("crossed",type,-100,0,100,0);
        InputModel in=builder.build();
        var sc=new ObstacleSpace(in,ClearanceClass.SMALL).check(c(-dx,dy),c(dx,-dy),new Exemptions().disc(c(dx,-dy),10,o->o.isExistingNetwork()));
        assertTrue(sc.valid(),sc.reason());assertEquals(4,sc.passages().get(0).to()-sc.passages().get(0).from(),1e-6);
        var rep=validate(in,Arrays.asList(c(-dx,dy),c(dx,-dy)),40,type);
        assertTrue(rep.isValid(),rep.getIssues().toString());
    }
    @Test void shortParallelApproachAfterCrossingIsNotHiddenByAnArbitraryRadius() {
        InputModel in=new SyntheticInput().source("s",10,-15).line("net",300,10,-15,10,-5)
                .point("cp",0,10,4).polyline("gas","gas_pipeline",-100,0,100,0).build();
        var rep=validate(in,Arrays.asList(c(0,10),c(0,-2.2),c(4,-2.2),c(10,-10)),10);
        assertTrue(rep.getIssues().stream().anyMatch(i->i.getCode().equals("CLEARANCE")&&i.getMessage().contains("gas_pipeline")),rep.getIssues().toString());
        assertFalse(new ObstacleSpace(in,ClearanceClass.SMALL).check(c(0,-2.2),c(4,-2.2)).valid());
    }
    @Test void crossingOneCommunicationDoesNotExemptANeighbouringParallelCommunication() {
        InputModel in=new SyntheticInput().source("s",0,-25).line("net",300,0,-25,0,-15)
                .point("cp",0,10,4).polyline("gas","gas_pipeline",-100,0,100,0)
                .polyline("near-cable","power_cable",1.9,-3,1.9,8).build();
        assertFalse(new ObstacleSpace(in,ClearanceClass.SMALL).check(c(0,10),c(0,-20),new Exemptions().disc(c(0,-20),5,o->o.isExistingNetwork())).valid());
        var rep=validate(in,Arrays.asList(c(0,10),c(0,-20)),10);
        assertTrue(rep.getIssues().stream().anyMatch(i->i.getCode().equals("CLEARANCE")&&i.getMessage().contains("power_cable")),rep.getIssues().toString());
    }
}
