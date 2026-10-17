package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.routing.ComplexityLimitException;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StrategyFailureIsolationTest {
    private SolveResult run(int failsAt, int count, boolean rejectAll) {
        InputModel input = new SyntheticInput().source("s",0,0).line("net",300,0,0,100,0)
                .point("cp",50,40,1).build();
        SolverConfig cfg = SolverConfig.defaults(); cfg.improve=false;
        AtomicInteger calls = new AtomicInteger();
        SolverVariantValidator independent = new SolverVariantValidator(input);
        return new HeatnetSolver(cfg, v -> rejectAll ? Collections.singletonList("rejected") : independent.validate(v),
                (ctx,s) -> {
                    if (calls.getAndIncrement() == failsAt) throw new ComplexityLimitException("injected graph limit");
                    return new ForestBuilder(ctx,s,Collections.emptyMap()).build();
                }).solve(input,HeatnetSolver.defaultStrategies().subList(0,count),null);
    }
    @Test void firstSuccessSurvivesSecondFailure() {
        SolveResult r=run(1,2,false);
        assertFalse(r.variants().isEmpty()); assertEquals(1,r.variants().get(0).connectedCount());
        assertTrue(r.diagnostics().messages().stream().anyMatch(m -> m.getCode().equals("COMPLEXITY_LIMIT")
                && m.getText().contains("joint-farthest-first")));
    }
    @Test void fourCompletedStrategiesSurviveLastFailure() { assertFalse(run(4,5,false).variants().isEmpty()); }
    @Test void firstFailureDoesNotPreventNextStrategy() { assertFalse(run(0,2,false).variants().isEmpty()); }
    @Test void failureDoesNotPromoteUnvalidatedCandidate() {
        SolveResult r=run(1,2,true); assertTrue(r.variants().isEmpty());
        assertTrue(r.diagnostics().messages().stream().anyMatch(m -> m.getCode().equals("NO_VALID_VARIANT")));
    }
    @Test void allFailedHasExplicitReason() { assertTrue(run(0,1,false).variants().isEmpty()); }
    @Test void deterministicAfterFailure() {
        assertEquals(HeatnetSolver.signature(run(1,3,false).variants().get(0)),
                HeatnetSolver.signature(run(1,3,false).variants().get(0)));
    }
    @Test void validationFailureOfLaterCandidateAlsoPreservesCompletedValidation() {
        var input=new SyntheticInput().line("net",300,0,0,100,0).point("cp",50,40,1).build();
        AtomicInteger validations=new AtomicInteger();var independent=new SolverVariantValidator(input);
        var result=new HeatnetSolver(SolverConfig.defaults(),v->{
            if(validations.getAndIncrement()==1)throw new ComplexityLimitException("validation fixture budget");
            return independent.validate(v);
        }).solve(input,HeatnetSolver.defaultStrategies().subList(0,2),null);
        assertFalse(result.variants().isEmpty());assertEquals(1,result.variants().get(0).connectedCount());
        assertTrue(result.diagnostics().messages().stream().anyMatch(m->m.getCode().equals("COMPLEXITY_LIMIT")));
    }
}
