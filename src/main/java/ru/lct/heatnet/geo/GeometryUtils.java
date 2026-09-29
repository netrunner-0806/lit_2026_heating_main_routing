package ru.lct.heatnet.geo;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.List;

/** Small geometric helpers shared by routing, topology and validation. */
public final class GeometryUtils {
    private GeometryUtils() {}

    public static final GeometryFactory GF = CrsTransformer.METRIC_FACTORY;

    public static Point point(Coordinate c) { return GF.createPoint(c); }

    public static LineString line(List<Coordinate> coords) { return GF.createLineString(coords.toArray(new Coordinate[0])); }

    public static LineString line(Coordinate a, Coordinate b) { return GF.createLineString(new Coordinate[]{a, b}); }

    public static double dist(Coordinate a, Coordinate b) { return a.distance(b); }

    /** Change of direction at vertex b between a->b and b->c, in degrees (0 = straight ahead, 180 = U-turn). */
    public static double turnAngleDeg(Coordinate a, Coordinate b, Coordinate c) {
        double d1 = Math.atan2(b.y - a.y, b.x - a.x);
        double d2 = Math.atan2(c.y - b.y, c.x - b.x);
        double diff = Math.abs(Angle.normalize(d2 - d1));
        return Math.toDegrees(diff);
    }

    /** Acute angle (0..90) between two direction vectors, degrees. */
    public static double acuteAngleDeg(double dx1, double dy1, double dx2, double dy2) {
        double a1 = Math.atan2(dy1, dx1);
        double a2 = Math.atan2(dy2, dx2);
        double d = Math.abs(Angle.normalize(a2 - a1));
        if (d > Math.PI / 2) d = Math.PI - d;
        return Math.toDegrees(d);
    }

    /** Nearest point of geometry g to coordinate c. */
    public static Coordinate nearestPoint(Geometry g, Coordinate c) {
        Coordinate[] cs = DistanceOp.nearestPoints(g, GF.createPoint(c));
        return cs[0];
    }

    /** Length of the polyline. */
    public static double length(List<Coordinate> coords) {
        double s = 0;
        for (int i = 1; i < coords.size(); i++) s += coords.get(i - 1).distance(coords.get(i));
        return s;
    }

    /** Removes consecutive duplicate coordinates (within tol). */
    public static List<Coordinate> dedupe(List<Coordinate> coords, double tol) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : coords) {
            if (out.isEmpty() || out.get(out.size() - 1).distance(c) > tol) out.add(c);
        }
        return out;
    }

    /** Removes collinear interior vertices (turn < tolDeg). */
    public static List<Coordinate> removeCollinear(List<Coordinate> coords, double tolDeg) {
        if (coords.size() < 3) return new ArrayList<>(coords);
        List<Coordinate> out = new ArrayList<>();
        out.add(coords.get(0));
        for (int i = 1; i < coords.size() - 1; i++) {
            Coordinate a = out.get(out.size() - 1), b = coords.get(i), c = coords.get(i + 1);
            if (turnAngleDeg(a, b, c) > tolDeg) out.add(b);
        }
        out.add(coords.get(coords.size() - 1));
        return out;
    }

    /** Sub-line of a polyline between two length indices. */
    public static LineString subLine(LineString line, double from, double to) {
        LengthIndexedLine lil = new LengthIndexedLine(line);
        return (LineString) lil.extractLine(from, to);
    }

    /** Position along the polyline (length index) of the closest point to c. */
    public static double project(LineString line, Coordinate c) {
        return new LengthIndexedLine(line).project(c);
    }

    public static Coordinate extractPoint(LineString line, double index) {
        return new LengthIndexedLine(line).extractPoint(index);
    }

    /** Unit direction of segment a->b. */
    public static double[] unit(Coordinate a, Coordinate b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double l = Math.hypot(dx, dy);
        if (l == 0) return new double[]{0, 0};
        return new double[]{dx / l, dy / l};
    }

    public static Coordinate offset(Coordinate a, double[] unit, double dist) {
        return new Coordinate(a.x + unit[0] * dist, a.y + unit[1] * dist);
    }

    /** All polygons of a (Multi)Polygon geometry. */
    public static List<Polygon> polygons(Geometry g) {
        List<Polygon> out = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry p = g.getGeometryN(i);
            if (p instanceof Polygon) out.add((Polygon) p);
            else if (p.getNumGeometries() > 1) out.addAll(polygons(p));
        }
        return out;
    }

    public static LineSegment segment(Coordinate a, Coordinate b) { return new LineSegment(a, b); }
}
