package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;

/** A place where a new tree may attach to the existing network. */
public final class TieInCandidate {

    public enum Kind { EXISTING_CHAMBER, NEW_CHAMBER_ON_LINE }

    private final Kind kind;
    private final Coordinate point;
    private final ExistingChamber chamber;
    private final ExistingNetworkLine line;
    private final int existingAdjacency;
    private int vertex = -1;

    public TieInCandidate(Kind kind, Coordinate point, ExistingChamber chamber, ExistingNetworkLine line, int existingAdjacency) {
        this.kind = kind;
        this.point = point;
        this.chamber = chamber;
        this.line = line;
        this.existingAdjacency = existingAdjacency;
    }

    public Kind kind() { return kind; }
    public Coordinate point() { return point; }
    public ExistingChamber chamber() { return chamber; }
    public ExistingNetworkLine line() { return line; }
    public int existingAdjacency() { return existingAdjacency; }
    public int vertex() { return vertex; }
    void setVertex(int v) { this.vertex = v; }

    /** Spare adjacency slots before any new link is attached. */
    public int spare() { return ru.lct.heatnet.config.Constants.MAX_CHAMBER_DEGREE - existingAdjacency; }

    @Override
    public String toString() {
        return kind == Kind.EXISTING_CHAMBER ? "chamber " + chamber.id() : "line " + line.id() + " @" + Math.round(point.x) + "," + Math.round(point.y);
    }
}
