package ru.lct.heatnet.geo;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;
import ru.lct.heatnet.config.DiameterSpec;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRule;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.domain.ObjectType;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.domain.SourcePoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streaming GeoJSON reader: walks the FeatureCollection with Jackson's streaming parser, materialises one feature
 * at a time, converts it into the metric domain model and discards the raw JSON. Memory is proportional to the
 * number of geometry coordinates, not to the file size.
 */
public final class GeoJsonStreamReader {

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ParseOptions options;

    public GeoJsonStreamReader(ParseOptions options) {
        this.options = options == null ? ParseOptions.defaults() : options;
    }

    public InputModel read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public InputModel read(InputStream in) throws IOException {
        Diagnostics diag = new Diagnostics();
        List<SourcePoint> sources = new ArrayList<>();
        List<ExistingNetworkLine> lines = new ArrayList<>();
        List<ExistingChamber> chambers = new ArrayList<>();
        List<ConnectionPoint> points = new ArrayList<>();
        List<Restriction> restrictions = new ArrayList<>();
        Set<JsonId> seenIds = new HashSet<>();
        Map<String, Integer> unknownTypes = new HashMap<>();
        Map<String, Integer> unknownRestrictionTypes = new HashMap<>();
        int featureCount = 0;
        boolean isFeatureCollection = false;
        boolean featuresSeen = false;

        try (JsonParser p = FACTORY.createParser(in)) {
            JsonToken t = p.nextToken();
            if (t != JsonToken.START_OBJECT) throw new InputParseException("Input is not a JSON object (expected FeatureCollection)", diag);
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                p.nextToken();
                if ("type".equals(field)) {
                    isFeatureCollection = "FeatureCollection".equals(p.getText());
                } else if ("features".equals(field)) {
                    if (p.currentToken() != JsonToken.START_ARRAY) throw new InputParseException("'features' must be an array", diag);
                    featuresSeen = true;
                    JsonToken ft;
                    while ((ft = p.nextToken()) != JsonToken.END_ARRAY) {
                        if (ft == null) throw new InputParseException("Unexpected end of input inside the 'features' array", diag);
                        int idx = featureCount++;
                        if (ft != JsonToken.START_OBJECT) {
                            // a null / scalar / nested array is not a Feature: report it and keep reading the rest of the file
                            diag.warn("BAD_FEATURE", "features[" + idx + "] is not a Feature object (" + ft + "); skipped", idx, null);
                            p.skipChildren();
                            continue;
                        }
                        JsonNode feature = MAPPER.readTree(p);
                        try {
                            handleFeature(feature, idx, diag, sources, lines, chambers, points, restrictions, seenIds, unknownTypes, unknownRestrictionTypes);
                        } catch (RuntimeException ex) {
                            // a malformed connection point changes the answer (a silently dropped consumer avoids its
                            // penalty): it rejects the input; any other malformed feature is skipped with a warning
                            String ot = feature.path("properties").path("object_type").asText("");
                            JsonId fid = JsonId.fromJson(feature.path("properties").get("id"));
                            if (isConsumer(ot)) diag.error("FEATURE_INVALID", "Invalid " + ot + " feature: " + ex.getMessage(), idx, fid);
                            else diag.warn("FEATURE_SKIPPED", "Feature skipped: " + ex.getMessage(), idx, fid);
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new InputParseException("Invalid JSON: " + ex.getOriginalMessage(), diag, ex);
        }
        if (!isFeatureCollection) diag.warn("NOT_FEATURE_COLLECTION", "Top-level 'type' is not 'FeatureCollection'");
        if (!featuresSeen) throw new InputParseException("No 'features' array in the input", diag);
        for (Map.Entry<String, Integer> e : unknownTypes.entrySet())
            diag.warn("UNKNOWN_OBJECT_TYPE", "Ignored " + e.getValue() + " feature(s) with object_type='" + e.getKey() + "'");
        for (Map.Entry<String, Integer> e : unknownRestrictionTypes.entrySet())
            diag.warn("UNKNOWN_RESTRICTION_TYPE", e.getValue() + " restriction(s) with unsupported restriction_type='" + e.getKey()
                    + "' handled by policy " + options.unknownRestrictionPolicy());
        if (lines.isEmpty()) diag.error("NO_HEAT_NETWORK", "Input contains no usable heat_network lines: nothing to connect to");
        if (points.isEmpty()) diag.error("NO_CONNECTION_POINTS", "Input contains no usable oks_connection_point features");
        if (sources.isEmpty()) diag.warn("NO_SOURCE", "Input contains no 'source' feature (not needed for the 2D calculation)");
        diag.info("INPUT_SUMMARY", String.format(java.util.Locale.ROOT, "features=%d, source=%d, heat_network=%d, heat_chamber=%d, oks_connection_point=%d, restriction=%d",
                featureCount, sources.size(), lines.size(), chambers.size(), points.size(), restrictions.size()));
        return new InputModel(sources, lines, chambers, points, restrictions, diag, featureCount);
    }

    private void handleFeature(JsonNode feature, int idx, Diagnostics diag, List<SourcePoint> sources, List<ExistingNetworkLine> lines,
                               List<ExistingChamber> chambers, List<ConnectionPoint> points, List<Restriction> restrictions,
                               Set<JsonId> seenIds, Map<String, Integer> unknownTypes, Map<String, Integer> unknownRestrictionTypes) {
        JsonNode props = feature.path("properties");
        String objectTypeCode = props.path("object_type").asText(null);
        ObjectType type = ObjectType.fromCode(objectTypeCode);
        JsonId id = JsonId.fromJson(props.get("id"));
        if (type == null) {
            unknownTypes.merge(objectTypeCode == null ? "<missing>" : objectTypeCode, 1, Integer::sum);
            return;
        }
        if (id == null) {
            diag.warn("MISSING_ID", "Feature of type " + type.code() + " has no 'id'; skipped", idx, null);
            return;
        }
        if (!seenIds.add(id)) diag.warn("DUPLICATE_ID", "Duplicate id " + id + " (type " + type.code() + ")", idx, id);

        JsonNode geomNode = feature.get("geometry");
        if (geomNode == null || geomNode.isNull()) {
            diag.warn("MISSING_GEOMETRY", "Feature has no geometry; skipped", idx, id);
            return;
        }
        Geometry wgs = GeoJsonGeometryCodec.read(geomNode, CrsTransformer.WGS84_FACTORY);
        if (wgs.isEmpty()) {
            if (type == ObjectType.OKS_CONNECTION_POINT) diag.error("EMPTY_GEOMETRY", "Connection point has empty geometry", idx, id);
            else diag.warn("EMPTY_GEOMETRY", "Feature has empty geometry; skipped", idx, id);
            return;
        }
        Coordinate outside = outsideWgs84(wgs);
        if (outside != null) {
            // the projection would silently produce garbage metres for such positions
            String msg = "Feature has a position outside WGS84 (lon " + outside.x + ", lat " + outside.y + ")";
            if (type == ObjectType.OKS_CONNECTION_POINT) diag.error("COORDINATE_OUT_OF_RANGE", msg, idx, id);
            else diag.warn("COORDINATE_OUT_OF_RANGE", msg + "; skipped", idx, id);
            return;
        }
        switch (type) {
            case SOURCE: {
                Point pt = requirePoint(wgs, type, idx, id, diag);
                if (pt == null) return;
                sources.add(new SourcePoint(id, (Point) CrsTransformer.get().toMetric(pt), lonLat(pt), idx));
                break;
            }
            case HEAT_CHAMBER: {
                Point pt = requirePoint(wgs, type, idx, id, diag);
                if (pt == null) return;
                chambers.add(new ExistingChamber(id, (Point) CrsTransformer.get().toMetric(pt), lonLat(pt), idx));
                break;
            }
            case OKS_CONNECTION_POINT: {
                Point pt = requirePoint(wgs, type, idx, id, diag);
                if (pt == null) return;
                JsonNode flow = props.get("flow_tph");
                if (flow == null || !flow.isNumber() || !Double.isFinite(flow.asDouble()) || flow.asDouble() <= 0) {
                    diag.error("MISSING_FLOW", "oks_connection_point without a positive numeric 'flow_tph'", idx, id);
                    return;
                }
                points.add(new ConnectionPoint(id, (Point) CrsTransformer.get().toMetric(pt), flow.asDouble(), lonLat(pt), idx));
                break;
            }
            case HEAT_NETWORK: {
                Geometry metric = CrsTransformer.get().toMetric(wgs);
                JsonNode d = props.get("diameter");
                boolean missing = d == null || !d.isNumber() || d.asInt() <= 0;
                int diameter = missing ? DiameterTable.largest().du() : d.asInt();
                if (missing) diag.warn("MISSING_DIAMETER", "heat_network without numeric 'diameter'; envelope of the largest DU assumed", idx, id);
                DiameterSpec spec = DiameterTable.nearestForExisting(diameter);
                if (!missing && spec.du() != diameter)
                    diag.warn("NON_STANDARD_DIAMETER", "heat_network diameter " + diameter + " is not in Table 1; nearest DU " + spec.du() + " used for the envelope", idx, id);
                if (metric instanceof LineString) {
                    lines.add(new ExistingNetworkLine(id, (LineString) metric, diameter, spec, missing, idx));
                } else if (metric instanceof MultiLineString) {
                    diag.warn("MULTILINE_NETWORK", "heat_network is a MultiLineString; each part is treated as a separate line with the same id", idx, id);
                    for (int i = 0; i < metric.getNumGeometries(); i++)
                        lines.add(new ExistingNetworkLine(id, (LineString) metric.getGeometryN(i), diameter, spec, missing, idx));
                } else {
                    diag.warn("BAD_GEOMETRY_TYPE", "heat_network must be a LineString, got " + wgs.getGeometryType() + "; skipped", idx, id);
                }
                break;
            }
            case RESTRICTION: {
                String rtype = props.path("restriction_type").asText(null);
                if (rtype != null) rtype = rtype.trim().toLowerCase();
                RestrictionRule rule = RestrictionRules.forType(rtype).orElse(null);
                if (rule == null) {
                    unknownRestrictionTypes.merge(rtype == null ? "<missing>" : rtype, 1, Integer::sum);
                    if (options.unknownRestrictionPolicy() == ParseOptions.UnknownRestrictionPolicy.FORBIDDEN)
                        rule = RestrictionRules.unknownAsForbidden(rtype == null ? "unknown" : rtype);
                }
                Geometry metric = CrsTransformer.get().toMetric(wgs);
                if (metric instanceof Polygonal && !metric.isValid()) {
                    // GeometryFixer keeps every lobe of a self-intersecting ring (buffer(0) silently drops lobes of
                    // opposite orientation, i.e. part of a forbidden area)
                    Geometry fixed = org.locationtech.jts.geom.util.GeometryFixer.fix(metric);
                    if (fixed.isEmpty() || !fixed.isValid()) {
                        diag.warn("INVALID_GEOMETRY", "restriction polygon is invalid and could not be repaired; skipped", idx, id);
                        return;
                    }
                    diag.warn("INVALID_GEOMETRY_REPAIRED", "restriction polygon was invalid and has been repaired (GeometryFixer)", idx, id);
                    metric = fixed;
                }
                if (metric instanceof Point) {
                    diag.warn("POINT_RESTRICTION", "Point restrictions are not supported; skipped", idx, id);
                    return;
                }
                restrictions.add(new Restriction(id, metric, rtype, rule, idx));
                break;
            }
            default:
                break;
        }
    }

    private static boolean isConsumer(String objectType) { return ObjectType.OKS_CONNECTION_POINT.code().equals(objectType); }

    /** First position outside the legal WGS84 range (lon -180..180, lat -90..90), or null. */
    private static Coordinate outsideWgs84(Geometry g) {
        for (Coordinate c : g.getCoordinates()) {
            if (!(c.x >= -180 && c.x <= 180 && c.y >= -90 && c.y <= 90)) return c;
        }
        return null;
    }

    private static Point requirePoint(Geometry g, ObjectType type, int idx, JsonId id, Diagnostics diag) {
        if (g instanceof Point) return (Point) g;
        if (g.getNumGeometries() == 1 && g.getGeometryN(0) instanceof Point) return (Point) g.getGeometryN(0);
        diag.warn("BAD_GEOMETRY_TYPE", type.code() + " must be a Point, got " + g.getGeometryType() + "; skipped", idx, id);
        return null;
    }

    private static double[] lonLat(Point p) {
        Coordinate c = p.getCoordinate();
        return new double[]{c.x, c.y};
    }
}
