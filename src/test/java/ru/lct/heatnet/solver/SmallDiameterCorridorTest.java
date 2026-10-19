package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.oracle.StressEngineeringOracle;
import ru.lct.heatnet.validation.SolverVariantValidator;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

/** Metric witnesses: distances use original polygons and independently transcribed Table 1 widths. */
class SmallDiameterCorridorTest {
    static InputModel straight(double flow, double halfGap) {
        return new SyntheticInput().line("net", 300, -20, 0, 20, 0).point("cp", 0, 80, flow)
                .rect("left", "park", -20, 5, -halfGap, 100)
                .rect("right", "park", halfGap, 5, 20, 100).build();
    }

    static boolean clearanceOracle(InputModel in, double width, Coordinate... path) {
        Geometry line = GeometryUtils.GF.createLineString(path);
        // Park clearance = 1 m between polygon and external envelope, not distance to a solver buffer.
        return in.restrictions().stream().allMatch(o -> line.distance(o.geometry()) >= 1 + width / 2 - 1e-8);
    }

    static SolveResult solve(InputModel in) {
        SolverConfig cfg = SolverConfig.defaults(); cfg.improve = false;
        return new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in,
                Collections.singletonList(HeatnetSolver.defaultStrategies().get(0)), null);
    }

    @ParameterizedTest
    @CsvSource({"10,80,0.470", "20,100,0.510", "30,125,0.600", "50,150,0.650",
            "100,200,0.880", "200,250,1.050", "400,300,1.150", "800,400,1.370"})
    void straightPassageFitsActualDiameter(double flow, int du, double width) {
        InputModel in = straight(flow, 1 + width / 2 + .02);
        assertTrue(clearanceOracle(in, width, c(0,80), c(0,0)));
        if (du < 400) assertFalse(clearanceOracle(in, 1.370, c(0,80), c(0,0)), "DU400 envelope hides the witness");
        SolveResult result = solve(in);
        assertFalse(result.variants().isEmpty(), result.diagnostics().messages().toString());
        VariantResult v = result.variants().get(0);
        assertEquals(1, v.connectedCount(), v.unconnected().toString());
        assertEquals(80, v.summary().newLength(), .001);
        for (var link : v.network().links()) {
            assertEquals(du, link.du());
            assertTrue(clearanceOracle(in, width, link.coords().toArray(new Coordinate[0])));
        }
        assertEquals(Collections.emptyList(), new SolverVariantValidator(in).validate(v));
        assertEquals(Collections.emptyList(), StressEngineeringOracle.check(in, new OutputBuilder(in).build(v), false));
    }

    @Test void impossibleActualClearanceIsNotRelaxed() {
        InputModel in = straight(20, 1.24);
        assertFalse(clearanceOracle(in, .510, c(0,80), c(0,0)));
        // Even the terminal lies closer than 1 + .510/2 to a park: no alternative path can repair it.
        assertTrue(in.restrictions().get(0).geometry().distance(GeometryUtils.point(c(0,80))) < 1.255);
        var r = solve(in);
        assertFalse(r.variants().isEmpty());
        assertEquals(0, r.variants().get(0).connectedCount());
        assertEquals(Collections.emptyList(), new SolverVariantValidator(in).validate(r.variants().get(0)));
    }

    @Test void du100PassesBetweenBuildingsWithTenPointSixMetreGap() {
        var in=new SyntheticInput().line("net",300,-20,0,20,0).point("cp",0,80,20)
                .rect("left","oks",-20,10,-5.30,100).rect("right","oks",5.30,10,20,100).build();
        var witness=GeometryUtils.GF.createLineString(new Coordinate[]{c(0,80),c(0,0)});
        for(var building:in.restrictions()) {
            double distance=witness.distance(building.geometry());
            assertTrue(distance>=5+.510/2);
            assertTrue(distance<5+1.370/2);
        }
        var result=solve(in);
        assertFalse(result.variants().isEmpty());
        var v=result.variants().get(0);
        assertEquals(1,v.connectedCount(),v.unconnected().toString());
        assertEquals(Collections.emptyList(),new SolverVariantValidator(in).validate(v));
        assertEquals(Collections.emptyList(),StressEngineeringOracle.check(in,new OutputBuilder(in).build(v),false));
    }

    @Test void combiningConsumersMustNotReuseTheirSmallerEnvelope() {
        // Each 22 t/h branch fits DU100, but the 44 t/h trunk requires DU150 (DU125 capacity is 40.2).
        InputModel in = straight(22, 1.31);
        assertTrue(clearanceOracle(in, .510, c(0,80), c(0,0)));
        assertFalse(clearanceOracle(in, .650, c(0,80), c(0,0)));
        var joined = new SyntheticInput().line("net",300,-20,0,20,0)
                .point("a",-5,100,22).point("b",5,100,22)
                .rect("left","park",-20,5,-1.31,80).rect("right","park",1.31,5,20,80).build();
        var n = new ru.lct.heatnet.topology.NewNetwork();
        var root = n.rootOnExistingLine(joined.networkLines().get(0), c(0,0),2);
        var junction = n.addJunctionNode(c(0,100));
        n.addLink(junction,root,java.util.Arrays.asList(c(0,100),c(0,0)),Collections.emptyList());
        for (var cp:joined.connectionPoints())
            n.addLink(n.addConnectionNode(cp),junction,java.util.Arrays.asList(cp.coordinate(),c(0,100)),Collections.emptyList());
        assertTrue(ru.lct.heatnet.hydraulic.HydraulicCalculator.compute(n).feasible());
        assertEquals(150,junction.parent().du());
        var v = new VariantResult("upgraded",n,Collections.emptyMap(),
                ru.lct.heatnet.cost.CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());
        v.setVariantId("v1"); v.setRank(1);
        assertFalse(new SolverVariantValidator(joined).validate(v).isEmpty(), "actual upgraded trunk must be rejected");
    }

    @Test void fallbackRejectsRouteWhoseLengthRequiresAWiderDiameter() {
        var in=new SyntheticInput().line("net",300,-20,0,20,0).point("cp",0,450,20)
                .rect("left","park",-20,5,-1.275,500).rect("right","park",1.275,5,20,500).build();
        assertTrue(clearanceOracle(in,.510,c(0,450),c(0,0)));
        // DU100 length limit = 419 m; next DU125 width = .600. Even the terminal cannot fit it.
        assertFalse(clearanceOracle(in,.600,c(0,450),c(0,0)));
        var result=solve(in);
        assertFalse(result.variants().isEmpty());
        assertEquals(0,result.variants().get(0).connectedCount(),"do not publish the feasible small-width trial after DU upgrade");
        assertEquals(Collections.emptyList(),new SolverVariantValidator(in).validate(result.variants().get(0)));
    }

    @Test void allStrategiesPreserveTheExactDiameterConnectionThroughImprovement() {
        var in=straight(20,1.30);
        var r=new HeatnetSolver(SolverConfig.defaults(),new SolverVariantValidator(in)).solve(in);
        assertFalse(r.variants().isEmpty());
        for(var v:r.variants()) {
            assertEquals(1,v.connectedCount());
            assertEquals(Collections.emptyList(),new SolverVariantValidator(in).validate(v));
            assertFalse(v.trace().decisions().stream().anyMatch(d->"ROUTE_REJECTED".equals(d.type)));
        }
    }

    static InputModel bent() {
        Geometry outer = rectangle(-20,5,60,95);
        Geometry corridor = rectangle(-1.3,38.7,1.3,95).union(rectangle(-1.3,38.7,41.3,41.3))
                .union(rectangle(38.7,5,41.3,41.3));
        return new SyntheticInput().line("net",300,20,0,60,0).point("cp",0,80,20)
                .restriction("walls","park",outer.difference(corridor)).build();
    }
    static Geometry rectangle(double x0,double y0,double x1,double y1) {
        return GeometryUtils.GF.createPolygon(new Coordinate[]{c(x0,y0),c(x1,y0),c(x1,y1),c(x0,y1),c(x0,y0)});
    }
    @Test void bentWitnessAndInvalidShortcutAreDistinguishedIndependently() {
        InputModel in=bent();
        assertTrue(clearanceOracle(in,.510,c(0,80),c(0,40),c(40,40),c(40,0)));
        assertFalse(clearanceOracle(in,.510,c(0,80),c(40,0)));
        assertFalse(clearanceOracle(in,1.370,c(0,80),c(0,40),c(40,40),c(40,0)));
        var result=solve(in);
        assertFalse(result.variants().isEmpty());
        var v=result.variants().get(0);
        assertEquals(Collections.emptyList(),new SolverVariantValidator(in).validate(v));
        // This bounded direct fallback does not promise to solve a bent passage. Record, do not pin, its outcome.
        System.out.println("BENT_CORRIDOR connected="+v.connectedCount()+" reasons="+v.unconnected());
    }
}
