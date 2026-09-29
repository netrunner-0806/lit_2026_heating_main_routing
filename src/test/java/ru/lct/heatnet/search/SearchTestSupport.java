package ru.lct.heatnet.search;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.CalculationDecision;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.List;

/** Shared helpers of the search-hardening tests (false unconnected, route diversity, toy oracle). */
final class SearchTestSupport {
    private SearchTestSupport() {}

    static SolveResult solve(InputModel in, boolean depth) {
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = depth;
        return new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
    }

    static VariantResult best(SolveResult r) {
        if (r.variants().isEmpty()) throw new AssertionError("no valid variant: " + r.diagnostics().messages());
        return r.variants().get(0);
    }

    static List<OutputFeature> render(VariantResult v) { return new ArrayList<>(new OutputBuilder().build(v)); }

    static ValidationReport validate(InputModel in, VariantResult v) { return new ResultValidator(in).validate(render(v)); }

    /** x of the root (attachment point) of the given connection point, relative to the synthetic origin. */
    static Coordinate rootOf(VariantResult v, String cpId) {
        for (NetNode n : v.network().connectionNodes()) {
            if (!n.connectionPoint().id().text().equals(cpId)) continue;
            NetNode cur = n;
            while (cur.parent() != null) cur = cur.parent().parent();
            return new Coordinate(cur.coord().x - SyntheticInput.OX, cur.coord().y - SyntheticInput.OY);
        }
        return null;
    }

    static int junctionChambers(VariantResult v) {
        int n = 0;
        for (NetNode node : v.network().nodes()) if (node.kind() == NetNode.Kind.NEW_CHAMBER_JUNCTION) n++;
        return n;
    }

    /** Human-readable summary of the solver's decisions for one connection point (trace evidence). */
    static String traceOf(VariantResult v, String cpId) {
        StringBuilder sb = new StringBuilder();
        if (v.trace() == null) return "(no trace)";
        for (CalculationDecision d : v.trace().decisions()) {
            if (!cpId.equals(d.objectId)) continue;
            sb.append(d.type).append(": ").append(d.decision).append(" — ").append(d.reason).append(' ').append(d.inputs);
            for (String a : d.alternatives) sb.append("\n      ").append(a);
            sb.append('\n');
        }
        return sb.toString();
    }

    static String describe(VariantResult v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.strategy()).append(' ').append(v.summary()).append('\n');
        for (NetLink l : v.network().links()) {
            sb.append("  ").append(l).append(" coords=");
            for (Coordinate c : l.coords()) sb.append(String.format("(%.1f,%.1f)", c.x - SyntheticInput.OX, c.y - SyntheticInput.OY));
            sb.append(" passages=").append(l.passages().size()).append('\n');
        }
        if (!v.unconnected().isEmpty()) sb.append("  unconnected: ").append(v.unconnected()).append('\n');
        return sb.toString();
    }
}
