package ru.lct.heatnet.solver;

import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;

/** What a graph vertex represents as an attachment target for a new branch. */
final class Target {
    enum Type { TIE_IN, LINK_POINT, NODE }

    final Type type;
    final TieInCandidate tieIn;
    final int linkId;
    final double position;
    final int nodeId;

    private Target(Type type, TieInCandidate tieIn, int linkId, double position, int nodeId) {
        this.type = type;
        this.tieIn = tieIn;
        this.linkId = linkId;
        this.position = position;
        this.nodeId = nodeId;
    }

    static Target tieIn(TieInCandidate t) { return new Target(Type.TIE_IN, t, -1, 0, -1); }
    static Target linkPoint(NetLink l, double pos) { return new Target(Type.LINK_POINT, null, l.id(), pos, -1); }
    static Target node(NetNode n) { return new Target(Type.NODE, null, -1, 0, n.id()); }

    @Override
    public String toString() {
        switch (type) {
            case TIE_IN: return "tie-in " + tieIn;
            case LINK_POINT: return "link#" + linkId + "@" + Math.round(position);
            default: return "node#" + nodeId;
        }
    }
}
