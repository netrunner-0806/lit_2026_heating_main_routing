package ru.lct.heatnet.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import ru.lct.heatnet.persistence.JobEntity;
import ru.lct.heatnet.persistence.JobRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobRecoveryTest {
    @TempDir Path root;

    @Test void restartFailsStaleJobsRemovesPartialResultsAndRetainsOnlyCompleteInputs() throws Exception {
        JobRepository repo = mock(JobRepository.class);
        JobService jobs = mock(JobService.class);
        when(jobs.storageDir()).thenReturn(root);
        JobEntity queued = job("queued", JobEntity.Status.QUEUED);
        JobEntity running = job("running", JobEntity.Status.RUNNING);
        Path qdir = Files.createDirectories(root.resolve("queued"));
        Files.write(qdir.resolve("input.part"), new byte[2]);
        Path rdir = Files.createDirectories(root.resolve("running"));
        Files.write(rdir.resolve("input.geojson"), new byte[4]);
        Files.write(rdir.resolve("result.geojson"), new byte[]{1});
        running.setResultPath(rdir.resolve("result.geojson").toString());
        running.setSummaryJson("partial summary");
        when(repo.findTop100ByStatusIn(anyCollection())).thenReturn(Arrays.asList(queued, running)).thenReturn(Collections.emptyList());
        MultipartProperties props = new MultipartProperties();
        props.setLocation(root.resolve("spool").toString());
        new JobRecovery(repo, jobs, props).afterPropertiesSet();
        assertFalse(Files.exists(qdir));
        assertNull(queued.getInputPath());
        assertTrue(Files.exists(Path.of(running.getInputPath())));
        assertFalse(Files.exists(rdir.resolve("result.geojson")));
        assertNull(running.getResultPath());
        assertNull(running.getSummaryJson());
        for (JobEntity e : Arrays.asList(queued, running)) {
            assertEquals(JobEntity.Status.FAILED, e.getStatus());
            assertNotNull(e.getFinishedAt());
            assertTrue(e.getError().contains("restart"));
            verify(repo).saveAndFlush(e);
        }
        assertTrue(Files.isDirectory(root.resolve("spool")));
    }

    private JobEntity job(String id, JobEntity.Status status) {
        JobEntity e = new JobEntity();
        e.setId(id); e.setStatus(status); e.setInputSize(4);
        e.setInputPath(root.resolve(id).resolve("input.geojson").toString());
        return e;
    }
}
