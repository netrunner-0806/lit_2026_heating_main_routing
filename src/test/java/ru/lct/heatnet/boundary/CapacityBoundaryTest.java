package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DiameterSpec;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;

/**
 * Rule: annex 2.3 / table 1 — DU is the minimal one whose capacity Q covers the flow (flow ≤ Q admissible).
 * <pre>
 * flow            | Q − ε | Q      | Q + ε
 * selected DU     | this  | this   | next larger DU (largest DU: infeasible)
 * validator       | ok    | ok     | CAPACITY
 * </pre>
 * ε = 1e-3 t/h (flow resolution of the input) and 1e-6 t/h, for every row of table 1.
 */
class CapacityBoundaryTest {

    private static final double[] EPS = {1e-3, 1e-6};

    @Test
    void tableSelectsTheMinimalDuWhoseCapacityIsNotLessThanTheFlow() {
        List<DiameterSpec> rows = DiameterTable.rows();
        for (int i = 0; i < rows.size(); i++) {
            DiameterSpec s = rows.get(i);
            for (double eps : EPS) {
                assertEquals(s.du(), DiameterTable.minByFlow(s.capacityTph() - eps).get().du(), "DU" + s.du() + " Q-eps");
                assertEquals(s.du(), DiameterTable.minByFlow(s.capacityTph()).get().du(), "DU" + s.du() + " Q");
                if (i + 1 < rows.size()) assertEquals(rows.get(i + 1).du(), DiameterTable.minByFlow(s.capacityTph() + eps).get().du(), "DU" + s.du() + " Q+eps");
                else assertFalse(DiameterTable.minByFlow(s.capacityTph() + eps).isPresent(), "beyond the largest DU");
            }
        }
    }

    private static NewNetwork single(double flow) {
        NewNetwork net = new NewNetwork();
        NetNode r = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofString("c"), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0), 2);
        NetNode a = net.addConnectionNode(new ConnectionPoint(JsonId.ofString("a"), GeometryUtils.GF.createPoint(new Coordinate(0, 10)), flow, new double[]{0, 0}, 0));
        net.addLink(a, r, Arrays.asList(a.coord(), r.coord()), Collections.emptyList());
        return net;
    }

    @Test
    void hydraulicCalculatorPerDu() {
        List<DiameterSpec> rows = DiameterTable.rows();
        for (int i = 0; i < rows.size(); i++) {
            DiameterSpec s = rows.get(i);
            for (double eps : EPS) {
                assertEquals(s.du(), du(single(s.capacityTph() - eps)));
                assertEquals(s.du(), du(single(s.capacityTph())));
                NewNetwork over = single(s.capacityTph() + eps);
                HydraulicCalculator.Result h = HydraulicCalculator.compute(over);
                if (i + 1 < rows.size()) { assertTrue(h.feasible()); assertEquals(rows.get(i + 1).du(), du(over)); }
                else assertFalse(h.feasible(), "flow above the largest capacity is infeasible");
            }
        }
    }

    private static int du(NewNetwork net) {
        assertTrue(HydraulicCalculator.compute(net).feasible());
        for (NetLink l : net.links()) return l.du();
        throw new AssertionError("no link");
    }

    @Test
    void validatorCapacityAtTheInputResolution() {
        double q = DiameterTable.requireDu(100).capacityTph();   // 22.3
        double[] flows = {round3(q - 0.001), q, round3(q + 0.001)};
        boolean[] error = {false, false, true};
        for (int i = 0; i < flows.length; i++) {
            InputModel in = new SyntheticInput().line(1, 300, -100, 0, 100, 0).point("A", 0, 100, flows[i]).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    line("n1", "A", "R", flows[i], 100, false, 1.0, 0, 100, 0, 0),
                    chamber("R", 0, 0, 300)));
            fs.add(summary(fs, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(error[i], has(rep, "CAPACITY"), "flow " + flows[i] + ": " + issues(rep));
            assertFalse(has(rep, "DU_OVERSIZED"), "DU100 is minimal for " + flows[i]);
            if (!error[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }
}
