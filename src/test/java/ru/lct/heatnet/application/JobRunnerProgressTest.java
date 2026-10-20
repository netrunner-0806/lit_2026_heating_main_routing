package ru.lct.heatnet.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.*;
import ru.lct.heatnet.solver.SolverConfig;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class JobRunnerProgressTest {
    @TempDir Path temp;
    @ParameterizedTest @EnumSource(SolverConfig.ExecutionMode.class)
    void persistsRealStagesSeriallyAndDoneOnlyAfterFileExists(SolverConfig.ExecutionMode mode) throws Exception {
        Path input=Files.copy(Path.of("src/test/resources/adversarial/06-multipolygon-oks.geojson"),temp.resolve("input.geojson"));
        var job=new JobEntity();job.setId(UUID.randomUUID().toString());job.setInputPath(input.toString());job.setMode("PLANAR");job.setMaxVariants(3);job.setUnknownRestrictionPolicy("IGNORE");job.setStatus(JobEntity.Status.QUEUED);
        var repo=mock(JobRepository.class);when(repo.claimQueued(eq(job.getId()),any())).thenAnswer(a->{job.setStatus(JobEntity.Status.RUNNING);job.setStartedAt(Instant.now());return 1;});
        when(repo.findById(job.getId())).thenReturn(Optional.of(job));
        var events=new CopyOnWriteArrayList<String>();var saving=new AtomicBoolean();var collision=new AtomicBoolean();
        when(repo.save(any())).thenAnswer(a->{
            if(!saving.compareAndSet(false,true))collision.set(true);
            try {events.add(job.getProgress());Thread.sleep(2);
                if(job.getStatus()==JobEntity.Status.DONE)assertTrue(Files.isRegularFile(Path.of(job.getResultPath())));
                else assertEquals(JobEntity.Status.RUNNING,job.getStatus());
                return job;
            }finally{saving.set(false);}
        });
        var props=new HeatnetProperties();props.setSolverExecutionMode(mode);
        new JobRunner(repo,props).run(job.getId());
        assertEquals(JobEntity.Status.DONE,job.getStatus(),job.getError());assertFalse(collision.get());
        assertEquals("Reading and validating input",events.get(0));
        assertTrue(events.stream().anyMatch(s->s.contains("mode="+mode)));
        assertTrue(events.stream().anyMatch(s->s.contains("completed=5;")));
        assertTrue(events.indexOf("Writing validated result")>events.indexOf("Validating"));
        assertTrue(events.get(events.size()-1).startsWith("Done:"));
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("heatnet-strategy")));
    }
}
