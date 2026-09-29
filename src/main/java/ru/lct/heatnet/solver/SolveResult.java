package ru.lct.heatnet.solver;

import ru.lct.heatnet.domain.Diagnostics;

import java.util.Collections;
import java.util.List;

/** Ranked, validated variants of one job. */
public final class SolveResult {
    private final List<VariantResult> variants;
    private final Diagnostics diagnostics;
    private final long elapsedMs;
    private final java.util.Map<String, Object> metrics;

    public SolveResult(List<VariantResult> variants, Diagnostics diagnostics, long elapsedMs) { this(variants, diagnostics, elapsedMs, new java.util.LinkedHashMap<>()); }

    public SolveResult(List<VariantResult> variants, Diagnostics diagnostics, long elapsedMs, java.util.Map<String, Object> metrics) {
        this.variants = Collections.unmodifiableList(variants);
        this.diagnostics = diagnostics;
        this.elapsedMs = elapsedMs;
        this.metrics = metrics;
    }

    /** Performance metrics: graph size, candidates, variants evaluated, timings. */
    public java.util.Map<String, Object> metrics() { return metrics; }

    public List<VariantResult> variants() { return variants; }
    public Diagnostics diagnostics() { return diagnostics; }
    public long elapsedMs() { return elapsedMs; }
}
