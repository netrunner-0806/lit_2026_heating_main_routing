package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.OutputFeatureReader;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end runs on small synthetic datasets that have nothing to do with the competition coordinates. */
class SyntheticIntegrationTest {

    static InputModel load(String name) throws Exception {
        try (InputStream in = SyntheticIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, name);
            return new GeoJsonStreamReader(ParseOptions.defaults()).read(in);
        }
    }

    static SolveResult solve(InputModel m) {
        SolverConfig cfg = SolverConfig.defaults();
        return new HeatnetSolver(cfg, new SolverVariantValidator(m)).solve(m, HeatnetSolver.defaultStrategies(), null);
    }

    static List<OutputFeature> render(SolveResult r) {
        List<OutputFeature> out = new ArrayList<>();
        OutputBuilder b = new OutputBuilder();
        for (VariantResult v : r.variants()) out.addAll(b.build(v));
        return out;
    }

    static OutputFeature summary(List<OutputFeature> fs, String vid) {
        for (OutputFeature f : fs) if ("variant_summary".equals(f.objectType()) && vid.equals(f.prop("variant_id"))) return f;
        return null;
    }

    @Test
    void basicDatasetWithRoadGasAndExistingChamber() throws Exception {
        InputModel m = load("synthetic-basic.geojson");
        assertEquals(3, m.connectionPoints().size());
        SolveResult r = solve(m);
        assertFalse(r.variants().isEmpty(), r.diagnostics().messages().toString());
        VariantResult best = r.variants().get(0);
        assertEquals(3, best.connectedCount(), "all three points connected: " + best.unconnected());
        List<OutputFeature> fs = render(r);
        // independent validation of the rendered output
        ValidationReport rep = new ResultValidator(m).validate(fs);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        // special passages for the road (K 1.60) and the gas pipeline (K 1.25) exist in the best variant
        boolean road = false, gas = false, base = false, chamberTieIn = false;
        for (OutputFeature f : fs) {
            if (!"v1".equals(f.prop("variant_id"))) continue;
            if ("heat_network".equals(f.objectType())) {
                assertNull(f.prop("depth_start"));
                assertNull(f.prop("depth_end"));
                assertNotNull(f.prop("length"));
                if ("special".equals(f.prop("laying_method"))) {
                    double k = ((Number) f.prop("k_spec")).doubleValue();
                    if (k == 1.60) road = true;
                    if (k == 1.25) gas = true;
                } else base = true;
                if (Integer.valueOf(200).equals(f.prop("start_node_id")) || Integer.valueOf(200).equals(f.prop("end_node_id"))) chamberTieIn = true;
            }
        }
        assertTrue(road, "route crosses the road as a special passage");
        assertTrue(gas, "route crosses the gas pipeline as a special passage");
        assertTrue(base);
        assertTrue(chamberTieIn, "the point next to chamber 200 ties into the existing chamber (10 m rule)");
        OutputFeature s = summary(fs, "v1");
        assertTrue(((Number) s.prop("existing_chamber_tie_in_count")).intValue() >= 1);
        assertEquals(5_000_000L * ((Number) s.prop("existing_chamber_tie_in_count")).intValue(), ((Number) s.prop("existing_chamber_tie_in_cost")).longValue());
        assertEquals(0L, ((Number) s.prop("unconnected_penalty")).longValue());
        assertEquals(1, s.prop("rank"));
    }

    @Test
    void unreachablePointIsReportedWithPenaltyAndOriginalIdType() throws Exception {
        InputModel m = load("synthetic-unreachable.geojson");
        SolveResult r = solve(m);
        assertFalse(r.variants().isEmpty());
        VariantResult v = r.variants().get(0);
        assertEquals(1, v.connectedCount());
        assertEquals(1, v.unconnected().size());
        assertEquals(JsonId.ofString("far"), v.unconnected().keySet().iterator().next().id());
        assertEquals(100_000_000 + 500_000 * 12.5, v.summary().penalty(), 1e-6);
        List<OutputFeature> fs = render(r);
        OutputFeature s = summary(fs, "v1");
        List<?> ids = (List<?>) s.prop("unconnected_oks_ids");
        assertEquals(1, ids.size());
        assertEquals("far", ids.get(0));
        assertTrue(new ResultValidator(m).validate(fs).isValid());
        // round trip through the GeoJSON writer/reader keeps the id types
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new GeoJsonResultWriter().write(bos, fs);
        List<OutputFeature> back = new OutputFeatureReader().read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(fs.size(), back.size());
        OutputFeature s2 = summary(back, "v1");
        assertEquals("far", ((List<?>) s2.prop("unconnected_oks_ids")).get(0));
        assertTrue(new ResultValidator(m).validate(back).isValid());
    }

    @Test
    void twoNeighboursShareATrunk() throws Exception {
        InputModel m = load("synthetic-shared-trunk.geojson");
        SolveResult r = solve(m);
        assertFalse(r.variants().isEmpty());
        VariantResult v = r.variants().get(0);
        assertEquals(2, v.connectedCount());
        List<OutputFeature> fs = render(r);
        assertTrue(new ResultValidator(m).validate(fs).isValid());
        boolean trunk = false;
        int junctions = 0;
        for (OutputFeature f : fs) {
            if (!"v1".equals(f.prop("variant_id"))) continue;
            if ("heat_network".equals(f.objectType()) && Math.abs(((Number) f.prop("flow_tph")).doubleValue() - 40.0) < 1e-6) trunk = true;
            if ("heat_chamber".equals(f.objectType()) && "junction".equals(f.prop("chamber_kind"))) junctions++;
        }
        assertTrue(trunk, "a shared section carries the sum of both flows (40 t/h)");
        assertEquals(1, junctions, "exactly one junction chamber");
        assertEquals(1, v.network().roots().size(), "one tie-in for both consumers");
    }
}
