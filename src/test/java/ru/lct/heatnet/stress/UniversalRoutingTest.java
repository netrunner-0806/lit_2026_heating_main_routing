package ru.lct.heatnet.stress;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.stream.Stream;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.validation.ResultValidator;
import static org.junit.jupiter.api.Assertions.*;

class UniversalRoutingTest {
    static Stream<String> scenes() throws Exception {
        var root=new ObjectMapper().readTree(Paths.get("examples/stress/universal-manifest.json").toFile());
        return java.util.stream.StreamSupport.stream(root.get("scenarios").spliterator(),false)
                .map(n->n.get("file").asText()).filter(s->s.startsWith("universal-"));
    }
    @ParameterizedTest @MethodSource("scenes")
    void knownFeasibleRoutesAreFoundInBothModes(String file) throws Exception {
        Path input=Paths.get("examples/stress",file);
        boolean impossible=file.contains("impossible");
        if(!impossible) WitnessRoutesTest.validate(input.resolveSibling(file.replace(".geojson",".witness.json")));
        for(boolean depth:new boolean[]{false,true}) {
            String previous=null;
            for(int repeat=0;repeat<2;repeat++) {
                var m=new GeoJsonStreamReader(ParseOptions.defaults()).read(input);
                assertFalse(m.diagnostics().hasErrors());
                var result=SolveKit.solve(m,depth);
                assertFalse(result.diagnostics().hasErrors(),result.diagnostics().messages().toString());
                assertFalse(result.variants().isEmpty());
                assertEquals(impossible?0:1,result.variants().get(0).connectedCount(),file+" "+result.variants().get(0).unconnected());
                var fs=SolveKit.render(result);var rep=new ResultValidator(m).validate(SolveKit.roundTrip(fs));
                assertTrue(rep.isValid(),rep.getIssues().toString());assertEquals(0,rep.getWarningCount(),rep.getIssues().toString());
                assertEquals(java.util.Collections.emptyList(),ru.lct.heatnet.oracle.StressEngineeringOracle.check(m,fs,depth));
                String canonical=SolveKit.canonical(fs,false);if(previous!=null)assertEquals(previous,canonical);previous=canonical;
            }
        }
    }
}
