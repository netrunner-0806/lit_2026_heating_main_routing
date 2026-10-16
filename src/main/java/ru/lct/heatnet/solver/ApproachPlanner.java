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
    private final java.util.Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
    public String rejectionSummary() { return rejected.toString(); }

    public ApproachPlanner(ObstacleSpace space) {
        this.space = space;
    }

    /** A local necessary condition, independent of graph sampling. This deliberately proves only this case. */
    public String provenTerminalConflict(ConnectionPoint cp) {
        Point point=GeometryUtils.point(cp.coordinate());
        for(Obstacle o:space.obstacles()) if(o.isExistingNetwork() && o.geometry().distance(point)<=0.01) return null;
        for(Obstacle o:space.query(point.getEnvelopeInternal())) {
            String type=o.rule().type();
            if(!o.isPolygonal() || !("road".equals(type)||"tram_tracks".equals(type))) continue;
            if(o.geometry().contains(point) && o.geometry().getBoundary().distance(point)>0.01)
                return "GENUINELY_UNREACHABLE: consumer " + cp.id() + " is strictly inside " + o.describe()
                        + "; the required 3 m exterior continuation cannot fit at this terminal; the chamber-on-existing-network exception does not apply";
        }
        return null;
    }

    public List<Approach> plan(ConnectionPoint cp) {
        return plan(cp, false);
    }

    /** Only used after the ordinary search failed: inspect the complete sampled exterior, nearest first. */
    public List<Approach> planExpanded(ConnectionPoint cp) {
        return plan(cp, true);
    }

    private List<Approach> plan(ConnectionPoint cp, boolean expanded) {
        rejected.clear();
        Coordinate p = cp.coordinate();
        Obstacle own = findOwnPolygon(p);
        List<Approach> out = new ArrayList<>();
        if (own == null) {
            out.add(new Approach(cp, null, null, p, null, true));
            return out;
        }
        double required = space.requiredDistance(own);
        // Keep the established nearest-point representation/order on the ordinary path.
        Geometry boundary = own.geometry().getBoundary();
        if (expanded) {
            List<LineString> exterior = new ArrayList<>();
            for (Polygon poly : GeometryUtils.polygons(own.geometry())) exterior.add(poly.getExteriorRing());
            boundary = GeometryUtils.GF.createMultiLineString(exterior.toArray(new LineString[0]));
        }
        if (expanded && boundary.getLength() / BOUNDARY_SAMPLE_STEP > 4096)
            throw new ru.lct.heatnet.routing.ComplexityLimitException("OKS boundary requires more than 4096 exit samples: " + cp.id());
        Coordinate nearest = GeometryUtils.nearestPoint(boundary, p);
        List<Coordinate> candidates = new ArrayList<>();
        candidates.add(nearest);
        for (Coordinate c : sampleBoundary(own.geometry(), expanded)) {
            boolean dup = false;
            for (Coordinate e : candidates) if (e.distance(c) < 1.0) { dup = true; break; }
            if (!dup) candidates.add(c);
        }
        candidates.subList(1, candidates.size()).sort((a, b) -> Double.compare(a.distance(p), b.distance(p)));
        if (!expanded && candidates.size() > MAX_FALLBACKS + 1) candidates = new ArrayList<>(candidates.subList(0, MAX_FALLBACKS + 1));
        boolean inside = own.prepared().contains(GeometryUtils.point(p));
        if (!own.prepared().covers(GeometryUtils.point(p))) {
            for (Polygon poly : GeometryUtils.polygons(own.geometry())) {
                if (poly.getNumInteriorRing() > 0 && GeometryUtils.GF.createPolygon(poly.getExteriorRing()).contains(GeometryUtils.point(p))) {
                    rejected.put("point lies in an enclosed OKS courtyard; own-building transit is not an exterior approach", 1);
                    return out;
                }
            }
        }
        Set<Integer> ignoreOwn = Collections.singleton(own.index());
        // only the OWN polygon is exempt on the final segment; every other restriction applies in full
        for (int i = 0; i < candidates.size(); i++) {
            Coordinate b = candidates.get(i);
            double[] dir = outwardDirection(own, p, b, inside);
            if (dir == null) { rejected.merge("no outward direction", 1, Integer::sum); continue; }
            if (expanded && inside && p.distance(b) > 0.02
                    && !own.prepared().covers(GeometryUtils.line(p, GeometryUtils.offset(b, dir, -0.01)))) {
                rejected.merge("straight exit would leave and re-enter own OKS", 1, Integer::sum); continue;
            }
            Coordinate a = freePoint(own, b, dir, required, ignoreOwn);
            if (a == null) { rejected.merge("no clearance outside own OKS", 1, Integer::sum); continue; }
            if (!singleOwnExit(own, p, a)) {
                rejected.merge("straight approach would leave and re-enter own OKS", 1, Integer::sum); continue;
            }
            SegmentCheck sc = space.check(p, a, new Exemptions().ignore(ignoreOwn)); // traversed P -> A
            if (sc.valid()) out.add(new Approach(cp, own, b, a, sc, i == 0));
            else {
                Approach extended = expanded ? extendPastSpecial(cp, own, b, dir, a, ignoreOwn, i == 0) : null;
                if (extended != null) out.add(extended); else rejected.merge(sc.reason(), 1, Integer::sum);
            }
            if (!expanded && out.size() >= MAX_RESULTS) break;
        }
        return out;
    }

    /** Check the actual P -> A segment, including exits whose outward normal differs from P -> boundary. */
    private boolean singleOwnExit(Obstacle own, Coordinate p, Coordinate a) {
        Geometry crossed = ru.lct.heatnet.geo.RobustGeometry.intersection(own.geometry(), GeometryUtils.line(p, a),
                own, space.geometryStats());
        List<double[]> intervals = new ArrayList<>();
        for (Object component : org.locationtech.jts.geom.util.LinearComponentExtracter.getLines(crossed)) {
            LineString part = (LineString) component;
            if (part.isEmpty() || part.getLength() <= ru.lct.heatnet.geo.GeometryTolerance.INTERSECTION_EPS_M) continue;
            double from = p.distance(part.getCoordinateN(0)), to = p.distance(part.getCoordinateN(part.getNumPoints() - 1));
            intervals.add(new double[]{Math.min(from, to), Math.max(from, to)});
        }
        intervals.sort(java.util.Comparator.comparingDouble(iv -> iv[0]));
        double end = 0;
        for (double[] iv : intervals) {
            if (iv[0] > end + ru.lct.heatnet.geo.GeometryTolerance.INTERSECTION_EPS_M) return false;
            end = Math.max(end, iv[1]);
        }
        return true;
    }

    /** Event-based extension, not a turn inside a special section. Only the failed adaptive approach uses it. */
    private Approach extendPastSpecial(ConnectionPoint cp, Obstacle own, Coordinate boundary, double[] dir,
                                       Coordinate first, Set<Integer> ignoreOwn, boolean nearest) {
        Coordinate p = cp.coordinate();
        Envelope env = space.vertexZoneUnion().getEnvelopeInternal();
        double reach = 1;
        for (double x : new double[]{env.getMinX(), env.getMaxX()}) for (double y : new double[]{env.getMinY(), env.getMaxY()})
            reach = Math.max(reach, p.distance(new Coordinate(x, y)) + 10);
        LineString ray = GeometryUtils.line(p, GeometryUtils.offset(p, dir, reach));
        java.util.SortedSet<Double> ends = new java.util.TreeSet<>();
        for (Obstacle o : space.query(ray.getEnvelopeInternal())) {
            if (!o.isSpecial() || !o.prepared().intersects(ray)) continue;
            Geometry crossed = ru.lct.heatnet.geo.RobustGeometry.intersection(o.geometry(), ray, o, space.geometryStats());
            for (int k = 0; k < crossed.getNumGeometries(); k++) {
                Geometry part = crossed.getGeometryN(k); if (part.isEmpty()) continue;
                double end = 0;
                for (Coordinate c : part.getCoordinates()) end = Math.max(end, (c.x - p.x) * dir[0] + (c.y - p.y) * dir[1]);
                end += o.rule().sectionExtentM() + 0.05;
                if (end <= p.distance(first)) continue;
                if (ends.size() >= 512) throw new ru.lct.heatnet.routing.ComplexityLimitException("more than 512 special-section exit events for " + cp.id());
                ends.add(end);
            }
        }
        Exemptions ex = new Exemptions().ignore(ignoreOwn);
        for (double end : ends) {
            Coordinate a = GeometryUtils.offset(p, dir, end);
            if (space.insideForbidden(a, ex) || own.geometry().distance(GeometryUtils.point(a)) < space.requiredDistance(own) + 0.02) continue;
            // Do not extend through another wing of the same building under the own-polygon exemption.
            if (own.prepared().intersects(GeometryUtils.line(GeometryUtils.offset(boundary, dir, 0.01), a))) continue;
            SegmentCheck sc = space.check(p, a, ex);
            if (sc.valid()) return new Approach(cp, own, boundary, a, sc, nearest);
        }
        return null;
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

    private static List<Coordinate> sampleBoundary(Geometry g, boolean exteriorOnly) {
        List<Coordinate> out = new ArrayList<>();
        for (Polygon poly : GeometryUtils.polygons(g)) {
            out.addAll(ExistingNetworkIndex.samplePoints(poly.getExteriorRing(), BOUNDARY_SAMPLE_STEP));
            if (!exteriorOnly) for (int i = 0; i < poly.getNumInteriorRing(); i++)
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
            // Clearance beyond another wing is not a valid exit from this boundary point.
            if (own.prepared().intersects(GeometryUtils.line(GeometryUtils.offset(b, dir, 0.01), a))) return null;
            return a;
        }
        return null;
    }
}
