package ru.lct.heatnet.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.lct.heatnet.SyntheticInput;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.SolveResult;
import ru.lct.heatnet.solver.SolverConfig;
import ru.lct.heatnet.solver.VariantResult;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.SolverVariantValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-file tests (PHASE 28): small deterministic scenes whose canonical output (see {@link CanonicalOutput}) is
 * compared with {@code src/test/resources/golden/<case>.expected.json}. Catches accidental changes of the output
 * contract or of solver decisions. Regenerate the expectations after an intended change with
 * {@code mvn test -Dtest=GoldenFileTest -Dheatnet.golden.update=true}.
 */
class GoldenFileTest {

    static final class Case {
        final String name; final Supplier<InputModel> input; final boolean depth;
        Case(String name, boolean depth, Supplier<InputModel> input) { this.name = name; this.depth = depth; this.input = input; }
        @Override public String toString() { return name; }
    }

    static Stream<Case> cases() {
        return Stream.of(
                // one consumer straight above the existing line: direct route, one new chamber on the line
                new Case("simple-direct", false, () -> new SyntheticInput()
                        .line(1, 400, 0, 0, 200, 0)
                        .rect(10, "oks", 80, 60, 120, 80).point("A", 100, 70, 10.0)
                        .build()),
                // a forbidden park between the consumer and the line forces a detour
                new Case("one-obstacle", false, () -> new SyntheticInput()
                        .line(1, 400, 0, 0, 200, 0)
                        .rect(10, "oks", 80, 60, 120, 80).point("A", 100, 70, 10.0)
                        .rect(11, "park", 60, 20, 140, 45)
                        .build()),
                // two neighbours share a trunk with a junction chamber
                new Case("shared-branch", false, () -> new SyntheticInput()
                        .line(1, 400, 0, 0, 400, 0)
                        .rect(2, "oks", 0, 100, 170, 340).rect(3, "oks", 250, 100, 400, 340)
                        .rect(4, "oks", 180, 300, 200, 320).point("A", 190, 310, 20.0)
                        .rect(5, "oks", 220, 300, 240, 320).point("B", 230, 310, 20.0)
                        .build()),
                // a gas pipeline between the consumer and the line: one special crossing with technical nodes
                new Case("one-special-crossing", false, () -> new SyntheticInput()
                        .line(1, 400, 0, 0, 200, 0)
                        .polyline(2, "gas_pipeline", 0, 30, 200, 30)
                        .rect(10, "oks", 80, 60, 120, 80).point("A", 100, 70, 10.0)
                        .build()),
                // the same scene in depth mode: numeric depths, a plateau above/below the pipeline
                new Case("one-depth-crossing", true, () -> new SyntheticInput()
                        .line(1, 400, 0, 0, 200, 0)
                        .polyline(2, "gas_pipeline", 0, 30, 200, 30)
                        .rect(10, "oks", 80, 60, 120, 80).point("A", 100, 70, 10.0)
                        .build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void outputMatchesGoldenFile(Case c) throws Exception {
        InputModel in = c.input.get();
        SolverConfig cfg = SolverConfig.defaults();
        cfg.depthMode = c.depth;
        SolveResult r = new HeatnetSolver(cfg, new SolverVariantValidator(in)).solve(in, HeatnetSolver.defaultStrategies(), null);
        assertFalse(r.variants().isEmpty(), r.diagnostics().messages().toString());
        List<OutputFeature> fs = new ArrayList<>();
        OutputBuilder b = new OutputBuilder();
        for (VariantResult v : r.variants()) fs.addAll(b.build(v));
        ValidationReport rep = new ResultValidator(in).validate(fs);
        assertTrue(rep.isValid(), rep.getIssues().toString());
        assertEquals(in.connectionPoints().size(), r.variants().get(0).connectedCount(), "golden scenes are fully connectable");

        ArrayNode actual = CanonicalOutput.canonical(fs);
        String actualText = CanonicalOutput.MAPPER.writeValueAsString(actual);
        if (Boolean.getBoolean("heatnet.golden.update")) {
            Path out = Paths.get("src", "test", "resources", "golden", c.name + ".expected.json");
            Files.createDirectories(out.getParent());
            Files.write(out, actualText.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream is = GoldenFileTest.class.getResourceAsStream("/golden/" + c.name + ".expected.json")) {
            assertNotNull(is, "missing golden file for " + c.name + " (run with -Dheatnet.golden.update=true)");
            String expectedText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode expected = CanonicalOutput.MAPPER.readTree(expectedText);
            JsonNode actualTree = CanonicalOutput.MAPPER.readTree(actualText); // same number representation on both sides
            if (!expected.equals(actualTree)) {
                fail("golden output of '" + c.name + "' changed:\n" + CanonicalOutput.diff(CanonicalOutput.MAPPER.writeValueAsString(expected), actualText));
            }
        }
    }
}
