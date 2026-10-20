package ru.lct.heatnet.application;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.*;
import ru.lct.heatnet.solver.SolverConfig;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** The real JobRunner, default profiles, real Memory Guard, final validation and on-disk publication.
 * A fresh bounded JVM makes admission independent of garbage left by other regression classes.
 */
public class ParallelProductionPathTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings={"PLANAR","DEPTH"})
    void runtimeConfiguredModesMatchCompetitionGoldenAndExplanation(String mode)throws Exception {
        Path log=temp.resolve(mode+".log");
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xms256m","-Xmx4g","-XX:+UseG1GC","-XX:ActiveProcessorCount=4",
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                getClass().getName(),mode,temp.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended=child.waitFor(6,TimeUnit.MINUTES);
        if(!ended){child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS))child.destroyForcibly().waitFor();}
        assertTrue(ended,"production-path regression timed out: "+log);
        assertEquals(0,child.exitValue(),()->{try{return Files.readString(log);}catch(Exception e){return e.toString();}});
    }
    public static void main(String[] args)throws Exception {
        String mode=args[0];Path root=Path.of(args[1]);ObjectMapper mapper=new ObjectMapper();
        JsonNode expectedExplain=null,expectedDiagnostics=null;
        for(SolverConfig.ExecutionMode execution:SolverConfig.ExecutionMode.values()) {
            Path dir=Files.createDirectories(root.resolve(execution.name()));
            Path input=Files.copy(Path.of("src/test/resources/fixtures/competition-corrected.geojson"),dir.resolve("input.geojson"));
            JobEntity job=new JobEntity();job.setId(UUID.randomUUID().toString());job.setStatus(JobEntity.Status.QUEUED);
            job.setInputPath(input.toString());job.setMaxVariants(3);job.setMode(mode);job.setUnknownRestrictionPolicy("IGNORE");
            JobRepository repo=mock(JobRepository.class);
            when(repo.claimQueued(eq(job.getId()),any())).thenReturn(1);
            when(repo.findById(job.getId())).thenReturn(Optional.of(job));when(repo.save(any())).thenAnswer(a->a.getArgument(0));
            HeatnetProperties props=new HeatnetProperties();props.setSolverExecutionMode(execution);props.setWorkers(1);
            new JobRunner(repo,props).run(job.getId());
            assertEquals(JobEntity.Status.DONE,job.getStatus(),job.getError());assertEquals(3,job.getVariantCount());
            JsonNode summary=mapper.readTree(job.getSummaryJson());
            assertEquals(17,summary.get(0).path("connectedOksCount").asInt());
            assertEquals(13.433636792,summary.get(0).path("score").asDouble(),1e-9);
            assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/golden/competition-"+mode.toLowerCase(Locale.ROOT)+".geojson")),
                    Files.readAllBytes(Path.of(job.getResultPath())));
            JsonNode validation=mapper.readTree(job.getValidationJson());
            assertTrue(validation.path("valid").asBoolean());assertEquals(0,validation.path("errors").asInt());
            assertEquals(0,validation.path("warnings").asInt());
            JsonNode explain=mapper.readTree(job.getExplainJson());
            JsonNode diagnostics=mapper.readTree(job.getDiagnosticsJson());
            // The sole nondeterministic diagnostic contains elapsed time.
            var normalized=mapper.createArrayNode();
            for(JsonNode d:diagnostics)if(!d.path("code").asText().equals("SOLVE_SUMMARY"))normalized.add(d);
            if(expectedExplain==null){expectedExplain=explain;expectedDiagnostics=normalized;}
            else{assertEquals(expectedExplain,explain);assertEquals(expectedDiagnostics,normalized);}
            if(execution==SolverConfig.ExecutionMode.PARALLEL_2) {
                JsonNode metrics=mapper.readTree(job.getMetricsJson());
                assertEquals("NONE",metrics.path("parallel_fallback").asText());
                assertEquals(2,metrics.path("parallel_memory_guard").path("peak_active_strategies").asInt());
                assertEquals("ADMIT",metrics.path("parallel_memory_guard").path("decisions").get(0).path("decision").asText());
            }
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()
                    &&(t.getName().equals("heatnet-strategy")||t.getName().equals("heatnet-memory-guard"))));
        }
    }
}
