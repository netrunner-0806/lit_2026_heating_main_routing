package ru.lct.heatnet.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.output.GeoJsonResultWriter;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CompetitionBaselineTest {
    @Test void bothModesRemainByteIdenticalToFrozenCompetitionResults() throws Exception {
        Assumptions.assumeTrue(TestData.correctedAvailable(), "set -Dheatnet.corrected.input to the corrected competition dataset");
        for(boolean depth:new boolean[]{false,true}) {
            OracleRuns.Run run=OracleRuns.run("corrected",depth);
            assertEquals(17,run.result.variants().get(0).connectedCount());
            assertEquals(13.433636792,run.result.variants().get(0).summary().score(),1e-9);
            ByteArrayOutputStream out=new ByteArrayOutputStream();new GeoJsonResultWriter().write(out,run.features);
            assertArrayEquals(Files.readAllBytes(Paths.get("examples/stress/baseline",depth?"depth.geojson":"planar.geojson")),out.toByteArray(),"frozen competition output "+depth);
        }
    }
}
