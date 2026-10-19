package ru.lct.heatnet.stress;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.validation.ResultValidator;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StressRoutingTest {
    @ParameterizedTest
    @ValueSource(strings={"routing-fallback", "routing-fallback-detour", "routing-fallback-oks", "routing-fallback-road-exit", "geometry-edge-cases", "routing-impossible", "routing-impossible-exit"})
    void fixturesHaveValidDeterministicEngineeringResults(String name) throws Exception {
        Path input=Paths.get("src/test/resources/routing",name+".geojson");
        if(!name.startsWith("routing-impossible")) WitnessRoutesTest.validate(input.resolveSibling(name+".witness.json"));
        int expected=name.startsWith("routing-impossible")?0:1;
        for(boolean depth:new boolean[]{false,true}) {
            String previous=null;
            for(int repeat=0;repeat<2;repeat++) {
                InputModel model=new GeoJsonStreamReader(ParseOptions.defaults()).read(input);
                assertFalse(model.diagnostics().hasErrors());
                SolveResult result=SolveKit.solve(model,depth);
                assertFalse(result.diagnostics().hasErrors(),result.diagnostics().messages().toString());
                assertFalse(result.variants().isEmpty());
                assertEquals(expected,result.variants().get(0).connectedCount(),name);
                var fs=SolveKit.render(result);
                var validation=new ResultValidator(model).validate(SolveKit.roundTrip(fs));
                assertTrue(validation.isValid(),validation.getIssues().toString());
                assertEquals(0,validation.getWarningCount());
                assertEquals(java.util.Collections.emptyList(),ru.lct.heatnet.oracle.StressEngineeringOracle.check(model,fs,depth));
                String canonical=SolveKit.canonical(fs,false);
                if(previous!=null)assertEquals(previous,canonical,"determinism: "+name);
                previous=canonical;
                if(name.startsWith("routing-fallback"))assertTrue(((Number)result.metrics().get("fallback_activations")).intValue()>0);
                for(var v:result.variants())for(String reason:v.unconnected().values())assertTrue(reason.startsWith("NO_"),reason);
            }
        }
    }
}
