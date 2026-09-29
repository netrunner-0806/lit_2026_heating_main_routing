package ru.lct.heatnet.restrictions;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.lct.heatnet.config.RestrictionRule;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.geo.GeometryUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * All obstacles of a job for one clearance class: check zones (buffered by the required axis distance), a spatial
 * index and the segment checker implementing Table 2 of the technical annex in 2D:
 * <ul>
 *   <li>FORBIDDEN objects: the segment must stay outside the clearance zone (except inside exemption discs);</li>
 *   <li>SPECIAL objects: the segment may cross the object as a single straight special section fully inside the
 *   segment; the crossing angle rule is checked; alongside clearance is checked outside the crossing neighbourhood.</li>
 * </ul>
 */
public final class ObstacleSpace {

    /** Zones used for the check are shrunk by this amount so that touching at exactly the clearance passes. */
    public static final double CHECK_EPS = ru.lct.heatnet.geo.GeometryTolerance.INTERSECTION_EPS_M;
    /** Vertex zones are enlarged so that chords between round-join arc vertices stay outside the check zone. */
    public static final int ARC_QUADRANT_SEGMENTS = 3;
    private static final double VERTEX_ZONE_FACTOR = 1.0 / Math.cos(Math.toRadians(90.0 / ARC_QUADRANT_SEGMENTS / 2.0));
    public static final double VERTEX_ZONE_MARGIN = ru.lct.heatnet.geo.GeometryTolerance.ZONE_MARGIN_M;
    /** Uncovered zone intersection shorter than this (metres) is treated as numerical noise. */
    private static final double COVER_TOL = ru.lct.heatnet.geo.GeometryTolerance.ZONE_MARGIN_M;
    /** Tolerance for the special section to lie inside the segment. */
    private static final double SECTION_TOL = ru.lct.heatnet.geo.GeometryTolerance.COORDINATE_EQUALITY_M;

    private final ClearanceClass cls;
    private final List<Obstacle> obstacles;
    private final double[] required;
    private final Geometry[] checkZones;
    private final PreparedGeometry[] preparedCheckZones;
    private final STRtree index = new STRtree();
    private final PreparedGeometry forbiddenUnion;
    private final Geometry vertexZoneUnion;

    public ObstacleSpace(InputModel input, ClearanceClass cls) {
        this.cls = cls;
        List<Obstacle> list = new ArrayList<>();
        for (Restriction r : input.restrictions()) {
            if (r.rule() == null) continue;
            list.add(Obstacle.of(list.size(), r));
        }
        RestrictionRule netRule = RestrictionRules.existingHeatNetwork();
        for (ExistingNetworkLine l : input.networkLines()) list.add(Obstacle.of(list.size(), l, netRule));
        this.obstacles = Collections.unmodifiableList(list);
        this.required = new double[list.size()];
        this.checkZones = new Geometry[list.size()];
        this.preparedCheckZones = new PreparedGeometry[list.size()];
        List<Geometry> forbiddenZones = new ArrayList<>();
        List<Geometry> vertexZones = new ArrayList<>();
        for (Obstacle o : list) {
            double d = o.requiredAxisDistance(cls);
            required[o.index()] = d;
            Geometry z = buffer(o.geometry(), d - CHECK_EPS);
            checkZones[o.index()] = z;
            preparedCheckZones[o.index()] = PreparedGeometryFactory.prepare(z);
            index.insert(z.getEnvelopeInternal(), o);
            if (o.isForbidden()) forbiddenZones.add(z);
            vertexZones.add(buffer(o.geometry(), d * VERTEX_ZONE_FACTOR + VERTEX_ZONE_MARGIN));
        }
        index.build();
        Geometry fu = forbiddenZones.isEmpty() ? GeometryUtils.GF.createPolygon() : UnaryUnionOp.union(forbiddenZones);
        this.forbiddenUnion = PreparedGeometryFactory.prepare(fu);
        this.vertexZoneUnion = vertexZones.isEmpty() ? GeometryUtils.GF.createPolygon() : UnaryUnionOp.union(vertexZones);
    }

    private static Geometry buffer(Geometry g, double dist) {
        BufferParameters bp = new BufferParameters(ARC_QUADRANT_SEGMENTS, BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 5.0);
        return BufferOp.bufferOp(g, dist, bp);
    }

    public ClearanceClass clearanceClass() { return cls; }
    public List<Obstacle> obstacles() { return obstacles; }
    public double requiredDistance(Obstacle o) { return required[o.index()]; }
    public Geometry vertexZoneUnion() { return vertexZoneUnion; }

    /** True if the point lies inside a forbidden clearance zone (so a line cannot pass/end there). */
    public boolean insideForbidden(Coordinate c) {
        return forbiddenUnion.intersects(GeometryUtils.point(c));
    }

    public boolean insideForbidden(Coordinate c, Exemptions ex) {
        if (ex == null || ex.isEmpty()) return insideForbidden(c);
        Point p = GeometryUtils.point(c);
        @SuppressWarnings("unchecked")
        List<Obstacle> cands = index.query(new Envelope(c));
        for (Obstacle o : cands) {
            if (!o.isForbidden() || ex.ignores(o)) continue;
            if (preparedCheckZones[o.index()].intersects(p) && !ex.exemptAt(o, c)) return true;
        }
        return false;
    }

    /** True if the point lies inside any check zone (forbidden or special): unsuitable for a chamber. */
    public boolean insideAnyZone(Coordinate c) { return insideAnyZone(c, java.util.Collections.emptySet()); }

    /** Same as {@link #insideAnyZone(Coordinate)} ignoring the given obstacle indexes (e.g. the own ОКС polygon). */
    public boolean insideAnyZone(Coordinate c, java.util.Set<Integer> ignore) {
        Point p = GeometryUtils.point(c);
        @SuppressWarnings("unchecked")
        List<Obstacle> cands = index.query(new Envelope(c));
        for (Obstacle o : cands) if (!ignore.contains(o.index()) && preparedCheckZones[o.index()].intersects(p)) return true;
        return false;
    }

    /** Obstacles whose check zone envelope intersects the envelope. */
    @SuppressWarnings("unchecked")
    public List<Obstacle> query(Envelope env) { return index.query(env); }

    /** Distance from the coordinate to the obstacle core geometry. */
    public double distance(Obstacle o, Coordinate c) { return o.geometry().distance(GeometryUtils.point(c)); }

    // ------------------------------------------------------------------------------------------------------------
    // Segment check
    // ------------------------------------------------------------------------------------------------------------

    public SegmentCheck check(Coordinate a, Coordinate b) { return check(a, b, Exemptions.NONE); }

    public SegmentCheck check(Coordinate a, Coordinate b, Exemptions ex) {
        double len = a.distance(b);
        if (len < 1e-9) return SegmentCheck.valid(0, Collections.emptyList());
        LineString seg = GeometryUtils.line(a, b);
        Envelope env = seg.getEnvelopeInternal();
        @SuppressWarnings("unchecked")
        List<Obstacle> cands = index.query(env);
        List<SpecialPassage> passages = new ArrayList<>();
        boolean[] dirOk = {true, true};   // [0] traversal a->b, [1] traversal b->a (road/tram entry angle rule)
        String[] dirReason = {null, null};
        for (Obstacle o : cands) {
            if (ex.ignores(o)) continue;
            PreparedGeometry zone = preparedCheckZones[o.index()];
            if (!zone.intersects(seg)) continue;
            List<double[]> exempt = ex.exemptIntervals(o, a, b, len);
            if (o.isForbidden()) {
                List<double[]> inside = intervals(checkZones[o.index()].intersection(seg), a, len);
                if (!covered(inside, exempt)) return SegmentCheck.invalid("clearance violated: " + o.describe());
                continue;
            }
            String err = specialCrossing(o, a, b, len, seg, exempt, passages, dirOk, dirReason);
            if (err != null) return SegmentCheck.invalid(err);
        }
        if (!dirOk[0] && !dirOk[1]) return SegmentCheck.invalid(dirReason[0]);
        if (dirOk[0] && dirOk[1]) return SegmentCheck.valid(len, passages);
        return SegmentCheck.directional(dirOk[0], dirOk[1], dirOk[0] ? dirReason[1] : dirReason[0], len, passages);
    }

    /**
     * Evaluates crossings of one special object by the segment; adds passages; returns an error text or null.
     */
    private String specialCrossing(Obstacle o, Coordinate a, Coordinate b, double len, LineString seg,
                                   List<double[]> exempt, List<SpecialPassage> out, boolean[] dirOk, String[] dirReason) {
        RestrictionRule rule = o.rule();
        double ext = rule.sectionExtentM();
        double coverRadius = rule.crossingCoverRadiusM(cls.representativeDu(), o.ownHalfWidthM());
        List<double[]> covers = new ArrayList<>(exempt);
        Geometry core = o.geometry().intersection(seg);
        if (o.isPolygonal()) {
            List<double[]> inside = intervals(core, a, len);
            for (double[] iv : inside) {
                double entry = iv[0], exit = iv[1];
                boolean entryExempt = inExempt(entry, exempt), exitExempt = inExempt(exit, exempt);
                if (exit - entry < 1e-6) continue; // touching
                if (entryExempt && exitExempt) continue;
                double from = entry - ext, to = exit + ext;
                if (from < -SECTION_TOL || to > len + SECTION_TOL)
                    return "special section of " + o.describe() + " does not fit into one straight segment";
                // annex 4: the angle is checked at the point of ENTRY relative to the polygon boundary; the entry
                // depends on the traversal direction, so validity is recorded per direction
                double angEntry = boundaryAngle(o, a, b, at(a, b, len, entry));
                double angExit = boundaryAngle(o, a, b, at(a, b, len, exit));
                if (rule.minCrossingAngleDeg() > 0) {
                    if (angEntry + 1e-6 < rule.minCrossingAngleDeg()) {
                        dirOk[0] = false;
                        dirReason[0] = String.format(java.util.Locale.ROOT, "entry angle %.1f° < %.0f° for %s", angEntry, rule.minCrossingAngleDeg(), o.describe());
                    }
                    if (angExit + 1e-6 < rule.minCrossingAngleDeg()) {
                        dirOk[1] = false;
                        dirReason[1] = String.format(java.util.Locale.ROOT, "entry angle %.1f° < %.0f° for %s (reverse traversal)", angExit, rule.minCrossingAngleDeg(), o.describe());
                    }
                }
                out.add(new SpecialPassage(o, Math.max(0, from), Math.min(len, to), rule.kSpec(), at(a, b, len, (entry + exit) / 2), angEntry));
                covers.add(new double[]{entry - coverRadius, exit + coverRadius});
            }
        } else {
            // linear object: crossing points; collinear overlaps are not allowed
            List<Double> ts = new ArrayList<>();
            for (int i = 0; i < core.getNumGeometries(); i++) {
                Geometry g = core.getGeometryN(i);
                if (g.isEmpty()) continue;
                if (g instanceof Point) {
                    ts.add(along(a, b, len, g.getCoordinate()));
                } else if (g.getLength() > 1e-6) {
                    double[] iv = intervalOf(g, a, len);
                    if (!inExempt(iv[0], exempt) || !inExempt(iv[1], exempt))
                        return "segment runs along " + o.describe();
                } else {
                    ts.add(along(a, b, len, g.getCoordinate()));
                }
            }
            for (double t : ts) {
                // termination at the object (tie-in / junction): only at an exempt segment end
                if (inExempt(t, exempt) && (t <= ru.lct.heatnet.config.Constants.TERMINATION_TOL_M || t >= len - ru.lct.heatnet.config.Constants.TERMINATION_TOL_M)) continue;
                // passing through the END point of a linear object is not a crossing (the pipe simply ends there): it is
                // a clearance violation, exactly as the independent validator sees it
                if (touchesLineEnd(o, at(a, b, len, t))) return "segment passes through the end point of " + o.describe() + " (not a crossing)";
                double from = t - ext, to = t + ext;
                if (from < -SECTION_TOL || to > len + SECTION_TOL)
                    return "special section of " + o.describe() + " does not fit into one straight segment";
                double ang = lineAngle(o, a, b, at(a, b, len, t));
                if (rule.minCrossingAngleDeg() > 0 && ang + 1e-6 < rule.minCrossingAngleDeg())
                    return String.format(java.util.Locale.ROOT, "crossing angle %.1f° < %.0f° for %s", ang, rule.minCrossingAngleDeg(), o.describe());
                out.add(new SpecialPassage(o, from, to, rule.kSpec(), at(a, b, len, t), ang));
                covers.add(new double[]{t - coverRadius, t + coverRadius});
            }
        }
        // alongside clearance outside crossings/exemptions
        List<double[]> zoneHits = intervals(checkZones[o.index()].intersection(seg), a, len);
        if (!covered(zoneHits, covers)) return "clearance violated alongside " + o.describe();
        return null;
    }

    /** True if the point lies on an end vertex of (a part of) the linear obstacle. */
    private static boolean touchesLineEnd(Obstacle o, Coordinate x) {
        Geometry g = o.geometry();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (!(part instanceof LineString) || part.isEmpty()) continue;
            Coordinate[] cs = part.getCoordinates();
            if (cs[0].distance(x) <= ru.lct.heatnet.geo.GeometryTolerance.ON_LINE_M || cs[cs.length - 1].distance(x) <= ru.lct.heatnet.geo.GeometryTolerance.ON_LINE_M) return true;
        }
        return false;
    }

    private static boolean inExempt(double t, List<double[]> exempt) {
        for (double[] e : exempt) if (t >= e[0] - 1e-6 && t <= e[1] + 1e-6) return true;
        return false;
    }

    private static Coordinate at(Coordinate a, Coordinate b, double len, double t) {
        double f = t / len;
        return new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
    }

    private static double along(Coordinate a, Coordinate b, double len, Coordinate p) {
        double ux = (b.x - a.x) / len, uy = (b.y - a.y) / len;
        return (p.x - a.x) * ux + (p.y - a.y) * uy;
    }

    /** Intervals along the segment covered by the (multi)line geometry g (result of an intersection). */
    private static List<double[]> intervals(Geometry g, Coordinate a, double len) {
        List<double[]> out = new ArrayList<>();
        if (g == null || g.isEmpty()) return out;
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (part instanceof LineString && part.getLength() > 1e-9) out.add(intervalOf(part, a, len));
            else if (part instanceof Point) {
                Coordinate c = part.getCoordinate();
                double t = a.distance(c);
                out.add(new double[]{t, t});
            }
        }
        out.sort((x, y) -> Double.compare(x[0], y[0]));
        return out;
    }

    private static double[] intervalOf(Geometry line, Coordinate a, double len) {
        Coordinate[] cs = line.getCoordinates();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Coordinate c : cs) {
            double t = a.distance(c);
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        return new double[]{Math.max(0, min), Math.min(len, max)};
    }

    /** True if every interval is covered by the union of the covers (up to COVER_TOL of uncovered length per gap). */
    static boolean covered(List<double[]> intervals, List<double[]> covers) {
        for (double[] iv : intervals) {
            double from = iv[0], to = iv[1];
            List<double[]> rem = new ArrayList<>();
            rem.add(new double[]{from, to});
            for (double[] c : covers) {
                List<double[]> next = new ArrayList<>();
                for (double[] r : rem) {
                    if (c[1] <= r[0] || c[0] >= r[1]) { next.add(r); continue; }
                    if (c[0] > r[0]) next.add(new double[]{r[0], c[0]});
                    if (c[1] < r[1]) next.add(new double[]{c[1], r[1]});
                }
                rem = next;
            }
            for (double[] r : rem) if (r[1] - r[0] > COVER_TOL) return false;
        }
        return true;
    }

    /** Acute angle between the segment and the polygon boundary edge nearest to the crossing point. */
    private static double boundaryAngle(Obstacle o, Coordinate a, Coordinate b, Coordinate p) {
        double best = Double.MAX_VALUE;
        double[] dir = null;
        for (Polygon poly : GeometryUtils.polygons(o.geometry())) {
            List<LinearRing> rings = new ArrayList<>();
            rings.add(poly.getExteriorRing());
            for (int i = 0; i < poly.getNumInteriorRing(); i++) rings.add(poly.getInteriorRingN(i));
            for (LinearRing ring : rings) {
                Coordinate[] cs = ring.getCoordinates();
                for (int i = 0; i + 1 < cs.length; i++) {
                    double d = org.locationtech.jts.algorithm.Distance.pointToSegment(p, cs[i], cs[i + 1]);
                    if (d < best) {
                        best = d;
                        dir = new double[]{cs[i + 1].x - cs[i].x, cs[i + 1].y - cs[i].y};
                    }
                }
            }
        }
        if (dir == null) return 90;
        return GeometryUtils.acuteAngleDeg(b.x - a.x, b.y - a.y, dir[0], dir[1]);
    }

    /** Acute angle between the segment and the linear object at the crossing point. */
    private static double lineAngle(Obstacle o, Coordinate a, Coordinate b, Coordinate p) {
        double best = Double.MAX_VALUE;
        double[] dir = null;
        Geometry g = o.geometry();
        for (int k = 0; k < g.getNumGeometries(); k++) {
            Coordinate[] cs = g.getGeometryN(k).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double d = org.locationtech.jts.algorithm.Distance.pointToSegment(p, cs[i], cs[i + 1]);
                if (d < best) {
                    best = d;
                    dir = new double[]{cs[i + 1].x - cs[i].x, cs[i + 1].y - cs[i].y};
                }
            }
        }
        if (dir == null) return 90;
        return GeometryUtils.acuteAngleDeg(b.x - a.x, b.y - a.y, dir[0], dir[1]);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Vertex generation for the navigation graph
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Candidate turning points: convex corners of the union of vertex zones (reflex vertices of the free space),
     * excluding points inside forbidden check zones.
     */
    public List<Coordinate> navigationVertices() {
        List<Coordinate> out = new ArrayList<>();
        for (Polygon poly : GeometryUtils.polygons(vertexZoneUnion)) {
            collectConvex(poly.getExteriorRing().getCoordinates(), true, out);
            for (int i = 0; i < poly.getNumInteriorRing(); i++) collectConvex(poly.getInteriorRingN(i).getCoordinates(), false, out);
        }
        List<Coordinate> filtered = new ArrayList<>();
        for (Coordinate c : out) if (!insideForbidden(c)) filtered.add(c);
        return filtered;
    }

    private static void collectConvex(Coordinate[] ring, boolean shell, List<Coordinate> out) {
        if (ring.length < 4) return;
        boolean ccw = Orientation.isCCW(ring);
        // we want the obstacle on the right-hand side while walking the ring: shell -> CW, hole -> CCW
        boolean reverse = shell ? ccw : !ccw;
        int n = ring.length - 1;
        for (int i = 0; i < n; i++) {
            Coordinate prev = ring[(i - 1 + n) % n], cur = ring[i], next = ring[(i + 1) % n];
            if (reverse) { Coordinate t = prev; prev = next; next = t; }
            double cross = (cur.x - prev.x) * (next.y - cur.y) - (cur.y - prev.y) * (next.x - cur.x);
            if (cross < -1e-9) out.add(cur); // right turn = convex obstacle corner
        }
    }
}
