package ru.lct.heatnet.routing;

/** A resource budget was exhausted; this is not proof that no geometric route exists. */
public final class ComplexityLimitException extends IllegalArgumentException {
    public ComplexityLimitException(String message) { super("COMPLEXITY_LIMIT: " + message); }
}
