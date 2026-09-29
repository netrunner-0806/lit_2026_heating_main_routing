package ru.lct.heatnet.oracle;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Variant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The finished output of the production solver must agree with the independent oracle (hand-typed annex tables,
 * own topology/flow/DU/cost/depth recomputation) on every fixture, in both modes.
 */
class OracleConformanceTest {

    static List<String> discrepancies(OracleRuns.Run run) {
        List<String> out = new ArrayList<>();
        Map<String, Variant> variants = ReferenceOutputOracle.parse(run.features, run.input);
        if (variants.isEmpty()) out.add("no variants in the output");
        for (Variant v : variants.values()) {
            for (String p : v.problems) out.add(v.vid + ": " + p);
            if (v.summary() == null) { out.add(v.vid + ": no variant_summary"); continue; }
            boolean depthOut = "depth".equals(v.summary().prop("mode"));
            if (depthOut != run.depth && !v.lines.isEmpty()) out.add(v.vid + ": summary mode " + v.summary().prop("mode") + " but the run is " + (run.depth ? "DEPTH" : "PLANAR"));
            for (String p : ReferenceHydraulicOracle.check(v)) out.add(v.vid + " [hydraulic] " + p);
            for (String p : ReferenceCostOracle.check(v, run.input)) out.add(v.vid + " [cost] " + p);
            for (String p : ReferenceDepthOracle.check(v, run.input)) out.add(v.vid + " [depth] " + p);
        }
        return out;
    }

    @TestFactory
    List<DynamicTest> productionOutputMatchesTheIndependentOracle() {
        List<DynamicTest> tests = new ArrayList<>();
        for (OracleRuns.Run run : OracleRuns.all()) {
            tests.add(DynamicTest.dynamicTest(run.toString(), () -> {
                assertFalse(run.result.variants().isEmpty(), run + ": no variants " + run.result.diagnostics().messages());
                List<String> d = discrepancies(run);
                assertTrue(d.isEmpty(), run + ": " + d.size() + " discrepancies:\n  " + String.join("\n  ", d));
            }));
        }
        return tests;
    }

    /** The oracle itself reproduces the worked example of annex section 7.3 (100 m of DU100 = 8 974 800 ₽, one tie-in, S = 0.6913). */
    @Test
    void oracleReproducesTheAnnexExample() {
        ReferenceRules.Du du100 = ReferenceRules.byDu(100);
        assertEquals(8_974_800L, Math.round(100.0 * du100.costPerMetre * ReferenceRules.kDepth(3.0) * 1.0));
        double calculated = 8_974_800L + ReferenceRules.EXISTING_CHAMBER_TIE_IN_COST;
        assertEquals(13_974_800L, Math.round(calculated));
        assertEquals(0.6913, Math.round(ReferenceRules.score(calculated, 100.0) * 10000) / 10000.0, 1e-9);
        assertEquals(100, ReferenceRules.minByFlow(20.0).du);
    }

    /** The oracle detects a wrong figure (sanity check that the comparison is not vacuous). */
    @Test
    void oracleRejectsATamperedOutput() {
        OracleRuns.Run run = OracleRuns.run("synthetic-basic", false);
        List<ru.lct.heatnet.output.OutputFeature> tampered = new ArrayList<>();
        boolean done = false;
        for (ru.lct.heatnet.output.OutputFeature f : run.features) {
            ru.lct.heatnet.output.OutputFeature g = f.geomType() == ru.lct.heatnet.output.OutputFeature.GeomType.LINESTRING
                    ? ru.lct.heatnet.output.OutputFeature.line(f.line()) : f.geomType() == ru.lct.heatnet.output.OutputFeature.GeomType.POINT
                    ? ru.lct.heatnet.output.OutputFeature.point(f.point()) : ru.lct.heatnet.output.OutputFeature.noGeometry();
            for (Map.Entry<String, Object> e : f.properties().entrySet()) g.prop(e.getKey(), e.getValue());
            if (!done && "heat_network".equals(g.objectType()) && "v1".equals(g.prop("variant_id"))) {
                g.prop("cost", ((Number) g.prop("cost")).longValue() + 1000);
                g.prop("diameter", 1400);
                done = true;
            }
            tampered.add(g);
        }
        assertTrue(done);
        OracleRuns.Run t = new OracleRuns.Run("tampered", false, run.input, run.result, tampered);
        List<String> d = discrepancies(t);
        assertTrue(d.stream().anyMatch(x -> x.contains("cost")), d.toString());
        assertTrue(d.stream().anyMatch(x -> x.contains("not minimal") || x.contains("decreases")), d.toString());
    }
}
