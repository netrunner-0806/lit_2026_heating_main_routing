package ru.lct.heatnet.solver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.SegmentCheck;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Job-level orchestration: runs several strategies, re-routes with larger clearance classes where the computed DU
 * requires it, validates every candidate variant with the external validator, removes duplicates, ranks by score.
 */
public final class HeatnetSolver {

    private static final Logger log = LoggerFactory.getLogger(HeatnetSolver.class);

    /** External validator: returns a list of error texts (empty = valid). */
    public interface VariantValidator {
        List<String> validate(VariantResult variant);
    }

    private final SolverConfig config;
    private final VariantValidator validator;

    public HeatnetSolver(SolverConfig config, VariantValidator validator) {
        this.config = config == null ? SolverConfig.defaults() : config;
        this.validator = validator;
    }

    public static List<Strategy> defaultStrategies() {
        return Arrays.asList(
                new Strategy("joint-nearest-first", Strategy.Order.NEAREST_FIRST, true, Strategy.TieInPolicy.ANY, true),
                new Strategy("joint-farthest-first", Strategy.Order.FARTHEST_FIRST, true, Strategy.TieInPolicy.ANY, true),
                new Strategy("joint-largest-flow-first", Strategy.Order.LARGEST_FLOW_FIRST, true, Strategy.TieInPolicy.ANY, true),
                new Strategy("joint-existing-chambers", Strategy.Order.NEAREST_FIRST, true, Strategy.TieInPolicy.EXISTING_CHAMBERS_ONLY, true),
                new Strategy("separate-connections", Strategy.Order.NEAREST_FIRST, false, Strategy.TieInPolicy.ANY, false)
        );
    }

    public SolveResult solve(InputModel input) { return solve(input, defaultStrategies(), null); }

    public SolveResult solve(InputModel input, List<Strategy> strategies, java.util.function.Consumer<String> progress) {
        long t0 = System.currentTimeMillis();
        Diagnostics diag = input.diagnostics();
        SolverContext ctx = new SolverContext(input, config);
        List<VariantResult> candidates = new ArrayList<>();
        for (Strategy s : strategies) {
            if (progress != null) progress.accept("strategy " + s.name());
            try {
                VariantResult vr = buildWithClassUpgrades(ctx, s);
                if (vr != null) { vr.setDepthMode(config.depthMode); candidates.add(vr); }
            } catch (RuntimeException ex) {
                log.warn("strategy {} failed: {}", s, ex.toString(), ex);
                diag.warn("STRATEGY_FAILED", "Strategy " + s.name() + " failed: " + ex.getMessage());
            }
        }
        // validate, dedupe, rank
        List<VariantResult> valid = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        candidates.sort(Comparator.comparingDouble(v -> v.summary().score()));
        long validationMs = 0;
        for (VariantResult vr : candidates) {
            vr.setVariantId("candidate-" + vr.strategy());
            vr.setRank(0);
            long tv = System.currentTimeMillis();
            List<String> errors = validator == null ? Collections.emptyList() : validator.validate(vr);
            validationMs += System.currentTimeMillis() - tv;
            if (!errors.isEmpty()) {
                log.warn("variant {} rejected by validator: {}", vr.strategy(), errors);
                diag.warn("VARIANT_REJECTED", "Variant from strategy " + vr.strategy() + " rejected by the validator: " + String.join("; ", errors));
                continue;
            }
            String sig = signature(vr);
            if (!signatures.add(sig)) {
                diag.info("VARIANT_DUPLICATE", "Variant from strategy " + vr.strategy() + " duplicates a better one and is dropped");
                continue;
            }
            valid.add(vr);
            if (valid.size() >= config.maxVariants) break;
        }
        for (int i = 0; i < valid.size(); i++) {
            valid.get(i).setVariantId("v" + (i + 1));
            valid.get(i).setRank(i + 1);
            if (valid.get(i).trace() != null)
                valid.get(i).trace().add("VARIANT_RANKED", "v" + (i + 1), "rank " + (i + 1), "S = 0.7 * calculated_cost / 25 000 000 + 0.3 * new_network_length / 100; lower is better",
                        CalculationTrace.inputs("score", Math.round(valid.get(i).summary().score() * 10000) / 10000.0, "calculated_cost", Math.round(valid.get(i).summary().calculatedCost()),
                                "new_network_length_m", Math.round(valid.get(i).summary().newLength() * 10) / 10.0, "strategy", valid.get(i).strategy(), "candidates_total", candidates.size()), null);
        }
        for (int i = 1; i < valid.size(); i++) valid.get(i).setDiffVsBest(VariantMetrics.diff(valid.get(i), valid.get(0)));
        if (valid.isEmpty()) diag.error("NO_VALID_VARIANT", "No variant passed validation");
        long ms = System.currentTimeMillis() - t0;
        diag.info("SOLVE_SUMMARY", "variants=" + valid.size() + " of " + candidates.size() + " candidates in " + ms + " ms");
        java.util.Map<String, Object> metrics = new java.util.LinkedHashMap<>(ctx.graphStats());
        metrics.put("variants_evaluated", candidates.size());
        metrics.put("strategies", strategies.size());
        metrics.put("routing_time_ms", ms - ctx.graphBuildMs() - validationMs);
        metrics.put("validation_time_ms", validationMs);
        metrics.put("solve_time_ms", ms);
        metrics.put("mode", config.depthMode ? "depth" : "planar");
        return new SolveResult(valid, diag, ms, metrics);
    }

    /** Builds a variant; if some link's DU needs a larger clearance class than routed, re-routes with raised floors. */
    private VariantResult buildWithClassUpgrades(SolverContext ctx, Strategy s) {
        Map<JsonId, ClearanceClass> floors = new HashMap<>();
        VariantResult vr = null;
        for (int iter = 0; iter <= config.classIterations; iter++) {
            vr = new ForestBuilder(ctx, s, floors).build();
            Map<JsonId, ClearanceClass> upgrades = clearanceUpgrades(ctx, vr, floors);
            if (upgrades.isEmpty()) return vr;
            log.info("strategy {}: clearance upgrades needed for {} points, re-routing", s, upgrades.size());
            floors.putAll(upgrades);
        }
        return vr;
    }

    /** Links whose actual DU requires a clearance class larger than the one used for routing. */
    private Map<JsonId, ClearanceClass> clearanceUpgrades(SolverContext ctx, VariantResult vr, Map<JsonId, ClearanceClass> floors) {
        Map<JsonId, ClearanceClass> out = new HashMap<>();
        NewNetwork net = vr.network();
        for (NetLink l : net.links()) {
            ClearanceClass need = ClearanceClass.forDu(l.du());
            if (need == ClearanceClass.SMALL) continue;
            // is the link geometry valid for the needed class?
            if (linkValid(ctx, l, need)) continue;
            for (NetNode cp : subtreeLeaves(l)) {
                ClearanceClass cur = floors.getOrDefault(cp.connectionPoint().id(), ClearanceClass.SMALL);
                if (need.ordinal() > cur.ordinal()) out.put(cp.connectionPoint().id(), need);
            }
        }
        return out;
    }

    static List<NetNode> subtreeLeaves(NetLink l) {
        List<NetNode> out = new ArrayList<>();
        collectLeaves(l.child(), out);
        return out;
    }

    private static void collectLeaves(NetNode n, List<NetNode> out) {
        if (n.kind() == NetNode.Kind.CONNECTION_POINT) out.add(n);
        for (NetLink c : n.children()) collectLeaves(c.child(), out);
    }

    /** Re-checks a link's segments with the obstacle space of the given class, using the routing exemptions. */
    static boolean linkValid(SolverContext ctx, NetLink l, ClearanceClass cls) {
        SolverContext.ClassResources r = ctx.resources(cls);
        List<org.locationtech.jts.geom.Coordinate> cs = l.coords();
        for (int i = 1; i < cs.size(); i++) {
            Exemptions ex = new Exemptions();
            if (i == 1 && l.ownPolygonIndex() >= 0) ex.ignore(Collections.singleton(l.ownPolygonIndex()));
            if (i == cs.size() - 1 && l.parent().isRoot()) ex.disc(l.parent().coord(), r.tieInDiscRadius, o -> o.isExistingNetwork());
            SegmentCheck sc = r.space.check(cs.get(i - 1), cs.get(i), ex);
            if (!sc.valid()) return false;
        }
        return true;
    }

    /** Topological signature used to drop duplicate variants. */
    static String signature(VariantResult vr) {
        List<String> parts = new ArrayList<>();
        for (NetNode cp : vr.network().connectionNodes()) {
            NetLink l = cp.parent();
            NetNode root = cp;
            while (root.parent() != null) root = root.parent().parent();
            parts.add(cp.connectionPoint().id() + "->" + Math.round(root.coord().x / 5) + ":" + Math.round(root.coord().y / 5) + ":" + l.parent().kind());
        }
        Collections.sort(parts);
        return parts + "|links=" + vr.network().linkCount() + "|L=" + Math.round(vr.summary().newLength() / 5);
    }

    public static Set<JsonId> ids(List<ConnectionPoint> cps, Function<ConnectionPoint, JsonId> f) {
        Set<JsonId> s = new LinkedHashSet<>();
        for (ConnectionPoint cp : cps) s.add(f.apply(cp));
        return s;
    }
}
