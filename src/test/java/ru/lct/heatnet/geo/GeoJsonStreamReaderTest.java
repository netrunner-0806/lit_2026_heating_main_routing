package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestData;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.domain.JsonId;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GeoJsonStreamReaderTest {

    @Test
    void readsCorrectedDataset() throws Exception {
        Assumptions.assumeTrue(TestData.correctedAvailable());
        InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(TestData.CORRECTED);
        assertEquals(144, m.featureCount());
        assertEquals(17, m.connectionPoints().size());
        assertEquals(88, m.restrictions().size());
        assertEquals(29, m.networkLines().size());
        assertEquals(9, m.chambers().size());
        assertEquals(1, m.sources().size());
        assertFalse(m.diagnostics().hasErrors(), m.diagnostics().messages().toString());
        assertTrue(m.connectionPoints().get(0).id().isNumeric());
        assertEquals(JsonId.ofNumber(1), m.connectionPoints().get(0).id());
        // metric geometry in UTM 37N
        assertTrue(m.sources().get(0).coordinate().x > 400_000 && m.sources().get(0).coordinate().x < 500_000);
    }

    @Test
    void preservesIdTypesAndReportsProblems() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":[" +
                "{\"type\":\"Feature\",\"properties\":{\"id\":\"cp-1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":10},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.75]}}," +
                "{\"type\":\"Feature\",\"properties\":{\"id\":7,\"object_type\":\"heat_network\",\"diameter\":300},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.75],[37.61,55.75]]}}," +
                "{\"type\":\"Feature\",\"properties\":{\"id\":8,\"object_type\":\"heat_network\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.76],[37.61,55.76]]}}," +
                "{\"type\":\"Feature\",\"properties\":{\"id\":9,\"object_type\":\"restriction\",\"restriction_type\":\"metro\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.6,55.7],[37.61,55.7],[37.61,55.71],[37.6,55.7]]]}}," +
                "{\"type\":\"Feature\",\"properties\":{\"id\":10,\"object_type\":\"spaceship\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.75]}}" +
                "]}";
        InputModel m = new GeoJsonStreamReader(ParseOptions.defaults()).read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(JsonId.ofString("cp-1"), m.connectionPoints().get(0).id());
        assertEquals(JsonId.ofNumber(7), m.networkLines().get(0).id());
        assertTrue(m.networkLines().get(1).diameterMissing());
        assertNull(m.restrictions().get(0).rule(), "unknown restriction type is ignored by default");
        assertTrue(m.diagnostics().messages().stream().anyMatch(x -> x.getCode().equals("UNKNOWN_RESTRICTION_TYPE")));
        assertTrue(m.diagnostics().messages().stream().anyMatch(x -> x.getCode().equals("UNKNOWN_OBJECT_TYPE")));
        assertTrue(m.diagnostics().messages().stream().anyMatch(x -> x.getCode().equals("MISSING_DIAMETER")));
        assertFalse(m.diagnostics().hasErrors());
        InputModel f = new GeoJsonStreamReader(new ParseOptions(ParseOptions.UnknownRestrictionPolicy.FORBIDDEN))
                .read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(f.restrictions().get(0).rule());
        assertTrue(f.restrictions().get(0).rule().isForbidden());
    }

    @Test
    void invalidJsonIsReported() {
        assertThrows(InputParseException.class, () -> new GeoJsonStreamReader(ParseOptions.defaults())
                .read(new ByteArrayInputStream("{\"type\": \"FeatureCollection\", \"features\": [{".getBytes(StandardCharsets.UTF_8))));
        assertThrows(InputParseException.class, () -> new GeoJsonStreamReader(ParseOptions.defaults())
                .read(new ByteArrayInputStream("[1,2,3]".getBytes(StandardCharsets.UTF_8))));
    }
}
