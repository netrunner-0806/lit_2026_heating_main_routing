package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;

/**
 * Rule: annex 2.1 — «Допускается произвольный угол поворота до 90° включительно» (0° = straight).
 * <pre>
 * turn     | 89.999° | 90.000° | 90.001°
 * result   | valid   | valid   | TURN_ANGLE / TURN_AT_CHAMBER
 * </pre>
 * Checked on the angle function, on a polyline vertex of one feature and on the flow path through a junction
 * chamber (last segment of the branch → first segment of the trunk).
 */
class TurnAngleBoundaryTest {

    private static final double[] T = {89.999, 90.000, 90.001};
    private static final boolean[] OK = {true, true, false};

    /** Point 100 m before B such that the turn at B onto the heading {@code headingAfterDeg} equals {@code turnDeg} (left turn). */
    private static double[] before(double bx, double by, double headingAfterDeg, double turnDeg) {
        double h = Math.toRadians(headingAfterDeg - turnDeg);
        return new double[]{bx - 100 * Math.cos(h), by - 100 * Math.sin(h)};
    }

    @Test
    void turnAngleFunctionIsExactAtNinetyDegrees() {
        for (double t : T) {
            double[] a = before(0, 0, -90, t);
            double turn = GeometryUtils.turnAngleDeg(new Coordinate(a[0], a[1]), new Coordinate(0, 0), new Coordinate(0, -100));
            assertEquals(t, turn, 1e-9);
        }
    }

    @Test
    void polylineVertexTurn() {
        for (int i = 0; i < T.length; i++) {
            double[] a = before(0, 100, -90, T[i]);
            InputModel in = new SyntheticInput().line(1, 300, -200, 0, 200, 0).point("A", a[0], a[1], 10).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    line("n1", "A", "R", 10, 80, false, 1.0, a[0], a[1], 0, 100, 0, 0),
                    chamber("R", 0, 0, 300)));
            fs.add(summary(fs, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(!OK[i], has(rep, "TURN_ANGLE"), "turn " + T[i] + ": " + issues(rep));
            if (OK[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }

    @Test
    void turnThroughAJunctionChamberAlongTheFlowPath() {
        for (int i = 0; i < T.length; i++) {
            double[] a = before(0, 100, -90, T[i]);
            InputModel in = new SyntheticInput().line(1, 300, -200, 0, 200, 0).point("A", a[0], a[1], 10).point("B", 0, 200, 10).build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    line("n1", "A", "J", 10, 80, false, 1.0, a[0], a[1], 0, 100),
                    line("n2", "B", "J", 10, 80, false, 1.0, 0, 200, 0, 100),
                    line("n3", "J", "R", 20, 100, false, 1.0, 0, 100, 0, 0),
                    chamber("J", 0, 100, 100), chamber("R", 0, 0, 300)));
            fs.add(summary(fs, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(!OK[i], has(rep, "TURN_AT_CHAMBER"), "turn " + T[i] + ": " + issues(rep));
            assertFalse(has(rep, "TURN_ANGLE"));
            if (OK[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }
}
