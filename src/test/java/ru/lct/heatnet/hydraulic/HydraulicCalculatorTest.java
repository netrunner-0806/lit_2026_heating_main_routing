package ru.lct.heatnet.hydraulic;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class HydraulicCalculatorTest {

    static ConnectionPoint cp(String id, double x, double y, double flow) {
        return new ConnectionPoint(JsonId.ofString(id), GeometryUtils.GF.createPoint(new Coordinate(x, y)), flow, new double[]{0, 0}, 0);
    }

    static NetNode root(NewNetwork net) {
        return net.rootAtExistingChamber(new ExistingChamber(JsonId.ofString("c"), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0), 2);
    }

    static NetLink straight(NewNetwork net, NetNode child, NetNode parent) {
        return net.addLink(child, parent, Arrays.asList(child.coord(), parent.coord()), Collections.emptyList());
    }

    @Test
    void duByFlowOnly() {
        NewNetwork net = new NewNetwork();
        NetNode r = root(net);
        NetLink l = straight(net, net.addConnectionNode(cp("a", 0, 100, 20)), r);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(20, l.flow(), 1e-9);
        assertEquals(100, l.du());
    }

    @Test
    void duRaisedByMaxLength() {
        // 4 t/h -> DU65 (245 m) but the section is 400 m -> DU100 (419 m); DU80 (327 m) is not enough
        NewNetwork net = new NewNetwork();
        NetNode r = root(net);
        NetLink l = straight(net, net.addConnectionNode(cp("a", 0, 400, 4.0)), r);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(100, l.du());
        assertTrue(HydraulicCalculator.lengthViolations(net).isEmpty());
    }

    @Test
    void continuousLengthAccumulatesThroughJunctionWithoutDuChange() {
        // leaf a (10 t/h -> DU80, 327 m): 200 m to junction J, then J -> root 200 m carrying a+b (12 t/h -> still DU80)
        // the DU80 run is 400 m > 327 -> the root-side section must become DU100; the leaf side stays DU80.
        NewNetwork net = new NewNetwork();
        NetNode r = root(net);
        NetNode j = net.addJunctionNode(new Coordinate(0, 200));
        NetNode a = net.addConnectionNode(cp("a", 0, 400, 10));
        NetNode b = net.addConnectionNode(cp("b", 50, 200, 2));
        NetLink la = straight(net, a, j);
        NetLink lb = straight(net, b, j);
        NetLink lj = straight(net, j, r);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(12, lj.flow(), 1e-9);
        assertEquals(80, la.du());
        assertEquals(50, lb.du());
        assertEquals(100, lj.du(), "root-side section takes the next DU so that the DU80 run stays within 327 m");
        assertTrue(HydraulicCalculator.lengthViolations(net).isEmpty());
    }

    @Test
    void duNeverDecreasesTowardsTheExistingNetwork() {
        // leaf a: 4 t/h over 400 m -> DU100 (by length); trunk J->root carries 4+3 = 7 t/h -> DU65 by flow,
        // but must be >= DU100 (monotonic).
        NewNetwork net = new NewNetwork();
        NetNode r = root(net);
        NetNode j = net.addJunctionNode(new Coordinate(0, 50));
        NetNode a = net.addConnectionNode(cp("a", 0, 450, 4));
        NetNode b = net.addConnectionNode(cp("b", 30, 50, 3));
        NetLink la = straight(net, a, j);
        NetLink lb = straight(net, b, j);
        NetLink lj = straight(net, j, r);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(100, la.du());
        assertEquals(50, lb.du());
        // trunk must be >= DU100; the DU100 run a->root would be 450 m > 419 m, so the trunk takes DU125
        assertEquals(125, lj.du());
    }

    @Test
    void sharedTrunkCarriesSumOfFlows() {
        NewNetwork net = new NewNetwork();
        NetNode r = root(net);
        NetNode j = net.addJunctionNode(new Coordinate(0, 50));
        NetNode a = net.addConnectionNode(cp("a", -20, 100, 24.87));
        NetNode b = net.addConnectionNode(cp("b", 20, 100, 18.76));
        straight(net, a, j);
        straight(net, b, j);
        NetLink lj = straight(net, j, r);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(43.63, lj.flow(), 1e-9);
        assertEquals(150, lj.du()); // 43.63 > 40.2 (DU125) -> DU150
    }

    @Test
    void infeasibleWhenTooLongForLargestDu() {
        NewNetwork net = new NewNetwork();
        NetNode r = root(net);
        straight(net, net.addConnectionNode(cp("a", 0, 12_000, 4)), r);
        assertFalse(HydraulicCalculator.compute(net).feasible());
    }
}
