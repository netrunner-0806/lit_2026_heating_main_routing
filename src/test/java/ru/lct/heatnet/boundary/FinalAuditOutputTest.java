package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.application.JobRunner;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.domain.*;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.*;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.topology.*;
import ru.lct.heatnet.validation.ResultValidator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

class FinalAuditOutputTest {
    static VariantResult variant(InputModel in) {
        NewNetwork n=new NewNetwork();
        var root=n.rootOnExistingLine(in.networkLines().get(0),c(0,0),2);
        var cp=in.connectionPoints().get(0);
        n.addLink(n.addConnectionNode(cp),root,Arrays.asList(cp.coordinate(),c(0,0)),Collections.emptyList());
        assertTrue(HydraulicCalculator.compute(n).feasible());
        var v=new VariantResult("test",n,Collections.emptyMap(),CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());
        v.setVariantId("v1");v.setRank(1);return v;
    }
    @Test void generatedIdsMustNotShadowInputNodes() {
        var in=new SyntheticInput().line("v1_net_1",300,-10,0,10,0).point("v1_chamber_1",0,30,1).build();
        var features=new OutputBuilder().build(variant(in));
        Set<Object> inputIds=new HashSet<>(Arrays.asList("v1_net_1","v1_chamber_1"));
        assertTrue(features.stream().noneMatch(f->inputIds.contains(f.prop("id"))));
    }
    @Test void validatorDetectsInputCollisionEvenForSummary() {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("cp",0,30,1)
                .source("v1_summary",-10,0).build();
        var features=new OutputBuilder().build(variant(in));
        var rep=new ResultValidator(in).validate(features);
        assertTrue(rep.getIssues().stream().anyMatch(i->i.getCode().equals("DUPLICATE_ID")));
    }
    @Test void technicalNodeCountMatchesRenderedNodes() {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("cp",0,30,1)
                .polyline("gas","gas_pipeline",-10,15,10,15).build();
        var v=variant(in);var l=v.network().links().iterator().next();
        var obstacle=ru.lct.heatnet.restrictions.Obstacle.of(0,in.restrictions().get(0));
        l.passages().add(new ru.lct.heatnet.restrictions.SpecialPassage(obstacle,13,17,1.25,c(0,15),90));
        long count=new OutputBuilder().build(v).stream().filter(f->"technical_node".equals(f.prop("object_type"))).count();
        assertEquals(2,count);
        assertEquals(count,JobRunner.summaries(new SolveResult(Collections.singletonList(v),new Diagnostics(),0)).get(0).technicalNodeCount);
    }
    @Test void allInputTypesAndGeneratedTechnicalIdsAreReservedDeterministically() throws Exception {
        var in=new SyntheticInput().line("v1_net_1",300,-10,0,10,0).point("cp",0,30,1)
                .source("v1_chamber_1",-10,0).chamber("v1_tn_1",-10,0)
                .rect("v1_summary","water",100,100,110,110)
                .polyline("gas","gas_pipeline",-10,15,10,15).build();
        var v=variant(in);var l=v.network().links().iterator().next();
        var obstacle=ru.lct.heatnet.restrictions.Obstacle.of(1,in.restrictions().get(1));
        l.passages().add(new ru.lct.heatnet.restrictions.SpecialPassage(obstacle,13,17,1.25,c(0,15),90));
        var builder=new OutputBuilder(in);var output=builder.build(v);Set<Object> ids=new HashSet<>();
        for(var f:output) {
            assertFalse(in.inputIds().contains(JsonId.ofString(f.prop("id").toString())));
            assertTrue(ids.add(f.prop("id")));
        }
        var first=new java.io.ByteArrayOutputStream();var second=new java.io.ByteArrayOutputStream();
        new GeoJsonResultWriter().write(first,output);new GeoJsonResultWriter().write(second,builder.build(v));
        assertArrayEquals(first.toByteArray(),second.toByteArray());
    }
    @Test void nonConflictingInputKeepsLegacyIdsAndBytes() throws Exception {
        var in=new SyntheticInput().line("net",300,-10,0,10,0).point("cp",0,30,1).build();var v=variant(in);
        var fs=new OutputBuilder(in).build(v);
        assertEquals(Arrays.asList("v1_net_1","v1_chamber_1","v1_summary"),
                fs.stream().map(f->f.prop("id")).collect(java.util.stream.Collectors.toList()));
        var first=new java.io.ByteArrayOutputStream();var second=new java.io.ByteArrayOutputStream();
        new GeoJsonResultWriter().write(first,fs);new GeoJsonResultWriter().write(second,new OutputBuilder().build(v));
        assertArrayEquals(first.toByteArray(),second.toByteArray());
    }
    @Test void parserReservesIdsOfIgnoredObjects() throws Exception {
        String json="{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":\"v1_net_1\",\"object_type\":\"extension\"},\"geometry\":null}]}";
        var in=new ru.lct.heatnet.geo.GeoJsonStreamReader(ru.lct.heatnet.geo.ParseOptions.defaults())
                .read(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(in.inputIds().contains(JsonId.ofString("v1_net_1")));
    }
    @Test void emptyDepthSummaryKeepsDeclaredMode() {
        var n=new NewNetwork();var v=new VariantResult("empty",n,Collections.emptyMap(),CostCalculator.summarize(n,Collections.emptyList()),Collections.emptyList());
        v.setDepthMode(true);v.setVariantId("v1");v.setRank(1);
        assertEquals("depth",JobRunner.summaries(new SolveResult(Collections.singletonList(v),new Diagnostics(),0)).get(0).mode);
    }
}
