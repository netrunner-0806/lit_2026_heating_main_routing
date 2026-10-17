package ru.lct.heatnet.input;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.lct.heatnet.api.*;
import ru.lct.heatnet.application.JobService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpErrorStatusTest {
    MockMvc mvc; JobService jobs;
    MockMultipartFile file=new MockMultipartFile("file","a.geojson","application/json",new byte[]{1});
    @BeforeEach void setup() {
        jobs=mock(JobService.class);
        mvc=MockMvcBuilders.standaloneSetup(new JobController(jobs)).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void missingMultipartIs400() throws Exception { mvc.perform(multipart("/api/v1/jobs")).andExpect(status().isBadRequest()); }
    @Test void wrongParameterTypeIs400() throws Exception { mvc.perform(multipart("/api/v1/jobs").file(file).param("maxVariants","abc")).andExpect(status().isBadRequest()); }
    @Test void missingRerunModeIs400() throws Exception { mvc.perform(post("/api/v1/jobs/id/rerun")).andExpect(status().isBadRequest()); }
    @Test void methodIs405() throws Exception { mvc.perform(put("/api/v1/jobs")).andExpect(status().isMethodNotAllowed()); }
    @Test void contentTypeIs415() throws Exception { mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnsupportedMediaType()); }
    @Test void queueRejectionIs503() throws Exception {
        when(jobs.submit(any(),any(),any(),any())).thenThrow(new org.springframework.core.task.TaskRejectedException("queue full"));
        mvc.perform(multipart("/api/v1/jobs").file(file)).andExpect(status().isServiceUnavailable());
    }
    @Test void oversizedIs413() throws Exception {
        when(jobs.submit(any(),any(),any(),any())).thenThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(1));
        mvc.perform(multipart("/api/v1/jobs").file(file)).andExpect(status().isPayloadTooLarge());
    }
    @Test void conflictIs409() throws Exception {
        when(jobs.submit(any(),any(),any(),any())).thenThrow(new ConflictException("busy"));
        mvc.perform(multipart("/api/v1/jobs").file(file)).andExpect(status().isConflict());
    }
    @Test void internalIs500WithoutSensitiveMessage() throws Exception {
        when(jobs.submit(any(),any(),any(),any())).thenThrow(new IllegalStateException("secret-value"));
        mvc.perform(multipart("/api/v1/jobs").file(file)).andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-value"))));
    }
}
