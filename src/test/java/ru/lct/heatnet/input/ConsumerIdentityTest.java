package ru.lct.heatnet.input;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.lct.heatnet.input.MalformedInputTest.*;

class ConsumerIdentityTest {
    @Test void missingConsumerIdIsNotJustSkipped() throws Exception {
        assertTrue(read(fc(NET,POINT,POINT.replace("\"id\":1,",""))).diagnostics().hasErrors());
    }
    @Test void duplicateConsumerIdIsNotAmbiguous() throws Exception {
        assertTrue(read(fc(NET,POINT,POINT)).diagnostics().hasErrors());
    }
    @Test void multiPointConsumerIsNotLost() throws Exception {
        assertTrue(read(fc(NET,POINT,feature("{\"id\":2,\"object_type\":\"oks_connection_point\",\"flow_tph\":1}",
                "{\"type\":\"MultiPoint\",\"coordinates\":[[37.6,55.7],[37.61,55.71]]}"))).diagnostics().hasErrors());
    }
    @Test void nullGeometryConsumerIsNotLost() throws Exception {
        assertTrue(read(fc(NET,POINT,feature("{\"id\":2,\"object_type\":\"oks_connection_point\",\"flow_tph\":1}","null"))).diagnostics().hasErrors());
    }
}
