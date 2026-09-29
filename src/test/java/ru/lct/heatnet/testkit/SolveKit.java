package ru.lct.heatnet.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.validation.OutputFeatureReader;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-kit: parse -> solve -> render -> independent validation, plus semantic signatures and canonical output
 * strings used by the metamorphic, property-based and determinism suites (test code only).
 */
public final class SolveKit {
    private SolveKit() {}

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static InputModel parse(byte[] geojson) throws IOException {
        return new GeoJsonStreamReader(ParseOptions.defaults()).read(new ByteArrayInputStream(geojson));
    }

    public static InputModel parse(JsonNode doc) throws IOException {
        return parse(MAPPER.writeValueAsBytes(doc));
    }

    public static SolveResult solve(InputModel m, boolean depth) {
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = depth;
        return new HeatnetSolver(cfg, new SolverVariantValidator(m)).solve(m, HeatnetSolver.defaultStrategies(), null);
    }

    public static List<OutputFeature> render(SolveResult r) {
        List<OutputFeature> out = new ArrayList<>();
        OutputBuilder b = new OutputBuilder();
        for (VariantResult v : r.variants()) out.addAll(b.build(v));
        return out;
    }

    /** Serialises the features to GeoJSON and reads them back (what an external consumer sees). */
    public static List<OutputFeature> roundTrip(List<OutputFeature> fs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new GeoJsonResultWriter().write(bos, fs);
        return new OutputFeatureReader().read(new ByteArrayInputStream(bos.toByteArray()));
    }

    public static Outcome run(InputModel m, boolean depth) {
        SolveResult r = solve(m, depth);
        List<OutputFeature> fs = render(r);
        ValidationReport rep = new ResultValidator(m).validate(fs);
        return new Outcome(m, r, fs, rep, depth);
    }

    public static final class Outcome {
        public final InputModel input;
        public final SolveResult result;
        public final List<OutputFeature> features;
        public final ValidationReport report;
        public final boolean depth;

        Outcome(InputModel input, SolveResult result, List<OutputFeature> features, ValidationReport report, boolean depth) {
            this.input = input; this.result = result; this.features = features; this.report = report; this.depth = depth;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // semantic signature of a variant (what must survive meaning-preserving input transformations)

    public static final class VariantSig {
        public String variantId, strategy;
        public int rank;
        public TreeSet<String> connected = new TreeSet<>(), unconnected = new TreeSet<>();
        public TreeMap<String, Integer> duByConsumer = new TreeMap<>();
        public int newChambers, tieIns, specialPieces;
        public double score, cost, length;
        public boolean valid;

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s(rank %d, %s, score %.4f, cost %.0f, len %.3f, connected %s, unconnected %s, du %s, chambers %d, tieIns %d, special %d, valid %s)",
                    variantId, rank, strategy, score, cost, length, connected, unconnected, duByConsumer, newChambers, tieIns, specialPieces, valid);
        }
    }

    public static List<VariantSig> semantic(Outcome o) {
        Map<String, VariantSig> sigs = new LinkedHashMap<>();
        for (OutputFeature f : o.features) {
            if (!"variant_summary".equals(f.objectType())) continue;
            VariantSig s = new VariantSig();
            s.variantId = (String) f.prop("variant_id");
            s.strategy = (String) f.prop("strategy");
            s.rank = ((Number) f.prop("rank")).intValue();
            s.score = ((Number) f.prop("score")).doubleValue();
            s.cost = ((Number) f.prop("calculated_cost")).doubleValue();
            s.length = ((Number) f.prop("new_network_length")).doubleValue();
            s.tieIns = ((Number) f.prop("existing_chamber_tie_in_count")).intValue();
            for (Object id : (List<?>) f.prop("unconnected_oks_ids")) s.unconnected.add(String.valueOf(id));
            for (ConnectionPoint cp : o.input.connectionPoints()) if (!s.unconnected.contains(cp.id().text())) s.connected.add(cp.id().text());
            s.valid = o.report.errorsOf(s.variantId).isEmpty();
            sigs.put(s.variantId, s);
        }
        for (OutputFeature f : o.features) {
            VariantSig s = sigs.get((String) f.prop("variant_id"));
            if (s == null) continue;
            if ("heat_chamber".equals(f.objectType())) s.newChambers++;
            if ("heat_network".equals(f.objectType())) {
                if ("special".equals(f.prop("laying_method"))) s.specialPieces++;
                String a = String.valueOf(f.prop("start_node_id")), b = String.valueOf(f.prop("end_node_id"));
                int du = ((Number) f.prop("diameter")).intValue();
                if (s.connected.contains(a)) s.duByConsumer.put(a, du);
                if (s.connected.contains(b)) s.duByConsumer.put(b, du);
            }
        }
        List<VariantSig> out = new ArrayList<>(sigs.values());
        out.sort((x, y) -> Integer.compare(x.rank, y.rank));
        return out;
    }

    /** Asserts that two semantic signatures describe the same solution (numbers within a relative tolerance). */
    public static void assertEquivalent(String label, List<VariantSig> expected, List<VariantSig> actual, double relTol) {
        assertEquals(expected.size(), actual.size(), label + ": number of variants\n  expected " + expected + "\n  actual   " + actual);
        for (int i = 0; i < expected.size(); i++) {
            VariantSig e = expected.get(i), a = actual.get(i);
            String ctx = label + " / " + e.variantId + "\n  expected " + e + "\n  actual   " + a;
            assertEquals(e.rank, a.rank, ctx);
            assertTrue(e.valid && a.valid, ctx + " (validator)");
            assertEquals(e.connected, a.connected, ctx + " (connected set)");
            assertEquals(e.unconnected, a.unconnected, ctx + " (unconnected set)");
            assertEquals(e.duByConsumer, a.duByConsumer, ctx + " (DU per consumer)");
            assertEquals(e.newChambers, a.newChambers, ctx + " (new chambers)");
            assertEquals(e.tieIns, a.tieIns, ctx + " (tie-ins)");
            assertClose(e.score, a.score, relTol, ctx + " (score)");
            assertClose(e.cost, a.cost, relTol, ctx + " (cost)");
            assertClose(e.length, a.length, relTol, ctx + " (length)");
        }
    }

    public static void assertClose(double e, double a, double relTol, String ctx) {
        double tol = Math.max(Math.abs(e), Math.abs(a)) * relTol + 1e-9;
        assertTrue(Math.abs(e - a) <= tol, ctx + ": " + e + " vs " + a + " (tol " + tol + ")");
    }

    // ------------------------------------------------------------------------------------------------------------
    // canonical output string (features sorted, coordinates rounded, generated ids optionally normalised)

    private static final Pattern GENERATED_ID = Pattern.compile("^v\\d+_(net|chamber|tn|summary)_?\\d*$");

    public static String canonical(List<OutputFeature> fs, boolean normaliseIds) {
        Map<String, String> nodeCoord = new java.util.HashMap<>();
        if (normaliseIds) {
            for (OutputFeature f : fs) {
                if (f.point() != null && f.prop("id") != null) nodeCoord.put(String.valueOf(f.prop("id")), coord(f.point()));
            }
        }
        List<String> rows = new ArrayList<>();
        for (OutputFeature f : fs) {
            StringBuilder sb = new StringBuilder();
            TreeMap<String, Object> props = new TreeMap<>(f.properties());
            for (Map.Entry<String, Object> e : props.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (normaliseIds) {
                    if (k.equals("id") && v != null && GENERATED_ID.matcher(String.valueOf(v)).matches()) v = "<generated>";
                    if ((k.equals("start_node_id") || k.equals("end_node_id")) && v != null && GENERATED_ID.matcher(String.valueOf(v)).matches())
                        v = "@" + nodeCoord.getOrDefault(String.valueOf(v), "?");
                    if (k.equals("link_id")) v = "<generated>";
                }
                sb.append(k).append('=').append(fmt(v)).append(';');
            }
            sb.append(" geom=");
            if (f.point() != null) sb.append(coord(f.point()));
            else if (f.line() != null) for (double[] c : f.line()) sb.append(coord(c)).append(' ');
            rows.add(sb.toString());
        }
        Collections.sort(rows);
        return String.join("\n", rows);
    }

    private static String coord(double[] c) {
        return String.format(Locale.ROOT, "%.6f,%.6f", c[0], c[1]);
    }

    private static String fmt(Object v) {
        if (v instanceof Double || v instanceof Float) return String.format(Locale.ROOT, "%.6f", ((Number) v).doubleValue());
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : (List<?>) v) sb.append(fmt(o)).append(',');
            return sb.append(']').toString();
        }
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<Object, Object> e : new TreeMap<Object, Object>((Map<?, ?>) v).entrySet()) sb.append(e.getKey()).append(':').append(fmt(e.getValue())).append(',');
            return sb.append('}').toString();
        }
        return String.valueOf(v);
    }
}
