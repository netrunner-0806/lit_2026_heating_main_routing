package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SolverSmokeTest {

    @Test
    void solvesCorrectedDataset() throws Exception {
        Assumptions.assumeTrue(TestData.correctedAvailable());
        InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(TestData.CORRECTED);
        SolverConfig cfg = SolverConfig.defaults();
        String only = System.getProperty("strategy");
        List<Strategy> strategies = HeatnetSolver.defaultStrategies();
        if (only != null) {
            List<Strategy> f = new ArrayList<>();
            for (Strategy s : strategies) if (s.name().equals(only)) f.add(s);
            strategies = f;
        }
        SolveResult r = new HeatnetSolver(cfg, new ru.lct.heatnet.validation.SolverVariantValidator(m)).solve(m, strategies, null);
        for (VariantResult v : r.variants()) System.out.println(v.variantId() + " rank=" + v.rank() + " " + v.strategy() + ": " + v.summary() + " unconnected=" + v.unconnected().keySet());
        for (ru.lct.heatnet.domain.Diagnostics.Message msg : r.diagnostics().messages()) System.out.println(msg);
        assertFalse(r.variants().isEmpty());
        List<OutputFeature> features = new ArrayList<>();
        OutputBuilder ob = new OutputBuilder();
        for (VariantResult v : r.variants()) features.addAll(ob.build(v));
        Path out = Paths.get("target", "smoke-result.geojson");
        Files.createDirectories(out.getParent());
        new GeoJsonResultWriter().write(out, features);
        System.out.println("written " + out.toAbsolutePath() + " features=" + features.size());
    }
}
