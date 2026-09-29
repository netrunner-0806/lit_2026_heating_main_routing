package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.depth.DepthProfile;
import ru.lct.heatnet.depth.DepthProfileOptimizer;
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
 * Rule: annex 5 — «Максимальный уклон – 0,10 м/м» → slope ≤ 0.100 admissible.
 * <pre>
 * slope (optimizer)             | 0.099999 | 0.100000 | 0.100001
 * ramp feasible                 | yes      | yes      | no
 * slope (validator, 10.000 m)   | 0.0999   | 0.1000   | 0.1001   (depths 3.000 → 3.999 / 4.000 / 4.001)
 * DEPTH_SLOPE                   | ok       | ok       | error
 * </pre>
 */
class SlopeBoundaryTest {

    private static final double[] S = {0.099999, 0.100000, 0.100001};

    @Test
    void optimizerAcceptsTheLimitAndRejectsAnythingSteeper() {
        for (double s : S) {
            DepthProfileOptimizer.Result r = new DepthProfileOptimizer().optimize(10, Collections.emptyList(), 3.0, 3.0 + s * 10, x -> 100_000);
            assertEquals(s <= 0.1, r.feasible, "slope " + s);
            if (r.feasible) assertEquals(s, r.profile.maxSlope(), 1e-9);
        }
    }

    @Test
    void profileSlopeMeasurementUsesHorizontalLength() {
        DepthProfile p = new DepthProfile(Arrays.asList(new double[]{0, 3.0}, new double[]{10, 4.0}), Collections.emptyList());
        assertEquals(0.1, p.maxSlope(), 1e-12);
        assertEquals(3.5, p.depthAt(5), 1e-12);
    }

    @Test
    void validatorSlopeAtOutputResolution() {
        double[] end = {3.999, 4.000, 4.001};
        boolean[] error = {false, false, true};
        for (int i = 0; i < end.length; i++) {
            InputModel in = new SyntheticInput().line(1, 300, -100, 0, 100, 0).point("A", 0, 10, 10).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    depthLine("n1", "A", "R", 10, 80, false, 1.0, 3.0, end[i], 0, 10, 0, 0),
                    chamber("R", 0, 0, 300)));
            fs.add(depthSummary(fs, 0, 0, 0, 0, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(error[i], has(rep, "DEPTH_SLOPE"), "3.0 -> " + end[i] + " over 10 m: " + issues(rep));
            assertFalse(has(rep, "DEPTH_3M_SPLIT"), "a ramp starting exactly at 3.0 m needs no split");
            if (!error[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }
}
