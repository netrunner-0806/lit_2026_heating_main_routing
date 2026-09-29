package ru.lct.heatnet.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.topology.NetLink;
import ru.lct.heatnet.topology.NetNode;
import ru.lct.heatnet.topology.NewNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Topological metrics of a variant and the automatically computed difference to another variant. */
public final class VariantMetrics {

    /** Length of links carrying two or more consumers (shared sections), metres. */
    public final double sharedNetworkLength;
    /** Number of distinct attachment points (roots) on the existing network. */
    public final int rootCount;
    /** Human-readable attachment points. */
    public final List<String> attachmentPoints;
    /** Attachment points closer than this are considered the same point when comparing variants, m. */
    public static final double SAME_ATTACHMENT_M = 10.0;
    /** Root key per consumer id. */
    public final Map<JsonId, String> rootByConsumer;
    /** Root coordinate per consumer id. */
    public final Map<JsonId, Coordinate> rootCoordByConsumer;
    /** Root coordinates of the variant. */
    public final List<Coordinate> rootCoords;
    /** Sorted consumer group (all consumers of the same root) per consumer id. */
    public final Map<JsonId, String> groupByConsumer;
    public final int newChambers;
    public final int tieIns;

    private VariantMetrics(double sharedNetworkLength, int rootCount, List<String> attachmentPoints, Map<JsonId, String> rootByConsumer,
                           Map<JsonId, Coordinate> rootCoordByConsumer, List<Coordinate> rootCoords,
                           Map<JsonId, String> groupByConsumer, int newChambers, int tieIns) {
        this.sharedNetworkLength = sharedNetworkLength;
        this.rootCount = rootCount;
        this.attachmentPoints = Collections.unmodifiableList(attachmentPoints);
        this.rootByConsumer = Collections.unmodifiableMap(rootByConsumer);
        this.rootCoordByConsumer = Collections.unmodifiableMap(rootCoordByConsumer);
        this.rootCoords = Collections.unmodifiableList(rootCoords);
        this.groupByConsumer = Collections.unmodifiableMap(groupByConsumer);
        this.newChambers = newChambers;
        this.tieIns = tieIns;
    }

    public static VariantMetrics of(NewNetwork net) {
        double shared = 0;
        for (NetLink l : net.links()) if (HeatnetSolver.subtreeLeaves(l).size() >= 2) shared += l.length();
        List<NetNode> roots = net.roots();
        List<String> points = new ArrayList<>();
        Map<NetNode, String> rootKeys = new LinkedHashMap<>();
        for (NetNode r : roots) {
            String key = rootKey(r);
            rootKeys.put(r, key);
            points.add(key);
        }
        Map<JsonId, String> rootByConsumer = new LinkedHashMap<>();
        Map<JsonId, Coordinate> rootCoordByConsumer = new LinkedHashMap<>();
        List<Coordinate> rootCoords = new ArrayList<>();
        for (NetNode r : roots) rootCoords.add(r.coord());
        Map<JsonId, String> groupByConsumer = new LinkedHashMap<>();
        Map<NetNode, TreeSet<String>> groups = new LinkedHashMap<>();
        for (NetNode cp : net.connectionNodes()) {
            NetNode r = cp;
            while (r.parent() != null) r = r.parent().parent();
            rootByConsumer.put(cp.connectionPoint().id(), rootKeys.getOrDefault(r, rootKey(r)));
            rootCoordByConsumer.put(cp.connectionPoint().id(), r.coord());
            groups.computeIfAbsent(r, k -> new TreeSet<>()).add(cp.connectionPoint().id().text());
        }
        for (NetNode cp : net.connectionNodes()) {
            NetNode r = cp;
            while (r.parent() != null) r = r.parent().parent();
            groupByConsumer.put(cp.connectionPoint().id(), String.join(",", groups.get(r)));
        }
        int tieIns = 0;
        for (NetNode n : net.nodes()) if (n.kind() == NetNode.Kind.EXISTING_CHAMBER) tieIns += n.children().size();
        return new VariantMetrics(shared, roots.size(), points, rootByConsumer, rootCoordByConsumer, rootCoords, groupByConsumer, net.newChambers().size(), tieIns);
    }

    /** Stable key of an attachment point: existing chamber id, or the line id with the point rounded to 1 m. */
    static String rootKey(NetNode r) {
        if (r.kind() == NetNode.Kind.EXISTING_CHAMBER) return "existing chamber " + r.existingChamber().id().text();
        Coordinate c = r.coord();
        String line = r.existingLine() == null ? "?" : r.existingLine().id().text();
        return "new chamber on line " + line + " @" + Math.round(c.x) + "," + Math.round(c.y);
    }

    /** Difference of {@code v} relative to {@code base} (typically the rank-1 variant). */
    public static Diff diff(VariantResult v, VariantResult base) {
        VariantMetrics a = v.metrics(), b = base.metrics();
        Diff d = new Diff();
        d.baseVariantId = base.variantId();
        d.deltaLength = v.summary().newLength() - base.summary().newLength();
        d.deltaCost = v.summary().calculatedCost() - base.summary().calculatedCost();
        d.deltaScore = v.summary().score() - base.summary().score();
        d.deltaNewChambers = a.newChambers - b.newChambers;
        d.deltaTieIns = a.tieIns - b.tieIns;
        d.deltaSharedLength = a.sharedNetworkLength - b.sharedNetworkLength;
        // attachment points within SAME_ATTACHMENT_M are the same point (a small shift along the line is not a difference)
        for (Map.Entry<JsonId, Coordinate> e : a.rootCoordByConsumer.entrySet()) {
            Coordinate other = b.rootCoordByConsumer.get(e.getKey());
            if (other == null || other.distance(e.getValue()) > SAME_ATTACHMENT_M) d.consumersWithDifferentAttachment++;
        }
        for (Map.Entry<JsonId, String> e : a.groupByConsumer.entrySet()) {
            String other = b.groupByConsumer.get(e.getKey());
            if (other == null || !other.equals(e.getValue())) d.consumersWithDifferentBranch++;
        }
        for (Coordinate p : a.rootCoords) {
            boolean found = false;
            for (Coordinate q : b.rootCoords) if (p.distance(q) <= SAME_ATTACHMENT_M) { found = true; break; }
            if (!found) d.newAttachmentPoints++;
        }
        List<String> parts = new ArrayList<>();
        if (Math.abs(d.deltaLength) >= 0.05) parts.add(String.format(java.util.Locale.ROOT, "%+.1f m network", d.deltaLength));
        if (Math.abs(d.deltaCost) >= 1) parts.add(String.format(java.util.Locale.ROOT, "%+,.0f ₽", d.deltaCost));
        if (d.deltaNewChambers != 0) parts.add(String.format(java.util.Locale.ROOT, "%+d new chamber%s", d.deltaNewChambers, Math.abs(d.deltaNewChambers) == 1 ? "" : "s"));
        if (d.deltaTieIns != 0) parts.add(String.format(java.util.Locale.ROOT, "%+d existing-chamber tie-in%s", d.deltaTieIns, Math.abs(d.deltaTieIns) == 1 ? "" : "s"));
        if (Math.abs(d.deltaSharedLength) >= 0.05) parts.add(String.format(java.util.Locale.ROOT, "%+.1f m shared sections", d.deltaSharedLength));
        if (d.consumersWithDifferentAttachment > 0) parts.add(d.consumersWithDifferentAttachment + " OKS attach at a different point");
        if (d.consumersWithDifferentBranch > 0) parts.add(d.consumersWithDifferentBranch + " OKS use a different shared branch");
        if (d.newAttachmentPoints > 0) parts.add(d.newAttachmentPoints + " attachment point" + (d.newAttachmentPoints == 1 ? "" : "s") + " not used by " + base.variantId());
        d.text = parts.isEmpty() ? "same topology as " + base.variantId() : "vs " + base.variantId() + ": " + String.join("; ", parts);
        return d;
    }

    /** Automatically computed difference between two variants. */
    public static final class Diff {
        public String baseVariantId;
        public double deltaLength;
        public double deltaCost;
        public double deltaScore;
        public int deltaNewChambers;
        public int deltaTieIns;
        public double deltaSharedLength;
        public int consumersWithDifferentAttachment;
        public int consumersWithDifferentBranch;
        public int newAttachmentPoints;
        public String text;
    }
}
