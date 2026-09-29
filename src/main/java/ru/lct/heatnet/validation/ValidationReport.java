package ru.lct.heatnet.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of validating an output GeoJSON against the input and the rules of the annex. */
public final class ValidationReport {
    private final List<ValidationIssue> issues = new ArrayList<>();
    private final Map<String, Integer> checksPerformed = new LinkedHashMap<>();

    void error(String code, String msg, String variant, String feature) { issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, code, msg, variant, feature)); }
    void warn(String code, String msg, String variant, String feature) { issues.add(new ValidationIssue(ValidationIssue.Severity.WARNING, code, msg, variant, feature)); }
    void info(String code, String msg, String variant, String feature) { issues.add(new ValidationIssue(ValidationIssue.Severity.INFO, code, msg, variant, feature)); }
    void counted(String check) { checksPerformed.merge(check, 1, Integer::sum); }

    public List<ValidationIssue> getIssues() { return Collections.unmodifiableList(issues); }
    public Map<String, Integer> getChecksPerformed() { return Collections.unmodifiableMap(checksPerformed); }
    public boolean isValid() { return issues.stream().noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR); }
    public long getErrorCount() { return issues.stream().filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR).count(); }
    public long getWarningCount() { return issues.stream().filter(i -> i.getSeverity() == ValidationIssue.Severity.WARNING).count(); }

    public List<String> errorsOf(String variantId) {
        List<String> out = new ArrayList<>();
        for (ValidationIssue i : issues)
            if (i.getSeverity() == ValidationIssue.Severity.ERROR && (i.getVariantId() == null || i.getVariantId().equals(variantId))) out.add(i.toString());
        return out;
    }

    public List<ValidationIssue> issuesOf(String variantId) {
        List<ValidationIssue> out = new ArrayList<>();
        for (ValidationIssue i : issues) if (i.getVariantId() == null || i.getVariantId().equals(variantId)) out.add(i);
        return out;
    }
}
