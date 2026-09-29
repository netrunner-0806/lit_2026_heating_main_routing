package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.depth.CrossingDepthOption;
import ru.lct.heatnet.depth.DepthInterval;
import ru.lct.heatnet.depth.DepthProfileOptimizer;
import ru.lct.heatnet.depth.VerticalObstacle;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;

/**
 * Rule: annex 5 — «минимальная [глубина] – 0,7 м» (top of the new envelope) → h ≥ 0.700 admissible.
 * <pre>
 * depth (optimizer, continuous) | 0.699999 | 0.700000 | 0.700001
 * ABOVE option admissible       | no       | yes      | yes
 * depth (validator, 3 decimals) | 0.699    | 0.700    | 0.701
 * DEPTH_MIN                     | error    | ok       | ok
 * </pre>
 * The validator is tested at the output resolution (depths are reported with 3 decimals).
 */
class DepthMinimumBoundaryTest {

    private static final double[] H = {0.699999, 0.700000, 0.700001};

    private static VerticalObstacle gas(double hNew) {
        return new VerticalObstacle(RestrictionRules.GAS_PIPELINE, "gas", DepthRules.forType(RestrictionRules.GAS_PIPELINE).get(), 50, 54, 0.40, 1.25, hNew);
    }

    @Test
    void intervalContainsTheMinimum() {
        DepthInterval free = new DepthInterval(DepthRules.MIN_DEPTH_M, DepthRules.SANITY_MAX_DEPTH_M);
        assertFalse(free.contains(H[0]));
        assertTrue(free.contains(H[1]));
        assertTrue(free.contains(H[2]));
    }

    @Test
    void aboveOptionExistsOnlyWhenTheTopStaysAtLeastZeroPointSeven() {
        for (double hi : H) {
            double hNew = 2.8 - 0.2 - hi;   // ABOVE the gas pipe puts the top of the new envelope at exactly hi
            VerticalObstacle g = gas(hNew);
            assertEquals(hi, g.above().hi, 1e-12);
            assertEquals(hi >= DepthRules.MIN_DEPTH_M, !g.above().isEmpty(), "hi=" + hi);
            DepthProfileOptimizer.Result r = new DepthProfileOptimizer().optimize(100, Collections.singletonList(g), null, 3.0, s -> 100_000);
            assertTrue(r.feasible, "BELOW is always possible");
            CrossingDepthOption.Method m = r.profile.decisions().get(0).options.get(0).method();
            assertEquals(hi >= DepthRules.MIN_DEPTH_M ? CrossingDepthOption.Method.ABOVE : CrossingDepthOption.Method.BELOW, m, "hi=" + hi);
            assertTrue(r.profile.minDepth() >= DepthRules.MIN_DEPTH_M - 1e-12);
        }
    }

    @Test
    void validatorMinimumDepthAtOutputResolution() {
        double[] depths = {0.699, 0.700, 0.701};
        boolean[] error = {true, false, false};
        for (int i = 0; i < depths.length; i++) {
            InputModel in = new SyntheticInput().line(1, 300, -100, 0, 100, 0).point("A", 0, 100, 10).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    depthLine("n1", "A", "R", 10, 80, false, 1.0, depths[i], depths[i], 0, 100, 0, 0),
                    chamber("R", 0, 0, 300)));
            fs.add(depthSummary(fs, 0, 0, 0, 0, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(error[i], has(rep, "DEPTH_MIN"), "depth " + depths[i] + ": " + issues(rep));
            if (!error[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }
}
