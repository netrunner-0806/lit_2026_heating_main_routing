package ru.lct.heatnet.oracle;

import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Line;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Node;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Variant;
import ru.lct.heatnet.output.OutputFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent depth checks (annex section 5 and the vertical column of table 2) on the output attributes:
 * depth range, slope, constant depth on special passages, continuity at nodes, the 3.0 m split, vertical
 * clearances of the reported crossings, and the depth figures of the variant summary.
 */
final class ReferenceDepthOracle {

    private ReferenceDepthOracle() {}

    static List<String> check(Variant v, InputModel in) {
        List<String> out = new ArrayList<>();
        OutputFeature s = v.summary();
        boolean depth = s != null && "depth".equals(s.prop("mode"));
        if (!depth) {
            for (Line l : v.lines) {
                if (l.depthStart != null || l.depthEnd != null) out.add(l.id + ": depth_start/depth_end must be null in the 2D mode");
                if (Math.abs(l.kDepth - 1.0) > 1e-12) out.add(l.id + ": k_depth must be 1 in the 2D mode");
                if (l.slope != null) out.add(l.id + ": slope must be null in the 2D mode");
            }
            return out;
        }
        for (Line l : v.lines) {
            if (l.depthStart == null || l.depthEnd == null) { out.add(l.id + ": numeric depth_start/depth_end required in depth mode"); continue; }
            double a = l.depthStart, b = l.depthEnd;
            if (a < ReferenceRules.MIN_DEPTH_M - 1e-9 || b < ReferenceRules.MIN_DEPTH_M - 1e-9) out.add(String.format("%s: depth %.3f..%.3f below the minimum 0.7 m", l.id, a, b));
            double slope = l.length > 0 ? Math.abs(a - b) / l.length : 0;
            if (slope > ReferenceRules.MAX_SLOPE * (1 + 1e-3) + 1e-9) out.add(String.format("%s: slope %.5f > 0.10 (depth %.3f -> %.3f over %.3f m)", l.id, slope, a, b, l.length));
            if (l.slope == null || Math.abs(l.slope - slope) > 1.5e-4) out.add(String.format("%s: reported slope %s, geometry gives %.5f", l.id, l.slope, slope));
            if (l.special && Math.abs(a - b) > 1e-9) out.add(String.format("%s: special passage must keep a constant depth (%.3f -> %.3f)", l.id, a, b));
            if (Math.min(a, b) < ReferenceRules.NORMAL_DEPTH_M - 1e-9 && Math.max(a, b) > ReferenceRules.NORMAL_DEPTH_M + 1e-9)
                out.add(String.format("%s: section crosses the 3.0 m depth (%.3f -> %.3f) without a technical_node split", l.id, a, b));
        }
        // continuity at every node
        for (Node n : v.nodes.values()) {
            Double ref = null;
            for (Line l : n.lines) {
                Double d = l.depthAt(n);
                if (d == null) continue;
                if (ref == null) ref = d;
                else if (Math.abs(ref - d) > 1e-6) out.add(String.format("node %s: depth %.3f on %s vs %.3f on %s", n.id, ref, n.lines.get(0).id, d, l.id));
            }
        }
        // vertical crossings
        int vertical = 0, above = 0, below = 0, under = 0;
        Map<Object, List<Line>> byLink = new LinkedHashMap<>();
        for (Line l : v.lines) byLink.computeIfAbsent(l.linkId, k -> new ArrayList<>()).add(l);
        for (List<Line> pieces : byLink.values()) {
            pieces.sort(Comparator.comparingInt(x -> x.order));
            Set<String> prevObjects = new HashSet<>();
            for (Line l : pieces) {
                Set<String> objects = new HashSet<>();
                for (Map<String, Object> c : l.crossings) {
                    String type = String.valueOf(c.get("type")), object = String.valueOf(c.get("object")), method = String.valueOf(c.get("method"));
                    objects.add(object);
                    checkCrossing(out, in, l, type, object, method, c);
                    if (prevObjects.contains(object)) continue; // same plateau continues into the next piece
                    if (ReferenceRules.isUtility(type)) { vertical++; if ("ABOVE".equals(method)) above++; else if ("BELOW".equals(method)) below++; else out.add(l.id + ": utility " + object + " with method " + method); }
                    else under++;
                }
                // every crossed object with a vertical rule must be explained by a crossing entry
                for (String label : l.crossed) {
                    String type = ReferenceOutputOracle.typeOf(label);
                    boolean hasRule = ReferenceRules.isUtility(type) || !Double.isNaN(ReferenceRules.minDepthUnder(type));
                    if (hasRule && !objects.contains(label)) out.add(l.id + ": crosses " + label + " but reports no vertical crossing for it");
                }
                prevObjects = objects;
            }
        }
        // summary figures
        double max = 0, min = Double.MAX_VALUE, weighted = 0, total = 0, deep = 0, shallow = 0;
        long extra = 0;
        int transitions = 0;
        for (List<Line> pieces : byLink.values()) {
            int prevSign = 0;
            for (Line l : pieces) {
                if (l.depthStart == null || l.depthEnd == null) continue;
                double a = l.depthStart, b = l.depthEnd, mid = (a + b) / 2;
                max = Math.max(max, Math.max(a, b));
                min = Math.min(min, Math.min(a, b));
                weighted += mid * l.length;
                total += l.length;
                if (mid > ReferenceRules.NORMAL_DEPTH_M + 1e-6) deep += l.length;
                else if (mid < ReferenceRules.NORMAL_DEPTH_M - 1e-6) shallow += l.length;
                double k = l.special ? ReferenceCostOracle.expectedKSpec(l) : 1.0;
                if (Double.isNaN(k)) k = l.kSpec;
                extra += l.cost - Math.round(l.length * ReferenceRules.byDu(l.du).costPerMetre * k);
                int sign = Math.abs(b - a) < 1e-9 ? 0 : (b > a ? 1 : -1);
                if (sign != 0 && sign != prevSign) transitions++;
                prevSign = sign;
            }
        }
        if (min == Double.MAX_VALUE) min = 0;
        cmp(out, s, "max_depth", max, 1e-3);
        cmp(out, s, "min_depth", min, 1e-3);
        cmp(out, s, "average_depth", total > 0 ? weighted / total : 0, 2e-3);
        cmp(out, s, "deep_network_length", deep, 1e-2);
        cmp(out, s, "shallow_network_length", shallow, 1e-2);
        cmp(out, s, "depth_transition_count", transitions, 0);
        cmp(out, s, "vertical_crossing_count", vertical, 0);
        cmp(out, s, "above_crossing_count", above, 0);
        cmp(out, s, "below_crossing_count", below, 0);
        cmp(out, s, "pass_under_count", under, 0);
        cmp(out, s, "depth_extra_cost", extra, 1.0 * Math.max(1, v.lines.size()));
        return out;
    }

    private static void checkCrossing(List<String> out, InputModel in, Line l, String type, String object, String method, Map<String, Object> c) {
        double top = ReferenceOutputOracle.num(c.get("new_top"));
        double bottom = ReferenceOutputOracle.num(c.get("new_bottom"));
        double hNew = ReferenceRules.byDu(l.du).heightM;
        if (Math.abs(top - l.depthStart) > 1e-6) out.add(l.id + ": crossing " + object + " new_top " + top + " differs from depth_start " + l.depthStart);
        if (Math.abs(bottom - (top + hNew)) > 1.5e-3) out.add(l.id + ": crossing " + object + " new_bottom " + bottom + ", expected top + height(DU" + l.du + ") = " + (top + hNew));
        if (Math.abs(ReferenceOutputOracle.num(c.get("new_height")) - hNew) > 1e-3) out.add(l.id + ": new_height " + c.get("new_height") + " differs from table 1 height " + hNew);
        if (ReferenceRules.isUtility(type)) {
            int existingDu = 0;
            if ("heat_network".equals(type)) {
                ExistingNetworkLine ex = ReferenceOutputOracle.existingLine(in, ReferenceOutputOracle.idOf(object));
                if (ex == null) { out.add(l.id + ": crossed existing line " + object + " not found in the input"); return; }
                existingDu = ex.diameter();
            }
            double uTop = ReferenceRules.utilityTop(type, existingDu);
            double uBottom = uTop + ReferenceRules.utilityHeight(type, existingDu);
            double clr = ReferenceRules.verticalClearance(type);
            if (Math.abs(ReferenceOutputOracle.num(c.get("utility_top")) - uTop) > 1e-6) out.add(l.id + ": " + object + " utility_top " + c.get("utility_top") + ", annex " + uTop);
            if (Math.abs(ReferenceOutputOracle.num(c.get("utility_bottom")) - uBottom) > 1.5e-3) out.add(l.id + ": " + object + " utility_bottom " + c.get("utility_bottom") + ", annex " + uBottom);
            if (Math.abs(ReferenceOutputOracle.num(c.get("required_clearance")) - clr) > 1e-9) out.add(l.id + ": " + object + " required_clearance " + c.get("required_clearance") + ", annex " + clr);
            if ("ABOVE".equals(method)) {
                if (top + hNew > uTop - clr + 1e-6) out.add(String.format("%s: ABOVE %s: new bottom %.3f + clearance %.1f > utility top %.3f", l.id, object, top + hNew, clr, uTop));
            } else if ("BELOW".equals(method)) {
                if (top < uBottom + clr - 1e-6) out.add(String.format("%s: BELOW %s: new top %.3f < utility bottom %.3f + clearance %.1f", l.id, object, top, uBottom, clr));
            }
        } else {
            double need = ReferenceRules.minDepthUnder(type);
            if (Double.isNaN(need)) { out.add(l.id + ": crossing entry for a type without a vertical rule: " + type); return; }
            if (!"UNDER".equals(method)) out.add(l.id + ": " + object + " must be passed UNDER, method " + method);
            if (Math.abs(ReferenceOutputOracle.num(c.get("required_min_depth")) - need) > 1e-9) out.add(l.id + ": " + object + " required_min_depth " + c.get("required_min_depth") + ", annex " + need);
            if (top < need - 1e-6) out.add(String.format("%s: under %s: top depth %.3f < %.1f", l.id, object, top, need));
        }
    }

    private static void cmp(List<String> out, OutputFeature s, String field, double expected, double tol) {
        Object o = s.prop(field);
        if (!(o instanceof Number)) { out.add(field + " missing: " + o); return; }
        double got = ((Number) o).doubleValue();
        if (Math.abs(got - expected) > tol) out.add(String.format("%s %s, expected %.4f", field, o, expected));
    }
}
