package ru.lct.heatnet.restrictions;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.config.RestrictionRule;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.domain.Restriction;

/**
 * A spatial object the new network must respect: a restriction feature or an existing heat network line
 * (crossable as a special passage). Holds the core geometry and its prepared form.
 */
public final class Obstacle {

    public enum Source { RESTRICTION, EXISTING_HEAT_NETWORK }

    private final int index;
    private final Source source;
    private final JsonId id;
    private final RestrictionRule rule;
    private final Geometry geometry;
    private final PreparedGeometry prepared;
    private final double ownHalfWidthM;
    private final boolean polygonal;
    /** Envelope height of an existing heat network line (Table 1 by its DU), NaN for restrictions. */
    private final double existingHeightM;

    private Obstacle(int index, Source source, JsonId id, RestrictionRule rule, Geometry geometry, double ownHalfWidthM) {
        this(index, source, id, rule, geometry, ownHalfWidthM, Double.NaN);
    }

    private Obstacle(int index, Source source, JsonId id, RestrictionRule rule, Geometry geometry, double ownHalfWidthM, double existingHeightM) {
        this.index = index;
        this.source = source;
        this.id = id;
        this.rule = rule;
        this.geometry = geometry;
        this.prepared = PreparedGeometryFactory.prepare(geometry);
        this.ownHalfWidthM = ownHalfWidthM;
        this.polygonal = geometry instanceof Polygonal;
        this.existingHeightM = existingHeightM;
    }

    public static Obstacle of(int index, Restriction r) {
        return new Obstacle(index, Source.RESTRICTION, r.id(), r.rule(), r.geometry(), r.rule().ownWidthM() / 2.0);
    }

    public static Obstacle of(int index, ExistingNetworkLine line, RestrictionRule existingNetworkRule) {
        return new Obstacle(index, Source.EXISTING_HEAT_NETWORK, line.id(), existingNetworkRule, line.line(), line.halfWidthM(), line.spec().heightM());
    }

    public int index() { return index; }
    public Source source() { return source; }
    public JsonId id() { return id; }
    public RestrictionRule rule() { return rule; }
    public Geometry geometry() { return geometry; }
    public PreparedGeometry prepared() { return prepared; }
    public boolean isPolygonal() { return polygonal; }
    public boolean isForbidden() { return rule.isForbidden(); }
    public boolean isSpecial() { return rule.isSpecial(); }
    public boolean isExistingNetwork() { return source == Source.EXISTING_HEAT_NETWORK; }
    public boolean isOks() { return source == Source.RESTRICTION && ru.lct.heatnet.config.RestrictionRules.OKS.equals(rule.type()); }

    /** Required distance between the new line axis and this object's geometry for the given DU. */
    public double requiredAxisDistance(int du) { return rule.requiredAxisDistanceM(du, ownHalfWidthM); }

    public double requiredAxisDistance(ClearanceClass cls) { return requiredAxisDistance(cls.representativeDu()); }
    public double ownHalfWidthM() { return ownHalfWidthM; }
    public double existingHeightM() { return existingHeightM; }

    public String describe() { return rule.type() + "#" + id; }

    @Override
    public String toString() { return describe(); }
}
