package ru.lct.heatnet.topology;

/** Package bridge letting the hydraulic module set flow/DU on links. */
public final class LinkMutator {
    private LinkMutator() {}
    public static void setFlow(NetLink l, double q) { l.flow = q; }
    public static void setDu(NetLink l, int du) { l.du = du; }
}
