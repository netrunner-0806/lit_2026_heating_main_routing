package ru.lct.heatnet.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import ru.lct.heatnet.config.*;
import ru.lct.heatnet.persistence.JobRepository;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedJobQueueTest {
    @TempDir Path root;
    @Test void oneRunningJobAndFullQueueRejectBeforeWritingInput()throws Exception {
        var props=new HeatnetProperties();props.setWorkers(1);props.setStorageDir(root.resolve("jobs").toString());
        var executor=new AppConfig().jobExecutor(props);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var repo=mock(JobRepository.class);var runner=mock(JobRunner.class);
        var service=new JobService(repo,runner,props,executor);
        try {
            executor.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=0;i<1000;i++)executor.execute(()->{});
            assertEquals(1,executor.getActiveCount());assertEquals(1000,executor.getThreadPoolExecutor().getQueue().size());
            var file=new MockMultipartFile("file","test.geojson","application/geo+json",new byte[]{1});
            assertThrows(TaskRejectedException.class,()->service.submit(file,null,3,"PLANAR"));
            verifyNoInteractions(repo,runner);assertFalse(Files.exists(root.resolve("jobs")));
        }finally {
            release.countDown();executor.getThreadPoolExecutor().shutdown();
            assertTrue(executor.getThreadPoolExecutor().awaitTermination(5,TimeUnit.SECONDS));executor.destroy();
        }
    }
}
