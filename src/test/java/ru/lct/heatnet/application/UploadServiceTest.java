package ru.lct.heatnet.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;
import ru.lct.heatnet.api.UploadException;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UploadServiceTest {
    @TempDir Path root;
    private final String token = "ab".repeat(32);
    private UploadService uploads;
    private JobService jobs;
    private JobRepository repo;
    private ThreadPoolTaskExecutor executor;
    private final Map<String, JobEntity> records = new HashMap<>();
    private MultipartProperties multipart;
    @BeforeEach void setup() {
        repo = mock(JobRepository.class); executor = mock(ThreadPoolTaskExecutor.class);
        when(repo.findById(anyString())).thenAnswer(a -> Optional.ofNullable(records.get(a.getArgument(0))));
        when(repo.saveAndFlush(any())).thenAnswer(a -> { JobEntity e = a.getArgument(0); records.put(e.getId(),e); return e; });
        when(repo.rejectUploadQueue(anyString(),any())).thenAnswer(a -> {
            JobEntity e=records.get(a.getArgument(0)); e.setStatus(JobEntity.Status.FAILED); e.setProgress("upload-queue-retry"); return 1;
        });
        HeatnetProperties p = new HeatnetProperties(); p.setStorageDir(root.toString());
        jobs = new JobService(repo,mock(JobRunner.class),p,executor);
        multipart = new MultipartProperties(); multipart.setMaxFileSize(DataSize.ofGigabytes(3));
        uploads = new UploadService(jobs,multipart);
    }
    private UploadService.Request request(long size) {
        UploadService.Request r = new UploadService.Request(); r.uploadId=UUID.randomUUID().toString();
        r.filename="../../input.geojson"; r.totalSize=size; r.fingerprint="01".repeat(32); r.chunkSize=UploadService.MIN_CHUNK; return r;
    }
    private static String hash(byte[] b) throws Exception {
        StringBuilder s=new StringBuilder(); for(byte x:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format("%02x",x)); return s.toString();
    }
    private Map<String,Object> put(UploadService.Request r,int index,byte[] bytes) throws Exception {
        return uploads.part(r.uploadId,token,index,bytes.length,hash(bytes),new ByteArrayInputStream(bytes));
    }
    private Path session(UploadService.Request r) { return root.resolve(".uploads").resolve(r.uploadId).resolve("session.json"); }
    private void statusCode(int expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected,assertThrows(UploadException.class,call).status());
    }

    @Test void restartResumesMissingPartsAndCompleteIsIdempotentWithoutFileCopy() throws Exception {
        byte[] first=new byte[UploadService.MIN_CHUNK]; Arrays.fill(first,(byte)42); byte[] last={1,2,3};
        UploadService.Request r=request(first.length+last.length); uploads.create(r,token); put(r,0,first);
        assertTrue(records.isEmpty());
        uploads=new UploadService(jobs,multipart);
        assertEquals((long)first.length,uploads.status(r.uploadId,token).get("receivedBytes"));
        put(r,0,first); put(r,1,last);
        Map<String,Object> done=uploads.complete(r.uploadId,token);
        String job=(String)done.get("jobId");
        assertEquals(job,uploads.complete(r.uploadId,token).get("jobId"));
        assertEquals(1,records.size()); verify(executor,times(1)).execute(any(Runnable.class));
        Path input=Path.of(records.get(job).getInputPath()); assertEquals(r.totalSize,Files.size(input));
        assertFalse(Files.exists(session(r).resolveSibling("data")));
        assertEquals(hash(concat(first,last)),done.get("sha256"));
        assertTrue(input.startsWith(root));
    }
    private static byte[] concat(byte[] a,byte[] b) { byte[] c=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,c,a.length,b.length);return c; }

    @Test void unacknowledgedPartialWriteCanBeRetriedAfterLostConnection() throws Exception {
        byte[] bytes=new byte[20]; UploadService.Request r=request(bytes.length); uploads.create(r,token);
        InputStream broken=new InputStream() {
            int n;
            @Override public int read() throws IOException {if(n++>5)throw new IOException("connection reset");return 0;}
        };
        assertThrows(IOException.class,()->uploads.part(r.uploadId,token,0,bytes.length,hash(bytes),broken));
        assertEquals(0L,uploads.status(r.uploadId,token).get("receivedBytes"));
        statusCode(409,()->uploads.complete(r.uploadId,token));
        put(r,0,bytes); assertEquals("COMPLETED",uploads.complete(r.uploadId,token).get("state"));
    }

    @Test void checksumConflictLengthBoundsAndOwnershipAreEnforced() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token);
        statusCode(403,()->uploads.status(r.uploadId,"cd".repeat(32)));
        statusCode(403,()->uploads.complete(r.uploadId,null));
        statusCode(400,()->put(r,-1,new byte[3])); statusCode(400,()->put(r,1,new byte[3]));
        statusCode(400,()->put(r,0,new byte[2]));
        statusCode(422,()->uploads.part(r.uploadId,token,0,3,hash(new byte[3]),new ByteArrayInputStream(new byte[]{1,2,3})));
        assertEquals(0L,uploads.status(r.uploadId,token).get("receivedBytes"));
        statusCode(413,()->uploads.part(r.uploadId,token,0,-1,hash(new byte[3]),new ByteArrayInputStream(new byte[4])));
        put(r,0,new byte[3]); statusCode(409,()->put(r,0,new byte[]{1,2,3}));
        put(r,0,new byte[3]); // identical duplicate consumes/verifies body, not merely the claimed hash
        statusCode(422,()->uploads.part(r.uploadId,token,0,3,hash(new byte[3]),new ByteArrayInputStream(new byte[]{1,2,3})));
        uploads.complete(r.uploadId,token);
        assertArrayEquals(new byte[3],Files.readAllBytes(Path.of(records.values().iterator().next().getInputPath())));
    }

    @Test void conflictingSessionCreationCannotReplaceExistingUpload() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token); uploads.create(r,token);
        r.totalSize=4; statusCode(409,()->uploads.create(r,token));
        UploadService.Request giant=request(3L*1024*1024*1024+1); statusCode(413,()->uploads.create(giant,token));
        assertFalse(Files.exists(session(giant))); r.uploadId="../escape";statusCode(400,()->uploads.create(r,token));
    }

    @Test void queueFailureRetainsVerifiedUploadAndSameJobIdCanBeCompletedAgain() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token); put(r,0,new byte[3]);
        doThrow(new TaskRejectedException("full")).doNothing().when(executor).execute(any(Runnable.class));
        statusCode(503,()->uploads.complete(r.uploadId,token));
        JobEntity first=records.values().iterator().next(); assertEquals(JobEntity.Status.FAILED,first.getStatus());
        assertEquals("READY",uploads.status(r.uploadId,token).get("state"));
        assertTrue(Files.isSameFile(session(r).resolveSibling("data"),Path.of(first.getInputPath())));
        assertEquals(first.getId(),uploads.complete(r.uploadId,token).get("jobId")); assertEquals(1,records.size());
    }

    @Test void databaseFailureDoesNotLoseReadyFileOrCreateOrphanOnCancel() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token); put(r,0,new byte[3]);
        doThrow(new IllegalStateException("db offline")).when(repo).saveAndFlush(any());
        assertThrows(IllegalStateException.class,()->uploads.complete(r.uploadId,token));
        assertEquals("READY",uploads.status(r.uploadId,token).get("state"));
        uploads.cancel(r.uploadId,token);
        try(var paths=Files.list(root)) {assertEquals(1,paths.count(),"only .uploads journal directory remains");}
        assertFalse(Files.exists(session(r).resolveSibling("data")));
    }

    @Test void fullStorageFailureNeverAcknowledgesDamagedPart() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token);
        uploads = new UploadService(jobs,multipart) {
            @Override void writeBlock(java.nio.channels.FileChannel file, java.nio.ByteBuffer block, long offset) throws IOException {
                file.write(java.nio.ByteBuffer.wrap(new byte[]{99}),offset);
                throw new IOException("No space left on device");
            }
        };
        assertThrows(IOException.class,()->put(r,0,new byte[3]));
        assertEquals(0L,uploads.status(r.uploadId,token).get("receivedBytes"));
        uploads = new UploadService(jobs,multipart);
        put(r,0,new byte[3]); uploads.complete(r.uploadId,token);
    }

    @Test void corruptedStoredPartIsUnacknowledgedAtComplete() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token); put(r,0,new byte[3]);
        Files.write(session(r).resolveSibling("data"),new byte[]{1,2,3});
        statusCode(422,()->uploads.complete(r.uploadId,token)); assertTrue(records.isEmpty());
        put(r,0,new byte[3]); uploads.complete(r.uploadId,token);
    }

    @Test void lostDatabaseAcknowledgementCanBeRetriedWithTheSameJobId() throws Exception {
        UploadService.Request r=request(3);uploads.create(r,token);put(r,0,new byte[3]);
        java.util.concurrent.atomic.AtomicBoolean lost=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(a->{JobEntity e=a.getArgument(0);records.put(e.getId(),e);
            if(!lost.getAndSet(true))throw new IllegalStateException("commit succeeded but acknowledgement lost");return e;})
                .when(repo).saveAndFlush(any());
        assertThrows(IllegalStateException.class,()->uploads.complete(r.uploadId,token));
        String id=records.values().iterator().next().getId();
        assertEquals(id,uploads.complete(r.uploadId,token).get("jobId"));assertEquals(1,records.size());
        verify(executor,times(1)).execute(any(Runnable.class));
    }

    @Test void crashAfterDispatchDoesNotAutomaticallyRerunRecoveredFailedJob() throws Exception {
        UploadService.Request r=request(3);uploads.create(r,token);put(r,0,new byte[3]);
        Path blockedJournal=session(r).resolveSibling("session.next");
        doAnswer(a->{Files.createDirectory(blockedJournal);return null;}).when(executor).execute(any(Runnable.class));
        assertThrows(IOException.class,()->uploads.complete(r.uploadId,token));
        Files.delete(blockedJournal);
        JobEntity dispatched=records.values().iterator().next();
        when(repo.findTop100ByStatusIn(anyCollection())).thenReturn(List.of(dispatched)).thenReturn(Collections.emptyList());
        new JobRecovery(repo,jobs,multipart).afterPropertiesSet();
        assertEquals(JobEntity.Status.FAILED,dispatched.getStatus());
        uploads=new UploadService(jobs,multipart);
        assertEquals(dispatched.getId(),uploads.complete(r.uploadId,token).get("jobId"));
        verify(executor,times(1)).execute(any(Runnable.class));
        assertArrayEquals(new byte[3],Files.readAllBytes(Path.of(dispatched.getInputPath())));
    }

    @Test void concurrentDuplicatePartsAndCompletesCreateOneJob() throws Exception {
        UploadService.Request r=request(3); uploads.create(r,token);
        ExecutorService threads=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Map<String,Object>>> calls=new ArrayList<>();for(int i=0;i<4;i++)calls.add(()->put(r,0,new byte[3]));
            for(Future<?> f:threads.invokeAll(calls))f.get();
            calls.clear();for(int i=0;i<4;i++)calls.add(()->uploads.complete(r.uploadId,token));
            Set<Object> ids=new HashSet<>();for(Future<Map<String,Object>> f:threads.invokeAll(calls))ids.add(f.get().get("jobId"));
            assertEquals(1,ids.size());verify(executor,times(1)).execute(any(Runnable.class));
        }finally{threads.shutdownNow();}
    }

    @Test void expirationAndCancelNeverRemoveActiveOrCompletedJobInputs() throws Exception {
        UploadService.Request expired=request(3),active=request(3),done=request(3);
        for(UploadService.Request r:List.of(expired,active,done)){uploads.create(r,token);put(r,0,new byte[3]);}
        Map<String,Object> result=uploads.complete(done.uploadId,token);
        ObjectMapper json=new ObjectMapper();
        for(UploadService.Request r:List.of(expired,done)) {
            UploadService.Session s=json.readValue(session(r).toFile(),UploadService.Session.class);s.expiresAt=0;json.writeValue(session(r).toFile(),s);
        }
        uploads.cleanup(); statusCode(410,()->uploads.status(expired.uploadId,token));
        assertFalse(Files.exists(session(expired).resolveSibling("data")));
        assertTrue(Files.exists(session(active).resolveSibling("data")));
        assertTrue(Files.exists(Path.of(records.get(result.get("jobId")).getInputPath())));
        statusCode(409,()->uploads.cancel(done.uploadId,token));
        uploads.cancel(active.uploadId,token);statusCode(410,()->uploads.status(active.uploadId,token));
    }
}
