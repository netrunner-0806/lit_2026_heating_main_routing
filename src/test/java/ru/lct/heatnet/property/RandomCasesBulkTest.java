package ru.lct.heatnet.property;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.solver.SolveResult;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bulk random run (phase 8): N seeded scenes in PLANAR (every third one also in DEPTH), all invariants checked.
 * Count: -Dheatnet.random.cases=N (default 150, ~40 s); base seed: -Dheatnet.random.base=S (default 1).
 * Every seed is reproducible with RandomCaseReplayTest.
 */
class RandomCasesBulkTest {

    @Test
    void bulkRandomScenes() {
        int n = Integer.getInteger("heatnet.random.cases", 150);
        long base = Long.getLong("heatnet.random.base", 1L);
        long t0 = System.currentTimeMillis();
        int variants = 0, empty = 0, unconnected = 0, depthRuns = 0;
        StringBuilder failures = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long seed = base + i;
            RandomScene.Scene scene = RandomScene.generate(seed);
            try {
                SolveResult r = Invariants.check(scene, false);
                if (r.variants().isEmpty()) empty++; else { variants += r.variants().size(); unconnected += r.variants().get(0).unconnected().size(); }
                if (i % 3 == 0) { depthRuns++; Invariants.check(scene, true); }
            } catch (AssertionError e) {
                failures.append(e.getMessage()).append('\n');
            }
        }
        System.out.println(String.format(Locale.ROOT, "random scenes: %d planar (%d with depth), variants %d, no-variant cases %d, unconnected consumers in best variants %d, %.1f s",
                n, depthRuns, variants, empty, unconnected, (System.currentTimeMillis() - t0) / 1000.0));
        if (failures.length() > 0) fail("random cases failed:\n" + failures);
    }
}
