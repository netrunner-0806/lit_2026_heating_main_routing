package ru.lct.heatnet.geo;

/** Valid input could not be processed internally. Never turn this into a missing obstacle or no-path result. */
public final class GeometryProcessingException extends RuntimeException {
    public GeometryProcessingException(String stage, Throwable cause) {
        super("INTERNAL_GEOMETRY_PROCESSING_FAILURE: " + stage + ": " + cause.getMessage(), cause);
    }
}
