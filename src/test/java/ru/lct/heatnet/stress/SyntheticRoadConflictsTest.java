package ru.lct.heatnet.stress;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.linearref.LengthIndexedLine;
import java.nio.file.Paths;
import java.util.*;
import ru.lct.heatnet.domain.*;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.validation.ResultValidator;
import static org.junit.jupiter.api.Assertions.*;

/** No solver NO_PATH is used as evidence of impossibility. The terminal is strictly inside a road core. */
class SyntheticRoadConflictsTest {
    @ParameterizedTest @ValueSource(ints={2,11,12,15,17})
    void nonChamberConsumerInsideRoadCannotAccommodateRequiredThreeMetres(int id) throws Exception {
        InputModel full=new GeoJsonStreamReader(ParseOptions.defaults()).read(Paths.get("src/test/resources/routing/competition-with-roads.geojson"));
        ConnectionPoint cp=full.connectionPoints().stream().filter(p->p.id().text().equals(Integer.toString(id))).findFirst().orElseThrow();
        var point=GeometryUtils.point(cp.coordinate());
        Restriction road=full.restrictions().stream().filter(r->r.restrictionType().equals("road")&&r.geometry().contains(point)).findFirst().orElseThrow();
        assertTrue(road.geometry().getBoundary().distance(point)>0.1,"strict interior, not a tolerance/boundary artifact");
        assertTrue(full.chambers().stream().noneMatch(c->c.coordinate().distance(cp.coordinate())<0.1));
        assertTrue(full.networkLines().stream().noneMatch(l->l.line().distance(point)<0.1));
        String reason=new ru.lct.heatnet.solver.ApproachPlanner(new ru.lct.heatnet.restrictions.ObstacleSpace(full,ru.lct.heatnet.restrictions.ClearanceClass.SMALL)).provenTerminalConflict(cp);
        assertNotNull(reason);assertTrue(reason.startsWith("GENUINELY_UNREACHABLE"));assertTrue(reason.contains(road.id().text()));
        InputModel m=new InputModel(full.sources(),full.networkLines(),full.chambers(),Collections.singletonList(cp),full.restrictions(),new Diagnostics(),full.featureCount());
        List<ExistingNetworkLine> lines=new ArrayList<>(m.networkLines());lines.sort(Comparator.comparingDouble(l->l.line().distance(point)));
        // Two different tie-in lines and directions: each independent validation rejects the section at the consumer.
        for(int i=0;i<2;i++) {
            ExistingNetworkLine line=lines.get(i);LengthIndexedLine lil=new LengthIndexedLine(line.line());
            var target=lil.extractPoint(lil.project(cp.coordinate()));
            NewNetwork net=new NewNetwork();
            net.addLink(net.addConnectionNode(cp),net.rootOnExistingLine(line,target,2),Arrays.asList(cp.coordinate(),target),Collections.emptyList());
            assertTrue(HydraulicCalculator.compute(net).feasible());
            VariantResult v=new VariantResult("conflict",net,Collections.emptyMap(),CostCalculator.summarize(net,Collections.emptyList()),Collections.emptyList());v.setVariantId("v1");v.setRank(1);
            var rep=new ResultValidator(m).validate(new OutputBuilder().build(v));
            assertFalse(rep.isValid());
            assertTrue(rep.getIssues().stream().anyMatch(x->x.getCode().equals("SECTION_OUTSIDE")&&x.getMessage().contains(road.id().text())),rep.getIssues().toString());
        }
        // General argument: every continuous terminal approach contains a nonzero suffix in this open road interior.
        // A road section must extend 3 m beyond both boundaries. No outside suffix exists after this terminal;
        // the official exception applies to an existing/new chamber on the existing network, not to this consumer.
    }
}
