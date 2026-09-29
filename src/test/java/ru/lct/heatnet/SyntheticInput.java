package ru.lct.heatnet;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.config.RestrictionRules;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.domain.Restriction;
import ru.lct.heatnet.domain.SourcePoint;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.geo.GeometryUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder of synthetic input models in local metric coordinates (offset into a valid UTM 37N area), unrelated to the
 * competition dataset. Coordinates are given in metres relative to the origin (400000, 6170000).
 */
public final class SyntheticInput {
    public static final double OX = 400_000, OY = 6_170_000;

    private final List<SourcePoint> sources = new ArrayList<>();
    private final List<ExistingNetworkLine> lines = new ArrayList<>();
    private final List<ExistingChamber> chambers = new ArrayList<>();
    private final List<ConnectionPoint> points = new ArrayList<>();
    private final List<Restriction> restrictions = new ArrayList<>();
    private int idx = 0;

    public static Coordinate c(double x, double y) { return new Coordinate(OX + x, OY + y); }

    private static double[] wgs(Coordinate m) { return CrsTransformer.get().toWgs84(m.x, m.y); }

    public SyntheticInput source(Object id, double x, double y) {
        Point p = GeometryUtils.point(c(x, y));
        sources.add(new SourcePoint(id(id), p, wgs(p.getCoordinate()), idx++));
        return this;
    }

    public SyntheticInput line(Object id, int du, double... xy) {
        Coordinate[] cs = new Coordinate[xy.length / 2];
        for (int i = 0; i < cs.length; i++) cs[i] = c(xy[2 * i], xy[2 * i + 1]);
        LineString ls = GeometryUtils.GF.createLineString(cs);
        lines.add(new ExistingNetworkLine(id(id), ls, du, DiameterTable.nearestForExisting(du), false, idx++));
        return this;
    }

    public SyntheticInput chamber(Object id, double x, double y) {
        Point p = GeometryUtils.point(c(x, y));
        chambers.add(new ExistingChamber(id(id), p, wgs(p.getCoordinate()), idx++));
        return this;
    }

    public SyntheticInput point(Object id, double x, double y, double flow) {
        Point p = GeometryUtils.point(c(x, y));
        points.add(new ConnectionPoint(id(id), p, flow, wgs(p.getCoordinate()), idx++));
        return this;
    }

    public SyntheticInput rect(Object id, String type, double x0, double y0, double x1, double y1) {
        Polygon poly = GeometryUtils.GF.createPolygon(new Coordinate[]{c(x0, y0), c(x1, y0), c(x1, y1), c(x0, y1), c(x0, y0)});
        return restriction(id, type, poly);
    }

    public SyntheticInput polyline(Object id, String type, double... xy) {
        Coordinate[] cs = new Coordinate[xy.length / 2];
        for (int i = 0; i < cs.length; i++) cs[i] = c(xy[2 * i], xy[2 * i + 1]);
        return restriction(id, type, GeometryUtils.GF.createLineString(cs));
    }

    public SyntheticInput restriction(Object id, String type, Geometry g) {
        restrictions.add(new Restriction(id(id), g, type, RestrictionRules.forType(type).orElse(null), idx++));
        return this;
    }

    public InputModel build() {
        return new InputModel(sources, lines, chambers, points, restrictions, new Diagnostics(), idx);
    }

    private static JsonId id(Object o) { return o instanceof Number ? JsonId.ofNumber((Number) o) : JsonId.ofString(String.valueOf(o)); }
}
