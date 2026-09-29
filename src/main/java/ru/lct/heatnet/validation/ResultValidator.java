package ru.lct.heatnet.validation;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.config.ChamberCostTable;
import ru.lct.heatnet.config.DepthRules;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.config.DiameterSpec;
import ru.lct.heatnet.config.DiameterTable;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.geo.GeometryUtils;
import ru.lct.heatnet.hydraulic.HydraulicCalculator;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.ExistingNetworkIndex;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;
import ru.lct.heatnet.validation.VariantModel.Chain;
import ru.lct.heatnet.validation.VariantModel.Line;
import ru.lct.heatnet.validation.VariantModel.Node;
import ru.lct.heatnet.validation.VariantModel.NodeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent final validator of an output GeoJSON against the input and the rules of the technical annex.
 * It rebuilds the topology from the output features only (ids, coordinates, attributes), then checks schema,
 * geometry, references, tree structure, degrees, turns, intersections, spatial restrictions (via
 * {@link SpatialValidator}), flows, diameters, max length, costs, summaries and ranks.
 */
public final class ResultValidator {

    private static final Set<String> OUTPUT_TYPES = new HashSet<>(Arrays.asList("heat_network", "heat_chamber", "technical_node", "variant_summary"));
    private static final double NODE_MATCH_TOL_M = ru.lct.heatnet.geo.GeometryTolerance.NODE_SNAP_M;   // line endpoints must coincide with node coordinates
    private static final double ON_LINE_TOL_M = ru.lct.heatnet.geo.GeometryTolerance.ON_LINE_M;       // new chamber located on an existing line

    private final InputModel input;
    private final ExistingNetworkIndex netIndex;
    private final CrsTransformer crs = CrsTransformer.get();

    public ResultValidator(InputModel input) {
        this.input = input;
        this.netIndex = new ExistingNetworkIndex(input);
    }

    public ValidationReport validate(List<OutputFeature> features) {
        ValidationReport rep = new ValidationReport();
        Map<String, VariantModel> variants = new LinkedHashMap<>();
        // ---- schema & grouping ----
        Set<String> seenIds = new HashSet<>();
        for (OutputFeature f : features) {
            String type = f.objectType();
            Object vidObj = f.prop("variant_id");
            String vid = vidObj == null ? null : String.valueOf(vidObj);
            String fid = f.prop("id") == null ? null : String.valueOf(f.prop("id"));
            rep.counted("schema");
            if (type == null || !OUTPUT_TYPES.contains(type)) { rep.error("BAD_OBJECT_TYPE", "object_type '" + type + "' is not allowed in the output", vid, fid); continue; }
            if (fid == null) { rep.error("MISSING_ID", "feature without id", vid, null); continue; }
            if (vid == null) { rep.error("MISSING_VARIANT_ID", "feature without variant_id", null, fid); continue; }
            if (!seenIds.add(vid + "|" + fid)) rep.error("DUPLICATE_ID", "duplicate id in variant", vid, fid);
            VariantModel vm = variants.computeIfAbsent(vid, VariantModel::new);
            switch (type) {
                case "heat_network": readLine(f, vm, rep); break;
                case "heat_chamber": readChamber(f, vm, rep); break;
                case "technical_node": readTechnicalNode(f, vm, rep); break;
                default:
                    if (vm.summary != null) rep.error("DUPLICATE_SUMMARY", "more than one variant_summary", vid, fid);
                    vm.summary = f;
                    if (f.geomType() != OutputFeature.GeomType.NONE) rep.error("SUMMARY_GEOMETRY", "variant_summary must have geometry = null", vid, fid);
            }
        }
        if (variants.isEmpty()) rep.error("EMPTY_OUTPUT", "no output features", null, null);
        for (VariantModel vm : variants.values()) validateVariant(vm, rep);
        validateRanks(variants, rep);
        return rep;
    }

    // ------------------------------------------------------------------------------------------------------------
    // reading features
    // ------------------------------------------------------------------------------------------------------------

    private void readLine(OutputFeature f, VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId, fid = String.valueOf(f.prop("id"));
        for (String req : new String[]{"start_node_id", "end_node_id", "flow_tph", "diameter", "length", "laying_method", "cost"})
            if (f.prop(req) == null) rep.error("MISSING_ATTRIBUTE", "heat_network without '" + req + "'", vid, fid);
        if (!f.properties().containsKey("depth_start") || !f.properties().containsKey("depth_end"))
            rep.error("MISSING_ATTRIBUTE", "heat_network must carry depth_start/depth_end (null in 2D)", vid, fid);
        // depth attributes are checked by the depth validator according to the variant mode
        if (f.geomType() != OutputFeature.GeomType.LINESTRING) { rep.error("BAD_GEOMETRY", "heat_network must be a LineString", vid, fid); return; }
        List<double[]> wgs = f.line();
        if (wgs.size() < 2) { rep.error("BAD_GEOMETRY", "LineString with fewer than 2 points", vid, fid); return; }
        Coordinate[] cs = new Coordinate[wgs.size()];
        for (int i = 0; i < wgs.size(); i++) {
            double[] c = wgs.get(i);
            if (!finite(c) || Math.abs(c[0]) > 180 || Math.abs(c[1]) > 90) { rep.error("BAD_COORDINATE", "coordinate out of WGS84 range or not finite", vid, fid); return; }
            cs[i] = crs.toMetric(c[0], c[1]);
        }
        for (int i = 1; i < cs.length; i++) if (cs[i].distance(cs[i - 1]) < 1e-6) rep.error("REPEATED_POINT", "repeated consecutive coordinate", vid, fid);
        LineString ls = GeometryUtils.GF.createLineString(cs);
        if (!ls.isSimple()) rep.error("SELF_INTERSECTION", "LineString intersects itself", vid, fid);
        Line l = new Line(f, fid, ls);
        l.flow = num(f.prop("flow_tph"), Double.NaN);
        l.du = (int) num(f.prop("diameter"), -1);
        l.declaredLength = num(f.prop("length"), Double.NaN);
        l.declaredCost = num(f.prop("cost"), Double.NaN);
        l.depthStart = f.prop("depth_start") instanceof Number ? ((Number) f.prop("depth_start")).doubleValue() : null;
        l.depthEnd = f.prop("depth_end") instanceof Number ? ((Number) f.prop("depth_end")).doubleValue() : null;
        String lm = String.valueOf(f.prop("laying_method"));
        if (!Constants.LAYING_BASE.equals(lm) && !Constants.LAYING_SPECIAL.equals(lm)) rep.error("BAD_LAYING_METHOD", "laying_method must be base or special", vid, fid);
        l.special = Constants.LAYING_SPECIAL.equals(lm);
        if (Double.isNaN(l.flow) || l.flow <= 0) rep.error("BAD_FLOW", "flow_tph must be a positive number", vid, fid);
        if (!DiameterTable.byDu(l.du).isPresent()) rep.error("BAD_DIAMETER", "diameter " + l.du + " is not in Table 1", vid, fid);
        if (Double.isNaN(l.declaredLength) || Math.abs(l.declaredLength - l.length) > ru.lct.heatnet.geo.GeometryTolerance.LENGTH_DECLARED_TOL_M)
            rep.error("BAD_LENGTH", String.format(java.util.Locale.ROOT, "length %.3f differs from geometric length %.3f m (EPSG:32637)", l.declaredLength, l.length), vid, fid);
        if (Double.isNaN(l.declaredCost) || l.declaredCost < 0) rep.error("BAD_COST", "cost must be a non-negative number", vid, fid);
        vm.lines.add(l);
    }

    private void readChamber(OutputFeature f, VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId, fid = String.valueOf(f.prop("id"));
        if (f.prop("diameter") == null || f.prop("cost") == null) rep.error("MISSING_ATTRIBUTE", "heat_chamber needs diameter and cost", vid, fid);
        if (f.geomType() != OutputFeature.GeomType.POINT) { rep.error("BAD_GEOMETRY", "heat_chamber must be a Point", vid, fid); return; }
        vm.chamberFeatures.add(f);
        addNode(vm, rep, f, NodeKind.NEW_CHAMBER);
    }

    private void readTechnicalNode(OutputFeature f, VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId, fid = String.valueOf(f.prop("id"));
        if (f.geomType() != OutputFeature.GeomType.POINT) { rep.error("BAD_GEOMETRY", "technical_node must be a Point", vid, fid); return; }
        vm.technicalNodeFeatures.add(f);
        addNode(vm, rep, f, NodeKind.TECHNICAL_NODE);
    }

    private void addNode(VariantModel vm, ValidationReport rep, OutputFeature f, NodeKind kind) {
        JsonId id = idOf(f.prop("id"));
        double[] c = f.point();
        if (!finite(c)) { rep.error("BAD_COORDINATE", "node coordinate not finite", vm.variantId, id.text()); return; }
        Node n = new Node(id, kind, crs.toMetric(c[0], c[1]), c, f, null, null);
        if (vm.nodes.containsKey(id)) rep.error("DUPLICATE_NODE_ID", "node id " + id + " clashes with another node", vm.variantId, id.text());
        vm.nodes.put(id, n);
    }

    static JsonId idOf(Object v) {
        if (v instanceof Number) return JsonId.ofNumber((Number) v);
        return JsonId.ofString(String.valueOf(v));
    }

    private static boolean finite(double[] c) { return c != null && c.length >= 2 && !Double.isNaN(c[0]) && !Double.isNaN(c[1]) && !Double.isInfinite(c[0]) && !Double.isInfinite(c[1]); }

    private static double num(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) try { return Double.parseDouble((String) o); } catch (NumberFormatException e) { return def; }
        return def;
    }

    // ------------------------------------------------------------------------------------------------------------
    // per-variant checks
    // ------------------------------------------------------------------------------------------------------------

    private void validateVariant(VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId;
        if (vm.summary == null) rep.error("MISSING_SUMMARY", "variant has no variant_summary", vid, null);
        // input nodes available for references
        Map<JsonId, ConnectionPoint> cps = new HashMap<>();
        for (ConnectionPoint cp : input.connectionPoints()) cps.put(cp.id(), cp);
        Map<JsonId, ExistingChamber> chambers = new HashMap<>();
        for (ExistingChamber ch : input.chambers()) chambers.put(ch.id(), ch);

        // ---- references & endpoint coincidence ----
        for (Line l : vm.lines) {
            rep.counted("references");
            l.start = resolve(vm, cps, chambers, l.feature.prop("start_node_id"), rep, l);
            l.end = resolve(vm, cps, chambers, l.feature.prop("end_node_id"), rep, l);
            if (l.start == null || l.end == null) continue;
            if (l.start == l.end) rep.error("DEGENERATE_LINE", "start and end node are the same", vid, l.id);
            if (l.start.coord.distance(l.coords.get(0)) > NODE_MATCH_TOL_M)
                rep.error("ENDPOINT_MISMATCH", String.format(java.util.Locale.ROOT, "first coordinate is %.3f m from start node %s", l.start.coord.distance(l.coords.get(0)), l.start.id), vid, l.id);
            if (l.end.coord.distance(l.coords.get(l.coords.size() - 1)) > NODE_MATCH_TOL_M)
                rep.error("ENDPOINT_MISMATCH", String.format(java.util.Locale.ROOT, "last coordinate is %.3f m from end node %s", l.end.coord.distance(l.coords.get(l.coords.size() - 1)), l.end.id), vid, l.id);
            l.start.lines.add(l);
            l.end.lines.add(l);
        }
        // ---- node roles / degrees ----
        for (Node n : vm.nodes.values()) {
            rep.counted("degrees");
            switch (n.kind) {
                case OKS:
                    if (n.degree() != 1) rep.error("OKS_DEGREE", "connection point " + n.id + " has " + n.degree() + " new lines (expected exactly 1)", vid, n.id.text());
                    break;
                case TECHNICAL_NODE:
                    if (n.degree() != 2) rep.error("TECHNICAL_NODE_DEGREE", "technical_node must join exactly 2 lines, has " + n.degree(), vid, n.id.text());
                    break;
                case EXISTING_CHAMBER: {
                    n.existingAdjacency = netIndex.existingAdjacency(n.chamber);
                    n.root = true;
                    if (n.existingAdjacency + n.degree() > Constants.MAX_CHAMBER_DEGREE)
                        rep.error("CHAMBER_DEGREE", "existing chamber " + n.id + " would have " + (n.existingAdjacency + n.degree()) + " adjacent sections (> 4)", vid, n.id.text());
                    break;
                }
                case NEW_CHAMBER: {
                    List<ExistingNetworkLine> on = netIndex.linesNear(n.coord, ON_LINE_TOL_M);
                    if (!on.isEmpty()) {
                        n.root = true;
                        n.onExistingLine = on.get(0);
                        n.existingAdjacency = netIndex.existingAdjacencyAt(n.coord);
                        // 10 m rule
                        // + coordinate noise of the WGS84 round trip (~1e-9 m): the inclusive 10 m rule stays inclusive
                        for (ExistingChamber ch : netIndex.chambersNear(n.coord, Constants.EXISTING_CHAMBER_SNAP_DISTANCE_M + ru.lct.heatnet.geo.GeometryTolerance.COORDINATE_EQUALITY_M)) {
                            if (netIndex.existingAdjacency(ch) + n.degree() <= Constants.MAX_CHAMBER_DEGREE)
                                rep.error("EXISTING_CHAMBER_WITHIN_10M", "new chamber " + n.id + " is " + String.format(java.util.Locale.ROOT, "%.1f", ch.coordinate().distance(n.coord)) + " m from existing chamber " + ch.id() + " which could be used", vid, n.id.text());
                        }
                    } else if (n.degree() < 3) {
                        rep.warn("CHAMBER_WITHOUT_BRANCH", "new chamber " + n.id + " is not on the existing network and joins only " + n.degree() + " line(s)", vid, n.id.text());
                    }
                    if (n.existingAdjacency + n.degree() > Constants.MAX_CHAMBER_DEGREE)
                        rep.error("CHAMBER_DEGREE", "new chamber " + n.id + " would have " + (n.existingAdjacency + n.degree()) + " adjacent sections (> 4)", vid, n.id.text());
                    if (n.degree() == 0) rep.error("ORPHAN_CHAMBER", "new chamber " + n.id + " has no lines", vid, n.id.text());
                    break;
                }
            }
        }
        // ---- forest structure ----
        Map<Node, Integer> comp = components(vm);
        Map<Integer, List<Node>> byComp = new HashMap<>();
        for (Map.Entry<Node, Integer> e : comp.entrySet()) byComp.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        Set<Node> connectedOks = new HashSet<>();
        for (List<Node> members : byComp.values()) {
            int edges = 0;
            List<Node> roots = new ArrayList<>();
            for (Node n : members) { edges += n.degree(); if (n.root) roots.add(n); if (n.kind == NodeKind.OKS) connectedOks.add(n); }
            edges /= 2;
            rep.counted("forest");
            if (edges != members.size() - 1) rep.error("CYCLE", "connected part with " + members.size() + " nodes and " + edges + " lines is not a tree", vid, members.get(0).id.text());
            if (roots.isEmpty()) rep.error("NO_ROOT", "connected part (" + members.get(0).id + "...) is not attached to the existing network", vid, members.get(0).id.text());
            if (roots.size() > 1) rep.error("MULTIPLE_ROOTS", "connected part attaches to the existing network in " + roots.size() + " places (" + roots + ")", vid, members.get(0).id.text());
        }
        // ---- unconnected list consistency ----
        List<Object> unconnectedIds = new ArrayList<>();
        if (vm.summary != null) {
            Object u = vm.summary.prop("unconnected_oks_ids");
            if (u instanceof List) unconnectedIds.addAll((List<?>) u); else rep.error("BAD_UNCONNECTED", "unconnected_oks_ids must be an array", vid, null);
        }
        Set<JsonId> unconnected = new HashSet<>();
        for (Object o : unconnectedIds) {
            JsonId id = idOf(o);
            if (!cps.containsKey(id)) rep.error("BAD_UNCONNECTED", "unconnected id " + id + " is not an input oks_connection_point (type preserved?)", vid, null);
            unconnected.add(id);
        }
        double unconnectedFlow = 0;
        for (ConnectionPoint cp : input.connectionPoints()) {
            Node n = vm.nodes.get(cp.id());
            boolean connected = n != null && connectedOks.contains(n);
            boolean listed = unconnected.contains(cp.id());
            if (connected && listed) rep.error("UNCONNECTED_MISMATCH", "connection point " + cp.id() + " is connected but listed as unconnected", vid, null);
            if (!connected && !listed) rep.error("UNCONNECTED_MISMATCH", "connection point " + cp.id() + " is neither connected nor listed in unconnected_oks_ids", vid, null);
            if (listed) unconnectedFlow += cp.flowTph();
        }
        // ---- chains, turns, intersections, spatial ----
        buildChains(vm, rep);
        SpatialValidator spatial = new SpatialValidator(input, netIndex);
        spatial.validate(vm, rep);
        // ---- technical nodes must be justified by a change of parameters (needs the spatial pass: crossed objects) ----
        for (Node n : vm.nodes.values()) {
            if (n.kind != NodeKind.TECHNICAL_NODE || n.degree() != 2) continue;
            Line a = n.lines.get(0), b = n.lines.get(1);
            boolean profileChange = false;
            if (a.depthStart != null && a.depthEnd != null && b.depthStart != null && b.depthEnd != null) {
                double ga = (a.end == n ? a.depthEnd - a.depthStart : a.depthStart - a.depthEnd) / Math.max(1e-9, a.declaredLength);
                double gb = (b.start == n ? b.depthEnd - b.depthStart : b.depthStart - b.depthEnd) / Math.max(1e-9, b.declaredLength);
                double at = a.end == n ? a.depthEnd : a.depthStart;
                profileChange = Math.abs(ga - gb) > 1e-6 || Math.abs(at - DepthRules.NORMAL_DEPTH_M) < 1e-6;
            }
            // a special feature is one straight LineString per set of crossed objects: a change of the set justifies the node
            boolean crossedSetChange = !a.coveringObjects.equals(b.coveringObjects);
            if (a.special == b.special && a.du == b.du && Math.abs(a.flow - b.flow) < 1e-6 && !profileChange && !crossedSetChange)
                rep.warn("UNNECESSARY_TECHNICAL_NODE", "technical_node without a parameter change", vid, n.id.text());
        }
        // ---- hydraulics ----
        validateHydraulics(vm, rep);
        // ---- costs & summary ----
        validateCosts(vm, rep, unconnected, cps);
        // ---- depth mode ----
        boolean depthMode = vm.summary != null && "depth".equals(vm.summary.prop("mode"));
        if (depthMode) new DepthValidator(vm, rep).validate();
        else for (Line l : vm.lines) if (l.depthStart != null || l.depthEnd != null) rep.warn("DEPTH_NOT_NULL", "depth given although this is the planar mode", vid, l.id);
    }

    private Node resolve(VariantModel vm, Map<JsonId, ConnectionPoint> cps, Map<JsonId, ExistingChamber> chambers, Object ref, ValidationReport rep, Line l) {
        if (ref == null) return null;
        JsonId id = idOf(ref);
        Node n = vm.nodes.get(id);
        if (n != null) return n;
        ConnectionPoint cp = cps.get(id);
        if (cp != null) {
            n = new Node(id, NodeKind.OKS, cp.coordinate(), cp.wgs84(), null, cp, null);
            vm.nodes.put(id, n);
            return n;
        }
        ExistingChamber ch = chambers.get(id);
        if (ch != null) {
            n = new Node(id, NodeKind.EXISTING_CHAMBER, ch.coordinate(), ch.wgs84(), null, null, ch);
            vm.nodes.put(id, n);
            return n;
        }
        rep.error("UNRESOLVED_NODE", "node reference " + id + " matches no input oks_connection_point / heat_chamber and no output heat_chamber / technical_node of the variant", vm.variantId, l.id);
        return null;
    }

    private static Map<Node, Integer> components(VariantModel vm) {
        Map<Node, Integer> comp = new HashMap<>();
        int c = 0;
        for (Node n : vm.nodes.values()) {
            if (comp.containsKey(n) || n.degree() == 0) continue;
            Deque<Node> st = new ArrayDeque<>();
            st.push(n);
            comp.put(n, c);
            while (!st.isEmpty()) {
                Node x = st.pop();
                for (Line l : x.lines) {
                    Node y = l.other(x);
                    if (y != null && !comp.containsKey(y)) { comp.put(y, c); st.push(y); }
                }
            }
            c++;
        }
        return comp;
    }

    /** Builds chains (sections between chambers / connection points through technical nodes), oriented towards the root. */
    private void buildChains(VariantModel vm, ValidationReport rep) {
        // orient lines towards the root by BFS from each root
        Set<Line> visited = new HashSet<>();
        for (Node root : vm.nodes.values()) {
            if (!root.root) continue;
            Deque<Node> q = new ArrayDeque<>();
            q.add(root);
            Set<Node> seen = new HashSet<>();
            seen.add(root);
            while (!q.isEmpty()) {
                Node x = q.poll();
                for (Line l : x.lines) {
                    if (visited.contains(l)) continue;
                    visited.add(l);
                    l.parentNode = x;
                    l.childNode = l.other(x);
                    if (l.childNode != null && seen.add(l.childNode)) q.add(l.childNode);
                }
            }
        }
        // chains start at nodes that are not technical nodes, follow towards the root
        Set<Line> used = new HashSet<>();
        for (Node n : vm.nodes.values()) {
            if (n.kind == NodeKind.TECHNICAL_NODE) continue;
            for (Line first : n.lines) {
                if (first.childNode != n || used.contains(first)) continue;
                Chain ch = new Chain();
                ch.childEnd = n;
                Line l = first;
                Node cur = n;
                while (true) {
                    used.add(l);
                    ch.lines.add(l);
                    List<Coordinate> cs = new ArrayList<>(l.coords);
                    if (l.start != cur) Collections.reverse(cs);
                    double from = ch.length;
                    if (ch.coords.isEmpty()) ch.coords.addAll(cs); else ch.coords.addAll(cs.subList(1, cs.size()));
                    ch.length += l.length;
                    ch.lineIntervals.add(new double[]{from, ch.length});
                    Node next = l.parentNode;
                    if (next == null || next.kind != NodeKind.TECHNICAL_NODE) { ch.parentEnd = next; break; }
                    Line cont = null;
                    for (Line m : next.lines) if (m != l) cont = m;
                    if (cont == null || cont.childNode != next) { ch.parentEnd = next; break; }
                    cur = next;
                    l = cont;
                }
                vm.chains.add(ch);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // hydraulics
    // ------------------------------------------------------------------------------------------------------------

    private void validateHydraulics(VariantModel vm, ValidationReport rep) {
        String vid = vm.variantId;
        // downstream flow per chain from the reconstructed tree
        Map<Chain, Double> flow = new HashMap<>();
        Map<Node, Chain> chainOfChild = new HashMap<>();
        for (Chain c : vm.chains) chainOfChild.put(c.childEnd, c);
        for (Chain c : vm.chains) flow.put(c, subtreeFlow(c, vm));
        for (Chain c : vm.chains) {
            rep.counted("hydraulics");
            double q = flow.get(c);
            for (Line l : c.lines) {
                if (Math.abs(l.flow - q) > 1e-3) rep.error("FLOW_MISMATCH", String.format(java.util.Locale.ROOT, "flow_tph %.3f but downstream consumers sum to %.3f t/h", l.flow, q), vid, l.id);
                DiameterSpec s = DiameterTable.byDu(l.du).orElse(null);
                if (s == null) continue;
                if (s.capacityTph() + 1e-9 < q) rep.error("CAPACITY", String.format(java.util.Locale.ROOT, "DU%d capacity %.1f t/h < flow %.3f", l.du, s.capacityTph(), q), vid, l.id);
                if (l.du != c.lines.get(0).du) rep.error("DU_CHANGE_IN_SECTION", "DU changes within a section of constant flow", vid, l.id);
            }
            // monotonic: parent chain DU >= this chain DU
            if (c.parentEnd != null && c.parentEnd.kind != NodeKind.OKS) {
                for (Line up : c.parentEnd.lines) {
                    if (up.childNode == c.parentEnd && up.du < c.lines.get(0).du)
                        rep.error("DU_DECREASES", "DU " + c.lines.get(0).du + " decreases to " + up.du + " towards the existing network at node " + c.parentEnd.id, vid, up.id);
                }
            }
        }
        // max continuous length per leaf-to-root path with runs of equal DU
        for (Node leaf : vm.nodes.values()) {
            if (leaf.kind != NodeKind.OKS) continue;
            Chain c = chainOfChild.get(leaf);
            double run = 0;
            int prevDu = -1;
            int guard = 0;
            while (c != null && guard++ < 10000) {
                int du = c.lines.get(0).du;
                if (du != prevDu) { run = 0; prevDu = du; }
                run += c.length;
                DiameterSpec s = DiameterTable.byDu(du).orElse(null);
                if (s != null && run > s.maxLengthM() + 1e-6)
                    rep.error("MAX_LENGTH", String.format(java.util.Locale.ROOT, "continuous run of DU%d on the path from %s reaches %.1f m > %.0f m", du, leaf.id, run, s.maxLengthM()), vid, c.lines.get(c.lines.size() - 1).id);
                c = c.parentEnd == null ? null : chainOfChild.get(c.parentEnd);
            }
        }
        // minimality: compare with the reference calculation on the reconstructed tree
        NewNetwork ref = rebuildNetwork(vm);
        if (ref == null) rep.warn("REFERENCE_NETWORK_UNAVAILABLE", "the tree could not be rebuilt for the DU minimality check (DU_OVERSIZED not evaluated)", vid, null);
        if (ref != null) {
            HydraulicCalculator.Result h = HydraulicCalculator.compute(ref);
            if (h.feasible()) {
                Map<Integer, Chain> byLink = refChainMap;
                for (NetLink l : ref.links()) {
                    Chain c = byLink.get(l.id());
                    if (c == null) continue;
                    if (c.lines.get(0).du > l.du())
                        rep.error("DU_OVERSIZED", "DU" + c.lines.get(0).du + " on section from " + c.childEnd.id + " but the minimal DU satisfying flow, max length and monotonicity is DU" + l.du(), vid, c.lines.get(0).id);
                }
            }
        }
    }

    private double subtreeFlow(Chain c, VariantModel vm) {
        double q = 0;
        Deque<Node> st = new ArrayDeque<>();
        st.push(c.childEnd);
        Set<Node> seen = new HashSet<>();
        while (!st.isEmpty()) {
            Node n = st.pop();
            if (!seen.add(n)) continue;
            if (n.kind == NodeKind.OKS) q += n.cp.flowTph();
            for (Line l : n.lines) if (l.parentNode == n && l.childNode != null) st.push(l.childNode);
        }
        return q;
    }

    private Map<Integer, Chain> refChainMap = new HashMap<>();

    /** Rebuilds a NewNetwork from the chains for the reference hydraulic calculation. */
    private NewNetwork rebuildNetwork(VariantModel vm) {
        try {
            NewNetwork net = new NewNetwork();
            Map<Node, NetNode> map = new HashMap<>();
            refChainMap = new HashMap<>();
            for (Node n : vm.nodes.values()) {
                if (n.degree() == 0 || n.kind == NodeKind.TECHNICAL_NODE) continue;
                switch (n.kind) {
                    case OKS: map.put(n, net.addConnectionNode(n.cp)); break;
                    case EXISTING_CHAMBER: map.put(n, net.rootAtExistingChamber(n.chamber, n.existingAdjacency)); break;
                    default:
                        map.put(n, n.root ? net.rootOnExistingLine(n.onExistingLine, n.coord, n.existingAdjacency) : net.addJunctionNode(n.coord));
                }
            }
            for (Chain c : vm.chains) {
                NetNode child = map.get(c.childEnd), parent = map.get(c.parentEnd);
                if (child == null || parent == null) return null;
                NetLink l = net.addLink(child, parent, c.coords, Collections.emptyList());
                refChainMap.put(l.id(), c);
            }
            return net;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // costs & summary
    // ------------------------------------------------------------------------------------------------------------

    private void validateCosts(VariantModel vm, ValidationReport rep, Set<JsonId> unconnected, Map<JsonId, ConnectionPoint> cps) {
        String vid = vm.variantId;
        double lineCost = 0, length = 0, chamberCost = 0, tieInCost = 0;
        int tieIns = 0;
        for (Line l : vm.lines) {
            rep.counted("cost");
            DiameterSpec s = DiameterTable.byDu(l.du).orElse(null);
            if (s == null) continue;
            double k = l.special ? l.kSpecExpected : 1.0;
            boolean depthMode = vm.summary != null && "depth".equals(vm.summary.prop("mode"));
            double kDepth = depthMode && l.depthStart != null && l.depthEnd != null ? ru.lct.heatnet.depth.DepthCostCalculator.kDepth(l.depthStart, l.depthEnd) : Constants.K_DEPTH_2D;
            double expected = l.declaredLength * s.costPerMeter() * kDepth * k;
            if (Math.abs(expected - l.declaredCost) > 1.0 + expected * 1e-6)
                rep.error("LINE_COST", String.format(java.util.Locale.ROOT, "cost %.0f but L*c(DU)*Kdepth*Kspec = %.3f * %.0f * %.4f * %.2f = %.0f", l.declaredCost, l.declaredLength, s.costPerMeter(), kDepth, k, expected), vid, l.id);
            lineCost += l.declaredCost;
            length += l.declaredLength;
        }
        for (Node n : vm.nodes.values()) {
            if (n.kind == NodeKind.NEW_CHAMBER) {
                int maxDu = 0;
                for (Line l : n.lines) maxDu = Math.max(maxDu, l.du);
                if (n.onExistingLine != null) maxDu = Math.max(maxDu, n.onExistingLine.diameter());
                double expected = ChamberCostTable.newChamberCost(maxDu);
                double declared = num(n.feature.prop("cost"), Double.NaN);
                int declaredDu = (int) num(n.feature.prop("diameter"), -1);
                if (declaredDu != maxDu) rep.error("CHAMBER_DIAMETER", "chamber diameter " + declaredDu + " but the largest adjacent DU is " + maxDu, vid, n.id.text());
                if (Math.abs(declared - expected) > 1.0) rep.error("CHAMBER_COST", String.format(java.util.Locale.ROOT, "chamber cost %.0f but the scale gives %.0f for DU%d", declared, expected, maxDu), vid, n.id.text());
                chamberCost += declared;
            } else if (n.kind == NodeKind.EXISTING_CHAMBER) {
                tieIns += n.degree();
                tieInCost += n.degree() * ChamberCostTable.EXISTING_CHAMBER_TIE_IN_COST;
            }
        }
        double penalty = 0;
        for (JsonId id : unconnected) { ConnectionPoint cp = cps.get(id); if (cp != null) penalty += CostCalculator.penalty(cp.flowTph()); }
        if (vm.summary == null) return;
        OutputFeature s = vm.summary;
        String sid = String.valueOf(s.prop("id"));
        for (String req : new String[]{"rank", "construction_cost", "chamber_construction_cost", "existing_chamber_tie_in_count", "existing_chamber_tie_in_cost",
                "unconnected_penalty", "calculated_cost", "new_network_length", "score", "unconnected_oks_ids"})
            if (s.prop(req) == null) rep.error("MISSING_ATTRIBUTE", "variant_summary without '" + req + "'", vid, sid);
        double construction = lineCost + chamberCost + tieInCost;
        checkNum(rep, vid, sid, "construction_cost", num(s.prop("construction_cost"), Double.NaN), construction, 2.0);
        checkNum(rep, vid, sid, "chamber_construction_cost", num(s.prop("chamber_construction_cost"), Double.NaN), chamberCost, 1.0);
        checkNum(rep, vid, sid, "existing_chamber_tie_in_count", num(s.prop("existing_chamber_tie_in_count"), Double.NaN), tieIns, 0.0);
        checkNum(rep, vid, sid, "existing_chamber_tie_in_cost", num(s.prop("existing_chamber_tie_in_cost"), Double.NaN), tieInCost, 1.0);
        checkNum(rep, vid, sid, "unconnected_penalty", num(s.prop("unconnected_penalty"), Double.NaN), penalty, 1.0);
        double calculated = construction + penalty;
        checkNum(rep, vid, sid, "calculated_cost", num(s.prop("calculated_cost"), Double.NaN), calculated, 3.0);
        checkNum(rep, vid, sid, "new_network_length", num(s.prop("new_network_length"), Double.NaN), length, 0.05);
        double score = CostCalculator.score(num(s.prop("calculated_cost"), calculated), num(s.prop("new_network_length"), length));
        checkNum(rep, vid, sid, "score", num(s.prop("score"), Double.NaN), score, ru.lct.heatnet.geo.GeometryTolerance.SCORE_TOL);
        if (!(s.prop("rank") instanceof Number) || ((Number) s.prop("rank")).intValue() < 1) rep.error("BAD_RANK", "rank must be a positive integer", vid, sid);
    }

    private static void checkNum(ValidationReport rep, String vid, String fid, String name, double declared, double expected, double tol) {
        if (Double.isNaN(declared) || Math.abs(declared - expected) > tol)
            rep.error("SUMMARY_" + name.toUpperCase(), String.format(java.util.Locale.ROOT, "%s = %s but recomputed value is %.4f", name, Double.isNaN(declared) ? "missing" : String.format(java.util.Locale.ROOT, "%.4f", declared), expected), vid, fid);
    }

    private void validateRanks(Map<String, VariantModel> variants, ValidationReport rep) {
        List<VariantModel> vs = new ArrayList<>();
        for (VariantModel vm : variants.values()) if (vm.summary != null && vm.summary.prop("rank") instanceof Number && vm.summary.prop("score") instanceof Number) vs.add(vm);
        vs.sort((a, b) -> Double.compare(num(a.summary.prop("score"), 0), num(b.summary.prop("score"), 0)));
        Set<Integer> ranks = new HashSet<>();
        for (int i = 0; i < vs.size(); i++) {
            int rank = ((Number) vs.get(i).summary.prop("rank")).intValue();
            if (!ranks.add(rank)) rep.error("DUPLICATE_RANK", "rank " + rank + " used twice", vs.get(i).variantId, null);
            if (rank != i + 1) rep.error("RANK_ORDER", "rank " + rank + " is inconsistent with the score ordering (expected " + (i + 1) + ")", vs.get(i).variantId, null);
        }
    }
}
