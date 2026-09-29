package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
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
 * Rule: annex 2.3 / table 1 — max continuous length Lmax of a DU, checked per continuous consumer→root path;
 * chambers / technical nodes without a DU change do not reset the count, a DU change does.
 * <pre>
 * run of DU80 (Lmax 327 m) | 326.999 | 327.000 | 327.001
 * solver                   | DU80    | DU80    | trunk raised to DU100
 * validator MAX_LENGTH     | ok      | ok      | error
 * </pre>
 * The run is accumulated across a junction chamber (flow changes, DU does not) and across a technical node, so
 * every single feature stays far below the limit: only the whole path exceeds it.
 */
class MaxLengthBoundaryTest {

    private static final double L80 = DiameterTable.requireDu(80).maxLengthM();   // 327
    private static final double[] EPS = {-0.001, 0.0, 0.001};

    private static ConnectionPoint cp(String id, double x, double y, double flow) {
        return new ConnectionPoint(JsonId.ofString(id), GeometryUtils.GF.createPoint(new Coordinate(x, y)), flow, new double[]{0, 0}, 0);
    }

    private static NetLink straight(NewNetwork net, NetNode child, NetNode parent) {
        return net.addLink(child, parent, Arrays.asList(child.coord(), parent.coord()), Collections.emptyList());
    }

    @Test
    void solverAccumulatesTheRunThroughAJunctionWithoutDuChange() {
        for (double eps : EPS) {
            NewNetwork net = new NewNetwork();
            NetNode r = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofString("c"), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0), 2);
            NetNode j = net.addJunctionNode(new Coordinate(0, 127));
            NetLink la = straight(net, net.addConnectionNode(cp("a", 0, L80 + eps, 10)), j);    // 200 + eps m, DU80
            NetLink lb = straight(net, net.addConnectionNode(cp("b", 30, 127, 2)), j);          // DU50
            NetLink lj = straight(net, j, r);                                                    // 127 m, 12 t/h -> DU80 by flow
            assertTrue(HydraulicCalculator.compute(net).feasible());
            assertEquals(80, la.du(), "eps " + eps);
            assertEquals(50, lb.du());
            assertEquals(eps > 0 ? 100 : 80, lj.du(), "run " + (L80 + eps) + " m of DU80");
            assertTrue(HydraulicCalculator.lengthViolations(net).isEmpty());
        }
    }

    @Test
    void duChangeStartsANewCount() {
        // leaf 4 t/h -> DU65 (245 m) over 240 m; trunk 12 t/h -> DU80 (327 m) over 300 m: 540 m in total, each run within its limit
        NewNetwork net = new NewNetwork();
        NetNode r = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofString("c"), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0), 2);
        NetNode j = net.addJunctionNode(new Coordinate(0, 300));
        NetLink la = straight(net, net.addConnectionNode(cp("a", 0, 540, 4)), j);
        straight(net, net.addConnectionNode(cp("b", 30, 300, 8)), j);
        NetLink lj = straight(net, j, r);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(65, la.du());
        assertEquals(80, lj.du());
        assertTrue(HydraulicCalculator.lengthViolations(net).isEmpty());
    }

    @Test
    void validatorChecksTheWholeContinuousPathNotSingleFeatures() {
        for (double eps : EPS) {
            double ya = L80 + eps;   // consumer a: path a -> t (100 m) -> J (100 + eps m) -> R (127 m)
            InputModel in = new SyntheticInput().line(1, 300, -100, 0, 100, 0).point("a", 0, ya, 10).point("b", 30, 127, 2).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    line("n1", "a", "t", 10, 80, false, 1.0, 0, ya, 0, 227),
                    line("n2", "t", "J", 10, 80, false, 1.0, 0, 227, 0, 127),
                    line("n3", "b", "J", 2, 50, false, 1.0, 30, 127, 0, 127),
                    line("n4", "J", "R", 12, 80, false, 1.0, 0, 127, 0, 0),
                    tn("t", 0, 227), chamber("J", 0, 127, 80), chamber("R", 0, 0, 300)));
            fs.add(summary(fs, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(eps > 0, has(rep, "MAX_LENGTH"), "run " + (L80 + eps) + ": " + issues(rep));
            assertFalse(has(rep, "CAPACITY"));
            if (eps <= 0) assertTrue(rep.isValid(), issues(rep));
        }
    }
}
