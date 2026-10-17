package ru.lct.heatnet.solver;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static ru.lct.heatnet.SyntheticInput.c;
import static org.junit.jupiter.api.Assertions.*;

class VariantDeduplicationAuditTest {
    final InputModel in=new SyntheticInput().line("net",300,-100,0,100,0).point("cp",0,100,10).build();
    VariantResult variant(String name,double x,boolean mirror) {
        NewNetwork net=new NewNetwork();
        var points=mirror?Arrays.asList(c(0,100),c(x,60),c(x,40),c(0,0)):Arrays.asList(c(0,100),c(x,0));
        net.addLink(net.addConnectionNode(in.connectionPoints().get(0)),net.rootOnExistingLine(in.networkLines().get(0),points.get(points.size()-1),2),points,Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(net).feasible());
        return new VariantResult(name,net,Collections.emptyMap(),CostCalculator.summarize(net,Collections.emptyList()),Collections.emptyList());
    }
    SolveResult solve(boolean mirror) {
        AtomicInteger i=new AtomicInteger();
        return new HeatnetSolver(SolverConfig.defaults(),new SolverVariantValidator(in),(ctx,s)->
                variant(s.name(),mirror?(i.getAndIncrement()==0?-40:40):(i.getAndIncrement()==0?2.49:2.51),mirror))
                .solve(in,HeatnetSolver.defaultStrategies().subList(0,2),null);
    }
    @Test void mirrorDetoursWithSameLengthAndRootAreDistinct() {assertEquals(2,solve(true).variants().size());}
    @Test void centimetreRootShiftAcrossGridBoundaryIsDuplicate() {assertEquals(1,solve(false).variants().size());}
}
