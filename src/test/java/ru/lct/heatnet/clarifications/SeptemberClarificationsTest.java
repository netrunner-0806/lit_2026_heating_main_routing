package ru.lct.heatnet.clarifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.topology.*;
import ru.lct.heatnet.validation.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

/** Official clarifications 29.09.2026: inside-road attachments, centreline extents and constant-flow DU. */
class SeptemberClarificationsTest {
    private static List<OutputFeature> output(NewNetwork net) {
        VariantResult v = new VariantResult("regression", net, Collections.emptyMap(),
                CostCalculator.summarize(net, Collections.emptyList()), Collections.emptyList());
        v.setVariantId("v1"); v.setRank(1);
        return new OutputBuilder().build(v);
    }

    private static NetLink link(NewNetwork net, NetNode child, NetNode parent) {
        return net.addLink(child, parent, Arrays.asList(child.coord(), parent.coord()), Collections.emptyList());
    }

    private static List<OutputFeature> lines(List<OutputFeature> features) {
        return features.stream().filter(f -> "heat_network".equals(f.objectType())).collect(Collectors.toList());
    }

    @ParameterizedTest
    @CsvSource({"road,true", "road,false", "tram_tracks,true", "tram_tracks,false"})
    void chamberInsidePolygonHasOnlyExitExtension(String type, boolean existing) {
        SyntheticInput builder = new SyntheticInput().line(1, 300, -10, 0, 10, 0)
                .rect(2, type, -20, -10, 20, 10).point("A", 0, 80, 10);
        if (existing) builder.chamber("C", 0, 0);
        InputModel in = builder.build();
        ObstacleSpace space = new ObstacleSpace(in, ClearanceClass.SMALL);
        Exemptions ex = new Exemptions().disc(c(0, 0), 10, o -> o.isExistingNetwork());
        SegmentCheck check = space.check(c(0, 80), c(0, 0), ex);
        assertTrue(check.valid(), check.reason());
        assertTrue(check.validReverse());
        assertEquals(1, check.passages().size());
        assertEquals(67, check.passages().get(0).from(), 1e-8);
        assertEquals(80, check.passages().get(0).to(), 1e-8);
        SegmentCheck reversed = space.check(c(0, 0), c(0, 80), ex);
        assertTrue(reversed.valid(), reversed.reason());
        assertEquals(0, reversed.passages().get(0).from(), 1e-8);
        assertEquals(13, reversed.passages().get(0).to(), 1e-8);
        assertFalse(space.check(c(0, 0), c(0, 12.9), ex).valid(), "full 3 m after exit must fit");
        assertFalse(space.check(c(0, 80), c(0, 0)).valid(), "ordinary vertex cannot truncate a special passage");
        NewNetwork net = new NewNetwork();
        NetNode root = existing ? net.rootAtExistingChamber(in.chambers().get(0), 2)
                : net.rootOnExistingLine(in.networkLines().get(0), c(0, 0), 2);
        NetNode cp = net.addConnectionNode(in.connectionPoints().get(0));
        net.addLink(cp, root, Arrays.asList(cp.coord(), root.coord()), check.passages());
        assertTrue(HydraulicCalculator.compute(net).feasible());
        List<OutputFeature> fs = output(net);
        ValidationReport report = new ResultValidator(in).validate(fs);
        assertTrue(report.isValid(), report.getIssues().toString());
        assertEquals(0, report.getWarningCount(), report.getIssues().toString());
        OutputFeature special = lines(fs).stream().filter(f -> "special".equals(f.prop("laying_method"))).findFirst().orElseThrow();
        assertEquals(13, ((Number) special.prop("length")).doubleValue(), 1e-8);
        assertEquals(2, special.line().size(), "one straight section from camera through exit + 3 m");
        assertEquals(existing ? 1 : 0, CostCalculator.summarize(net, Collections.emptyList()).tieInCount());
        SolveResult solved = new HeatnetSolver(SolverConfig.defaults(), new SolverVariantValidator(in)).solve(in);
        assertFalse(solved.variants().isEmpty(), solved.diagnostics().messages().toString());
        assertEquals(1, solved.variants().get(0).connectedCount(), "all available attachment points are inside the polygon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gas_pipeline", "power_cable", "heat_network"})
    void linearCrossingExtentIsExactlyTwoMetresAlongAxis(String type) {
        SyntheticInput b = new SyntheticInput();
        if (type.equals("heat_network")) b.line(1, 1400, -100, 0, 100, 0);
        else b.polyline(1, type, -100, 0, 100, 0);
        ObstacleSpace space = new ObstacleSpace(b.build(), ClearanceClass.LARGE);
        // 60-degree crossing, large DU: neither obliqueness nor pair width increases the 4 m section.
        SegmentCheck result = space.check(c(-10, -10 * Math.sqrt(3)), c(10, 10 * Math.sqrt(3)));
        assertTrue(result.valid(), result.reason());
        assertEquals(18, result.passages().get(0).from(), 1e-8);
        assertEquals(22, result.passages().get(0).to(), 1e-8);
        assertTrue(space.check(c(0, -2), c(0, 20)).valid());
        assertFalse(space.check(c(0, -1.9), c(0, 20)).valid());
    }

    @Test
    void constantFlowAcrossGeometrySplitsRaisesEntirePart() {
        InputModel in = new SyntheticInput().line(1, 300, -10, 0, 10, 0).point("A", 0, 400, 4).build();
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootOnExistingLine(in.networkLines().get(0), c(0, 0), 2);
        NetNode junction = net.addJunctionNode(c(0, 100));
        NetLink first = link(net, net.addConnectionNode(in.connectionPoints().get(0)), junction);
        NetLink last = link(net, junction, root);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(100, first.du()); assertEquals(100, last.du());
        assertTrue(HydraulicCalculator.lengthViolations(net).isEmpty());
        assertTrue(new ResultValidator(in).validate(output(net)).isValid());
        // The old workaround (DU65 for 200 m, DU80 for 200 m) passes each local length limit but is illegal.
        NewNetwork bad = new NewNetwork();
        NetNode r = bad.rootOnExistingLine(in.networkLines().get(0), c(0, 0), 2);
        NetNode j = bad.addJunctionNode(c(0, 200));
        NetLink a = link(bad, bad.addConnectionNode(in.connectionPoints().get(0)), j), z = link(bad, j, r);
        LinkMutator.setFlow(a, 4); LinkMutator.setFlow(z, 4);
        LinkMutator.setDu(a, 65); LinkMutator.setDu(z, 80);
        ValidationReport report = new ResultValidator(in).validate(output(bad));
        assertTrue(report.getIssues().stream().anyMatch(x -> "DU_CHANGE_IN_SECTION".equals(x.getCode())));
    }

    @Test
    void technicalAndSpecialOutputSplitsKeepTheHydraulicDiameter() {
        InputModel in = new SyntheticInput().line(1, 300, -10, 0, 10, 0)
                .polyline(2, "gas_pipeline", -20, 200, 20, 200).point("A", 0, 400, 4).build();
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootOnExistingLine(in.networkLines().get(0), c(0, 0), 2);
        NetNode cp = net.addConnectionNode(in.connectionPoints().get(0));
        SegmentCheck sc = new ObstacleSpace(in, ClearanceClass.SMALL).check(cp.coord(), root.coord(),
                new Exemptions().disc(root.coord(), 10, o -> o.isExistingNetwork()));
        assertTrue(sc.valid(), sc.reason());
        net.addLink(cp, root, Arrays.asList(cp.coord(), root.coord()), sc.passages());
        assertTrue(HydraulicCalculator.compute(net).feasible());
        List<OutputFeature> fs = output(net);
        assertEquals(3, lines(fs).size());
        assertEquals(2, fs.stream().filter(f -> "technical_node".equals(f.objectType())).count());
        for (OutputFeature f : lines(fs)) assertEquals(100, ((Number) f.prop("diameter")).intValue());
        ResultValidator validator = new ResultValidator(in);
        ValidationReport report = validator.validate(fs);
        assertTrue(report.isValid(), report.getIssues().toString());
        assertEquals(0, report.getWarningCount());
        lines(fs).get(1).prop("diameter", 125);
        assertTrue(validator.validate(fs).getIssues().stream().anyMatch(x -> "DU_CHANGE_IN_SECTION".equals(x.getCode())));
        lines(fs).get(1).prop("diameter", 100);
        assertTrue(validator.validate(fs).isValid(), "reused input index must not retain validation state from another result");
    }

    @Test
    void nearbyNewChambersRemainJunctionCandidates() throws Exception {
        InputModel in = new SyntheticInput().line(1, 300, -10, 0, 10, 0).point("A", 0, 10, 4).build();
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootOnExistingLine(in.networkLines().get(0), c(0, 0), 2);
        NetNode child = net.addConnectionNode(in.connectionPoints().get(0));
        NetLink link = net.addLink(child, root, Arrays.asList(c(0, 10), c(0, 9), c(0, 1), c(0, 0)), Collections.emptyList());
        ForestBuilder builder = new ForestBuilder(new SolverContext(in, SolverConfig.defaults()),
                HeatnetSolver.defaultStrategies().get(0), Collections.emptyMap());
        java.lang.reflect.Method positions = ForestBuilder.class.getDeclaredMethod("junctionPositions", NetLink.class);
        positions.setAccessible(true);
        List<?> candidates = (List<?>) positions.invoke(builder, link);
        assertTrue(candidates.contains(1.0), "a chamber may be 1 m from the child end");
        assertTrue(candidates.contains(9.0), "a chamber may be 1 m from the existing-network end");
    }

    @Test
    void realBranchAllowsNewHydraulicPart() {
        InputModel in = new SyntheticInput().line(1, 300, -10, 0, 10, 0)
                .point("A", 0, 400, 10).point("B", 50, 200, 2).build();
        NewNetwork net = new NewNetwork();
        NetNode root = net.rootOnExistingLine(in.networkLines().get(0), c(0, 0), 2);
        NetNode branch = net.addJunctionNode(c(0, 200));
        NetLink a = link(net, net.addConnectionNode(in.connectionPoints().get(0)), branch);
        link(net, net.addConnectionNode(in.connectionPoints().get(1)), branch);
        NetLink trunk = link(net, branch, root);
        assertTrue(HydraulicCalculator.compute(net).feasible());
        assertEquals(80, a.du()); assertEquals(100, trunk.du());
        assertEquals(12, trunk.flow(), 1e-9);
    }
}
