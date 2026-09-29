package ru.lct.heatnet.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.CrsTransformer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

/** Meaning-preserving transformations of an input GeoJSON document (Jackson tree), for metamorphic tests. */
public final class GeoJsonDoc {
    private GeoJsonDoc() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static ObjectNode loadResource(String path) throws IOException {
        try (InputStream in = GeoJsonDoc.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing test resource " + path);
            return (ObjectNode) SolveKit.MAPPER.readTree(in);
        }
    }

    public static ObjectNode loadFile(Path p) throws IOException {
        return (ObjectNode) SolveKit.MAPPER.readTree(Files.readAllBytes(p));
    }

    public static ObjectNode copy(ObjectNode doc) { return doc.deepCopy(); }

    public static ArrayNode features(ObjectNode doc) { return (ArrayNode) doc.get("features"); }

    // ------------------------------------------------------------------------------------------------------------

    /** (a) random permutation of the features array. */
    public static ObjectNode shuffleFeatures(ObjectNode doc, long seed) {
        ObjectNode d = copy(doc);
        List<JsonNode> fs = new ArrayList<>();
        features(d).forEach(fs::add);
        Collections.shuffle(fs, new Random(seed));
        ArrayNode arr = F.arrayNode();
        fs.forEach(arr::add);
        d.set("features", arr);
        return d;
    }

    /** (b) reversal of coordinate order of every LineString / MultiLineString part (heat_network and restrictions). */
    public static ObjectNode reverseLines(ObjectNode doc) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (g == null || g.isNull()) continue;
            String t = g.path("type").asText();
            if ("LineString".equals(t)) g.set("coordinates", reversed((ArrayNode) g.get("coordinates")));
            else if ("MultiLineString".equals(t)) {
                ArrayNode parts = F.arrayNode();
                for (JsonNode part : g.get("coordinates")) parts.add(reversed((ArrayNode) part));
                g.set("coordinates", parts);
            }
        }
        return d;
    }

    /** (c) ring direction CW <-> CCW of every Polygon / MultiPolygon ring (closed rings stay closed). */
    public static ObjectNode reverseRings(ObjectNode doc) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (g == null || g.isNull()) continue;
            String t = g.path("type").asText();
            if ("Polygon".equals(t)) g.set("coordinates", reversedRings((ArrayNode) g.get("coordinates")));
            else if ("MultiPolygon".equals(t)) {
                ArrayNode polys = F.arrayNode();
                for (JsonNode poly : g.get("coordinates")) polys.add(reversedRings((ArrayNode) poly));
                g.set("coordinates", polys);
            }
        }
        return d;
    }

    /** (d) splits every LineString with >= 3 vertices matching the predicate into a 2-part MultiLineString (parts share the split vertex). */
    public static ObjectNode splitToMultiLine(ObjectNode doc, Predicate<JsonNode> which) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (g == null || g.isNull() || !"LineString".equals(g.path("type").asText()) || !which.test(f)) continue;
            ArrayNode cs = (ArrayNode) g.get("coordinates");
            if (cs.size() < 3) continue;
            int k = cs.size() / 2;
            ArrayNode p1 = F.arrayNode(), p2 = F.arrayNode();
            for (int i = 0; i <= k; i++) p1.add(cs.get(i).deepCopy());
            for (int i = k; i < cs.size(); i++) p2.add(cs.get(i).deepCopy());
            g.put("type", "MultiLineString");
            g.set("coordinates", F.arrayNode().add(p1).add(p2));
        }
        return d;
    }

    /** (d) reverses the order of the parts of every MultiLineString. */
    public static ObjectNode reverseMultiLineParts(ObjectNode doc) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (g == null || g.isNull() || !"MultiLineString".equals(g.path("type").asText())) continue;
            g.set("coordinates", reversed((ArrayNode) g.get("coordinates")));
        }
        return d;
    }

    /** (e) every numeric feature id becomes its decimal string ("123"). */
    public static ObjectNode stringifyIds(ObjectNode doc) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode p = (ObjectNode) f.get("properties");
            JsonNode id = p.get("id");
            if (id != null && id.isNumber()) p.put("id", id.isIntegralNumber() ? Long.toString(id.longValue()) : id.asText());
        }
        return d;
    }

    /** (f) inserts the metric midpoint into every segment longer than 2 m (lines, multi-lines and polygon rings). */
    public static ObjectNode addMidVertices(ObjectNode doc) { return addMidVertices(doc, f -> true); }

    /** (f) restricted to features of one object_type. */
    public static ObjectNode addMidVerticesOf(ObjectNode doc, String objectType) {
        return addMidVertices(doc, f -> objectType.equals(f.path("properties").path("object_type").asText()));
    }

    public static ObjectNode addMidVertices(ObjectNode doc, Predicate<JsonNode> which) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (g == null || g.isNull() || !which.test(f)) continue;
            String t = g.path("type").asText();
            switch (t) {
                case "LineString": g.set("coordinates", withMidpoints((ArrayNode) g.get("coordinates"))); break;
                case "MultiLineString":
                case "Polygon": {
                    ArrayNode parts = F.arrayNode();
                    for (JsonNode part : g.get("coordinates")) parts.add(withMidpoints((ArrayNode) part));
                    g.set("coordinates", parts);
                    break;
                }
                case "MultiPolygon": {
                    ArrayNode polys = F.arrayNode();
                    for (JsonNode poly : g.get("coordinates")) {
                        ArrayNode rings = F.arrayNode();
                        for (JsonNode ring : poly) rings.add(withMidpoints((ArrayNode) ring));
                        polys.add(rings);
                    }
                    g.set("coordinates", polys);
                    break;
                }
                default: break;
            }
        }
        return d;
    }

    /** (g) random permutation of the property keys of every feature and of the feature members themselves. */
    public static ObjectNode permuteKeys(ObjectNode doc, long seed) {
        Random rnd = new Random(seed);
        ObjectNode d = copy(doc);
        ArrayNode arr = F.arrayNode();
        for (JsonNode f : features(d)) {
            ObjectNode nf = permuted((ObjectNode) f, rnd);
            nf.set("properties", permuted((ObjectNode) f.get("properties"), rnd));
            arr.add(nf);
        }
        d.set("features", arr);
        return permuted(d, rnd);
    }

    /** (h) rigid translation of every coordinate by (dx, dy) metres in EPSG:32637, converted back to WGS84. */
    public static ObjectNode translate(ObjectNode doc, double dx, double dy) {
        return mapCoordinates(doc, ll -> {
            Coordinate m = CrsTransformer.get().toMetric(ll[0], ll[1]);
            return CrsTransformer.get().toWgs84(m.x + dx, m.y + dy);
        });
    }

    /** Applies the function to every [lon, lat] position of every geometry. */
    public static ObjectNode mapCoordinates(ObjectNode doc, Function<double[], double[]> fn) {
        ObjectNode d = copy(doc);
        for (JsonNode f : features(d)) {
            ObjectNode g = (ObjectNode) f.get("geometry");
            if (g == null || g.isNull()) continue;
            g.set("coordinates", mapPositions(g.get("coordinates"), fn));
        }
        return d;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static JsonNode mapPositions(JsonNode node, Function<double[], double[]> fn) {
        if (node.isArray() && node.size() >= 2 && node.get(0).isNumber()) {
            double[] r = fn.apply(new double[]{node.get(0).asDouble(), node.get(1).asDouble()});
            return F.arrayNode().add(round9(r[0])).add(round9(r[1]));
        }
        ArrayNode out = F.arrayNode();
        for (JsonNode child : node) out.add(mapPositions(child, fn));
        return out;
    }

    private static double round9(double v) { return Math.round(v * 1e9) / 1e9; }

    private static ArrayNode reversed(ArrayNode a) {
        ArrayNode out = F.arrayNode();
        for (int i = a.size() - 1; i >= 0; i--) out.add(a.get(i).deepCopy());
        return out;
    }

    private static ArrayNode reversedRings(ArrayNode rings) {
        ArrayNode out = F.arrayNode();
        for (JsonNode ring : rings) out.add(reversed((ArrayNode) ring));
        return out;
    }

    private static ArrayNode withMidpoints(ArrayNode cs) {
        ArrayNode out = F.arrayNode();
        for (int i = 0; i < cs.size(); i++) {
            out.add(cs.get(i).deepCopy());
            if (i + 1 < cs.size()) {
                Coordinate a = CrsTransformer.get().toMetric(cs.get(i).get(0).asDouble(), cs.get(i).get(1).asDouble());
                Coordinate b = CrsTransformer.get().toMetric(cs.get(i + 1).get(0).asDouble(), cs.get(i + 1).get(1).asDouble());
                if (a.distance(b) < 2.0) continue;
                double[] mid = CrsTransformer.get().toWgs84((a.x + b.x) / 2, (a.y + b.y) / 2);
                out.add(F.arrayNode().add(round9(mid[0])).add(round9(mid[1])));
            }
        }
        return out;
    }

    private static ObjectNode permuted(ObjectNode o, Random rnd) {
        List<Map.Entry<String, JsonNode>> es = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) es.add(it.next());
        Collections.shuffle(es, rnd);
        ObjectNode out = F.objectNode();
        for (Map.Entry<String, JsonNode> e : es) out.set(e.getKey(), e.getValue());
        return out;
    }
}
