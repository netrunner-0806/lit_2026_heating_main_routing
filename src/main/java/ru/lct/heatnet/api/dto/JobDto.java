package ru.lct.heatnet.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lct.heatnet.persistence.JobEntity;

import java.time.Instant;

/** Public view of a job. */
public class JobDto {
    public String id;
    public String status;
    public Instant createdAt;
    public Instant startedAt;
    public Instant finishedAt;
    public String originalFilename;
    public long inputSize;
    public String progress;
    public String error;
    public long elapsedMs;
    public int featureCount;
    public int connectionPointCount;
    public int variantCount;
    public String unknownRestrictionPolicy;
    public int maxVariants;
    public String mode;
    public JsonNode diagnostics;
    public JsonNode inputStats;
    public String resultUrl;
    public String resultGeoJsonUrl;

    public static JobDto of(JobEntity e, JsonNode diagnostics) {
        JobDto d = new JobDto();
        d.id = e.getId();
        d.status = e.getStatus().name();
        d.createdAt = e.getCreatedAt();
        d.startedAt = e.getStartedAt();
        d.finishedAt = e.getFinishedAt();
        d.originalFilename = e.getOriginalFilename();
        d.inputSize = e.getInputSize();
        d.progress = e.getProgress();
        d.error = e.getError();
        d.elapsedMs = e.getElapsedMs();
        d.featureCount = e.getFeatureCount();
        d.connectionPointCount = e.getConnectionPointCount();
        d.variantCount = e.getVariantCount();
        d.unknownRestrictionPolicy = e.getUnknownRestrictionPolicy();
        d.maxVariants = e.getMaxVariants();
        d.mode = e.getMode();
        d.diagnostics = diagnostics;
        if (e.getStatus() == JobEntity.Status.DONE) {
            d.resultUrl = "/api/v1/jobs/" + e.getId() + "/result";
            d.resultGeoJsonUrl = "/api/v1/jobs/" + e.getId() + "/result.geojson";
        }
        return d;
    }
}
