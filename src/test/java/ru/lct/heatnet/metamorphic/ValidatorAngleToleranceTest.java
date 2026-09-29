package ru.lct.heatnet.metamorphic;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.testkit.GeoJsonDoc;
import ru.lct.heatnet.testkit.SolveKit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-1 (metamorphic finding): the solver builds routes with exact right angles (axis-aligned obstacle zones and
 * helper grid) and accepts turn <= 90° + 1e-6°. The output is written in WGS84 with 9 decimals (~0.1 mm), and the
 * independent validator re-measures the angle on those rounded coordinates with the same 1e-6° tolerance, so an
 * exact 90° turn on a short segment (2.4 m here) becomes 90.0003° and the whole variant is rejected
 * ("turn of 90.0°"). Observed on synthetic-basic translated by 1 m: the joint-existing-chambers variant disappears.
 *
 * <p>Expected: the validator tolerance must exceed the coordinate rounding noise (0.1 mm / 1 m ≈ 0.006°); fix
 * verified with SpatialValidator.ANGLE_TOL_DEG = 0.01° for the turn and crossing-angle checks.
 */
class ValidatorAngleToleranceTest {
    @Test
    void exactRightAnglesSurviveTheWgs84RoundTrip() throws Exception {
        ObjectNode doc = GeoJsonDoc.loadResource("/fixtures/synthetic-basic.geojson");
        List<String> rejected = new ArrayList<>();
        for (double[] s : new double[][]{{0, 0}, {1, 0}, {0, 1}, {2, 3}, {37.3, -21.7}}) {
            SolveResult r = SolveKit.solve(SolveKit.parse(GeoJsonDoc.translate(doc, s[0], s[1])), false);
            for (Diagnostics.Message m : r.diagnostics().messages()) {
                if ("VARIANT_REJECTED".equals(m.getCode()) && (m.getText().contains("TURN_ANGLE") || m.getText().contains("CROSSING_ANGLE")))
                    rejected.add("shift " + s[0] + "/" + s[1] + ": " + m.getText());
            }
        }
        assertTrue(rejected.isEmpty(), "variants built by the solver were rejected by the validator on angle tolerance:\n" + String.join("\n", rejected));
    }
}
