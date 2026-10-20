package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.routing.*;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelStrategyStateTest {
    String geo(SolveResult result)throws Exception {var bytes=new java.io.ByteArrayOutputStream();new ru.lct.heatnet.output.GeoJsonResultWriter().write(bytes,SolveKit.render(result));return bytes.toString("UTF-8");}
    InputModel scene(){return new SyntheticInput().line("net",300,0,0,100,0).point("cp-a",30,30,1).point("cp-b",70,35,1).build();}
    @Test void graphAndApproachMutationsArePrivate()throws Exception {
        SolverContext base=new SolverContext(scene(),SolverConfig.defaults());
        SolverContext a=base.experimentalFork(),b=base.experimentalFork();
        var original=base.resources(ClearanceClass.SMALL);var ar=a.resources(ClearanceClass.SMALL);var br=b.resources(ClearanceClass.SMALL);
        int n=original.graph.size(),before=original.approaches.values().iterator().next().get(0).vertex();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> fa=pool.submit(()->{ar.graph.addVertex(new Coordinate(20,50),Exemptions.NONE,100,null);ar.approaches.values().iterator().next().get(0).setVertex(12345);ar.graph.truncate(n);});
            Future<?> fb=pool.submit(()->AdaptiveRouting.expandRegion(br.graph,new org.locationtech.jts.geom.Envelope(10,20,50,60),10,100));
            fa.get();fb.get();
            assertEquals(n,original.graph.size());assertEquals(n,ar.graph.size());assertTrue(br.graph.size()>n);
            assertEquals(before,original.approaches.values().iterator().next().get(0).vertex());assertEquals(before,br.approaches.values().iterator().next().get(0).vertex());
        }finally{pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void simultaneousAdaptiveExpansionsDoNotPolluteSibling()throws Exception {
        SolverContext base=new SolverContext(scene(),SolverConfig.defaults());var a=base.experimentalFork().resources(ClearanceClass.SMALL);var b=base.experimentalFork().resources(ClearanceClass.SMALL);
        int baseSize=a.graph.size();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<?> fa=pool.submit(()->AdaptiveRouting.expandGrid(a.graph,20,100));Future<?> fb=pool.submit(()->AdaptiveRouting.expandGrid(b.graph,20,100));fa.get();fb.get();assertEquals(a.graph.size(),b.graph.size());assertTrue(a.graph.size()>baseSize);a.graph.truncate(baseSize);assertTrue(b.graph.size()>a.graph.size());}
        finally{pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void outputAndExplainAreDeterministicAcrossJobsAndModes()throws Exception {
        for(boolean depth:new boolean[]{false,true}) {
            var cfg=SolverConfig.defaults();cfg.depthMode=depth;var input=scene();
            SolveResult sequential=new HeatnetSolver(cfg,new SolverVariantValidator(input)).solve(input);
            String expected=geo(sequential);
            String trace=StrategyTestSupport.JSON.writeValueAsString(sequential.variants().get(0).trace().decisions());
            for(int repeat=0;repeat<3;repeat++){
                var next=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(next));
                try(ParallelExperiment p=new ParallelExperiment((ctx,s)->StrategyTestSupport.build(control,ctx,s))){
                    var actual=new HeatnetSolver(cfg,new SolverVariantValidator(next),p::apply).solve(next);p.requireAdmissible();
                    assertEquals(expected,geo(actual));
                    assertEquals(trace,StrategyTestSupport.JSON.writeValueAsString(actual.variants().get(0).trace().decisions()));
                }
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
            }
        }
    }
    @Test void oneLocalFailurePreservesOtherValidatedResults(){
        var cfg=SolverConfig.defaults();var input=scene();var control=new HeatnetSolver(cfg,new SolverVariantValidator(input));
        try(ParallelExperiment p=new ParallelExperiment((ctx,s)->{if(s.name().equals("joint-farthest-first"))throw new ComplexityLimitException("injected local edge budget");return StrategyTestSupport.build(control,ctx,s);})){
            var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),p::apply).solve(input);assertFalse(result.variants().isEmpty());assertEquals(2,result.variants().get(0).connectedCount());assertTrue(result.diagnostics().messages().stream().anyMatch(m->m.getCode().equals("COMPLEXITY_LIMIT")));
        }
    }
}
