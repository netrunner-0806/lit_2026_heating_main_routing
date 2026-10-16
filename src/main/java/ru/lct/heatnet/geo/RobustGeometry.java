package ru.lct.heatnet.geo;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;

/** Preserve legacy results; retry only a numeric topology failure. Inputs are never repaired or rounded here. */
public final class RobustGeometry {
    private RobustGeometry() { }
    public static final class Stats {
        public final LongAdder intersections = new LongAdder();
        public final LongAdder retries = new LongAdder();
    }
    public static Geometry intersection(Geometry a, Geometry b, Object context) { return intersection(a, b, context, null); }
    public static Geometry intersection(Geometry a, Geometry b, Object context, Stats stats) {
        if (stats != null) stats.intersections.increment();
        try { return a.intersection(b); }
        catch (TopologyException first) {
            if (stats != null) stats.retries.increment();
            try {
                Geometry result = OverlayNGRobust.overlay(a, b, OverlayNG.INTERSECTION);
                if (!result.isValid()) throw new IllegalStateException("robust intersection is invalid");
                return result;
            } catch (RuntimeException second) { second.addSuppressed(first); throw new GeometryProcessingException(String.valueOf(context), second); }
        }
    }
    public static Geometry union(Collection<Geometry> zones, String context, Stats stats) {
        try { return UnaryUnionOp.union(zones); }
        catch (TopologyException first) {
            if (stats != null) stats.retries.increment();
            try {
                Geometry result = OverlayNGRobust.union(zones);
                if (!result.isValid()) throw new IllegalStateException("robust union is invalid");
                return result;
            } catch (RuntimeException second) { second.addSuppressed(first); throw new GeometryProcessingException(context, second); }
        }
    }
}
