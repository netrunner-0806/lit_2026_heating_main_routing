package ru.lct.heatnet.property;

import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.testkit.InputGeoJson;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Output invariants that every produced variant must satisfy, whatever the input (phase 8). Checked on the
 * GeoJSON round-tripped features, i.e. on what an external consumer would read.
 */
public final class Invariants {
    private Invariants() {}

    public static final Path FAILURE_DIR = Paths.get("target", "property-failures");

    /** Runs the solver on the scene in the given mode and checks every invariant; throws AssertionError with the seed. */
    public static SolveResult check(RandomScene.Scene scene, boolean depth) {
        try {
            SolveResult r = SolveKit.solve(scene.input, depth);
            List<OutputFeature> fs = SolveKit.roundTrip(SolveKit.render(r));
            checkAll(scene.input, r, fs, depth);
            return r;
        } catch (AssertionError | RuntimeException | java.io.IOException e) {
            Path dump = FAILURE_DIR.resolve("seed-" + scene.seed + (depth ? "-depth" : "-planar") + ".geojson");
            try { InputGeoJson.write(scene.input, dump); } catch (java.io.IOException ignored) { }
            throw new AssertionError("random case FAILED: seed=" + scene.seed + " depth=" + depth + " (" + scene.description + ")\n  input saved to " + dump
                    + "\n  replay: mvn -o test -Dtest=RandomCaseReplayTest -Dheatnet.replay.seed=" + scene.seed + "\n  cause: " + e, e);
        }
    }

    public static void checkAll(InputModel input, SolveResult r, List<OutputFeature> fs, boolean depth) {
        // the solver must not swallow crashes: a failed strategy is a bug, not a "no route"
        for (Diagnostics.Message m : r.diagnostics().messages()) {
            if ("STRATEGY_FAILED".equals(m.getCode())) throw new AssertionError("strategy crashed: " + m.getText());
        }
        if (r.variants().isEmpty()) {
            // acceptable only when nothing could be connected at all: then there must be no output either
            if (!fs.isEmpty()) throw new AssertionError("no variants but " + fs.size() + " output features");
            return;
        }
        ValidationReport rep = new ResultValidator(input).validate(fs);
        if (rep.getErrorCount() > 0) throw new AssertionError("independent validator errors: " + rep.getIssues());

        Set<String> consumerIds = new HashSet<>();
        for (ConnectionPoint cp : input.connectionPoints()) consumerIds.add(cp.id().text());
        Set<String> chamberIds = new HashSet<>();
        for (ExistingChamber c : input.chambers()) chamberIds.add(c.id().text());
        Map<String, Double> flowById = new HashMap<>();
        for (ConnectionPoint cp : input.connectionPoints()) flowById.put(cp.id().text(), cp.flowTph());

        Set<String> allIds = new HashSet<>();
        Map<String, List<OutputFeature>> byVariant = new LinkedHashMap<>();
        for (OutputFeature f : fs) {
            noNaN(f);
            String vid = String.valueOf(f.prop("variant_id"));
            byVariant.computeIfAbsent(vid, k -> new ArrayList<>()).add(f);
            String id = String.valueOf(f.prop("id"));
            if (!allIds.add(vid + "/" + id)) throw new AssertionError("duplicate generated id " + id + " in " + vid);
            if (f.line() != null) {
                if (f.line().size() < 2) throw new AssertionError("LineString with < 2 coordinates: " + id);
                double len = ((Number) f.prop("length")).doubleValue();
                if (!(len > 0)) throw new AssertionError("non-positive length on " + id + ": " + len);
                double cost = ((Number) f.prop("cost")).doubleValue();
                if (!(cost >= 0)) throw new AssertionError("negative cost on " + id);
                double flow = ((Number) f.prop("flow_tph")).doubleValue();
                if (!(flow > 0)) throw new AssertionError("non-positive flow on " + id);
                int du = ((Number) f.prop("diameter")).intValue();
                if (!DiameterTable.byDu(du).isPresent()) throw new AssertionError("diameter " + du + " is not in Table 1 (" + id + ")");
                Object ds = f.prop("depth_start"), de = f.prop("depth_end");
                if (depth && (ds == null || de == null)) throw new AssertionError("DEPTH mode: depth_start/depth_end must be numeric on " + id);
                if (!depth && (ds != null || de != null)) throw new AssertionError("PLANAR mode: depth_start/depth_end must be null on " + id);
            }
        }
        int ranks = 0;
        double prevScore = -1;
        List<VariantResult> vrs = r.variants();
        for (Map.Entry<String, List<OutputFeature>> e : byVariant.entrySet()) {
            String vid = e.getKey();
            List<OutputFeature> vfs = e.getValue();
            OutputFeature summary = null;
            Set<String> nodeIds = new HashSet<>();
            int newChambers = 0, tieInChambers = 0;
            for (OutputFeature f : vfs) {
                if ("variant_summary".equals(f.objectType())) { if (summary != null) throw new AssertionError("two summaries in " + vid); summary = f; }
                if ("heat_chamber".equals(f.objectType())) { nodeIds.add(String.valueOf(f.prop("id"))); newChambers++; if ("tie_in_on_existing_network".equals(f.prop("chamber_kind"))) tieInChambers++; }
                if ("technical_node".equals(f.objectType())) nodeIds.add(String.valueOf(f.prop("id")));
            }
            if (summary == null) throw new AssertionError("variant " + vid + " has no variant_summary");
            int rank = ((Number) summary.prop("rank")).intValue();
            if (rank != ++ranks) throw new AssertionError("ranks not contiguous: got " + rank + " expected " + ranks);
            double score = ((Number) summary.prop("score")).doubleValue();
            if (score < prevScore - 1e-9) throw new AssertionError("scores not ordered by rank in " + vid);
            prevScore = score;
            // references, forest structure, connected consumers
            Map<String, String> parent = new HashMap<>(); // union-find
            Map<String, Integer> degree = new HashMap<>();
            double lineCost = 0, lineLen = 0;
            Set<String> consumersOnTree = new HashSet<>(), rootsOnTree = new HashSet<>();
            for (OutputFeature f : vfs) {
                if (!"heat_network".equals(f.objectType())) continue;
                String a = String.valueOf(f.prop("start_node_id")), b = String.valueOf(f.prop("end_node_id"));
                for (String n : new String[]{a, b}) {
                    boolean known = nodeIds.contains(n) || consumerIds.contains(n) || chamberIds.contains(n);
                    if (!known) throw new AssertionError("line " + f.prop("id") + " references unknown node " + n);
                    degree.merge(n, 1, Integer::sum);
                    if (consumerIds.contains(n)) consumersOnTree.add(n);
                    if (chamberIds.contains(n)) rootsOnTree.add(n);
                }
                if (find(parent, a).equals(find(parent, b))) throw new AssertionError("cycle detected at line " + f.prop("id") + " in " + vid);
                parent.put(find(parent, a), find(parent, b));
                lineCost += ((Number) f.prop("cost")).doubleValue();
                lineLen += ((Number) f.prop("length")).doubleValue();
            }
            for (OutputFeature f : vfs) if ("heat_chamber".equals(f.objectType()) && "tie_in_on_existing_network".equals(f.prop("chamber_kind"))) rootsOnTree.add(String.valueOf(f.prop("id")));
            // every root component must contain a root (existing chamber or tie-in chamber)
            Set<String> rootComponents = new HashSet<>();
            for (String root : rootsOnTree) rootComponents.add(find(parent, root));
            List<?> unconnected = (List<?>) summary.prop("unconnected_oks_ids");
            Set<String> unconnectedIds = new HashSet<>();
            for (Object o : unconnected) unconnectedIds.add(String.valueOf(o));
            for (String c : consumerIds) {
                boolean claimedConnected = !unconnectedIds.contains(c);
                if (claimedConnected) {
                    if (!consumersOnTree.contains(c)) throw new AssertionError("consumer " + c + " claimed connected but has no line in " + vid);
                    if (degree.get(c) != 1) throw new AssertionError("consumer " + c + " must have exactly one line, has " + degree.get(c));
                    if (!rootComponents.contains(find(parent, c))) throw new AssertionError("consumer " + c + " is not attached to the existing network in " + vid);
                } else if (consumersOnTree.contains(c)) throw new AssertionError("consumer " + c + " listed as unconnected but has a line in " + vid);
            }
            int connectedCount = ((Number) summary.prop("connected_oks_count")).intValue();
            if (connectedCount != consumerIds.size() - unconnectedIds.size()) throw new AssertionError("connected_oks_count mismatch in " + vid);
            // cost arithmetic (all money values are whole rubles, lengths 3 decimals)
            double networkCost = ((Number) summary.prop("network_construction_cost")).doubleValue();
            double chamberCost = ((Number) summary.prop("chamber_construction_cost")).doubleValue();
            double tieInCost = ((Number) summary.prop("existing_chamber_tie_in_cost")).doubleValue();
            int tieInCount = ((Number) summary.prop("existing_chamber_tie_in_count")).intValue();
            double penalty = ((Number) summary.prop("unconnected_penalty")).doubleValue();
            double construction = ((Number) summary.prop("construction_cost")).doubleValue();
            double calculated = ((Number) summary.prop("calculated_cost")).doubleValue();
            double length = ((Number) summary.prop("new_network_length")).doubleValue();
            close("network_construction_cost", networkCost, lineCost, 0.5);
            close("new_network_length", length, lineLen, 1e-3 * Math.max(1, vfs.size()));
            close("construction_cost", construction, networkCost + chamberCost + tieInCost, 0.5);
            close("calculated_cost", calculated, construction + penalty, 0.5);
            close("existing_chamber_tie_in_cost", tieInCost, tieInCount * 5_000_000.0, 0.5);
            double expectedPenalty = 0;
            for (String u : unconnectedIds) expectedPenalty += Constants.UNCONNECTED_PENALTY_FIXED + Constants.UNCONNECTED_PENALTY_PER_TPH * flowById.getOrDefault(u, 0.0);
            close("unconnected_penalty", penalty, expectedPenalty, 0.5);
            double expectedScore = Constants.SCORE_COST_WEIGHT * calculated / Constants.SCORE_COST_BASE + Constants.SCORE_LENGTH_WEIGHT * length / Constants.SCORE_LENGTH_BASE;
            close("score", score, expectedScore, 6e-5);
            if (newChambers > 0 && chamberCost <= 0) throw new AssertionError("new chambers without chamber cost in " + vid);
            if (newChambers == 0 && chamberCost != 0) throw new AssertionError("chamber cost without chambers in " + vid);
            // mode consistency (BUG-2, see OutputModeFieldTest: an all-unconnected DEPTH run currently reports "planar";
            // the check is skipped for variants without lines until the fix is applied)
            String mode = String.valueOf(summary.prop("mode"));
            if (!mode.equals(depth ? "depth" : "planar")) throw new AssertionError("summary mode " + mode + " in " + (depth ? "depth" : "planar") + " run");
        }
        if (byVariant.size() != vrs.size()) throw new AssertionError("variants in output " + byVariant.size() + " != solver variants " + vrs.size());
    }

    private static String find(Map<String, String> parent, String x) {
        String p = parent.get(x);
        if (p == null || p.equals(x)) { parent.putIfAbsent(x, x); return x; }
        String root = find(parent, p);
        parent.put(x, root);
        return root;
    }

    private static void close(String what, double a, double b, double tol) {
        if (Math.abs(a - b) > tol) throw new AssertionError(String.format(Locale.ROOT, "%s: %.6f vs recomputed %.6f (tol %.6f)", what, a, b, tol));
    }

    private static void noNaN(OutputFeature f) {
        for (Map.Entry<String, Object> e : f.properties().entrySet()) noNaN(e.getKey(), e.getValue());
        if (f.point() != null) for (double v : f.point()) if (!Double.isFinite(v)) throw new AssertionError("non-finite coordinate in " + f.prop("id"));
        if (f.line() != null) for (double[] c : f.line()) for (double v : c) if (!Double.isFinite(v)) throw new AssertionError("non-finite coordinate in " + f.prop("id"));
    }

    private static void noNaN(String key, Object v) {
        if (v instanceof Double || v instanceof Float) { if (!Double.isFinite(((Number) v).doubleValue())) throw new AssertionError("non-finite value of " + key); }
        else if (v instanceof List) for (Object o : (List<?>) v) noNaN(key, o);
        else if (v instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) noNaN(key + "." + e.getKey(), e.getValue());
    }
}
