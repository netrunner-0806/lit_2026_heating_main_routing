package ru.lct.heatnet.topology;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.restrictions.SpecialPassage;

import java.util.ArrayList;
import java.util.List;

/**
 * Link of the new network: polyline from the child node (away from the existing network) to the parent node
 * (towards the existing network). One link is one section of constant flow and DU.
 */
public final class NetLink {
    final int id;
    NetNode child;
    NetNode parent;
    /** Coordinates from child to parent. */
    final List<Coordinate> coords;
    /** Special passages, intervals measured along coords from the child end. */
    final List<SpecialPassage> passages;
    final double length;
    double flow;
    int du;
    /** Length from the child end where no junction may be placed (final approach segment inside the own ОКС). */
    double reservedFromChild;
    /** Obstacle index of the own ОКС polygon exempt on the first segment, or -1. */
    int ownPolygonIndex = -1;
    /** Depth profile along coords (child -> parent) in depth mode; null in the planar mode. */
    ru.lct.heatnet.depth.DepthProfile profile;

    NetLink(int id, NetNode child, NetNode parent, List<Coordinate> coords, List<SpecialPassage> passages) {
        this.id = id;
        this.child = child;
        this.parent = parent;
        this.coords = new ArrayList<>(coords);
        this.passages = new ArrayList<>(passages);
        this.length = ru.lct.heatnet.geo.GeometryUtils.length(this.coords);
    }

    NetLink copyShallow(NetNode child, NetNode parent) {
        NetLink l = new NetLink(id, child, parent, coords, passages);
        l.flow = flow;
        l.du = du;
        l.reservedFromChild = reservedFromChild;
        l.ownPolygonIndex = ownPolygonIndex;
        l.profile = profile;
        return l;
    }

    public int id() { return id; }
    public NetNode child() { return child; }
    public NetNode parent() { return parent; }
    public List<Coordinate> coords() { return coords; }
    public List<SpecialPassage> passages() { return passages; }
    public double length() { return length; }
    public double flow() { return flow; }
    public int du() { return du; }
    public double reservedFromChild() { return reservedFromChild; }
    public int ownPolygonIndex() { return ownPolygonIndex; }
    public ru.lct.heatnet.depth.DepthProfile profile() { return profile; }
    public void setProfile(ru.lct.heatnet.depth.DepthProfile p) { this.profile = p; }
    public void setApproachInfo(double reserved, int ownPolygonIndex) {
        this.reservedFromChild = reserved;
        this.ownPolygonIndex = ownPolygonIndex;
    }

    @Override
    public String toString() { return "link#" + id + "(" + child + "->" + parent + ", L=" + Math.round(length) + ", Q=" + flow + ", DU" + du + ")"; }
}
