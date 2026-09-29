package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Segments of the new network already built in the current variant. New routes must not intersect them or run
 * closer than the separation distance, except inside exemption discs around shared nodes (junctions).
 */
public final class DynamicObstacles {

    public static final class Seg {
        public final Coordinate a, b;
        public final Envelope env;
        public final Object owner;

        Seg(Coordinate a, Coordinate b, Object owner) {
            this.a = a; this.b = b; this.owner = owner;
            this.env = new Envelope(a, b);
        }
    }

    private final List<Seg> segs = new ArrayList<>();
    /** Hard rule: new lines must not intersect/overlap outside a common node (numerical tolerance only). */
    private final double hardDistance;
    /** Soft preference: routes closer than this to another new line pay a proximity penalty. */
    private final double softDistance;
    /** Penalty per metre of a segment part inside the soft distance (fraction of its length at zero distance). */
    public static final double PROXIMITY_PENALTY_FACTOR = 1.0;

    public DynamicObstacles(double hardDistance, double softDistance) {
        this.hardDistance = hardDistance;
        this.softDistance = softDistance;
    }

    public double hardDistance() { return hardDistance; }
    public double softDistance() { return softDistance; }

    public void add(Coordinate a, Coordinate b, Object owner) { segs.add(new Seg(a, b, owner)); }

    public void addPolyline(List<Coordinate> coords, Object owner) {
        for (int i = 1; i < coords.size(); i++) add(coords.get(i - 1), coords.get(i), owner);
    }

    public void clear() { segs.clear(); }

    public boolean isEmpty() { return segs.isEmpty(); }

    public List<Seg> segments() { return segs; }

    public DynamicObstacles copy() {
        DynamicObstacles d = new DynamicObstacles(hardDistance, softDistance);
        d.segs.addAll(segs);
        return d;
    }

    /**
     * True if the segment a->b keeps the separation from all stored segments. Exemption discs are given as
     * {@code Object[]{Coordinate centre, Double radius, Object ownerOrNull}}: inside the disc a stored segment is
     * ignored only if it belongs to the given owner (link) or has an endpoint at the disc centre (incident segment).
     */
    public boolean allows(Coordinate a, Coordinate b, List<Object[]> exempt) {
        return proximity(a, b, exempt, true) >= 0;
    }

    /** Soft penalty (metres of equivalent length) for running close to other new lines; 0 if farther than the soft distance. */
    public double proximityPenalty(Coordinate a, Coordinate b, List<Object[]> exempt) {
        double p = proximity(a, b, exempt, false);
        return p < 0 ? 0 : p;
    }

    /**
     * Hard mode: returns -1 if the segment comes closer than the hard distance to a stored segment (outside the
     * exemptions), else 0. Soft mode: returns the accumulated proximity penalty.
     */
    private double proximity(Coordinate a, Coordinate b, List<Object[]> exempt, boolean hard) {
        double len = a.distance(b);
        if (len < 1e-9) return 0;
        double reach = hard ? hardDistance : softDistance;
        double penalty = 0;
        Envelope env = new Envelope(a, b);
        env.expandBy(reach);
        for (Seg s : segs) {
            if (!s.env.intersects(env)) continue;
            List<double[]> exIv = new ArrayList<>();
            if (exempt != null) {
                for (Object[] e : exempt) {
                    Coordinate c = (Coordinate) e[0];
                    Object owner = e.length > 2 ? e[2] : null;
                    boolean applies = (owner != null && owner.equals(s.owner)) || s.a.distance(c) < 1e-3 || s.b.distance(c) < 1e-3;
                    if (!applies) continue;
                    double[] iv = ru.lct.heatnet.restrictions.ExemptionsAccess.discInterval(a, b, len, c, (Double) e[1]);
                    if (iv != null) exIv.add(iv);
                }
            }
            // split the query segment into non-exempt parts and check each
            List<double[]> parts = subtract(new double[]{0, len}, exIv);
            for (double[] p : parts) {
                if (p[1] - p[0] < 1e-6) continue;
                Coordinate pa = at(a, b, len, p[0]), pb = at(a, b, len, p[1]);
                double d = new LineSegment(pa, pb).distance(new LineSegment(s.a, s.b));
                if (hard) {
                    if (d < hardDistance) return -1;
                } else if (d < softDistance) {
                    penalty += PROXIMITY_PENALTY_FACTOR * (p[1] - p[0]) * (1.0 - d / softDistance);
                }
            }
        }
        return penalty;
    }

    /** Segments within the given distance of the point. */
    public List<Seg> near(Coordinate c, double dist) {
        List<Seg> out = new ArrayList<>();
        Envelope env = new Envelope(c);
        env.expandBy(dist);
        for (Seg s : segs) {
            if (!s.env.intersects(env)) continue;
            if (new LineSegment(s.a, s.b).distance(c) <= dist) out.add(s);
        }
        return out;
    }

    private static List<double[]> subtract(double[] iv, List<double[]> covers) {
        List<double[]> rem = new ArrayList<>();
        rem.add(iv);
        for (double[] c : covers) {
            List<double[]> next = new ArrayList<>();
            for (double[] r : rem) {
                if (c[1] <= r[0] || c[0] >= r[1]) { next.add(r); continue; }
                if (c[0] > r[0]) next.add(new double[]{r[0], c[0]});
                if (c[1] < r[1]) next.add(new double[]{c[1], r[1]});
            }
            rem = next;
        }
        return rem;
    }

    private static Coordinate at(Coordinate a, Coordinate b, double len, double t) {
        double f = t / len;
        return new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
    }
}
