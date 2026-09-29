package ru.lct.heatnet.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;

/**
 * Identifier that preserves the original JSON type (string or number). Ids are opaque link values:
 * they are never parsed or converted semantically. Two ids are equal only if both value and JSON kind match.
 */
public final class JsonId implements Comparable<JsonId> {

    private final boolean numeric;
    private final String text;      // canonical textual form (for strings: the string; for numbers: the JSON literal)
    private final Number number;    // set for numeric ids

    private JsonId(boolean numeric, String text, Number number) {
        this.numeric = numeric;
        this.text = text;
        this.number = number;
    }

    public static JsonId ofString(String s) { return new JsonId(false, Objects.requireNonNull(s), null); }

    public static JsonId ofNumber(Number n) {
        Objects.requireNonNull(n);
        String literal;
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            literal = (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15)
                    ? Long.toString((long) d) : Double.toString(d);
            if (d == Math.rint(d) && Math.abs(d) < 1e15) n = (long) d;
        } else {
            literal = n.toString();
        }
        return new JsonId(true, literal, n);
    }

    public static JsonId fromJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return ofNumber(node.numberValue());
        if (node.isTextual()) return ofString(node.asText());
        return ofString(node.toString());
    }

    public boolean isNumeric() { return numeric; }
    public String text() { return text; }
    public Number number() { return number; }

    /** Jackson node with the original type. */
    public JsonNode toJson() {
        if (!numeric) return JsonNodeFactory.instance.textNode(text);
        if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte)
            return JsonNodeFactory.instance.numberNode(number.longValue());
        if (number instanceof java.math.BigInteger) return JsonNodeFactory.instance.numberNode((java.math.BigInteger) number);
        if (number instanceof java.math.BigDecimal) return JsonNodeFactory.instance.numberNode((java.math.BigDecimal) number);
        return JsonNodeFactory.instance.numberNode(number.doubleValue());
    }

    /** Value suitable for Jackson serialisation (String or Number). */
    public Object toJavaValue() { return numeric ? number : text; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonId)) return false;
        JsonId that = (JsonId) o;
        return numeric == that.numeric && text.equals(that.text);
    }

    @Override
    public int hashCode() { return Objects.hash(numeric, text); }

    @Override
    public String toString() { return numeric ? text : '"' + text + '"'; }

    @Override
    public int compareTo(JsonId o) {
        if (numeric != o.numeric) return numeric ? -1 : 1;
        if (numeric) return Double.compare(number.doubleValue(), o.number.doubleValue());
        return text.compareTo(o.text);
    }
}
