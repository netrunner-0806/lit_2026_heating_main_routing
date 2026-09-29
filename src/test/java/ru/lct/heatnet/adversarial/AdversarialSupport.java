package ru.lct.heatnet.adversarial;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationIssue;
import ru.lct.heatnet.validation.ValidationReport;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Shared helpers of the adversarial suites: fixture loading, solving, rendering, validation and hand-built output features. */
final class AdversarialSupport {
    private AdversarialSupport() {}

    static final double OX = AdversarialFixtureGenerator.OX, OY = AdversarialFixtureGenerator.OY;

    /** Result of one end-to-end run. */
    static final class Run {
        final InputModel input;
        final SolveResult result;
        final VariantResult best;
        final List<OutputFeature> features;
        final ValidationReport report;

        Run(InputModel input, SolveResult result, VariantResult best, List<OutputFeature> features, ValidationReport report) {
            this.input = input; this.result = result; this.best = best; this.features = features; this.report = report;
        }

        List<OutputFeature> lines() { return features.stream().filter(f -> "heat_network".equals(f.objectType()) && "v1".equals(f.prop("variant_id"))).collect(Collectors.toList()); }
        List<OutputFeature> chambers() { return features.stream().filter(f -> "heat_chamber".equals(f.objectType()) && "v1".equals(f.prop("variant_id"))).collect(Collectors.toList()); }
        OutputFeature summary() { return features.stream().filter(f -> "variant_summary".equals(f.objectType()) && "v1".equals(f.prop("variant_id"))).findFirst().orElse(null); }
        boolean hasIssue(String code) { return report.getIssues().stream().anyMatch(i -> i.getCode().equals(code)); }
        List<String> errorCodes() { return report.getIssues().stream().filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR).map(ValidationIssue::getCode).collect(Collectors.toList()); }
        String issues() { return report.getIssues().toString(); }
        /** Metric coordinates (relative to the scene origin) of all vertices of the best variant's lines. */
        List<Coordinate> metricVertices() {
            List<Coordinate> out = new ArrayList<>();
            for (OutputFeature f : lines()) for (double[] ll : f.line()) out.add(metric(ll));
            return out;
        }
        /** True if any special line of v1 crosses an object whose label contains the text. */
        boolean crosses(String text) {
            return lines().stream().anyMatch(f -> String.valueOf(f.prop("crossed_restrictions")).contains(text));
        }
        double newLength() { return ((Number) summary().prop("new_network_length")).doubleValue(); }
        double score() { return ((Number) summary().prop("score")).doubleValue(); }
    }

    static InputModel load(String name) throws Exception {
        try (InputStream in = AdversarialSupport.class.getResourceAsStream("/adversarial/" + name + ".geojson")) {
            assertNotNull(in, "fixture " + name);
            return new GeoJsonStreamReader(ParseOptions.defaults()).read(in);
        }
    }

    static SolveResult solve(InputModel in) {
        SolverConfig cfg = SolverConfig.defaults();
        return new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
    }

    static Run run(String name) throws Exception {
        InputModel in = load(name);
        SolveResult r = solve(in);
        if (r.variants().isEmpty()) throw new AssertionError("no variants for " + name + ": " + r.diagnostics().messages());
        VariantResult best = r.variants().get(0);
        List<OutputFeature> fs = new ArrayList<>(new OutputBuilder().build(best));
        ValidationReport rep = new ResultValidator(in).validate(fs);
        return new Run(in, r, best, fs, rep);
    }

    static Coordinate metric(double[] lonLat) {
        Coordinate c = CrsTransformer.get().toMetric(lonLat[0], lonLat[1]);
        return new Coordinate(c.x - OX, c.y - OY);
    }

    static double[] ll(double x, double y) { return CrsTransformer.get().toWgs84(OX + x, OY + y); }

    // ---- hand-built output features (same pattern as ResultValidatorRulesTest) ----

    static OutputFeature line(String id, Object start, Object end, double flow, int du, boolean special, double kSpec, double... xy) {
        List<double[]> cs = new ArrayList<>();
        double len = 0;
        for (int i = 0; i < xy.length / 2; i++) {
            cs.add(ll(xy[2 * i], xy[2 * i + 1]));
            if (i > 0) len += Math.hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1]);
        }
        double lenR = Math.round(len * 1000.0) / 1000.0;
        return OutputFeature.line(cs).prop("id", id).prop("object_type", "heat_network").prop("variant_id", "v1")
                .prop("start_node_id", start).prop("end_node_id", end).prop("flow_tph", flow).prop("diameter", du)
                .prop("length", lenR).prop("laying_method", special ? "special" : "base")
                .prop("depth_start", null).prop("depth_end", null)
                .prop("cost", Math.round(lenR * DiameterTable.requireDu(du).costPerMeter() * kSpec));
    }

    static OutputFeature chamber(String id, double x, double y, int du, long cost) {
        return OutputFeature.point(ll(x, y)).prop("id", id).prop("object_type", "heat_chamber").prop("variant_id", "v1").prop("diameter", du).prop("cost", cost);
    }

    static OutputFeature tn(String id, double x, double y) {
        return OutputFeature.point(ll(x, y)).prop("id", id).prop("object_type", "technical_node").prop("variant_id", "v1");
    }

    static OutputFeature summary(List<OutputFeature> fs) {
        double lines = 0, chambers = 0, len = 0;
        for (OutputFeature f : fs) {
            if ("heat_network".equals(f.objectType())) { lines += ((Number) f.prop("cost")).doubleValue(); len += ((Number) f.prop("length")).doubleValue(); }
            if ("heat_chamber".equals(f.objectType())) chambers += ((Number) f.prop("cost")).doubleValue();
        }
        double calc = lines + chambers;
        return OutputFeature.noGeometry().prop("id", "v1_summary").prop("object_type", "variant_summary").prop("variant_id", "v1")
                .prop("rank", 1).prop("construction_cost", calc).prop("chamber_construction_cost", chambers)
                .prop("existing_chamber_tie_in_count", 0).prop("existing_chamber_tie_in_cost", 0).prop("unconnected_penalty", 0)
                .prop("calculated_cost", calc).prop("new_network_length", len)
                .prop("score", 0.7 * calc / 25_000_000 + 0.3 * len / 100).prop("unconnected_oks_ids", new ArrayList<>());
    }

    static ValidationReport validate(InputModel in, List<OutputFeature> fs) {
        List<OutputFeature> all = new ArrayList<>(fs);
        all.add(summary(fs));
        return new ResultValidator(in).validate(all);
    }

    static boolean has(ValidationReport rep, String code) { return rep.getIssues().stream().anyMatch(i -> i.getCode().equals(code)); }
}
