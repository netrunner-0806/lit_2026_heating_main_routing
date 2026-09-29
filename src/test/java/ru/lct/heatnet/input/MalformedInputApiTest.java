package ru.lct.heatnet.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * API contract for malformed uploads (PHASE 20): errors are controlled and readable, no stack trace reaches the
 * client, a failed job never exposes a half-created "successful" result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MalformedInputApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    static final String VALID = MalformedInputTest.fc(MalformedInputTest.NET, MalformedInputTest.POINT);

    private JsonNode submit(String body, String query, int expectedStatus) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "upload.geojson", "application/geo+json", body.getBytes(StandardCharsets.UTF_8));
        MvcResult r = mvc.perform(multipart("/api/v1/jobs" + query).file(file)).andReturn();
        assertEquals(expectedStatus, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode waitForJob(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = mvc.perform(get("/api/v1/jobs/" + id)).andReturn();
            assertEquals(200, r.getResponse().getStatus());
            JsonNode j = mapper.readTree(r.getResponse().getContentAsString());
            String st = j.path("status").asText();
            if ("DONE".equals(st) || "FAILED".equals(st)) return j;
            Thread.sleep(150);
        }
        throw new AssertionError("job " + id + " did not finish in time");
    }

    private static void assertReadable(String text) {
        assertNotNull(text);
        assertFalse(text.trim().isEmpty(), "message must not be empty");
        assertFalse(text.contains("\tat ") || text.contains("\n\tat"), "stack trace leaked to the client: " + text);
        assertFalse(text.matches("(?s).*\\bjava\\.lang\\.[A-Za-z]+(Exception|Error)\\b.*"), "internal exception class leaked: " + text);
        assertFalse(text.matches("(?s).*\\bru\\.lct\\.[a-z.]+[A-Za-z]+Exception\\b.*"), "internal exception class leaked: " + text);
    }

    private void assertNoHalfResult(String id) throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/jobs/" + id + "/result")).andReturn();
        assertEquals(200, res.getResponse().getStatus());
        JsonNode j = mapper.readTree(res.getResponse().getContentAsString());
        assertEquals("FAILED", j.path("status").asText());
        assertTrue(j.path("resultGeoJsonUrl").isNull(), "no result url for a failed job");
        assertTrue(j.path("variants").isNull() || j.path("variants").size() == 0, "no variants for a failed job");
        assertReadable(j.path("error").asText());
        MvcResult geo = mvc.perform(get("/api/v1/jobs/" + id + "/result.geojson")).andReturn();
        assertEquals(409, geo.getResponse().getStatus(), "a failed job has no downloadable result");
        assertReadable(mapper.readTree(geo.getResponse().getContentAsString()).path("message").asText());
        MvcResult rep = mvc.perform(get("/api/v1/jobs/" + id + "/report.json")).andReturn();
        assertTrue(rep.getResponse().getStatus() == 409 || rep.getResponse().getStatus() == 404, "report of a failed job must be a controlled 4xx, got " + rep.getResponse().getStatus());
        assertReadable(mapper.readTree(rep.getResponse().getContentAsString()).path("message").asText());
    }

    @Test
    void emptyUploadIsRejectedWith400() throws Exception {
        JsonNode j = submit("", "", 400);
        assertEquals(400, j.path("status").asInt());
        assertReadable(j.path("message").asText());
        assertTrue(j.path("message").asText().contains("Empty upload"), j.toString());
    }

    @Test
    void invalidModeIsRejectedWith400() throws Exception {
        JsonNode j = submit(VALID, "?mode=BOGUS", 400);
        assertReadable(j.path("message").asText());
        assertTrue(j.path("message").asText().contains("PLANAR"), j.toString());
    }

    @Test
    void invalidJsonJobFailsWithoutStackTraceOrHalfResult() throws Exception {
        JsonNode accepted = submit("{ \"type\": \"FeatureCollection\", \"features\": [ { not json", "", 202);
        String id = accepted.path("id").asText();
        JsonNode done = waitForJob(id);
        assertEquals("FAILED", done.path("status").asText());
        String error = done.path("error").asText();
        assertReadable(error);
        assertTrue(error.startsWith("Invalid input:"), error);
        assertNoHalfResult(id);
    }

    @Test
    void binaryGarbageFailsCleanly() throws Exception {
        byte[] junk = new byte[2048];
        new java.util.Random(7).nextBytes(junk);
        MockMultipartFile file = new MockMultipartFile("file", "junk.bin", "application/octet-stream", junk);
        MvcResult r = mvc.perform(multipart("/api/v1/jobs").file(file)).andReturn();
        assertEquals(202, r.getResponse().getStatus());
        String id = mapper.readTree(r.getResponse().getContentAsString()).path("id").asText();
        JsonNode done = waitForJob(id);
        assertEquals("FAILED", done.path("status").asText());
        assertReadable(done.path("error").asText());
        assertNoHalfResult(id);
    }

    @Test
    void notAFeatureCollectionFailsCleanly() throws Exception {
        String id = submit("[1, 2, 3]", "", 202).path("id").asText();
        JsonNode done = waitForJob(id);
        assertEquals("FAILED", done.path("status").asText());
        assertReadable(done.path("error").asText());
        assertNoHalfResult(id);
    }

    @Test
    void inputWithoutUsableObjectsIsRejectedWithDiagnostics() throws Exception {
        String id = submit(MalformedInputTest.fc(MalformedInputTest.NET), "", 202).path("id").asText();
        JsonNode done = waitForJob(id);
        assertEquals("FAILED", done.path("status").asText());
        String error = done.path("error").asText();
        assertReadable(error);
        assertTrue(error.startsWith("Input rejected:"), error);
        JsonNode diag = done.path("diagnostics");
        assertTrue(diag.isArray() && diag.size() > 0, "diagnostics must be returned: " + done);
        boolean noPoints = false;
        for (JsonNode d : diag) if ("NO_CONNECTION_POINTS".equals(d.path("code").asText())) noPoints = true;
        assertTrue(noPoints, diag.toString());
        assertNoHalfResult(id);
    }

    @Test
    void pointWithNegativeFlowIsRejectedNotSilentlyDropped() throws Exception {
        String bad = MalformedInputTest.feature("{\"id\":2,\"object_type\":\"oks_connection_point\",\"flow_tph\":-4}", "{\"type\":\"Point\",\"coordinates\":[37.606,55.753]}");
        String id = submit(MalformedInputTest.fc(MalformedInputTest.NET, MalformedInputTest.POINT, bad), "", 202).path("id").asText();
        JsonNode done = waitForJob(id);
        assertEquals("FAILED", done.path("status").asText(), "a consumer with an invalid flow cannot be ignored silently: " + done);
        assertReadable(done.path("error").asText());
    }

    @Test
    void validSmallInputStillSucceeds() throws Exception {
        String id = submit(VALID, "", 202).path("id").asText();
        JsonNode done = waitForJob(id);
        assertEquals("DONE", done.path("status").asText(), done.toString());
        MvcResult geo = mvc.perform(get("/api/v1/jobs/" + id + "/result.geojson")).andReturn();
        assertEquals(200, geo.getResponse().getStatus());
        JsonNode fc = mapper.readTree(geo.getResponse().getContentAsString());
        assertEquals("FeatureCollection", fc.path("type").asText());
        assertTrue(fc.path("features").size() > 0);
    }
}
