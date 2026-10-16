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
    private static Edge nearest(List<Edge> candidates, Coordinate p) {
        Edge best = null; double min = Double.MAX_VALUE;
        for (Edge e : candidates) {
            double d = Distance.pointToSegment(p, e.a, e.b);
            if (d < min || (d == min && best != null && e.order < best.order)) { min = d; best = e; }
        }
        return best;
    }
}
