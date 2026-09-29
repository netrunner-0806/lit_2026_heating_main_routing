package ru.lct.heatnet.oracle;

import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Kind;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Line;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Node;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Variant;
import ru.lct.heatnet.output.OutputFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent recomputation of every money figure of the annex (sections 3.2 and 6) from the output features:
 * segment cost = round(L · c(DU) · Kгл · Kспец), chamber cost by the largest adjacent DU, tie-in cost, penalty,
 * construction_cost, calculated_cost, new_network_length and score.
 */
final class ReferenceCostOracle {

    private ReferenceCostOracle() {}

    /** Kspec expected from the crossed objects (largest coefficient; NaN when no crossed type is known). */
    static double expectedKSpec(Line l) {
        double k = Double.NaN;
        for (String label : l.crossed) {
            double kk = ReferenceRules.kSpec(ReferenceOutputOracle.typeOf(label));
            if (Double.isNaN(kk)) continue;
            k = Double.isNaN(k) ? kk : Math.max(k, kk);
        }
        return k;
    }

    static double expectedKDepth(Line l) {
        if (l.depthStart == null || l.depthEnd == null) return 1.0;
        return ReferenceRules.kDepthRamp(l.depthStart, l.depthEnd);
    }

    static long expectedLineCost(Line l) {
        ReferenceRules.Du d = ReferenceRules.byDu(l.du);
        double k = l.special ? expectedKSpec(l) : 1.0;
        if (Double.isNaN(k)) k = l.kSpec; // unknown (custom) type: cannot be derived independently
        return Math.round(l.length * d.costPerMetre * expectedKDepth(l) * k);
    }

    static List<String> check(Variant v, InputModel in) {
        List<String> out = new ArrayList<>();
        // ---- lines ----
        double lengthSum = 0;
        long lineCost = 0;
        for (Line l : v.lines) {
            double geo = ReferenceOutputOracle.metricLength(l.metric);
            if (Math.abs(geo - l.length) > 2e-3) out.add(String.format("%s: length %.3f but the geometry measures %.3f m in EPSG:32637", l.id, l.length, geo));
            if (ReferenceRules.byDu(l.du) == null) { out.add(l.id + ": unknown DU " + l.du); continue; }
            if (!l.special && !l.crossed.isEmpty()) out.add(l.id + ": base section crosses " + l.crossed);
            if (l.special && l.crossed.isEmpty()) out.add(l.id + ": special section without crossed objects");
            double ks = l.special ? expectedKSpec(l) : 1.0;
            if (!Double.isNaN(ks) && Math.abs(ks - l.kSpec) > 1e-9) out.add(String.format("%s: k_spec %.4f, expected max Kспец of %s = %.4f", l.id, l.kSpec, l.crossed, ks));
            double kd = expectedKDepth(l);
            if (Math.abs(kd - l.kDepth) > 6e-5) out.add(String.format("%s: k_depth %.5f, expected %.5f from depths %s..%s", l.id, l.kDepth, kd, l.depthStart, l.depthEnd));
            long c = expectedLineCost(l);
            if (Math.abs(c - l.cost) > 1) out.add(String.format("%s: cost %d, expected round(%.3f * %.0f * %.4f * %.2f) = %d", l.id, l.cost, l.length,
                    ReferenceRules.byDu(l.du).costPerMetre, kd, Double.isNaN(ks) ? l.kSpec : ks, c));
            lengthSum += l.length;
            lineCost += l.cost;
        }
        // ---- chambers ----
        long chamberCost = 0;
        for (Node ch : v.chambers) {
            // annex 3.2: the largest DU of ALL adjacent heat-network sections, the existing line included for a tie-in chamber
            int maxDu = 0;
            for (Line l : ch.lines) maxDu = Math.max(maxDu, l.du);
            int existingAdj = 0;
            if ("tie_in_on_existing_network".equals(ch.chamberKind)) {
                ExistingNetworkLine ex = ch.existingLineId == null ? null : ReferenceOutputOracle.existingLine(in, String.valueOf(ch.existingLineId));
                if (ex == null) out.add("chamber " + ch.id + ": existing_line_id " + ch.existingLineId + " not found in the input");
                else {
                    maxDu = Math.max(maxDu, ex.diameter());
                    if (ex.line().distance(ru.lct.heatnet.geo.CrsTransformer.METRIC_FACTORY.createPoint(ch.metric)) > 0.05)
                        out.add("chamber " + ch.id + " is not on the existing line " + ch.existingLineId);
                }
                existingAdj = ReferenceOutputOracle.existingAdjacency(ch.metric, in);
            } else if (!"junction".equals(ch.chamberKind)) out.add("chamber " + ch.id + ": unknown chamber_kind " + ch.chamberKind);
            if (ch.lines.isEmpty()) out.add("chamber " + ch.id + " has no adjacent new section");
            if (ch.diameter == null || ch.diameter != maxDu) out.add("chamber " + ch.id + ": diameter " + ch.diameter + ", largest adjacent DU " + maxDu);
            long expected = ReferenceRules.chamberCost(maxDu);
            if (ch.cost == null || ch.cost != expected) out.add("chamber " + ch.id + ": cost " + ch.cost + ", expected " + expected + " for DU" + maxDu);
            if (ch.lines.size() + existingAdj > ReferenceRules.MAX_CHAMBER_ADJACENCY)
                out.add("chamber " + ch.id + ": " + ch.lines.size() + " new + " + existingAdj + " existing adjacent sections (> 4)");
            if (ch.cost != null) chamberCost += ch.cost;
        }
        for (Node n : v.nodes.values()) {
            if (n.kind != Kind.EXISTING_CHAMBER) continue;
            int adj = ReferenceOutputOracle.existingAdjacency(n.metric, in) + n.lines.size();
            if (adj > ReferenceRules.MAX_CHAMBER_ADJACENCY) out.add("existing chamber " + n.id + ": " + adj + " adjacent sections after the tie-in (> 4)");
        }
        // ---- tie-ins into existing chambers ----
        int tieIns = 0;
        for (Node n : v.nodes.values()) if (n.kind == Kind.EXISTING_CHAMBER) tieIns += n.lines.size();
        long tieInCost = tieIns * ReferenceRules.EXISTING_CHAMBER_TIE_IN_COST;
        // ---- penalty from the input flows of the unconnected consumers ----
        OutputFeature s = v.summary();
        if (s == null) { out.add("variant " + v.vid + " has no variant_summary"); return out; }
        double penalty = 0;
        Set<String> connected = new HashSet<>();
        for (Node n : v.oks()) connected.add(n.key);
        List<?> unconnected = s.prop("unconnected_oks_ids") instanceof List ? (List<?>) s.prop("unconnected_oks_ids") : null;
        if (unconnected == null) out.add("unconnected_oks_ids is not an array");
        else {
            for (Object id : unconnected) {
                ConnectionPoint cp = ReferenceOutputOracle.connectionPoint(in, ReferenceOutputOracle.key(id));
                if (cp == null) { out.add("unconnected id " + id + " (" + id.getClass().getSimpleName() + ") is not an input consumer with the same id type"); continue; }
                if (connected.contains(ReferenceOutputOracle.key(id))) out.add("consumer " + id + " is listed as unconnected but has a new section");
                penalty += ReferenceRules.penalty(cp.flowTph());
            }
            if (connected.size() + unconnected.size() != in.connectionPoints().size())
                out.add("connected " + connected.size() + " + unconnected " + unconnected.size() + " != " + in.connectionPoints().size() + " consumers in the input");
        }
        long construction = lineCost + chamberCost + tieInCost;
        double calculated = construction + penalty;
        cmpLong(out, "network_construction_cost", s, lineCost);
        cmpLong(out, "chamber_construction_cost", s, chamberCost);
        cmpLong(out, "existing_chamber_tie_in_count", s, tieIns);
        cmpLong(out, "existing_chamber_tie_in_cost", s, tieInCost);
        cmpLong(out, "unconnected_penalty", s, Math.round(penalty));
        cmpLong(out, "construction_cost", s, construction);
        cmpLong(out, "calculated_cost", s, Math.round(calculated));
        double len = ReferenceOutputOracle.num(s.prop("new_network_length"));
        if (Math.abs(len - lengthSum) > 1e-3 * Math.max(1, v.lines.size())) out.add(String.format("new_network_length %.3f, sum of section lengths %.3f", len, lengthSum));
        double score = ReferenceRules.score(calculated, lengthSum);
        double reported = ReferenceOutputOracle.num(s.prop("score"));
        if (Math.abs(score - reported) > 1e-4) out.add(String.format("score %.4f, expected 0.7*%.0f/25e6 + 0.3*%.3f/100 = %.4f", reported, calculated, lengthSum, score));
        cmpLong(out, "connected_oks_count", s, connected.size());
        cmpLong(out, "attachment_point_count", s, v.roots.size());
        // every consumer with a new section reaches an attachment point
        for (Node n : v.oks()) {
            List<Line> path = v.pathToRoot(n);
            Node end = n;
            for (Line l : path) end = l.other(end);
            if (!end.root) out.add("consumer " + n.id + " does not reach an attachment point");
        }
        return out;
    }

    private static void cmpLong(List<String> out, String field, OutputFeature s, long expected) {
        Object o = s.prop(field);
        if (!(o instanceof Number)) { out.add(field + " missing or not numeric: " + o); return; }
        long got = Math.round(((Number) o).doubleValue());
        if (got != expected) out.add(field + " " + got + ", expected " + expected);
    }
}
