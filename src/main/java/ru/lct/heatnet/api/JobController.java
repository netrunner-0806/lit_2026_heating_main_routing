package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.api.dto.JobDto;
import ru.lct.heatnet.application.JobService;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.persistence.JobEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Загрузка GeoJSON, запуск расчёта, статус, результаты")
public class JobController {

    private final JobService jobs;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobController(JobService jobs) { this.jobs = jobs; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить входной GeoJSON и запустить расчёт",
            description = "Файл потоково сохраняется во временное хранилище (поддерживаются файлы до 3 ГБ), задание ставится в очередь и обрабатывается асинхронно. Ответ 202 содержит id задания.")
    public ResponseEntity<JobDto> submit(
            @Parameter(description = "GeoJSON FeatureCollection в WGS84") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Политика для неизвестных restriction_type: IGNORE (по умолчанию) или FORBIDDEN")
            @RequestParam(value = "unknownRestrictionPolicy", required = false) ParseOptions.UnknownRestrictionPolicy policy,
            @Parameter(description = "Максимум вариантов (1..3)") @RequestParam(value = "maxVariants", required = false) Integer maxVariants,
            @Parameter(description = "Режим расчёта: PLANAR (2D, по умолчанию) или DEPTH (2D + вертикальный профиль, отдельный расчёт и ранжирование)")
            @RequestParam(value = "mode", required = false) String mode) throws IOException {
        JobEntity e = jobs.submit(file, policy, maxVariants, mode);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.toDto(e));
    }

    @PostMapping("/{id}/rerun")
    @Operation(summary = "Повторить расчёт того же входного файла в другом режиме (PLANAR / DEPTH)")
    public ResponseEntity<JobDto> rerun(@PathVariable String id, @RequestParam("mode") String mode) throws IOException {
        JobEntity e = jobs.rerun(id, mode);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.toDto(e));
    }

    @GetMapping("/{id}/explain")
    @Operation(summary = "Объяснение решений расчёта (выбор врезок, маршрутов, диаметров, глубин) по вариантам")
    public JsonNode explain(@PathVariable String id) {
        JobEntity e = jobs.find(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
        ObjectNode n = mapper.createObjectNode();
        n.put("jobId", e.getId());
        n.put("mode", e.getMode());
        n.set("decisions", jobs.parse(e.getExplainJson()));
        return n;
    }

    @GetMapping
    @Operation(summary = "Список последних заданий")
    public List<JobDto> list() { return jobs.list(); }

    @GetMapping("/{id}")
    @Operation(summary = "Статус обработки задания")
    public JobDto status(@PathVariable String id) {
        return jobs.toDto(jobs.find(id).orElseThrow(() -> new NotFoundException("job " + id + " not found")));
    }

    @GetMapping("/{id}/result")
    @Operation(summary = "Результат: сводки вариантов, диагностика входа, отчёт валидатора")
    public JsonNode result(@PathVariable String id) {
        JobEntity e = jobs.find(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
        ObjectNode n = mapper.createObjectNode();
        n.put("jobId", e.getId());
        n.put("status", e.getStatus().name());
        n.put("mode", e.getMode());
        n.put("error", e.getError());
        n.put("elapsedMs", e.getElapsedMs());
        n.set("metrics", jobs.parse(e.getMetricsJson()));
        n.set("inputStats", jobs.parse(e.getInputStatsJson()));
        n.set("variants", jobs.parse(e.getSummaryJson()));
        n.set("diagnostics", jobs.parse(e.getDiagnosticsJson()));
        n.set("validation", jobs.parse(e.getValidationJson()));
        n.put("resultGeoJsonUrl", e.getStatus() == JobEntity.Status.DONE ? "/api/v1/jobs/" + id + "/result.geojson" : null);
        return n;
    }

    @GetMapping(value = "/{id}/result.geojson")
    @Operation(summary = "Скачать результат GeoJSON (WGS84, все варианты в одном файле)")
    public ResponseEntity<Resource> resultGeoJson(@PathVariable String id) throws IOException {
        JobEntity e = jobs.find(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
        if (e.getStatus() != JobEntity.Status.DONE || e.getResultPath() == null)
            throw new ConflictException("job " + id + " has no result yet (status " + e.getStatus() + ")");
        return file(Paths.get(e.getResultPath()), "result-" + id + ".geojson");
    }

    @GetMapping(value = "/{id}/input.geojson")
    @Operation(summary = "Скачать загруженный входной GeoJSON (для отображения на карте)")
    public ResponseEntity<Resource> inputGeoJson(@PathVariable String id) throws IOException {
        JobEntity e = jobs.find(id).orElseThrow(() -> new NotFoundException("job " + id + " not found"));
        if (e.getInputPath() == null) throw new NotFoundException("Input of job " + id + " is not retained (failed jobs keep only metadata and diagnostics)");
        return file(Paths.get(e.getInputPath()), "input-" + id + ".geojson");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить задание и его файлы")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        jobs.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Resource> file(Path path, String name) throws IOException {
        if (!Files.exists(path)) throw new NotFoundException("file not found");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }
}
