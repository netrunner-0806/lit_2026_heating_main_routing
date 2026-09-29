package ru.lct.heatnet.cost;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.SpecialPassage;
import ru.lct.heatnet.topology.LinkMutator;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CostCalculatorTest {

    static ConnectionPoint cp(String id, double x, double y, double flow) {
        return new ConnectionPoint(JsonId.ofString(id), GeometryUtils.GF.createPoint(new Coordinate(x, y)), flow, new double[]{0, 0}, 0);
    }

    static ExistingChamber chamber(String id, double x, double y) {
        return new ExistingChamber(JsonId.ofString(id), GeometryUtils.GF.createPoint(new Coordinate(x, y)), new double[]{0, 0}, 0);
    }

    @Test
    void scoreMatchesTheAnnexExample() {
        // annex 7.3: 100 m of DU100 = 8 974 800 + one tie-in 5 000 000 -> score 0.6913
        assertEquals(8_974_800, 100 * ru.lct.heatnet.config.DiameterTable.requireDu(100).costPerMeter(), 1e-6);
        assertEquals(0.6913, CostCalculator.score(13_974_800, 100.0), 1e-4);
    }

    @Test
    void penaltyFormula() {
        assertEquals(100_000_000 + 500_000 * 24.87, CostCalculator.penalty(24.87), 1e-6);
    }

    @Test
    void existingChamberTieInCostAndSummary() {
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootAtExistingChamber(chamber("c1", 0, 0), 2);
        NetNode a = net.addConnectionNode(cp("a", 0, 100, 20));
        NetNode b = net.addConnectionNode(cp("b", 50, 0, 10));
        net.addLink(a, root, Arrays.asList(new Coordinate(0, 100), new Coordinate(0, 0)), Collections.emptyList());
        net.addLink(b, root, Arrays.asList(new Coordinate(50, 0), new Coordinate(0, 0)), Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(net).feasible());
        CostSummary s = CostCalculator.summarize(net, Collections.singletonList(5.0));
        assertEquals(2, s.tieInCount());
        assertEquals(10_000_000, s.tieInCost(), 1e-6);
        assertEquals(0, s.chamberCost(), 1e-6);
        double lines = 100 * 89_748 + 50 * 83_530; // DU100 for 20 t/h, DU80 for 10 t/h
        assertEquals(lines, s.lineCost(), 1e-6);
        assertEquals(lines + 10_000_000, s.constructionCost(), 1e-6);
        assertEquals(100_000_000 + 500_000 * 5, s.penalty(), 1e-6);
        assertEquals(s.constructionCost() + s.penalty(), s.calculatedCost(), 1e-6);
        assertEquals(150, s.newLength(), 1e-6);
        assertEquals(CostCalculator.score(s.calculatedCost(), 150), s.score(), 1e-12);
        assertEquals(0, root.spareDegree());
    }

    @Test
    void specialPiecesUseMaxKspecAndSplitAtBoundaries() {
        Restriction road = new Restriction(JsonId.ofString("r"), GeometryUtils.GF.createPolygon(), "road", RestrictionRules.require("road"), 0);
        Restriction gas = new Restriction(JsonId.ofString("g"), GeometryUtils.GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 1)}), "gas_pipeline", RestrictionRules.require("gas_pipeline"), 1);
        Obstacle oRoad = Obstacle.of(0, road), oGas = Obstacle.of(1, gas);
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootAtExistingChamber(chamber("c1", 0, 0), 2);
        NetNode a = net.addConnectionNode(cp("a", 100, 0, 20));
        List<SpecialPassage> ps = Arrays.asList(
                new SpecialPassage(oRoad, 20, 40, 1.60, new Coordinate(70, 0), 90),
                new SpecialPassage(oGas, 35, 39, 1.25, new Coordinate(63, 0), 90));
        NetLink l = net.addLink(a, root, Arrays.asList(new Coordinate(100, 0), new Coordinate(0, 0)), ps);
        LinkMutator.setDu(l, 100);
        LinkMutator.setFlow(l, 20);
        List<LinkPiece> pieces = CostCalculator.pieces(l);
        // base [0,20], special road [20,35], special road+gas [35,39], special road [39,40], base [40,100]
        assertEquals(5, pieces.size());
        assertFalse(pieces.get(0).special());
        assertEquals(20, pieces.get(0).length(), 1e-9);
        assertTrue(pieces.get(1).special());
        assertEquals(1.60, pieces.get(1).kSpec());
        assertEquals(1.60, pieces.get(2).kSpec(), "overlap uses the max coefficient, not sum/product");
        assertEquals(2, pieces.get(2).crossed().size());
        assertEquals(4, pieces.get(2).length(), 1e-9);
        assertEquals(1.60, pieces.get(3).kSpec());
        assertFalse(pieces.get(4).special());
        double cpm = 89_748;
        // piece costs are whole rubles (rounded), so compare with a 1 ruble tolerance
        assertEquals(20 * cpm, pieces.get(0).cost(), 1.0);
        assertEquals(15 * cpm * 1.60, pieces.get(1).cost(), 1.0);
        assertEquals(4 * cpm * 1.60, pieces.get(2).cost(), 1.0);
        double total = 0;
        for (LinkPiece p : pieces) total += p.cost();
        assertEquals(CostCalculator.linkCost(l), total, 1e-6);
        assertEquals(80 * cpm + 20 * cpm * 1.60, total, 2.0);
    }

    @Test
    void specialPieceIsSplitAtAnInteriorTurn() {
        // link with a 90° turn at (50,0); a merged special section [30..70] spans the vertex -> two straight special pieces
        Restriction road = new Restriction(JsonId.ofString("r"), GeometryUtils.GF.createPolygon(), "road", RestrictionRules.require("road"), 0);
        Obstacle o = Obstacle.of(0, road);
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootAtExistingChamber(chamber("c1", 0, 0), 2);
        NetNode a = net.addConnectionNode(cp("a", 50, 50, 20));
        NetLink l = net.addLink(a, root, Arrays.asList(new Coordinate(50, 50), new Coordinate(50, 0), new Coordinate(0, 0)),
                Arrays.asList(new SpecialPassage(o, 30, 70, 1.60, new Coordinate(50, 0), 90)));
        LinkMutator.setDu(l, 100);
        LinkMutator.setFlow(l, 20);
        List<LinkPiece> pieces = CostCalculator.pieces(l);
        assertEquals(4, pieces.size(), "base, special (before the turn), special (after the turn), base");
        assertTrue(pieces.get(1).special() && pieces.get(2).special());
        assertEquals(2, pieces.get(1).coords().size(), "special piece is a single straight segment");
        assertEquals(2, pieces.get(2).coords().size());
        assertEquals(20, pieces.get(1).length(), 1e-9);
        assertEquals(20, pieces.get(2).length(), 1e-9);
        assertEquals(new Coordinate(50, 0), pieces.get(1).coords().get(1));
    }

    @Test
    void newChamberCostByMaxAdjacentDuIncludingExistingLine() {
        ru.lct.heatnet.domain.ExistingNetworkLine line = new ru.lct.heatnet.domain.ExistingNetworkLine(JsonId.ofNumber(7),
                GeometryUtils.GF.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(100, 0)}), 400,
                ru.lct.heatnet.config.DiameterTable.requireDu(400), false, 0);
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootOnExistingLine(line, new Coordinate(50, 0), 2);
        NetNode a = net.addConnectionNode(cp("a", 50, 100, 20));
        net.addLink(a, root, Arrays.asList(new Coordinate(50, 100), new Coordinate(50, 0)), Collections.emptyList());
        HydraulicCalculator.compute(net);
        assertEquals(400, root.maxAdjacentDu());
        assertEquals(5_000_000, CostCalculator.newChamberCost(root));
        assertEquals(1, root.spareDegree());
        NetNode j = net.addJunctionNode(new Coordinate(0, 0));
        assertEquals(0, j.maxAdjacentDu());
        assertEquals(3_000_000, CostCalculator.newChamberCost(j));
    }
}
