package ru.lct.heatnet.stress;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.domain.*;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.validation.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalNavigationTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void expansionAndDiameterUpgradeMustPreserveTheGlobalDetour(boolean depth)throws Exception {
        InputModel m=new ru.lct.heatnet.SyntheticInput().source(1,-50,0).line(2,300,-70,0,-30,0)
                .chamber(3,-50,0).point(4,100,0,1).rect(5,"oks",98,-2,102,2)
                .rect(6,"water",0,-3000,40,3000).rect(7,"park",10000,-2,10010,2).build();
        // Independent 6.35 km witness: above the long water body, away from the remote park.
        var witness=new ru.lct.heatnet.topology.NewNetwork();
        var child=witness.addConnectionNode(m.connectionPoints().get(0));
        var root=witness.rootAtExistingChamber(m.chambers().get(0),2);
        witness.addLink(child,root,java.util.Arrays.asList(child.coord(),ru.lct.heatnet.SyntheticInput.c(100,3100),
                ru.lct.heatnet.SyntheticInput.c(-50,3100),root.coord()),java.util.Collections.emptyList());
        assertTrue(ru.lct.heatnet.hydraulic.HydraulicCalculator.compute(witness).feasible());
        if(depth)assertTrue(new ru.lct.heatnet.depth.NetworkDepthPlanner().plan(witness).feasible);
        var w=new VariantResult("independent witness",witness,java.util.Collections.emptyMap(),
                ru.lct.heatnet.cost.CostCalculator.summarize(witness,java.util.Collections.emptyList()),java.util.Collections.emptyList());
        w.setVariantId("v1");w.setRank(1);
        w.setDepthMode(depth);
        var witnessReport=new ResultValidator(m).validate(SolveKit.roundTrip(new ru.lct.heatnet.output.OutputBuilder().build(w)));
        assertTrue(witnessReport.isValid(),witnessReport.getIssues().toString());
        for(boolean local:new boolean[]{false,true}) {
            SolverConfig cfg=SolverConfig.defaults();cfg.localGraphMinimumObstacles=local?0:Integer.MAX_VALUE;cfg.depthMode=depth;
            SolveResult r=new HeatnetSolver(cfg,new SolverVariantValidator(m)).solve(m,
                    java.util.Collections.singletonList(HeatnetSolver.defaultStrategies().get(0)),null);
            assertFalse(r.variants().isEmpty(),r.diagnostics().messages().toString());
            assertEquals(1,r.variants().get(0).connectedCount(),"local="+local+" "+r.variants().get(0).unconnected());
            var v=new ResultValidator(m).validate(SolveKit.roundTrip(SolveKit.render(r)));
            assertTrue(v.isValid(),v.getIssues().toString());assertEquals(0,v.getWarningCount());
        }
    }

    @ParameterizedTest @ValueSource(strings={"universal-water-detours","universal-long-detour","universal-narrow-corridor",
            "universal-intersecting-roads","universal-adjacent-roads","routing-fallback","routing-fallback-detour","universal-boundary"})
    void localSearchAndExpansionKeepKnownFeasibleConnections(String name)throws Exception {
        for(boolean depth:new boolean[]{false,true}) {
            String previous=null;
            for(int repeat=0;repeat<2;repeat++) {
                InputModel m=new GeoJsonStreamReader(ParseOptions.defaults()).read(Paths.get("examples/stress",name+".geojson"));
                SolverConfig cfg=SolverConfig.defaults();cfg.depthMode=depth;cfg.localGraphMinimumObstacles=0;cfg.localGraphMargin=30;
                SolveResult r=new HeatnetSolver(cfg,new SolverVariantValidator(m)).solve(m);
                assertFalse(r.diagnostics().hasErrors(),r.diagnostics().messages().toString());
                assertFalse(r.variants().isEmpty());
                assertEquals(1,r.variants().get(0).connectedCount(),name+" "+r.variants().get(0).unconnected());
                var features=SolveKit.render(r);var validation=new ResultValidator(m).validate(SolveKit.roundTrip(features));
                assertTrue(validation.isValid(),validation.getIssues().toString());assertEquals(0,validation.getWarningCount());
                assertEquals(java.util.Collections.emptyList(),ru.lct.heatnet.oracle.StressEngineeringOracle.check(m,features,depth));
                String canonical=SolveKit.canonical(features,false);if(previous!=null)assertEquals(previous,canonical);previous=canonical;
            }
        }
    }

    @Test void aLongBarrierRequiresExpansionAndIsNeverDiscarded()throws Exception {
        InputModel m=new ru.lct.heatnet.SyntheticInput().source(1,-50,0).line(2,300,-50,-20,-50,20)
                .chamber(3,-50,0).point(4,100,0,1).rect(5,"oks",98,-2,102,2)
                .rect(6,"water",0,-500,40,500).build();
        SolverConfig cfg=SolverConfig.defaults();cfg.localGraphMinimumObstacles=0;cfg.localGraphMargin=25;
        SolveResult r=new HeatnetSolver(cfg,new SolverVariantValidator(m)).solve(m);
        assertFalse(r.variants().isEmpty(),r.diagnostics().messages().toString());
        assertEquals(1,r.variants().get(0).connectedCount(),r.variants().get(0).unconnected().toString());
        assertTrue(((Number)r.metrics().get("navigation_region_expansions")).intValue()>0);
        var features=SolveKit.render(r);var validation=new ResultValidator(m).validate(SolveKit.roundTrip(features));
        assertTrue(validation.isValid(),validation.getIssues().toString());
    }

    @Test void neighbouringConsumersKeepSharedConnectionsAcrossTheWorkingArea()throws Exception {
        double globalScore=Double.NaN;
        for(boolean local:new boolean[]{false,true}) {
            InputModel m=new ru.lct.heatnet.SyntheticInput().source(1,0,0).line(2,300,0,-20,0,20).chamber(3,0,0)
                    .point(4,100,-15,1).point(5,100,15,1)
                    .rect(6,"oks",98,-17,102,-13).rect(7,"oks",98,13,102,17).build();
            SolverConfig cfg=SolverConfig.defaults();cfg.localGraphMinimumObstacles=local?0:Integer.MAX_VALUE;cfg.localGraphMargin=25;
            SolveResult r=new HeatnetSolver(cfg,new SolverVariantValidator(m)).solve(m);
            assertEquals(2,r.variants().get(0).connectedCount());
            double score=r.variants().get(0).summary().score();
            if(local)assertEquals(globalScore,score,1e-9);else globalScore=score;
            var validation=new ResultValidator(m).validate(SolveKit.roundTrip(SolveKit.render(r)));
            assertTrue(validation.isValid(),validation.getIssues().toString());
        }
    }
}
