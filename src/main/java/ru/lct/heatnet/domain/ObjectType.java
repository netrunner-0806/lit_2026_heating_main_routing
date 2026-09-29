package ru.lct.heatnet.domain;

/** object_type values of the input GeoJSON. */
public enum ObjectType {
    SOURCE("source"),
    HEAT_NETWORK("heat_network"),
    HEAT_CHAMBER("heat_chamber"),
    OKS_CONNECTION_POINT("oks_connection_point"),
    RESTRICTION("restriction");

    private final String code;

    ObjectType(String code) { this.code = code; }

    public String code() { return code; }

    public static ObjectType fromCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        for (ObjectType t : values()) if (t.code.equals(c)) return t;
        return null;
    }
}
