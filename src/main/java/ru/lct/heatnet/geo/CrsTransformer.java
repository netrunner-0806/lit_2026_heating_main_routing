package ru.lct.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.GeometryTransformer;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import ru.lct.heatnet.config.Constants;

/**
 * WGS84 (EPSG:4326, lon/lat) <-> WGS84 / UTM zone 37N (EPSG:32637). Thread-safe.
 */
public final class CrsTransformer {

    private static final CrsTransformer INSTANCE = new CrsTransformer();

    public static final GeometryFactory METRIC_FACTORY = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING), 32637);
    public static final GeometryFactory WGS84_FACTORY = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING), 4326);

    private final CoordinateTransform forward;
    private final CoordinateTransform inverse;

    private CrsTransformer() {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem wgs84 = f.createFromName(Constants.CRS_INPUT);
        CoordinateReferenceSystem utm = f.createFromName(Constants.CRS_METRIC);
        CoordinateTransformFactory tf = new CoordinateTransformFactory();
        this.forward = tf.createTransform(wgs84, utm);
        this.inverse = tf.createTransform(utm, wgs84);
    }

    public static CrsTransformer get() { return INSTANCE; }

    /** lon/lat -> x/y (metres). */
    public Coordinate toMetric(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        // proj4j transforms are not guaranteed thread-safe on shared ProjCoordinate; we allocate per call
        synchronized (forward) {
            forward.transform(new ProjCoordinate(lon, lat), out);
        }
        return new Coordinate(out.x, out.y);
    }

    /** x/y (metres) -> [lon, lat]. */
    public double[] toWgs84(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        synchronized (inverse) {
            inverse.transform(new ProjCoordinate(x, y), out);
        }
        return new double[]{out.x, out.y};
    }

    public Geometry toMetric(Geometry wgs84Geometry) {
        GeometryTransformer t = new GeometryTransformer() {
            @Override
            protected CoordinateSequence transformCoordinates(CoordinateSequence coords, Geometry parent) {
                Coordinate[] out = new Coordinate[coords.size()];
                for (int i = 0; i < coords.size(); i++) out[i] = toMetric(coords.getX(i), coords.getY(i));
                return METRIC_FACTORY.getCoordinateSequenceFactory().create(out);
            }
        };
        Geometry g = t.transform(wgs84Geometry);
        g.setSRID(32637);
        return g;
    }

    public Geometry toWgs84(Geometry metricGeometry) {
        GeometryTransformer t = new GeometryTransformer() {
            @Override
            protected CoordinateSequence transformCoordinates(CoordinateSequence coords, Geometry parent) {
                Coordinate[] out = new Coordinate[coords.size()];
                for (int i = 0; i < coords.size(); i++) {
                    double[] ll = toWgs84(coords.getX(i), coords.getY(i));
                    out[i] = new Coordinate(ll[0], ll[1]);
                }
                return WGS84_FACTORY.getCoordinateSequenceFactory().create(out);
            }
        };
        Geometry g = t.transform(metricGeometry);
        g.setSRID(4326);
        return g;
    }
}
