package ru.lct.heatnet.solver;

/** Parameters that make variants substantively different. */
public final class Strategy {

    public enum Order { NEAREST_FIRST, FARTHEST_FIRST, LARGEST_FLOW_FIRST }

    public enum TieInPolicy { ANY, PREFER_EXISTING_CHAMBERS, EXISTING_CHAMBERS_ONLY, NEW_CHAMBERS_ONLY }

    private final String name;
    private final Order order;
    private final boolean allowJunctions;
    private final TieInPolicy tieInPolicy;
    private final boolean improve;

    public Strategy(String name, Order order, boolean allowJunctions, TieInPolicy tieInPolicy, boolean improve) {
        this.name = name;
        this.order = order;
        this.allowJunctions = allowJunctions;
        this.tieInPolicy = tieInPolicy;
        this.improve = improve;
    }

    public String name() { return name; }
    public Order order() { return order; }
    public boolean allowJunctions() { return allowJunctions; }
    public TieInPolicy tieInPolicy() { return tieInPolicy; }
    public boolean improve() { return improve; }

    @Override
    public String toString() { return name; }
}
