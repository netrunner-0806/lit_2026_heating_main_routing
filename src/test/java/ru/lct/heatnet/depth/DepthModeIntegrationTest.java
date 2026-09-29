package ru.lct.heatnet.depth;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Depth mode end to end on the synthetic fixture with a road and a gas pipeline. */
class DepthModeIntegrationTest {

    static InputModel load(String name) throws Exception {
        try (InputStream in = DepthModeIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new GeoJsonStreamReader(ParseOptions.defaults()).read(in);
        }
    }

    @Test
    void depthModeProducesNumericDepthsAndConstantCrossingPlateaus() throws Exception {
        InputModel m = load("synthetic-basic.geojson");
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = true;
        SolveResult r = new HeatnetSolver(cfg, null).solve(m, HeatnetSolver.defaultStrategies(), null);
        assertFalse(r.variants().isEmpty(), r.diagnostics().messages().toString());
        VariantResult v = r.variants().get(0);
        assertEquals(3, v.connectedCount());
        List<OutputFeature> fs = new ArrayList<>(new OutputBuilder().build(v));
        int lines = 0, special = 0;
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType())) continue;
            lines++;
            assertNotNull(f.prop("depth_start"), "depth mode requires numeric depths");
            assertNotNull(f.prop("depth_end"));
            double d0 = ((Number) f.prop("depth_start")).doubleValue(), d1 = ((Number) f.prop("depth_end")).doubleValue();
            assertTrue(d0 >= 0.7 && d1 >= 0.7);
            double len = ((Number) f.prop("length")).doubleValue();
            assertTrue(Math.abs(d1 - d0) / len <= 0.1 + 1e-6, "slope");
            if ("special".equals(f.prop("laying_method"))) {
                special++;
                assertEquals(d0, d1, 1e-9, "constant depth on the crossing plateau: " + f.properties());
                assertNotNull(f.prop("crossings"));
            }
        }
        assertTrue(special >= 2, "road and gas crossings");
        OutputFeature s = fs.get(fs.size() - 1);
        assertEquals("variant_summary", s.objectType());
        assertEquals("depth", s.prop("mode"));
        assertTrue(((Number) s.prop("vertical_crossing_count")).intValue() >= 1);
        assertTrue(((Number) s.prop("max_depth")).doubleValue() >= 3.0);
        ru.lct.heatnet.validation.ValidationReport rep = new ru.lct.heatnet.validation.ResultValidator(m).validate(fs);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        assertTrue(rep.getChecksPerformed().keySet().stream().anyMatch(k -> k.startsWith("depth:")));
    }
}
