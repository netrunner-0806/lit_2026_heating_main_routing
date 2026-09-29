package ru.lct.heatnet.application;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Component;
import ru.lct.heatnet.persistence.JobEntity;
import ru.lct.heatnet.persistence.JobRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Reconciles interrupted work before readiness in this single-application deployment. Never re-enqueues it. */
@Component
public class JobRecovery implements InitializingBean {
    private final JobRepository repo;
    private final JobService jobs;
    private final MultipartProperties multipart;

    public JobRecovery(JobRepository repo, JobService jobs, MultipartProperties multipart) {
        this.repo = repo;
        this.jobs = jobs;
        this.multipart = multipart;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        Files.createDirectories(jobs.storageDir());
        if (multipart.getLocation() != null && !multipart.getLocation().isEmpty())
            Files.createDirectories(Paths.get(multipart.getLocation()));
        List<JobEntity.Status> active = Arrays.asList(JobEntity.Status.QUEUED, JobEntity.Status.RUNNING);
        List<JobEntity> stale;
        while (!(stale = repo.findTop100ByStatusIn(active)).isEmpty()) {
            for (JobEntity e : stale) {
                Path dir = jobs.storageDir().resolve(e.getId());
                Files.deleteIfExists(dir.resolve("input.part"));
                Files.deleteIfExists(dir.resolve("result.geojson"));
                Path input = dir.resolve("input.geojson");
                boolean complete = Files.isRegularFile(input) && Files.size(input) == e.getInputSize();
                if (!complete) {
                    Files.deleteIfExists(input);
                    Files.deleteIfExists(dir);
                }
                e.setInputPath(complete ? input.toString() : null);
                e.setResultPath(null);
                e.setSummaryJson(null);
                e.setValidationJson(null);
                e.setExplainJson(null);
                e.setMetricsJson(null);
                e.setVariantCount(0);
                e.setStatus(JobEntity.Status.FAILED);
                e.setProgress("failed");
                e.setFinishedAt(Instant.now());
                e.setError("Calculation interrupted by application restart. "
                        + (complete ? "The input is retained and can be submitted for rerun." : "Please upload the file again."));
                repo.saveAndFlush(e);
            }
        }
    }
}
