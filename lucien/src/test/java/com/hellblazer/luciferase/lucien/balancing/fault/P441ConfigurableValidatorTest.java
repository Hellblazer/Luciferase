package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.ConfigurableValidator;
import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.LatencySLA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigurableValidator test infrastructure (P4.4.1).
 * <p>
 * Validates recovery state validation, VON topology validation, forest integrity checks,
 * performance validation, and report generation.
 */
class P441ConfigurableValidatorTest {

    private ConfigurableValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigurableValidator();
    }

    @Test
    void testValidateRecoveryState_Success() {
        // Given: Matching recovery states
        var partitionId = UUID.randomUUID();
        var expected = RecoveryState.initial(partitionId, 1000L)
            .withPhase(RecoveryPhase.COMPLETE, 2000L)
            .withIncrementedAttempt(2000L);

        var actual = RecoveryState.initial(partitionId, 1000L)
            .withPhase(RecoveryPhase.COMPLETE, 2000L)
            .withIncrementedAttempt(2000L);

        // When: Validate
        validator.validateRecoveryState(expected, actual);
        var report = validator.generateReport();

        // Then: Should pass
        assertTrue(report.passed(), "Validation should pass");
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void testValidateRecoveryState_PhaseMismatch() {
        // Given: Mismatched phases
        var partitionId = UUID.randomUUID();
        var expected = RecoveryState.initial(partitionId, 1000L)
            .withPhase(RecoveryPhase.COMPLETE, 2000L);

        var actual = RecoveryState.initial(partitionId, 1000L)
            .withPhase(RecoveryPhase.FAILED, 2000L);

        // When: Validate
        validator.validateRecoveryState(expected, actual);
        var report = validator.generateReport();

        // Then: Should fail with phase mismatch
        assertFalse(report.passed(), "Validation should fail");
        assertTrue(
            report.failures().stream().anyMatch(f -> f.contains("phase")),
            "Should report phase mismatch"
        );
    }

    @Test
    void testValidateVONTopology() {
        // Given: Expected partitions
        var partition0 = UUID.randomUUID();
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();
        var expectedPartitions = Set.of(partition0, partition1, partition2);

        // When: Validate
        validator.validateVONTopology(expectedPartitions);
        var report = validator.generateReport();

        // Then: Should pass (basic validation)
        assertTrue(report.passed(), "Validation should pass");
    }

    @Test
    void testValidateVONTopology_EmptyPartitions() {
        // Given: Empty partition set
        var expectedPartitions = Set.<UUID>of();

        // When: Validate
        validator.validateVONTopology(expectedPartitions);
        var report = validator.generateReport();

        // Then: Should fail
        assertFalse(report.passed(), "Validation should fail for empty partitions");
        assertTrue(
            report.failures().stream().anyMatch(f -> f.contains("empty")),
            "Should report empty partition set"
        );
    }

    @Test
    void testValidatePerformance_WithinSLA() {
        // Given: Latency within SLA
        var sla = LatencySLA.standard(); // p50=10ms, p95=50ms, p99=100ms, max=500ms
        var actualLatency = 25L; // Within all thresholds

        // When: Validate
        validator.validatePerformance(sla, actualLatency);
        var report = validator.generateReport();

        // Then: Should pass
        assertTrue(report.passed(), "Performance should be within SLA");
    }

    @Test
    void testValidatePerformance_ExceedsSLA() {
        // Given: Latency exceeding SLA
        var sla = LatencySLA.standard(); // max=500ms
        var actualLatency = 600L; // Exceeds max

        // When: Validate
        validator.validatePerformance(sla, actualLatency);
        var report = validator.generateReport();

        // Then: Should fail
        assertFalse(report.passed(), "Performance should exceed SLA");
        assertTrue(
            report.failures().stream().anyMatch(f -> f.contains("latency")),
            "Should report latency violation"
        );
    }

    @Test
    void testValidationReport_GeneratesMetrics() {
        // Given: Multiple validations with different outcomes
        var partitionId = UUID.randomUUID();
        var expected = RecoveryState.initial(partitionId, 1000L);
        var actual = RecoveryState.initial(partitionId, 1000L);

        validator.validateRecoveryState(expected, actual);
        validator.validateVONTopology(Set.of(UUID.randomUUID()));
        validator.validatePerformance(LatencySLA.standard(), 25L);

        // When: Generate report
        var report = validator.generateReport();

        // Then: Should include metrics
        assertNotNull(report.metrics());
        assertFalse(report.metrics().isEmpty(), "Report should include metrics");
    }

    @Test
    void testMultipleDimensionValidation() {
        // Given: Multiple validations, some passing, some failing
        var partitionId = UUID.randomUUID();

        // Passing recovery state validation
        var expected = RecoveryState.initial(partitionId, 1000L);
        var actual = RecoveryState.initial(partitionId, 1000L);
        validator.validateRecoveryState(expected, actual);

        // Failing VON topology validation
        validator.validateVONTopology(Set.of()); // Empty set should fail

        // Passing performance validation
        validator.validatePerformance(LatencySLA.standard(), 25L);

        // When: Generate report
        var report = validator.generateReport();

        // Then: Should fail overall but track individual results
        assertFalse(report.passed(), "Overall validation should fail");
        assertTrue(report.failureCount() > 0, "Should have at least one failure");
    }

    @Test
    void testValidationFailureDetails() {
        // Given: Multiple validation failures
        var partitionId = UUID.randomUUID();

        // Phase mismatch
        var expected = RecoveryState.initial(partitionId, 1000L).withPhase(RecoveryPhase.COMPLETE, 2000L);
        var actual = RecoveryState.initial(partitionId, 1000L).withPhase(RecoveryPhase.FAILED, 2000L);
        validator.validateRecoveryState(expected, actual);

        // Empty VON topology
        validator.validateVONTopology(Set.of());

        // Latency violation
        validator.validatePerformance(LatencySLA.strict(), 200L);

        // When: Generate report
        var report = validator.generateReport();

        // Then: Should provide detailed failure messages
        assertFalse(report.passed());
        assertTrue(report.failureCount() >= 3, "Should have at least 3 failures");

        var summary = report.getFailureSummary();
        assertFalse(summary.contains("No failures"), "Summary should contain failures");
    }

    @Test
    void testValidationMetrics() {
        // Given: Validations with metrics
        var partitionId = UUID.randomUUID();
        var expected = RecoveryState.initial(partitionId, 1000L);
        var actual = RecoveryState.initial(partitionId, 1000L);

        validator.validateRecoveryState(expected, actual);
        validator.validatePerformance(LatencySLA.standard(), 25L);

        // When: Generate report
        var report = validator.generateReport();

        // Then: Should include validation metrics
        assertTrue(report.metrics().containsKey("validations_run"));
        var validationCount = (Integer) report.metrics().get("validations_run");
        assertTrue(validationCount >= 2, "Should track validation count");
    }

    @Test
    void testGenerateReport_Idempotent() {
        // Given: Validator with some validations
        var partitionId = UUID.randomUUID();
        var expected = RecoveryState.initial(partitionId, 1000L);
        var actual = RecoveryState.initial(partitionId, 1000L);
        validator.validateRecoveryState(expected, actual);

        // When: Generate report multiple times
        var report1 = validator.generateReport();
        var report2 = validator.generateReport();

        // Then: Reports should be identical
        assertEquals(report1.passed(), report2.passed());
        assertEquals(report1.failureCount(), report2.failureCount());
    }

    @Test
    void testValidateRecoveryState_AttempCountMismatch() {
        // Given: Mismatched attempt counts
        var partitionId = UUID.randomUUID();
        var expected = RecoveryState.initial(partitionId, 1000L)
            .withIncrementedAttempt(2000L)
            .withIncrementedAttempt(3000L);

        var actual = RecoveryState.initial(partitionId, 1000L)
            .withIncrementedAttempt(2000L);

        // When: Validate
        validator.validateRecoveryState(expected, actual);
        var report = validator.generateReport();

        // Then: Should fail with attempt count mismatch
        assertFalse(report.passed(), "Validation should fail");
        assertTrue(
            report.failures().stream().anyMatch(f -> f.contains("attempt")),
            "Should report attempt count mismatch"
        );
    }
}
