package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.routing.ComplexityLimitException;
import ru.lct.heatnet.validation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetryAuditTest {
    private void retry(String code, boolean alwaysInvalid, int expectedCalls) {
        var input=new SyntheticInput().line("net",300,0,0,20,0).point("cp",10,20,1).build();
        var cfg=SolverConfig.defaults();cfg.improve=false;
        var calls=new AtomicInteger();
        HeatnetSolver.VariantValidator validator=new HeatnetSolver.VariantValidator() {
            public List<String> validate(VariantResult v) {
                return alwaysInvalid || calls.get()==1 ? List.of("opaque localized error") : new SolverVariantValidator(input).validate(v);
            }
            public List<ValidationIssue> validateIssues(VariantResult v) {
                return alwaysInvalid || calls.get()==1 ? List.of(new ValidationIssue(ValidationIssue.Severity.ERROR,code,"opaque localized error",null,null)) : List.of();
            }
        };
        var result=new HeatnetSolver(cfg,validator,(ctx,s)->{
            calls.incrementAndGet();return new ForestBuilder(ctx,s,Collections.emptyMap()).build();
        }).solve(input,HeatnetSolver.defaultStrategies().subList(0,1),null);
        assertEquals(expectedCalls,calls.get());
        if(expectedCalls==2 && !alwaysInvalid) assertEquals(1,result.variants().size());
        else assertTrue(result.variants().isEmpty());
    }
    @Test void clearanceUsesCodeNotMessage() { retry("CLEARANCE",false,2); }
    @Test void ownBuildingClearanceAlsoRetries() { retry("OWN_OKS_CLEARANCE",false,2); }
    @Test void otherErrorsDoNotRetry() { retry("CHAMBER_DEGREE",false,1); }
    @Test void atMostOneRetry() { retry("CLEARANCE",true,2); }
    @Test void identicalGraphFailureIsReusedButOtherClassIsIndependent() {
        var input=new SyntheticInput().line("net",300,0,0,20,0).point("cp",10,20,1).build();
        var cfg=SolverConfig.defaults();cfg.helperGridSpacing=.001;
        var ctx=new SolverContext(input,cfg,false);
        var first=assertThrows(ComplexityLimitException.class,()->ctx.resources(ClearanceClass.SMALL));
        assertSame(first,assertThrows(ComplexityLimitException.class,()->ctx.resources(ClearanceClass.SMALL)));
        assertNotSame(first,assertThrows(ComplexityLimitException.class,()->ctx.resources(ClearanceClass.MEDIUM)));
        // Exact-clearance mode is a different construction condition, not a cached approximate failure.
        ctx.requireExactClearance();
        assertNotSame(first,assertThrows(ComplexityLimitException.class,()->ctx.resources(ClearanceClass.SMALL)));
    }
}
