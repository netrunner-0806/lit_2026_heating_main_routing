package ru.lct.heatnet.output;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One output GeoJSON feature: ordered properties and WGS84 geometry (Point = double[2], LineString = list of double[2], null = no geometry). */
public final class OutputFeature {
    public enum GeomType { POINT, LINESTRING, NONE }

    private final GeomType geomType;
    private final double[] point;
    private final List<double[]> line;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    private OutputFeature(GeomType geomType, double[] point, List<double[]> line) {
        this.geomType = geomType;
        this.point = point;
        this.line = line;
    }

    public static OutputFeature point(double[] lonLat) { return new OutputFeature(GeomType.POINT, lonLat, null); }
    public static OutputFeature line(List<double[]> coords) { return new OutputFeature(GeomType.LINESTRING, null, coords); }
    public static OutputFeature noGeometry() { return new OutputFeature(GeomType.NONE, null, null); }

    public OutputFeature prop(String key, Object value) { properties.put(key, value); return this; }
    public Map<String, Object> properties() { return properties; }
    public GeomType geomType() { return geomType; }
    public double[] point() { return point; }
    public List<double[]> line() { return line; }
    public Object prop(String key) { return properties.get(key); }
    public String objectType() { return (String) properties.get("object_type"); }
}
