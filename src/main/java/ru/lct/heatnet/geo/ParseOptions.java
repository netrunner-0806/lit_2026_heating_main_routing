package ru.lct.heatnet.geo;

/** Options controlling input normalisation. */
public final class ParseOptions {

    public enum UnknownRestrictionPolicy {
        /** Unknown restriction types are ignored with a warning (mandatory part uses only Table 2 types). */
        IGNORE,
        /** Unknown restriction types are treated as forbidden with 1.0 m clearance (conservative). */
        FORBIDDEN
    }

    private final UnknownRestrictionPolicy unknownRestrictionPolicy;

    public ParseOptions(UnknownRestrictionPolicy unknownRestrictionPolicy) {
        this.unknownRestrictionPolicy = unknownRestrictionPolicy == null ? UnknownRestrictionPolicy.IGNORE : unknownRestrictionPolicy;
    }

    public static ParseOptions defaults() { return new ParseOptions(UnknownRestrictionPolicy.IGNORE); }

    public UnknownRestrictionPolicy unknownRestrictionPolicy() { return unknownRestrictionPolicy; }
}
