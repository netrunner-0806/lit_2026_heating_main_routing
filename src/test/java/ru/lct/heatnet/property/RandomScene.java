package ru.lct.heatnet.property;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded generator of small random scenes (metric, inside the EPSG:32637 test area of {@link SyntheticInput}):
 * 1–5 OKS with their own rectangular buildings, 1–3 existing networks with sources and chambers, 0–4 rectangular
 * obstacles of random types, 0–2 utility polylines (gas / power cable), random flows 1–200 t/h.
 * The same seed always yields the same scene.
 */
public final class RandomScene {
    private RandomScene() {}

    private static final String[] AREA_TYPES = {"road", "water", "park", "oks", "railway", "tram_tracks", "social_area", "prohibited_site", "road", "oks"};
    private static final int[] EXISTING_DU = {100, 125, 150, 200, 250, 300, 400, 500, 600, 800, 1000};

    public static final class Scene {
        public final long seed;
        public final InputModel input;
        public final String description;
        Scene(long seed, InputModel input, String description) { this.seed = seed; this.input = input; this.description = description; }
    }

    public static Scene generate(long seed) {
        Random r = new Random(seed);
        double w = 150 + r.nextInt(251), h = 150 + r.nextInt(251);
        SyntheticInput in = new SyntheticInput();
        StringBuilder desc = new StringBuilder(String.format(java.util.Locale.ROOT, "seed=%d area=%.0fx%.0f", seed, w, h));
        List<LineString> lines = new ArrayList<>();
        List<Polygon> buildings = new ArrayList<>();

        // existing networks: horizontal-ish polylines across the area, a source at the start, sometimes a chamber
        int nLines = 1 + r.nextInt(3);
        for (int i = 0; i < nLines; i++) {
            double y = 20 + r.nextDouble() * (h - 40);
            List<Double> xy = new ArrayList<>();
            xy.add(0.0); xy.add(y);
            boolean bend = r.nextBoolean();
            double midX = w / 2 + (r.nextDouble() - 0.5) * 60, midY = y + (r.nextDouble() - 0.5) * 80;
            if (bend) { xy.add(midX); xy.add(midY); }
            xy.add(w); xy.add(y + (r.nextDouble() - 0.5) * 40);
            double[] arr = xy.stream().mapToDouble(Double::doubleValue).toArray();
            int du = EXISTING_DU[r.nextInt(EXISTING_DU.length)];
            in.line(100 + i, du, arr);
            in.source("S" + i, arr[0], arr[1]);
            Coordinate[] cs = new Coordinate[arr.length / 2];
            for (int k = 0; k < cs.length; k++) cs[k] = SyntheticInput.c(arr[2 * k], arr[2 * k + 1]);
            lines.add(GeometryUtils.GF.createLineString(cs));
            if (bend && r.nextBoolean()) in.chamber(200 + i, midX, midY);           // chamber on an interior vertex (adjacency 2)
            if (r.nextInt(3) == 0) in.chamber(250 + i, arr[arr.length - 2], arr[arr.length - 1]); // chamber at the far end (adjacency 1)
            desc.append(String.format(java.util.Locale.ROOT, " line%d(DU%d,%s)", 100 + i, du, bend ? "bend" : "straight"));
        }

        // consumers with their own buildings (disjoint from lines and from each other)
        int nOks = 1 + r.nextInt(5);
        int placed = 0;
        for (int i = 0; i < nOks; i++) {
            for (int attempt = 0; attempt < 40; attempt++) {
                double bw = 10 + r.nextDouble() * 30, bh = 10 + r.nextDouble() * 30;
                double x0 = 5 + r.nextDouble() * (w - bw - 10), y0 = 5 + r.nextDouble() * (h - bh - 10);
                Polygon b = rect(x0, y0, x0 + bw, y0 + bh);
                boolean ok = true;
                for (LineString l : lines) if (l.distance(b) < 1.0) { ok = false; break; }
                for (Polygon o : buildings) if (o.distance(b) < 2.0) { ok = false; break; }
                if (!ok) continue;
                buildings.add(b);
                double px = x0 + 1 + r.nextDouble() * (bw - 2), py = y0 + 1 + r.nextDouble() * (bh - 2);
                double flow = Math.round((1 + r.nextDouble() * 199) * 10) / 10.0;
                Object id = r.nextBoolean() ? (Object) ("OKS-" + (char) ('A' + i)) : (Object) (1000 + i);
                in.rect(10 + i, "oks", x0, y0, x0 + bw, y0 + bh).point(id, px, py, flow);
                desc.append(String.format(java.util.Locale.ROOT, " oks%s(%.1f t/h)", id, flow));
                placed++;
                break;
            }
        }
        if (placed == 0) { // always at least one consumer
            double x0 = w / 2 - 8, y0 = 5;
            in.rect(10, "oks", x0, y0, x0 + 16, y0 + 12).point("OKS-Z", x0 + 8, y0 + 6, 10.0);
            desc.append(" oksZ(10 t/h)");
        }

        // area obstacles of random types (may overlap anything: the solver has to cope, unconnected is allowed)
        int nObs = r.nextInt(5);
        for (int i = 0; i < nObs; i++) {
            double ow = 10 + r.nextDouble() * 70, oh = 10 + r.nextDouble() * 70;
            double x0 = r.nextDouble() * (w - ow), y0 = r.nextDouble() * (h - oh);
            String type = AREA_TYPES[r.nextInt(AREA_TYPES.length)];
            in.rect(50 + i, type, x0, y0, x0 + ow, y0 + oh);
            desc.append(" ").append(type);
        }

        // utility polylines crossing the area
        int nUtil = r.nextInt(3);
        for (int i = 0; i < nUtil; i++) {
            String type = r.nextBoolean() ? "gas_pipeline" : "power_cable";
            if (r.nextBoolean()) {
                double y = 10 + r.nextDouble() * (h - 20);
                in.polyline(70 + i, type, 0, y, w / 2, y + (r.nextDouble() - 0.5) * 30, w, y);
            } else {
                double x = 10 + r.nextDouble() * (w - 20);
                in.polyline(70 + i, type, x, 0, x + (r.nextDouble() - 0.5) * 30, h / 2, x, h);
            }
            desc.append(" ").append(type);
        }
        return new Scene(seed, in.build(), desc.toString());
    }

    private static Polygon rect(double x0, double y0, double x1, double y1) {
        return GeometryUtils.GF.createPolygon(new Coordinate[]{SyntheticInput.c(x0, y0), SyntheticInput.c(x1, y0), SyntheticInput.c(x1, y1), SyntheticInput.c(x0, y1), SyntheticInput.c(x0, y0)});
    }

    static Envelope envelope(Geometry g) { return g.getEnvelopeInternal(); }

    static int diameterCount() { return DiameterTable.rows().size(); }
}
