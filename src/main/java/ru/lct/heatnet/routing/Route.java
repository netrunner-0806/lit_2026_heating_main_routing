package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.restrictions.SegmentCheck;
import ru.lct.heatnet.restrictions.SpecialPassage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A validated polyline route with per-segment special passages. */
public final class Route {
    private final List<Coordinate> coords;
    private final List<SegmentCheck> segments;
    private final double length;
    private final double effectiveLength;

    public Route(List<Coordinate> coords, List<SegmentCheck> segments) {
        this.coords = Collections.unmodifiableList(new ArrayList<>(coords));
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        double l = 0, e = 0;
        for (SegmentCheck s : segments) { l += s.length(); e += s.effectiveLength(); }
        this.length = l;
        this.effectiveLength = e;
    }

    public List<Coordinate> coords() { return coords; }
    public List<SegmentCheck> segments() { return segments; }
    public double length() { return length; }
    public double effectiveLength() { return effectiveLength; }
    public Coordinate start() { return coords.get(0); }
    public Coordinate end() { return coords.get(coords.size() - 1); }

    /** Passages with intervals re-based to the whole polyline length index. */
    public List<SpecialPassage> passagesAlongRoute() {
        List<SpecialPassage> out = new ArrayList<>();
        double offset = 0;
        for (SegmentCheck s : segments) {
            for (SpecialPassage p : s.passages()) out.add(p.shifted(offset));
            offset += s.length();
        }
        return out;
    }

    public Route reversed() {
        List<Coordinate> rc = new ArrayList<>(coords);
        Collections.reverse(rc);
        List<SegmentCheck> rs = new ArrayList<>();
        for (int i = segments.size() - 1; i >= 0; i--) {
            SegmentCheck s = segments.get(i);
            List<SpecialPassage> ps = new ArrayList<>();
            for (SpecialPassage p : s.passages())
                ps.add(new SpecialPassage(p.obstacle(), s.length() - p.to(), s.length() - p.from(), p.kSpec(), p.crossingPoint(), p.crossingAngleDeg()));
            rs.add(SegmentCheck.valid(s.length(), ps));
        }
        return new Route(rc, rs);
    }
}
