package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.restrictions.Obstacle;
import ru.lct.heatnet.restrictions.SegmentCheck;
import ru.lct.heatnet.routing.NavGraph;

/**
 * Final approach of a connection point: the fixed straight segment from the free-space point A through the nearest
 * boundary point B of the own ОКС polygon to the connection point P (section 2.2 of the annex).
 */
public final class Approach {
    private final ConnectionPoint cp;
    private final Obstacle ownPolygon;
    private final Coordinate boundaryPoint;
    private final Coordinate freePoint;
    private final SegmentCheck finalSegment; // check of the final segment traversed P->A (null when A == P)
    private final boolean nearestBoundary;
    private int vertex = -1;

    public Approach(ConnectionPoint cp, Obstacle ownPolygon, Coordinate boundaryPoint, Coordinate freePoint,
                    SegmentCheck finalSegment, boolean nearestBoundary) {
        this.cp = cp;
        this.ownPolygon = ownPolygon;
        this.boundaryPoint = boundaryPoint;
        this.freePoint = freePoint;
        this.finalSegment = finalSegment;
        this.nearestBoundary = nearestBoundary;
    }

    public ConnectionPoint cp() { return cp; }
    public Obstacle ownPolygon() { return ownPolygon; }
    public Coordinate boundaryPoint() { return boundaryPoint; }
    /** Point in free space where the graph route starts. */
    public Coordinate freePoint() { return freePoint; }
    public Coordinate target() { return cp.coordinate(); }
    public boolean hasFinalSegment() { return finalSegment != null; }
    public SegmentCheck finalSegment() { return finalSegment; }
    public double finalLength() { return finalSegment == null ? 0 : finalSegment.length(); }
    public boolean nearestBoundary() { return nearestBoundary; }
    public int vertex() { return vertex; }
    void setVertex(int v) { this.vertex = v; }

    /** Heading of the fixed segment P->A (null if none): the first graph edge must not turn more than 90° from it. */
    public NavGraph.Heading heading() {
        if (finalSegment == null) return null;
        Coordinate p = cp.coordinate();
        return new NavGraph.Heading(freePoint.x - p.x, freePoint.y - p.y);
    }
}
