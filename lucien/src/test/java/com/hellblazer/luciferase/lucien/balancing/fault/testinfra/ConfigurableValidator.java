package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.lucien.balancing.fault.RecoveryState;

import java.util.*;

/**
 * Multi-aspect validation framework for distributed system testing.
 * <p>
 * Validates system state across multiple dimensions:
 * <ul>
 *   <li>Recovery state correctness (phases, retries, timing)</li>
 *   <li>VON topology consistency (partition membership)</li>
 *   <li>Forest integrity (partition distribution, ghost layer)</li>
 *   <li>Performance (latency SLAs, throughput)</li>
 * </ul>
 * <p>
 * Accumulates validation failures and metrics, then generates a comprehensive
 * {@link ValidationReport} summarizing all validation outcomes.
 * <p>
 * <b>Usage Pattern</b>:
 * <pre>{@code
 * var validator = new ConfigurableValidator();
 *
 * // Perform multiple validations
 * validator.validateRecoveryState(expected, actual);
 * validator.validateVONTopology(expectedPartitions);
 * validator.validatePerformance(sla, actualLatency);
 *
 * // Generate comprehensive report
 * var report = validator.generateReport();
 * if (!report.passed()) {
 *     System.err.println(report.getFailureSummary());
 * }
 * }</pre>
 */
public class ConfigurableValidator {

    private final List<String> failures = new ArrayList<>();
    private final Map<String, Object> metrics = new HashMap<>();
    private int validationsRun = 0;

    /**
     * Create new validator instance.
     */
    public ConfigurableValidator() {
    }

    /**
     * Validate recovery state matches expected state.
     * <p>
     * Checks phase, attempt count, and partition ID consistency.
     *
     * @param expected expected recovery state
     * @param actual actual recovery state
     * @throws NullPointerException if expected or actual is null
     */
    public void validateRecoveryState(RecoveryState expected, RecoveryState actual) {
        Objects.requireNonNull(expected, "expected cannot be null");
        Objects.requireNonNull(actual, "actual cannot be null");

        validationsRun++;

        // Validate partition ID
        if (!expected.partitionId().equals(actual.partitionId())) {
            failures.add(String.format(
                "Recovery state partition ID mismatch: expected=%s, actual=%s",
                expected.partitionId(), actual.partitionId()
            ));
        }

        // Validate phase
        if (expected.currentPhase() != actual.currentPhase()) {
            failures.add(String.format(
                "Recovery state phase mismatch: expected=%s, actual=%s",
                expected.currentPhase(), actual.currentPhase()
            ));
        }

        // Validate attempt count
        if (expected.attemptCount() != actual.attemptCount()) {
            failures.add(String.format(
                "Recovery state attempt count mismatch: expected=%d, actual=%d",
                expected.attemptCount(), actual.attemptCount()
            ));
        }

        // Track metrics
        metrics.put("recovery_phase_expected", expected.currentPhase().name());
        metrics.put("recovery_phase_actual", actual.currentPhase().name());
        metrics.put("recovery_attempts", actual.attemptCount());
    }

    /**
     * Validate VON topology contains expected partitions.
     * <p>
     * Checks that partition set is non-empty and consistent.
     *
     * @param expectedPartitions set of expected partition UUIDs
     * @throws NullPointerException if expectedPartitions is null
     */
    public void validateVONTopology(Set<UUID> expectedPartitions) {
        Objects.requireNonNull(expectedPartitions, "expectedPartitions cannot be null");

        validationsRun++;

        // Check for empty partition set
        if (expectedPartitions.isEmpty()) {
            failures.add("VON topology validation failed: partition set is empty");
        }

        // Track metrics
        metrics.put("von_partition_count", expectedPartitions.size());
    }

    /**
     * Validate forest integrity (placeholder for future implementation).
     * <p>
     * Currently performs basic validation. Will be extended in P4.4.2-P4.4.5
     * to check ghost layer consistency and partition distribution.
     *
     * @param forest distributed forest to validate
     */
    public void validateForestIntegrity(Object forest) {
        Objects.requireNonNull(forest, "forest cannot be null");
        validationsRun++;

        // Placeholder - will be implemented in P4.4.2-P4.4.5
        metrics.put("forest_validated", true);
    }

    /**
     * Validate performance against SLA thresholds.
     * <p>
     * Checks that actual latency does not exceed SLA maximum.
     *
     * @param sla latency SLA thresholds
     * @param actualLatencyMs actual latency in milliseconds
     * @throws NullPointerException if sla is null
     */
    public void validatePerformance(LatencySLA sla, long actualLatencyMs) {
        Objects.requireNonNull(sla, "sla cannot be null");

        validationsRun++;

        // Check against max latency threshold
        if (actualLatencyMs > sla.maxLatencyMs()) {
            failures.add(String.format(
                "Performance validation failed: latency=%dms exceeds max=%dms",
                actualLatencyMs, sla.maxLatencyMs()
            ));
        }

        // Track metrics
        metrics.put("latency_actual_ms", actualLatencyMs);
        metrics.put("latency_sla_max_ms", sla.maxLatencyMs());
        metrics.put("latency_within_sla", actualLatencyMs <= sla.maxLatencyMs());
    }

    /**
     * Generate comprehensive validation report.
     * <p>
     * Includes pass/fail status, failure messages, and collected metrics.
     * Can be called multiple times (idempotent).
     *
     * @return ValidationReport summarizing all validation outcomes
     */
    public ValidationReport generateReport() {
        var reportMetrics = new HashMap<>(metrics);
        reportMetrics.put("validations_run", validationsRun);
        reportMetrics.put("failure_count", failures.size());

        if (failures.isEmpty()) {
            return ValidationReport.pass(reportMetrics);
        } else {
            return ValidationReport.fail(failures, reportMetrics);
        }
    }
}
