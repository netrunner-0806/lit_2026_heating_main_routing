package ru.lct.heatnet.restrictions;

import org.locationtech.jts.geom.Coordinate;

/** Package-private helper exposed for the routing package. */
public final class ExemptionsAccess {
    private ExemptionsAccess() {}

    public static double[] discInterval(Coordinate a, Coordinate b, double len, Coordinate c, double r) {
        return Exemptions.discInterval(a, b, len, c, r);
    }
}
