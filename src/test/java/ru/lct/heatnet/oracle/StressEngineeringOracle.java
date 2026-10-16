package ru.lct.heatnet.oracle;

import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import java.util.List;

/** Test-only adapter to the independent annex tables and flow/cost/depth recomputation. */
public final class StressEngineeringOracle {
    private StressEngineeringOracle() { }
    public static List<String> check(InputModel input, List<OutputFeature> features, boolean depth) {
        return OracleConformanceTest.discrepancies(new OracleRuns.Run("stress",depth,input,null,features));
    }
}
