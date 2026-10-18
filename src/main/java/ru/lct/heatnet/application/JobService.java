package ru.lct.heatnet.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.api.dto.JobDto;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.persistence.JobEntity;
import ru.lct.heatnet.persistence.JobRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Job lifecycle: upload to disk, queue, run asynchronously, expose results. The REST layer never touches the solver. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repo;
    private final JobRunner runner;
    private final HeatnetProperties props;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobService(JobRepository repo, JobRunner runner, HeatnetProperties props, @Qualifier("jobExecutor") ThreadPoolTaskExecutor executor) {
        this.repo = repo;
        this.runner = runner;
        this.props = props;
        this.executor = executor;
    }

    public Path storageDir() { return Paths.get(props.getStorageDir()).toAbsolutePath(); }

    /** A completed resumable upload has a stable job id. Hard-link publication costs no second file copy.
     * The caller serializes a session. Duplicate dispatch is safe because JobRunner claims QUEUED atomically.
     * Neither DB failure nor queue rejection deletes the verified upload. */
    public JobEntity submitCompleted(String id, String filename, long size, Path verifiedInput,
                                     ParseOptions.UnknownRestrictionPolicy policy, int maxVariants, String mode) throws IOException {
        maxVariants = variantCount(maxVariants);
        JobEntity e = repo.findById(id).orElse(null);
        if (e != null && e.getStatus() != JobEntity.Status.QUEUED
                && !"upload-queue-retry".equals(e.getProgress())) return e;
        if (e == null) {
            Path dir = storageDir().resolve(id);
            Files.createDirectories(dir);
            Path input = dir.resolve("input.geojson");
            if (!Files.exists(input)) Files.createLink(input, verifiedInput);
            if (!Files.isSameFile(input, verifiedInput) || Files.size(input) != size)
                throw new IOException("Verified upload publication conflict");
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(storageDir(), java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
            e = new JobEntity(); e.setId(id); e.setCreatedAt(Instant.now());
            e.setOriginalFilename(filename); e.setInputSize(size); e.setInputPath(input.toString());
            e.setMode(normalizeMode(mode)); e.setMaxVariants(maxVariants);
            e.setUnknownRestrictionPolicy(policy.name());
        }
        if (e.getStatus() != JobEntity.Status.QUEUED) {
            e.setStatus(JobEntity.Status.QUEUED); e.setProgress("queued");
            e.setError(null); e.setFinishedAt(null);
            repo.saveAndFlush(e);
        }
        try { executor.execute(() -> runner.run(id)); }
        catch (org.springframework.core.task.TaskRejectedException ex) {
            repo.rejectUploadQueue(id, Instant.now());
            throw new ru.lct.heatnet.api.UploadException(503, "Calculation queue is full; upload retained, retry complete");
        }
        return repo.findById(id).orElse(e);
    }

    /** Register the received upload before persistence; the servlet provider can move its disk spool. */
    public JobEntity submit(MultipartFile file, ParseOptions.UnknownRestrictionPolicy policy, Integer maxVariants, String mode) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Empty upload: field 'file' must contain a GeoJSON FeatureCollection");
        String normalized = normalizeMode(mode);
        return persistAndQueue(file.getOriginalFilename(), file.getSize(), policy, maxVariants, normalized,
                partial -> file.transferTo(partial.toFile()));
    }

    /** Re-runs the input of an existing job in another mode (the uploaded file is kept on disk). */
    public JobEntity rerun(String sourceId, String mode) throws IOException {
        String normalized = normalizeMode(mode);
        JobEntity src = repo.findById(sourceId).orElseThrow(() -> new ru.lct.heatnet.api.NotFoundException("job " + sourceId + " not found"));
        if (src.getInputPath() == null) throw new IllegalArgumentException("Job " + sourceId + " has no retained input");
        Path srcInput = Paths.get(src.getInputPath());
        if (!Files.exists(srcInput)) throw new ru.lct.heatnet.api.ConflictException("input of job " + sourceId + " is no longer available");
        return persistAndQueue(src.getOriginalFilename(), Files.size(srcInput),
                ParseOptions.UnknownRestrictionPolicy.valueOf(src.getUnknownRestrictionPolicy()), Math.max(1, Math.min(ru.lct.heatnet.config.Constants.MAX_VARIANTS, src.getMaxVariants())), normalized,
                partial -> Files.copy(srcInput, partial));
    }

    private int variantCount(Integer requested) {
        int maximum = ru.lct.heatnet.config.Constants.MAX_VARIANTS;
        if (requested != null && (requested < 1 || requested > maximum))
            throw new IllegalArgumentException("maxVariants must be 1.." + maximum);
        return requested == null ? Math.max(1, Math.min(maximum, props.getMaxVariants())) : requested;
    }

    @FunctionalInterface
    private interface UploadWriter { void write(Path partial) throws IOException; }

    private JobEntity persistAndQueue(String originalFilename, long size, ParseOptions.UnknownRestrictionPolicy policy,
                                     Integer maxVariants, String mode, UploadWriter writer) throws IOException {
        int count = variantCount(maxVariants);
        String id = UUID.randomUUID().toString();
        Path dir = storageDir().resolve(id);
        Path input = dir.resolve("input.geojson");
        Path partial = dir.resolve("input.part");
        JobEntity e = new JobEntity();
        e.setId(id);
        e.setStatus(JobEntity.Status.QUEUED);
        e.setCreatedAt(Instant.now());
        e.setOriginalFilename(originalFilename);
        e.setInputSize(size);
        e.setInputPath(input.toString());
        e.setMode(mode);
        e.setUnknownRestrictionPolicy((policy == null ? ParseOptions.UnknownRestrictionPolicy.IGNORE : policy).name());
        e.setMaxVariants(count);
        e.setProgress("Saving upload");
        try {
            // Commit the record first: a crash during persistence leaves a recoverable job, not an orphan file.
            repo.saveAndFlush(e);
            Files.createDirectories(dir);
            writer.write(partial);
            if (Files.size(partial) != size) throw new IOException("Incomplete upload: saved size differs from received size");
            try {
                Files.move(partial, input, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(partial, input);
            }
            e.setProgress("queued");
            repo.saveAndFlush(e);
            executor.execute(() -> runner.run(id));
        } catch (IOException | RuntimeException ex) {
            boolean cleaned = false;
            try {
                Files.deleteIfExists(partial);
                Files.deleteIfExists(input);
                Files.deleteIfExists(dir);
                cleaned = true;
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
                log.error("Could not clean failed upload {}", id, cleanup);
            }
            try {
                Optional<JobEntity> saved = repo.findById(id);
                if (saved.isPresent()) {
                    JobEntity failed = saved.get();
                    failed.setStatus(JobEntity.Status.FAILED);
                    failed.setProgress("failed");
                    failed.setError("Upload could not be saved or scheduled. Please retry.");
                    failed.setFinishedAt(Instant.now());
                    if (cleaned) failed.setInputPath(null);
                    repo.saveAndFlush(failed);
                }
            } catch (RuntimeException cleanup) {
                // If the DB is unavailable, startup recovery handles the committed QUEUED record.
                ex.addSuppressed(cleanup);
                log.error("Could not mark failed upload {}; startup recovery will reconcile it", id, cleanup);
            }
            throw ex;
        }
        log.info("job {} queued ({} bytes, {}, mode {})", id, e.getInputSize(), originalFilename, e.getMode());
        return e;
    }

    public static String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return "PLANAR";
        String m = mode.trim().toUpperCase();
        if (!m.equals("PLANAR") && !m.equals("DEPTH")) throw new IllegalArgumentException("mode must be PLANAR or DEPTH");
        return m;
    }

    public Optional<JobEntity> find(String id) { return repo.findById(id); }

    public List<JobDto> list() {
        List<JobDto> out = new ArrayList<>();
        for (JobEntity e : repo.findTop50ByOrderByCreatedAtDesc()) out.add(JobDto.of(e, null));
        return out;
    }

    public JobDto toDto(JobEntity e) {
        JobDto d = JobDto.of(e, parse(e.getDiagnosticsJson()));
        d.inputStats = parse(e.getInputStatsJson());
        return d;
    }

    public JsonNode parse(String json) {
        if (json == null) return null;
        try { return mapper.readTree(json); } catch (IOException ex) { return null; }
    }

    public void delete(String id) throws IOException {
        // The status predicate and deletion execute atomically in the database. In particular,
        // a worker's QUEUED -> RUNNING claim cannot race a read-then-delete operation.
        int removed = repo.deleteTerminal(id, java.util.Arrays.asList(JobEntity.Status.DONE, JobEntity.Status.FAILED));
        if (removed == 0) {
            if (repo.existsById(id)) throw new ru.lct.heatnet.api.ConflictException("Cannot delete a QUEUED or RUNNING job; wait for completion");
            return;
        }
        Path dir = storageDir().resolve(id);
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) { } });
            }
        }
    }
}
