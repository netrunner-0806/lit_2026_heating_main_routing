package ru.lct.heatnet.persistence;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/** Persistent state of a calculation job; large payloads (input, result GeoJSON) live on disk. */
@Entity
@Table(name = "jobs")
public class JobEntity {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    @Column(length = 512)
    private String originalFilename;
    private long inputSize;
    @Column(length = 1024)
    private String inputPath;
    @Column(length = 1024)
    private String resultPath;

    /** Calculation mode: PLANAR (2D) or DEPTH (2D + vertical profile), separate results and rankings. */
    @Column(length = 16)
    private String mode = "PLANAR";

    @Column(length = 32)
    private String unknownRestrictionPolicy;
    private int maxVariants;

    @Column(length = 512)
    private String progress;
    @Column(columnDefinition = "TEXT")
    private String error;
    @Column(columnDefinition = "TEXT")
    private String summaryJson;
    @Column(columnDefinition = "TEXT")
    private String diagnosticsJson;
    @Column(columnDefinition = "TEXT")
    private String validationJson;
    private long elapsedMs;
    @Column(columnDefinition = "TEXT")
    private String metricsJson;
    @Column(columnDefinition = "TEXT")
    private String explainJson;
    @Column(columnDefinition = "TEXT")
    private String inputStatsJson;
    private int featureCount;
    private int connectionPointCount;
    private int variantCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public long getInputSize() { return inputSize; }
    public void setInputSize(long inputSize) { this.inputSize = inputSize; }
    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }
    public String getResultPath() { return resultPath; }
    public void setResultPath(String resultPath) { this.resultPath = resultPath; }
    public String getMode() { return mode == null ? "PLANAR" : mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getUnknownRestrictionPolicy() { return unknownRestrictionPolicy; }
    public void setUnknownRestrictionPolicy(String unknownRestrictionPolicy) { this.unknownRestrictionPolicy = unknownRestrictionPolicy; }
    public int getMaxVariants() { return maxVariants; }
    public void setMaxVariants(int maxVariants) { this.maxVariants = maxVariants; }
    public String getProgress() { return progress; }
    public void setProgress(String progress) { this.progress = progress; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getDiagnosticsJson() { return diagnosticsJson; }
    public void setDiagnosticsJson(String diagnosticsJson) { this.diagnosticsJson = diagnosticsJson; }
    public String getValidationJson() { return validationJson; }
    public void setValidationJson(String validationJson) { this.validationJson = validationJson; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getExplainJson() { return explainJson; }
    public void setExplainJson(String explainJson) { this.explainJson = explainJson; }
    public String getInputStatsJson() { return inputStatsJson; }
    public void setInputStatsJson(String inputStatsJson) { this.inputStatsJson = inputStatsJson; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public int getFeatureCount() { return featureCount; }
    public void setFeatureCount(int featureCount) { this.featureCount = featureCount; }
    public int getConnectionPointCount() { return connectionPointCount; }
    public void setConnectionPointCount(int connectionPointCount) { this.connectionPointCount = connectionPointCount; }
    public int getVariantCount() { return variantCount; }
    public void setVariantCount(int variantCount) { this.variantCount = variantCount; }
}
