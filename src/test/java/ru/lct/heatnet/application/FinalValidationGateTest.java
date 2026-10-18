package ru.lct.heatnet.application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.persistence.*;
import ru.lct.heatnet.validation.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FinalValidationGateTest {
    @TempDir Path dir;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void publicationRequiresSuccessfulFinalValidation(boolean valid) throws Exception {
        Path input=Files.writeString(dir.resolve("input.geojson"),
                "{\"type\":\"FeatureCollection\",\"features\":[" +
                "{\"type\":\"Feature\",\"properties\":{\"id\":\"net\",\"object_type\":\"heat_network\",\"diameter\":300},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.64,55.7],[37.641,55.7]]}}," +
                "{\"type\":\"Feature\",\"properties\":{\"id\":\"cp\",\"object_type\":\"oks_connection_point\",\"flow_tph\":1},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6405,55.7003]}}]}");
        JobEntity e=new JobEntity();e.setId("test");e.setStatus(JobEntity.Status.RUNNING);e.setInputPath(input.toString());
        e.setUnknownRestrictionPolicy("IGNORE");e.setMaxVariants(1);e.setMode("PLANAR");
        var repo=mock(JobRepository.class);when(repo.claimQueued(eq("test"),any())).thenReturn(1);
        when(repo.findById("test")).thenReturn(Optional.of(e));
        var props=new HeatnetProperties();props.setImprove(false);
        new JobRunner(repo,props) {
            @Override ValidationReport validateResult(InputModel in,List<OutputFeature> features) {
                assertFalse(features.isEmpty(),"successful strategies already returned output");
                return new ResultValidator(in).validate(valid ? features : Collections.emptyList());
            }
        }.run("test");
        assertEquals(valid ? JobEntity.Status.DONE : JobEntity.Status.FAILED,e.getStatus());
        assertEquals(valid,e.getResultPath()!=null);
        assertNotNull(e.getValidationJson());assertTrue(Files.exists(input));
        assertEquals(valid,Files.exists(dir.resolve("result.geojson")));
    }
}
