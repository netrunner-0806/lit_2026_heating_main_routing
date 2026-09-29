package ru.lct.heatnet.solver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One explainable decision of the calculation (structured, not a log line). */
public final class CalculationDecision {
    public final String type;
    public final String objectId;
    public final String decision;
    public final String reason;
    public final Map<String, Object> inputs;
    public final List<String> alternatives;

    public CalculationDecision(String type, String objectId, String decision, String reason, Map<String, Object> inputs, List<String> alternatives) {
        this.type = type;
        this.objectId = objectId;
        this.decision = decision;
        this.reason = reason;
        this.inputs = inputs == null ? new LinkedHashMap<>() : inputs;
        this.alternatives = alternatives == null ? java.util.Collections.emptyList() : alternatives;
    }

    public String getType() { return type; }
    public String getObjectId() { return objectId; }
    public String getDecision() { return decision; }
    public String getReason() { return reason; }
    public Map<String, Object> getInputs() { return inputs; }
    public List<String> getAlternatives() { return alternatives; }
}
