package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.restrictions.ClearanceClass;
import ru.lct.heatnet.restrictions.ObstacleSpace;
import ru.lct.heatnet.restrictions.SegmentCheck;
import ru.lct.heatnet.validation.ValidationReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.boundary.BoundaryFixtures.*;
import static ru.lct.heatnet.SyntheticInput.c;

/**
 * Rule: annex table 2 — road / tram_tracks crossing angle «не менее 45°» (at the point of entry, relative to the
 * polygon boundary).
 * <pre>
 * angle    | 44.999° | 45.000° | 45.001°
 * result   | invalid | valid   | valid
 * </pre>
 * Checked on the segment check of the obstacle space (both traversal directions) and on the validator
 * (CROSSING_ANGLE) with a hand-built straight crossing at the given angle.
 */
class CrossingAngleBoundaryTest {

    private static final double[] ANG = {44.999, 45.000, 45.001};
    private static final boolean[] OK = {false, true, true};

    @Test
    void segmentCheckEntryAngle() {
        for (String type : new String[]{RestrictionRules.ROAD, RestrictionRules.TRAM_TRACKS}) {
            ObstacleSpace s = new ObstacleSpace(new SyntheticInput().rect(1, type, -1000, 100, 1000, 110).build(), ClearanceClass.SMALL);
            for (int i = 0; i < ANG.length; i++) {
                double th = Math.toRadians(ANG[i]);
                Coordinate a = c(0, 50), b = c(110 / Math.tan(th), 160);   // crosses the strip y 100..110 at angle ANG to its edges
                SegmentCheck sc = s.check(a, b);
                assertEquals(OK[i], sc.valid(), type + " " + ANG[i] + "°: " + sc.reason());
                assertEquals(OK[i], sc.validReverse(), type + " " + ANG[i] + "° reverse");
                if (OK[i]) {
                    assertEquals(1, sc.passages().size());
                    assertEquals(ANG[i], sc.passages().get(0).crossingAngleDeg(), 1e-6);
                }
            }
        }
    }

    @Test
    void validatorEntryAngle() {
        for (String type : new String[]{RestrictionRules.ROAD, RestrictionRules.TRAM_TRACKS}) {
            double k = RestrictionRules.require(type).kSpec();
            for (int i = 0; i < ANG.length; i++) {
                double th = Math.toRadians(ANG[i]);
                double[] dir = {Math.cos(th), Math.sin(th)};
                double ax = 0, ay = 50;
                double tEntry = 50 / dir[1], tExit = 60 / dir[1], tRoot = 100 / dir[1];
                double[] t1 = {ax + (tEntry - 3) * dir[0], ay + (tEntry - 3) * dir[1]};
                double[] t2 = {ax + (tExit + 3) * dir[0], ay + (tExit + 3) * dir[1]};
                double[] r = {ax + tRoot * dir[0], ay + tRoot * dir[1]};
                InputModel in = new SyntheticInput().rect(1, type, -1000, 100, 1000, 110).line(2, 300, -300, 150, 300, 150).point("A", ax, ay, 10).build();
                List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                        line("n1", "A", "t1", 10, 80, false, 1.0, ax, ay, t1[0], t1[1]),
                        withKSpec(line("n2", "t1", "t2", 10, 80, true, k, t1[0], t1[1], t2[0], t2[1]), k),
                        line("n3", "t2", "R", 10, 80, false, 1.0, t2[0], t2[1], r[0], r[1]),
                        tn("t1", t1[0], t1[1]), tn("t2", t2[0], t2[1]), chamber("R", r[0], r[1], 300)));
                fs.add(summary(fs, 0));
                ValidationReport rep = validate(in, fs);
                assertEquals(!OK[i], has(rep, "CROSSING_ANGLE"), type + " " + ANG[i] + "°: " + issues(rep));
                if (OK[i]) assertTrue(rep.isValid(), issues(rep));
            }
        }
    }
}
