package ru.lct.heatnet.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.domain.SourcePoint;
import ru.lct.heatnet.geo.CrsTransformer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serialises a metric InputModel back to an input GeoJSON (WGS84) so that generated cases can be replayed. */
public final class InputGeoJson {
    private InputGeoJson() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static ObjectNode toDocument(InputModel m) {
        ObjectNode doc = F.objectNode();
        doc.put("type", "FeatureCollection");
        ObjectNode crs = doc.putObject("crs");
        crs.put("type", "name");
        crs.putObject("properties").put("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
        ArrayNode fs = doc.putArray("features");
        for (SourcePoint s : m.sources()) fs.add(feature(s.id(), "source", s.geometry(), null, null));
        for (ExistingNetworkLine l : m.networkLines()) {
            ObjectNode f = feature(l.id(), "heat_network", l.geometry(), null, null);
            if (!l.diameterMissing()) ((ObjectNode) f.get("properties")).put("diameter", l.diameter());
            fs.add(f);
        }
        for (ExistingChamber c : m.chambers()) fs.add(feature(c.id(), "heat_chamber", c.geometry(), null, null));
        for (Restriction r : m.restrictions()) fs.add(feature(r.id(), "restriction", r.geometry(), "restriction_type", r.restrictionType()));
        for (ConnectionPoint cp : m.connectionPoints()) {
            ObjectNode f = feature(cp.id(), "oks_connection_point", cp.geometry(), null, null);
            ((ObjectNode) f.get("properties")).put("flow_tph", cp.flowTph());
            fs.add(f);
        }
        return doc;
    }

    public static byte[] toBytes(InputModel m) throws IOException {
        return SolveKit.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(toDocument(m));
    }

    public static void write(InputModel m, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, toBytes(m));
    }

    private static ObjectNode feature(JsonId id, String objectType, Geometry metric, String extraKey, String extraValue) {
        ObjectNode f = F.objectNode();
        f.put("type", "Feature");
        ObjectNode p = f.putObject("properties");
        p.set("id", id.toJson());
        p.put("object_type", objectType);
        if (extraKey != null) p.put(extraKey, extraValue);
        f.set("geometry", geometry(metric));
        return f;
    }

    private static ObjectNode geometry(Geometry g) {
        ObjectNode out = F.objectNode();
        if (g instanceof Point) {
            out.put("type", "Point");
            out.set("coordinates", position(g.getCoordinate()));
        } else if (g instanceof LineString) {
            out.put("type", "LineString");
            out.set("coordinates", positions(g.getCoordinates()));
        } else if (g instanceof Polygon) {
            out.put("type", "Polygon");
            out.set("coordinates", rings((Polygon) g));
        } else if (g instanceof MultiLineString) {
            out.put("type", "MultiLineString");
            ArrayNode parts = out.putArray("coordinates");
            for (int i = 0; i < g.getNumGeometries(); i++) parts.add(positions(g.getGeometryN(i).getCoordinates()));
        } else if (g instanceof MultiPolygon) {
            out.put("type", "MultiPolygon");
            ArrayNode polys = out.putArray("coordinates");
            for (int i = 0; i < g.getNumGeometries(); i++) polys.add(rings((Polygon) g.getGeometryN(i)));
        } else throw new IllegalArgumentException("unsupported geometry " + g.getGeometryType());
        return out;
    }

    private static ArrayNode rings(Polygon p) {
        ArrayNode rs = F.arrayNode();
        rs.add(positions(p.getExteriorRing().getCoordinates()));
        for (int i = 0; i < p.getNumInteriorRing(); i++) rs.add(positions(p.getInteriorRingN(i).getCoordinates()));
        return rs;
    }

    private static ArrayNode positions(Coordinate[] cs) {
        ArrayNode a = F.arrayNode();
        for (Coordinate c : cs) a.add(position(c));
        return a;
    }

    private static ArrayNode position(Coordinate metric) {
        double[] ll = CrsTransformer.get().toWgs84(metric.x, metric.y);
        return F.arrayNode().add(Math.round(ll[0] * 1e9) / 1e9).add(Math.round(ll[1] * 1e9) / 1e9);
    }
}
