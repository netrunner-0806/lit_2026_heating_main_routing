package ru.lct.heatnet.oracle;

import ru.lct.heatnet.oracle.ReferenceOutputOracle.Line;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Node;
import ru.lct.heatnet.oracle.ReferenceOutputOracle.Variant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent recomputation of flows and diameters (annex 2.3, clarifications 1–2): flow of a line = sum of the
 * consumer flows below it; DU = minimal table DU by capacity that also satisfies the maximum continuous length on
 * every consumer path; DU never decreases towards the attachment point; DU is constant where the flow is constant.
 */
final class ReferenceHydraulicOracle {

    private ReferenceHydraulicOracle() {}

    static List<String> check(Variant v) {
        List<String> out = new ArrayList<>();
        Map<String, Double> flowOf = new HashMap<>();
        for (Node n : v.nodes.values()) if (n.kind == ReferenceOutputOracle.Kind.OKS) flowOf.put(n.key, n.flowTph);
        // 1. flow aggregation
        for (Line l : v.lines) {
            double q = 0;
            for (String leaf : l.leaves) q += flowOf.getOrDefault(leaf, 0.0);
            if (l.parentSide == null) { out.add(l.id + ": not oriented, flow not checked"); continue; }
            if (Math.abs(q - l.flow) > 1e-3) out.add(String.format("%s: flow_tph %.3f, sum of consumer flows below the line %.3f", l.id, l.flow, q));
            if (l.leaves.isEmpty()) out.add(l.id + ": no consumer below the line (dangling section)");
        }
        // 2. capacity and official DU
        for (Line l : v.lines) {
            ReferenceRules.Du d = ReferenceRules.byDu(l.du);
            if (d == null) { out.add(l.id + ": diameter " + l.du + " is not in table 1"); continue; }
            if (d.capacityTph < l.flow - 1e-9) out.add(String.format("%s: DU%d capacity %.1f < flow %.3f", l.id, l.du, d.capacityTph, l.flow));
            ReferenceRules.Du min = ReferenceRules.minByFlow(l.flow);
            if (min != null && l.du < min.du) out.add(l.id + ": DU" + l.du + " below the minimum by flow DU" + min.du);
        }
        // 3. monotonic towards the attachment point
        for (Line l : v.lines) {
            Node p = l.parentSide;
            if (p == null || p.root) continue;
            Line up = p.toRoot;
            if (up != null && up.du < l.du) out.add(l.id + ": DU" + l.du + " decreases to DU" + up.du + " on " + up.id + " towards the attachment point");
        }
        // 4. maximum continuous length on every consumer path (runs of equal DU; nodes without a DU change do not reset)
        for (Node oks : v.oks()) {
            List<Line> path = v.pathToRoot(oks);
            double run = 0;
            int du = -1;
            for (Line l : path) {
                if (l.du != du) { du = l.du; run = 0; }
                run += l.length;
                ReferenceRules.Du d = ReferenceRules.byDu(du);
                if (d != null && run > d.maxLengthM + 1e-6)
                    out.add(String.format("path of %s: continuous DU%d run %.3f m exceeds the limit %.0f m at %s", oks.id, du, run, d.maxLengthM, l.id));
            }
        }
        // 5. constant DU where the flow is constant, and minimality of the chosen DU per constant-flow section
        List<Set<Line>> sections = sections(v);
        for (Set<Line> s : sections) {
            Line first = s.iterator().next();
            for (Line l : s) {
                if (l.du != first.du) out.add(l.id + ": DU" + l.du + " differs from DU" + first.du + " (" + first.id + ") on a section of constant flow");
                if (Math.abs(l.flow - first.flow) > 1e-6) out.add(l.id + ": flow differs inside a section without branching");
            }
            ReferenceRules.Du min = ReferenceRules.minByFlow(first.flow);
            if (min == null || first.du <= min.du) continue;
            ReferenceRules.Du smaller = ReferenceRules.nextSmaller(first.du);
            if (smaller == null || smaller.du < min.du) continue;
            if (feasibleWith(v, s, smaller.du)) {
                out.add(first.id + ": DU" + first.du + " is not minimal — DU" + smaller.du + " (capacity " + smaller.capacityTph + " t/h ≥ flow "
                        + first.flow + ") satisfies the maximum length and the monotonic rule on every path");
            }
        }
        return out;
    }

    /** Lines joined through nodes with exactly two lines (technical nodes and pass-through chambers) carry the same flow. */
    static List<Set<Line>> sections(Variant v) {
        Map<Line, Set<Line>> of = new HashMap<>();
        for (Line l : v.lines) { Set<Line> s = new LinkedHashSet<>(); s.add(l); of.put(l, s); }
        for (Node n : v.nodes.values()) {
            if (n.lines.size() != 2 || n.root) continue;
            Line a = n.lines.get(0), b = n.lines.get(1);
            Set<Line> sa = of.get(a), sb = of.get(b);
            if (sa == sb) continue;
            sa.addAll(sb);
            for (Line l : sb) of.put(l, sa);
        }
        List<Set<Line>> out = new ArrayList<>();
        for (Set<Line> s : of.values()) if (!out.contains(s)) out.add(s);
        return out;
    }

    /** Would the network stay valid (monotonic DU, max continuous length) if the section used {@code du}? */
    private static boolean feasibleWith(Variant v, Set<Line> section, int du) {
        Map<Line, Integer> duOf = new HashMap<>();
        for (Line l : v.lines) duOf.put(l, section.contains(l) ? du : l.du);
        for (Line l : v.lines) {
            Node p = l.parentSide;
            if (p == null || p.root) continue;
            Line up = p.toRoot;
            if (up != null && duOf.get(up) < duOf.get(l)) return false;
        }
        for (Node oks : v.oks()) {
            double run = 0;
            int cur = -1;
            for (Line l : v.pathToRoot(oks)) {
                int d = duOf.get(l);
                if (d != cur) { cur = d; run = 0; }
                run += l.length;
                ReferenceRules.Du row = ReferenceRules.byDu(cur);
                if (row != null && run > row.maxLengthM + 1e-6) return false;
            }
        }
        return true;
    }
}
