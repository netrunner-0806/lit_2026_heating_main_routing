package ru.lct.heatnet.geo;

import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import java.util.*;

/** Immutable per-geometry boundary index. Exact distance and original edge order resolve ties. */
public final class BoundarySegments {
    private static final double QUERY_RADIUS = 1e-6;
    private static final class Edge {
        final Coordinate a, b; final int order;
        Edge(Coordinate a, Coordinate b, int order) { this.a = a; this.b = b; this.order = order; }
    }
    private final List<Edge> edges = new ArrayList<>();
    private final STRtree index = new STRtree();

    public BoundarySegments(Geometry geometry) {
        if (geometry instanceof Polygonal) {
            for (Polygon p : GeometryUtils.polygons(geometry)) {
                add(p.getExteriorRing());
                for (int i = 0; i < p.getNumInteriorRing(); i++) add(p.getInteriorRingN(i));
            }
        } else for (int i = 0; i < geometry.getNumGeometries(); i++) add(geometry.getGeometryN(i));
        index.build();
    }
    private void add(Geometry line) {
        Coordinate[] cs = line.getCoordinates();
        for (int i = 1; i < cs.length; i++) {
            Edge e = new Edge(cs[i - 1], cs[i], edges.size());
            edges.add(e); index.insert(new Envelope(e.a, e.b), e);
        }
    }
    public double angle(Coordinate a, Coordinate b, Coordinate p) {
        Envelope env = new Envelope(p); env.expandBy(QUERY_RADIUS);
        @SuppressWarnings("unchecked") List<Edge> local = index.query(env);
        Edge best = nearest(local, p);
        // A crossing normally lies on an edge. If it does not, use the exhaustive exact search.
        if (best == null || Distance.pointToSegment(p, best.a, best.b) > QUERY_RADIUS) best = nearest(edges, p);
        return best == null ? 90 : GeometryUtils.acuteAngleDeg(b.x - a.x, b.y - a.y, best.b.x - best.a.x, best.b.y - best.a.y);
    }
    /** Clearance transitions belong to the original edges actually crossed at p, not to the
     * connected buffer of the entire bent object. Intersect their exact finite capsules with
     * the straight route; no polygonal buffer approximation or arbitrary crossing radius. */
    public List<double[]> crossingClearanceIntervals(Coordinate a, Coordinate b, Coordinate p, double radius) {
        List<double[]> result = new ArrayList<>();
        double length = a.distance(b);
        if (length == 0) return result;
        Envelope env = new Envelope(p); env.expandBy(QUERY_RADIUS);
        @SuppressWarnings("unchecked") List<Edge> local = index.query(env);
        for (Edge e : local) {
            if (Distance.pointToSegment(p, e.a, e.b) > QUERY_RADIUS) continue;
            double edgeLength = e.a.distance(e.b);
            if (edgeLength == 0) continue;
            double ux = (b.x-a.x)/length, uy = (b.y-a.y)/length;
            double vx = (e.b.x-e.a.x)/edgeLength, vy = (e.b.y-e.a.y)/edgeLength;
            double cross = ux*vy-uy*vx;
            if (Math.abs(cross) < 1e-12) continue; // a tangent/parallel edge is not crossed
            double px = a.x-e.a.x, py = a.y-e.a.y;
            double[] strip = {0, length};
            if (clip(strip, px*vx+py*vy, ux*vx+uy*vy, 0, edgeLength)
                    && clip(strip, px*vy-py*vx, cross, -radius, radius)) result.add(strip);
            for (Coordinate end : new Coordinate[]{e.a,e.b}) {
                double dx = end.x-a.x, dy = end.y-a.y;
                double t = dx*ux+dy*uy, normal = dx*uy-dy*ux;
                double h2 = radius*radius-normal*normal;
                if (h2 < 0) continue;
                double h = Math.sqrt(h2), lo = Math.max(0,t-h), hi = Math.min(length,t+h);
                if (hi >= lo) result.add(new double[]{lo,hi});
            }
        }
        return result;
    }

    private static boolean clip(double[] interval, double origin, double direction, double min, double max) {
        if (Math.abs(direction) < 1e-12) return origin >= min && origin <= max;
        double a = (min-origin)/direction, b = (max-origin)/direction;
        interval[0] = Math.max(interval[0],Math.min(a,b));
        interval[1] = Math.min(interval[1],Math.max(a,b));
        return interval[1] >= interval[0];
    }
    private static Edge nearest(List<Edge> candidates, Coordinate p) {
        Edge best = null; double min = Double.MAX_VALUE;
        for (Edge e : candidates) {
            double d = Distance.pointToSegment(p, e.a, e.b);
            if (d < min || (d == min && best != null && e.order < best.order)) { min = d; best = e; }
        }
        return best;
    }
}
