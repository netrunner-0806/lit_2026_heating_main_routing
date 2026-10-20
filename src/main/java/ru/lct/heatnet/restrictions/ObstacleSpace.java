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
    private boolean exactClearance;
    public void requireExactClearance() { exactClearance = true; }
    private final List<ru.lct.heatnet.domain.ExistingChamber> chambers;
    private final List<Obstacle> obstacles;
    private final double[] required;
    private final java.util.concurrent.atomic.AtomicReferenceArray<PreparedGeometry> preparedCheckZones;
    private final STRtree index = new STRtree();
    /** Optional rejection certificates, never a substitute for the complete obstacle index. */
    private final STRtree forbiddenInteriors = new STRtree();
    private final PreparedGeometry forbiddenUnion;
    private volatile Geometry vertexZoneUnion;
    private final boolean deferred;
    private final Envelope navigationRegion;
    private final Envelope fullNavigationEnvelope = new Envelope();
    private final java.util.concurrent.atomic.AtomicInteger preparedCount = new java.util.concurrent.atomic.AtomicInteger();
    private final ru.lct.heatnet.geo.RobustGeometry.Stats geometryStats = new ru.lct.heatnet.geo.RobustGeometry.Stats();
    public ru.lct.heatnet.geo.RobustGeometry.Stats geometryStats() { return geometryStats; }

    public ObstacleSpace(InputModel input, ClearanceClass cls) {
        this(input, cls, input.restrictions().size() > 2048);
    }

    /** Deferred mode indexes conservative envelopes of ALL objects; geometry is prepared only when queried. */
    public ObstacleSpace(InputModel input, ClearanceClass cls, boolean deferred) {
        this(input,cls,deferred,null);
    }

    public ObstacleSpace(InputModel input, ClearanceClass cls, boolean deferred, Envelope navigationRegion) {
        this(input,cls,deferred,navigationRegion,true);
    }

    /** Approximate checks are only candidate generation; callers must independently validate actual DU. */
    public ObstacleSpace(InputModel input, ClearanceClass cls, boolean deferred, Envelope navigationRegion, boolean exactClearance) {
        this(input, cls, deferred, navigationRegion, exactClearance, cls.representativeDu());
    }

    /** Exact-DU candidate generation; the whole tentative network must still pass independent validation. */
    public ObstacleSpace(InputModel input, ClearanceClass cls, boolean deferred, Envelope navigationRegion,
                         boolean exactClearance, int routingDu) {
        if (ClearanceClass.forDu(routingDu) != cls) throw new IllegalArgumentException("DU outside clearance class");
        ru.lct.heatnet.config.DiameterTable.requireDu(routingDu);
        this.exactClearance=exactClearance;
        this.navigationRegion=navigationRegion==null?null:new Envelope(navigationRegion);
        this.deferred = deferred || navigationRegion!=null;
        this.cls = cls;
        this.chambers = input.chambers();
        List<Obstacle> list = new ArrayList<>();
        for (Restriction r : input.restrictions()) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            if (r.rule() == null) continue;
            list.add(Obstacle.of(list.size(), r));
        }
        RestrictionRule netRule = RestrictionRules.existingHeatNetwork();
        for (ExistingNetworkLine l : input.networkLines()) list.add(Obstacle.of(list.size(), l, netRule));
        this.obstacles = Collections.unmodifiableList(list);
        this.required = new double[list.size()];
        this.preparedCheckZones = new java.util.concurrent.atomic.AtomicReferenceArray<>(list.size());
        List<Geometry> forbiddenZones = new ArrayList<>();
        List<Geometry> vertexZones = new ArrayList<>();
        for (Obstacle o : list) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            try {
            double d = o.requiredAxisDistance(routingDu);
            required[o.index()] = d;
            Envelope navigation = new Envelope(o.geometry().getEnvelopeInternal());
            navigation.expandBy(d * VERTEX_ZONE_FACTOR + VERTEX_ZONE_MARGIN);
            fullNavigationEnvelope.expandToInclude(navigation);
            if(this.deferred) {
                Envelope check = new Envelope(o.geometry().getEnvelopeInternal());
                check.expandBy(d); // conservative: a long object crossing a query cannot be discarded by its vertices
                index.insert(check, o);
                continue;
            }
            Geometry z = buffer(o.geometry(), d - CHECK_EPS);
            preparedCheckZones.set(o.index(), PreparedGeometryFactory.prepare(z));
            preparedCount.incrementAndGet();
            index.insert(z.getEnvelopeInternal(), o);
            if (o.isForbidden()) forbiddenZones.add(z);
            vertexZones.add(buffer(o.geometry(), d * VERTEX_ZONE_FACTOR + VERTEX_ZONE_MARGIN));
            } catch (org.locationtech.jts.geom.TopologyException ex) {
                throw new ru.lct.heatnet.geo.GeometryProcessingException("clearance buffer " + o.describe(), ex);
            }
        }
        index.build();
        // Bound accelerator memory independently of city size. Missing certificates simply use the full checker.
        List<Obstacle> interiorCandidates = navigationRegion == null ? list : query(navigationRegion);
        int interiorCount = 0;
        for (Obstacle o : interiorCandidates) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            if (interiorCount >= 4096) break;
            if (!o.isForbidden() || !(o.geometry() instanceof Polygon) || required[o.index()] <= CHECK_EPS) continue;
            Polygon polygon = (Polygon) o.geometry();
            if (polygon.getNumInteriorRing() != 0 || polygon.getNumPoints() != 5) continue;
            Envelope inner = polygon.getEnvelopeInternal();
            inner.expandBy(-Math.max(1e-4, Math.max(inner.getWidth(), inner.getHeight()) * 1e-8));
            // covers is the proof; a rotated/sliver/concave quad that lacks this interior gets no shortcut.
            if (inner.isNull() || !polygon.covers(GeometryUtils.GF.toGeometry(inner))) continue;
            forbiddenInteriors.insert(inner, new Object[]{o, inner}); interiorCount++;
        }
        forbiddenInteriors.build();
        Geometry fu = forbiddenZones.isEmpty() ? GeometryUtils.GF.createPolygon() : ru.lct.heatnet.geo.RobustGeometry.union(forbiddenZones, "forbidden clearance union", geometryStats);
        this.forbiddenUnion = this.deferred ? null : PreparedGeometryFactory.prepare(fu);
        this.vertexZoneUnion = this.deferred ? null : vertexZones.isEmpty() ? GeometryUtils.GF.createPolygon() : ru.lct.heatnet.geo.RobustGeometry.union(vertexZones, "navigation zone union", geometryStats);
    }

    private static Geometry buffer(Geometry g, double dist) {
        BufferParameters bp = new BufferParameters(ARC_QUADRANT_SEGMENTS, BufferParameters.CAP_ROUND, BufferParameters.JOIN_ROUND, 5.0);
        return BufferOp.bufferOp(g, dist, bp);
    }

    public ClearanceClass clearanceClass() { return cls; }
    public List<Obstacle> obstacles() { return obstacles; }
    public double requiredDistance(Obstacle o) { return required[o.index()]; }
    public Geometry vertexZoneUnion() {
        Geometry result=vertexZoneUnion;
        if(result==null) synchronized(this) {
            if(vertexZoneUnion==null) {
                vertexZoneUnion=buildNavigationUnion(navigationRegion,ARC_QUADRANT_SEGMENTS,VERTEX_ZONE_FACTOR);
            }
            result=vertexZoneUnion;
        }
        return result;
    }

    /** Estimate before allocating any city-wide union. Eager mode retains the historical grid origin exactly. */
    public Envelope navigationEnvelope() { return navigationRegion!=null?new Envelope(navigationRegion):deferred ? new Envelope(fullNavigationEnvelope) : vertexZoneUnion().getEnvelopeInternal(); }
    public Envelope initialNavigationRegion() { return navigationRegion==null?null:new Envelope(navigationRegion); }
    public Envelope fullNavigationEnvelope() { return new Envelope(fullNavigationEnvelope); }
    public int preparedObjectCount() { return preparedCount.get(); }

    /** Only navigation candidates are spatially selected. Segment checks always query the full obstacle index. */
    private Geometry buildNavigationUnion(Envelope region,int quadrants,double factor) {
        List<Obstacle> selected=obstacles;
        if(region!=null) {
            double extra=0;for(double d:required)extra=Math.max(extra,d*(factor-1)+VERTEX_ZONE_MARGIN);
            Envelope query=new Envelope(region);query.expandBy(extra);
            selected=new ArrayList<>(query(query));
            selected.sort(java.util.Comparator.comparingInt(Obstacle::index));
        }
        List<Geometry> zones=new ArrayList<>();long coordinates=0;
        long budget=Math.max(50000,Math.min(2000000,Runtime.getRuntime().maxMemory()/1024));
        for(Obstacle o:selected) {
            try {
                double d=required[o.index()]*factor+VERTEX_ZONE_MARGIN;
                Geometry core=o.geometry();
                if(region!=null) {
                    Envelope clip=new Envelope(region);clip.expandBy(d+1);
                    if(!clip.contains(core.getEnvelopeInternal()))
                        core=ru.lct.heatnet.geo.RobustGeometry.intersection(core,GeometryUtils.GF.toGeometry(clip),"navigation-only clipping " + o.describe(),geometryStats);
                }
                coordinates+=core.getNumPoints();
                if(coordinates>budget)throw new ru.lct.heatnet.routing.ComplexityLimitException("navigation union input exceeds " + budget + " coordinates; expand/stage the geometry workload");
                if(!core.isEmpty()) zones.add(BufferOp.bufferOp(core,d,new BufferParameters(quadrants,BufferParameters.CAP_ROUND,BufferParameters.JOIN_ROUND,5.0)));
            } catch(org.locationtech.jts.geom.TopologyException ex) {
                throw new ru.lct.heatnet.geo.GeometryProcessingException("navigation buffer " + o.describe(),ex);
            }
        }
        Geometry union=zones.isEmpty()?GeometryUtils.GF.createPolygon():ru.lct.heatnet.geo.RobustGeometry.union(zones,"navigation zone union",geometryStats);
        return region==null||region.contains(union.getEnvelopeInternal())?union:ru.lct.heatnet.geo.RobustGeometry.intersection(union,GeometryUtils.GF.toGeometry(region),"navigation region boundary",geometryStats);
    }

    public List<Coordinate> navigationVertices(Envelope region,int maxNodes) {
        return navigationVertices(buildNavigationUnion(region,ARC_QUADRANT_SEGMENTS,VERTEX_ZONE_FACTOR),maxNodes);
    }

    private PreparedGeometry prepared(Obstacle o) {
        PreparedGeometry result=preparedCheckZones.get(o.index());
        if(result==null) synchronized(o) {
            result=preparedCheckZones.get(o.index());
            if(result==null) {
                try {result=PreparedGeometryFactory.prepare(buffer(o.geometry(),required[o.index()]-CHECK_EPS));}
                catch(org.locationtech.jts.geom.TopologyException ex) {
                    throw new ru.lct.heatnet.geo.GeometryProcessingException("clearance buffer " + o.describe(),ex);
                }
                preparedCheckZones.set(o.index(),result);preparedCount.incrementAndGet();
            }
        }
        return result;
    }

    /** True if the point lies inside a forbidden clearance zone (so a line cannot pass/end there). */
    public boolean insideForbidden(Coordinate c) {
        Point p=GeometryUtils.point(c);
        for(Obstacle o:query(new Envelope(c))) if(o.isForbidden() && (prepared(o).intersects(p) || exactClearance && o.geometry().isWithinDistance(p,required[o.index()]-CHECK_EPS)))return true;
        return false;
    }

    public boolean insideForbidden(Coordinate c, Exemptions ex) {
        if (ex == null || ex.isEmpty()) return insideForbidden(c);
        Point p = GeometryUtils.point(c);
        @SuppressWarnings("unchecked")
        List<Obstacle> cands = index.query(new Envelope(c));
        for (Obstacle o : cands) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            if (!o.isForbidden() || ex.ignores(o)) continue;
            if ((prepared(o).intersects(p) || exactClearance && o.geometry().isWithinDistance(p,required[o.index()]-CHECK_EPS)) && !ex.exemptAt(o, c)) return true;
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
        for (Obstacle o : cands) if (!ignore.contains(o.index()) && (prepared(o).intersects(p) || exactClearance && o.geometry().isWithinDistance(p,required[o.index()]-CHECK_EPS))) return true;
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

    /** Positive-only certificate: a straight chord has > tolerance uncovered length inside a forbidden core.
     * Liang–Barsky clipping uses a strict interior box certified against the original polygon. Road/tram/utilities,
     * holes, ambiguous near-tangencies and exempt pieces always fall back to the complete JTS implementation. */
    public boolean definitelyBlocked(Coordinate a, Coordinate b, Exemptions ex) {
        double len = a.distance(b);
        if (len <= COVER_TOL) return false;
        @SuppressWarnings("unchecked") List<Object[]> candidates = forbiddenInteriors.query(new Envelope(a, b));
        for (Object[] entry : candidates) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            Obstacle o = (Obstacle) entry[0]; if (ex.ignores(o)) continue;
            Envelope e = (Envelope) entry[1];
            double from = 0, to = 1;
            double dx = b.x - a.x, dy = b.y - a.y;
            if (dx == 0) { if (a.x <= e.getMinX() || a.x >= e.getMaxX()) continue; }
            else {
                double u = (e.getMinX() - a.x) / dx, v = (e.getMaxX() - a.x) / dx;
                from = Math.max(from, Math.min(u, v)); to = Math.min(to, Math.max(u, v));
            }
            if (dy == 0) { if (a.y <= e.getMinY() || a.y >= e.getMaxY()) continue; }
            else {
                double u = (e.getMinY() - a.y) / dy, v = (e.getMaxY() - a.y) / dy;
                from = Math.max(from, Math.min(u, v)); to = Math.min(to, Math.max(u, v));
            }
            if ((to - from) * len <= COVER_TOL + 1e-5) continue;
            // Widen the numerical safety margin before comparing with the existing coverage semantics.
            List<double[]> inside = Collections.singletonList(new double[]{from * len + 1e-6, to * len - 1e-6});
            if (!covered(inside, ex.exemptIntervals(o, a, b, len))) return true;
        }
        return false;
    }

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
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            if (ex.ignores(o)) continue;
            if (!prepared(o).intersects(seg) && (!exactClearance || !o.geometry().isWithinDistance(seg, required[o.index()] - CHECK_EPS))) continue;
            List<double[]> exempt = ex.exemptIntervals(o, a, b, len);
            if (o.isForbidden()) {
                if (!clearanceOutside(o, a, b, len, exempt)) return SegmentCheck.invalid("clearance violated: " + o.describe());
                continue;
            }
            String err = specialCrossing(o, a, b, len, seg, exempt, ex, passages, dirOk, dirReason);
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
                                   List<double[]> exempt, Exemptions ex, List<SpecialPassage> out, boolean[] dirOk, String[] dirReason) {
        RestrictionRule rule = o.rule();
        double ext = rule.sectionExtentM();
        List<double[]> covers = new ArrayList<>(exempt);
        Geometry core = intersection(o.geometry(), seg, o);
        if (o.isPolygonal()) {
            List<double[]> inside = intervals(core, a, len);
            for (double[] iv : inside) {
                ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
                double entry = iv[0], exit = iv[1];
                boolean entryExempt = inExempt(entry, exempt), exitExempt = inExempt(exit, exempt);
                if (exit - entry < 1e-6) continue; // touching
                if (entryExempt && exitExempt) continue;
                boolean roadOrTram = RestrictionRules.ROAD.equals(rule.type()) || RestrictionRules.TRAM_TRACKS.equals(rule.type());
                boolean startsAtChamber = roadOrTram && entry <= SECTION_TOL
                        && o.geometry().covers(GeometryUtils.point(a)) && isTieIn(a, ex);
                boolean endsAtChamber = roadOrTram && exit >= len - SECTION_TOL
                        && o.geometry().covers(GeometryUtils.point(b)) && isTieIn(b, ex);
                double from = startsAtChamber ? 0 : entry - ext;
                double to = endsAtChamber ? len : exit + ext;
                if (from < -SECTION_TOL || to > len + SECTION_TOL)
                    return "special section of " + o.describe() + " does not fit into one straight segment";
                // annex 4: the angle is checked at the point of ENTRY relative to the polygon boundary; the entry
                // depends on the traversal direction, so validity is recorded per direction
                double angEntry = boundaryAngle(o, a, b, at(a, b, len, entry));
                double angExit = boundaryAngle(o, a, b, at(a, b, len, exit));
                if (rule.minCrossingAngleDeg() > 0) {
                    if (!startsAtChamber && angEntry + 1e-6 < rule.minCrossingAngleDeg()) {
                        dirOk[0] = false;
                        dirReason[0] = String.format(java.util.Locale.ROOT, "entry angle %.1f° < %.0f° for %s", angEntry, rule.minCrossingAngleDeg(), o.describe());
                    }
                    if (!endsAtChamber && angExit + 1e-6 < rule.minCrossingAngleDeg()) {
                        dirOk[1] = false;
                        dirReason[1] = String.format(java.util.Locale.ROOT, "entry angle %.1f° < %.0f° for %s (reverse traversal)", angExit, rule.minCrossingAngleDeg(), o.describe());
                    }
                }
                out.add(new SpecialPassage(o, Math.max(0, from), Math.min(len, to), rule.kSpec(), at(a, b, len, (entry + exit) / 2), angEntry));
                addCrossingTransition(covers, o, a, b, len, entry, exit, from, to);
            }
        } else {
            // linear object: crossing points; collinear overlaps are not allowed
            List<Double> ts = new ArrayList<>();
            for (int i = 0; i < core.getNumGeometries(); i++) {
                ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
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
                ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
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
                addCrossingTransition(covers, o, a, b, len, t, t, from, to);
            }
        }
        // alongside clearance outside crossings/exemptions
        if (!clearanceOutside(o, a, b, len, covers)) return "clearance violated alongside " + o.describe();
        return null;
    }

    /** Exact distances to the original geometry decide admissibility. Navigation buffers are only candidates. */
    private boolean clearanceOutside(Obstacle o, Coordinate a, Coordinate b, double len, List<double[]> covers) {
        if (!exactClearance)
            return covered(intervals(intersection(prepared(o).getGeometry(),GeometryUtils.line(a,b),o),a,len),covers);
        List<double[]> sorted = new ArrayList<>(covers);
        sorted.sort(java.util.Comparator.comparingDouble(v -> v[0]));
        double at = 0;
        for (double[] cover : sorted) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            double end = Math.min(len, cover[0]);
            if (end > at + 1e-9 && o.geometry().distance(GeometryUtils.line(at(a,b,len,at),at(a,b,len,end)))
                    < required[o.index()] - CHECK_EPS) return false;
            at = Math.max(at,cover[1]);
            if (at >= len) return true;
        }
        return at >= len || o.geometry().distance(GeometryUtils.line(at(a,b,len,at),b)) >= required[o.index()] - CHECK_EPS;
    }

    /** Exempt only the finite boundary/axis edges at this crossing; passage cost extent is unchanged. */
    private void addCrossingTransition(List<double[]> covers, Obstacle o, Coordinate a, Coordinate b,
                                       double len, double entry, double exit, double from, double to) {
        covers.add(new double[]{from, to});
        covers.addAll(o.boundarySegments().crossingClearanceIntervals(a,b,at(a,b,len,entry),required[o.index()]));
        if (exit != entry)
            covers.addAll(o.boundarySegments().crossingClearanceIntervals(a,b,at(a,b,len,exit),required[o.index()]));
    }

    /** Only attachment terminals may end inside road/tram; arbitrary graph vertices cannot truncate a passage. */
    private boolean isTieIn(Coordinate c, Exemptions ex) {
        if (ex == null || ex.isEmpty()) return false;
        double tol = ru.lct.heatnet.geo.GeometryTolerance.ON_LINE_M;
        for (ru.lct.heatnet.domain.ExistingChamber chamber : chambers)
            if (chamber.coordinate().distance(c) <= tol) return true;
        Envelope env = new Envelope(c);
        env.expandBy(tol);
        for (Obstacle obstacle : query(env))
            if (obstacle.isExistingNetwork() && ex.exemptAt(obstacle, c)
                    && obstacle.geometry().distance(GeometryUtils.point(c)) <= tol) return true;
        return false;
    }

    /** True if the point lies on an end vertex of (a part of) the linear obstacle. */
    private static boolean touchesLineEnd(Obstacle o, Coordinate x) {
        Geometry g = o.geometry();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
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
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
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
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            double t = a.distance(c);
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        return new double[]{Math.max(0, min), Math.min(len, max)};
    }

    /** True if every interval is covered by the union of the covers (up to COVER_TOL of uncovered length per gap). */
    static boolean covered(List<double[]> intervals, List<double[]> covers) {
        for (double[] iv : intervals) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            double from = iv[0], to = iv[1];
            List<double[]> rem = new ArrayList<>();
            rem.add(new double[]{from, to});
            for (double[] c : covers) {
                ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
                List<double[]> next = new ArrayList<>();
                for (double[] r : rem) {
                    ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
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

    private Geometry intersection(Geometry a, Geometry b, Obstacle o) {
        return ru.lct.heatnet.geo.RobustGeometry.intersection(a, b, o, geometryStats);
    }

    /** Acute angle between the segment and the polygon boundary edge nearest to the crossing point. */
    private static double boundaryAngle(Obstacle o, Coordinate a, Coordinate b, Coordinate p) {
        return o.boundarySegments().angle(a, b, p);
    }

    /** Acute angle to the linear object's nearest segment (same exact search/tie rule). */
    private static double lineAngle(Obstacle o, Coordinate a, Coordinate b, Coordinate p) {
        return o.boundarySegments().angle(a, b, p);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Vertex generation for the navigation graph
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Candidate turning points: convex corners of the union of vertex zones (reflex vertices of the free space),
     * excluding points inside forbidden check zones.
     */
    public List<Coordinate> navigationVertices() {
        return navigationVertices(Integer.MAX_VALUE);
    }

    public List<Coordinate> navigationVertices(int maxNodes) {
        return navigationVertices(vertexZoneUnion(), maxNodes);
    }

    /**
     * Fallback only: finer arc chords require a smaller outward safety offset. The ordinary navigation union
     * intentionally overestimates buffers, and can close a corridor that remains open in the check geometry.
     * These are extra candidates only: every connecting edge still uses the unchanged segment checker.
     */
    public List<Coordinate> refinedNavigationVertices(int maxNodes) {
        int quadrants = 12;
        double factor = 1.0 / Math.cos(Math.PI / (4.0 * quadrants));
        return navigationVertices(buildNavigationUnion(navigationRegion,quadrants,factor),maxNodes);
    }

    private List<Coordinate> navigationVertices(Geometry union, int maxNodes) {
        List<Coordinate> out = new ArrayList<>();
        for (Polygon poly : GeometryUtils.polygons(union)) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            collectConvex(poly.getExteriorRing().getCoordinates(), true, out, maxNodes);
            for (int i = 0; i < poly.getNumInteriorRing(); i++) collectConvex(poly.getInteriorRingN(i).getCoordinates(), false, out, maxNodes);
        }
        List<Coordinate> filtered = new ArrayList<>();
        for (Coordinate c : out) if (!insideForbidden(c)) filtered.add(c);
        return filtered;
    }

    private static void collectConvex(Coordinate[] ring, boolean shell, List<Coordinate> out, int maxNodes) {
        if (ring.length < 4) return;
        boolean ccw = Orientation.isCCW(ring);
        // we want the obstacle on the right-hand side while walking the ring: shell -> CW, hole -> CCW
        boolean reverse = shell ? ccw : !ccw;
        int n = ring.length - 1;
        for (int i = 0; i < n; i++) {
            ru.lct.heatnet.routing.ComputeCancellation.checkpoint();
            Coordinate prev = ring[(i - 1 + n) % n], cur = ring[i], next = ring[(i + 1) % n];
            if (reverse) { Coordinate t = prev; prev = next; next = t; }
            double cross = (cur.x - prev.x) * (next.y - cur.y) - (cur.y - prev.y) * (next.x - cur.x);
            if (cross < -1e-9) {
                if (out.size() >= maxNodes) throw new ru.lct.heatnet.routing.ComplexityLimitException("Navigation graph exceeds "
                        + maxNodes + " obstacle vertices; the input geometry is too complex for automatic routing");
                out.add(cur); // right turn = convex obstacle corner
            }
        }
    }
}
