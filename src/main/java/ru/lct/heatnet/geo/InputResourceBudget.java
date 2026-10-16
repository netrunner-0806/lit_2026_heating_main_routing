package ru.lct.heatnet.geo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import ru.lct.heatnet.routing.ComplexityLimitException;

/** Per-read admission budget. Leaves heap for spatial indexes, overlays and search; never discards an obstacle. */
final class InputResourceBudget {
    private final long limit;
    private long estimated;
    InputResourceBudget(long limit) { this.limit = limit; }

    void charge(long bytes, String context) {
        if (bytes > limit - estimated)
            throw new ComplexityLimitException("input model budget " + limit + " bytes exhausted at " + context
                    + "; estimated retained model=" + (estimated + bytes) + "; input was not spatially truncated");
        estimated += bytes;
    }

    /** Bounds the DOM of a single Feature before Jackson can materialise an arbitrarily large object.
     * Jackson may already have prefetched 8 KiB. Exact consumed length is checked at the end as well. */
    static final class FeatureStream extends FilterInputStream {
        private final long limit;
        private long readBytes, start = -1;
        private int index;
        FeatureStream(InputStream in, long limit) { super(in); this.limit = limit; }
        void begin(long byteOffset, int featureIndex) { start = byteOffset; index = featureIndex; }
        void end(long byteOffset) {
            if (byteOffset - start > limit) throw exceeded();
            start = -1;
        }
        private ComplexityLimitException exceeded() {
            return new ComplexityLimitException("feature #" + index + " exceeds the single-feature parsing budget "
                    + limit + " bytes; this is a resource limit, not invalid geometry");
        }
        private long remaining() {
            long left = start < 0 ? Long.MAX_VALUE : limit + 8192 - (readBytes - start);
            if (left <= 0) throw exceeded();
            return left;
        }
        @Override public int read() throws IOException {
            remaining(); int value = in.read(); if (value >= 0) readBytes++; return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int n = in.read(b, off, (int)Math.min(len, remaining()));
            if (n > 0) readBytes += n;
            return n;
        }
    }
}
