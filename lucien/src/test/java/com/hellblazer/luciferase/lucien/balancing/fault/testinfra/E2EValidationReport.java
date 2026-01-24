package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.*;

/**
 * End-to-end validation report.
 * <p>
 * Aggregates validation results from {@link E2ETestValidator} including
 * workflow completeness, data consistency, quorum maintenance, ghost layer
 * validity, and performance metrics.
 *
 * @param passed true if all validations passed
 * @param failures list of failure messages (empty if passed)
 * @param metrics collected metrics during validation
 */
public record E2EValidationReport(
    boolean passed,
    List<String> failures,
    Map<String, Object> metrics
) {
    /**
     * Compact constructor with validation.
     */
    public E2EValidationReport {
        Objects.requireNonNull(failures, "failures cannot be null");
        Objects.requireNonNull(metrics, "metrics cannot be null");

        // Defensive copy for immutability
        failures = List.copyOf(failures);
        metrics = Map.copyOf(metrics);
    }

    /**
     * Create passing E2E validation report.
     *
     * @param metrics collected metrics
     * @return report with passed=true
     */
    public static E2EValidationReport pass(Map<String, Object> metrics) {
        return new E2EValidationReport(true, List.of(), metrics);
    }

    /**
     * Create failing E2E validation report.
     *
     * @param failures failure messages
     * @param metrics collected metrics
     * @return report with passed=false
     */
    public static E2EValidationReport fail(List<String> failures, Map<String, Object> metrics) {
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("Failing report must have at least one failure");
        }
        return new E2EValidationReport(false, failures, metrics);
    }

    /**
     * Get failure count.
     *
     * @return number of validation failures
     */
    public int failureCount() {
        return failures.size();
    }

    /**
     * Get comprehensive report summary.
     *
     * @return multi-line formatted report
     */
    public String summary() {
        var sb = new StringBuilder();
        sb.append("====================================\n");
        sb.append("   E2E VALIDATION REPORT\n");
        sb.append("====================================\n\n");

        // Overall status
        sb.append("Status: ");
        if (passed) {
            sb.append("✅ PASSED\n");
        } else {
            sb.append("❌ FAILED\n");
        }
        sb.append("\n");

        // Key metrics
        sb.append("Key Metrics:\n");
        appendMetric(sb, "  Checks Performed", metrics.get("checks_performed"));
        appendMetric(sb, "  Failure Count", metrics.get("failure_count"));
        appendMetric(sb, "  Workflow Complete", metrics.get("workflow_complete"));
        appendMetric(sb, "  Data Consistent", metrics.get("data_consistent"));
        appendMetric(sb, "  Quorum Maintained", metrics.get("quorum_maintained"));
        appendMetric(sb, "  Performance Within SLA", metrics.get("performance_within_sla"));
        sb.append("\n");

        // Failures (if any)
        if (!failures.isEmpty()) {
            sb.append("Failures:\n");
            for (var i = 0; i < failures.size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, failures.get(i)));
            }
            sb.append("\n");
        }

        // Detailed metrics
        sb.append("Detailed Metrics:\n");
        for (var entry : metrics.entrySet()) {
            if (!isKeyMetric(entry.getKey())) {
                sb.append(String.format("  %s: %s\n", entry.getKey(), formatValue(entry.getValue())));
            }
        }

        sb.append("====================================\n");
        return sb.toString();
    }

    private void appendMetric(StringBuilder sb, String label, Object value) {
        if (value != null) {
            sb.append(String.format("%s: %s\n", label, formatValue(value)));
        }
    }

    private boolean isKeyMetric(String key) {
        return Set.of(
            "checks_performed", "failure_count", "workflow_complete",
            "data_consistent", "quorum_maintained", "performance_within_sla"
        ).contains(key);
    }

    private String formatValue(Object value) {
        if (value instanceof Boolean b) {
            return b ? "✅" : "❌";
        } else if (value instanceof Double d) {
            return String.format("%.2f", d);
        } else if (value instanceof Map<?, ?> map) {
            return map.toString();
        } else {
            return String.valueOf(value);
        }
    }
}
