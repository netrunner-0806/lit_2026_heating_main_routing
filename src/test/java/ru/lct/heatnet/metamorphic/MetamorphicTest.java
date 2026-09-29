package ru.lct.heatnet.metamorphic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.testkit.GeoJsonDoc;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.testkit.SolveKit.Outcome;
import ru.lct.heatnet.testkit.SolveKit.VariantSig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metamorphic tests (phase 7): transformations of the input GeoJSON that do not change the meaning of the task must
 * not change the solution (connected set, DU per consumer, chambers, tie-ins, score/cost/length within tolerance,
 * validator verdict). Cases: two synthetic fixtures (PLANAR) and the depth demo (PLANAR and DEPTH).
 *
 * <p>The fixtures place every consumer exactly at the centre of a square building, i.e. four boundary points are
 * equally near (an exact tie). Which tied exit is "the nearest" is then decided by floating-point noise, so the
 * consumers are moved 0.7 m / 0.3 m off-centre here to make the nearest exit unique. The tie itself is covered by
 * {@link TiedExitsTest} (BUG-3).
 */
class MetamorphicTest {

    private static final String[] CASES = {"/fixtures/synthetic-basic.geojson", "/fixtures/synthetic-shared-trunk.geojson", "/fixtures/depth-demo.geojson"};
    private static final double EXACT = 1e-6, TRANSLATION = 1e-3, EXTRA_VERTICES = 1e-2;

    /** A transformation is a pair (reference document, transformed document) built from the original. */
    static final class Transform {
        final String name;
        final Function<ObjectNode, ObjectNode> reference, transformed;
        final double tolerance;
        final boolean bestOnly;
        Transform(String name, Function<ObjectNode, ObjectNode> reference, Function<ObjectNode, ObjectNode> transformed, double tolerance, boolean bestOnly) {
            this.name = name; this.reference = reference; this.transformed = transformed; this.tolerance = tolerance; this.bestOnly = bestOnly;
        }
        @Override public String toString() { return name; }
    }

    static final List<Transform> TRANSFORMS = List.of(
            new Transform("feature order permutation (seed 42)", d -> d, d -> GeoJsonDoc.shuffleFeatures(d, 42L), EXACT, false),
            new Transform("feature order permutation (seed 7)", d -> d, d -> GeoJsonDoc.shuffleFeatures(d, 7L), EXACT, false),
            // heat_network reversal is covered by LineDirectionTest (BUG-4); restriction lines are reversed here
            new Transform("restriction LineString direction reversal", d -> d, MetamorphicTest::reverseRestrictionLines, EXACT, false),
            new Transform("polygon ring direction CW<->CCW", d -> d, GeoJsonDoc::reverseRings, EXACT, false),
            new Transform("MultiLineString part order", d -> GeoJsonDoc.splitToMultiLine(d, MetamorphicTest::restrictionLine),
                    d -> GeoJsonDoc.reverseMultiLineParts(GeoJsonDoc.splitToMultiLine(d, MetamorphicTest::restrictionLine)), EXACT, false),
            new Transform("numeric ids as strings", d -> d, GeoJsonDoc::stringifyIds, EXACT, false),
            new Transform("property key order", d -> d, d -> GeoJsonDoc.permuteKeys(d, 99L), EXACT, false),
            new Transform("rigid translation +37.3/-21.7 m", d -> d, d -> GeoJsonDoc.translate(d, 37.3, -21.7), TRANSLATION, false),
            new Transform("rigid translation -250/+180 m", d -> d, d -> GeoJsonDoc.translate(d, -250.0, 180.0), TRANSLATION, false),
            new Transform("rigid translation +1/0 m", d -> d, d -> GeoJsonDoc.translate(d, 1.0, 0.0), TRANSLATION, false),
            // extra vertices on straight segments do not change the geometry, but the visibility graph gains
            // waypoints (obstacle zone vertices), so the heuristic may find a slightly different (better) route:
            // structural equality is exact, the best variant's score/cost/length are compared within 1 %
            new Transform("extra collinear vertices", d -> d, GeoJsonDoc::addMidVertices, EXTRA_VERTICES, true));

    static ObjectNode reverseRestrictionLines(ObjectNode doc) {
        ObjectNode d = GeoJsonDoc.copy(doc);
        for (JsonNode f : GeoJsonDoc.features(d)) {
            if (!restrictionLine(f)) continue;
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (!"LineString".equals(g.path("type").asText())) continue;
            com.fasterxml.jackson.databind.node.ArrayNode cs = (com.fasterxml.jackson.databind.node.ArrayNode) g.get("coordinates");
            com.fasterxml.jackson.databind.node.ArrayNode out = SolveKit.MAPPER.createArrayNode();
            for (int i = cs.size() - 1; i >= 0; i--) out.add(cs.get(i).deepCopy());
            g.set("coordinates", out);
        }
        return d;
    }

    private static boolean restrictionLine(JsonNode f) {
        return "restriction".equals(f.path("properties").path("object_type").asText());
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        for (String c : CASES) {
            for (Transform t : TRANSFORMS) {
                out.add(Arguments.of(c, false, t));
                if (c.contains("depth-demo")) out.add(Arguments.of(c, true, t));
            }
        }
        return out.stream();
    }

    // reference outcomes are shared between transformations of the same case (the solver is deterministic)
    private static final Map<String, List<VariantSig>> REFERENCE = new ConcurrentHashMap<>();

    private static List<VariantSig> reference(String key, ObjectNode doc, boolean depth) throws Exception {
        List<VariantSig> cached = REFERENCE.get(key);
        if (cached != null) return cached;
        List<VariantSig> sig = SolveKit.semantic(SolveKit.run(SolveKit.parse(doc), depth));
        REFERENCE.put(key, sig);
        return sig;
    }

    static ObjectNode fixture(String name) throws Exception {
        return nudgeConsumers(GeoJsonDoc.loadResource(name), 0.7, 0.3);
    }

    /** Moves every consumer by (dx, dy) metres so that its nearest building side is unique (see class comment). */
    static ObjectNode nudgeConsumers(ObjectNode doc, double dx, double dy) {
        ObjectNode d = GeoJsonDoc.copy(doc);
        for (JsonNode f : GeoJsonDoc.features(d)) {
            if (!"oks_connection_point".equals(f.path("properties").path("object_type").asText())) continue;
            ObjectNode g = (ObjectNode) f.get("geometry");
            double lon = g.get("coordinates").get(0).asDouble(), lat = g.get("coordinates").get(1).asDouble();
            Coordinate m = CrsTransformer.get().toMetric(lon, lat);
            double[] ll = CrsTransformer.get().toWgs84(m.x + dx, m.y + dy);
            g.set("coordinates", SolveKit.MAPPER.createArrayNode().add(Math.round(ll[0] * 1e9) / 1e9).add(Math.round(ll[1] * 1e9) / 1e9));
        }
        return d;
    }

    @ParameterizedTest(name = "{0} depth={1}: {2}")
    @MethodSource("cases")
    void transformationPreservesTheSolution(String name, boolean depth, Transform t) throws Exception {
        ObjectNode original = fixture(name);
        ObjectNode ref = t.reference.apply(original);
        String key = name + "|" + depth + "|" + (ref == original ? "original" : t.name);
        List<VariantSig> expected = reference(key, ref, depth);
        assertFalse(expected.isEmpty(), "reference solution exists for " + name);
        for (VariantSig s : expected) assertTrue(s.valid, "reference variant valid: " + s);
        Outcome actual = SolveKit.run(SolveKit.parse(t.transformed.apply(original)), depth);
        List<VariantSig> got = SolveKit.semantic(actual);
        String label = name + " / " + t.name + (depth ? " [DEPTH]" : " [PLANAR]");
        if (t.bestOnly) {
            assertFalse(got.isEmpty(), label + ": a solution exists");
            SolveKit.assertEquivalent(label + " (best variant)", Collections.singletonList(expected.get(0)), Collections.singletonList(got.get(0)), t.tolerance);
        } else {
            SolveKit.assertEquivalent(label, expected, got, t.tolerance);
        }
    }

    /** (e) input id type: output references keep the JSON type of the input id (123 stays a number, "123" a string). */
    @Test
    void outputReferencesKeepTheInputIdType() throws Exception {
        ObjectNode numeric = fixture("/fixtures/synthetic-basic.geojson");
        ObjectNode textual = GeoJsonDoc.stringifyIds(numeric);
        Outcome a = SolveKit.run(SolveKit.parse(numeric), false);
        Outcome b = SolveKit.run(SolveKit.parse(textual), false);
        SolveKit.assertEquivalent("id type", SolveKit.semantic(a), SolveKit.semantic(b), EXACT);
        // existing chamber 200 and consumer 2 are referenced by start/end node ids; line 100/101 by existing_line_id
        int numRefs = 0, strRefs = 0;
        for (OutputFeature f : a.features) {
            for (String k : new String[]{"start_node_id", "end_node_id", "existing_line_id"}) {
                Object v = f.prop(k);
                if (v == null) continue;
                if (isInputNumericId(v)) { assertTrue(v instanceof Number, k + " of " + f.prop("id") + " must stay numeric: " + v.getClass()); numRefs++; }
            }
        }
        for (OutputFeature f : b.features) {
            for (String k : new String[]{"start_node_id", "end_node_id", "existing_line_id"}) {
                Object v = f.prop(k);
                if (v == null) continue;
                assertFalse(v instanceof Number, k + " of " + f.prop("id") + " must be a string when the input id was a string: " + v);
                if (v.toString().matches("\\d+")) strRefs++;
            }
        }
        assertTrue(numRefs > 0, "numeric input ids are referenced by the output");
        assertEquals(numRefs, strRefs, "the same references exist with string ids");
        // round trip through GeoJSON keeps the types too
        List<OutputFeature> backA = SolveKit.roundTrip(a.features), backB = SolveKit.roundTrip(b.features);
        for (OutputFeature f : backA) if (f.prop("existing_line_id") != null) assertTrue(f.prop("existing_line_id") instanceof Number);
        for (OutputFeature f : backB) if (f.prop("existing_line_id") != null) assertTrue(f.prop("existing_line_id") instanceof String);
    }

    private static boolean isInputNumericId(Object v) {
        // generated ids are strings like v1_chamber_1; input ids in synthetic-basic are numbers or "cp-1"
        return !(v instanceof String) || ((String) v).matches("\\d+");
    }
}
