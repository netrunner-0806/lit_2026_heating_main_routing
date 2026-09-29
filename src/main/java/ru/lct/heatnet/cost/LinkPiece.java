package ru.lct.heatnet.cost;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.restrictions.Obstacle;

import java.util.List;

/** Part of a link with constant laying method and Kspec (becomes one output heat_network LineString). */
public final class LinkPiece {
    private final List<Coordinate> coords;
    private final double from;
    private final double to;
    private final double length;
    private final boolean special;
    private final double kSpec;
    private final List<Obstacle> crossed;
    private final double cost;
    /** Depth of the envelope top at both ends (NaN in the planar mode) and the applied Kdepth. */
    private final double depthStart;
    private final double depthEnd;
    private final double kDepth;

    public LinkPiece(List<Coordinate> coords, double from, double to, boolean special, double kSpec, List<Obstacle> crossed, double cost) {
        this(coords, from, to, special, kSpec, crossed, cost, Double.NaN, Double.NaN, 1.0);
    }

    public LinkPiece(List<Coordinate> coords, double from, double to, boolean special, double kSpec, List<Obstacle> crossed, double cost,
                     double depthStart, double depthEnd, double kDepth) {
        this(coords, from, to, special, kSpec, crossed, cost, depthStart, depthEnd, kDepth, Double.NaN);
    }

    public LinkPiece(List<Coordinate> coords, double from, double to, boolean special, double kSpec, List<Obstacle> crossed, double cost,
                     double depthStart, double depthEnd, double kDepth, double slopeExact) {
        this.slopeExact = slopeExact;
        this.coords = coords;
        this.from = from;
        this.to = to;
        this.length = CostCalculator.roundLength(to - from);
        this.special = special;
        this.kSpec = kSpec;
        this.crossed = crossed;
        this.cost = cost;
        this.depthStart = depthStart;
        this.depthEnd = depthEnd;
        this.kDepth = kDepth;
    }

    public double depthStart() { return depthStart; }
    public double depthEnd() { return depthEnd; }
    public double kDepth() { return kDepth; }
    public boolean hasDepth() { return !Double.isNaN(depthStart); }
    private final double slopeExact;

    /** Slope of the unrounded profile when known (never exceeds the planning limit), else from the reported values. */
    public double slope() {
        if (!Double.isNaN(slopeExact)) return slopeExact;
        return length() <= 0 || !hasDepth() ? 0 : Math.abs(depthEnd - depthStart) / length();
    }

    public List<Coordinate> coords() { return coords; }
    public double from() { return from; }
    public double to() { return to; }
    public double length() { return length; }
    public boolean special() { return special; }
    public double kSpec() { return kSpec; }
    public List<Obstacle> crossed() { return crossed; }
    public double cost() { return cost; }
}
