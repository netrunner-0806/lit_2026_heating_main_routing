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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    /** Streams the multipart upload straight to the job directory and queues the calculation. */
    public JobEntity submit(MultipartFile file, ParseOptions.UnknownRestrictionPolicy policy, Integer maxVariants, String mode) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Empty upload: field 'file' must contain a GeoJSON FeatureCollection");
        String id = UUID.randomUUID().toString();
        Path dir = storageDir().resolve(id);
        Files.createDirectories(dir);
        Path input = dir.resolve("input.geojson");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, input, StandardCopyOption.REPLACE_EXISTING);
        }
        return create(id, input, file.getOriginalFilename(), policy, maxVariants, mode);
    }

    /** Re-runs the input of an existing job in another mode (the uploaded file is kept on disk). */
    public JobEntity rerun(String sourceId, String mode) throws IOException {
        JobEntity src = repo.findById(sourceId).orElseThrow(() -> new ru.lct.heatnet.api.NotFoundException("job " + sourceId + " not found"));
        if (src.getInputPath() == null) throw new IllegalArgumentException("Job " + sourceId + " has no retained input (failed jobs are not rerunnable)");
        Path srcInput = Paths.get(src.getInputPath());
        if (!Files.exists(srcInput)) throw new ru.lct.heatnet.api.ConflictException("input of job " + sourceId + " is no longer available");
        String id = UUID.randomUUID().toString();
        Path dir = storageDir().resolve(id);
        Files.createDirectories(dir);
        Path input = dir.resolve("input.geojson");
        Files.copy(srcInput, input, StandardCopyOption.REPLACE_EXISTING);
        return create(id, input, src.getOriginalFilename(), ParseOptions.UnknownRestrictionPolicy.valueOf(src.getUnknownRestrictionPolicy()), src.getMaxVariants(), mode);
    }

    private JobEntity create(String id, Path input, String originalFilename, ParseOptions.UnknownRestrictionPolicy policy, Integer maxVariants, String mode) throws IOException {
        JobEntity e = new JobEntity();
        e.setId(id);
        e.setStatus(JobEntity.Status.QUEUED);
        e.setCreatedAt(Instant.now());
        e.setOriginalFilename(originalFilename);
        e.setInputSize(Files.size(input));
        e.setInputPath(input.toString());
        e.setMode(normalizeMode(mode));
        e.setUnknownRestrictionPolicy((policy == null ? ParseOptions.UnknownRestrictionPolicy.IGNORE : policy).name());
        e.setMaxVariants(maxVariants == null || maxVariants < 1 ? props.getMaxVariants() : Math.min(maxVariants, ru.lct.heatnet.config.Constants.MAX_VARIANTS));
        e.setProgress("queued");
        repo.save(e);
        executor.execute(() -> runner.run(id));
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
        Optional<JobEntity> e = repo.findById(id);
        if (!e.isPresent()) return;
        repo.deleteById(id);
        Path dir = storageDir().resolve(id);
        if (Files.exists(dir)) {
            try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) { } });
            }
        }
    }
}
