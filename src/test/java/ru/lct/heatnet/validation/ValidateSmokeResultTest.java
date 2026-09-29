package ru.lct.heatnet.validation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Validates whatever result file is given by -Dresult=... (default target/smoke-result.geojson) and prints the report. */
class ValidateSmokeResultTest {

    @Test
    void validateResultFile() throws Exception {
        Path result = Paths.get(System.getProperty("result", "target/smoke-result.geojson"));
        Assumptions.assumeTrue(TestData.correctedAvailable() && Files.exists(result));
        InputModel input = new GeoJsonStreamReader(ParseOptions.defaults()).read(TestData.CORRECTED);
        List<OutputFeature> features = new OutputFeatureReader().read(result);
        ValidationReport rep = new ResultValidator(input).validate(features);
        System.out.println("checks: " + rep.getChecksPerformed());
        for (ValidationIssue i : rep.getIssues()) System.out.println(i);
        System.out.println("VALID=" + rep.isValid() + " errors=" + rep.getErrorCount() + " warnings=" + rep.getWarningCount());
    }
}
