package ru.lct.heatnet.geo;

import ru.lct.heatnet.domain.Diagnostics;

/** Fatal input problem (invalid JSON / structure / no usable objects). Carries the diagnostics collected so far. */
public class InputParseException extends RuntimeException {
    private final Diagnostics diagnostics;

    public InputParseException(String message, Diagnostics diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    public InputParseException(String message, Diagnostics diagnostics, Throwable cause) {
        super(message, cause);
        this.diagnostics = diagnostics;
    }

    public Diagnostics diagnostics() { return diagnostics; }
}
