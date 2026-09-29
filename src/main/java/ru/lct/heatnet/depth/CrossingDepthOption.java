package ru.lct.heatnet.depth;

/** Chosen way of passing one utility on a plateau: ABOVE / BELOW / UNDER (road, tram). */
public final class CrossingDepthOption {
    public enum Method { ABOVE, BELOW, UNDER }

    private final VerticalObstacle obstacle;
    private final Method method;
    private final DepthInterval interval;

    public CrossingDepthOption(VerticalObstacle obstacle, Method method, DepthInterval interval) {
        this.obstacle = obstacle;
        this.method = method;
        this.interval = interval;
    }

    public VerticalObstacle obstacle() { return obstacle; }
    public Method method() { return method; }
    public DepthInterval interval() { return interval; }

    @Override
    public String toString() { return obstacle.label() + ":" + method + interval; }
}
