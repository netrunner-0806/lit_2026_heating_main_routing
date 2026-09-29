package ru.lct.heatnet.validation;

import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.validation.VariantModel.Chain;
import ru.lct.heatnet.validation.VariantModel.Crossing;
import ru.lct.heatnet.validation.VariantModel.Line;
import ru.lct.heatnet.validation.VariantModel.Node;
import ru.lct.heatnet.validation.VariantModel.NodeKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Depth-mode checks of an output variant, run after all planar checks (section 5 of the annex): depths present
 * and >= 0.7 m, slope <= 0.10 per feature (horizontal length), continuity at every node, road/tram cover, vertical
 * clearances above/below utilities on constant-depth crossing plateaus, correct split at 3.0 m, independent Kdepth
 * cost recalculation and consistency of the summary figures.
 */
final class DepthValidator {

    /** Tolerance when comparing two reported depths: continuity, constant plateaus, transitions. */
    private static final double DEPTH_TOL = ru.lct.heatnet.geo.GeometryTolerance.DEPTH_TOL_M;
    /** Tolerance for numeric limits of the annex (0.7 m minimum, cover, vertical clearance): floating noise only, so a
     *  reported 0.699 m or a 0.199 m clearance is a violation. */
    private static final double RULE_TOL = ru.lct.heatnet.geo.GeometryTolerance.RULE_TOL;
    /** Depths are reported with 4 decimals and lengths with 3: the slope of a piece recomputed from the reported values
     *  may exceed the true slope by (2 * 0.00005 + 0.10 * 0.0005) / length; this rounding term is added to the limit. */
    private static final double SLOPE_ROUNDING_M = ru.lct.heatnet.geo.GeometryTolerance.DEPTH_REPORT_RESOLUTION_M + 0.10 * ru.lct.heatnet.geo.GeometryTolerance.LENGTH_REPORT_RESOLUTION_M;

    private final VariantModel vm;
    private final ValidationReport rep;
    private final String vid;

    DepthValidator(VariantModel vm, ValidationReport rep) {
        this.vm = vm;
        this.rep = rep;
        this.vid = vm.variantId;
    }

    void validate() {
        double maxDepth = 0, minDepth = Double.MAX_VALUE, weighted = 0, total = 0, deep = 0, shallow = 0, extra = 0;
        int transitions = 0, vertical = 0, above = 0, below = 0;
        java.util.Set<Crossing> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); // one count per crossing section
        for (Line l : vm.lines) {
            rep.counted("depth:attributes");
            if (l.depthStart == null || l.depthEnd == null) { rep.error("DEPTH_REQUIRED", "depth_start/depth_end must be numbers in the depth mode", vid, l.id); continue; }
            double d0 = l.depthStart, d1 = l.depthEnd;
            if (d0 < DepthRules.MIN_DEPTH_M - RULE_TOL || d1 < DepthRules.MIN_DEPTH_M - RULE_TOL)
                rep.error("DEPTH_MIN", String.format(java.util.Locale.ROOT, "depth %.3f/%.3f m is shallower than %.1f m", d0, d1, DepthRules.MIN_DEPTH_M), vid, l.id);
            if (d0 > DepthRules.SANITY_MAX_DEPTH_M || d1 > DepthRules.SANITY_MAX_DEPTH_M) rep.error("DEPTH_SANITY", "depth beyond the numerical sanity guard", vid, l.id);
            rep.counted("depth:slope");
            double slope = l.declaredLength > 0 ? Math.abs(d1 - d0) / l.declaredLength : 0;
            if (slope > DepthRules.MAX_SLOPE + RULE_TOL + (l.declaredLength > 0 ? SLOPE_ROUNDING_M / l.declaredLength : 0))
                rep.error("DEPTH_SLOPE", String.format(java.util.Locale.ROOT, "slope %.4f > %.2f (%.3f -> %.3f m over %.3f m)", slope, DepthRules.MAX_SLOPE, d0, d1, l.declaredLength), vid, l.id);
            rep.counted("depth:split3m");
            if ((d0 < DepthRules.NORMAL_DEPTH_M - DEPTH_TOL && d1 > DepthRules.NORMAL_DEPTH_M + DEPTH_TOL) || (d0 > DepthRules.NORMAL_DEPTH_M + DEPTH_TOL && d1 < DepthRules.NORMAL_DEPTH_M - DEPTH_TOL))
                rep.error("DEPTH_3M_SPLIT", String.format(java.util.Locale.ROOT, "feature crosses the normal depth inside (%.3f -> %.3f): it must be split at 3.0 m by a technical_node", d0, d1), vid, l.id);
            // crossings: constant plateau + vertical rules
            if (!l.crossings.isEmpty()) {
                rep.counted("depth:crossings");
                if (Math.abs(d1 - d0) > DEPTH_TOL) rep.error("CONSTANT_CROSSING_DEPTH", String.format(java.util.Locale.ROOT, "crossing section must have a constant depth (%.3f -> %.3f)", d0, d1), vid, l.id);
                double hNew = DiameterTable.byDu(l.du).map(s -> s.heightM()).orElse(0.0);
                for (Crossing c : l.crossings) {
                    DepthRules.VerticalRule rule = DepthRules.forType(c.type).orElse(null);
                    if (rule == null) continue;
                    boolean firstPiece = counted.add(c); // a section covered by several pieces is one crossing
                    if (!rule.isUtility()) {
                        String code = ru.lct.heatnet.config.RestrictionRules.TRAM_TRACKS.equals(c.type) ? "TRAM_DEPTH" : "ROAD_DEPTH";
                        if (d0 < rule.minDepthM() - RULE_TOL) rep.error(code, String.format(java.util.Locale.ROOT, "%s crossed with cover %.3f m < %.1f m", c.label, d0, rule.minDepthM()), vid, l.id);
                        continue;
                    }
                    if (firstPiece) vertical++;
                    double height = Double.isNaN(rule.utilityHeightM()) ? c.existingHeightM : rule.utilityHeightM();
                    double utilTop = rule.utilityTopM(), utilBottom = utilTop + height;
                    double newTop = d0, newBottom = d0 + hNew;
                    double clearAbove = utilTop - newBottom, clearBelow = newTop - utilBottom;
                    String code = ru.lct.heatnet.config.RestrictionRules.GAS_PIPELINE.equals(c.type) ? "GAS_VERTICAL_CLEARANCE"
                            : ru.lct.heatnet.config.RestrictionRules.POWER_CABLE.equals(c.type) ? "POWER_VERTICAL_CLEARANCE" : "HEAT_NETWORK_VERTICAL_CLEARANCE";
                    if (clearAbove >= rule.clearanceM() - RULE_TOL) { if (firstPiece) above++; }
                    else if (clearBelow >= rule.clearanceM() - RULE_TOL) { if (firstPiece) below++; }
                    else rep.error(code, String.format(java.util.Locale.ROOT, "%s: new envelope %.3f..%.3f m vs utility %.3f..%.3f m, clearance above %.3f / below %.3f < %.2f m",
                            c.label, newTop, newBottom, utilTop, utilBottom, clearAbove, clearBelow, rule.clearanceM()), vid, l.id);
                }
            }
            double mid = (d0 + d1) / 2;
            weighted += mid * l.declaredLength;
            total += l.declaredLength;
            maxDepth = Math.max(maxDepth, Math.max(d0, d1));
            minDepth = Math.min(minDepth, Math.min(d0, d1));
            if (mid > DepthRules.NORMAL_DEPTH_M + 1e-6) deep += l.declaredLength; else if (mid < DepthRules.NORMAL_DEPTH_M - 1e-6) shallow += l.declaredLength;
            if (Math.abs(d1 - d0) > DEPTH_TOL) transitions++;
            double base = Math.round(l.declaredLength * DiameterTable.byDu(l.du).map(s -> s.costPerMeter()).orElse(0.0) * (l.special ? l.kSpecExpected : 1.0));
            extra += l.declaredCost - base;
        }
        // continuity at nodes: every line meeting at a node has the same depth at that end
        for (Node n : vm.nodes.values()) {
            if (n.lines.size() < 2) continue;
            rep.counted("depth:continuity");
            Double ref = null;
            for (Line l : n.lines) {
                Double d = l.start == n ? l.depthStart : l.depthEnd;
                if (d == null) continue;
                if (ref == null) ref = d;
                else if (Math.abs(ref - d) > DEPTH_TOL)
                    rep.error("DEPTH_CONTINUITY", String.format(java.util.Locale.ROOT, "depth jump at node %s: %.3f vs %.3f m", n.id, ref, d), vid, l.id);
            }
        }
        // one physical section = one profile: through technical nodes the depth must continue (already covered by
        // continuity); through chambers the trunk and each branch share the node depth (covered as well)
        rep.counted("depth:profile");
        // summary
        if (vm.summary != null) {
            rep.counted("depth:summary");
            check("max_depth", maxDepth, 0.002);
            check("average_depth", total > 0 ? weighted / total : 0, 0.01);
            check("deep_network_length", deep, 0.05);
            check("shallow_network_length", shallow, 0.05);
            check("depth_extra_cost", extra, 2.0);
            Object vc = vm.summary.prop("vertical_crossing_count");
            if (!(vc instanceof Number) || ((Number) vc).intValue() != vertical)
                rep.error("DEPTH_SUMMARY", "vertical_crossing_count = " + vc + " but " + vertical + " utility crossings found", vid, String.valueOf(vm.summary.prop("id")));
            Object ac = vm.summary.prop("above_crossing_count"), bc = vm.summary.prop("below_crossing_count");
            if (!(ac instanceof Number) || ((Number) ac).intValue() != above || !(bc instanceof Number) || ((Number) bc).intValue() != below)
                rep.error("DEPTH_SUMMARY", "above/below counts " + ac + "/" + bc + " but recomputed " + above + "/" + below, vid, String.valueOf(vm.summary.prop("id")));
            if (transitions == 0 && (vm.summary.prop("depth_transition_count") instanceof Number) && ((Number) vm.summary.prop("depth_transition_count")).intValue() != 0)
                rep.error("DEPTH_SUMMARY", "depth_transition_count > 0 but no sloped feature exists", vid, String.valueOf(vm.summary.prop("id")));
        }
    }

    private void check(String name, double expected, double tol) {
        Object v = vm.summary.prop(name);
        if (!(v instanceof Number)) { rep.error("DEPTH_SUMMARY", name + " missing in the depth summary", vid, String.valueOf(vm.summary.prop("id"))); return; }
        double d = ((Number) v).doubleValue();
        if (Math.abs(d - expected) > tol) rep.error("DEPTH_SUMMARY", String.format(java.util.Locale.ROOT, "%s = %.3f but recomputed %.3f", name, d, expected), vid, String.valueOf(vm.summary.prop("id")));
    }
}
