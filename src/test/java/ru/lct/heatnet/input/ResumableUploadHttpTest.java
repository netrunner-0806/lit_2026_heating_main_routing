package ru.lct.heatnet.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import ru.lct.heatnet.application.*;
import ru.lct.heatnet.persistence.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Tomcat/HTTP binary requests; mock only the expensive calculation after durable handoff. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:resumable-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ResumableUploadHttpTest {
    private static final Path ROOT=temp();
    @DynamicPropertySource static void paths(DynamicPropertyRegistry r){r.add("heatnet.storage-dir",()->ROOT.toString());}
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JobRepository jobs;
    @MockBean JobRunner runner;
    private final String token="ef".repeat(32);
    private static Path temp(){try{return Files.createTempDirectory("heatnet-resumable-http-");}catch(Exception ex){throw new IllegalStateException(ex);}}
    private JsonNode call(String method,String path,byte[] body,String contentType,String hash,String secret,int expected)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://localhost:"+port+"/api/v1/uploads"+path).openConnection();
        c.setRequestMethod(method);c.setReadTimeout(10000);c.setRequestProperty("Content-Type",contentType);
        if(secret!=null)c.setRequestProperty("X-Upload-Token",secret);if(hash!=null)c.setRequestProperty("X-Part-SHA256",hash);
        try{
            if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);try(var out=c.getOutputStream()){out.write(body);}}
            assertEquals(expected,c.getResponseCode());
            if(expected==204)return null;
            try(var in=expected>=400?c.getErrorStream():c.getInputStream()){return json.readTree(in);}
        }finally{c.disconnect();}
    }
    private byte[] request(String id,long size)throws Exception{
        return json.writeValueAsBytes(Map.of("uploadId",id,"filename","../sample.geojson","totalSize",size,"fingerprint","ab".repeat(32)));
    }
    private static String sha(byte[] body)throws Exception{
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(body))s.append(String.format("%02x",b));return s.toString();
    }
    @Test void binaryProtocolOwnershipChecksumAndIdempotentHandoffThroughHttp()throws Exception{
        String id=UUID.randomUUID().toString();byte[] data="{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8);
        call("POST","",request(id,data.length),"application/json",null,token,201);
        call("GET","/"+id,null,"application/json",null,null,403);
        call("POST","/"+id+"/complete",null,"application/json",null,token,409);
        call("PUT","/"+id+"/parts/0",data,"application/octet-stream",sha(data),token,200);
        call("PUT","/"+id+"/parts/0",data,"application/octet-stream",sha(data),token,200);
        String job=call("POST","/"+id+"/complete",null,"application/json",null,token,202).path("jobId").asText();
        assertEquals(job,call("POST","/"+id+"/complete",null,"application/json",null,token,202).path("jobId").asText());
        assertArrayEquals(data,Files.readAllBytes(Path.of(jobs.findById(job).orElseThrow().getInputPath())));
        assertEquals(1,jobs.claimQueued(job,java.time.Instant.now()));
        assertEquals(0,jobs.claimQueued(job,java.time.Instant.now()),"duplicate queue delivery cannot run the job twice");
        assertEquals(0,jobs.rejectUploadQueue(job,java.time.Instant.now()),"a queue error cannot fail an already running job");
        call("DELETE","/"+id,null,"application/json",null,token,409);
    }
    @Test void excessiveFileAndMetadataRejectedBeforeCreatingJob()throws Exception{
        long count=jobs.count();String id=UUID.randomUUID().toString();
        call("POST","",request(id,3L*1024*1024*1024+1),"application/json",null,token,413);
        for(String path:new String[]{"","/",";probe=1","/"+id+"/complete"})
            call("POST",path,new byte[8193],"application/json",null,token,413);
        assertEquals(count,jobs.count());assertFalse(Files.exists(ROOT.resolve(".uploads").resolve(id)));
    }
    @AfterAll static void cleanup()throws Exception{
        try(var files=Files.walk(ROOT)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}
    }
}
