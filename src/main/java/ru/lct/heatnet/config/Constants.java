package ru.lct.heatnet.config;

/** Centralised constants of the current technical annex (2D mandatory mode). */
public final class Constants {
    private Constants() {}

    public static final String CRS_INPUT = "EPSG:4326";
    public static final String CRS_METRIC = "EPSG:32637";

    /** Max change of direction at one vertex, degrees (0 = straight). */
    public static final double MAX_TURN_ANGLE_DEG = 90.0;
    /** Max number of line sections adjacent to one chamber. */
    public static final int MAX_CHAMBER_DEGREE = 4;
    /** Use an existing chamber if the tie-in point is within this distance, metres. */
    public static final double EXISTING_CHAMBER_SNAP_DISTANCE_M = 10.0;

    /** Penalty for an unconnected connection point: fixed + per t/h. */
    public static final double UNCONNECTED_PENALTY_FIXED = 100_000_000d;
    public static final double UNCONNECTED_PENALTY_PER_TPH = 500_000d;

    /** Score S = 0.7 * (C / 25e6) + 0.3 * (L / 100). */
    public static final double SCORE_COST_WEIGHT = 0.7;
    public static final double SCORE_COST_BASE = 25_000_000d;
    public static final double SCORE_LENGTH_WEIGHT = 0.3;
    public static final double SCORE_LENGTH_BASE = 100d;

    /** Depth coefficient in the base 2D mode. */
    public static final double K_DEPTH_2D = 1.0;

    /** Max number of variants returned. */
    public static final int MAX_VARIANTS = 3;

    public static final String LAYING_BASE = "base";
    public static final String LAYING_SPECIAL = "special";

    /** A crossing point closer than this to the end of a line that terminates on the object is a termination, not a crossing. */
    public static final double TERMINATION_TOL_M = 0.5;

    /** Geometric tolerance for coincidence of points, metres. */
    public static final double COINCIDENCE_TOL_M = 0.01;
}
