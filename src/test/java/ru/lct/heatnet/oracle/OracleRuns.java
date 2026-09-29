package ru.lct.heatnet.oracle;

import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.validation.SolverVariantValidator;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Production runs shared by the oracle and integrity tests (each input is solved once per mode per JVM). */
final class OracleRuns {

    private OracleRuns() {}

    static final class Run {
        final String name;
        final boolean depth;
        final InputModel input;
        final SolveResult result;
        final List<OutputFeature> features;

        Run(String name, boolean depth, InputModel input, SolveResult result, List<OutputFeature> features) {
            this.name = name; this.depth = depth; this.input = input; this.result = result; this.features = features;
        }

        @Override public String toString() { return name + (depth ? " [DEPTH]" : " [PLANAR]"); }
    }

    private static final Map<String, Run> CACHE = new LinkedHashMap<>();

    static synchronized Run run(String name, boolean depth) {
        String key = name + "|" + depth;
        Run r = CACHE.get(key);
        if (r == null) {
            InputModel in = input(name);
            SolverConfig cfg = SolverConfig.defaults();
            cfg.depthMode = depth;
            SolveResult res = new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
            List<OutputFeature> fs = new ArrayList<>();
            OutputBuilder b = new OutputBuilder();
            for (VariantResult v : res.variants()) fs.addAll(b.build(v));
            r = new Run(name, depth, in, res, fs);
            CACHE.put(key, r);
        }
        return r;
    }

    static InputModel input(String name) {
        try {
            if ("corrected".equals(name)) return new GeoJsonStreamReader(ParseOptions.defaults()).read(TestData.CORRECTED);
            if ("id-types".equals(name)) return idTypes();
            try (InputStream in = OracleRuns.class.getResourceAsStream("/fixtures/" + name + ".geojson")) {
                if (in == null) throw new IllegalArgumentException("fixture " + name + " not found");
                return new GeoJsonStreamReader(ParseOptions.defaults()).read(in);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Numeric chamber id 7 next to a consumer with the STRING id "7", plus a numeric consumer 8 (id type preservation). */
    private static InputModel idTypes() {
        return new SyntheticInput()
                .source(1, -50, 0)
                .line(100, 300, -50, 0, 250, 0)
                .chamber(7, 100, 0)
                .rect(1, "oks", 90, 40, 110, 60).point("7", 100, 50, 10.0)
                .rect(2, "oks", 190, 40, 210, 60).point(8, 200, 50, 5.0)
                .build();
    }

    /** All runs of the conformance matrix (the corrected dataset only when the file is present). */
    static List<Run> all() {
        List<Run> out = new ArrayList<>();
        out.add(run("synthetic-basic", false));
        out.add(run("synthetic-shared-trunk", false));
        out.add(run("synthetic-unreachable", false));
        out.add(run("id-types", false));
        out.add(run("depth-demo", false));
        out.add(run("depth-demo", true));
        if (TestData.correctedAvailable()) {
            out.add(run("corrected", false));
            out.add(run("corrected", true));
        }
        return Collections.unmodifiableList(out);
    }
}
