package ru.lct.heatnet.depth;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The synthetic depth demo dataset shows ABOVE, BELOW, kept depth between close crossings, return to 3 m and rerouting. */
class DepthDemoDatasetTest {

    static SolveResult solve(InputModel in, boolean depth) {
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = depth;
        return new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
    }

    static double rootXOf(VariantResult v, String cpId) {
        for (NetNode n : v.network().connectionNodes()) {
            if (!n.connectionPoint().id().text().equals(cpId)) continue;
            NetNode r = n;
            while (r.parent() != null) r = r.parent().parent();
            return r.coord().x - SyntheticInput.OX;
        }
        return Double.NaN;
    }

    @Test
    void demoShowsAllDepthBehaviours() throws Exception {
        InputModel in;
        try (InputStream s = getClass().getResourceAsStream("/fixtures/depth-demo.geojson")) { in = new GeoJsonStreamReader(ParseOptions.defaults()).read(s); }
        VariantResult planar = solve(in, false).variants().get(0);
        assertEquals(3, planar.connectedCount());
        SolveResult dr = solve(in, true);
        assertFalse(dr.variants().isEmpty(), dr.diagnostics().messages().toString());
        VariantResult depth = dr.variants().get(0);
        assertEquals(3, depth.connectedCount(), depth.unconnected().toString());
        List<OutputFeature> fs = new ArrayList<>(new OutputBuilder().build(depth));
        ru.lct.heatnet.validation.ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(rep.isValid());
        // technical nodes between special pieces crossing different object sets (road / road + main) are justified
        assertEquals(0, rep.getWarningCount(), rep.getIssues().toString());
        // B: planar attaches under the building (gas 3.5 m from the tie-in), depth mode attaches outside the pipeline extent
        double pb = rootXOf(planar, "B"), db = rootXOf(depth, "B");
        assertTrue(pb > 130 && pb < 250, "planar B root x=" + pb);
        assertTrue(db < 130 || db > 250, "depth-aware B root x=" + db);
        // A: BELOW the gas pipeline (ABOVE ramp does not fit before the tie-in)
        boolean below = false, roadWithMain = false, keptDepth = false, flatAt3 = false;
        int above = 0;
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType())) continue;
            double d0 = ((Number) f.prop("depth_start")).doubleValue(), d1 = ((Number) f.prop("depth_end")).doubleValue();
            if (!"special".equals(f.prop("laying_method"))) {
                if (Math.abs(d0 - 3.0) < 1e-9 && Math.abs(d1 - 3.0) < 1e-9 && ((Number) f.prop("length")).doubleValue() > 5) flatAt3 = true;
                continue;
            }
            List<?> crossings = (List<?>) f.prop("crossings");
            boolean road = false, main = false;
            for (Object o : crossings) {
                Map<?, ?> c = (Map<?, ?>) o;
                if ("BELOW".equals(c.get("method"))) below = true;
                if ("ABOVE".equals(c.get("method"))) above++;
                if ("road".equals(c.get("type"))) road = true;
                if ("heat_network".equals(c.get("type"))) main = true;
            }
            if (road && main) { roadWithMain = true; assertTrue(d0 >= 1.0 && d0 < 3.0, "merged plateau above the main and under the road: " + d0); }
        }
        assertTrue(below, "A crosses the gas pipeline BELOW");
        assertTrue(above >= 3, "C crosses the cable, the gas pipe and the existing main ABOVE: " + above);
        assertTrue(roadWithMain, "road and existing main form one overlapping plateau");
        assertTrue(flatAt3, "profile returns to the normal depth between distant crossings");
        // C: between the power cable (y=60) and the gas pipe (y=52) the depth is kept shallow (no return to 3.0)
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType())) continue;
            double d0 = ((Number) f.prop("depth_start")).doubleValue(), d1 = ((Number) f.prop("depth_end")).doubleValue();
            double len = ((Number) f.prop("length")).doubleValue();
            if (!"special".equals(f.prop("laying_method")) && len < 5 && d0 < 2.9 && d1 < 2.9 && Math.abs(d0 - d1) > 1e-6) keptDepth = true;
        }
        assertTrue(keptDepth, "a short ramp between the two close crossings without returning to 3.0 m");
        OutputFeature summary = fs.get(fs.size() - 1);
        assertEquals("depth", summary.prop("mode"));
        assertTrue(((Number) summary.prop("below_crossing_count")).intValue() >= 1);
        assertTrue(((Number) summary.prop("above_crossing_count")).intValue() >= 3);
        assertTrue(((Number) summary.prop("max_depth")).doubleValue() >= 3.4 - 1e-6);
        assertTrue(depth.summary().newLength() > planar.summary().newLength(), "depth-aware network is longer in plan (reroute of B)");
    }
}
