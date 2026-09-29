package ru.lct.heatnet.oracle;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.output.OutputFeature;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent reading of a finished output (the annex section 7 feature list) into a checkable model: nodes, lines,
 * tree orientation from the attachment points, consumer leaves per line and consumer paths. Uses only the output
 * properties defined by the annex plus the input model for flows and existing objects. No production solver,
 * cost, hydraulic or validator class is involved (the CRS transform is a pure library conversion).
 */
final class ReferenceOutputOracle {

    private ReferenceOutputOracle() {}

    enum Kind { OKS, EXISTING_CHAMBER, NEW_CHAMBER, TECHNICAL_NODE }

    static final class Node {
        final String key;
        final Object id;
        final Kind kind;
        double[] wgs;
        Coordinate metric;
        final List<Line> lines = new ArrayList<>();
        String chamberKind;
        Object existingLineId; // tie-in chambers: the existing heat_network section the chamber is created on
        Integer diameter;
        Long cost;
        double flowTph; // OKS only
        boolean root;
        Line toRoot;    // line towards the attachment point (null for roots)

        Node(String key, Object id, Kind kind) { this.key = key; this.id = id; this.kind = kind; }

        @Override public String toString() { return kind + " " + id; }
    }

    static final class Line {
        String id;
        Object startId, endId;
        Node start, end;
        double flow;
        int du;
        double length;
        boolean special;
        Double depthStart, depthEnd;
        long cost;
        double kSpec, kDepth;
        Double slope;
        List<String> crossed = new ArrayList<>();
        List<Map<String, Object>> crossings = new ArrayList<>();
        List<Coordinate> metric = new ArrayList<>();
        Object linkId;
        int order;          // position in the feature list (pieces of one link are emitted consecutively)
        Node parentSide, childSide;
        final Set<String> leaves = new LinkedHashSet<>();

        Node other(Node n) { return n == start ? end : start; }

        Double depthAt(Node n) { return n == start ? depthStart : depthEnd; }

        @Override public String toString() { return id; }
    }

    static final class Variant {
        final String vid;
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final List<Line> lines = new ArrayList<>();
        final List<Node> chambers = new ArrayList<>();
        final List<OutputFeature> summaries = new ArrayList<>();
        final List<Node> roots = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        Variant(String vid) { this.vid = vid; }

        OutputFeature summary() { return summaries.isEmpty() ? null : summaries.get(0); }

        List<Node> oks() {
            List<Node> out = new ArrayList<>();
            for (Node n : nodes.values()) if (n.kind == Kind.OKS && !n.lines.isEmpty()) out.add(n);
            return out;
        }

        /** Lines from the consumer to its attachment point. */
        List<Line> pathToRoot(Node oks) {
            List<Line> out = new ArrayList<>();
            Node n = oks;
            int guard = 0;
            while (n != null && !n.root && n.toRoot != null && guard++ < 100_000) {
                out.add(n.toRoot);
                n = n.toRoot.other(n);
            }
            return out;
        }
    }

    /** Type-preserving id key: numeric 123 and string "123" are different identifiers. */
    static String key(Object id) {
        if (id == null) return "null";
        if (id instanceof Number) {
            BigDecimal b = new BigDecimal(id.toString());
            return "n:" + b.stripTrailingZeros().toPlainString();
        }
        return "s:" + id;
    }

    static double num(Object o) { return o == null ? Double.NaN : ((Number) o).doubleValue(); }

    static Map<String, Variant> parse(List<OutputFeature> features, InputModel input) {
        Map<String, Variant> out = new LinkedHashMap<>();
        Map<String, ConnectionPoint> cps = new HashMap<>();
        for (ConnectionPoint cp : input.connectionPoints()) cps.put(key(cp.id().toJavaValue()), cp);
        Map<String, ExistingChamber> chambers = new HashMap<>();
        for (ExistingChamber c : input.chambers()) chambers.put(key(c.id().toJavaValue()), c);
        CrsTransformer crs = CrsTransformer.get();
        int order = 0;
        for (OutputFeature f : features) {
            String vid = String.valueOf(f.prop("variant_id"));
            Variant v = out.computeIfAbsent(vid, Variant::new);
            String type = f.objectType();
            if ("variant_summary".equals(type)) { v.summaries.add(f); continue; }
            if ("heat_chamber".equals(type)) {
                Node n = new Node(key(f.prop("id")), f.prop("id"), Kind.NEW_CHAMBER);
                n.wgs = f.point();
                n.metric = n.wgs == null ? null : crs.toMetric(n.wgs[0], n.wgs[1]);
                n.chamberKind = (String) f.prop("chamber_kind");
                n.existingLineId = f.prop("existing_line_id");
                n.diameter = f.prop("diameter") == null ? null : ((Number) f.prop("diameter")).intValue();
                n.cost = f.prop("cost") == null ? null : ((Number) f.prop("cost")).longValue();
                if (v.nodes.put(n.key, n) != null) v.problems.add("duplicate node id " + f.prop("id"));
                v.chambers.add(n);
                continue;
            }
            if ("technical_node".equals(type)) {
                Node n = new Node(key(f.prop("id")), f.prop("id"), Kind.TECHNICAL_NODE);
                n.wgs = f.point();
                n.metric = n.wgs == null ? null : crs.toMetric(n.wgs[0], n.wgs[1]);
                if (v.nodes.put(n.key, n) != null) v.problems.add("duplicate node id " + f.prop("id"));
                continue;
            }
            if ("heat_network".equals(type)) {
                Line l = new Line();
                l.id = String.valueOf(f.prop("id"));
                l.order = order++;
                l.startId = f.prop("start_node_id");
                l.endId = f.prop("end_node_id");
                l.flow = num(f.prop("flow_tph"));
                l.du = (int) num(f.prop("diameter"));
                l.length = num(f.prop("length"));
                l.special = "special".equals(f.prop("laying_method"));
                l.depthStart = f.prop("depth_start") == null ? null : num(f.prop("depth_start"));
                l.depthEnd = f.prop("depth_end") == null ? null : num(f.prop("depth_end"));
                l.cost = f.prop("cost") == null ? Long.MIN_VALUE : ((Number) f.prop("cost")).longValue();
                l.kSpec = f.prop("k_spec") == null ? Double.NaN : num(f.prop("k_spec"));
                l.kDepth = f.prop("k_depth") == null ? Double.NaN : num(f.prop("k_depth"));
                l.slope = f.prop("slope") == null ? null : num(f.prop("slope"));
                if (f.prop("crossed_restrictions") instanceof List) for (Object o : (List<?>) f.prop("crossed_restrictions")) l.crossed.add(String.valueOf(o));
                if (f.prop("crossings") instanceof List) {
                    for (Object o : (List<?>) f.prop("crossings")) {
                        if (o instanceof Map) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
                            l.crossings.add(m);
                        } else v.problems.add(l.id + ": crossing entry is not an object: " + o);
                    }
                }
                l.linkId = f.prop("link_id");
                if (f.line() != null) for (double[] c : f.line()) l.metric.add(crs.toMetric(c[0], c[1]));
                v.lines.add(l);
                continue;
            }
            v.problems.add("unknown object_type " + type);
        }
        for (Variant v : out.values()) {
            // input nodes referenced by the lines
            for (Line l : v.lines) {
                for (Object ref : new Object[]{l.startId, l.endId}) {
                    String k = key(ref);
                    if (v.nodes.containsKey(k)) continue;
                    ConnectionPoint cp = cps.get(k);
                    ExistingChamber ch = chambers.get(k);
                    if (cp != null) {
                        Node n = new Node(k, ref, Kind.OKS);
                        n.wgs = cp.wgs84(); n.metric = cp.point().getCoordinate(); n.flowTph = cp.flowTph();
                        v.nodes.put(k, n);
                    } else if (ch != null) {
                        Node n = new Node(k, ref, Kind.EXISTING_CHAMBER);
                        n.wgs = ch.wgs84(); n.metric = ch.point().getCoordinate();
                        v.nodes.put(k, n);
                    }
                }
                l.start = v.nodes.get(key(l.startId));
                l.end = v.nodes.get(key(l.endId));
                if (l.start == null) v.problems.add(l.id + ": start_node_id " + l.startId + " does not exist");
                if (l.end == null) v.problems.add(l.id + ": end_node_id " + l.endId + " does not exist");
                if (l.start != null) l.start.lines.add(l);
                if (l.end != null) l.end.lines.add(l);
            }
            orient(v);
        }
        return out;
    }

    /** Roots = attachment points; BFS assigns the parent side of every line and the consumer leaves below it. */
    private static void orient(Variant v) {
        for (Node n : v.nodes.values()) {
            boolean root = (n.kind == Kind.EXISTING_CHAMBER && !n.lines.isEmpty())
                    || (n.kind == Kind.NEW_CHAMBER && "tie_in_on_existing_network".equals(n.chamberKind));
            if (root) { n.root = true; v.roots.add(n); }
        }
        Set<Line> seen = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>(v.roots);
        Set<Node> visited = new HashSet<>(v.roots);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            for (Line l : n.lines) {
                if (seen.contains(l)) continue;
                seen.add(l);
                Node other = l.other(n);
                if (other == null) continue;
                l.parentSide = n;
                l.childSide = other;
                if (other.root) { v.problems.add(l.id + ": connects two attachment points (" + n + ", " + other + ")"); continue; }
                if (!visited.add(other)) { v.problems.add(l.id + ": cycle or second parent at " + other); continue; }
                other.toRoot = l;
                queue.add(other);
            }
        }
        for (Line l : v.lines) if (!seen.contains(l)) v.problems.add(l.id + ": not reachable from any attachment point");
        // leaves: post-order from OKS nodes up to the root
        for (Node n : v.nodes.values()) {
            if (n.kind != Kind.OKS || n.lines.isEmpty()) continue;
            if (n.lines.size() != 1) v.problems.add("consumer " + n.id + " has " + n.lines.size() + " lines");
            Node cur = n;
            int guard = 0;
            while (cur != null && !cur.root && cur.toRoot != null && guard++ < 100_000) {
                cur.toRoot.leaves.add(n.key);
                cur = cur.toRoot.other(cur);
            }
        }
    }

    static ConnectionPoint connectionPoint(InputModel in, String key) {
        for (ConnectionPoint cp : in.connectionPoints()) if (key(cp.id().toJavaValue()).equals(key)) return cp;
        return null;
    }

    static ExistingNetworkLine existingLine(InputModel in, String idText) {
        for (ExistingNetworkLine l : in.networkLines()) if (l.id().text().equals(idText)) return l;
        return null;
    }

    /**
     * Existing sections adjacent to a point of the existing network (annex 2.1 / clarification 12): a line ending at
     * the point counts once, a line passing through it counts twice.
     */
    static int existingAdjacency(Coordinate c, InputModel in) {
        if (c == null) return 0;
        int n = 0;
        for (ExistingNetworkLine l : in.networkLines()) {
            Coordinate[] cs = l.line().getCoordinates();
            boolean atEnd = cs[0].distance(c) < 0.5 || cs[cs.length - 1].distance(c) < 0.5;
            if (atEnd) { n++; continue; }
            if (l.line().distance(CrsTransformer.METRIC_FACTORY.createPoint(c)) < 0.5) n += 2;
        }
        return n;
    }

    static double metricLength(List<Coordinate> cs) {
        double s = 0;
        for (int i = 1; i < cs.size(); i++) s += cs.get(i - 1).distance(cs.get(i));
        return s;
    }

    /** "type#id" label of a crossed object as written by the output. */
    static String typeOf(String label) { int i = label.indexOf('#'); return i < 0 ? label : label.substring(0, i); }

    static String idOf(String label) { int i = label.indexOf('#'); return i < 0 ? "" : label.substring(i + 1); }
}
