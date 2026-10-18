package ru.lct.heatnet.solver;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;
class VariantLimitTest {
    @Test void oversizedInternalConfigurationCannotPublishFourValidVariants() {
        var input=new SyntheticInput().line("net",300,-40,0,40,0).point("cp",0,30,1).build();
        var cfg=SolverConfig.defaults();cfg.maxVariants=99;var sequence=new AtomicInteger();
        var result=new HeatnetSolver(cfg,new SolverVariantValidator(input),(ctx,strategy)->{
            var n=new NewNetwork();var p=c(10*sequence.getAndIncrement(),0);
            var root=n.rootOnExistingLine(input.networkLines().get(0),p,2);
            n.addLink(n.addConnectionNode(input.connectionPoints().get(0)),root,Arrays.asList(c(0,30),p),Collections.emptyList());
            assertTrue(HydraulicCalculator.compute(n).feasible());
            return new VariantResult(strategy.name(),n,Collections.emptyMap(),CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());
        }).solve(input,HeatnetSolver.defaultStrategies().subList(0,4),null);
        assertEquals(4,sequence.get());assertEquals(3,result.variants().size());
    }
}
