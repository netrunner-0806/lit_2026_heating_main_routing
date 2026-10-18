package ru.lct.heatnet.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.heatnet.api.UploadException;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.persistence.JobEntity;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Single-app durable upload journal on the same persistent filesystem as jobs.
 * Data is forced before each atomic journal acknowledgement. Unacknowledged ranges can always be overwritten.
 * Fixed striped locks bound memory and serialize writes/complete/cleanup, including overlapping HTTP requests.
 * This deployment has one app replica; sharing this directory between app processes is unsupported.
 */
@Service
@EnableScheduling
public class UploadService {
    public static final int MIN_CHUNK = 8 * 1024 * 1024, DEFAULT_CHUNK = 16 * 1024 * 1024, MAX_CHUNK = 32 * 1024 * 1024;
    private static final long TTL = 24 * 60 * 60 * 1000L;
    private final ObjectMapper json = new ObjectMapper();
    private final JobService jobs;
    private final long maxSize;
    private final ReentrantLock[] locks = new ReentrantLock[256];

    public UploadService(JobService jobs, MultipartProperties multipart) {
        this.jobs = jobs; this.maxSize = multipart.getMaxFileSize().toBytes();
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    public static final class Request {
        public String uploadId, filename, fingerprint, mode = "PLANAR", unknownRestrictionPolicy = "IGNORE";
        public long totalSize;
        public int chunkSize = DEFAULT_CHUNK, maxVariants = 3;
    }
    public static final class Session {
        public String id, tokenHash, filename, fingerprint, mode, policy, jobId, sha256;
        public String state = "UPLOADING";
        public long totalSize, createdAt, updatedAt, expiresAt;
        public int chunkSize, maxVariants;
        public String[] parts;
        public boolean cleanupComplete;
    }

    private Path root() { return jobs.storageDir().resolve(".uploads"); }
    private static String id(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new UploadException(400, "Invalid upload id");
        return id;
    }
    private Path dir(String id) { return root().resolve(id(id)); }
    private ReentrantLock lock(String id) { return locks[(id(id).hashCode() & Integer.MAX_VALUE) % locks.length]; }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
    private static String tokenHash(String token) {
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new UploadException(403, "Upload token required");
        return hex(digest().digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    private Session load(String id, String token) throws IOException {
        String hash = tokenHash(token);
        Path path = dir(id).resolve("session.json");
        if (!Files.isRegularFile(path)) throw new UploadException(404, "Upload session not found");
        Session s = json.readValue(path.toFile(), Session.class);
        if (!MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                s.tokenHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new UploadException(403, "Upload token does not match");
        if (s.state.equals("CANCELLED") || s.state.equals("EXPIRED")) throw new UploadException(410, "Upload " + s.state.toLowerCase(Locale.ROOT));
        if (!s.state.equals("COMPLETED") && s.expiresAt < System.currentTimeMillis()) {
            s.state = "EXPIRED"; save(s); removeTemporary(s);
            throw new UploadException(410, "Upload expired");
        }
        return s;
    }
    private void save(Session s) throws IOException {
        Path d = dir(s.id); Files.createDirectories(d);
        byte[] bytes = json.writeValueAsBytes(s); // bounded metadata: at most 384 part hashes
        Path next = d.resolve("session.next");
        try (FileChannel ch = FileChannel.open(next, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) ch.write(b); ch.force(true);
        }
        // Fail if the storage cannot provide atomic replacement; never acknowledge an unsafe journal.
        Files.move(next, d.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel directory = FileChannel.open(d, StandardOpenOption.READ)) { directory.force(true); }
    }
    private void touch(Session s) { s.updatedAt = System.currentTimeMillis(); s.expiresAt = s.updatedAt + TTL; }
    private static long partSize(Session s, int index) { return Math.min(s.chunkSize, s.totalSize - (long) index * s.chunkSize); }

    public Map<String, Object> create(Request request, String token) throws IOException {
        id(request.uploadId); String hash = tokenHash(token);
        if (request.totalSize <= 0) throw new UploadException(400, "Empty upload");
        if (request.totalSize > maxSize) throw new UploadException(413, "File exceeds " + maxSize + " bytes");
        if (request.chunkSize < MIN_CHUNK || request.chunkSize > MAX_CHUNK) throw new UploadException(400, "chunkSize must be 8..32 MiB");
        if (request.maxVariants < 1 || request.maxVariants > 3) throw new UploadException(400, "maxVariants must be 1..3");
        String mode = JobService.normalizeMode(request.mode);
        String policy;
        try { policy = ParseOptions.UnknownRestrictionPolicy.valueOf(request.unknownRestrictionPolicy == null ? "IGNORE" : request.unknownRestrictionPolicy).name(); }
        catch (IllegalArgumentException ex) { throw new UploadException(400, "unknownRestrictionPolicy must be IGNORE or FORBIDDEN"); }
        if (request.filename == null || request.filename.isEmpty() || request.filename.length() > 512)
            throw new UploadException(400, "filename must contain 1..512 characters");
        if (request.fingerprint == null || !request.fingerprint.matches("[0-9a-f]{64}"))
            throw new UploadException(400, "A SHA-256 file identity fingerprint is required");
        ReentrantLock l = lock(request.uploadId); l.lock();
        try {
            if (Files.exists(dir(request.uploadId).resolve("session.json"))) {
                Session old = load(request.uploadId, token);
                if (!old.filename.equals(request.filename) || old.totalSize != request.totalSize || old.chunkSize != request.chunkSize
                        || !old.fingerprint.equals(request.fingerprint) || !old.mode.equals(mode) || !old.policy.equals(policy) || old.maxVariants != request.maxVariants)
                    throw new UploadException(409, "Upload id already belongs to another request");
                return view(old);
            }
            Files.createDirectories(root());
            if (Files.getFileStore(root()).getUsableSpace() < request.totalSize + 64L * 1024 * 1024)
                throw new UploadException(507, "Insufficient upload storage");
            Session s = new Session(); s.id = request.uploadId; s.tokenHash = hash;
            s.filename = request.filename; s.fingerprint = request.fingerprint; s.totalSize = request.totalSize;
            s.chunkSize = request.chunkSize; s.mode = mode; s.policy = policy; s.maxVariants = request.maxVariants;
            s.jobId = UUID.randomUUID().toString(); s.createdAt = System.currentTimeMillis(); touch(s);
            s.parts = new String[(int) ((s.totalSize + s.chunkSize - 1) / s.chunkSize)];
            try { save(s); }
            catch (IOException ex) {
                if (!Files.exists(dir(s.id).resolve("session.json"))) {
                    try { Files.deleteIfExists(dir(s.id).resolve("session.next")); Files.deleteIfExists(dir(s.id)); }
                    catch (IOException cleanup) { ex.addSuppressed(cleanup); }
                }
                throw ex;
            }
            return view(s);
        } finally { l.unlock(); }
    }

    public Map<String, Object> status(String id, String token) throws IOException {
        ReentrantLock l = lock(id); l.lock();
        try { Session s = load(id, token); touch(s); save(s); return view(s); }
        finally { l.unlock(); }
    }

    public Map<String, Object> part(String id, String token, int index, long contentLength, String checksum, InputStream body) throws IOException {
        ReentrantLock l = lock(id); l.lock();
        try {
            Session s = load(id, token);
            if (index < 0 || index >= s.parts.length) throw new UploadException(400, "partIndex outside file");
            if (checksum == null || !checksum.matches("[0-9a-f]{64}")) throw new UploadException(400, "X-Part-SHA256 required");
            long expected = partSize(s, index);
            if (contentLength >= 0 && contentLength != expected) throw new UploadException(400, "Incorrect part length");
            boolean duplicate = s.parts[index] != null;
            if (!s.state.equals("UPLOADING") && !duplicate) throw new UploadException(409, "Upload no longer accepts new parts");
            if (duplicate && !s.parts[index].equals(checksum)) throw new UploadException(409, "Conflicting repeated part");
            // No staging copy. A partial/incorrect part remains unacknowledged and is safely overwritten on retry.
            MessageDigest sha = digest(); byte[] buffer = new byte[64 * 1024]; long received = 0;
            try (FileChannel file = duplicate ? null : FileChannel.open(dir(id).resolve("data"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                while (received < expected) {
                    int n = body.read(buffer, 0, (int) Math.min(buffer.length, expected - received));
                    if (n < 0) throw new UploadException(400, "Incomplete part; retry this part");
                    if (n == 0) continue;
                    sha.update(buffer, 0, n);
                    if (file != null) {
                        ByteBuffer block = ByteBuffer.wrap(buffer, 0, n);
                        long offset = (long) index * s.chunkSize + received;
                        writeBlock(file, block, offset);
                    }
                    received += n;
                }
                if (body.read() != -1) throw new UploadException(413, "Part exceeds expected length");
                if (!checksum.equals(hex(sha.digest()))) throw new UploadException(422, "Part SHA-256 mismatch");
                if (file != null) file.force(true);
            }
            s.parts[index] = checksum; touch(s); save(s); return view(s);
        } finally { l.unlock(); }
    }

    /** Isolated disk-write seam for fault-injection tests; production always writes directly to the session file. */
    void writeBlock(FileChannel file, ByteBuffer block, long offset) throws IOException {
        while (block.hasRemaining()) offset += file.write(block, offset);
    }

    public Map<String, Object> complete(String id, String token) throws IOException {
        ReentrantLock l = lock(id); l.lock();
        try {
            Session s = load(id, token);
            if (s.state.equals("COMPLETED")) {
                if (!jobs.find(s.jobId).isPresent()) throw new UploadException(410, "Completed job was deleted");
                return view(s);
            }
            if (Arrays.stream(s.parts).anyMatch(Objects::isNull)) throw new UploadException(409, "Upload has missing parts");
            Path data = dir(id).resolve("data");
            if (!s.state.equals("READY")) {
                if (!Files.isRegularFile(data) || Files.size(data) != s.totalSize) throw new UploadException(409, "Stored upload size mismatch");
                MessageDigest full = digest(); byte[] block = new byte[64 * 1024];
                try (InputStream in = Files.newInputStream(data)) {
                    for (int i = 0; i < s.parts.length; i++) {
                        MessageDigest part = digest(); long left = partSize(s, i);
                        while (left > 0) {
                            int n = in.read(block, 0, (int) Math.min(left, block.length));
                            if (n < 0) throw new UploadException(409, "Stored upload truncated");
                            part.update(block, 0, n); full.update(block, 0, n); left -= n;
                        }
                        if (!s.parts[i].equals(hex(part.digest()))) {
                            s.parts[i] = null; touch(s); save(s);
                            throw new UploadException(422, "Stored part " + i + " is damaged; resend this part");
                        }
                    }
                }
                s.sha256 = hex(full.digest()); s.state = "READY"; touch(s); save(s);
            }
            jobs.submitCompleted(s.jobId, s.filename, s.totalSize, data,
                    ParseOptions.UnknownRestrictionPolicy.valueOf(s.policy), s.maxVariants, s.mode);
            s.state = "COMPLETED"; touch(s); save(s);
            Files.deleteIfExists(data); // only the upload hard link; the job input remains
            return view(s);
        } finally { l.unlock(); }
    }

    public void cancel(String id, String token) throws IOException {
        ReentrantLock l = lock(id); l.lock();
        try {
            Session s = load(id, token);
            JobEntity job = jobs.find(s.jobId).orElse(null);
            if (s.state.equals("COMPLETED") || (job != null && !(job.getStatus() == JobEntity.Status.FAILED
                    && "upload-queue-retry".equals(job.getProgress()))))
                throw new UploadException(409, "Upload already belongs to a job");
            s.state = "CANCELLED"; touch(s); save(s); removeTemporary(s);
        } finally { l.unlock(); }
    }

    private void removeTemporary(Session s) throws IOException {
        Files.deleteIfExists(dir(s.id).resolve("data"));
        // A DB outage can leave a published link before job registration. Never remove a registered job's input.
        if (!jobs.find(s.jobId).isPresent()) {
            Path d = jobs.storageDir().resolve(s.jobId);
            Files.deleteIfExists(d.resolve("input.geojson")); Files.deleteIfExists(d);
        }
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void cleanup() throws IOException {
        if (!Files.isDirectory(root())) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root())) {
            for (Path d : entries) {
                if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) || !d.getFileName().toString().matches("[0-9a-f-]{36}")) continue;
                ReentrantLock l = lock(d.getFileName().toString()); if (!l.tryLock()) continue;
                try {
                    Path journal = d.resolve("session.json"); if (!Files.isRegularFile(journal)) continue;
                    Session s = json.readValue(journal.toFile(), Session.class);
                    if (s.cleanupComplete || s.expiresAt >= System.currentTimeMillis()) continue;
                    // Keep the small completed tombstone for complete idempotency, even after input/job deletion.
                    if (!s.state.equals("COMPLETED")) { s.state = "EXPIRED"; save(s); }
                    removeTemporary(s);
                    s.cleanupComplete = true; save(s);
                } catch (IOException | RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(UploadService.class).warn("Upload cleanup deferred for {}", d.getFileName(), ex);
                } finally { l.unlock(); }
            }
        }
    }

    private Map<String, Object> view(Session s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", s.id); out.put("state", s.state); out.put("filename", s.filename);
        out.put("totalSize", s.totalSize); out.put("chunkSize", s.chunkSize); out.put("partCount", s.parts.length);
        List<Map<String, Object>> parts = new ArrayList<>(); long received = 0;
        for (int i = 0; i < s.parts.length; i++) if (s.parts[i] != null) {
            Map<String, Object> p = new LinkedHashMap<>(); p.put("index", i); p.put("sha256", s.parts[i]); parts.add(p); received += partSize(s, i);
        }
        out.put("parts", parts); out.put("receivedBytes", received); out.put("fingerprint", s.fingerprint);
        out.put("mode", s.mode); out.put("maxVariants", s.maxVariants); out.put("unknownRestrictionPolicy", s.policy);
        out.put("createdAt", s.createdAt); out.put("updatedAt", s.updatedAt); out.put("expiresAt", s.expiresAt);
        out.put("sha256", s.sha256); out.put("jobId", s.state.equals("COMPLETED") ? s.jobId : null);
        return out;
    }
}
