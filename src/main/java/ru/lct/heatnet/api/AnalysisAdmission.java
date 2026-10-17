package ru.lct.heatnet.api;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/** One bounded, materialising HTTP analysis at a time (shared by validation and reports).
 * Download endpoints remain streaming and do not use this permit. */
final class AnalysisAdmission implements AutoCloseable {
    private static final Semaphore PERMIT = new Semaphore(1);
    static AnalysisAdmission acquire() {
        if (!PERMIT.tryAcquire()) throw new RejectedExecutionException("HTTP analysis is busy");
        return new AnalysisAdmission();
    }
    private AnalysisAdmission() { }
    static long outputBytes() { return Math.max(65536, Runtime.getRuntime().maxMemory() / 64); }
    static ru.lct.heatnet.geo.ParseOptions inputOptions(ru.lct.heatnet.geo.ParseOptions.UnknownRestrictionPolicy policy) {
        long heap=Runtime.getRuntime().maxMemory();
        return new ru.lct.heatnet.geo.ParseOptions(policy,heap/8,Math.max(65536,Math.min(16L<<20,heap/256)));
    }
    @Override public void close() { PERMIT.release(); }
}
