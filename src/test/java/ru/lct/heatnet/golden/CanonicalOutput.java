package ru.lct.heatnet.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.lct.heatnet.output.OutputFeature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical, semantic form of an output feature list for golden-file comparison:
 * <ul>
 *   <li>generated ids ({@code v1_net_3}, {@code v1_chamber_1}, {@code v1_tn_2}, {@code v1_summary}) are replaced by
 *   structural labels derived from the sorted position of the feature ({@code v1:heat_network#3}); references
 *   ({@code start_node_id}, {@code end_node_id}) are remapped the same way, input ids stay as they are;</li>
 *   <li>features are sorted by (variant_id, object_type, geometry, properties);</li>
 *   <li>coordinates are rounded to 1e-7 degrees, other numbers to 1e-3; property keys are sorted;</li>
 *   <li>job-specific / free-text fields ({@code notes}) are dropped.</li>
 * </ul>
 */
final class CanonicalOutput {
    private CanonicalOutput() {}

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    private static final java.util.Set<String> DROP = new java.util.HashSet<>(java.util.Arrays.asList("notes"));
    private static final java.util.Set<String> GENERATED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList("heat_network", "heat_chamber", "technical_node", "variant_summary"));

    static ArrayNode canonical(List<OutputFeature> features) {
        // 1. normalise each feature without ids, remember its original id
        List<ObjectNode> nodes = new ArrayList<>();
        List<String> originalIds = new ArrayList<>();
        for (OutputFeature f : features) {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.set("geometry", geometry(f));
            ObjectNode props = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, Object> e : new TreeMap<>(f.properties()).entrySet()) {
                if (DROP.contains(e.getKey()) || e.getKey().equals("id")) continue;
                props.set(e.getKey(), value(e.getValue(), 3));
            }
            n.set("properties", props);
            nodes.add(n);
            originalIds.add(String.valueOf(f.prop("id")));
        }
        // 2. sort structurally (ids and references are not part of the key)
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) order.add(i);
        order.sort((a, b) -> sortKey(nodes.get(a)).compareTo(sortKey(nodes.get(b))));
        // 3. labels for generated ids
        Map<String, String> label = new HashMap<>();
        Map<String, Integer> counters = new HashMap<>();
        for (int i : order) {
            ObjectNode n = nodes.get(i);
            String type = n.path("properties").path("object_type").asText();
            String vid = n.path("properties").path("variant_id").asText();
            if (!GENERATED_TYPES.contains(type)) continue;
            String base = vid + ":" + type;
            int k = counters.merge(base, 1, Integer::sum);
            label.put(originalIds.get(i), base + "#" + k);
        }
        // 4. emit with labels and remapped references
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (int i : order) {
            ObjectNode n = nodes.get(i).deepCopy();
            ObjectNode props = (ObjectNode) n.get("properties");
            props.put("id", label.getOrDefault(originalIds.get(i), originalIds.get(i)));
            for (String ref : new String[]{"start_node_id", "end_node_id"}) {
                JsonNode r = props.get(ref);
                if (r != null && !r.isNull() && label.containsKey(r.asText())) props.put(ref, label.get(r.asText()));
            }
            out.add(n);
        }
        return out;
    }

    private static String sortKey(ObjectNode n) {
        JsonNode p = n.get("properties");
        StringBuilder sb = new StringBuilder();
        sb.append(p.path("variant_id").asText()).append('|').append(p.path("object_type").asText()).append('|');
        sb.append(n.get("geometry").toString()).append('|');
        ObjectNode rest = p.deepCopy();
        rest.remove("start_node_id");
        rest.remove("end_node_id");
        sb.append(rest.toString());
        return sb.toString();
    }

    private static JsonNode geometry(OutputFeature f) {
        switch (f.geomType()) {
            case POINT: return coord(f.point());
            case LINESTRING: {
                ArrayNode a = JsonNodeFactory.instance.arrayNode();
                for (double[] c : f.line()) a.add(coord(c));
                return a;
            }
            default: return JsonNodeFactory.instance.nullNode();
        }
    }

    private static ArrayNode coord(double[] c) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        a.add(round(c[0], 7));
        a.add(round(c[1], 7));
        return a;
    }

    @SuppressWarnings("unchecked")
    private static JsonNode value(Object v, int decimals) {
        if (v == null) return JsonNodeFactory.instance.nullNode();
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) return JsonNodeFactory.instance.numberNode(round(((Number) v).doubleValue(), decimals));
        if (v instanceof Number) return JsonNodeFactory.instance.numberNode(((Number) v).longValue());
        if (v instanceof Boolean) return JsonNodeFactory.instance.booleanNode((Boolean) v);
        if (v instanceof Map) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) v).entrySet()) o.set(e.getKey(), value(e.getValue(), decimals));
            return o;
        }
        if (v instanceof List) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            for (Object o : (List<Object>) v) a.add(value(o, decimals));
            return a;
        }
        if (v instanceof JsonNode) return (JsonNode) v;
        return JsonNodeFactory.instance.textNode(String.valueOf(v));
    }

    private static BigDecimal round(double d, int decimals) {
        BigDecimal b = new BigDecimal(d).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0) : b;
    }

    /** Readable line diff of two pretty-printed JSON documents (first differing lines). */
    static String diff(String expected, String actual) {
        List<String> e = java.util.Arrays.asList(expected.split("\n")), a = java.util.Arrays.asList(actual.split("\n"));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < Math.max(e.size(), a.size()) && shown < 20; i++) {
            String le = i < e.size() ? e.get(i) : "<eof>", la = i < a.size() ? a.get(i) : "<eof>";
            if (!le.equals(la)) { sb.append("line ").append(i + 1).append(":\n  expected: ").append(le).append("\n  actual:   ").append(la).append('\n'); shown++; }
        }
        return sb.length() == 0 ? "(documents differ only in ordering/whitespace)" : sb.toString();
    }

    static List<String> emptyList() { return Collections.emptyList(); }
}
