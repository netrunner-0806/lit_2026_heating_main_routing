package ru.lct.heatnet.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lct.heatnet.api.*;
import ru.lct.heatnet.application.*;
import ru.lct.heatnet.persistence.JobEntity;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HttpDataAuditTest {
    @TempDir Path dir;
    @ParameterizedTest @ValueSource(strings={
        "{\"type\":\"Point\"}", "{\"type\":\"Point\",\"coordinates\":[]}",
        "{\"type\":\"Point\",\"coordinates\":[37]}", "{\"type\":\"Point\",\"coordinates\":[\"x\",55]}",
        "{\"type\":\"Point\",\"coordinates\":[37,55,1]}",
        "{\"type\":\"LineString\",\"coordinates\":[]}", "{\"type\":\"LineString\",\"coordinates\":[[]]}",
        "{\"type\":\"Polygon\",\"coordinates\":[]}"})
    void malformedGeometryIsBadRequest(String geometry) throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new ValidationController(mock(JobRunner.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String input=MalformedInputTest.fc(MalformedInputTest.NET,MalformedInputTest.POINT);
        String result="{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":"+geometry+",\"properties\":{}}]}";
        mvc.perform(multipart("/api/v1/validate")
            .file(new MockMultipartFile("input","input.geojson","application/json",input.getBytes()))
            .file(new MockMultipartFile("result","result.geojson","application/json",result.getBytes())))
            .andExpect(status().isBadRequest());
    }
    @Test void uploadEnumDoesNotExposeJavaNames() throws Exception {
        var svc=new UploadService(mock(JobService.class),new MultipartProperties());
        var req=new UploadService.Request();req.uploadId=UUID.randomUUID().toString();req.totalSize=1;
        req.unknownRestrictionPolicy="X";
        RuntimeException ex=assertThrows(RuntimeException.class,()->svc.create(req,"a".repeat(64)));
        var handler=new ApiExceptionHandler();
        String body=(ex instanceof UploadException ? handler.upload((UploadException)ex) : handler.badRequest((IllegalArgumentException)ex)).getBody().toString();
        assertFalse(body.contains("ru.lct"),body);assertTrue(body.contains("IGNORE"));assertTrue(body.contains("FORBIDDEN"));
    }
    @ParameterizedTest @ValueSource(strings={"=1+1","+SUM(1)","-1+1","@SUM(1)"," \t\r=1+1","\u0001@SUM(1)","\uFEFF=1+1"})
    void csvNeutralizesFormulaCellsWithoutChangingNumericValues(String payload) throws Exception {
        var props=new LinkedHashMap<String,Object>();props.put("object_type","heat_network");props.put("variant_id","v1");
        props.put("id","normal-id");props.put("start_node_id",payload);props.put("end_node_id","quoted;\"id\nnext");props.put("flow_tph",-1.25);
        Path file=dir.resolve("result.geojson");
        new ObjectMapper().writeValue(file.toFile(),Map.of("type","FeatureCollection","features",List.of(Map.of("type","Feature","geometry",Map.of("type","LineString","coordinates",List.of(List.of(37,55),List.of(37.1,55))),"properties",props))));
        var job=new JobEntity();job.setStatus(JobEntity.Status.DONE);job.setResultPath(file.toString());
        var jobs=mock(JobService.class);when(jobs.find("j")).thenReturn(Optional.of(job));
        String csv=new ReportController(jobs).reportCsv("j").getBody();
        assertTrue(csv.contains("'"+payload),csv);
        assertTrue(csv.contains(";normal-id;"));assertTrue(csv.contains(";-1.25;"));
        assertTrue(csv.contains("\"quoted;\"\"id\nnext\""),csv);
    }
}
