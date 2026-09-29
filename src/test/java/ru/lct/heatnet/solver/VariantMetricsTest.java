package ru.lct.heatnet.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class VariantMetricsTest {

    static ConnectionPoint cp(String id, double x, double y, double flow) {
        return new ConnectionPoint(JsonId.ofString(id), GeometryUtils.GF.createPoint(new Coordinate(x, y)), flow, new double[]{0, 0}, 0);
    }

    static ExistingNetworkLine line() {
        return new ExistingNetworkLine(JsonId.ofNumber(1), GeometryUtils.GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(400, 0)}), 400,
                ru.lct.heatnet.config.DiameterTable.requireDu(400), false, 0);
    }

    /** Shared trunk: A and B join at J (100,100), trunk J -> root (100,0). */
    static VariantResult shared(String id, double rootX) {
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootOnExistingLine(line(), new Coordinate(rootX, 0), 2);
        NetNode j = net.addJunctionNode(new Coordinate(rootX, 100));
        NetNode a = net.addConnectionNode(cp("A", rootX - 50, 150, 10));
        NetNode b = net.addConnectionNode(cp("B", rootX + 50, 150, 10));
        net.addLink(a, j, Arrays.asList(a.coord(), j.coord()), Collections.emptyList());
        net.addLink(b, j, Arrays.asList(b.coord(), j.coord()), Collections.emptyList());
        net.addLink(j, root, Arrays.asList(j.coord(), root.coord()), Collections.emptyList());
        HydraulicCalculator.compute(net);
        VariantResult v = new VariantResult(id, net, new LinkedHashMap<>(), CostCalculator.summarize(net, Collections.emptyList()), Collections.emptyList());
        v.setVariantId(id);
        return v;
    }

    /** Separate: A and B each with their own root. */
    static VariantResult separate(String id) {
        NewNetwork net = new NewNetwork();
        NetNode ra = net.rootOnExistingLine(line(), new Coordinate(50, 0), 2);
        NetNode rb = net.rootOnExistingLine(line(), new Coordinate(150, 0), 2);
        NetNode a = net.addConnectionNode(cp("A", 50, 150, 10));
        NetNode b = net.addConnectionNode(cp("B", 150, 150, 10));
        net.addLink(a, ra, Arrays.asList(a.coord(), ra.coord()), Collections.emptyList());
        net.addLink(b, rb, Arrays.asList(b.coord(), rb.coord()), Collections.emptyList());
        HydraulicCalculator.compute(net);
        VariantResult v = new VariantResult(id, net, new LinkedHashMap<>(), CostCalculator.summarize(net, Collections.emptyList()), Collections.emptyList());
        v.setVariantId(id);
        return v;
    }

    @Test
    void metricsAndDiff() {
        VariantResult v1 = shared("v1", 100), v2 = separate("v2"), v3 = shared("v3", 104);
        assertEquals(100, v1.metrics().sharedNetworkLength, 1e-9, "only the trunk is shared");
        assertEquals(1, v1.metrics().rootCount);
        assertEquals(0, v2.metrics().sharedNetworkLength, 1e-9);
        assertEquals(2, v2.metrics().rootCount);
        VariantMetrics.Diff d = VariantMetrics.diff(v2, v1);
        assertEquals(2, d.consumersWithDifferentBranch, "both consumers left the shared trunk");
        assertEquals(2, d.newAttachmentPoints, "both roots (50 and 150) are farther than 10 m from the v1 root at 100");
        assertTrue(d.text.startsWith("vs v1:"));
        assertTrue(d.text.contains("shared"), d.text);
        // a 4 m shift of the same attachment point is not a different attachment
        VariantMetrics.Diff d3 = VariantMetrics.diff(v3, v1);
        assertEquals(0, d3.consumersWithDifferentAttachment);
        assertEquals(0, d3.newAttachmentPoints);
        assertEquals(0, d3.consumersWithDifferentBranch);
    }
}
