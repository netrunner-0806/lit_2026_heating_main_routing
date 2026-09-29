package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeometryUtils;

import java.util.ArrayList;
import java.util.List;

/** Geometric facts about the existing network: chamber adjacency, points on lines, nearby chambers. */
public final class ExistingNetworkIndex {

    /** Tolerance for "a line ends in / passes through a chamber", metres. */
    public static final double NODE_TOL_M = 0.5;

    private final InputModel input;
    private final STRtree lineIndex = new STRtree();
    private final STRtree chamberIndex = new STRtree();

    public ExistingNetworkIndex(InputModel input) {
        this.input = input;
        for (ExistingNetworkLine l : input.networkLines()) lineIndex.insert(l.line().getEnvelopeInternal(), l);
        for (ExistingChamber c : input.chambers()) chamberIndex.insert(new Envelope(c.coordinate()), c);
        lineIndex.build();
        chamberIndex.build();
    }

    public InputModel input() { return input; }

    /** Adjacency slots occupied by existing lines at the point: an ending line = 1, a line passing through = 2. */
    public int existingAdjacencyAt(Coordinate p) {
        Envelope env = new Envelope(p);
        env.expandBy(NODE_TOL_M);
        @SuppressWarnings("unchecked")
        List<ExistingNetworkLine> lines = lineIndex.query(env);
        int adj = 0;
        for (ExistingNetworkLine l : lines) {
            LineString ls = l.line();
            Coordinate s = ls.getCoordinateN(0), e = ls.getCoordinateN(ls.getNumPoints() - 1);
            boolean atStart = s.distance(p) <= NODE_TOL_M, atEnd = e.distance(p) <= NODE_TOL_M;
            if (atStart || atEnd) adj += (atStart && atEnd) ? 2 : 1;
            else if (ls.distance(GeometryUtils.point(p)) <= NODE_TOL_M) adj += 2;
        }
        return adj;
    }

    public int existingAdjacency(ExistingChamber c) { return existingAdjacencyAt(c.coordinate()); }

    /** Existing chambers within the distance of the point, nearest first. */
    public List<ExistingChamber> chambersNear(Coordinate p, double dist) {
        Envelope env = new Envelope(p);
        env.expandBy(dist);
        @SuppressWarnings("unchecked")
        List<ExistingChamber> cs = chamberIndex.query(env);
        List<ExistingChamber> out = new ArrayList<>();
        for (ExistingChamber c : cs) if (c.coordinate().distance(p) <= dist) out.add(c);
        out.sort((a, b) -> Double.compare(a.coordinate().distance(p), b.coordinate().distance(p)));
        return out;
    }

    /** Existing chambers within the 10 m snap distance of the point that still have a spare adjacency slot. */
    public List<ExistingChamber> usableChambersNear(Coordinate p) {
        List<ExistingChamber> out = new ArrayList<>();
        for (ExistingChamber c : chambersNear(p, Constants.EXISTING_CHAMBER_SNAP_DISTANCE_M))
            if (existingAdjacency(c) + 1 <= Constants.MAX_CHAMBER_DEGREE) out.add(c);
        return out;
    }

    public List<ExistingNetworkLine> linesNear(Coordinate p, double dist) {
        Envelope env = new Envelope(p);
        env.expandBy(dist);
        @SuppressWarnings("unchecked")
        List<ExistingNetworkLine> ls = lineIndex.query(env);
        List<ExistingNetworkLine> out = new ArrayList<>();
        for (ExistingNetworkLine l : ls) if (l.line().distance(GeometryUtils.point(p)) <= dist) out.add(l);
        return out;
    }

    /** Points along a line: all vertices plus interpolated points every {@code step} metres. */
    public static List<Coordinate> samplePoints(LineString line, double step) {
        List<Coordinate> out = new ArrayList<>();
        Coordinate[] cs = line.getCoordinates();
        for (int i = 0; i < cs.length - 1; i++) {
            Coordinate a = cs[i], b = cs[i + 1];
            out.add(a);
            double seg = a.distance(b);
            int n = (int) Math.floor(seg / step);
            for (int k = 1; k <= n; k++) {
                double t = k * step;
                if (seg - t < step * 0.3) break;
                double f = t / seg;
                out.add(new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f));
            }
        }
        out.add(cs[cs.length - 1]);
        return out;
    }
}
