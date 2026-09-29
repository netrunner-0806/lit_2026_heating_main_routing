package ru.lct.heatnet.validation;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.domain.ConnectionPoint;
import ru.lct.heatnet.domain.ExistingChamber;
import ru.lct.heatnet.domain.ExistingNetworkLine;
import ru.lct.heatnet.domain.JsonId;
import ru.lct.heatnet.output.OutputFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory model of one output variant reconstructed from its features (independent of the solver). */
final class VariantModel {

    enum NodeKind { OKS, EXISTING_CHAMBER, NEW_CHAMBER, TECHNICAL_NODE }

    static final class Node {
        final JsonId id;
        final NodeKind kind;
        final Coordinate coord;      // metric
        final double[] wgs;
        final OutputFeature feature; // null for input nodes
        final ConnectionPoint cp;
        final ExistingChamber chamber;
        final List<Line> lines = new ArrayList<>();
        ExistingNetworkLine onExistingLine; // for new chambers located on an existing line
        int existingAdjacency;
        boolean root;

        Node(JsonId id, NodeKind kind, Coordinate coord, double[] wgs, OutputFeature feature, ConnectionPoint cp, ExistingChamber chamber) {
            this.id = id; this.kind = kind; this.coord = coord; this.wgs = wgs; this.feature = feature; this.cp = cp; this.chamber = chamber;
        }

        boolean isChamber() { return kind == NodeKind.EXISTING_CHAMBER || kind == NodeKind.NEW_CHAMBER; }
        int degree() { return lines.size(); }
    }

    static final class Line {
        final OutputFeature feature;
        final String id;
        final LineString metric;
        final List<Coordinate> coords;
        final double length;         // geometric metric length
        Node start, end;
        double flow;
        int du;
        boolean special;
        double declaredLength, declaredCost;
        /** Max Kspec of the special sections covering this line (set by the spatial validator), 1.0 if none. */
        double kSpecExpected = 1.0;
        boolean coveredBySection;
        /** Labels of the objects whose special sections cover this line (set by the spatial validator). */
        final java.util.TreeSet<String> coveringObjects = new java.util.TreeSet<>();
        /** Crossed objects with vertical rules covering this line (set by the spatial validator). */
        final List<Crossing> crossings = new ArrayList<>();
        Double depthStart, depthEnd;
        // tree orientation (set after root detection)
        Node parentNode, childNode;

        Line(OutputFeature f, String id, LineString metric) {
            this.feature = f; this.id = id; this.metric = metric;
            this.coords = java.util.Arrays.asList(metric.getCoordinates());
            this.length = metric.getLength();
        }

        Node other(Node n) { return n == start ? end : start; }
    }

    /** One crossed object with a vertical rule, as seen from a line. */
    static final class Crossing {
        final String type;
        final String label;
        final double existingHeightM; // NaN unless existing heat network
        Crossing(String type, String label, double existingHeightM) { this.type = type; this.label = label; this.existingHeightM = existingHeightM; }
    }

    /** Maximal path of lines through technical nodes: one section between chambers / connection points. */
    static final class Chain {
        final List<Line> lines = new ArrayList<>();   // ordered from childEnd to parentEnd
        final List<Coordinate> coords = new ArrayList<>();
        Node childEnd, parentEnd;
        double length;
        /** [from, to] along the chain for each line in order. */
        final List<double[]> lineIntervals = new ArrayList<>();
    }

    final String variantId;
    final Map<JsonId, Node> nodes = new LinkedHashMap<>();
    final List<Line> lines = new ArrayList<>();
    final List<Chain> chains = new ArrayList<>();
    OutputFeature summary;
    final List<OutputFeature> chamberFeatures = new ArrayList<>();
    final List<OutputFeature> technicalNodeFeatures = new ArrayList<>();

    VariantModel(String variantId) { this.variantId = variantId; }
}
