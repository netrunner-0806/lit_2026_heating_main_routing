package ru.lct.heatnet.restrictions;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Exemptions applied when checking a segment: the own ОКС polygon of the final approach segment and discs around
 * segment endpoints where a given class of obstacles is not checked (tie-in point on an existing line, junction
 * point on the new network, existing chamber).
 */
public final class Exemptions {

    public static final Exemptions NONE = new Exemptions();

    /** Disc around a point inside which obstacles matching the predicate are ignored. */
    public static final class Disc {
        final Coordinate center;
        final double radius;
        final Predicate<Obstacle> applies;

        public Disc(Coordinate center, double radius, Predicate<Obstacle> applies) {
            this.center = center;
            this.radius = radius;
            this.applies = applies;
        }
    }

    private final List<Disc> discs = new ArrayList<>();
    private Set<Integer> ignoredObstacleIndexes = java.util.Collections.emptySet();

    public Exemptions() {}

    public Exemptions ignore(Set<Integer> obstacleIndexes) {
        this.ignoredObstacleIndexes = obstacleIndexes;
        return this;
    }

    public Exemptions disc(Coordinate center, double radius, Predicate<Obstacle> applies) {
        discs.add(new Disc(center, radius, applies));
        return this;
    }

    public boolean ignores(Obstacle o) { return ignoredObstacleIndexes.contains(o.index()); }

    /** Returns the exempt interval(s) along the segment a->b for the obstacle: list of [from,to] pairs. */
    public List<double[]> exemptIntervals(Obstacle o, Coordinate a, Coordinate b, double length) {
        List<double[]> out = new ArrayList<>();
        for (Disc d : discs) {
            if (!d.applies.test(o)) continue;
            // intersection of the segment with the disc -> interval along the segment
            double[] iv = discInterval(a, b, length, d.center, d.radius);
            if (iv != null) out.add(iv);
        }
        return out;
    }

    static double[] discInterval(Coordinate a, Coordinate b, double len, Coordinate c, double r) {
        if (len <= 0) return null;
        double ux = (b.x - a.x) / len, uy = (b.y - a.y) / len;
        double px = c.x - a.x, py = c.y - a.y;
        double t0 = px * ux + py * uy;                 // projection of the centre
        double d2 = px * px + py * py - t0 * t0;       // squared perpendicular distance
        double h2 = r * r - d2;
        if (h2 < 0) return null;
        double h = Math.sqrt(h2);
        double from = Math.max(0, t0 - h), to = Math.min(len, t0 + h);
        if (to <= from) return null;
        return new double[]{from, to};
    }

    /** True if the point lies inside a disc exemption applying to the obstacle. */
    public boolean exemptAt(Obstacle o, Coordinate c) {
        for (Disc d : discs) if (d.applies.test(o) && d.center.distance(c) <= d.radius) return true;
        return false;
    }

    /** Union of two exemption sets (used for edges between two terminals). */
    public static Exemptions merge(Exemptions a, Exemptions b) {
        if (a == null || a.isEmpty()) return b == null ? NONE : b;
        if (b == null || b.isEmpty()) return a;
        Exemptions m = new Exemptions();
        java.util.Set<Integer> ign = new java.util.HashSet<>(a.ignoredObstacleIndexes);
        ign.addAll(b.ignoredObstacleIndexes);
        m.ignoredObstacleIndexes = ign;
        m.discs.addAll(a.discs);
        m.discs.addAll(b.discs);
        return m;
    }

    public boolean isEmpty() { return discs.isEmpty() && ignoredObstacleIndexes.isEmpty(); }
}
