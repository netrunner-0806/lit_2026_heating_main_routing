package ru.lct.heatnet.topology;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.restrictions.SpecialPassage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable forest of new links rooted at tie-in nodes on the existing network. Supports cheap copying so that the
 * solver can evaluate tentative attachments.
 */
public final class NewNetwork {

    private final Map<Integer, NetNode> nodes = new LinkedHashMap<>();
    private final Map<Integer, NetLink> links = new LinkedHashMap<>();
    private int nextNodeId = 1;
    private int nextLinkId = 1;

    public NewNetwork() {}

    public NewNetwork copy() {
        NewNetwork n = new NewNetwork();
        n.nextNodeId = nextNodeId;
        n.nextLinkId = nextLinkId;
        Map<Integer, NetNode> nn = new HashMap<>();
        for (NetNode node : nodes.values()) {
            NetNode c = node.copyShallow();
            n.nodes.put(c.id, c);
            nn.put(c.id, c);
        }
        for (NetLink link : links.values()) {
            NetLink c = link.copyShallow(nn.get(link.child.id), nn.get(link.parent.id));
            n.links.put(c.id, c);
            c.child.parent = c;
            c.parent.children.add(c);
        }
        return n;
    }

    public Iterable<NetNode> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public Iterable<NetLink> links() { return Collections.unmodifiableCollection(links.values()); }
    public int linkCount() { return links.size(); }
    public NetNode node(int id) { return nodes.get(id); }
    public NetLink link(int id) { return links.get(id); }

    public List<NetNode> roots() {
        List<NetNode> r = new ArrayList<>();
        for (NetNode n : nodes.values()) if (n.isRoot()) r.add(n);
        return r;
    }

    public List<NetNode> connectionNodes() {
        List<NetNode> r = new ArrayList<>();
        for (NetNode n : nodes.values()) if (n.kind == NetNode.Kind.CONNECTION_POINT) r.add(n);
        return r;
    }

    public List<NetNode> newChambers() {
        List<NetNode> r = new ArrayList<>();
        for (NetNode n : nodes.values()) if (n.isNewChamber()) r.add(n);
        return r;
    }

    public List<NetNode> chambers() {
        List<NetNode> r = new ArrayList<>();
        for (NetNode n : nodes.values()) if (n.isChamber()) r.add(n);
        return r;
    }

    // ---- node creation ----

    public NetNode addConnectionNode(ConnectionPoint cp) {
        NetNode n = new NetNode(nextNodeId++, NetNode.Kind.CONNECTION_POINT, cp.coordinate(), cp, null, null, 0);
        nodes.put(n.id, n);
        return n;
    }

    /** Finds or creates the root node at an existing chamber. */
    public NetNode rootAtExistingChamber(ExistingChamber ch, int existingAdjacency) {
        for (NetNode n : nodes.values()) if (n.kind == NetNode.Kind.EXISTING_CHAMBER && n.existingChamber == ch) return n;
        NetNode n = new NetNode(nextNodeId++, NetNode.Kind.EXISTING_CHAMBER, ch.coordinate(), null, ch, null, existingAdjacency);
        nodes.put(n.id, n);
        return n;
    }

    /** Finds (within 1 mm) or creates the root node = new chamber on an existing line. */
    public NetNode rootOnExistingLine(ExistingNetworkLine line, Coordinate point, int existingAdjacency) {
        for (NetNode n : nodes.values())
            if (n.kind == NetNode.Kind.NEW_CHAMBER_ON_EXISTING && n.coord.distance(point) < 1e-3) return n;
        NetNode n = new NetNode(nextNodeId++, NetNode.Kind.NEW_CHAMBER_ON_EXISTING, point, null, null, line, existingAdjacency);
        nodes.put(n.id, n);
        return n;
    }

    /** Creates a junction chamber node at the coordinate (used when rebuilding a network from output features). */
    public NetNode addJunctionNode(Coordinate c) {
        NetNode n = new NetNode(nextNodeId++, NetNode.Kind.NEW_CHAMBER_JUNCTION, c, null, null, null, 0);
        nodes.put(n.id, n);
        return n;
    }

    /** Adds a link child->parent with the polyline given from child to parent. */
    public NetLink addLink(NetNode child, NetNode parent, List<Coordinate> coordsChildToParent, List<SpecialPassage> passages) {
        NetLink l = new NetLink(nextLinkId++, child, parent, coordsChildToParent, passages);
        links.put(l.id, l);
        child.parent = l;
        parent.children.add(l);
        return l;
    }

    /**
     * Splits the link at the given interior point (distance {@code at} along the link from its child end),
     * creating a new junction chamber there. Returns the junction node.
     */
    public NetNode splitLink(NetLink link, double at) {
        List<Coordinate> cs = link.coords;
        List<Coordinate> first = new ArrayList<>();
        List<Coordinate> second = new ArrayList<>();
        double acc = 0;
        Coordinate split = null;
        for (int i = 0; i < cs.size() - 1; i++) {
            Coordinate a = cs.get(i), b = cs.get(i + 1);
            double seg = a.distance(b);
            if (split == null) {
                first.add(a);
                if (acc + seg >= at - 1e-9) {
                    double f = Math.max(0, Math.min(1, (at - acc) / seg));
                    split = new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
                    if (split.distance(a) > 1e-6) first.add(split); else { first.remove(first.size() - 1); first.add(split); }
                    second.add(split);
                    if (split.distance(b) > 1e-6) second.add(b);
                }
            } else {
                second.add(b);
            }
            acc += seg;
        }
        if (split == null) throw new IllegalArgumentException("split position beyond link length");
        NetNode j = new NetNode(nextNodeId++, NetNode.Kind.NEW_CHAMBER_JUNCTION, split, null, null, null, 0);
        nodes.put(j.id, j);
        // passages: distribute
        double firstLen = ru.lct.heatnet.geo.GeometryUtils.length(first);
        List<SpecialPassage> p1 = new ArrayList<>(), p2 = new ArrayList<>();
        for (SpecialPassage p : link.passages) {
            if (p.to() <= firstLen + 1e-9) p1.add(p);
            else if (p.from() >= firstLen - 1e-9) p2.add(p.shifted(-firstLen));
            else throw new IllegalStateException("cannot split a link inside a special section");
        }
        NetNode child = link.child, parent = link.parent;
        links.remove(link.id);
        parent.children.remove(link);
        NetLink l1 = new NetLink(nextLinkId++, child, j, first, p1);
        NetLink l2 = new NetLink(nextLinkId++, j, parent, second, p2);
        l1.setApproachInfo(link.reservedFromChild, link.ownPolygonIndex);
        links.put(l1.id, l1);
        links.put(l2.id, l2);
        child.parent = l1;
        j.children.add(l1);
        j.parent = l2;
        parent.children.add(l2);
        return j;
    }

    /** True if the point (distance along the link) falls inside a special section or its neighbourhood. */
    public static boolean insideSpecial(NetLink link, double at, double margin) {
        for (SpecialPassage p : link.passages) if (at > p.from() - margin && at < p.to() + margin) return true;
        return false;
    }

    /** Removes a leaf connection node with its link; merges a junction left with degree 2 back into one link. */
    public void removeLeaf(NetNode leaf) {
        if (leaf.kind != NetNode.Kind.CONNECTION_POINT || leaf.parent == null) return;
        NetLink l = leaf.parent;
        NetNode p = l.parent;
        links.remove(l.id);
        p.children.remove(l);
        nodes.remove(leaf.id);
        if (p.kind == NetNode.Kind.NEW_CHAMBER_JUNCTION && p.children.size() == 1 && p.parent != null) {
            NetLink a = p.children.get(0), b = p.parent;
            List<Coordinate> cs = new ArrayList<>(a.coords);
            cs.addAll(b.coords.subList(1, b.coords.size()));
            List<SpecialPassage> ps = new ArrayList<>(a.passages);
            for (SpecialPassage sp : b.passages) ps.add(sp.shifted(a.length));
            links.remove(a.id);
            links.remove(b.id);
            b.parent.children.remove(b);
            nodes.remove(p.id);
            NetLink m = new NetLink(nextLinkId++, a.child, b.parent, cs, ps);
            m.setApproachInfo(a.reservedFromChild, a.ownPolygonIndex);
            links.put(m.id, m);
            a.child.parent = m;
            b.parent.children.add(m);
        } else if (p.isRoot() && p.children.isEmpty()) {
            nodes.remove(p.id);
        }
    }

    public double totalLength() {
        double s = 0;
        for (NetLink l : links.values()) s += l.length;
        return s;
    }
}
