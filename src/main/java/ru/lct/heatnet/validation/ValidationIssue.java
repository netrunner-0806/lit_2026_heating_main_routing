package ru.lct.heatnet.validation;

/** One finding of the result validator. */
public final class ValidationIssue {
    public enum Severity { ERROR, WARNING, INFO }

    private final Severity severity;
    private final String code;
    private final String message;
    private final String variantId;
    private final String featureId;

    public ValidationIssue(Severity severity, String code, String message, String variantId, String featureId) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.variantId = variantId;
        this.featureId = featureId;
    }

    public Severity getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getVariantId() { return variantId; }
    public String getFeatureId() { return featureId; }

    @Override
    public String toString() {
        return severity + " [" + code + "]" + (variantId != null ? " " + variantId : "") + (featureId != null ? " (" + featureId + ")" : "") + ": " + message;
    }
}
