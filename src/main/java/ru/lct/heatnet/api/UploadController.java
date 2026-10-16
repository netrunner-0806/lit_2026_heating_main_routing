package ru.lct.heatnet.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.lct.heatnet.application.UploadService;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

/** Capability-protected resumable upload; the existing multipart jobs endpoint is unchanged. */
@RestController
@RequestMapping("/api/v1/uploads")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Uploads", description = "Возобновляемая загрузка: SHA-256 частей, устойчивый журнал, идемпотентный complete. X-Upload-Token — секрет владельца (64 hex).")
public class UploadController {
    private final UploadService uploads;
    public UploadController(UploadService uploads) { this.uploads = uploads; }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, Object>> create(@RequestBody UploadService.Request request,
            @RequestHeader(value = "X-Upload-Token", required = false) String token) throws IOException {
        return ResponseEntity.status(201).body(uploads.create(request, token));
    }
    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id,
            @RequestHeader(value = "X-Upload-Token", required = false) String token) throws IOException {
        return uploads.status(id, token);
    }
    @PutMapping(value = "/{id}/parts/{index}", consumes = "application/octet-stream")
    public Map<String, Object> part(@PathVariable String id, @PathVariable int index,
            @RequestHeader(value = "X-Upload-Token", required = false) String token,
            @RequestHeader(value = "X-Part-SHA256", required = false) String checksum,
            HttpServletRequest request) throws IOException {
        return uploads.part(id, token, index, request.getContentLengthLong(), checksum, request.getInputStream());
    }
    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String id,
            @RequestHeader(value = "X-Upload-Token", required = false) String token) throws IOException {
        return ResponseEntity.accepted().body(uploads.complete(id, token));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id,
            @RequestHeader(value = "X-Upload-Token", required = false) String token) throws IOException {
        uploads.cancel(id, token); return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> storageFailure(IOException ex) {
        org.slf4j.LoggerFactory.getLogger(UploadController.class).warn("Upload storage/transport failure", ex);
        // Native FileChannel.write reports ENOSPC as a plain IOException on Linux.
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
        boolean full = message.contains("no space left on device") || message.contains("disk quota exceeded");
        int status = full ? 507 : 503;
        return ResponseEntity.status(status).body(java.util.Map.of("status", status,
                "message", full ? "Upload storage is full; acknowledged parts retained" : "Upload interrupted; query status and retry missing parts"));
    }
}
