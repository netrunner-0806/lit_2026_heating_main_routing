package ru.lct.heatnet.solver;

import java.util.*;

/** Operation totals count preparation once. Graph dimensions are maximum observed per context, not sums of shared graphs. */
final class ParallelMetrics {
    private static final List<String> OPERATIONS = Arrays.asList(
            "dijkstra_traversals", "geometry_visibility_checks", "dynamic_vertex_insertions",
            "candidate_pairs_examined", "certified_interior_rejections", "visibility_checks",
            "candidate_hydraulic_recomputations", "candidate_topology_copies", "candidate_count",
            "routing_graph_time_ms", "fallback_activations", "navigation_region_expansions",
            "routing_intersection_calls", "robust_overlay_retries");
    static Map<String,Object> aggregate(Map<String,Object> current, Map<String,Object> discarded,
                                         List<Map<String,Object>> strategies) {
        Map<String,Object> out = new LinkedHashMap<>(current);
        List<Map<String,Object>> parts = new ArrayList<>(strategies);
        parts.add(current);
        if (discarded != null) parts.add(discarded);
        for (String key : OPERATIONS) {
            long sum = 0;
            for (Map<String,Object> part : parts) {
                sum += value(part,key);
                if (key.equals("visibility_checks")) sum -= value(part,"inherited_visibility_checks");
            }
            out.put(key,sum);
        }
        for (String key : Arrays.asList("graph_nodes","graph_edges","graph_directed_edges","tie_in_candidates","restriction_check_buffers"))
            out.put(key,parts.stream().mapToLong(p->value(p,key)).max().orElse(0));
        out.put("shared_graph_metrics",new LinkedHashMap<>(discarded == null ? current : discarded));
        out.put("recovery_graph_metrics",discarded == null ? Collections.emptyMap() : new LinkedHashMap<>(current));
        out.put("operation_counts_complete",parts.stream()
                .noneMatch(p->Boolean.FALSE.equals(p.get("operation_counts_complete"))));
        out.remove("inherited_visibility_checks");
        return out;
    }
    private static long value(Map<String,Object> part,String key) {
        Object value=part.get(key);return value instanceof Number ? ((Number)value).longValue() : 0;
    }
}
