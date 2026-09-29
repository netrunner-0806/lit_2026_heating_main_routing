package ru.lct.heatnet.topology;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;

import java.util.ArrayList;
import java.util.List;

/** Node of the new network tree: a connection point (leaf), a chamber (existing or new) or a root on an existing line. */
public final class NetNode {

    public enum Kind {
        /** oks_connection_point: leaf of the tree. */
        CONNECTION_POINT,
        /** Existing heat_chamber used as the tie-in (root). Each new link ending here is one tie-in. */
        EXISTING_CHAMBER,
        /** New heat_chamber created on an existing heat_network line (root). */
        NEW_CHAMBER_ON_EXISTING,
        /** New heat_chamber created on the new network where a branch joins. */
        NEW_CHAMBER_JUNCTION
    }

    final int id;
    final Kind kind;
    final Coordinate coord;
    final ConnectionPoint connectionPoint;
    final ExistingChamber existingChamber;
    final ExistingNetworkLine existingLine;
    /** Number of adjacency slots taken by existing lines (existing chamber / new chamber on an existing line). */
    final int existingAdjacency;
    NetLink parent;
    final List<NetLink> children = new ArrayList<>();

    NetNode(int id, Kind kind, Coordinate coord, ConnectionPoint cp, ExistingChamber ch, ExistingNetworkLine line, int existingAdjacency) {
        this.id = id;
        this.kind = kind;
        this.coord = coord;
        this.connectionPoint = cp;
        this.existingChamber = ch;
        this.existingLine = line;
        this.existingAdjacency = existingAdjacency;
    }

    NetNode copyShallow() {
        return new NetNode(id, kind, coord, connectionPoint, existingChamber, existingLine, existingAdjacency);
    }

    public int id() { return id; }
    public Kind kind() { return kind; }
    public Coordinate coord() { return coord; }
    public ConnectionPoint connectionPoint() { return connectionPoint; }
    public ExistingChamber existingChamber() { return existingChamber; }
    public ExistingNetworkLine existingLine() { return existingLine; }
    public int existingAdjacency() { return existingAdjacency; }
    public NetLink parent() { return parent; }
    public List<NetLink> children() { return children; }
    public boolean isRoot() { return kind == Kind.EXISTING_CHAMBER || kind == Kind.NEW_CHAMBER_ON_EXISTING; }
    public boolean isChamber() { return kind != Kind.CONNECTION_POINT; }
    public boolean isNewChamber() { return kind == Kind.NEW_CHAMBER_ON_EXISTING || kind == Kind.NEW_CHAMBER_JUNCTION; }

    /** Total adjacency: existing lines + new links. */
    public int degree() { return existingAdjacency + children.size() + (parent == null ? 0 : 1); }

    public int spareDegree() { return ru.lct.heatnet.config.Constants.MAX_CHAMBER_DEGREE - degree(); }

    /** Largest DU of all adjacent sections (new links and, for a chamber on an existing line, that line). */
    public int maxAdjacentDu() {
        int du = 0;
        if (parent != null) du = Math.max(du, parent.du);
        for (NetLink l : children) du = Math.max(du, l.du);
        if (kind == Kind.NEW_CHAMBER_ON_EXISTING && existingLine != null) du = Math.max(du, existingLine.diameter());
        return du;
    }

    @Override
    public String toString() { return kind + "#" + id; }
}
