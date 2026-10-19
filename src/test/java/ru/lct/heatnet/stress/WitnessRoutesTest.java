package ru.lct.heatnet.stress;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.*;
import ru.lct.heatnet.geo.*;
import ru.lct.heatnet.restrictions.*;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.*;
import ru.lct.heatnet.output.*;
import ru.lct.heatnet.validation.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WitnessRoutesTest {
    @Test void suppliedWitnessesPassIndependentEngineeringValidation() throws Exception {
        Path root=Paths.get("src/test/resources/routing");
        try (java.util.stream.Stream<Path> files=Files.list(root)) {
            for(Path p:(Iterable<Path>)files.filter(x->x.toString().endsWith(".witness.json")).sorted()::iterator) validate(p);
        }
    }
    static void validate(Path p) throws Exception {
        validate(p,false);
        validate(p,true);
    }
    private static void validate(Path p, boolean depth) throws Exception {
        JsonNode witness=new ObjectMapper().readTree(p.toFile());
        Path input=p.resolveSibling(p.getFileName().toString().replace(".witness.json",".geojson"));
        InputModel m=new GeoJsonStreamReader(ParseOptions.defaults()).read(input);
        assertFalse(m.diagnostics().hasErrors(),m.diagnostics().messages().toString());
        List<Coordinate> cs=new ArrayList<>();
        for(JsonNode c:witness.get("coordinates")) {
            cs.add(CrsTransformer.get().toMetric(c.get(0).asDouble(),c.get(1).asDouble()));
        }
        NewNetwork net=new NewNetwork();
        NetNode root=witness.path("chamber").isNull()?net.rootOnExistingLine(m.networkLines().get(0),cs.get(cs.size()-1),2)
                :net.rootAtExistingChamber(m.chambers().get(0),2);
        ConnectionPoint cp=m.connectionPoints().get(0);NetNode leaf=net.addConnectionNode(cp);
        NetLink link=net.addLink(leaf,root,cs,Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(net).feasible(),p.toString());
        ObstacleSpace space=new ObstacleSpace(m,ClearanceClass.forDu(link.du()));
        Obstacle own=null;for(Obstacle o:space.obstacles())if(o.isOks()&&o.prepared().covers(GeometryUtils.point(cp.coordinate())))own=o;
        List<SpecialPassage> passages=new ArrayList<>();double offset=0;
        for(int i=1;i<cs.size();i++) {
            Exemptions ex=new Exemptions();
            if(i==1&&own!=null)ex.ignore(Collections.singleton(own.index()));
            if(i==cs.size()-1)ex.disc(root.coord(),20,o->o.isExistingNetwork());
            SegmentCheck sc=space.check(cs.get(i-1),cs.get(i),ex);
            assertTrue(sc.valid(),p+": segment "+i+": "+sc.reason());
            for(SpecialPassage s:sc.passages())passages.add(s.shifted(offset));offset+=cs.get(i-1).distance(cs.get(i));
        }
        // Rebuild the witness with explicit special sections; the output validator independently checks the rules.
        NewNetwork result=new NewNetwork();
        NetNode r=witness.path("chamber").isNull()?result.rootOnExistingLine(m.networkLines().get(0),root.coord(),2):result.rootAtExistingChamber(m.chambers().get(0),2);
        NetLink l=result.addLink(result.addConnectionNode(cp),r,cs,passages);
        if(own!=null)l.setApproachInfo(cs.get(0).distance(cs.get(1)),own.index());
        assertTrue(HydraulicCalculator.compute(result).feasible());
        if(depth) {
            var profile=new ru.lct.heatnet.depth.NetworkDepthPlanner().plan(result);
            assertTrue(profile.feasible,p+": depth "+profile.problem);
        }
        VariantResult v=new VariantResult("witness",result,Collections.emptyMap(),CostCalculator.summarize(result,Collections.emptyList()),Collections.emptyList());
        v.setDepthMode(depth);
        v.setVariantId("v1");v.setRank(1);
        List<OutputFeature> fs=new OutputBuilder().build(v);
        ValidationReport report=new ResultValidator(m).validate(fs);
        assertTrue(report.isValid(),p+": "+report.getIssues());assertEquals(0,report.getWarningCount(),p+": "+report.getIssues());
    }
}
