package ru.lct.heatnet.validation;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.output.OutputFeature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.SyntheticInput.c;

/** Hand-built output features exercising individual validator rules. */
class ResultValidatorRulesTest {

    private static double[] ll(double x, double y) { return CrsTransformer.get().toWgs84(c(x, y).x, c(x, y).y); }

    private static OutputFeature line(String id, Object start, Object end, double flow, int du, boolean special, double kSpec, double... xy) {
        List<double[]> cs = new ArrayList<>();
        double len = 0;
        for (int i = 0; i < xy.length / 2; i++) {
            cs.add(ll(xy[2 * i], xy[2 * i + 1]));
            if (i > 0) len += Math.hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1]);
        }
        double lenR = Math.round(len * 1000.0) / 1000.0;
        return OutputFeature.line(cs).prop("id", id).prop("object_type", "heat_network").prop("variant_id", "v1")
                .prop("start_node_id", start).prop("end_node_id", end).prop("flow_tph", flow).prop("diameter", du)
                .prop("length", lenR).prop("laying_method", special ? "special" : "base")
                .prop("depth_start", null).prop("depth_end", null)
                .prop("cost", Math.round(lenR * DiameterTable.requireDu(du).costPerMeter() * kSpec));
    }

    private static OutputFeature chamber(String id, double x, double y, int du, long cost) {
        return OutputFeature.point(ll(x, y)).prop("id", id).prop("object_type", "heat_chamber").prop("variant_id", "v1").prop("diameter", du).prop("cost", cost);
    }

    private static OutputFeature tn(String id, double x, double y) {
        return OutputFeature.point(ll(x, y)).prop("id", id).prop("object_type", "technical_node").prop("variant_id", "v1");
    }

    private static OutputFeature summary(List<OutputFeature> fs) {
        double lines = 0, chambers = 0, len = 0;
        for (OutputFeature f : fs) {
            if ("heat_network".equals(f.objectType())) { lines += ((Number) f.prop("cost")).doubleValue(); len += ((Number) f.prop("length")).doubleValue(); }
            if ("heat_chamber".equals(f.objectType())) chambers += ((Number) f.prop("cost")).doubleValue();
        }
        double calc = lines + chambers;
        return OutputFeature.noGeometry().prop("id", "v1_summary").prop("object_type", "variant_summary").prop("variant_id", "v1")
                .prop("rank", 1).prop("construction_cost", calc).prop("chamber_construction_cost", chambers)
                .prop("existing_chamber_tie_in_count", 0).prop("existing_chamber_tie_in_cost", 0).prop("unconnected_penalty", 0)
                .prop("calculated_cost", calc).prop("new_network_length", len)
                .prop("score", 0.7 * calc / 25_000_000 + 0.3 * len / 100).prop("unconnected_oks_ids", new ArrayList<>());
    }

    private static boolean has(ValidationReport rep, String code) {
        return rep.getIssues().stream().anyMatch(i -> i.getCode().equals(code));
    }

    @Test
    void turnOfMoreThan90DegreesThroughAChamberIsInvalid() {
        // existing line y=0; root chamber R at (100,0); junction J at (100,100); consumers A (40,60) and B (160,60)
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 200, 0)
                .point("A", 40, 60, 10).point("B", 160, 60, 10).build();
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "J", 10, 80, false, 1.0, 40, 60, 100, 100),      // heading 33.7° then J -> R heading 270°: turn 123.7°
                line("n2", "B", "J", 10, 80, false, 1.0, 160, 60, 100, 100),
                line("n3", "J", "R", 20, 100, false, 1.0, 100, 100, 100, 0),
                chamber("J", 100, 100, 100, 3_000_000), chamber("R", 100, 0, 300, 5_000_000)));
        fs.add(summary(fs));
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(has(rep, "TURN_AT_CHAMBER"), rep.getIssues().toString());
        // straight through the junction: consumer at (100,200) -> J -> R is valid for that path
        InputModel in2 = new SyntheticInput().line(1, 300, 0, 0, 200, 0).point("A", 100, 200, 10).point("B", 200, 200, 10).build();
        List<OutputFeature> fs2 = new ArrayList<>(Arrays.asList(
                line("n1", "A", "J", 10, 80, false, 1.0, 100, 200, 100, 100),
                line("n2", "B", "J", 10, 80, false, 1.0, 200, 200, 100, 100),  // arrives from the north-east: turn 45° onto J -> R
                line("n3", "J", "R", 20, 100, false, 1.0, 100, 100, 100, 0),
                chamber("J", 100, 100, 100, 3_000_000), chamber("R", 100, 0, 300, 5_000_000)));
        fs2.add(summary(fs2));
        ValidationReport rep2 = new ResultValidator(in2).validate(fs2);
        assertFalse(has(rep2, "TURN_AT_CHAMBER"), rep2.getIssues().toString());
        assertFalse(has(rep2, "TURN_ANGLE"));
    }

    @Test
    void roadEntryAngleIsMandatoryButExitAngleIsOnlyReported() {
        Polygon road = GeometryUtils.GF.createPolygon(new Coordinate[]{c(0, 100), c(300, 100), c(300, 400), c(200, 400), c(100, 110), c(0, 100)});
        // consumer below the road, existing line above it: traversal enters through the bottom edge at 90°, exits at 19°
        InputModel in = new SyntheticInput().line(1, 300, 0, 450, 300, 450).restriction(9, "road", road).point("A", 150, 50, 20).build();
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 20, 100, false, 1.0, 150, 50, 150, 97),
                line("n2", "t1", "t2", 20, 100, true, 1.60, 150, 97, 150, 258),
                line("n3", "t2", "R", 20, 100, false, 1.0, 150, 258, 150, 450),
                tn("t1", 150, 97), tn("t2", 150, 258), chamber("R", 150, 450, 300, 5_000_000)));
        fs.add(summary(fs));
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertFalse(has(rep, "CROSSING_ANGLE"), rep.getIssues().toString());
        assertTrue(has(rep, "CROSSING_EXIT_ANGLE"), "exit angle below 45° is reported as information");
        assertTrue(rep.isValid(), rep.getIssues().toString());
        // the opposite traversal (consumer above, network below) enters through the steep edge: invalid
        InputModel in2 = new SyntheticInput().line(1, 300, 0, 0, 300, 0).restriction(9, "road", road).point("A", 150, 450, 20).build();
        List<OutputFeature> fs2 = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 20, 100, false, 1.0, 150, 450, 150, 258),
                line("n2", "t1", "t2", 20, 100, true, 1.60, 150, 258, 150, 97),
                line("n3", "t2", "R", 20, 100, false, 1.0, 150, 97, 150, 0),
                tn("t1", 150, 258), tn("t2", 150, 97), chamber("R", 150, 0, 300, 5_000_000)));
        fs2.add(summary(fs2));
        ValidationReport rep2 = new ResultValidator(in2).validate(fs2);
        assertTrue(has(rep2, "CROSSING_ANGLE"), rep2.getIssues().toString());
    }

    @Test
    void specialFeatureWithAnInteriorTurnIsInvalid() {
        InputModel in = new SyntheticInput().line(1, 300, 0, 0, 300, 0).rect(9, "road", 0, 100, 300, 110).point("A", 150, 200, 10).build();
        List<OutputFeature> fs = new ArrayList<>(Arrays.asList(
                line("n1", "A", "t1", 10, 80, false, 1.0, 150, 200, 150, 113),
                line("n2", "t1", "t2", 10, 80, true, 1.60, 150, 113, 150, 105, 152, 97),   // bent special feature
                line("n3", "t2", "R", 10, 80, false, 1.0, 152, 97, 152, 0),
                tn("t1", 150, 113), tn("t2", 152, 97), chamber("R", 152, 0, 300, 5_000_000)));
        fs.add(summary(fs));
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(has(rep, "SPECIAL_NOT_STRAIGHT"), rep.getIssues().toString());
    }
}
