package ru.lct.heatnet.geo;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.routing.ComplexityLimitException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InputResourceBudgetTest {
    private static String feature(int id, String extra) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":" + id
                + ",\"object_type\":\"restriction\",\"restriction_type\":\"water\"" + extra
                + "},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37,55],[37.01,55],[37.01,55.01],[37,55]]]}}";
    }
    private static ByteArrayInputStream input(String features) {
        return new ByteArrayInputStream(("{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}").getBytes(StandardCharsets.UTF_8));
    }
    private static GeoJsonStreamReader reader(long model, long feature) {
        return new GeoJsonStreamReader(new ParseOptions(ParseOptions.UnknownRestrictionPolicy.IGNORE, model, feature));
    }
    @Test void accumulatedGeometryStopsWithResourceDiagnosisAndReaderCanBeReused() throws Exception {
        GeoJsonStreamReader r=reader(1000,65536);
        String s=feature(1,"")+","+feature(2,"");
        var ex=assertThrows(ComplexityLimitException.class,()->r.read(input(s)));
        assertTrue(ex.getMessage().contains("feature #1 id=2"),ex.getMessage());
        assertEquals(1,r.read(input(feature(1,""))).restrictions().size(),"Budget belongs to one read, not the reader instance");
    }
    @Test void oversizedFeatureIsRejectedBeforeTheWholeFeatureIsRead() {
        var in=input(feature(1,",\"description\":\""+"x".repeat(300000)+"\""));
        int before=in.available();
        var ex=assertThrows(ComplexityLimitException.class,()->reader(1L<<30,65536).read(in));
        assertTrue(ex.getMessage().contains("single-feature"),ex.getMessage());
        assertTrue(before-in.available()<80000,"Must stop the parser before materialising the giant DOM");
    }
    @Test void featureLimitIsPerObjectNotTotalFileAndRetainedBudgetExcludesUnusedText() throws Exception {
        StringBuilder features=new StringBuilder();
        for(int i=0;i<20;i++) { if(i>0)features.append(',');features.append(feature(i,",\"description\":\""+"я".repeat(10000)+"\"")); }
        assertEquals(20,reader(20000,30000).read(input(features.toString())).restrictions().size());
    }
    @Test void prefetchedSmallFeatureStillGetsExactLengthCheck() {
        var ex=assertThrows(ComplexityLimitException.class,()->reader(1L<<30,100).read(input(feature(1,""))));
        assertTrue(ex.getMessage().contains("single-feature"));
    }
    @Test void unknownMetadataCannotGrowWithoutBound() {
        StringBuilder features=new StringBuilder();
        for(int i=0;i<100;i++) { if(i>0)features.append(',');features.append("{\"properties\":{\"object_type\":\"unknown-").append(i).append("\"}}"); }
        var ex=assertThrows(ComplexityLimitException.class,()->reader(2048,65536).read(input(features.toString())));
        assertTrue(ex.getMessage().contains("unknown object type"));
    }
}
