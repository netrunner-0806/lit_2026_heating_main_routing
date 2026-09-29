package ru.lct.heatnet.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import ru.lct.heatnet.api.JobController;
import ru.lct.heatnet.application.JobService;
import ru.lct.heatnet.persistence.JobEntity;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InputPreviewTest {
    @TempDir Path root;

    @Test void smallInputRemainsAvailableForMap() throws Exception {
        byte[] data = "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8);
        Path input = Files.write(root.resolve("input.geojson"), data);
        ResponseEntity<Resource> response = controller(input, data.length).inputPreview("job");
        assertEquals(200, response.getStatusCodeValue());
        try (java.io.InputStream in = response.getBody().getInputStream()) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test void giantInputDoesNotReturnAnyBody() throws Exception {
        ResponseEntity<Resource> response = controller(root.resolve("not-opened"), 3L * 1024 * 1024 * 1024).inputPreview("job");
        assertEquals(204, response.getStatusCodeValue());
        assertNull(response.getBody());
    }

    @Test void actualFileSizeIsCheckedEvenWhenMetadataIsWrong() throws Exception {
        Path input = root.resolve("large.geojson");
        try (RandomAccessFile f = new RandomAccessFile(input.toFile(), "rw")) {
            f.setLength(JobController.MAX_INPUT_PREVIEW_BYTES + 1);
        }
        assertEquals(204, controller(input, 1).inputPreview("job").getStatusCodeValue());
    }

    private JobController controller(Path input, long size) {
        JobService jobs = mock(JobService.class);
        JobEntity e = new JobEntity();
        e.setId("job"); e.setInputSize(size); e.setInputPath(input.toString());
        when(jobs.find("job")).thenReturn(Optional.of(e));
        return new JobController(jobs);
    }
}
