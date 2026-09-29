package ru.lct.heatnet.output;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.config.Constants;
import ru.lct.heatnet.cost.CostCalculator;
import ru.lct.heatnet.cost.CostSummary;
import ru.lct.heatnet.cost.LinkPiece;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.geo.CrsTransformer;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a built variant into output features (section 7 of the annex): heat_network pieces split at technical
 * nodes (special passage boundaries), new heat_chamber points, technical_node points and one variant_summary.
 * Line endpoints reuse the exact node coordinates (input WGS84 for input nodes, one shared transform for new nodes).
 */
public final class OutputBuilder {

    /** 12 decimals (~0.1 µm): with 9 decimals (~0.1 mm) an exact 90° junction can read as 90.009° after the WGS84 round trip. */
    private static final int COORD_DECIMALS = 12;

    private final CrsTransformer crs = CrsTransformer.get();

    /** Node registry: metric coordinate key -> (id, wgs84). */
    private static final class NodeRef {
        final JsonId id;
        final double[] wgs;
        NodeRef(JsonId id, double[] wgs) { this.id = id; this.wgs = wgs; }
    }

    public List<OutputFeature> build(VariantResult variant) {
        String vid = variant.variantId();
        NewNetwork net = variant.network();
        List<OutputFeature> out = new ArrayList<>();
        Map<String, NodeRef> nodes = new HashMap<>();
        int chamberSeq = 0, tnSeq = 0, netSeq = 0;
        List<OutputFeature> chambers = new ArrayList<>();
        List<OutputFeature> tns = new ArrayList<>();
        List<OutputFeature> lines = new ArrayList<>();

        for (NetNode n : net.nodes()) {
            switch (n.kind()) {
                case CONNECTION_POINT:
                    nodes.put(key(n.coord()), new NodeRef(n.connectionPoint().id(), n.connectionPoint().wgs84()));
                    break;
                case EXISTING_CHAMBER:
                    nodes.put(key(n.coord()), new NodeRef(n.existingChamber().id(), n.existingChamber().wgs84()));
                    break;
                default: {
                    JsonId id = JsonId.ofString(vid + "_chamber_" + (++chamberSeq));
                    double[] wgs = round(crs.toWgs84(n.coord().x, n.coord().y));
                    nodes.put(key(n.coord()), new NodeRef(id, wgs));
                    chambers.add(OutputFeature.point(wgs)
                            .prop("id", id.toJavaValue())
                            .prop("object_type", "heat_chamber")
                            .prop("variant_id", vid)
                            .prop("diameter", n.maxAdjacentDu())
                            .prop("cost", money(CostCalculator.newChamberCost(n)))
                            .prop("chamber_kind", n.kind() == NetNode.Kind.NEW_CHAMBER_ON_EXISTING ? "tie_in_on_existing_network" : "junction")
                            .prop("existing_line_id", n.existingLine() == null ? null : n.existingLine().id().toJavaValue()));
                }
            }
        }

        for (NetLink link : net.links()) {
            List<LinkPiece> pieces = CostCalculator.pieces(link);
            for (LinkPiece piece : pieces) {
                List<Coordinate> cs = piece.coords();
                Coordinate first = cs.get(0), last = cs.get(cs.size() - 1);
                NodeRef start = nodes.get(key(first));
                if (start == null) {
                    JsonId id = JsonId.ofString(vid + "_tn_" + (++tnSeq));
                    double[] wgs = round(crs.toWgs84(first.x, first.y));
                    start = new NodeRef(id, wgs);
                    nodes.put(key(first), start);
                    tns.add(OutputFeature.point(wgs).prop("id", id.toJavaValue()).prop("object_type", "technical_node").prop("variant_id", vid));
                }
                NodeRef end = nodes.get(key(last));
                if (end == null) {
                    JsonId id = JsonId.ofString(vid + "_tn_" + (++tnSeq));
                    double[] wgs = round(crs.toWgs84(last.x, last.y));
                    end = new NodeRef(id, wgs);
                    nodes.put(key(last), end);
                    tns.add(OutputFeature.point(wgs).prop("id", id.toJavaValue()).prop("object_type", "technical_node").prop("variant_id", vid));
                }
                List<double[]> coords = new ArrayList<>();
                coords.add(start.wgs);
                for (int i = 1; i < cs.size() - 1; i++) coords.add(round(crs.toWgs84(cs.get(i).x, cs.get(i).y)));
                coords.add(end.wgs);
                List<Object> crossed = new ArrayList<>();
                for (ru.lct.heatnet.restrictions.Obstacle o : piece.crossed()) crossed.add(o.describe());
                boolean depth = piece.hasDepth();
                lines.add(OutputFeature.line(coords)
                        .prop("id", vid + "_net_" + (++netSeq))
                        .prop("object_type", "heat_network")
                        .prop("variant_id", vid)
                        .prop("start_node_id", start.id.toJavaValue())
                        .prop("end_node_id", end.id.toJavaValue())
                        .prop("flow_tph", round(link.flow(), 3))
                        .prop("diameter", link.du())
                        .prop("length", round(piece.length(), 3))
                        .prop("laying_method", piece.special() ? Constants.LAYING_SPECIAL : Constants.LAYING_BASE)
                        .prop("depth_start", depth ? piece.depthStart() : null)
                        .prop("depth_end", depth ? piece.depthEnd() : null)
                        .prop("cost", money(piece.cost()))
                        .prop("k_spec", piece.special() ? piece.kSpec() : 1.0)
                        .prop("k_depth", depth ? round(piece.kDepth(), 4) : 1.0)
                        .prop("slope", depth ? round(piece.slope(), 4) : null)
                        .prop("crossed_restrictions", crossed)
                        .prop("crossings", depth && piece.special() ? crossingDetails(link, piece) : null)
                        .prop("link_id", link.id()));
            }
        }

        CostSummary s = variant.summary();
        boolean depthMode = variant.depthMode(); // mode of the calculation, not derived from the links (a variant may have none)
        ru.lct.heatnet.depth.DepthMetrics dm = depthMode ? ru.lct.heatnet.depth.DepthMetrics.of(net) : null;
        List<Object> unconnectedIds = new ArrayList<>();
        for (ConnectionPoint cp : variant.unconnected().keySet()) unconnectedIds.add(cp.id().toJavaValue());
        OutputFeature summary = OutputFeature.noGeometry()
                .prop("id", vid + "_summary")
                .prop("object_type", "variant_summary")
                .prop("variant_id", vid)
                .prop("rank", variant.rank())
                .prop("construction_cost", money(s.constructionCost()))
                .prop("chamber_construction_cost", money(s.chamberCost()))
                .prop("existing_chamber_tie_in_count", s.tieInCount())
                .prop("existing_chamber_tie_in_cost", money(s.tieInCost()))
                .prop("unconnected_penalty", money(s.penalty()))
                .prop("calculated_cost", money(s.calculatedCost()))
                .prop("new_network_length", round(s.newLength(), 3))
                .prop("score", round(s.score(), 4))
                .prop("unconnected_oks_ids", unconnectedIds)
                .prop("network_construction_cost", money(s.lineCost()))
                .prop("connected_oks_count", variant.connectedCount())
                .prop("strategy", variant.strategy())
                .prop("notes", variant.notes())
                .prop("shared_network_length", round(variant.metrics().sharedNetworkLength, 3))
                .prop("attachment_point_count", variant.metrics().rootCount)
                .prop("attachment_points", variant.metrics().attachmentPoints)
                .prop("diff_vs_best", variant.diffVsBest() == null ? null : variant.diffVsBest().text)
                .prop("mode", depthMode ? "depth" : "planar");
        if (dm != null) {
            summary.prop("max_depth", round(dm.maxDepth, 3))
                    .prop("min_depth", round(dm.minDepth, 3))
                    .prop("average_depth", round(dm.averageDepth, 3))
                    .prop("deep_network_length", round(dm.deepLength, 3))
                    .prop("shallow_network_length", round(dm.shallowLength, 3))
                    .prop("depth_transition_count", dm.transitionCount)
                    .prop("vertical_crossing_count", dm.verticalCrossingCount)
                    .prop("above_crossing_count", dm.aboveCount)
                    .prop("below_crossing_count", dm.belowCount)
                    .prop("pass_under_count", dm.underCount)
                    .prop("depth_extra_cost", money(dm.extraCost));
        }
        out.addAll(lines);
        out.addAll(chambers);
        out.addAll(tns);
        out.add(summary);
        return out;
    }

    static String key(Coordinate c) { return Math.round(c.x * 1000) + ":" + Math.round(c.y * 1000); }

    /** Vertical crossing details of a special piece in depth mode (for the crossing inspector and the report). */
    static List<Object> crossingDetails(NetLink link, ru.lct.heatnet.cost.LinkPiece piece) {
        List<Object> out = new ArrayList<>();
        ru.lct.heatnet.depth.DepthProfile profile = link.profile();
        double hNew = ru.lct.heatnet.config.DiameterTable.requireDu(link.du()).heightM();
        double mid = (piece.from() + piece.to()) / 2;
        for (ru.lct.heatnet.depth.DepthProfile.PlateauDecision d : profile.decisions()) {
            if (mid < d.from - 1e-6 || mid > d.to + 1e-6) continue;
            for (ru.lct.heatnet.depth.CrossingDepthOption o : d.options) {
                ru.lct.heatnet.depth.VerticalObstacle vo = o.obstacle();
                if (vo.from() >= piece.to() - 1e-6 || vo.to() <= piece.from() + 1e-6) continue; // positive overlap only (touching pieces are not crossings)
                java.util.Map<String, Object> c = new java.util.LinkedHashMap<>();
                c.put("type", vo.type());
                c.put("object", vo.label());
                c.put("method", o.method().name());
                double top = piece.depthStart(), bottom = top + hNew;
                c.put("new_top", round(top, 4));
                c.put("new_bottom", round(bottom, 4));
                c.put("new_height", round(hNew, 4));
                if (vo.isUtility()) {
                    c.put("utility_top", round(vo.utilityTop(), 4));
                    c.put("utility_bottom", round(vo.utilityBottom(), 4));
                    c.put("required_clearance", vo.rule().clearanceM());
                    double actual = o.method() == ru.lct.heatnet.depth.CrossingDepthOption.Method.ABOVE ? vo.utilityTop() - bottom : top - vo.utilityBottom();
                    c.put("actual_clearance", round(actual, 4));
                } else {
                    c.put("required_min_depth", vo.rule().minDepthM());
                    c.put("actual_depth", round(top, 4));
                }
                c.put("k_spec", vo.kSpec());
                out.add(c);
            }
        }
        return out;
    }

    static double[] round(double[] ll) {
        return new double[]{round(ll[0], COORD_DECIMALS), round(ll[1], COORD_DECIMALS)};
    }

    static double round(double v, int decimals) {
        return new BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /** Money rounded to whole rubles. */
    static long money(double v) { return Math.round(v); }
}
