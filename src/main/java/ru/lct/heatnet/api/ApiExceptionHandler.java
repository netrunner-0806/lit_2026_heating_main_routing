package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import ru.lct.heatnet.domain.Diagnostics;
import ru.lct.heatnet.geo.InputParseException;

/** Uniform JSON error responses with input diagnostics where available. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ObjectNode> notFound(NotFoundException ex) { return body(HttpStatus.NOT_FOUND, ex.getMessage(), null); }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ObjectNode> conflict(ConflictException ex) { return body(HttpStatus.CONFLICT, ex.getMessage(), null); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ObjectNode> badRequest(IllegalArgumentException ex) { return body(HttpStatus.BAD_REQUEST, ex.getMessage(), null); }

    @ExceptionHandler(InputParseException.class)
    public ResponseEntity<ObjectNode> unprocessable(InputParseException ex) { return body(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.diagnostics()); }

    @ExceptionHandler({MaxUploadSizeExceededException.class})
    public ResponseEntity<ObjectNode> tooLarge(MaxUploadSizeExceededException ex) { return body(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the configured limit", null); }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ObjectNode> multipart(MultipartException ex) { return body(HttpStatus.BAD_REQUEST, "Invalid multipart request: " + ex.getMessage(), null); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> other(Exception ex) {
        String ref = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled (ref {})", ref, ex);
        // no exception class names, messages or stack traces leave the service; the reference finds the log entry
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error (ref " + ref + ")", null);
    }

    private ResponseEntity<ObjectNode> body(HttpStatus status, String message, Diagnostics diag) {
        ObjectNode n = mapper.createObjectNode();
        n.put("status", status.value());
        n.put("error", status.getReasonPhrase());
        n.put("message", message);
        if (diag != null) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = n.putArray("diagnostics");
            for (Diagnostics.Message m : diag.messages()) {
                ObjectNode o = arr.addObject();
                o.put("severity", m.getSeverity().name());
                o.put("code", m.getCode());
                o.put("text", m.getText());
                if (m.getFeatureIndex() != null) o.put("featureIndex", m.getFeatureIndex());
                if (m.getFeatureId() != null) o.put("featureId", m.getFeatureId());
            }
        }
        return ResponseEntity.status(status).body(n);
    }
}
