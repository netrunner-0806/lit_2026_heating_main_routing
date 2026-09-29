package ru.lct.heatnet.validation;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.RestrictionRule;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.solver.ExistingNetworkIndex;
import ru.lct.heatnet.validation.VariantModel.Chain;
import ru.lct.heatnet.validation.VariantModel.Line;
import ru.lct.heatnet.validation.VariantModel.Node;
import ru.lct.heatnet.validation.VariantModel.NodeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometric checks of the output: turn angles, intersections between new lines, forbidden objects (no crossing,
 * clearance by DU with the own-ОКС final-segment exception), special passages (single straight special section of
 * the right extent, crossing angle, Kspec) and alongside clearance outside crossings. Implemented directly on JTS
 * geometry, independently of the routing code.
 */
final class SpatialValidator {

    private static final double SECTION_SNAP_M = ru.lct.heatnet.geo.GeometryTolerance.ON_LINE_M;
    private static final double STRAIGHT_TOL_DEG = ru.lct.heatnet.geo.GeometryTolerance.VALIDATOR_STRAIGHT_TOL_DEG;
    private static final double DIST_TOL_M = ru.lct.heatnet.geo.GeometryTolerance.INTERSECTION_EPS_M;
    private static final double ANGLE_TOL_DEG = ru.lct.heatnet.geo.GeometryTolerance.VALIDATOR_ANGLE_TOL_DEG;
    /** Two distinct new lines may stay within the coincidence tolerance of each other only near a common node (m). */
    private static final double NEAR_OVERLAP_MAX_M = 1.0;

    private static final class Obj {
        final String label;
        final RestrictionRule rule;
        final Geometry geom;
        final double ownHalfWidth;
        final boolean existingNetwork;
        final boolean oks;
        final double existingHeight;
        Obj(String label, RestrictionRule rule, Geometry geom, double ownHalfWidth, boolean existingNetwork) { this(label, rule, geom, ownHalfWidth, existingNetwork, Double.NaN); }
        Obj(String label, RestrictionRule rule, Geometry geom, double ownHalfWidth, boolean existingNetwork, double existingHeight) {
            this.label = label; this.rule = rule; this.geom = geom; this.ownHalfWidth = ownHalfWidth; this.existingNetwork = existingNetwork;
            this.oks = RestrictionRules.OKS.equals(rule.type());
            this.existingHeight = existingHeight;
        }
    }

    private final List<Obj> objects = new ArrayList<>();
    private final STRtree index = new STRtree();
    private final ExistingNetworkIndex netIndex;
    private double maxExistingHalfWidth = 0;

    SpatialValidator(InputModel input, ExistingNetworkIndex netIndex) {
        this.netIndex = netIndex;
        for (ExistingNetworkLine l : input.networkLines()) maxExistingHalfWidth = Math.max(maxExistingHalfWidth, l.halfWidthM());
        for (Restriction r : input.restrictions()) {
            if (r.rule() == null) continue;
            Obj o = new Obj(r.restrictionType() + "#" + r.id(), r.rule(), r.geometry(), r.rule().ownWidthM() / 2, false);
            objects.add(o);
            index.insert(r.geometry().getEnvelopeInternal(), o);
        }
        for (ExistingNetworkLine l : input.networkLines()) {
            Obj o = new Obj("heat_network#" + l.id(), RestrictionRules.existingHeatNetwork(), l.line(), l.halfWidthM(), true, l.spec().heightM());
            objects.add(o);
            index.insert(l.line().getEnvelopeInternal(), o);
        }
        index.build();
    }

    void validate(VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId;
        // turns along chains (across technical nodes)
        for (Chain c : vm.chains) {
            rep.counted("turns");
            for (int i = 1; i + 1 < c.coords.size(); i++) {
                double t = GeometryUtils.turnAngleDeg(c.coords.get(i - 1), c.coords.get(i), c.coords.get(i + 1));
                if (t > Constants.MAX_TURN_ANGLE_DEG + ANGLE_TOL_DEG)
                    rep.error("TURN_ANGLE", String.format(java.util.Locale.ROOT, "turn of %.1f° at vertex %d of the section from %s", t, i, c.childEnd.id), vid, c.lines.get(0).id);
            }
        }
        // intersections between lines
        for (int i = 0; i < vm.lines.size(); i++) {
            Line a = vm.lines.get(i);
            for (int j = i + 1; j < vm.lines.size(); j++) {
                Line b = vm.lines.get(j);
                if (!a.metric.getEnvelopeInternal().intersects(b.metric.getEnvelopeInternal())) continue;
                rep.counted("intersections");
                Geometry x = a.metric.intersection(b.metric);
                boolean ok = true;
                for (int k = 0; k < x.getNumGeometries(); k++) {
                    Geometry g = x.getGeometryN(k);
                    if (!(g instanceof Point)) { ok = false; break; }
                    Coordinate p = g.getCoordinate();
                    boolean sharedNode = false;
                    for (Node n : new Node[]{a.start, a.end}) if (n != null && (n == b.start || n == b.end) && n.coord.distance(p) < 0.01) sharedNode = true;
                    if (!sharedNode) ok = false;
                }
                if (!ok) { rep.error("LINES_INTERSECT", "new lines " + a.id + " and " + b.id + " intersect outside a common node", vid, a.id); continue; }
                // no exact intersection outside common nodes, but a long run closer than the coincidence tolerance
                // (e.g. two lines drawn a fraction of a millimetre apart) is an overlap as well
                double near = b.metric.intersection(a.metric.buffer(Constants.COINCIDENCE_TOL_M)).getLength();
                if (near > NEAR_OVERLAP_MAX_M)
                    rep.error("LINES_TOO_CLOSE", String.format(java.util.Locale.ROOT, "new lines %s and %s run within %.2f m of each other over %.2f m", a.id, b.id, Constants.COINCIDENCE_TOL_M, near), vid, a.id);
            }
        }
        // turns along the flow path through chambers: last segment of a chain -> first segment of its parent chain
        java.util.Map<Node, Chain> chainOfChild = new java.util.HashMap<>();
        for (Chain c : vm.chains) chainOfChild.put(c.childEnd, c);
        for (Chain c : vm.chains) {
            if (c.parentEnd == null || c.coords.size() < 2) continue;
            Chain pc = chainOfChild.get(c.parentEnd);
            if (pc == null || pc.coords.size() < 2) continue;
            rep.counted("turns");
            double t = GeometryUtils.turnAngleDeg(c.coords.get(c.coords.size() - 2), c.coords.get(c.coords.size() - 1), pc.coords.get(1));
            if (t > Constants.MAX_TURN_ANGLE_DEG + ANGLE_TOL_DEG)
                rep.error("TURN_AT_CHAMBER", String.format(java.util.Locale.ROOT, "turn of %.1f° at chamber %s on the path from %s towards the existing network", t, c.parentEnd.id, c.childEnd.id), vid, c.lines.get(c.lines.size() - 1).id);
        }
        // every special feature must be one straight segment
        for (Line l : vm.lines) {
            if (!l.special) continue;
            for (int i = 1; i + 1 < l.coords.size(); i++) {
                double t = GeometryUtils.turnAngleDeg(l.coords.get(i - 1), l.coords.get(i), l.coords.get(i + 1));
                if (t > STRAIGHT_TOL_DEG) { rep.error("SPECIAL_NOT_STRAIGHT", String.format(java.util.Locale.ROOT, "special passage feature has an interior turn of %.2f°", t), vid, l.id); break; }
            }
        }
        // restrictions per chain
        for (Chain c : vm.chains) checkChain(c, vm, rep);
        for (Line l : vm.lines)
            if (l.special && !l.coveredBySection) rep.error("SPECIAL_WITHOUT_CROSSING", "laying_method=special but the line crosses no special-passage object", vid, l.id);
    }

    private void checkChain(Chain c, VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId;
        LineString chainLine = GeometryUtils.line(c.coords);
        LengthIndexedLine lil = new LengthIndexedLine(chainLine);
        int du = c.lines.get(0).du;
        Obj own = null;
        double finalLen = 0;
        if (c.childEnd.kind == NodeKind.OKS) {
            own = ownPolygon(c.childEnd.coord, du);
            if (own != null && c.coords.size() >= 2) finalLen = c.coords.get(0).distance(c.coords.get(1));
        }
        boolean parentIsRoot = c.parentEnd != null && c.parentEnd.root;
        Envelope env = chainLine.getEnvelopeInternal();
        env.expandBy(15);
        @SuppressWarnings("unchecked")
        List<Obj> cands = index.query(env);
        for (Obj o : cands) {
            double required = o.rule.requiredAxisDistanceM(du, o.ownHalfWidth);
            if (o.geom.distance(chainLine) > required + 1) continue; // far away
            rep.counted("restriction:" + o.rule.type());
            if (o.rule.isForbidden()) {
                checkForbidden(o, own, finalLen, c, chainLine, lil, required, vm, rep);
            } else {
                checkSpecial(o, c, chainLine, lil, required, parentIsRoot, vm, rep);
            }
        }
    }

    private Obj ownPolygon(Coordinate p, int du) {
        Point pt = GeometryUtils.point(p);
        Obj best = null;
        double bestD = Double.MAX_VALUE;
        Envelope env = new Envelope(p);
        env.expandBy(20);
        @SuppressWarnings("unchecked")
        List<Obj> cands = index.query(env);
        for (Obj o : cands) {
            if (!o.oks || !(o.geom instanceof org.locationtech.jts.geom.Polygonal)) continue;
            if (o.geom.covers(pt)) return o;
            double d = o.geom.distance(pt);
            if (d < o.rule.requiredAxisDistanceM(du, 0) && d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    private void checkForbidden(Obj o, Obj own, double finalLen, Chain c, LineString chainLine, LengthIndexedLine lil, double required,
                                VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId;
        String lineId = c.lines.get(0).id;
        if (o == own) {
            // the final straight segment may enter the own polygon; the rest must keep the clearance
            if (c.length - finalLen > 1e-6) {
                Geometry rest = lil.extractLine(finalLen, c.length);
                if (o.geom.intersects(rest)) rep.error("OWN_OKS_CROSSED", "the route enters the own ОКС polygon outside the final straight segment", vid, lineId);
                double d = o.geom.distance(rest);
                if (d + DIST_TOL_M < required)
                    rep.error("OWN_OKS_CLEARANCE", String.format(java.util.Locale.ROOT, "distance %.2f m to the own ОКС polygon beyond the final segment < required %.2f m", d, required), vid, lineId);
            }
            return;
        }
        if (o.geom.intersects(chainLine)) {
            rep.error("FORBIDDEN_CROSSED", "the route crosses " + o.label + " (crossing forbidden)", vid, lineId);
            return;
        }
        // clearance applies in full to every foreign object, including on the final segment
        if (finalLen > 0) {
            Geometry fin = lil.extractLine(0, finalLen);
            double d = o.geom.distance(fin);
            if (d + DIST_TOL_M < required)
                rep.error("CLEARANCE", String.format(java.util.Locale.ROOT, "final segment to %s passes %.2f m from %s (< required %.2f m)", c.childEnd.id, d, o.label, required), vid, lineId);
            if (c.length - finalLen > 1e-6) {
                Geometry rest = lil.extractLine(finalLen, c.length);
                double d2 = o.geom.distance(rest);
                if (d2 + DIST_TOL_M < required) rep.error("CLEARANCE", String.format(java.util.Locale.ROOT, "route passes %.2f m from %s (< required %.2f m for DU%d)", d2, o.label, required, c.lines.get(0).du), vid, lineIdAt(c, lil, o)); 
            }
        } else {
            double d = o.geom.distance(chainLine);
            if (d + DIST_TOL_M < required) rep.error("CLEARANCE", String.format(java.util.Locale.ROOT, "route passes %.2f m from %s (< required %.2f m for DU%d)", d, o.label, required, c.lines.get(0).du), vid, lineIdAt(c, lil, o));
        }
    }

    private String lineIdAt(Chain c, LengthIndexedLine lil, Obj o) {
        Coordinate[] near = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(GeometryUtils.line(c.coords), o.geom);
        double pos = lil.project(near[0]);
        for (int i = 0; i < c.lines.size(); i++) {
            double[] iv = c.lineIntervals.get(i);
            if (pos >= iv[0] - 1e-6 && pos <= iv[1] + 1e-6) return c.lines.get(i).id;
        }
        return c.lines.get(0).id;
    }

    private void checkSpecial(Obj o, Chain c, LineString chainLine, LengthIndexedLine lil, double required, boolean parentIsRoot,
                              VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId;
        RestrictionRule rule = o.rule;
        double ext = rule.sectionExtentM();
        double L = c.length;
        Geometry inter = o.geom.intersection(chainLine);
        List<double[]> sections = new ArrayList<>();   // [from, to] along the chain
        List<double[]> covers = new ArrayList<>();     // intervals where alongside clearance is not checked
        int du = c.lines.get(0).du;
        double coverR = rule.crossingCoverRadiusM(du, o.ownHalfWidth) - ext;
        if (parentIsRoot && o.existingNetwork) covers.add(new double[]{L - rule.tieInExemptRadiusM(du, maxExistingHalfWidth), L + 1});
        for (int k = 0; k < inter.getNumGeometries(); k++) {
            Geometry g = inter.getGeometryN(k);
            if (g.isEmpty()) continue;
            double from, to;
            Coordinate at;
            if (g instanceof Point) {
                double t = lil.project(g.getCoordinate());
                if (o.existingNetwork && parentIsRoot && L - t < Constants.TERMINATION_TOL_M) continue; // termination at the tie-in
                if (o.geom instanceof org.locationtech.jts.geom.Polygonal) continue;           // touching a polygon boundary
                from = t - ext; to = t + ext; at = g.getCoordinate();
            } else {
                double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                for (Coordinate cc : g.getCoordinates()) { double t = lil.project(cc); mn = Math.min(mn, t); mx = Math.max(mx, t); }
                if (!(o.geom instanceof org.locationtech.jts.geom.Polygonal)) {
                    rep.error("RUNS_ALONG", "the route runs along " + o.label + " over " + String.format(java.util.Locale.ROOT, "%.1f", mx - mn) + " m", vid, c.lines.get(0).id);
                    continue;
                }
                if (mx - mn < 1e-6) continue;
                from = mn - ext; to = mx + ext; at = lil.extractPoint(mn);
                // annex 4: the angle is checked at the point of entry (traversal consumer -> existing network);
                // the exit angle is reported for information only
                if (rule.minCrossingAngleDeg() > 0) {
                    double angIn = boundaryAngle(o.geom, lil, mn);
                    if (angIn + ANGLE_TOL_DEG < rule.minCrossingAngleDeg())
                        rep.error("CROSSING_ANGLE", String.format(java.util.Locale.ROOT, "entry into %s at %.1f° < %.0f°", o.label, angIn, rule.minCrossingAngleDeg()), vid, lineAt(c, mn));
                    double angOut = boundaryAngle(o.geom, lil, mx);
                    if (angOut + ANGLE_TOL_DEG < rule.minCrossingAngleDeg())
                        rep.info("CROSSING_EXIT_ANGLE", String.format(java.util.Locale.ROOT, "exit from %s at %.1f° (entry %.1f°; only the entry angle is mandatory)", o.label, angOut, angIn), vid, lineAt(c, mx));
                }
            }
            if (rule.minCrossingAngleDeg() > 0 && g instanceof Point) {
                double ang = lineAngle(o.geom, lil, lil.project(g.getCoordinate()));
                if (ang + ANGLE_TOL_DEG < rule.minCrossingAngleDeg())
                    rep.error("CROSSING_ANGLE", String.format(java.util.Locale.ROOT, "crossing of %s at %.1f° < %.0f°", o.label, ang, rule.minCrossingAngleDeg()), vid, lineAt(c, lil.project(at)));
            }
            // section inside the chain (snap to the ends within tolerance)
            if (from < -SECTION_SNAP_M || to > L + SECTION_SNAP_M) {
                rep.error("SECTION_OUTSIDE", String.format(java.util.Locale.ROOT, "special section for %s [%.1f..%.1f] does not fit into the section between nodes (length %.1f)", o.label, from, to, L), vid, lineAt(c, Math.max(0, Math.min(L, (from + to) / 2))));
            }
            from = Math.max(0, from); to = Math.min(L, to);
            sections.add(new double[]{from, to});
            covers.add(new double[]{from - coverR, to + coverR});
            // straightness inside the section
            double acc = 0;
            for (int i = 1; i + 1 < c.coords.size(); i++) {
                acc += c.coords.get(i - 1).distance(c.coords.get(i));
                if (acc > from + 1e-6 && acc < to - 1e-6) {
                    double t = GeometryUtils.turnAngleDeg(c.coords.get(i - 1), c.coords.get(i), c.coords.get(i + 1));
                    if (t > 0.01) rep.error("SECTION_NOT_STRAIGHT", String.format(java.util.Locale.ROOT, "special passage through %s contains a turn of %.1f°", o.label, t), vid, lineAt(c, acc));
                }
            }
            // coverage by special lines (one crossing record per section, shared by every line covering it)
            VariantModel.Crossing crossing = new VariantModel.Crossing(rule.type(), o.label, o.existingHeight);
            for (int i = 0; i < c.lines.size(); i++) {
                double[] iv = c.lineIntervals.get(i);
                double ov = Math.min(iv[1], to) - Math.max(iv[0], from);
                if (ov > 0.01) {
                    Line l = c.lines.get(i);
                    if (!l.special) rep.error("CROSSING_NOT_SPECIAL", String.format(java.util.Locale.ROOT, "line crosses %s but laying_method is base (section %.1f..%.1f m of the section)", o.label, from, to), vid, l.id);
                    l.coveredBySection = true;
                    l.coveringObjects.add(o.label);
                    l.kSpecExpected = Math.max(l.kSpecExpected, rule.kSpec());
                    l.crossings.add(crossing);
                    if (l.special && (iv[0] < from - SECTION_SNAP_M || iv[1] > to + SECTION_SNAP_M) && !coveredByOthers(l, iv, sections))
                        rep.warn("SPECIAL_LONGER_THAN_SECTION", String.format(java.util.Locale.ROOT, "special line extends beyond the special section of %s", o.label), vid, l.id);
                }
            }
        }
        // alongside clearance outside crossings
        List<double[]> rem = subtract(new double[]{0, L}, covers);
        for (double[] r : rem) {
            if (r[1] - r[0] < 0.02) continue;
            Geometry part = lil.extractLine(r[0], r[1]);
            double d = o.geom.distance(part);
            if (d + DIST_TOL_M < required)
                rep.error("CLEARANCE", String.format(java.util.Locale.ROOT, "route passes %.2f m from %s (< required %.2f m) outside a special passage", d, o.label, required), vid, lineAt(c, (r[0] + r[1]) / 2));
        }
    }

    private static boolean coveredByOthers(Line l, double[] iv, List<double[]> sections) {
        List<double[]> rem = subtract(new double[]{iv[0], iv[1]}, sections);
        for (double[] r : rem) if (r[1] - r[0] > SECTION_SNAP_M) return false;
        return true;
    }

    private String lineAt(Chain c, double pos) {
        for (int i = 0; i < c.lines.size(); i++) {
            double[] iv = c.lineIntervals.get(i);
            if (pos >= iv[0] - 1e-6 && pos <= iv[1] + 1e-6) return c.lines.get(i).id;
        }
        return c.lines.get(0).id;
    }

    private static List<double[]> subtract(double[] iv, List<double[]> covers) {
        List<double[]> rem = new ArrayList<>();
        rem.add(iv);
        for (double[] cv : covers) {
            List<double[]> next = new ArrayList<>();
            for (double[] r : rem) {
                if (cv[1] <= r[0] || cv[0] >= r[1]) { next.add(r); continue; }
                if (cv[0] > r[0]) next.add(new double[]{r[0], cv[0]});
                if (cv[1] < r[1]) next.add(new double[]{cv[1], r[1]});
            }
            rem = next;
        }
        return rem;
    }

    /** Acute angle between the chain direction at pos and the nearest polygon boundary edge. */
    private static double boundaryAngle(Geometry poly, LengthIndexedLine lil, double pos) {
        Coordinate p = lil.extractPoint(pos);
        Coordinate a = lil.extractPoint(Math.max(0, pos - 0.5)), b = lil.extractPoint(pos + 0.5);
        double best = Double.MAX_VALUE;
        double[] dir = null;
        for (Polygon pg : GeometryUtils.polygons(poly)) {
            List<LineString> rings = new ArrayList<>();
            rings.add(pg.getExteriorRing());
            for (int i = 0; i < pg.getNumInteriorRing(); i++) rings.add(pg.getInteriorRingN(i));
            for (LineString ring : rings) {
                Coordinate[] cs = ring.getCoordinates();
                for (int i = 0; i + 1 < cs.length; i++) {
                    double d = org.locationtech.jts.algorithm.Distance.pointToSegment(p, cs[i], cs[i + 1]);
                    if (d < best) { best = d; dir = new double[]{cs[i + 1].x - cs[i].x, cs[i + 1].y - cs[i].y}; }
                }
            }
        }
        if (dir == null) return 90;
        return GeometryUtils.acuteAngleDeg(b.x - a.x, b.y - a.y, dir[0], dir[1]);
    }

    private static double lineAngle(Geometry line, LengthIndexedLine lil, double pos) {
        Coordinate p = lil.extractPoint(pos);
        Coordinate a = lil.extractPoint(Math.max(0, pos - 0.5)), b = lil.extractPoint(pos + 0.5);
        double best = Double.MAX_VALUE;
        double[] dir = null;
        for (int k = 0; k < line.getNumGeometries(); k++) {
            Coordinate[] cs = line.getGeometryN(k).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double d = org.locationtech.jts.algorithm.Distance.pointToSegment(p, cs[i], cs[i + 1]);
                if (d < best) { best = d; dir = new double[]{cs[i + 1].x - cs[i].x, cs[i + 1].y - cs[i].y}; }
            }
        }
        if (dir == null) return 90;
        return GeometryUtils.acuteAngleDeg(b.x - a.x, b.y - a.y, dir[0], dir[1]);
    }
}
