package ru.lct.heatnet.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.*;
import java.nio.file.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FailedJobInputRetentionTest {
    @TempDir Path root;
    @Test void failedCalculationRetainsInputAndDuplicateDeliveryCannotRunAgain()throws Exception{
        Path input=Files.writeString(root.resolve("input.geojson"),"{invalid json");
        JobEntity e=new JobEntity();e.setId("retained");e.setStatus(JobEntity.Status.RUNNING);
        e.setInputPath(input.toString());e.setUnknownRestrictionPolicy("IGNORE");
        JobRepository repo=mock(JobRepository.class);
        when(repo.claimQueued(eq("retained"),any())).thenReturn(1,0);
        when(repo.findById("retained")).thenReturn(Optional.of(e));
        JobRunner runner=new JobRunner(repo,new HeatnetProperties());runner.run("retained");runner.run("retained");
        assertEquals(JobEntity.Status.FAILED,e.getStatus());assertEquals(input.toString(),e.getInputPath());
        assertEquals("{invalid json",Files.readString(input));verify(repo,times(1)).findById("retained");
    }
}
