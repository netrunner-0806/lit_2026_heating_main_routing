package ru.lct.heatnet.hydraulic;

import ru.lct.heatnet.config.DiameterSpec;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Simplified hydraulic model of the technical annex (section 2.3):
 * <ol>
 *   <li>flow of a link = sum of flow_tph of all connection points downstream of it;</li>
 *   <li>DU = minimal DU satisfying the capacity;</li>
 *   <li>DU must not decrease towards the existing network;</li>
 *   <li>max continuous length is checked per leaf-to-root path; a run of equal DU accumulates across nodes and is
 *   reset only where the DU changes; if a run exceeds the limit, the entire constant-flow part containing the
 *   exceeded section takes the next DU. Geometry/output splits never reset the hydraulic part.</li>
 * </ol>
 * The three rules are iterated to a fixed point; DUs only grow, so it terminates.
 */
public final class HydraulicCalculator {

    private HydraulicCalculator() {}

    public static final class Result {
        private final boolean feasible;
        private final String problem;

        Result(boolean feasible, String problem) { this.feasible = feasible; this.problem = problem; }

        public boolean feasible() { return feasible; }
        public String problem() { return problem; }
    }

    /** Computes flow and DU for every link in place. */
    public static Result compute(NewNetwork net) {
        // 1. flows
        for (NetLink l : net.links()) { setFlow(l, 0); }
        for (NetNode cp : net.connectionNodes()) {
            double q = cp.connectionPoint().flowTph();
            for (NetLink l = cp.parent(); l != null; l = l.parent().parent()) setFlow(l, l.flow() + q);
        }
        // 2. initial DU by flow
        for (NetLink l : net.links()) {
            Optional<DiameterSpec> s = DiameterTable.minByFlow(l.flow());
            if (!s.isPresent()) return new Result(false, "flow " + l.flow() + " t/h exceeds the largest DU capacity on " + l);
            setDu(l, s.get().du());
        }
        // 3. fixed point of monotonicity + max length
        for (int iter = 0; iter < 200; iter++) {
            boolean changed = false;
            // monotonic: parent >= children (post-order)
            for (NetNode root : net.roots()) {
                for (NetLink top : root.children()) {
                    if (enforceMonotonic(top)) changed = true;
                }
            }
            // max length per path
            for (NetNode cp : net.connectionNodes()) {
                List<NetLink> path = new ArrayList<>();
                for (NetLink l = cp.parent(); l != null; l = l.parent().parent()) path.add(l);
                double run = 0;
                int prevDu = -1;
                for (NetLink l : path) {
                    if (l.du() != prevDu) { run = 0; prevDu = l.du(); }
                    run += l.length();
                    DiameterSpec spec = DiameterTable.requireDu(l.du());
                    if (run > spec.maxLengthM() + 1e-6) {
                        Optional<DiameterSpec> next = DiameterTable.next(l.du());
                        if (!next.isPresent()) return new Result(false, "continuous length " + Math.round(run) + " m exceeds the limit of the largest DU on " + l);
                        raiseConstantFlowPart(l, next.get().du());
                        changed = true;
                        // Earlier links in this path may also have changed DU: recompute the run next iteration.
                        break;
                    }
                }
            }
            if (!changed) return new Result(true, null);
        }
        return new Result(false, "hydraulic iteration did not converge");
    }

    /** Returns true if any DU was raised. */
    private static boolean enforceMonotonic(NetLink l) {
        boolean changed = false;
        int maxChild = 0;
        for (NetLink c : l.child().children()) {
            if (enforceMonotonic(c)) changed = true;
            maxChild = Math.max(maxChild, c.du());
        }
        if (maxChild > l.du()) { raiseConstantFlowPart(l, maxChild); changed = true; }
        return changed;
    }

    /** Clarification 29.09.2026 #20: degree-two splits cannot introduce a diameter change. */
    private static void raiseConstantFlowPart(NetLink link, int du) {
        NetLink top = link;
        while (top.parent().parent() != null && top.parent().children().size() == 1
                && Double.compare(top.flow(), top.parent().parent().flow()) == 0)
            top = top.parent().parent();
        for (NetLink l = top; ; ) {
            if (l.du() < du) setDu(l, du);
            if (l.child().children().size() != 1) break;
            NetLink child = l.child().children().get(0);
            if (Double.compare(child.flow(), l.flow()) != 0) break;
            l = child;
        }
    }

    // package-private mutators via reflection-free accessors
    private static void setFlow(NetLink l, double q) { ru.lct.heatnet.topology.LinkMutator.setFlow(l, q); }
    private static void setDu(NetLink l, int du) { ru.lct.heatnet.topology.LinkMutator.setDu(l, du); }

    /**
     * Max continuous-length check for reporting: returns the list of (leaf id, DU, run length, limit) violations.
     */
    public static List<String> lengthViolations(NewNetwork net) {
        List<String> out = new ArrayList<>();
        for (NetNode cp : net.connectionNodes()) {
            double run = 0;
            int prevDu = -1;
            for (NetLink l = cp.parent(); l != null; l = l.parent().parent()) {
                if (l.du() != prevDu) { run = 0; prevDu = l.du(); }
                run += l.length();
                if (run > DiameterTable.requireDu(l.du()).maxLengthM() + 1e-6)
                    out.add("path from " + cp.connectionPoint().id() + ": run of DU" + l.du() + " is " + Math.round(run) + " m > " + DiameterTable.requireDu(l.du()).maxLengthM());
            }
        }
        return out;
    }
}
