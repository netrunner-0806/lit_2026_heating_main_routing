package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRule;
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
 * Rule: annex table 2 / 3.1 — minimal horizontal clearance between the outer edge of the new pair envelope and the
 * object (its envelope for gas / cable / existing network): oks 5/7/9 m by DU, park / social_area /
 * prohibited_site / water / railway 1.0 m, road / tram 1.5 m alongside, gas / cable 2.0 m, existing network 1.0 m.
 * <pre>
 * axis distance         | required − 0.01 | required | required + 0.01
 * segment check         | invalid         | valid    | valid
 * validator CLEARANCE   | error           | ok       | ok
 * </pre>
 * required = clearance + half width of the new pair + half width of the object envelope. The obstacle space shrinks
 * its check zones by CHECK_EPS = 1 mm so that touching exactly at the clearance passes; the validator allows
 * DIST_TOL = 1 mm. 1 cm is therefore the finest meaningful step, and neither tolerance stretches to it.
 */
class HorizontalClearanceBoundaryTest {

    private static final double EPS = 0.01;
    private static final String[] POLYGON_TYPES = {RestrictionRules.OKS, RestrictionRules.PARK, RestrictionRules.SOCIAL_AREA, RestrictionRules.PROHIBITED_SITE,
            RestrictionRules.WATER, RestrictionRules.RAILWAY, RestrictionRules.ROAD, RestrictionRules.TRAM_TRACKS};
    private static final String[] LINEAR_TYPES = {RestrictionRules.GAS_PIPELINE, RestrictionRules.POWER_CABLE};

    @Test
    void segmentCheckPerTypeAndClearanceClass() {
        for (ClearanceClass cls : ClearanceClass.values()) {
            for (String type : POLYGON_TYPES) {
                RestrictionRule rule = RestrictionRules.require(type);
                double required = rule.requiredAxisDistanceM(cls.representativeDu(), 0);
                ObstacleSpace s = new ObstacleSpace(new SyntheticInput().rect(1, type, 0, 100, 100, 150).build(), cls);
                checkThree(s, type + "/" + cls, required);
            }
            for (String type : LINEAR_TYPES) {
                RestrictionRule rule = RestrictionRules.require(type);
                double required = rule.requiredAxisDistanceM(cls.representativeDu(), rule.ownWidthM() / 2);
                ObstacleSpace s = new ObstacleSpace(new SyntheticInput().polyline(1, type, 0, 100, 100, 100).build(), cls);
                checkThree(s, type + "/" + cls, required);
            }
            // existing heat network alongside (own width from its DU)
            RestrictionRule net = RestrictionRules.existingHeatNetwork();
            double required = net.requiredAxisDistanceM(cls.representativeDu(), DiameterTable.requireDu(300).halfWidthM());
            ObstacleSpace s = new ObstacleSpace(new SyntheticInput().line(1, 300, 0, 100, 100, 100).build(), cls);
            checkThree(s, "heat_network/" + cls, required);
        }
    }

    private static void checkThree(ObstacleSpace s, String label, double required) {
        double[] offs = {-EPS, 0, EPS};
        boolean[] ok = {false, true, true};
        for (int i = 0; i < offs.length; i++) {
            double y = 100 - (required + offs[i]);
            SegmentCheck sc = s.check(c(-50, y), c(150, y));
            assertEquals(ok[i], sc.valid(), label + " at " + (required + offs[i]) + " m (required " + required + "): " + sc.reason());
        }
    }

    @Test
    void validatorClearancePerType() {
        int du = 80;   // 10 t/h
        double half = DiameterTable.requireDu(du).halfWidthM();
        for (String type : POLYGON_TYPES) validatorThree(type, RestrictionRules.require(type).clearanceM(du) + half, false);
        for (String type : LINEAR_TYPES) validatorThree(type, RestrictionRules.require(type).clearanceM(du) + half + RestrictionRules.require(type).ownWidthM() / 2, false);
        validatorThree(RestrictionRules.EXISTING_HEAT_NETWORK, RestrictionRules.existingHeatNetwork().clearanceM(du) + half + DiameterTable.requireDu(300).halfWidthM(), true);
    }

    private static void validatorThree(String type, double required, boolean existingLine) {
        double[] offs = {-EPS, 0, EPS};
        boolean[] error = {true, false, false};
        for (int i = 0; i < offs.length; i++) {
            double y = 100 - (required + offs[i]);
            SyntheticInput s = new SyntheticInput().line(1, 300, 150, y - 50, 150, y + 50).point("A", -50, y, 10);
            if (existingLine) s.line(2, 300, 0, 100, 100, 100);
            else if (Arrays.asList(LINEAR_TYPES).contains(type)) s.polyline(2, type, 0, 100, 100, 100);
            else s.rect(2, type, 0, 100, 100, 150);
            InputModel in = s.build();
            List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                    line("n1", "A", "R", 10, 80, false, 1.0, -50, y, 150, y),
                    chamber("R", 150, y, 300)));
            fs.add(summary(fs, 0));
            ValidationReport rep = validate(in, fs);
            assertEquals(error[i], has(rep, "CLEARANCE"), type + " at " + (required + offs[i]) + " m (required " + required + "): " + issues(rep));
            assertFalse(has(rep, "FORBIDDEN_CROSSED"));
            if (!error[i]) assertTrue(rep.isValid(), issues(rep));
        }
    }
}
