package ru.lct.heatnet.input;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.lct.heatnet.api.ValidationController;
import ru.lct.heatnet.application.JobRunner;
import ru.lct.heatnet.geo.InputParseException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ValidationInputAuditTest {
    @Test void validationEndpointCannotValidateAnInputWithLostConsumer() throws Exception {
        String json="{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"object_type\":\"oks_connection_point\",\"id\":\"lost\",\"flow_tph\":1}}]}";
        var input=new MockMultipartFile("input","bad.geojson","application/geo+json",json.getBytes(StandardCharsets.UTF_8));
        var result=new MockMultipartFile("result","empty.geojson","application/geo+json","{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8));
        JobRunner runner=mock(JobRunner.class);
        assertThrows(InputParseException.class,()->new ValidationController(runner).validate(input,result,null));
        verifyNoInteractions(runner);
    }
}
