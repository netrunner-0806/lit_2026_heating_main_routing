package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.restrictions.Exemptions;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.ObstacleSpace;
import ru.lct.heatnet.restrictions.SegmentCheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans the final approach segment for a connection point (annex 2.2): from the nearest boundary point of the own
 * ОКС polygon straight to the point; the own-polygon clearance is not applied on this segment. Fallback boundary
 * points (sampled along the boundary, nearest first) are used only when the nearest one is not feasible.
 */
public final class ApproachPlanner {

    private static final double BOUNDARY_SAMPLE_STEP = 2.0;
    private static final int MAX_FALLBACKS = 40;
    private static final int MAX_RESULTS = 8;

    private final ObstacleSpace space;

    public ApproachPlanner(ObstacleSpace space) {
        this.space = space;
    }

    public List<Approach> plan(ConnectionPoint cp) {
        Coordinate p = cp.coordinate();
        Obstacle own = findOwnPolygon(p);
        List<Approach> out = new ArrayList<>();
        if (own == null) {
            out.add(new Approach(cp, null, null, p, null, true));
            return out;
        }
        double required = space.requiredDistance(own);
        Geometry boundary = own.geometry().getBoundary();
        Coordinate nearest = GeometryUtils.nearestPoint(boundary, p);
        List<Coordinate> candidates = new ArrayList<>();
        candidates.add(nearest);
        for (Coordinate c : sampleBoundary(own.geometry())) {
            boolean dup = false;
            for (Coordinate e : candidates) if (e.distance(c) < 1.0) { dup = true; break; }
            if (!dup) candidates.add(c);
        }
        candidates.subList(1, candidates.size()).sort((a, b) -> Double.compare(a.distance(p), b.distance(p)));
        if (candidates.size() > MAX_FALLBACKS + 1) candidates = new ArrayList<>(candidates.subList(0, MAX_FALLBACKS + 1));
        boolean inside = own.prepared().contains(GeometryUtils.point(p));
        Set<Integer> ignoreOwn = Collections.singleton(own.index());
        // only the OWN polygon is exempt on the final segment; every other restriction applies in full
        for (int i = 0; i < candidates.size(); i++) {
            Coordinate b = candidates.get(i);
            double[] dir = outwardDirection(own, p, b, inside);
            if (dir == null) continue;
            Coordinate a = freePoint(own, b, dir, required, ignoreOwn);
            if (a == null) continue;
            SegmentCheck sc = space.check(p, a, new Exemptions().ignore(ignoreOwn)); // traversed P -> A
            if (sc.valid()) out.add(new Approach(cp, own, b, a, sc, i == 0));
            if (out.size() >= MAX_RESULTS) break;
        }
        return out;
    }

    /** Own polygon: the ОКС polygon containing the point, else the ОКС polygon closer than its clearance. */
    Obstacle findOwnPolygon(Coordinate p) {
        Point pt = GeometryUtils.point(p);
        Obstacle best = null;
        double bestDist = Double.MAX_VALUE;
        Envelope env = new Envelope(p);
        env.expandBy(20);
        for (Obstacle o : space.query(env)) {
            if (!o.isOks() || !o.isPolygonal()) continue;
            if (o.prepared().covers(pt)) return o;
            double d = o.geometry().distance(pt);
            if (d < space.requiredDistance(o) && d < bestDist) { bestDist = d; best = o; }
        }
        return best;
    }

    private static List<Coordinate> sampleBoundary(Geometry g) {
        List<Coordinate> out = new ArrayList<>();
        for (Polygon poly : GeometryUtils.polygons(g)) {
            out.addAll(ExistingNetworkIndex.samplePoints(poly.getExteriorRing(), BOUNDARY_SAMPLE_STEP));
            for (int i = 0; i < poly.getNumInteriorRing(); i++)
                out.addAll(ExistingNetworkIndex.samplePoints(poly.getInteriorRingN(i), BOUNDARY_SAMPLE_STEP));
        }
        return out;
    }

    /** Unit direction pointing from the polygon outward through the boundary point b. */
    private static double[] outwardDirection(Obstacle own, Coordinate p, Coordinate b, boolean inside) {
        double[] d;
        if (p.distance(b) > 1e-6) {
            d = inside ? GeometryUtils.unit(p, b) : GeometryUtils.unit(b, p);
        } else {
            d = null;
        }
        // verify / derive using a small probe: the point slightly outside must not be inside the polygon
        if (d != null) {
            Coordinate probe = GeometryUtils.offset(b, d, 0.05);
            if (!own.prepared().contains(GeometryUtils.point(probe))) return d;
        }
        // derive from the nearest boundary edge normal
        double[] edge = nearestEdgeDirection(own.geometry(), b);
        if (edge == null) return d;
        double[] n1 = {-edge[1], edge[0]}, n2 = {edge[1], -edge[0]};
        Coordinate p1 = GeometryUtils.offset(b, n1, 0.05);
        if (!own.prepared().contains(GeometryUtils.point(p1))) return n1;
        Coordinate p2 = GeometryUtils.offset(b, n2, 0.05);
        if (!own.prepared().contains(GeometryUtils.point(p2))) return n2;
        return d;
    }

    private static double[] nearestEdgeDirection(Geometry g, Coordinate b) {
        double best = Double.MAX_VALUE;
        double[] dir = null;
        for (Polygon poly : GeometryUtils.polygons(g)) {
            List<LineString> rings = new ArrayList<>();
            rings.add(poly.getExteriorRing());
            for (int i = 0; i < poly.getNumInteriorRing(); i++) rings.add(poly.getInteriorRingN(i));
            for (LineString ring : rings) {
                Coordinate[] cs = ring.getCoordinates();
                for (int i = 0; i + 1 < cs.length; i++) {
                    double d = org.locationtech.jts.algorithm.Distance.pointToSegment(b, cs[i], cs[i + 1]);
                    if (d < best) { best = d; dir = GeometryUtils.unit(cs[i], cs[i + 1]); }
                }
            }
        }
        return dir;
    }

    /** First point along the outward ray that is outside the own clearance zone and outside other forbidden zones. */
    private Coordinate freePoint(Obstacle own, Coordinate b, double[] dir, double required, Set<Integer> ignoreOwn) {
        Exemptions ex = new Exemptions().ignore(ignoreOwn);
        for (double t = required + 0.05; t <= required * 3 + 5; t += 0.25) {
            Coordinate a = GeometryUtils.offset(b, dir, t);
            if (own.geometry().distance(GeometryUtils.point(a)) < required + 0.02) continue;
            if (space.insideForbidden(a, ex)) return null; // ray enters another forbidden zone: not usable
            return a;
        }
        return null;
    }
}
