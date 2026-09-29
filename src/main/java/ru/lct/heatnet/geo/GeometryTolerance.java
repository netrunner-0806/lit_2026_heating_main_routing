package ru.lct.heatnet.geo;

/**
 * Single registry of numerical tolerances, separated by purpose. Values are metres / degrees / rubles in the metric
 * working CRS (EPSG:32637). They are NOT rules of the annex: they only absorb floating-point and projection noise
 * (a WGS84 round trip of a coordinate moves it by well under 1 mm) and must stay far below any rule threshold
 * (the smallest rule distances are 0.2 m of vertical clearance and 1 m of horizontal clearance).
 *
 * <table>
 * <tr><th>Purpose</th><th>Constant</th><th>Value</th></tr>
 * <tr><td>two coordinates are the same point</td><td>{@link #COORDINATE_EQUALITY_M}</td><td>1e-6 m</td></tr>
 * <tr><td>line endpoint must coincide with its node</td><td>{@link #NODE_SNAP_M}</td><td>2 mm</td></tr>
 * <tr><td>new chamber lies on an existing line; special section ends snap to line ends (validator)</td><td>{@link #ON_LINE_M}</td><td>5 cm</td></tr>
 * <tr><td>special section boundary snapped to the link end (cost pieces)</td><td>{@link #SPECIAL_SNAP_TO_END_M}</td><td>2 cm</td></tr>
 * <tr><td>margin of clearance-zone polygons around vertices / coverage of special sections</td><td>{@link #ZONE_MARGIN_M}</td><td>2 cm</td></tr>
 * <tr><td>intersection / distance predicates</td><td>{@link #INTERSECTION_EPS_M}</td><td>1 mm</td></tr>
 * <tr><td>declared vs geometric length of an output line</td><td>{@link #LENGTH_DECLARED_TOL_M}</td><td>2 cm</td></tr>
 * <tr><td>hard minimum distance between distinct new lines outside a common node</td><td>{@link #NEW_LINE_HARD_DISTANCE_M}</td><td>5 cm</td></tr>
 * <tr><td>angle comparisons (turn 90°, crossing 45°): solver / validator</td><td>{@link #ANGLE_TOL_DEG} / {@link #VALIDATOR_ANGLE_TOL_DEG}</td><td>1e-6° / 1e-4°</td></tr>
 * <tr><td>a special feature is straight (cost pieces / validator)</td><td>{@link #STRAIGHT_TOL_DEG} / {@link #VALIDATOR_STRAIGHT_TOL_DEG}</td><td>1e-3° / 0.01°</td></tr>
 * <tr><td>comparison of two reported depths (continuity, plateaus)</td><td>{@link #DEPTH_TOL_M}</td><td>1 mm</td></tr>
 * <tr><td>numeric rule thresholds (0.7 m, clearances, slope)</td><td>{@link #RULE_TOL}</td><td>1e-6</td></tr>
 * <tr><td>rounding of reported depths (4 decimals) / lengths (3 decimals)</td><td>{@link #DEPTH_REPORT_RESOLUTION_M} / {@link #LENGTH_REPORT_RESOLUTION_M}</td><td>1e-4 / 1e-3</td></tr>
 * <tr><td>whole-ruble costs recomputed by the validator</td><td>{@link #COST_TOL_RUB}</td><td>1 ruble per rounded term</td></tr>
 * <tr><td>score (4 decimals)</td><td>{@link #SCORE_TOL}</td><td>1e-3</td></tr>
 * </table>
 */
public final class GeometryTolerance {
    private GeometryTolerance() {}

    public static final double COORDINATE_EQUALITY_M = 1e-6;
    public static final double NODE_SNAP_M = 0.002;
    public static final double ON_LINE_M = 0.05;
    public static final double SPECIAL_SNAP_TO_END_M = 0.02;
    public static final double ZONE_MARGIN_M = 0.02;
    public static final double INTERSECTION_EPS_M = 1e-3;
    public static final double LENGTH_DECLARED_TOL_M = 0.02;
    public static final double NEW_LINE_HARD_DISTANCE_M = 0.05;
    public static final double ANGLE_TOL_DEG = 1e-6;
    /** Validator angle comparisons on output geometry: an exact 90° junction or 45° crossing built in metric
     *  coordinates is re-read from WGS84 with 12 decimals; measured worst-case noise of the round trip is 8.5e-6° on a
     *  1 m segment (0.0093° with 9 decimals). 1e-4° absorbs it and still rejects 90.001° / 44.999°. */
    public static final double VALIDATOR_ANGLE_TOL_DEG = 1e-4;
    public static final double STRAIGHT_TOL_DEG = 1e-3;
    public static final double VALIDATOR_STRAIGHT_TOL_DEG = 0.01;
    public static final double DEPTH_TOL_M = 1e-3;
    /** Numeric rule thresholds (0.7 m, clearances, cover, slope 0.10): floating noise only, never a rounding step. */
    public static final double RULE_TOL = 1e-6;
    /** Total rounding error of a depth difference: depths are reported with 4 decimals (±0.00005 each end). */
    public static final double DEPTH_REPORT_RESOLUTION_M = 1e-4;
    /** Lengths are reported with 3 decimals (±0.0005). */
    public static final double LENGTH_REPORT_RESOLUTION_M = 1e-3;
    public static final double COST_TOL_RUB = 1.0;
    public static final double SCORE_TOL = 1e-3;
}
