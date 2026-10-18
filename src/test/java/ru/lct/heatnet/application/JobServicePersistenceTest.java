package ru.lct.heatnet.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.JobEntity;
import ru.lct.heatnet.persistence.JobRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobServicePersistenceTest {
    @TempDir Path root;
    JobRepository repo;
    ThreadPoolTaskExecutor executor;
    JobService service;
    Map<String, JobEntity> records;

    @BeforeEach void setup() {
        repo = mock(JobRepository.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        records = new HashMap<>();
        when(repo.saveAndFlush(any())).thenAnswer(a -> {
            JobEntity e = a.getArgument(0); records.put(e.getId(), e); return e;
        });
        when(repo.findById(anyString())).thenAnswer(a -> Optional.ofNullable(records.get(a.getArgument(0))));
        HeatnetProperties props = new HeatnetProperties();
        props.setStorageDir(root.resolve("jobs").toString());
        service = new JobService(repo, mock(JobRunner.class), props, executor);
    }

    private MultipartFile upload() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(4L);
        when(file.getOriginalFilename()).thenReturn("input.geojson");
        return file;
    }

    private void assertCleaned() throws IOException {
        Path jobs = root.resolve("jobs");
        if (Files.exists(jobs)) try (java.util.stream.Stream<Path> paths = Files.list(jobs)) {
            assertEquals(0, paths.count(), "no partial file or orphan directory");
        }
        for (JobEntity e : records.values()) {
            assertEquals(JobEntity.Status.FAILED, e.getStatus());
            assertNull(e.getInputPath());
        }
        verify(executor, never()).submit(any(Runnable.class));
    }

    @Test void movesServletSpoolWithoutReadingOrCopyingItAgain() throws Exception {
        Path spool = Files.write(root.resolve("spool"), new byte[]{1, 2, 3, 4});
        MultipartFile file = upload();
        doAnswer(a -> {
            assertEquals(1, records.size(), "job already visible before moving upload");
            assertEquals("Saving upload", records.values().iterator().next().getProgress());
            Files.move(spool, ((File) a.getArgument(0)).toPath()); return null;
        }).when(file).transferTo(any(File.class));
        JobEntity job = service.submit(file, null, 3, "PLANAR");
        assertFalse(Files.exists(spool));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(Path.of(job.getInputPath())));
        assertFalse(Files.exists(Path.of(job.getInputPath()).resolveSibling("input.part")));
        verify(file, never()).getInputStream();
        verify(executor).execute(any(Runnable.class));
        assertEquals(JobEntity.Status.QUEUED, job.getStatus());
    }

    @Test void diskFullDuringTransferCleansPartialAndFailsJob() throws Exception {
        MultipartFile file = upload();
        doAnswer(a -> {
            Files.write(((File) a.getArgument(0)).toPath(), new byte[]{1});
            throw new IOException("No space left on device");
        }).when(file).transferTo(any(File.class));
        assertThrows(IOException.class, () -> service.submit(file, null, 3, "PLANAR"));
        assertCleaned();
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test void truncatedTransferCannotBeEnqueued() throws Exception {
        MultipartFile file = upload();
        doAnswer(a -> { Files.write(((File) a.getArgument(0)).toPath(), new byte[]{1}); return null; })
                .when(file).transferTo(any(File.class));
        assertThrows(IOException.class, () -> service.submit(file, null, 3, "PLANAR"));
        assertCleaned();
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test void dbFailureBeforePersistenceDoesNotMoveUpload() throws Exception {
        MultipartFile file = upload();
        doThrow(new IllegalStateException("DB unavailable")).when(repo).saveAndFlush(any());
        assertThrows(IllegalStateException.class, () -> service.submit(file, null, 3, "PLANAR"));
        verify(file, never()).transferTo(any(File.class));
        verify(executor, never()).execute(any(Runnable.class));
        assertCleaned();
    }

    @Test void dbFailureAfterPersistenceCleansFileAndFailsJob() throws Exception {
        MultipartFile file = upload();
        doAnswer(a -> { Files.write(((File) a.getArgument(0)).toPath(), new byte[4]); return null; })
                .when(file).transferTo(any(File.class));
        AtomicInteger saves = new AtomicInteger();
        doAnswer(a -> {
            if (saves.incrementAndGet() == 2) throw new IllegalStateException("DB save failed");
            JobEntity e = a.getArgument(0); records.put(e.getId(), e); return e;
        }).when(repo).saveAndFlush(any());
        assertThrows(IllegalStateException.class, () -> service.submit(file, null, 3, "PLANAR"));
        assertCleaned();
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test void rejectedQueueDoesNotLeaveQueuedJobOrFile() throws Exception {
        MultipartFile file = upload();
        doAnswer(a -> { Files.write(((File) a.getArgument(0)).toPath(), new byte[4]); return null; })
                .when(file).transferTo(any(File.class));
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        assertThrows(TaskRejectedException.class, () -> service.submit(file, null, 3, "PLANAR"));
        assertCleaned();
    }

    @Test void malformedVariantParameterReturnsHttp400() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ru.lct.heatnet.api.JobController(service))
                .setControllerAdvice(new ru.lct.heatnet.api.ApiExceptionHandler()).build();
        for(String value:new String[]{"0","-1","4","abc","1.5","999999999999"}) {
            var file=new org.springframework.mock.web.MockMultipartFile("file","test.geojson","application/geo+json",new byte[]{1});
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/jobs")
                    .file(file).param("maxVariants",value)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        verifyNoInteractions(repo);
    }

    @Test void invalidVariantCountsRejectedBeforePersistence() {
        for (int count : new int[]{-1,0,4,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> service.submit(upload(), null, count, "PLANAR"));
        verifyNoInteractions(repo);
    }

    @Test void configuredDefaultCannotExceedThree() throws Exception {
        HeatnetProperties props=new HeatnetProperties();props.setMaxVariants(100);props.setStorageDir(root.toString());
        JobService configured=new JobService(repo,mock(JobRunner.class),props,executor);
        MultipartFile file=upload();
        doAnswer(a->{Files.write(((File)a.getArgument(0)).toPath(),new byte[4]);return null;}).when(file).transferTo(any(File.class));
        assertEquals(3,configured.submit(file,null,null,"PLANAR").getMaxVariants());
    }

    @Test void invalidModeCreatesNeitherRecordNorFile() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.submit(upload(), null, 3, "invalid"));
        verify(repo, never()).saveAndFlush(any());
        assertCleaned();
    }

    @Test void rerunSharesImmutableInputAndSourceDeletionDoesNotLoseIt() throws Exception {
        Path original = Files.write(root.resolve("original.geojson"), new byte[]{1,2,3,4});
        JobEntity source = new JobEntity(); source.setId("source"); source.setInputPath(original.toString());
        source.setUnknownRestrictionPolicy("IGNORE"); source.setMaxVariants(3);
        when(repo.findById("source")).thenReturn(Optional.of(source));
        var rerun = service.rerun("source", "DEPTH");
        Path retained = Path.of(rerun.getInputPath());
        assertTrue(Files.isSameFile(original, retained), "rerun must not duplicate a multi-GB immutable input");
        Files.delete(original);
        assertArrayEquals(new byte[]{1,2,3,4}, Files.readAllBytes(retained));
    }

    @Test void insufficientStorageRejectsBeforeTransferringOrEnqueueing() throws Exception {
        MultipartFile file = upload(); when(file.getSize()).thenReturn(Long.MAX_VALUE);
        var ex = assertThrows(ru.lct.heatnet.api.UploadException.class, () -> service.submit(file,null,3,"PLANAR"));
        assertEquals(507,ex.status());
        verify(file,never()).transferTo(any(File.class));verify(executor,never()).execute(any(Runnable.class));
        assertTrue(records.isEmpty());
    }

    @Test void saturatedQueueRejectsBeforeTransfer() throws Exception {
        var pool=new ThreadPoolTaskExecutor();pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(1);pool.initialize();
        var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);
        try {
            pool.execute(()->{started.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));pool.execute(()->{});
            var props=new HeatnetProperties();props.setStorageDir(root.resolve("jobs").toString());
            var full=new JobService(repo,mock(JobRunner.class),props,pool);var file=upload();
            assertThrows(TaskRejectedException.class,()->full.submit(file,null,3,"PLANAR"));
            verify(file,never()).transferTo(any(File.class));assertTrue(records.isEmpty());
        } finally { release.countDown();pool.shutdown(); }
    }

    @Test void rerunFailureDoesNotDeleteSourceInput() throws Exception {
        Path original = Files.write(root.resolve("original.geojson"), new byte[4]);
        JobEntity source = new JobEntity();
        source.setId("source"); source.setInputPath(original.toString()); source.setUnknownRestrictionPolicy("IGNORE");
        when(repo.findById("source")).thenReturn(Optional.of(source));
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        assertThrows(TaskRejectedException.class, () -> service.rerun("source", "DEPTH"));
        assertTrue(Files.isRegularFile(original));
        assertCleaned();
    }
}
