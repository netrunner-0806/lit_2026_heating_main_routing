package ru.lct.heatnet.restrictions;

import org.locationtech.jts.geom.Coordinate;

/**
 * One special passage of a straight segment through a crossable object: the special section is the interval
 * [from, to] measured along the segment from its start.
 */
public final class SpecialPassage {
    private final Obstacle obstacle;
    private final double from;
    private final double to;
    private final double kSpec;
    private final Coordinate crossingPoint;
    private final double crossingAngleDeg;

    public SpecialPassage(Obstacle obstacle, double from, double to, double kSpec, Coordinate crossingPoint, double crossingAngleDeg) {
        this.obstacle = obstacle;
        this.from = from;
        this.to = to;
        this.kSpec = kSpec;
        this.crossingPoint = crossingPoint;
        this.crossingAngleDeg = crossingAngleDeg;
    }

    public Obstacle obstacle() { return obstacle; }
    public double from() { return from; }
    public double to() { return to; }
    public double length() { return to - from; }
    public double kSpec() { return kSpec; }
    public Coordinate crossingPoint() { return crossingPoint; }
    public double crossingAngleDeg() { return crossingAngleDeg; }

    public SpecialPassage shifted(double offset) {
        return new SpecialPassage(obstacle, from + offset, to + offset, kSpec, crossingPoint, crossingAngleDeg);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%s [%.2f..%.2f] K=%.2f angle=%.1f", obstacle.describe(), from, to, kSpec, crossingAngleDeg);
    }
}
