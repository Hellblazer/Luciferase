package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.lucien.balancing.fault.RecoveryPhase;

import java.util.*;

/**
 * End-to-end validation framework for distributed system integration tests.
 * <p>
 * Validates complete recovery workflows across multiple dimensions:
 * <ul>
 *   <li>Workflow completeness (fault → detection → recovery → success)</li>
 *   <li>Data consistency across failures</li>
 *   <li>Quorum maintenance during failures</li>
 *   <li>Ghost layer validity after recovery</li>
 *   <li>Performance SLAs (latency, throughput)</li>
 * </ul>
 * <p>
 * Accumulates validation results and generates comprehensive E2E reports.
 * <p>
 * <b>Usage Pattern</b>:
 * <pre>{@code
 * var validator = new E2ETestValidator();
 *
 * // Validate workflow
 * validator.validateWorkflowComplete(phases, expectedPhases);
 *
 * // Validate quorum
 * validator.validateQuorumMaintained(activePartitions, minimumQuorum);
 *
 * // Validate performance
 * validator.validatePerformanceSLA(latency, throughput, sla);
 *
 * // Generate report
 * var report = validator.generateReport();
 * if (!report.passed()) {
 *     System.err.println(report.summary());
 * }
 * }</pre>
 */
public class E2ETestValidator {

    private final List<String> failures;
    private final Map<String, Object> metrics;
    private int checksPerformed;

    /**
     * Create new E2E validator.
     */
    public E2ETestValidator() {
        this.failures = new ArrayList<>();
        this.metrics = new HashMap<>();
        this.checksPerformed = 0;
    }

    /**
     * Validate complete end-to-end workflow.
     * <p>
     * Checks that all expected recovery phases were reached in correct order.
     *
     * @param actualPhases phases reached during recovery
     * @param expectedPhases phases expected for successful recovery
     */
    public void validateWorkflowComplete(List<RecoveryPhase> actualPhases, List<RecoveryPhase> expectedPhases) {
        Objects.requireNonNull(actualPhases, "actualPhases cannot be null");
        Objects.requireNonNull(expectedPhases, "expectedPhases cannot be null");

        checksPerformed++;

        // Check all expected phases present
        for (var expectedPhase : expectedPhases) {
            if (!actualPhases.contains(expectedPhase)) {
                failures.add(String.format(
                    "Workflow incomplete: missing phase %s (actual phases: %s)",
                    expectedPhase, actualPhases
                ));
            }
        }

        // Check phases occurred in valid order
        var phaseIndices = new HashMap<RecoveryPhase, Integer>();
        for (var i = 0; i < actualPhases.size(); i++) {
            phaseIndices.put(actualPhases.get(i), i);
        }

        // DETECTING must come before REDISTRIBUTING
        if (phaseIndices.containsKey(RecoveryPhase.DETECTING) &&
            phaseIndices.containsKey(RecoveryPhase.REDISTRIBUTING)) {
            if (phaseIndices.get(RecoveryPhase.DETECTING) > phaseIndices.get(RecoveryPhase.REDISTRIBUTING)) {
                failures.add("Workflow order violation: DETECTING must precede REDISTRIBUTING");
            }
        }

        // REDISTRIBUTING must come before REBALANCING
        if (phaseIndices.containsKey(RecoveryPhase.REDISTRIBUTING) &&
            phaseIndices.containsKey(RecoveryPhase.REBALANCING)) {
            if (phaseIndices.get(RecoveryPhase.REDISTRIBUTING) > phaseIndices.get(RecoveryPhase.REBALANCING)) {
                failures.add("Workflow order violation: REDISTRIBUTING must precede REBALANCING");
            }
        }

        // VALIDATING must come before COMPLETE
        if (phaseIndices.containsKey(RecoveryPhase.VALIDATING) &&
            phaseIndices.containsKey(RecoveryPhase.COMPLETE)) {
            if (phaseIndices.get(RecoveryPhase.VALIDATING) > phaseIndices.get(RecoveryPhase.COMPLETE)) {
                failures.add("Workflow order violation: VALIDATING must precede COMPLETE");
            }
        }

        // Track metrics
        metrics.put("workflow_phases_expected", expectedPhases.size());
        metrics.put("workflow_phases_actual", actualPhases.size());
        metrics.put("workflow_complete", failures.isEmpty());
    }

    /**
     * Validate data consistency across partition failures.
     * <p>
     * Checks that entity counts and distributions remain consistent
     * after recovery completes.
     *
     * @param expectedEntityCount expected total entity count
     * @param actualEntityCount actual entity count after recovery
     * @param expectedDistribution expected entity distribution across partitions
     * @param actualDistribution actual distribution after recovery
     */
    public void validateDataConsistency(
        int expectedEntityCount,
        int actualEntityCount,
        Map<UUID, Integer> expectedDistribution,
        Map<UUID, Integer> actualDistribution
    ) {
        Objects.requireNonNull(expectedDistribution, "expectedDistribution cannot be null");
        Objects.requireNonNull(actualDistribution, "actualDistribution cannot be null");

        checksPerformed++;

        // Check entity count consistency
        if (expectedEntityCount != actualEntityCount) {
            failures.add(String.format(
                "Data consistency violation: entity count mismatch (expected=%d, actual=%d)",
                expectedEntityCount, actualEntityCount
            ));
        }

        // Check distribution consistency for surviving partitions
        for (var entry : expectedDistribution.entrySet()) {
            var partitionId = entry.getKey();
            var expectedCount = entry.getValue();
            var actualCount = actualDistribution.getOrDefault(partitionId, 0);

            if (!expectedCount.equals(actualCount)) {
                failures.add(String.format(
                    "Data consistency violation: partition %s has wrong entity count (expected=%d, actual=%d)",
                    partitionId, expectedCount, actualCount
                ));
            }
        }

        // Track metrics
        metrics.put("entity_count_expected", expectedEntityCount);
        metrics.put("entity_count_actual", actualEntityCount);
        metrics.put("data_consistent", expectedEntityCount == actualEntityCount);
    }

    /**
     * Validate quorum maintained during failures.
     * <p>
     * Checks that sufficient partitions remained active to maintain
     * system availability throughout the failure scenario.
     *
     * @param activePartitions set of active (healthy) partition UUIDs
     * @param minimumQuorum minimum required active partitions
     */
    public void validateQuorumMaintained(Set<UUID> activePartitions, int minimumQuorum) {
        Objects.requireNonNull(activePartitions, "activePartitions cannot be null");
        if (minimumQuorum <= 0) {
            throw new IllegalArgumentException("minimumQuorum must be positive, got: " + minimumQuorum);
        }

        checksPerformed++;

        var activeCount = activePartitions.size();
        if (activeCount < minimumQuorum) {
            failures.add(String.format(
                "Quorum violation: insufficient active partitions (active=%d, minimum=%d)",
                activeCount, minimumQuorum
            ));
        }

        // Track metrics
        metrics.put("active_partition_count", activeCount);
        metrics.put("minimum_quorum", minimumQuorum);
        metrics.put("quorum_maintained", activeCount >= minimumQuorum);
    }

    /**
     * Validate ghost layer validity after recovery.
     * <p>
     * Checks that ghost layer entities are correctly distributed and
     * no dangling references exist after partition failure recovery.
     *
     * @param ghostEntities map of partition to ghost entity count
     * @param expectedGhostEntities expected ghost entity counts
     */
    public void validateGhostLayerValid(Map<UUID, Integer> ghostEntities, Map<UUID, Integer> expectedGhostEntities) {
        Objects.requireNonNull(ghostEntities, "ghostEntities cannot be null");
        Objects.requireNonNull(expectedGhostEntities, "expectedGhostEntities cannot be null");

        checksPerformed++;

        // Check ghost entity counts match expectations
        for (var entry : expectedGhostEntities.entrySet()) {
            var partitionId = entry.getKey();
            var expectedCount = entry.getValue();
            var actualCount = ghostEntities.getOrDefault(partitionId, 0);

            if (!expectedCount.equals(actualCount)) {
                failures.add(String.format(
                    "Ghost layer violation: partition %s has wrong ghost count (expected=%d, actual=%d)",
                    partitionId, expectedCount, actualCount
                ));
            }
        }

        // Track metrics
        var totalGhosts = ghostEntities.values().stream().mapToInt(Integer::intValue).sum();
        metrics.put("ghost_entity_count", totalGhosts);
        metrics.put("ghost_layer_valid", failures.isEmpty());
    }

    /**
     * Validate performance SLA compliance.
     * <p>
     * Checks that recovery latency and throughput meet specified SLAs.
     *
     * @param latencyMs actual recovery latency in milliseconds
     * @param throughput actual throughput (operations per second)
     * @param sla performance SLA specification
     */
    public void validatePerformanceSLA(long latencyMs, double throughput, PerformanceSLA sla) {
        Objects.requireNonNull(sla, "sla cannot be null");

        checksPerformed++;

        // Check latency SLA
        if (latencyMs > sla.maxLatencyMs()) {
            failures.add(String.format(
                "Performance SLA violation: latency exceeds maximum (actual=%dms, max=%dms)",
                latencyMs, sla.maxLatencyMs()
            ));
        }

        // Check throughput SLA
        if (throughput < sla.minThroughput()) {
            failures.add(String.format(
                "Performance SLA violation: throughput below minimum (actual=%.2f ops/s, min=%.2f ops/s)",
                throughput, sla.minThroughput()
            ));
        }

        // Track metrics
        metrics.put("latency_ms", latencyMs);
        metrics.put("latency_sla_ms", sla.maxLatencyMs());
        metrics.put("throughput_ops_per_sec", throughput);
        metrics.put("throughput_sla_ops_per_sec", sla.minThroughput());
        metrics.put("performance_within_sla", latencyMs <= sla.maxLatencyMs() && throughput >= sla.minThroughput());
    }

    /**
     * Validate recovery succeeded despite injected faults.
     * <p>
     * Checks that system recovered to healthy state despite fault injection.
     *
     * @param recoverySuccessful true if recovery completed successfully
     * @param injectedFaults list of faults that were injected
     */
    public void validateRecoveryDespiteFaults(boolean recoverySuccessful, List<InjectedFault> injectedFaults) {
        Objects.requireNonNull(injectedFaults, "injectedFaults cannot be null");

        checksPerformed++;

        if (!recoverySuccessful) {
            failures.add(String.format(
                "Recovery failed despite fault injection (faults injected: %d)",
                injectedFaults.size()
            ));
        }

        // Track metrics
        metrics.put("faults_injected", injectedFaults.size());
        metrics.put("recovery_successful", recoverySuccessful);

        // Count faults by type
        var faultsByType = new HashMap<FaultType, Integer>();
        for (var fault : injectedFaults) {
            faultsByType.merge(fault.type(), 1, Integer::sum);
        }
        metrics.put("faults_by_type", faultsByType);
    }

    /**
     * Generate comprehensive E2E validation report.
     *
     * @return E2EValidationReport with pass/fail status and details
     */
    public E2EValidationReport generateReport() {
        var reportMetrics = new HashMap<>(metrics);
        reportMetrics.put("checks_performed", checksPerformed);
        reportMetrics.put("failure_count", failures.size());

        if (failures.isEmpty()) {
            return E2EValidationReport.pass(reportMetrics);
        } else {
            return E2EValidationReport.fail(failures, reportMetrics);
        }
    }

    /**
     * Reset validator state for reuse.
     */
    public void reset() {
        failures.clear();
        metrics.clear();
        checksPerformed = 0;
    }
}
