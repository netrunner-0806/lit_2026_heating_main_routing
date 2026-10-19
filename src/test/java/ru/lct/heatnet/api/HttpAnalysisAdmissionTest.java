package ru.lct.heatnet.api;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.lct.heatnet.application.JobRunner;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpAnalysisAdmissionTest {
    @Test void onlyOneHeavyValidationMayMaterializeFilesAtOnce() throws Exception {
        byte[] bytes=Files.readAllBytes(Paths.get("src/test/resources/fixtures/depth-demo.geojson"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger reads=new AtomicInteger();
        MockMultipartFile input=new MockMultipartFile("input","input.geojson","application/json",bytes) {
            @Override public InputStream getInputStream() throws IOException {
                if(reads.incrementAndGet()==1) {entered.countDown();try {if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new IOException(e);}}
                return super.getInputStream();
            }
        };
        var result=new MockMultipartFile("result","result.geojson","application/json","{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes());
        var controller=new ValidationController(mock(JobRunner.class));ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> first=pool.submit(()->{try{controller.validate(input,result,null);}catch(IOException ex){throw new UncheckedIOException(ex);}});
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class,()->controller.validate(input,result,null));
            assertEquals(1,reads.get(),"second request must be rejected before materializing geometry");
        } finally {release.countDown();first.get(15,TimeUnit.SECONDS);pool.shutdownNow();}
        assertDoesNotThrow(()->controller.validate(input,result,null));
    }
}
