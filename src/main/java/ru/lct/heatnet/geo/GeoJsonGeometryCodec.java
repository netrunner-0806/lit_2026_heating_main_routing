package ru.lct.heatnet.geo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** GeoJSON geometry <-> JTS (coordinates kept as given, no CRS conversion here). */
public final class GeoJsonGeometryCodec {
    private GeoJsonGeometryCodec() {}

    public static Geometry read(JsonNode geom, GeometryFactory gf) {
        if (geom == null || geom.isNull()) return null;
        String type = geom.path("type").asText(null);
        if (type == null) throw new IllegalArgumentException("geometry without type");
        JsonNode c = geom.get("coordinates");
        switch (type) {
            case "Point":
                return gf.createPoint(coord(c));
            case "MultiPoint":
                return gf.createMultiPointFromCoords(coords(c));
            case "LineString":
                return gf.createLineString(coords(c));
            case "MultiLineString": {
                List<LineString> ls = new ArrayList<>();
                for (JsonNode n : c) ls.add(gf.createLineString(coords(n)));
                return gf.createMultiLineString(ls.toArray(new LineString[0]));
            }
            case "Polygon":
                return polygon(c, gf);
            case "MultiPolygon": {
                List<Polygon> ps = new ArrayList<>();
                for (JsonNode n : c) ps.add(polygon(n, gf));
                return gf.createMultiPolygon(ps.toArray(new Polygon[0]));
            }
            case "GeometryCollection": {
                List<Geometry> gs = new ArrayList<>();
                for (JsonNode n : geom.path("geometries")) gs.add(read(n, gf));
                return gf.createGeometryCollection(gs.toArray(new Geometry[0]));
            }
            default:
                throw new IllegalArgumentException("unsupported geometry type " + type);
        }
    }

    private static Polygon polygon(JsonNode rings, GeometryFactory gf) {
        if (rings == null || !rings.isArray() || rings.size() == 0) throw new IllegalArgumentException("Polygon without rings");
        LinearRing shell = gf.createLinearRing(ring(rings.get(0)));
        List<LinearRing> holes = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) holes.add(gf.createLinearRing(ring(rings.get(i))));
        return gf.createPolygon(shell, holes.toArray(new LinearRing[0]));
    }

    /** A ring needs at least 3 distinct positions (RFC 7946: 4 positions with the closing one); a 2-point "ring" is not a polygon. */
    private static Coordinate[] ring(JsonNode n) {
        Coordinate[] cs = closed(coords(n));
        if (cs.length < 4) throw new IllegalArgumentException("polygon ring with fewer than 3 distinct positions");
        return cs;
    }

    private static Coordinate[] closed(Coordinate[] cs) {
        if (cs.length >= 1 && !cs[0].equals2D(cs[cs.length - 1])) {
            Coordinate[] out = new Coordinate[cs.length + 1];
            System.arraycopy(cs, 0, out, 0, cs.length);
            out[cs.length] = cs[0].copy();
            return out;
        }
        return cs;
    }

    private static Coordinate coord(JsonNode n) {
        if (n == null || !n.isArray() || n.size() < 2) throw new IllegalArgumentException("invalid position " + n);
        JsonNode x = n.get(0), y = n.get(1);
        // only JSON numbers are positions: strings, booleans, nulls and objects must not silently become 0.0
        if (!x.isNumber() || !y.isNumber()) throw new IllegalArgumentException("non-numeric position " + n);
        double lon = x.asDouble(), lat = y.asDouble();
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) throw new IllegalArgumentException("non-finite position " + n);
        return new Coordinate(lon, lat);
    }

    private static Coordinate[] coords(JsonNode n) {
        if (n == null || !n.isArray()) throw new IllegalArgumentException("invalid coordinate array");
        Coordinate[] out = new Coordinate[n.size()];
        for (int i = 0; i < n.size(); i++) out[i] = coord(n.get(i));
        return out;
    }

    // ---- writing ----

    public static void write(JsonGenerator g, Geometry geom, int decimals) throws IOException {
        if (geom == null) { g.writeNull(); return; }
        g.writeStartObject();
        if (geom instanceof Point) {
            g.writeStringField("type", "Point");
            g.writeFieldName("coordinates");
            writeCoord(g, geom.getCoordinate(), decimals);
        } else if (geom instanceof LineString) {
            g.writeStringField("type", "LineString");
            g.writeFieldName("coordinates");
            writeCoords(g, geom.getCoordinates(), decimals);
        } else if (geom instanceof Polygon) {
            g.writeStringField("type", "Polygon");
            g.writeFieldName("coordinates");
            writePolygon(g, (Polygon) geom, decimals);
        } else if (geom instanceof MultiPoint) {
            g.writeStringField("type", "MultiPoint");
            g.writeFieldName("coordinates");
            writeCoords(g, geom.getCoordinates(), decimals);
        } else if (geom instanceof MultiLineString) {
            g.writeStringField("type", "MultiLineString");
            g.writeFieldName("coordinates");
            g.writeStartArray();
            for (int i = 0; i < geom.getNumGeometries(); i++) writeCoords(g, geom.getGeometryN(i).getCoordinates(), decimals);
            g.writeEndArray();
        } else if (geom instanceof MultiPolygon) {
            g.writeStringField("type", "MultiPolygon");
            g.writeFieldName("coordinates");
            g.writeStartArray();
            for (int i = 0; i < geom.getNumGeometries(); i++) writePolygon(g, (Polygon) geom.getGeometryN(i), decimals);
            g.writeEndArray();
        } else {
            throw new IllegalArgumentException("unsupported geometry " + geom.getGeometryType());
        }
        g.writeEndObject();
    }

    private static void writePolygon(JsonGenerator g, Polygon p, int decimals) throws IOException {
        g.writeStartArray();
        writeCoords(g, p.getExteriorRing().getCoordinates(), decimals);
        for (int i = 0; i < p.getNumInteriorRing(); i++) writeCoords(g, p.getInteriorRingN(i).getCoordinates(), decimals);
        g.writeEndArray();
    }

    private static void writeCoords(JsonGenerator g, Coordinate[] cs, int decimals) throws IOException {
        g.writeStartArray();
        for (Coordinate c : cs) writeCoord(g, c, decimals);
        g.writeEndArray();
    }

    public static void writeCoord(JsonGenerator g, Coordinate c, int decimals) throws IOException {
        g.writeStartArray();
        g.writeNumber(round(c.x, decimals));
        g.writeNumber(round(c.y, decimals));
        g.writeEndArray();
    }

    public static BigDecimal round(double v, int decimals) {
        return new BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().scale() < 0
                ? new BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).setScale(1, RoundingMode.HALF_UP)
                : new BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
