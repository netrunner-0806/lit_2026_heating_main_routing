package ru.lct.heatnet.validation;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.routing.ComplexityLimitException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class OutputReadBudgetTest {
    @Test void oversizedFeatureFailsBeforeFullMaterialization() {
        byte[] bytes=("{\"type\":\"FeatureCollection\",\"features\":[{\"geometry\":null,\"properties\":{\"notes\":\""+"x".repeat(1_000_000)+"\"}}]}").getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in=new ByteArrayInputStream(bytes);
        assertThrows(ComplexityLimitException.class,()->new OutputFeatureReader().readBounded(in,65536));
        assertTrue(in.available()>900_000,"reader must stop while the oversized token is still arriving");
    }
    @Test void smallResultSurvivesBoundedRead() throws Exception {
        var in=new ByteArrayInputStream("{\"type\":\"FeatureCollection\",\"features\":[{\"geometry\":null,\"properties\":{\"id\":1}}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1,new OutputFeatureReader().readBounded(in,65536).size());
    }
}
