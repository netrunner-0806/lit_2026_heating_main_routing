package ru.lct.heatnet.determinism;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.testkit.SolveKit;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Determinism (phase 9): the same input solved repeatedly must give byte-for-byte the same canonical output
 * (connected sets, scores, costs, ranks, strategies, geometry, generated ids). No normalisation of generated ids
 * is needed because they are sequential and deterministic; the strict comparison covers them too.
 */
class DeterminismTest {

    private static InputModel fixture(String name) throws Exception {
        try (InputStream in = DeterminismTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, name);
            return new GeoJsonStreamReader(ParseOptions.defaults()).read(in);
        }
    }

    private static String run(InputModel m, boolean depth, StringBuilder digest) {
        SolveResult r = SolveKit.solve(m, depth);
        List<OutputFeature> fs = SolveKit.render(r);
        for (VariantResult v : r.variants()) {
            List<String> connected = new ArrayList<>();
            v.network().connectionNodes().forEach(n -> connected.add(n.connectionPoint().id().text()));
            java.util.Collections.sort(connected);
            digest.append(String.format(Locale.ROOT, "%s rank=%d strategy=%s score=%.6f cost=%.2f length=%.4f connected=%s unconnected=%s\n",
                    v.variantId(), v.rank(), v.strategy(), v.summary().score(), v.summary().calculatedCost(), v.summary().newLength(), connected,
                    v.unconnected().keySet().stream().map(cp -> cp.id().text()).sorted().collect(java.util.stream.Collectors.toList())));
        }
        return SolveKit.canonical(fs, false);
    }

    private static void assertDeterministic(String label, InputModel m, boolean depth, int runs) {
        String first = null, firstDigest = null;
        for (int i = 0; i < runs; i++) {
            StringBuilder digest = new StringBuilder();
            String canon = run(m, depth, digest);
            if (first == null) { first = canon; firstDigest = digest.toString(); continue; }
            assertEquals(firstDigest, digest.toString(), label + ": variant digest differs on run " + (i + 1));
            assertEquals(first, canon, label + ": canonical output differs on run " + (i + 1));
        }
        assertNotNull(first);
        assertFalse(firstDigest.isEmpty(), label + ": produced variants");
    }

    @Test
    void syntheticBasicTenRuns() throws Exception {
        assertDeterministic("synthetic-basic PLANAR", fixture("synthetic-basic.geojson"), false, 10);
    }

    @Test
    void sharedTrunkTenRuns() throws Exception {
        assertDeterministic("synthetic-shared-trunk PLANAR", fixture("synthetic-shared-trunk.geojson"), false, 10);
    }

    @Test
    void depthDemoTenRunsPlanarAndDepth() throws Exception {
        InputModel m = fixture("depth-demo.geojson");
        assertDeterministic("depth-demo PLANAR", m, false, 10);
        assertDeterministic("depth-demo DEPTH", m, true, 10);
    }

    /** The competition dataset: 2 runs by default (≈35 s); -Dheatnet.determinism.corrected=3 for three, =0 to skip. */
    @Test
    void correctedDatasetRepeatedRuns() throws Exception {
        assumeTrue(TestData.correctedAvailable(), "corrected dataset not available");
        int runs = Integer.getInteger("heatnet.determinism.corrected", 2);
        assumeTrue(runs >= 2, "skipped by -Dheatnet.determinism.corrected");
        InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(TestData.CORRECTED);
        assertDeterministic("corrected dataset PLANAR", m, false, runs);
    }
}
