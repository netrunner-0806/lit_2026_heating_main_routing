package ru.lct.heatnet.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.lct.heatnet.application.JobRunner;
import ru.lct.heatnet.application.JobService;
import ru.lct.heatnet.persistence.JobRepository;

import javax.servlet.MultipartConfigElement;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/** Real embedded Tomcat, HTTP and disk spool. MockMvc multipart requests do not enforce servlet limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:multipart-limits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultipartLimitIntegrationTest {
    private static final long THREE_GIB = 3L * 1024 * 1024 * 1024;
    private static final Path ROOT = tempRoot();
    @LocalServerPort int port;
    @Autowired MultipartConfigElement config;
    @Autowired JobService jobs;
    @Autowired JobRepository repo;
    @Autowired ObjectMapper mapper;
    @MockBean JobRunner runner; // exercise upload/persistence, not solver performance on 3 GiB of padding

    @DynamicPropertySource static void paths(DynamicPropertyRegistry r) {
        r.add("heatnet.storage-dir", () -> ROOT.resolve("jobs").toString());
        r.add("spring.servlet.multipart.location", () -> ROOT.resolve("spool").toString());
    }

    @Test void acceptsFileAtThreeGiBLimitIncludingMultipartOverhead() throws Exception {
        assertEquals(THREE_GIB, config.getMaxFileSize());
        assertEquals(4L * 1024 * 1024 * 1024, config.getMaxRequestSize());
        JsonNode response = upload(THREE_GIB, 202);
        String id = response.path("id").asText();
        assertEquals(THREE_GIB, response.path("inputSize").asLong());
        Path input = Path.of(jobs.find(id).orElseThrow().getInputPath());
        assertEquals(THREE_GIB, Files.size(input));
        assertFalse(Files.exists(input.resolveSibling("input.part")));
        jobs.delete(id);
    }

    @Test void rejectsFileOneByteAboveLimitWithoutCreatingJob() throws Exception {
        long before = repo.count();
        upload(THREE_GIB + 1, 413);
        assertEquals(before, repo.count());
    }

    private JsonNode upload(long bytes, int expected) throws Exception {
        String boundary = "lct-multipart-boundary";
        byte[] start = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.geojson\"\r\n"
                + "Content-Type: application/geo+json\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + port + "/api/v1/jobs").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(10_000); c.setReadTimeout(180_000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setFixedLengthStreamingMode(bytes + start.length + end.length);
        IOException writeFailure = null;
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(start);
                byte[] block = new byte[1024 * 1024];
                java.util.Arrays.fill(block, (byte) ' ');
                for (long left = bytes; left > 0; ) {
                    int n = (int) Math.min(left, block.length);
                    out.write(block, 0, n); left -= n;
                }
                out.write(end);
            } catch (IOException ex) { writeFailure = ex; }
            int status = c.getResponseCode();
            assertEquals(expected, status, "HTTP status; write failure=" + writeFailure);
            if (expected == 202 && writeFailure != null) throw writeFailure;
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                return mapper.readTree(in);
            }
        } finally { c.disconnect(); }
    }

    private static Path tempRoot() {
        try { return Files.createTempDirectory("heatnet-multipart-limits-"); }
        catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    @AfterAll static void cleanup() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
}
