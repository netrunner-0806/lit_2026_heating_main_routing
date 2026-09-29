package ru.lct.heatnet.adversarial;

import ru.lct.heatnet.geo.CrsTransformer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generator of the adversarial GeoJSON fixtures in {@code src/test/resources/adversarial}. Scenes are described in
 * metres relative to the origin (400000, 6170000) of EPSG:32637 and converted to WGS84 with the production
 * transformer, so that the fixtures exercise the real parsing path. Regenerate with
 * {@code java -cp target/test-classes:target/classes:<deps> ru.lct.heatnet.adversarial.AdversarialFixtureGenerator [dir]}.
 * The committed files are the source of truth for the tests; {@link AdversarialFixtureGenerator#scenes()} documents them.
 */
public final class AdversarialFixtureGenerator {

    public static final double OX = 400_000, OY = 6_170_000;

    /** One fixture: a list of features written in insertion order. */
    public static final class Scene {
        public final String name;
        private final List<String> features = new ArrayList<>();

        public Scene(String name) { this.name = name; }

        public Scene source(Object id, double x, double y) {
            features.add(feature(props("id", id, "object_type", "source"), pointGeom(x, y)));
            return this;
        }

        public Scene chamber(Object id, double x, double y) {
            features.add(feature(props("id", id, "object_type", "heat_chamber"), pointGeom(x, y)));
            return this;
        }

        public Scene point(Object id, double x, double y, double flow) {
            features.add(feature(props("id", id, "object_type", "oks_connection_point", "flow_tph", flow), pointGeom(x, y)));
            return this;
        }

        public Scene line(Object id, int du, double... xy) {
            features.add(feature(props("id", id, "object_type", "heat_network", "diameter", du), lineString(xy)));
            return this;
        }

        public Scene multiLine(Object id, int du, double[]... parts) {
            features.add(feature(props("id", id, "object_type", "heat_network", "diameter", du), multiLineString(parts)));
            return this;
        }

        public Scene polyline(Object id, String type, double... xy) {
            features.add(feature(props("id", id, "object_type", "restriction", "restriction_type", type), lineString(xy)));
            return this;
        }

        public Scene multiPolyline(Object id, String type, double[]... parts) {
            features.add(feature(props("id", id, "object_type", "restriction", "restriction_type", type), multiLineString(parts)));
            return this;
        }

        /** Polygon from rings given as flat x,y lists (first ring = shell, the rest = holes); rings are written as given. */
        public Scene polygon(Object id, String type, double[]... rings) {
            features.add(feature(props("id", id, "object_type", "restriction", "restriction_type", type), polygonGeom(rings)));
            return this;
        }

        public Scene multiPolygon(Object id, String type, double[][]... polygons) {
            StringBuilder sb = new StringBuilder("{\"type\": \"MultiPolygon\", \"coordinates\": [");
            for (int i = 0; i < polygons.length; i++) { if (i > 0) sb.append(", "); sb.append(rings(polygons[i])); }
            sb.append("]}");
            features.add(feature(props("id", id, "object_type", "restriction", "restriction_type", type), sb.toString()));
            return this;
        }

        public String toGeoJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n \"type\": \"FeatureCollection\",\n \"crs\": {\"type\": \"name\", \"properties\": {\"name\": \"urn:ogc:def:crs:OGC:1.3:CRS84\"}},\n \"features\": [\n");
            for (int i = 0; i < features.size(); i++) {
                sb.append("  ").append(features.get(i));
                if (i + 1 < features.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append(" ]\n}\n");
            return sb.toString();
        }
    }

    // ---- JSON helpers -------------------------------------------------------------------------------------------

    static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String feature(Map<String, Object> props, String geometry) {
        StringBuilder sb = new StringBuilder("{\"type\": \"Feature\", \"properties\": {");
        boolean first = true;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof String) sb.append("\"").append(v).append("\"");
            else if (v instanceof Double) sb.append(String.format(Locale.ROOT, "%s", v));
            else sb.append(v);
        }
        sb.append("}, \"geometry\": ").append(geometry).append("}");
        return sb.toString();
    }

    static String coord(double x, double y) {
        double[] ll = CrsTransformer.get().toWgs84(OX + x, OY + y);
        return String.format(Locale.ROOT, "[%.9f, %.9f]", ll[0], ll[1]);
    }

    static String pointGeom(double x, double y) { return "{\"type\": \"Point\", \"coordinates\": " + coord(x, y) + "}"; }

    static String coordList(double[] xy) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < xy.length; i += 2) { if (i > 0) sb.append(", "); sb.append(coord(xy[i], xy[i + 1])); }
        return sb.append("]").toString();
    }

    static String lineString(double[] xy) { return "{\"type\": \"LineString\", \"coordinates\": " + coordList(xy) + "}"; }

    static String multiLineString(double[][] parts) {
        StringBuilder sb = new StringBuilder("{\"type\": \"MultiLineString\", \"coordinates\": [");
        for (int i = 0; i < parts.length; i++) { if (i > 0) sb.append(", "); sb.append(coordList(parts[i])); }
        return sb.append("]}").toString();
    }

    static String rings(double[][] rings) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rings.length; i++) { if (i > 0) sb.append(", "); sb.append(coordList(rings[i])); }
        return sb.append("]").toString();
    }

    static String polygonGeom(double[][] rings) { return "{\"type\": \"Polygon\", \"coordinates\": " + rings(rings) + "}"; }

    /** Closed rectangle ring, counter-clockwise. */
    public static double[] rectCcw(double x0, double y0, double x1, double y1) { return new double[]{x0, y0, x1, y0, x1, y1, x0, y1, x0, y0}; }

    /** Closed rectangle ring, clockwise. */
    public static double[] rectCw(double x0, double y0, double x1, double y1) { return new double[]{x0, y0, x0, y1, x1, y1, x1, y0, x0, y0}; }

    // ---- scenes -------------------------------------------------------------------------------------------------

    /** Existing DU300 line along y = 0 from x = 0 to 300 with the source at its start. */
    private static Scene base(String name) { return new Scene(name).source(300, 0, 0).line(100, 300, 0, 0, 300, 0); }

    /**
     * Consumer building 10 x 10 m centred at (cx, 100) with the connection point 1 m south of the centre, so that the
     * nearest boundary point is unambiguous (4 m to the south wall, 5 m to the east/west walls, 6 m to the north).
     */
    private static Scene consumer(Scene s, Object id, double cx, double flow) {
        return s.polygon("oks_" + id, "oks", rectCcw(cx - 5, 95, cx + 5, 105)).point(id, cx, 99, flow);
    }

    public static List<Scene> scenes() {
        List<Scene> out = new ArrayList<>();
        // 1. forbidden polygon with a hole between the consumer and the network: the ring cannot be crossed
        out.add(consumer(base("01-polygon-with-hole"), "A", 50, 10).polygon(1, "park", rectCcw(30, 30, 70, 70), rectCw(40, 40, 60, 60)));
        // 2. connection point inside the courtyard (hole) of its own building: no way out without crossing the building
        out.add(base("02-point-inside-hole").polygon("oks_A", "oks", rectCcw(20, 80, 80, 140), rectCw(35, 95, 65, 125)).point("A", 50, 98.5, 10));
        // 3. connection point exactly on the boundary of its building
        out.add(base("03-point-on-boundary").polygon("oks_A", "oks", rectCcw(40, 95, 60, 105)).point("A", 50, 95, 10));
        // 4/5. identical scenes, rings written clockwise vs counter-clockwise
        out.add(base("04-ring-cw").polygon("oks_A", "oks", rectCw(40, 95, 60, 105)).point("A", 50, 99, 10).polygon(1, "park", rectCw(20, 40, 40, 60)));
        out.add(base("05-ring-ccw").polygon("oks_A", "oks", rectCcw(40, 95, 60, 105)).point("A", 50, 99, 10).polygon(1, "park", rectCcw(20, 40, 40, 60)));
        // 6. own building as a MultiPolygon (two parts)
        out.add(base("06-multipolygon-oks").multiPolygon("oks_A", "oks", new double[][]{rectCcw(40, 95, 60, 105)}, new double[][]{rectCcw(70, 95, 90, 105)}).point("A", 50, 99, 10));
        // 7. gas pipeline as a MultiLineString whose parts together block the whole width
        out.add(consumer(base("07-multilinestring-restriction"), "A", 50, 10).multiPolyline(1, "gas_pipeline", new double[]{0, 50, 150, 50}, new double[]{150, 50, 300, 50}));
        // 8. existing network as a MultiLineString (two parts, one id)
        out.add(consumer(new Scene("08-multiline-existing").source(300, 0, 0).multiLine(100, 300, new double[]{0, 0, 150, 0}, new double[]{150, 0, 300, 0}), "A", 200, 10));
        // 9. duplicate consecutive coordinates in the existing line and in the building ring
        out.add(new Scene("09-duplicate-coordinates").source(300, 0, 0).line(100, 300, 0, 0, 100, 0, 100, 0, 300, 0)
                .polygon("oks_A", "oks", new double[]{45, 95, 55, 95, 55, 95, 55, 105, 45, 105, 45, 95}).point("A", 50, 99, 10));
        // 10. almost-zero-length segments (0.1 mm) in the existing line and in a forbidden polygon
        out.add(consumer(new Scene("10-almost-zero-segment").source(300, 0, 0).line(100, 300, 0, 0, 100, 0, 100.0001, 0, 300, 0), "A", 50, 10)
                .polygon(1, "park", new double[]{20, 40, 40, 40, 40.0001, 40, 40, 60, 20, 60, 20, 40}));
        // 11. collinear interior vertices (a) vs the plain geometry (b): must be equivalent
        out.add(new Scene("11a-collinear-segments").source(300, 0, 0).line(100, 300, 0, 0, 100, 0, 200, 0, 300, 0)
                .polygon("oks_A", "oks", new double[]{45, 95, 50, 95, 55, 95, 55, 100, 55, 105, 45, 105, 45, 95}).point("A", 50, 99, 10));
        out.add(consumer(base("11b-collinear-plain"), "A", 50, 10));
        // 12. straight route exactly tangent to the clearance zone of a park (edge at 52 - 1.685 for the SMALL class)
        out.add(consumer(base("12-tangent-buffer"), "A", 52, 10).polygon(1, "park", rectCcw(20, 30, 52 - 1.685, 70)));
        // 13. gas pipeline ending exactly on the straight route: touching at one point
        out.add(consumer(base("13-touch-one-point"), "A", 52, 10).polyline(1, "gas_pipeline", 20, 50, 52, 50));
        // 14. two neighbours far from the network: legitimate common junction node
        out.add(consumer(consumer(base("14-common-node"), "A", 35, 10), "B", 65, 10));
        // 17. route crosses another existing line exactly at its interior vertex (line under a road: no tie-in there)
        out.add(consumer(base("17-cross-existing-at-vertex"), "A", 52, 10).line(101, 200, 30, 50, 52, 50, 80, 50).polygon(1, "road", rectCcw(20, 46, 84, 54)));
        // 18. tie-in exactly at the end of the existing line (no chamber there)
        out.add(consumer(new Scene("18-tie-in-at-endpoint").source(300, 0, 0).line(100, 300, 0, 0, 100, 0), "A", 100, 10));
        // 19. nearest tie-in point 0.6 m from an existing chamber: the chamber must be used (10 m rule)
        out.add(consumer(base("19-tie-in-near-chamber").chamber(200, 100, 0), "A", 100.6, 10));
        // 20. narrow corridor between two parks: (a) just wide enough for the SMALL class, (b) too narrow
        out.add(consumer(base("20a-narrow-corridor-fits"), "A", 52, 10).polygon(1, "park", rectCcw(20, 30, 52 - 1.785, 70)).polygon(2, "park", rectCcw(52 + 1.785, 30, 84, 70)));
        out.add(consumer(base("20b-narrow-corridor-blocked"), "A", 52, 10).polygon(1, "park", rectCcw(20, 30, 52 - 1.5, 70)).polygon(2, "park", rectCcw(52 + 1.5, 30, 84, 70)));
        // 21. L-shaped (concave) building
        out.add(base("21-concave-oks").polygon("oks_A", "oks", new double[]{30, 90, 60, 90, 60, 100, 40, 100, 40, 120, 30, 120, 30, 90}).point("A", 34, 110, 10));
        // 22. deep narrow niche open towards the network: the nearest boundary point leads into the niche
        out.add(base("22-deep-niche").polygon("oks_A", "oks", new double[]{30, 90, 50, 90, 50, 130, 54, 130, 54, 90, 74, 90, 74, 140, 30, 140, 30, 90}).point("A", 48, 128, 10));
        // 23. (a) nested buildings: the point lies in both; (b) adjacent buildings sharing an edge
        out.add(base("23a-nested-oks").polygon("oks_B", "oks", rectCcw(30, 80, 70, 120)).polygon("oks_A", "oks", rectCcw(45, 95, 55, 105)).point("A", 50, 100, 10));
        out.add(base("23b-adjacent-oks").polygon("oks_A", "oks", rectCcw(40, 95, 50, 105)).polygon("oks_B", "oks", rectCcw(50, 95, 60, 105)).point("A", 46, 100, 10));
        // 24. self-intersecting (bow-tie) park polygon: repaired by buffer(0) into two triangles
        out.add(consumer(base("24-self-intersecting"), "A", 40, 10).polygon(1, "park", new double[]{20, 30, 60, 70, 60, 30, 20, 70, 20, 30}));
        // 25. 1 cm sliver polygon on the straight route
        out.add(consumer(base("25-sliver"), "A", 52, 10).polygon(1, "park", rectCcw(52, 30, 52.01, 70)));
        // 26. long park edge almost parallel (about 1 degree) to the straight route
        out.add(consumer(base("26-almost-parallel"), "A", 54, 10).polygon(1, "park", new double[]{20, 20, 51.0, 20, 52.3, 90, 20, 90, 20, 20}));
        // 27. gas pipeline and power cable crossing each other exactly on the route
        out.add(consumer(base("27-shared-crossing-point"), "A", 52, 10).polyline(1, "gas_pipeline", 30, 50, 74, 50).polyline(2, "power_cable", 32, 30, 72, 70));
        // 28. two special sections starting (a) 5 mm apart and (b) 0.5 m apart
        out.add(consumer(base("28a-near-coincident-sections"), "A", 52, 10).polyline(1, "gas_pipeline", 30, 50, 74, 50).polyline(2, "gas_pipeline", 30, 50.005, 74, 50.005));
        out.add(consumer(base("28b-close-sections"), "A", 52, 10).polyline(1, "gas_pipeline", 30, 50, 74, 50).polyline(2, "gas_pipeline", 30, 50.5, 74, 50.5));
        // ---- own ОКС exit point (phase 5) ----
        // C: several feasible exits; the nearest boundary (north, 4 m) is blocked by a park, the next (south, 6 m) is feasible
        out.add(base("51-own-oks-blocked-nearest-exit").polygon("oks_A", "oks", rectCcw(40, 95, 60, 105)).point("A", 50, 101, 10).polygon(1, "park", rectCcw(30, 106, 70, 130)));
        // E: foreign building 7 m east: the nearest exit (east wall, 4 m) leads into the foreign clearance zone
        out.add(base("53-own-oks-neighbour").polygon("oks_A", "oks", rectCcw(40, 80, 50, 120)).point("A", 46, 100, 10).polygon("oks_B", "oks", rectCcw(57, 80, 67, 120)));
        // D: building enclosed by a foreign building ring: no feasible exit at all
        out.add(base("52-own-oks-no-exit").polygon("oks_B", "oks", rectCcw(25, 80, 75, 130), rectCw(38, 93, 62, 117)).polygon("oks_A", "oks", rectCcw(45, 100, 55, 110)).point("A", 50, 105, 10));
        // ---- special crossing exemption (phase 6) ----
        // 61. perpendicular road crossing
        out.add(consumer(base("61-road-perpendicular"), "A", 52, 10).polygon(1, "road", rectCcw(0, 40, 300, 50)));
        // 65. zig-zag gas pipeline crossed twice by the straight route
        out.add(consumer(base("65-gas-crossed-twice"), "A", 52, 10).polyline(1, "gas_pipeline", 30, 30, 74, 30, 74, 60, 30, 60));
        // 66. gas pipeline inside a road polygon: overlapping special objects
        out.add(consumer(base("66-road-with-gas"), "A", 52, 10).polygon(1, "road", rectCcw(0, 40, 300, 50)).polyline(2, "gas_pipeline", 0, 45, 300, 45));
        // 67. gas pipeline 1.5 m from the existing line: the special section cannot end at the tie-in chamber
        out.add(consumer(base("67-gas-near-tie-in"), "A", 52, 10).polyline(1, "gas_pipeline", 30, 1.5, 74, 1.5));
        // 68. road extent ending 0.5 m before a gas section: two special zones separated by a 0.5 m base piece
        out.add(consumer(base("68-road-then-gas"), "A", 52, 10).polygon(1, "road", rectCcw(0, 40, 300, 50)).polyline(2, "gas_pipeline", 0, 55.5, 300, 55.5));
        return out;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "src/test/resources/adversarial");
        Files.createDirectories(dir);
        for (Scene s : scenes()) {
            Files.write(dir.resolve(s.name + ".geojson"), s.toGeoJson().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(scenes().size() + " fixtures written to " + dir.toAbsolutePath());
    }
}
