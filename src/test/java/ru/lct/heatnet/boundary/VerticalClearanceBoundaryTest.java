package ru.lct.heatnet.boundary;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.depth.CrossingDepthOption;
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
 * Rule: annex table 2 (depth mode) — vertical clearance «не менее» 0.2 m (gas), 0.5 m (power cable), 0.5 m
 * (existing heat network), passing ABOVE or BELOW; utility envelopes: gas 0.40 m top at 2.8 m, cable 0.20 m top at
 * 2.7 m, existing network H(DU) top at 3.0 m.
 * <pre>
 * clearance (rules, continuous)  | c − 1e-6 | c       | c + 1e-6
 * admissible                     | no       | yes     | yes
 * clearance (validator, 3 dec.)  | c − 0.001| c       | c + 0.001
 * *_VERTICAL_CLEARANCE           | error    | ok      | ok
 * </pre>
 * Both sides are checked: ABOVE (clearance = utility top − new bottom) and BELOW (new top − utility bottom).
 * Road / tram cover under the object: 0.999 / 1.000 / 1.001 m (road), 1.199 / 1.200 / 1.201 m (tram) → error only below.
 */
class VerticalClearanceBoundaryTest {

    private static final double H_NEW = DiameterTable.requireDu(80).heightM();   // 10 t/h -> DU80, 0.16 m
    private static final double H_EXISTING = DiameterTable.requireDu(400).heightM();  // 0.56 m

    private static VerticalObstacle obstacle(String type, double hNew) {
        double height = RestrictionRules.GAS_PIPELINE.equals(type) ? 0.40 : RestrictionRules.POWER_CABLE.equals(type) ? 0.20 : H_EXISTING;
        return new VerticalObstacle(type, type, DepthRules.forType(type).get(), 50, 54, height, RestrictionRules.require(type).kSpec(), hNew);
    }

    /** [type, utility top, utility height, clearance] */
    private static final Object[][] RULES = {
            {RestrictionRules.GAS_PIPELINE, 2.8, 0.40, 0.2},
            {RestrictionRules.POWER_CABLE, 2.7, 0.20, 0.5},
            {RestrictionRules.EXISTING_HEAT_NETWORK, 3.0, H_EXISTING, 0.5}};

    @Test
    void admissibleIntervalsAreClosedAtTheClearance() {
        double eps = 1e-6;
        for (Object[] r : RULES) {
            String type = (String) r[0];
            double top = (double) r[1], height = (double) r[2], clear = (double) r[3];
            VerticalObstacle o = obstacle(type, H_NEW);
            double aboveTop = top - clear - H_NEW;          // new top giving exactly the clearance above
            double belowTop = top + height + clear;          // new top giving exactly the clearance below
            assertFalse(o.above().contains(aboveTop + eps), type + " above: clearance " + (clear - eps));
            assertTrue(o.above().contains(aboveTop), type + " above: clearance exactly " + clear);
            assertTrue(o.above().contains(aboveTop - eps), type + " above: clearance " + (clear + eps));
            assertFalse(o.below().contains(belowTop - eps), type + " below: clearance " + (clear - eps));
            assertTrue(o.below().contains(belowTop), type + " below: clearance exactly " + clear);
            assertTrue(o.below().contains(belowTop + eps), type + " below: clearance " + (clear + eps));
        }
    }

    @Test
    void optimizerPlateausSitExactlyAtTheClearanceBound() {
        for (Object[] r : RULES) {
            String type = (String) r[0];
            double top = (double) r[1], height = (double) r[2], clear = (double) r[3];
            DepthProfileOptimizer.Result above = new DepthProfileOptimizer().optimize(100, Collections.singletonList(obstacle(type, H_NEW)), null, 3.0, s -> 100_000);
            assertTrue(above.feasible);
            assertEquals(CrossingDepthOption.Method.ABOVE, above.profile.decisions().get(0).options.get(0).method(), type);
            assertEquals(top - clear - H_NEW, above.profile.decisions().get(0).depth, 1e-9, type + ": ABOVE plateau at exactly the clearance");
            DepthProfileOptimizer.Result below = new DepthProfileOptimizer().optimize(100, Collections.singletonList(obstacle(type, 2.0)), null, 3.0, s -> 100_000);
            assertTrue(below.feasible);
            assertEquals(CrossingDepthOption.Method.BELOW, below.profile.decisions().get(0).options.get(0).method(), type);
            assertEquals(top + height + clear, below.profile.decisions().get(0).depth, 1e-9, type + ": BELOW plateau at exactly the clearance");
        }
    }

    @Test
    void validatorClearanceAtOutputResolution() {
        double eps = 0.001;
        for (Object[] r : RULES) {
            String type = (String) r[0];
            double top = (double) r[1], height = (double) r[2], clear = (double) r[3];
            String code = RestrictionRules.GAS_PIPELINE.equals(type) ? "GAS_VERTICAL_CLEARANCE" : RestrictionRules.POWER_CABLE.equals(type) ? "POWER_VERTICAL_CLEARANCE" : "HEAT_NETWORK_VERTICAL_CLEARANCE";
            for (boolean aboveSide : new boolean[]{true, false}) {
                for (int i = -1; i <= 1; i++) {
                    double clearance = clear + i * eps;
                    double d = aboveSide ? round3(top - H_NEW - clearance) : round3(top + height + clearance);
                    ValidationReport rep = validate(inputWith(type), network(type, d));
                    boolean expectError = i < 0;
                    assertEquals(expectError, has(rep, code), type + (aboveSide ? " above" : " below") + " clearance " + clearance + " (depth " + d + "): " + issues(rep));
                    if (!expectError) assertTrue(rep.isValid(), issues(rep));
                }
            }
        }
    }

    /** Road / tram (depth mode): «верх габарита новой сети на глубине не менее 1,0 м / 1,2 м» under the object. */
    @Test
    void validatorRoadAndTramCoverAtOutputResolution() {
        Object[][] rules = {{RestrictionRules.ROAD, 1.0, "ROAD_DEPTH"}, {RestrictionRules.TRAM_TRACKS, 1.2, "TRAM_DEPTH"}};
        for (Object[] r : rules) {
            String type = (String) r[0];
            double minDepth = (double) r[1];
            double k = RestrictionRules.require(type).kSpec();
            for (int i = -1; i <= 1; i++) {
                double d = round3(minDepth + i * 0.001);
                InputModel in = new SyntheticInput().line(1, 300, -100, 200, 100, 200).rect(2, type, -50, 95, 50, 105).point("A", 0, 0, 10).build();
                List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                        depthLine("n1", "A", "t1", 10, 80, false, 1.0, d, d, 0, 0, 0, 92),
                        withKSpec(depthLine("n2", "t1", "t2", 10, 80, true, k, d, d, 0, 92, 0, 108), k),
                        depthLine("n3", "t2", "R", 10, 80, false, 1.0, d, d, 0, 108, 0, 200),
                        tn("t1", 0, 92), tn("t2", 0, 108), chamber("R", 0, 200, 300)));
                fs.add(depthSummary(fs, 0, 0, 0, 0, 1));
                ValidationReport rep = validate(in, fs);
                assertEquals(i < 0, has(rep, (String) r[2]), type + " cover " + d + ": " + issues(rep));
                if (i >= 0) assertTrue(rep.isValid(), issues(rep));
            }
        }
    }

    private static InputModel inputWith(String type) {
        SyntheticInput s = new SyntheticInput().line(1, 300, -100, 200, 100, 200).point("A", 0, 0, 10);
        if (RestrictionRules.EXISTING_HEAT_NETWORK.equals(type)) s.line(2, 400, -50, 100, 50, 100);
        else s.polyline(2, type, -50, 100, 50, 100);
        return s.build();
    }

    /** Straight route A (0,0) -> R (0,200) crossing the utility at y = 100, flat at depth d everywhere. */
    private static List<OutputFeature> network(String type, double d) {
        double k = RestrictionRules.require(type).kSpec();
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                depthLine("n1", "A", "t1", 10, 80, false, 1.0, d, d, 0, 0, 0, 98),
                withKSpec(depthLine("n2", "t1", "t2", 10, 80, true, k, d, d, 0, 98, 0, 102), k),
                depthLine("n3", "t2", "R", 10, 80, false, 1.0, d, d, 0, 102, 0, 200),
                tn("t1", 0, 98), tn("t2", 0, 102), chamber("R", 0, 200, 300)));
        boolean above = d + H_NEW <= utilityTop(type);
        fs.add(depthSummary(fs, 0, 1, above ? 1 : 0, above ? 0 : 1, 0));
        return fs;
    }

    private static double utilityTop(String type) {
        for (Object[] r : RULES) if (r[0].equals(type)) return (double) r[1];
        throw new IllegalArgumentException(type);
    }
}
