package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heatnet.application.JobService;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.persistence.JobEntity;
import ru.lct.heatnet.validation.OutputFeatureReader;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Engineering summary report derived from the stored result GeoJSON (the GeoJSON stays the official output). */
@RestController
@RequestMapping("/api/v1/jobs/{id}")
@Tag(name = "Report", description = "Инженерный отчёт по результату (JSON / CSV)")
public class ReportController {

    private final JobService jobs;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReportController(JobService jobs) { this.jobs = jobs; }

    @GetMapping("/report.json")
    @Operation(summary = "Отчёт по вариантам: распределение ДУ, длины и стоимости по ДУ, спецпроходы, камеры, врезки, глубина")
    public JsonNode reportJson(@PathVariable String id) throws IOException {
        JobEntity e = job(id);
        List<OutputFeature> fs = new OutputFeatureReader().read(Paths.get(e.getResultPath()));
        ObjectNode root = mapper.createObjectNode();
        root.put("jobId", id);
        root.put("mode", e.getMode());
        root.put("inputFile", e.getOriginalFilename());
        root.set("inputStats", jobs.parse(e.getInputStatsJson()));
        ArrayNode variants = root.putArray("variants");
        for (String vid : variantIds(fs)) variants.add(variantReport(fs, vid));
        return root;
    }

    @GetMapping(value = "/report.csv", produces = "text/csv")
    @Operation(summary = "Отчёт по участкам новой сети в CSV")
    public ResponseEntity<String> reportCsv(@PathVariable String id) throws IOException {
        JobEntity e = job(id);
        List<OutputFeature> fs = new OutputFeatureReader().read(Paths.get(e.getResultPath()));
        StringBuilder sb = new StringBuilder("variant_id;id;start_node_id;end_node_id;flow_tph;diameter;length_m;laying_method;k_spec;depth_start;depth_end;k_depth;cost_rub;crossed_restrictions\n");
        for (OutputFeature f : fs) {
            if (!"heat_network".equals(f.objectType())) continue;
            sb.append(f.prop("variant_id")).append(';').append(f.prop("id")).append(';').append(f.prop("start_node_id")).append(';').append(f.prop("end_node_id")).append(';')
                    .append(f.prop("flow_tph")).append(';').append(f.prop("diameter")).append(';').append(f.prop("length")).append(';').append(f.prop("laying_method")).append(';')
                    .append(f.prop("k_spec")).append(';').append(f.prop("depth_start")).append(';').append(f.prop("depth_end")).append(';').append(f.prop("k_depth")).append(';')
                    .append(f.prop("cost")).append(';').append(String.valueOf(f.prop("crossed_restrictions")).replace(';', ',')).append('\n');
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(sb.toString());
    }

    private JobEntity job(String id) {
        JobEntity e = jobs.find(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
        if (e.getStatus() != JobEntity.Status.DONE || e.getResultPath() == null) throw new ConflictException("job " + id + " has no result yet");
        return e;
    }

    private static List<String> variantIds(List<OutputFeature> fs) {
        List<String> out = new java.util.ArrayList<>();
        for (OutputFeature f : fs) if ("variant_summary".equals(f.objectType())) out.add(String.valueOf(f.prop("variant_id")));
        return out;
    }

    private ObjectNode variantReport(List<OutputFeature> fs, String vid) {
        ObjectNode n = mapper.createObjectNode();
        Map<Integer, double[]> byDu = new TreeMap<>();          // du -> {length, cost}
        Map<String, double[]> byCrossing = new TreeMap<>();     // type -> {count, length, cost}
        double specialCost = 0, totalFlow = 0, depthExtra = 0;
        int lines = 0, chambers = 0, tns = 0;
        double chamberCost = 0;
        OutputFeature summary = null;
        for (OutputFeature f : fs) {
            if (!vid.equals(String.valueOf(f.prop("variant_id")))) continue;
            switch (f.objectType()) {
                case "heat_network": {
                    lines++;
                    int du = ((Number) f.prop("diameter")).intValue();
                    double len = num(f.prop("length")), cost = num(f.prop("cost"));
                    byDu.computeIfAbsent(du, k -> new double[2]);
                    byDu.get(du)[0] += len;
                    byDu.get(du)[1] += cost;
                    if ("special".equals(f.prop("laying_method"))) {
                        specialCost += cost;
                        Object cr = f.prop("crossed_restrictions");
                        if (cr instanceof List) for (Object o : (List<?>) cr) {
                            String type = String.valueOf(o).split("#")[0];
                            byCrossing.computeIfAbsent(type, k -> new double[3]);
                            byCrossing.get(type)[0]++;
                            byCrossing.get(type)[1] += len;
                            byCrossing.get(type)[2] += cost;
                        }
                    }
                    Object kd = f.prop("k_depth");
                    if (kd instanceof Number && ((Number) kd).doubleValue() > 1.0) depthExtra += cost - cost / ((Number) kd).doubleValue();
                    break;
                }
                case "heat_chamber": chambers++; chamberCost += num(f.prop("cost")); break;
                case "technical_node": tns++; break;
                default: summary = f;
            }
        }
        n.put("variantId", vid);
        if (summary != null) {
            for (String k : new String[]{"rank", "score", "construction_cost", "calculated_cost", "new_network_length", "chamber_construction_cost", "existing_chamber_tie_in_count",
                    "existing_chamber_tie_in_cost", "unconnected_penalty", "connected_oks_count", "strategy", "mode", "shared_network_length", "max_depth", "average_depth", "depth_extra_cost",
                    "vertical_crossing_count", "above_crossing_count", "below_crossing_count", "depth_transition_count"})
                if (summary.prop(k) != null) n.putPOJO(k, summary.prop(k));
            n.putPOJO("unconnected_oks_ids", summary.prop("unconnected_oks_ids"));
        }
        // flows: sum of consumer flows = max flow at roots is not derivable here; report the sum of leaf-line flows
        for (OutputFeature f : fs) if (vid.equals(String.valueOf(f.prop("variant_id"))) && "heat_network".equals(f.objectType())) totalFlow = Math.max(totalFlow, num(f.prop("flow_tph")));
        n.put("max_section_flow_tph", totalFlow);
        n.put("line_features", lines);
        n.put("new_chambers", chambers);
        n.put("technical_nodes", tns);
        n.put("chamber_cost", chamberCost);
        n.put("special_crossing_cost", specialCost);
        n.put("depth_extra_cost_recomputed", Math.round(depthExtra));
        ArrayNode du = n.putArray("by_diameter");
        for (Map.Entry<Integer, double[]> e : byDu.entrySet()) {
            ObjectNode o = du.addObject();
            o.put("du", e.getKey());
            o.put("length_m", Math.round(e.getValue()[0] * 1000) / 1000.0);
            o.put("cost_rub", Math.round(e.getValue()[1]));
        }
        ArrayNode cr = n.putArray("crossings_by_type");
        for (Map.Entry<String, double[]> e : byCrossing.entrySet()) {
            ObjectNode o = cr.addObject();
            o.put("type", e.getKey());
            o.put("count", (int) e.getValue()[0]);
            o.put("length_m", Math.round(e.getValue()[1] * 1000) / 1000.0);
            o.put("cost_rub", Math.round(e.getValue()[2]));
        }
        return n;
    }

    private static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }
}
