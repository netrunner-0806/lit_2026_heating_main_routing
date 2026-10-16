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
    private final long modelBudgetBytes;
    private final long featureBudgetBytes;

    public ParseOptions(UnknownRestrictionPolicy unknownRestrictionPolicy) {
        this(unknownRestrictionPolicy, Runtime.getRuntime().maxMemory() * 55 / 100,
                Math.max(65536, Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 64)));
    }

    /** Resource budgets, not geometric/engineering limits. Explicit values also allow small deterministic tests. */
    public ParseOptions(UnknownRestrictionPolicy unknownRestrictionPolicy, long modelBudgetBytes, long featureBudgetBytes) {
        if (modelBudgetBytes <= 0 || featureBudgetBytes <= 0) throw new IllegalArgumentException("Input budgets must be positive");
        this.unknownRestrictionPolicy = unknownRestrictionPolicy == null ? UnknownRestrictionPolicy.IGNORE : unknownRestrictionPolicy;
        this.modelBudgetBytes = modelBudgetBytes;
        this.featureBudgetBytes = featureBudgetBytes;
    }

    public static ParseOptions defaults() { return new ParseOptions(UnknownRestrictionPolicy.IGNORE); }

    public UnknownRestrictionPolicy unknownRestrictionPolicy() { return unknownRestrictionPolicy; }
    public long modelBudgetBytes() { return modelBudgetBytes; }
    public long featureBudgetBytes() { return featureBudgetBytes; }
}
