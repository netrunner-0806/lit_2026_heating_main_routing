package ru.lct.heatnet.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.*;
import ru.lct.heatnet.testkit.SolveKit;
import ru.lct.heatnet.validation.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone stress runner: one input/mode per bounded JVM; writes evidence even on controlled failure. */
public final class StressMain {
    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[0]), output = Paths.get(args[2]);
        Files.createDirectories(output.getParent());
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("input", input.getFileName().toString()); report.put("mode", args[1]);
        report.put("input_sha256", sha(input));
        report.put("build_sha256", buildHash());
        AtomicLong peak = new AtomicLong();
        Thread sample = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        });
        sample.setDaemon(true); sample.start(); long start = System.nanoTime();
        try {
            InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(input);
            report.put("features", m.featureCount()); report.put("restrictions", m.restrictions().size());
            report.put("consumers", m.connectionPoints().size());
            if (m.diagnostics().hasErrors()) throw new IllegalArgumentException("INVALID_INPUT: " + m.diagnostics().errors());
            SolverConfig cfg = SolverConfig.defaults(); cfg.depthMode = "DEPTH".equals(args[1]);
            SolveResult result = new HeatnetSolver(cfg, new SolverVariantValidator(m)).solve(m);
            List<OutputFeature> fs = SolveKit.render(result);
            ValidationReport validation = new ResultValidator(m).validate(SolveKit.roundTrip(fs));
            report.put("valid", validation.isValid()); report.put("errors", validation.getErrorCount());
            report.put("warnings", validation.getWarningCount()); report.put("issues", validation.getIssues());
            report.put("metrics", result.metrics()); report.put("diagnostics", result.diagnostics().messages());
            List<Map<String,Object>> variants = new ArrayList<>();
            for (VariantResult v : result.variants()) {
                Map<String,Object> x = new LinkedHashMap<>();x.put("connected",v.connectedCount());
                x.put("score",v.summary().score());x.put("cost",v.summary().calculatedCost());
                x.put("new_length_m",v.summary().newLength());x.put("new_chambers",v.network().newChambers().size());
                Map<String,String> unconnected=new LinkedHashMap<>(); v.unconnected().forEach((cp,reason)->unconnected.put(cp.id().text(),reason));
                x.put("unconnected",unconnected);x.put("notes",v.notes());variants.add(x);
            }
            report.put("variants",variants);
            List<String> oracle = ru.lct.heatnet.oracle.StressEngineeringOracle.check(m, fs, cfg.depthMode);
            report.put("engineering_oracle_issues", oracle);
            if (!oracle.isEmpty()) throw new IllegalStateException("ENGINEERING_ORACLE: " + oracle);
            if (!validation.isValid()) throw new IllegalStateException("INVALID_OUTPUT: " + validation.getIssues());
            report.put("status", result.variants().isEmpty() ? "FAILED" : "DONE");
            new GeoJsonResultWriter().write(output,fs);
            report.put("output_sha256",sha(Files.readAllBytes(output)));
        } catch (Exception ex) {
            report.put("status","FAILED");report.put("exception",ex.toString());ex.printStackTrace();
        } finally {
            sample.interrupt();sample.join();
            report.put("wall_ms",(System.nanoTime()-start)/1_000_000);report.put("sampled_peak_heap_bytes",peak.get());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Paths.get(output+".report.json").toFile(),report);
            System.out.println("STRESS_RESULT "+new ObjectMapper().writeValueAsString(report));
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();
    }
    private static String sha(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] block=new byte[64*1024];
        try(java.io.InputStream in=Files.newInputStream(path)) {
            for(int n;(n=in.read(block))>=0;)if(n>0)digest.update(block,0,n);
        }
        StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format("%02x",b&255));return s.toString();
    }
    private static String buildHash() throws Exception {
        Path location=Paths.get(HeatnetSolver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isDirectory(location)) return sha(Files.readAllBytes(location));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var files=Files.walk(location)) {
            for(Path p:(Iterable<Path>)files.filter(f->f.toString().endsWith(".class")).sorted()::iterator) {
                digest.update(location.relativize(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(Files.readAllBytes(p));
            }
        }
        StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format("%02x",b&255));return s.toString();
    }
}
