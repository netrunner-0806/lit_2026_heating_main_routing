package ru.lct.heatnet.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

/** Audit S1/S2: the route is straight; it is the obstacle that bends after the crossing. */
class BentCrossingClearanceTest {
    private InputModel scene(String type) {
        SyntheticInput b = new SyntheticInput().source("s", 120, -10)
                .line("net", 300, 120, -10, 120, 10).point("cp", -30, 0, 10);
        if (type.equals("gas_pipeline")) b.polyline("bent", type, 0,-60, 0,1, 80,1, 80,60);
        else b.restriction("bent", type, GeometryUtils.GF.createPolygon(new Coordinate[]{
                c(-2,-60), c(2,-60), c(2,1), c(80,1), c(80,5), c(-2,5), c(-2,-60)}));
        return b.build();
    }

    @ParameterizedTest @ValueSource(strings={"gas_pipeline", "road", "tram_tracks"})
    void solverRejectsParallelRunAfterBendingObstacle(String type) {
        // Independent numerical witness at x=40: axis/boundary y=1, new DU80 width=0.47 m.
        // Gas width=0.40 m. These Table 1/2 constants do not call production clearance rules.
        double actualGap = 1 - 0.47 / 2 - (type.equals("gas_pipeline") ? 0.40 / 2 : 0);
        assertEquals(type.equals("gas_pipeline") ? 0.565 : 0.765, actualGap, 1e-12);
        assertTrue(actualGap < (type.equals("gas_pipeline") ? 2 : 1.5));
        SegmentCheck checked = new ObstacleSpace(scene(type), ClearanceClass.SMALL)
                .check(c(-30,0), c(120,0), new Exemptions().disc(c(120,0),10,Obstacle::isExistingNetwork));
        assertFalse(checked.valid(), "The crossing cannot exempt the 80 m parallel run: " + type);
    }

    @ParameterizedTest @ValueSource(strings={"gas_pipeline", "road", "tram_tracks"})
    void independentValidatorRejectsSameStraightRun(String type) {
        InputModel input = scene(type);
        Obstacle crossed = Obstacle.of(0, input.restrictions().get(0));
        NewNetwork net = new NewNetwork();
        boolean gas = type.equals("gas_pipeline");
        net.addLink(net.addConnectionNode(input.connectionPoints().get(0)),
                net.rootOnExistingLine(input.networkLines().get(0), c(120,0), 2),
                Arrays.asList(c(-30,0), c(120,0)), Collections.singletonList(
                        new SpecialPassage(crossed, gas ? 28 : 25, gas ? 32 : 35,
                                gas ? 1.25 : type.equals("road") ? 1.6 : 1.75, c(0,0), 90)));
        assertTrue(HydraulicCalculator.compute(net).feasible());
        VariantResult v = new VariantResult("audit",net,Collections.emptyMap(),
                CostCalculator.summarize(net,Collections.emptyList()),Collections.emptyList());
        v.setVariantId("v1"); v.setRank(1);
        ValidationReport report = new ResultValidator(input).validate(new OutputBuilder().build(v));
        assertTrue(report.getIssues().stream().anyMatch(i -> i.getCode().equals("CLEARANCE")
                && i.getMessage().contains(type)), report.getIssues().toString());
    }
}
