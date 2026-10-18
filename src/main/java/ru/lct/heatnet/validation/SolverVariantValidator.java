package ru.lct.heatnet.validation;

import ru.lct.heatnet.domain.InputModel;
import ru.lct.heatnet.output.OutputBuilder;
import ru.lct.heatnet.output.OutputFeature;
import ru.lct.heatnet.solver.HeatnetSolver;
import ru.lct.heatnet.solver.VariantResult;

import java.util.List;

/** Adapter: validates a candidate variant by rendering it to output features and running the result validator. */
public final class SolverVariantValidator implements HeatnetSolver.VariantValidator {
    private final ResultValidator validator;
    private final OutputBuilder builder;

    public SolverVariantValidator(InputModel input) { this.validator = new ResultValidator(input); this.builder = new OutputBuilder(input); }

    @Override
    public List<String> validate(VariantResult variant) {
        String saved = variant.variantId();
        int savedRank = variant.rank();
        variant.setVariantId(saved == null ? "candidate" : saved);
        variant.setRank(1);
        try {
            List<OutputFeature> features = builder.build(variant);
            ValidationReport rep = validator.validate(features);
            return rep.errorsOf(variant.variantId());
        } finally {
            variant.setVariantId(saved);
            variant.setRank(savedRank);
        }
    }
}
