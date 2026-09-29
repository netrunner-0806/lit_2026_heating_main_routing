package ru.lct.heatnet.boundary;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.ChamberCostTable;
import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.ValidationIssue;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-built output features for boundary tests of the independent validator (planar and depth mode). Coordinates
 * are metric offsets from {@link SyntheticInput#OX}/{@link SyntheticInput#OY}, converted to WGS84 like the real
 * output. Costs follow section 6 of the annex: L * c(DU) * Kdepth * Kspec, whole rubles.
 */
final class BoundaryFixtures {
    private BoundaryFixtures() {}

    static double[] ll(double x, double y) {
        Coordinate c = SyntheticInput.c(x, y);
        return CrsTransformer.get().toWgs84(c.x, c.y);
    }

    static double length(double... xy) {
        double len = 0;
        for (int i = 1; i < xy.length / 2; i++) len += Math.hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1]);
        return len;
    }

    static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Planar heat_network feature (depth_start/depth_end = null, Kdepth = 1). */
    static OutputFeature line(String id, Object start, Object end, double flow, int du, boolean special, double kSpec, double... xy) {
        return build(id, start, end, flow, du, special, kSpec, null, null, xy);
    }

    /** Depth-mode heat_network feature with the given end depths (cost uses the mean Kdepth of the ends). */
    static OutputFeature depthLine(String id, Object start, Object end, double flow, int du, boolean special, double kSpec, double d0, double d1, double... xy) {
        return build(id, start, end, flow, du, special, kSpec, d0, d1, xy);
    }

    private static OutputFeature build(String id, Object start, Object end, double flow, int du, boolean special, double kSpec, Double d0, Double d1, double... xy) {
        List<double[]> cs = new ArrayList<>();
        for (int i = 0; i < xy.length / 2; i++) cs.add(ll(xy[2 * i], xy[2 * i + 1]));
        double lenR = round3(length(xy));
        double kDepth = d0 == null ? 1.0 : DepthRules.kDepthRamp(d0, d1);
        OutputFeature f = OutputFeature.line(cs).prop("id", id).prop("object_type", "heat_network").prop("variant_id", "v1")
                .prop("start_node_id", start).prop("end_node_id", end).prop("flow_tph", flow).prop("diameter", du)
                .prop("length", lenR).prop("laying_method", special ? "special" : "base")
                .prop("depth_start", d0).prop("depth_end", d1)
                .prop("cost", Math.round(lenR * DiameterTable.requireDu(du).costPerMeter() * kSpec * kDepth));
        if (d0 != null) f.prop("k_depth", kDepth).prop("slope", lenR > 0 ? Math.abs(d1 - d0) / lenR : 0.0);
        return f;
    }

    /** New chamber; cost and diameter by the largest adjacent DU (including the existing line for a root chamber). */
    static OutputFeature chamber(String id, double x, double y, int maxDu) {
        return OutputFeature.point(ll(x, y)).prop("id", id).prop("object_type", "heat_chamber").prop("variant_id", "v1")
                .prop("diameter", maxDu).prop("cost", Math.round(ChamberCostTable.newChamberCost(maxDu)));
    }

    static OutputFeature tn(String id, double x, double y) {
        return OutputFeature.point(ll(x, y)).prop("id", id).prop("object_type", "technical_node").prop("variant_id", "v1");
    }

    /** Planar variant_summary consistent with the features (tieIns = new lines ending in existing chambers). */
    static OutputFeature summary(List<OutputFeature> fs, int tieIns) {
        double lines = 0, chambers = 0, len = 0;
        for (OutputFeature f : fs) {
            if ("heat_network".equals(f.objectType())) { lines += ((Number) f.prop("cost")).doubleValue(); len += ((Number) f.prop("length")).doubleValue(); }
            if ("heat_chamber".equals(f.objectType())) chambers += ((Number) f.prop("cost")).doubleValue();
        }
        double tieInCost = tieIns * ChamberCostTable.EXISTING_CHAMBER_TIE_IN_COST;
        double calc = lines + chambers + tieInCost;
        return OutputFeature.noGeometry().prop("id", "v1_summary").prop("object_type", "variant_summary").prop("variant_id", "v1")
                .prop("rank", 1).prop("construction_cost", calc).prop("chamber_construction_cost", chambers)
                .prop("existing_chamber_tie_in_count", tieIns).prop("existing_chamber_tie_in_cost", tieInCost).prop("unconnected_penalty", 0)
                .prop("calculated_cost", calc).prop("new_network_length", len)
                .prop("score", 0.7 * calc / 25_000_000 + 0.3 * len / 100).prop("unconnected_oks_ids", new ArrayList<>())
                .prop("mode", "planar");
    }

    /** Depth-mode summary: planar figures plus the depth block recomputed from the features. */
    static OutputFeature depthSummary(List<OutputFeature> fs, int tieIns, int vertical, int above, int below, int passUnder) {
        OutputFeature s = summary(fs, tieIns);
        double max = 0, min = Double.MAX_VALUE, weighted = 0, total = 0, deep = 0, shallow = 0, extra = 0;
        int transitions = 0;
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType())) continue;
            double d0 = ((Number) f.prop("depth_start")).doubleValue(), d1 = ((Number) f.prop("depth_end")).doubleValue();
            double len = ((Number) f.prop("length")).doubleValue();
            double mid = (d0 + d1) / 2;
            weighted += mid * len; total += len;
            max = Math.max(max, Math.max(d0, d1)); min = Math.min(min, Math.min(d0, d1));
            if (mid > 3.0 + 1e-6) deep += len; else if (mid < 3.0 - 1e-6) shallow += len;
            if (Math.abs(d1 - d0) > 1e-3) transitions++;
            int du = ((Number) f.prop("diameter")).intValue();
            double kSpec = "special".equals(f.prop("laying_method")) ? kSpecOf(f) : 1.0;
            double base = Math.round(len * DiameterTable.requireDu(du).costPerMeter() * kSpec);
            extra += ((Number) f.prop("cost")).doubleValue() - base;
        }
        return s.prop("mode", "depth").prop("max_depth", round3(max)).prop("min_depth", round3(min == Double.MAX_VALUE ? 0 : min))
                .prop("average_depth", total > 0 ? weighted / total : 0).prop("deep_network_length", deep).prop("shallow_network_length", shallow)
                .prop("depth_transition_count", transitions).prop("vertical_crossing_count", vertical).prop("above_crossing_count", above)
                .prop("below_crossing_count", below).prop("pass_under_count", passUnder).prop("depth_extra_cost", extra);
    }

    private static double kSpecOf(OutputFeature f) {
        Object k = f.prop("k_spec");
        return k instanceof Number ? ((Number) k).doubleValue() : 1.0;
    }

    static OutputFeature withKSpec(OutputFeature f, double k) { return f.prop("k_spec", k); }

    static ValidationReport validate(InputModel in, List<OutputFeature> fs) { return new ResultValidator(in).validate(fs); }

    static boolean has(ValidationReport rep, String code) {
        for (ValidationIssue i : rep.getIssues()) if (i.getCode().equals(code)) return true;
        return false;
    }

    static String issues(ValidationReport rep) { return rep.getIssues().toString(); }
}
