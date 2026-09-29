package ru.lct.heatnet.topology;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.SpecialPassage;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class NewNetworkTest {

    static ConnectionPoint cp(String id, double x, double y, double flow) {
        return new ConnectionPoint(JsonId.ofString(id), GeometryUtils.GF.createPoint(new Coordinate(x, y)), flow, new double[]{0, 0}, 0);
    }

    @Test
    void chamberDegreeLimitIsFour() {
        NewNetwork net = new NewNetwork();
        ExistingChamber ch = new ExistingChamber(JsonId.ofNumber(106), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0);
        NetNode root = net.rootAtExistingChamber(ch, 2); // two existing lines end here
        assertEquals(2, root.spareDegree());
        net.addLink(net.addConnectionNode(cp("a", 0, 10, 1)), root, Arrays.asList(new Coordinate(0, 10), new Coordinate(0, 0)), Collections.emptyList());
        net.addLink(net.addConnectionNode(cp("b", 10, 0, 1)), root, Arrays.asList(new Coordinate(10, 0), new Coordinate(0, 0)), Collections.emptyList());
        assertEquals(0, root.spareDegree(), "2 existing + 2 new = 4: no more sections may end here");
        assertEquals(4, root.degree());
        // a line passing through a chamber takes two slots
        NetNode root2 = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofNumber(107), GeometryUtils.GF.createPoint(new Coordinate(100, 0)), new double[]{0, 0}, 0), 4);
        assertEquals(0, root2.spareDegree());
        // junction created by splitting: degree 3 (two halves + branch), spare 1
        NetNode a2 = net.addConnectionNode(cp("c", 200, 100, 1));
        NetNode root3 = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofNumber(108), GeometryUtils.GF.createPoint(new Coordinate(200, 0)), new double[]{0, 0}, 0), 2);
        NetLink l = net.addLink(a2, root3, Arrays.asList(new Coordinate(200, 100), new Coordinate(200, 50), new Coordinate(200, 0)), Collections.emptyList());
        NetNode j = net.splitLink(l, 60);
        assertEquals(NetNode.Kind.NEW_CHAMBER_JUNCTION, j.kind());
        assertEquals(1, j.children().size());
        assertNotNull(j.parent());
        assertEquals(2, j.degree());
        net.addLink(net.addConnectionNode(cp("d", 250, 40, 1)), j, Arrays.asList(new Coordinate(250, 40), j.coord()), Collections.emptyList());
        assertEquals(3, j.degree());
        assertEquals(1, j.spareDegree());
    }

    @Test
    void splitKeepsGeometryAndDistributesPassages() {
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofNumber(1), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0), 2);
        NetNode a = net.addConnectionNode(cp("a", 0, 100, 1));
        Restriction road = new Restriction(JsonId.ofString("r"), GeometryUtils.GF.createPolygon(), "road", RestrictionRules.require("road"), 0);
        Obstacle o = Obstacle.of(0, road);
        NetLink l = net.addLink(a, root, Arrays.asList(new Coordinate(0, 100), new Coordinate(0, 50), new Coordinate(0, 0)),
                Collections.singletonList(new SpecialPassage(o, 70, 90, 1.6, new Coordinate(0, 20), 90)));
        NetNode j = net.splitLink(l, 30);
        assertEquals(new Coordinate(0, 70), j.coord());
        NetLink first = j.children().get(0), second = j.parent();
        assertEquals(30, first.length(), 1e-9);
        assertEquals(70, second.length(), 1e-9);
        assertEquals(Arrays.asList(new Coordinate(0, 100), new Coordinate(0, 70)), first.coords());
        assertEquals(Arrays.asList(new Coordinate(0, 70), new Coordinate(0, 50), new Coordinate(0, 0)), second.coords());
        assertTrue(first.passages().isEmpty());
        assertEquals(1, second.passages().size());
        assertEquals(40, second.passages().get(0).from(), 1e-9);
        assertEquals(60, second.passages().get(0).to(), 1e-9);
        assertEquals(2, net.linkCount());
        assertThrows(IllegalStateException.class, () -> net.splitLink(second, 50), "cannot split inside a special section");
    }

    @Test
    void removeLeafMergesJunction() {
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootAtExistingChamber(new ExistingChamber(JsonId.ofNumber(1), GeometryUtils.GF.createPoint(new Coordinate(0, 0)), new double[]{0, 0}, 0), 2);
        NetNode a = net.addConnectionNode(cp("a", 0, 100, 1));
        NetLink l = net.addLink(a, root, Arrays.asList(new Coordinate(0, 100), new Coordinate(0, 0)), Collections.emptyList());
        NetNode j = net.splitLink(l, 40);
        NetNode b = net.addConnectionNode(cp("b", 30, 60, 1));
        net.addLink(b, j, Arrays.asList(new Coordinate(30, 60), j.coord()), Collections.emptyList());
        assertEquals(3, net.linkCount());
        net.removeLeaf(b);
        assertEquals(1, net.linkCount());
        assertNull(net.node(j.id()));
        assertEquals(100, a.parent().length(), 1e-9);
        assertEquals(root, a.parent().parent());
        NewNetwork copy = net.copy();
        assertEquals(1, copy.linkCount());
        assertEquals(2, copy.chambers().size() + copy.connectionNodes().size());
    }
}
