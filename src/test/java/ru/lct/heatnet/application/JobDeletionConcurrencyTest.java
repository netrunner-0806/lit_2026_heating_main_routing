package ru.lct.heatnet.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ru.lct.heatnet.api.ConflictException;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest @ActiveProfiles("test")
class JobDeletionConcurrencyTest {
    @Autowired JobRepository database;
    @TempDir Path storage;
    private HeatnetProperties props() { HeatnetProperties p=new HeatnetProperties();p.setStorageDir(storage.toString());p.setImprove(false);return p; }
    private JobEntity job(JobEntity.Status status) throws Exception {
        JobEntity j=new JobEntity();j.setId(UUID.randomUUID().toString());j.setStatus(status);j.setCreatedAt(Instant.now());
        Path dir=Files.createDirectories(storage.resolve(j.getId()));
        // Invalid input deliberately gives a fast, real FAILED transition after the synchronization barrier.
        Path input=Files.writeString(dir.resolve("input.geojson"),"{}");
        j.setInputPath(input.toString());j.setUnknownRestrictionPolicy("IGNORE");j.setMaxVariants(1);
        return database.saveAndFlush(j);
    }
    @Test void runningDeletionIsConflictAndRealRunnerCannotResurrectDeletedJob() throws Exception {
        JobEntity j=job(JobEntity.Status.QUEUED), other=job(JobEntity.Status.DONE);
        CountDownLatch claimed=new CountDownLatch(1), resume=new CountDownLatch(1);
        JobRepository runnerRepo=mock(JobRepository.class,withSettings().defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(database)));
        doAnswer(call -> { var result=database.findById(j.getId());claimed.countDown();
            assertTrue(resume.await(10,TimeUnit.SECONDS));return result; }).when(runnerRepo).findById(j.getId());
        JobRunner runner=new JobRunner(runnerRepo,props());
        JobService service=new JobService(database,runner,props(),mock(ThreadPoolTaskExecutor.class));
        ExecutorService executor=Executors.newSingleThreadExecutor();
        Future<?> running=executor.submit(() -> runner.run(j.getId()));
        try {
            assertTrue(claimed.await(10,TimeUnit.SECONDS));
            assertThrows(ConflictException.class, () -> service.delete(j.getId()));
            assertTrue(Files.exists(Path.of(j.getInputPath())));
        } finally { resume.countDown();running.get(10,TimeUnit.SECONDS);executor.shutdownNow(); }
        assertEquals(JobEntity.Status.FAILED,database.findById(j.getId()).orElseThrow().getStatus());
        service.delete(j.getId());service.delete(j.getId());runner.run(j.getId());
        assertFalse(database.existsById(j.getId()));assertFalse(Files.exists(Path.of(j.getInputPath())));
        assertTrue(database.existsById(other.getId()));assertTrue(Files.exists(Path.of(other.getInputPath())));
        service.delete(other.getId());
    }
    @Test void queuedDeletionIsExplicitConflictBeforeWorkerClaimsIt() throws Exception {
        JobEntity j=job(JobEntity.Status.QUEUED);
        JobRunner runner=new JobRunner(database,props());
        JobService service=new JobService(database,runner,props(),mock(ThreadPoolTaskExecutor.class));
        assertThrows(ConflictException.class, () -> service.delete(j.getId()));
        runner.run(j.getId());assertEquals(JobEntity.Status.FAILED,database.findById(j.getId()).orElseThrow().getStatus());
        service.delete(j.getId());assertFalse(database.existsById(j.getId()));
    }
    @Test void concurrentTerminalSaveAndDeleteCannotRecreateRow() throws Exception {
        JobEntity j=job(JobEntity.Status.QUEUED);
        CountDownLatch ready=new CountDownLatch(1),finish=new CountDownLatch(1);
        JobRepository runnerRepo=mock(JobRepository.class,withSettings().defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(database)));
        doAnswer(call->{JobEntity e=call.getArgument(0);if(e.getStatus()==JobEntity.Status.FAILED){ready.countDown();assertTrue(finish.await(10,TimeUnit.SECONDS));}
            return database.save(e);}).when(runnerRepo).save(any(JobEntity.class));
        JobRunner runner=new JobRunner(runnerRepo,props());
        JobService service=new JobService(database,runner,props(),mock(ThreadPoolTaskExecutor.class));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        Future<?> running=executor.submit(()->runner.run(j.getId()));
        try {
            assertTrue(ready.await(10,TimeUnit.SECONDS));
            Future<?> deletion=executor.submit(()->{finish.countDown();try{service.delete(j.getId());}catch(ConflictException expected){}return null;});
            deletion.get(10,TimeUnit.SECONDS);running.get(10,TimeUnit.SECONDS);
            service.delete(j.getId());runner.run(j.getId());
            assertFalse(database.existsById(j.getId()));assertFalse(Files.exists(Path.of(j.getInputPath())));
        } finally {finish.countDown();executor.shutdownNow();}
    }
}
