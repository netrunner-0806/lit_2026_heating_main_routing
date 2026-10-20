package ru.lct.heatnet.oracle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.validation.SolverVariantValidator;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MultipartTieInOracleTest {
    @ParameterizedTest @EnumSource(SolverConfig.ExecutionMode.class)
    void checksAllComponentsButStillRejectsAChamberOffTheWholeLine(SolverConfig.ExecutionMode mode) throws Exception {
        var input = new GeoJsonStreamReader(ParseOptions.defaults())
                .read(Path.of("src/test/resources/adversarial/08-multiline-existing.geojson"));
        var cfg = SolverConfig.defaults(); cfg.executionMode = mode;
        var result = new HeatnetSolver(cfg, new SolverVariantValidator(input)).solve(input);
        assertEquals(1, result.variants().get(0).connectedCount());
        var variant = ReferenceOutputOracle.parse(SolveKit.render(result), input).values().iterator().next();
        var chamber = variant.chambers.get(0);
        var point = CrsTransformer.METRIC_FACTORY.createPoint(chamber.metric);
        assertTrue(input.networkLines().get(0).line().distance(point) > 0.05);
        assertTrue(input.networkLines().get(1).line().distance(point) <= 0.05);
        assertTrue(ReferenceCostOracle.check(variant, input).isEmpty(), ReferenceCostOracle.check(variant, input).toString());
        // A string ID must not select components of the numeric ID with the same spelling.
        chamber.existingLineId = "100";
        assertTrue(ReferenceCostOracle.check(variant, input).stream()
                .anyMatch(issue -> issue.contains("is not on the existing line")));
        chamber.existingLineId = 100;
        chamber.metric.y += 1;
        assertTrue(ReferenceCostOracle.check(variant, input).stream()
                .anyMatch(issue -> issue.contains("is not on the existing line")));
        chamber.existingLineId = 999;
        assertTrue(ReferenceCostOracle.check(variant, input).stream()
                .anyMatch(issue -> issue.contains("not found in the input")));
    }
}
