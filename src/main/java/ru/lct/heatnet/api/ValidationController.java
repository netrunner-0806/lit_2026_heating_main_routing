package ru.lct.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.application.JobRunner;
import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.geo.GeoJsonStreamReader;
import ru.lct.heatnet.geo.ParseOptions;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.validation.OutputFeatureReader;
import ru.lct.heatnet.validation.ResultValidator;
import ru.lct.heatnet.validation.ValidationReport;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/api/v1/validate")
@Tag(name = "Validation", description = "Независимая проверка выходного GeoJSON по правилам технического приложения")
public class ValidationController {

    private final JobRunner runner;

    public ValidationController(JobRunner runner) { this.runner = runner; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Проверить результат", description = "Принимает входной GeoJSON (input) и результат (result) и возвращает отчёт валидатора: схема, топология, повороты, пересечения, ограничения, расходы, ДУ, предельные длины, стоимости, сводки, ранги.")
    public JsonNode validate(@RequestParam("input") MultipartFile input, @RequestParam("result") MultipartFile result,
                             @RequestParam(value = "unknownRestrictionPolicy", required = false) ParseOptions.UnknownRestrictionPolicy policy) throws IOException {
        InputModel model;
        try (InputStream in = input.getInputStream()) {
            model = new GeoJsonStreamReader(new ParseOptions(policy)).read(in);
        }
        List<OutputFeature> features;
        try (InputStream in = result.getInputStream()) {
            features = new OutputFeatureReader().read(in);
        }
        ValidationReport report = new ResultValidator(model).validate(features);
        return runner.validation(report);
    }
}
