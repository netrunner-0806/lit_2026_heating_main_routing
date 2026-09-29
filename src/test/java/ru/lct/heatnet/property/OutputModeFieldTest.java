package ru.lct.heatnet.property;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.testkit.SolveKit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-2 (found by the random property, seed 3999999999): the variant_summary "mode" is derived from the presence
 * of depth profiles on the links, so a DEPTH run in which nothing could be connected (no links) reports
 * mode = "planar" — the job says DEPTH, the result says planar, and the depth summary fields are missing.
 *
 * <p>Expected: the mode of the run is a property of the run, not of the links. Fix verified: VariantResult carries
 * depthMode (set by HeatnetSolver from the config) and OutputBuilder uses it.
 */
class OutputModeFieldTest {
    @Test
    void depthRunWithoutConnectionsStillReportsDepthMode() {
        RandomScene.Scene scene = RandomScene.generate(3999999999L);
        SolveResult r = SolveKit.solve(scene.input, true);
        assertTrue(!r.variants().isEmpty() && r.variants().get(0).connectedCount() == 0, "scene where the only consumer is unreachable: " + scene.description);
        for (OutputFeature f : SolveKit.render(r)) {
            if ("variant_summary".equals(f.objectType())) assertEquals("depth", f.prop("mode"), "mode of a DEPTH run");
        }
    }
}
