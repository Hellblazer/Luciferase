package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validation report containing validation results and metrics.
 * <p>
 * Aggregates validation failures and collected metrics from multi-aspect validation.
 * Used by {@link ConfigurableValidator} to report validation outcomes.
 *
 * @param passed True if all validations passed, false otherwise
 * @param failures List of failure messages (empty if passed=true)
 * @param metrics Collected metrics during validation (e.g., latencies, counts)
 */
public record ValidationReport(
    boolean passed,
    List<String> failures,
    Map<String, Object> metrics
) {
    /**
     * Compact constructor with validation.
     */
    public ValidationReport {
        Objects.requireNonNull(failures, "failures cannot be null");
        Objects.requireNonNull(metrics, "metrics cannot be null");

        // Defensive copy to ensure immutability
        failures = List.copyOf(failures);
        metrics = Map.copyOf(metrics);
    }

    /**
     * Create a passing validation report with no failures.
     *
     * @param metrics collected metrics
     * @return ValidationReport with passed=true
     */
    public static ValidationReport pass(Map<String, Object> metrics) {
        return new ValidationReport(true, List.of(), metrics);
    }

    /**
     * Create a failing validation report with failure messages.
     *
     * @param failures list of failure messages
     * @param metrics collected metrics
     * @return ValidationReport with passed=false
     */
    public static ValidationReport fail(List<String> failures, Map<String, Object> metrics) {
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("Failing report must have at least one failure message");
        }
        return new ValidationReport(false, failures, metrics);
    }

    /**
     * Get failure count.
     *
     * @return number of failures
     */
    public int failureCount() {
        return failures.size();
    }

    /**
     * Get formatted failure summary.
     *
     * @return multi-line string with all failures, or "No failures" if passed
     */
    public String getFailureSummary() {
        if (passed) {
            return "No failures";
        }
        var sb = new StringBuilder();
        sb.append("Validation failures (").append(failures.size()).append("):\n");
        for (var i = 0; i < failures.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(failures.get(i)).append("\n");
        }
        return sb.toString();
    }
}
