package ru.lct.heatnet.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import ru.lct.heatnet.output.OutputFeature;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Structural contract of the output GeoJSON (annex section 7) on every run of the conformance matrix. */
class OutputIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> REQUIRED_ALL = Arrays.asList("id", "object_type", "variant_id");
    private static final List<String> REQUIRED_LINE = Arrays.asList("start_node_id", "end_node_id", "flow_tph", "diameter", "length", "laying_method", "cost");
    private static final List<String> REQUIRED_CHAMBER = Arrays.asList("diameter", "cost");
    private static final List<String> REQUIRED_SUMMARY = Arrays.asList("rank", "construction_cost", "chamber_construction_cost", "existing_chamber_tie_in_count",
            "existing_chamber_tie_in_cost", "unconnected_penalty", "calculated_cost", "new_network_length", "score", "unconnected_oks_ids");

    @TestFactory
    List<DynamicTest> outputContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (OracleRuns.Run run : OracleRuns.all()) {
            tests.add(DynamicTest.dynamicTest(run + " features", () -> {
                List<String> p = checkFeatures(run.features, run.input, run.depth);
                assertTrue(p.isEmpty(), run + ": " + p.size() + " problems:\n  " + String.join("\n  ", p));
            }));
            tests.add(DynamicTest.dynamicTest(run + " raw JSON round trip", () -> {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                new GeoJsonResultWriter().write(bos, run.features);
                JsonNode root = MAPPER.readTree(bos.toByteArray());
                List<String> p = checkRawJson(root, run.input, run.depth);
                assertTrue(p.isEmpty(), run + ": " + p.size() + " raw JSON problems:\n  " + String.join("\n  ", p));
            }));
        }
        return tests;
    }

    // ------------------------------------------------------------------------------------------------------------

    static List<String> checkFeatures(List<OutputFeature> fs, InputModel in, boolean depthMode) {
        List<String> out = new ArrayList<>();
        Map<String, Object> inputIds = new HashMap<>();   // type-preserving key -> java value
        for (ConnectionPoint cp : in.connectionPoints()) inputIds.put(ReferenceOutputOracle.key(cp.id().toJavaValue()), cp.id().toJavaValue());
        for (ExistingChamber c : in.chambers()) inputIds.put(ReferenceOutputOracle.key(c.id().toJavaValue()), c.id().toJavaValue());
        Map<String, Integer> summariesPerVariant = new LinkedHashMap<>();
        Map<String, Integer> rankOf = new LinkedHashMap<>();
        Set<String> allIds = new HashSet<>();
        Map<String, Map<String, double[]>> nodeWgs = new HashMap<>(); // variant -> node key -> wgs
        Map<String, Set<String>> variantsOfId = new HashMap<>();
        CrsTransformer crs = CrsTransformer.get();
        for (OutputFeature f : fs) {
            String id = String.valueOf(f.prop("id"));
            String type = f.objectType();
            String vid = String.valueOf(f.prop("variant_id"));
            for (String k : REQUIRED_ALL) if (f.prop(k) == null) out.add(id + " (" + type + "): missing " + k);
            if (!(f.prop("variant_id") instanceof String) && !(f.prop("variant_id") instanceof Number)) out.add(id + ": variant_id must be string or number");
            walk(id, "properties", f.properties(), out);
            if ("variant_summary".equals(type)) {
                summariesPerVariant.merge(vid, 1, Integer::sum);
                for (String k : REQUIRED_SUMMARY) if (f.prop(k) == null) out.add(id + ": missing " + k);
                if (f.geomType() != OutputFeature.GeomType.NONE) out.add(id + ": variant_summary must have null geometry");
                if (f.prop("rank") instanceof Number) rankOf.put(vid, ((Number) f.prop("rank")).intValue());
                boolean depthOut = "depth".equals(f.prop("mode"));
                if (depthOut != depthMode) out.add(id + ": mode " + f.prop("mode") + " in a " + (depthMode ? "DEPTH" : "PLANAR") + " run");
                if (f.prop("unconnected_oks_ids") instanceof List) {
                    for (Object u : (List<?>) f.prop("unconnected_oks_ids")) {
                        Object v = inputIds.get(ReferenceOutputOracle.key(u));
                        if (v == null) out.add(id + ": unconnected id " + u + " (" + (u == null ? "null" : u.getClass().getSimpleName()) + ") does not match an input consumer id with the same JSON type");
                    }
                }
            } else {
                String key = ReferenceOutputOracle.key(f.prop("id"));
                if (!allIds.add(key)) out.add(id + ": duplicate generated id");
                variantsOfId.computeIfAbsent(key, k -> new HashSet<>()).add(vid);
                if (id.startsWith("v") && !id.startsWith(vid + "_")) out.add(id + ": generated id does not carry its variant prefix " + vid);
            }
            if ("heat_network".equals(type)) {
                for (String k : REQUIRED_LINE) if (f.prop(k) == null) out.add(id + ": missing " + k);
                if (f.geomType() != OutputFeature.GeomType.LINESTRING || f.line() == null) out.add(id + ": heat_network must be a LineString");
                else {
                    if (f.line().size() < 2) out.add(id + ": LineString with " + f.line().size() + " coordinates");
                    double len = 0;
                    Coordinate prev = null;
                    for (double[] c : f.line()) {
                        if (c.length != 2 || !Double.isFinite(c[0]) || !Double.isFinite(c[1])) out.add(id + ": bad coordinate " + Arrays.toString(c));
                        else if (Math.abs(c[0]) > 180 || Math.abs(c[1]) > 90) out.add(id + ": coordinate outside WGS84 range " + Arrays.toString(c));
                        Coordinate m = crs.toMetric(c[0], c[1]);
                        if (prev != null) len += prev.distance(m);
                        prev = m;
                    }
                    if (len < 1e-6) out.add(id + ": zero-length heat_network");
                }
                if (f.prop("length") instanceof Number && ((Number) f.prop("length")).doubleValue() <= 0) out.add(id + ": length must be > 0");
                if (f.prop("cost") instanceof Number && ((Number) f.prop("cost")).doubleValue() < 0) out.add(id + ": cost must be >= 0");
                if (f.prop("flow_tph") instanceof Number && ((Number) f.prop("flow_tph")).doubleValue() <= 0) out.add(id + ": flow_tph must be > 0");
                if (f.prop("diameter") instanceof Number && !ReferenceRules.isOfficialDu(((Number) f.prop("diameter")).intValue())) out.add(id + ": diameter " + f.prop("diameter") + " not in table 1");
                if (!"base".equals(f.prop("laying_method")) && !"special".equals(f.prop("laying_method"))) out.add(id + ": laying_method " + f.prop("laying_method"));
                if (depthMode) {
                    if (!(f.prop("depth_start") instanceof Number) || !(f.prop("depth_end") instanceof Number)) out.add(id + ": depth_start/depth_end must be numeric in DEPTH mode");
                } else {
                    if (f.prop("depth_start") != null || f.prop("depth_end") != null) out.add(id + ": depth_start/depth_end must be null in PLANAR mode");
                }
                for (String ref : new String[]{"start_node_id", "end_node_id"}) {
                    Object r = f.prop(ref);
                    if (!(r instanceof String) && !(r instanceof Number)) out.add(id + ": " + ref + " must be string or number: " + r);
                }
            } else if ("heat_chamber".equals(type) || "technical_node".equals(type)) {
                if (f.geomType() != OutputFeature.GeomType.POINT || f.point() == null) out.add(id + ": " + type + " must be a Point");
                else nodeWgs.computeIfAbsent(vid, k -> new HashMap<>()).put(ReferenceOutputOracle.key(f.prop("id")), f.point());
                if ("heat_chamber".equals(type)) {
                    for (String k : REQUIRED_CHAMBER) if (f.prop(k) == null) out.add(id + ": missing " + k);
                    if (f.prop("cost") instanceof Number && ((Number) f.prop("cost")).doubleValue() < 0) out.add(id + ": cost must be >= 0");
                }
            } else if (!"variant_summary".equals(type)) out.add(id + ": unknown object_type " + type);
        }
        // node references: exist (input or same variant), geometry coincides
        Map<String, double[]> inputWgs = new HashMap<>();
        for (ConnectionPoint cp : in.connectionPoints()) inputWgs.put(ReferenceOutputOracle.key(cp.id().toJavaValue()), cp.wgs84());
        for (ExistingChamber c : in.chambers()) inputWgs.put(ReferenceOutputOracle.key(c.id().toJavaValue()), c.wgs84());
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType()) || f.line() == null || f.line().size() < 2) continue;
            String vid = String.valueOf(f.prop("variant_id"));
            String id = String.valueOf(f.prop("id"));
            Object[] refs = {f.prop("start_node_id"), f.prop("end_node_id")};
            double[][] ends = {f.line().get(0), f.line().get(f.line().size() - 1)};
            for (int i = 0; i < 2; i++) {
                String key = ReferenceOutputOracle.key(refs[i]);
                double[] w = inputWgs.get(key);
                if (w == null) {
                    w = nodeWgs.getOrDefault(vid, new HashMap<>()).get(key);
                    Set<String> owners = variantsOfId.get(key);
                    if (w == null) {
                        if (owners != null && !owners.contains(vid)) out.add(id + ": references node " + refs[i] + " of another variant " + owners);
                        else out.add(id + ": node " + refs[i] + " does not exist (neither input nor a node of variant " + vid + ")");
                        continue;
                    }
                }
                Coordinate a = crs.toMetric(w[0], w[1]), b = crs.toMetric(ends[i][0], ends[i][1]);
                if (a.distance(b) > 0.05) out.add(String.format("%s: %s node %s is %.3f m from the LineString end", id, i == 0 ? "start" : "end", refs[i], a.distance(b)));
            }
        }
        // summaries and ranks
        for (Map.Entry<String, Integer> e : summariesPerVariant.entrySet()) if (e.getValue() != 1) out.add("variant " + e.getKey() + " has " + e.getValue() + " summaries");
        for (String vid : variantsOfId.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()))
            if (!summariesPerVariant.containsKey(vid)) out.add("variant " + vid + " has features but no variant_summary");
        TreeSet<Integer> ranks = new TreeSet<>(rankOf.values());
        if (ranks.size() != rankOf.size()) out.add("ranks are not unique: " + rankOf);
        int expect = 1;
        for (int r : ranks) { if (r != expect) { out.add("ranks are not contiguous 1..N: " + rankOf); break; } expect++; }
        return out;
    }

    /** Recursive walk over property values: numbers must be finite, nested lists/maps are inspected. */
    static void walk(String id, String path, Object value, List<String> out) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.add(id + ": " + path + " is " + value);
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) walk(id, path + "." + e.getKey(), e.getValue(), out);
        } else if (value instanceof List) {
            int i = 0;
            for (Object o : (List<?>) value) walk(id, path + "[" + (i++) + "]", o, out);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    static List<String> checkRawJson(JsonNode root, InputModel in, boolean depthMode) {
        List<String> out = new ArrayList<>();
        if (!"FeatureCollection".equals(root.path("type").asText())) out.add("type is not FeatureCollection");
        JsonNode features = root.path("features");
        if (!features.isArray()) { out.add("features is not an array"); return out; }
        Set<String> ids = new HashSet<>();
        Set<String> inputKeys = new HashSet<>();   // "N:text" / "S:text" — the JSON type is part of the identity
        for (ConnectionPoint cp : in.connectionPoints()) inputKeys.add((cp.id().isNumeric() ? "N:" : "S:") + cp.id().text());
        for (ExistingChamber c : in.chambers()) inputKeys.add((c.id().isNumeric() ? "N:" : "S:") + c.id().text());
        for (JsonNode f : features) {
            JsonNode p = f.path("properties");
            String id = p.path("id").asText();
            String type = p.path("object_type").asText();
            walkJson(id, "properties", p, out);
            if (!"variant_summary".equals(type) && !ids.add(p.path("variant_id").asText() + "|" + p.path("id").getNodeType() + "|" + id)) out.add(id + ": duplicate id in JSON");
            if ("heat_network".equals(type)) {
                JsonNode g = f.path("geometry");
                if (!"LineString".equals(g.path("type").asText()) || g.path("coordinates").size() < 2) out.add(id + ": geometry is not a LineString with >= 2 coordinates");
                for (String ref : new String[]{"start_node_id", "end_node_id"}) {
                    JsonNode r = p.path(ref);
                    if (!r.isTextual() && !r.isNumber()) out.add(id + ": " + ref + " is " + r.getNodeType());
                    String k = (r.isNumber() ? "N:" : "S:") + r.asText();
                    boolean generated = r.isTextual() && r.asText().startsWith(p.path("variant_id").asText() + "_");
                    if (!generated && !inputKeys.contains(k))
                        out.add(id + ": " + ref + " " + r + " (" + r.getNodeType() + ") matches no input id with the same JSON type");
                }
                JsonNode ds = p.path("depth_start"), de = p.path("depth_end");
                if (depthMode && !(ds.isNumber() && de.isNumber())) out.add(id + ": depth_start/depth_end must be numbers in DEPTH mode");
                if (!depthMode && !(ds.isNull() && de.isNull())) out.add(id + ": depth_start/depth_end must be null in PLANAR mode");
                if (!p.path("diameter").isIntegralNumber()) out.add(id + ": diameter must be an integer");
            } else if ("variant_summary".equals(type)) {
                if (!f.path("geometry").isNull()) out.add(id + ": variant_summary geometry must be null");
                if (!p.path("rank").isIntegralNumber()) out.add(id + ": rank must be an integer");
                if (!p.path("existing_chamber_tie_in_count").isIntegralNumber()) out.add(id + ": existing_chamber_tie_in_count must be an integer");
                JsonNode u = p.path("unconnected_oks_ids");
                if (!u.isArray()) out.add(id + ": unconnected_oks_ids must be an array");
                else for (JsonNode x : u) {
                    if (!inputKeys.contains((x.isNumber() ? "N:" : "S:") + x.asText())) out.add(id + ": unconnected id " + x + " (" + x.getNodeType() + ") matches no input consumer id with the same JSON type");
                }
            } else if ("heat_chamber".equals(type) || "technical_node".equals(type)) {
                JsonNode g = f.path("geometry");
                if (!"Point".equals(g.path("type").asText())) out.add(id + ": geometry is not a Point");
            }
        }
        return out;
    }

    static void walkJson(String id, String path, JsonNode n, List<String> out) {
        if (n.isNumber()) {
            if (!Double.isFinite(n.asDouble())) out.add(id + ": " + path + " is not finite");
        } else if (n.isTextual()) {
            String t = n.asText();
            if ("NaN".equals(t) || "Infinity".equals(t) || "-Infinity".equals(t)) out.add(id + ": " + path + " is the string " + t + " (non-finite number written as text)");
        } else if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) { Map.Entry<String, JsonNode> e = it.next(); walkJson(id, path + "." + e.getKey(), e.getValue(), out); }
        } else if (n.isArray()) {
            int i = 0;
            for (JsonNode x : n) walkJson(id, path + "[" + (i++) + "]", x, out);
        }
    }
}
