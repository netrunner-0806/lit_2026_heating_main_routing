package ru.lct.heatnet.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.api.dto.VariantSummaryDto;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.InputParseException;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.persistence.JobEntity;
import ru.lct.heatnet.persistence.JobRepository;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationIssue;
import ru.lct.heatnet.validation.ValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Runs the full pipeline for one job: streaming parse -> solve -> render -> final validation -> write GeoJSON. */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository repo;
    private final HeatnetProperties props;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JobRunner(JobRepository repo, HeatnetProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public void run(String id) {
        Optional<JobEntity> opt = repo.findById(id);
        if (!opt.isPresent()) return;
        JobEntity e = opt.get();
        e.setStatus(JobEntity.Status.RUNNING);
        e.setStartedAt(Instant.now());
        e.setProgress("Parsing input");
        repo.save(e);
        long t0 = System.currentTimeMillis();
        try {
            ParseOptions options = new ParseOptions(ParseOptions.UnknownRestrictionPolicy.valueOf(e.getUnknownRestrictionPolicy()));
            InputModel input = new GeoJsonStreamReader(options).read(Paths.get(e.getInputPath()));
            e.setFeatureCount(input.featureCount());
            e.setConnectionPointCount(input.connectionPoints().size());
            if (input.diagnostics().hasErrors()) {
                fail(e, "Input rejected: " + describe(input.diagnostics()), input.diagnostics(), t0);
                return;
            }
            long parseMs = System.currentTimeMillis() - t0;
            e.setInputStatsJson(mapper.writeValueAsString(inputStats(input)));
            boolean depth = "DEPTH".equals(e.getMode());
            progress(e, "Building routing graph");
            SolverConfig cfg = SolverConfig.defaults();
            cfg.maxVariants = e.getMaxVariants();
            cfg.improve = props.isImprove();
            cfg.depthMode = depth;
            HeatnetSolver solver = new HeatnetSolver(cfg, new SolverVariantValidator(input));
            SolveResult result = solver.solve(input, HeatnetSolver.defaultStrategies(), msg -> progress(e, (depth ? "Generating variants, flows, DU and depth profiles: " : "Generating variants, flows and DU: ") + msg));
            progress(e, "Validating");
            long tv = System.currentTimeMillis();
            List<OutputFeature> features = new ArrayList<>();
            OutputBuilder builder = new OutputBuilder();
            for (VariantResult v : result.variants()) features.addAll(builder.build(v));
            ValidationReport report = new ResultValidator(input).validate(features);
            long validationMs = System.currentTimeMillis() - tv;
            Path out = Paths.get(e.getInputPath()).resolveSibling("result.geojson");
            new GeoJsonResultWriter().write(out, features);
            e.setResultPath(out.toString());
            e.setVariantCount(result.variants().size());
            e.setSummaryJson(mapper.writeValueAsString(summaries(result)));
            e.setDiagnosticsJson(mapper.writeValueAsString(diagnostics(result.diagnostics())));
            e.setValidationJson(mapper.writeValueAsString(validation(report)));
            e.setExplainJson(mapper.writeValueAsString(explain(result)));
            e.setElapsedMs(System.currentTimeMillis() - t0);
            java.util.Map<String, Object> metrics = new java.util.LinkedHashMap<>();
            metrics.put("parse_time_ms", parseMs);
            metrics.putAll(result.metrics());
            metrics.put("final_validation_time_ms", validationMs);
            metrics.put("total_time_ms", e.getElapsedMs());
            e.setMetricsJson(mapper.writeValueAsString(metrics));
            e.setFinishedAt(Instant.now());
            if (result.variants().isEmpty()) {
                e.setStatus(JobEntity.Status.FAILED);
                e.setError("No valid variant could be built: " + describe(result.diagnostics()));
                e.setProgress("failed");
            } else {
                e.setStatus(JobEntity.Status.DONE);
                e.setProgress("Done: " + result.variants().size() + " variant(s), validation " + (report.isValid() ? "passed" : "FAILED"));
            }
            repo.save(e);
            log.info("job {} finished in {} ms: {} variants, validation valid={}", id, e.getElapsedMs(), result.variants().size(), report.isValid());
        } catch (InputParseException ex) {
            fail(e, "Invalid input: " + ex.getMessage(), ex.diagnostics(), t0);
        } catch (Exception ex) {
            log.error("job {} failed", id, ex);
            fail(e, "Processing failed: " + ex, null, t0);
        }
    }

    private void progress(JobEntity e, String msg) {
        e.setProgress(msg);
        try { repo.save(e); } catch (RuntimeException ignore) { }
    }

    private void fail(JobEntity e, String message, Diagnostics diag, long t0) {
        e.setStatus(JobEntity.Status.FAILED);
        e.setError(message);
        e.setProgress("failed");
        e.setElapsedMs(System.currentTimeMillis() - t0);
        e.setFinishedAt(Instant.now());
        if (diag != null) {
            try { e.setDiagnosticsJson(mapper.writeValueAsString(diagnostics(diag))); } catch (Exception ignore) { }
        }
        discardInput(e);
        repo.save(e);
    }

    /** The upload of a failed job is not retained (uploads may be gigabytes); metadata and diagnostics stay. */
    private void discardInput(JobEntity e) {
        if (e.getInputPath() == null) return;
        try {
            Path p = Paths.get(e.getInputPath());
            Files.deleteIfExists(p);
            Files.deleteIfExists(p.resolveSibling("result.geojson"));
            log.info("job {} failed: input file discarded ({} bytes)", e.getId(), e.getInputSize());
        } catch (IOException ex) {
            log.warn("job {}: could not discard the input file: {}", e.getId(), ex.toString());
        }
        e.setInputPath(null);
    }

    private static String describe(Diagnostics d) {
        List<String> parts = new ArrayList<>();
        for (Diagnostics.Message m : d.errors()) parts.add(m.getText());
        return String.join("; ", parts);
    }

    public static List<VariantSummaryDto> summaries(SolveResult result) {
        List<VariantSummaryDto> out = new ArrayList<>();
        for (VariantResult v : result.variants()) {
            VariantSummaryDto d = new VariantSummaryDto();
            d.variantId = v.variantId();
            d.rank = v.rank();
            d.strategy = v.strategy();
            d.score = v.summary().score();
            d.constructionCost = v.summary().constructionCost();
            d.networkConstructionCost = v.summary().lineCost();
            d.chamberConstructionCost = v.summary().chamberCost();
            d.existingChamberTieInCount = v.summary().tieInCount();
            d.existingChamberTieInCost = v.summary().tieInCost();
            d.unconnectedPenalty = v.summary().penalty();
            d.calculatedCost = v.summary().calculatedCost();
            d.newNetworkLength = v.summary().newLength();
            d.connectedOksCount = v.connectedCount();
            d.newChamberCount = v.network().newChambers().size();
            d.lineCount = v.network().linkCount();
            d.unconnectedOksIds = new ArrayList<>();
            for (ConnectionPoint cp : v.unconnected().keySet()) d.unconnectedOksIds.add(cp.id().toJavaValue());
            d.notes = v.notes();
            d.sharedNetworkLength = v.metrics().sharedNetworkLength;
            d.rootCount = v.metrics().rootCount;
            d.attachmentPoints = v.metrics().attachmentPoints;
            d.diffVsBest = v.diffVsBest();
            boolean depth = false;
            for (ru.lct.heatnet.topology.NetLink l : v.network().links()) if (l.profile() != null) depth = true;
            d.mode = depth ? "depth" : "planar";
            int special = 0;
            for (ru.lct.heatnet.topology.NetLink l : v.network().links()) special += l.passages().size();
            d.specialCrossingCount = special;
            if (depth) {
                ru.lct.heatnet.depth.DepthMetrics dm = ru.lct.heatnet.depth.DepthMetrics.of(v.network());
                d.maxDepth = dm.maxDepth; d.averageDepth = dm.averageDepth; d.depthExtraCost = dm.extraCost;
                d.verticalCrossingCount = dm.verticalCrossingCount; d.aboveCrossingCount = dm.aboveCount; d.belowCrossingCount = dm.belowCount;
                d.depthTransitionCount = dm.transitionCount; d.deepNetworkLength = dm.deepLength; d.shallowNetworkLength = dm.shallowLength;
            }
            out.add(d);
        }
        return out;
    }

    /** Structured input statistics for the diagnostics card. */
    public static java.util.Map<String, Object> inputStats(InputModel input) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("features", input.featureCount());
        m.put("sources", input.sources().size());
        m.put("existing_networks", input.networkLines().size());
        m.put("existing_chambers", input.chambers().size());
        m.put("oks_points", input.connectionPoints().size());
        m.put("restrictions", input.restrictions().size());
        java.util.Map<String, Integer> types = new java.util.TreeMap<>();
        int unknown = 0, custom = 0;
        for (ru.lct.heatnet.domain.Restriction r : input.restrictions()) {
            types.merge(r.restrictionType() == null ? "<missing>" : r.restrictionType(), 1, Integer::sum);
            if (r.rule() == null) unknown++;
            else if (!ru.lct.heatnet.config.RestrictionRules.isStandard(r.restrictionType())) custom++;
        }
        m.put("restriction_types", types);
        m.put("unknown_restrictions", unknown);
        m.put("custom_rule_restrictions", custom);
        int fixed = 0;
        for (Diagnostics.Message msg : input.diagnostics().messages()) if ("INVALID_GEOMETRY_REPAIRED".equals(msg.getCode())) fixed++;
        m.put("invalid_geometries_fixed", fixed);
        m.put("crs", ru.lct.heatnet.config.Constants.CRS_INPUT + " -> " + ru.lct.heatnet.config.Constants.CRS_METRIC);
        return m;
    }

    /** Structured calculation trace (bounded) for the explain endpoint. */
    public static java.util.List<Object> explain(SolveResult result) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (VariantResult v : result.variants()) {
            java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("variantId", v.variantId());
            e.put("rank", v.rank());
            e.put("strategy", v.strategy());
            e.put("decisions", v.trace() == null ? java.util.Collections.emptyList() : v.trace().decisions());
            out.add(e);
        }
        return out;
    }

    public ArrayNode diagnostics(Diagnostics d) {
        ArrayNode arr = mapper.createArrayNode();
        for (Diagnostics.Message m : d.messages()) {
            ObjectNode n = arr.addObject();
            n.put("severity", m.getSeverity().name());
            n.put("code", m.getCode());
            n.put("text", m.getText());
            if (m.getFeatureIndex() != null) n.put("featureIndex", m.getFeatureIndex());
            if (m.getFeatureId() != null) n.put("featureId", m.getFeatureId());
        }
        return arr;
    }

    public ObjectNode validation(ValidationReport r) {
        ObjectNode n = mapper.createObjectNode();
        n.put("valid", r.isValid());
        n.put("errors", r.getErrorCount());
        n.put("warnings", r.getWarningCount());
        ObjectNode checks = n.putObject("checks");
        r.getChecksPerformed().forEach(checks::put);
        ArrayNode issues = n.putArray("issues");
        for (ValidationIssue i : r.getIssues()) {
            ObjectNode o = issues.addObject();
            o.put("severity", i.getSeverity().name());
            o.put("code", i.getCode());
            o.put("message", i.getMessage());
            o.put("variantId", i.getVariantId());
            o.put("featureId", i.getFeatureId());
        }
        return n;
    }
}
