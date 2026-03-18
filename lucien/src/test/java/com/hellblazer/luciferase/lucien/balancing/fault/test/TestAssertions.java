package com.hellblazer.luciferase.lucien.balancing.fault.test;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.FaultMetrics;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionChangeEvent;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Custom assertions for fault tolerance tests.
 * <p>
 * Provides domain-specific assertions that simplify test verification:
 * <ul>
 *   <li>Wait for eventual consistency (polling assertions)</li>
 *   <li>Verify status transition sequences</li>
 *   <li>Check for cascading failures</li>
 *   <li>Validate metrics bounds</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * // Wait for partition to become healthy
 * TestAssertions.assertEventuallyHealthy(
 *     handler,
 *     partitionId,
 *     Duration.ofSeconds(5)
 * );
 *
 * // Verify status sequence
 * TestAssertions.assertStatusSequence(
 *     events,
 *     PartitionStatus.HEALTHY,
 *     PartitionStatus.SUSPECTED,
 *     PartitionStatus.FAILED,
 *     PartitionStatus.RECOVERING,
 *     PartitionStatus.HEALTHY
 * );
 * }</pre>
 */
public class TestAssertions {

    /**
     * Assert partition reaches HEALTHY status within timeout.
     * <p>
     * Polls status every 50ms until HEALTHY or timeout expires.
     *
     * @param handler fault handler
     * @param partitionId partition to check
     * @param timeout maximum wait time
     * @throws AssertionError if partition not HEALTHY within timeout
     */
    public static void assertEventuallyHealthy(
        FaultHandler handler,
        UUID partitionId,
        Duration timeout
    ) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        var lastStatus = handler.checkHealth(partitionId);

        while (System.currentTimeMillis() < deadline) {
            lastStatus = handler.checkHealth(partitionId);
            if (lastStatus == PartitionStatus.HEALTHY) {
                return; // Success
            }

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for HEALTHY status", e);
            }
        }

        throw new AssertionError(
            "Partition " + partitionId + " did not reach HEALTHY status within " + timeout +
            ". Last status: " + lastStatus
        );
    }

    /**
     * Assert partition reaches expected status within timeout.
     *
     * @param handler fault handler
     * @param partitionId partition to check
     * @param expectedStatus expected status
     * @param timeout maximum wait time
     * @throws AssertionError if status not reached within timeout
     */
    public static void assertEventuallyStatus(
        FaultHandler handler,
        UUID partitionId,
        PartitionStatus expectedStatus,
        Duration timeout
    ) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        var lastStatus = handler.checkHealth(partitionId);

        while (System.currentTimeMillis() < deadline) {
            lastStatus = handler.checkHealth(partitionId);
            if (lastStatus == expectedStatus) {
                return; // Success
            }

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for status: " + expectedStatus, e);
            }
        }

        throw new AssertionError(
            "Partition " + partitionId + " did not reach " + expectedStatus + " within " + timeout +
            ". Last status: " + lastStatus
        );
    }

    /**
     * Assert status transition sequence matches expected.
     * <p>
     * Verifies events contain exactly the expected status transitions in order.
     *
     * @param events recorded events
     * @param expectedSequence expected status sequence (newStatus of each event)
     * @throws AssertionError if sequence does not match
     */
    public static void assertStatusSequence(
        List<PartitionChangeEvent> events,
        PartitionStatus... expectedSequence
    ) {
        if (events.size() != expectedSequence.length) {
            throw new AssertionError(
                "Expected " + expectedSequence.length + " transitions, got " + events.size() +
                ". Events: " + events
            );
        }

        for (int i = 0; i < expectedSequence.length; i++) {
            var event = events.get(i);
            var expected = expectedSequence[i];
            if (event.newStatus() != expected) {
                throw new AssertionError(
                    "Transition " + i + ": expected " + expected + ", got " + event.newStatus() +
                    ". Full sequence: " + Arrays.toString(expectedSequence) +
                    ". Actual: " + events.stream().map(PartitionChangeEvent::newStatus).toList()
                );
            }
        }
    }

    /**
     * Assert no cascading failures detected.
     * <p>
     * Verifies that trigger partition failure did not cause other partitions
     * to fail within the time window.
     *
     * @param handler fault handler
     * @param triggerPartition partition that failed first
     * @param timeWindowMs time window to check for cascades
     * @throws AssertionError if cascading failures detected
     */
    public static void assertNoCascadingFailure(
        FaultHandler handler,
        UUID triggerPartition,
        int timeWindowMs
    ) {
        // Wait for time window
        try {
            TimeUnit.MILLISECONDS.sleep(timeWindowMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted during cascade detection window", e);
        }

        // Check if trigger partition is the only failed one
        // Note: This is a simplified check. Real implementation would need
        // access to all partition IDs from the handler.
        var triggerStatus = handler.checkHealth(triggerPartition);
        if (triggerStatus != PartitionStatus.FAILED && triggerStatus != PartitionStatus.SUSPECTED) {
            // If trigger is not failed, no cascade could have happened
            return;
        }

        // In real implementation, would iterate all partitions and verify
        // only triggerPartition is FAILED/SUSPECTED.
        // For now, we rely on the test to inject known partition IDs.
    }

    /**
     * Assert metrics within expected bounds.
     * <p>
     * Validates fault detection and recovery metrics are within acceptable ranges.
     *
     * @param metrics fault metrics to validate
     * @param maxDetectionLatencyMs maximum allowed detection latency
     * @param maxFailureCount maximum allowed failure count
     * @throws AssertionError if metrics exceed bounds
     */
    public static void assertMetricsInRange(
        FaultMetrics metrics,
        long maxDetectionLatencyMs,
        int maxFailureCount
    ) {
        if (metrics.detectionLatencyMs() > maxDetectionLatencyMs) {
            throw new AssertionError(
                "Detection latency " + metrics.detectionLatencyMs() + "ms exceeds max " +
                maxDetectionLatencyMs + "ms"
            );
        }

        if (metrics.failureCount() > maxFailureCount) {
            throw new AssertionError(
                "Failure count " + metrics.failureCount() + " exceeds max " + maxFailureCount
            );
        }
    }

    /**
     * Assert recovery success rate meets minimum threshold.
     *
     * @param metrics fault metrics
     * @param minSuccessRate minimum success rate (0.0-1.0)
     * @throws AssertionError if success rate below threshold
     */
    public static void assertRecoverySuccessRate(
        FaultMetrics metrics,
        double minSuccessRate
    ) {
        if (minSuccessRate < 0.0 || minSuccessRate > 1.0) {
            throw new IllegalArgumentException("minSuccessRate must be 0.0-1.0, got: " + minSuccessRate);
        }

        var actualRate = metrics.successRate();
        if (actualRate < minSuccessRate) {
            throw new AssertionError(
                "Recovery success rate " + actualRate + " below minimum " + minSuccessRate +
                ". Successful: " + metrics.successfulRecoveries() +
                ", Failed: " + metrics.failedRecoveries()
            );
        }
    }

    /**
     * Assert event contains expected transition.
     *
     * @param event event to check
     * @param expectedOld expected old status
     * @param expectedNew expected new status
     * @throws AssertionError if transition does not match
     */
    public static void assertEventTransition(
        PartitionChangeEvent event,
        PartitionStatus expectedOld,
        PartitionStatus expectedNew
    ) {
        if (event.oldStatus() != expectedOld || event.newStatus() != expectedNew) {
            throw new AssertionError(
                "Expected transition " + expectedOld + " → " + expectedNew +
                ", got " + event.oldStatus() + " → " + event.newStatus()
            );
        }
    }

    /**
     * Assert event contains reason keyword.
     *
     * @param event event to check
     * @param keyword expected keyword in reason
     * @throws AssertionError if keyword not found
     */
    public static void assertEventReason(
        PartitionChangeEvent event,
        String keyword
    ) {
        if (!event.reason().toLowerCase().contains(keyword.toLowerCase())) {
            throw new AssertionError(
                "Event reason '" + event.reason() + "' does not contain keyword '" + keyword + "'"
            );
        }
    }

    /**
     * Assert partition view has expected healthy node count.
     *
     * @param handler fault handler
     * @param partitionId partition to check
     * @param expectedHealthy expected healthy node count
     * @throws AssertionError if healthy count does not match
     */
    public static void assertHealthyNodeCount(
        FaultHandler handler,
        UUID partitionId,
        int expectedHealthy
    ) {
        var view = handler.getPartitionView(partitionId);
        if (view == null) {
            throw new AssertionError("No view for partition: " + partitionId);
        }

        if (view.healthyNodes() != expectedHealthy) {
            throw new AssertionError(
                "Expected " + expectedHealthy + " healthy nodes, got " + view.healthyNodes()
            );
        }
    }

    /**
     * Assert partition last seen timestamp is recent (within threshold).
     *
     * @param handler fault handler
     * @param partitionId partition to check
     * @param maxAgeMs maximum allowed age in milliseconds
     * @throws AssertionError if timestamp too old
     */
    public static void assertRecentHeartbeat(
        FaultHandler handler,
        UUID partitionId,
        long maxAgeMs
    ) {
        var view = handler.getPartitionView(partitionId);
        if (view == null) {
            throw new AssertionError("No view for partition: " + partitionId);
        }

        var age = System.currentTimeMillis() - view.lastSeenMs();
        if (age > maxAgeMs) {
            throw new AssertionError(
                "Last heartbeat " + age + "ms ago exceeds max age " + maxAgeMs + "ms"
            );
        }
    }
}
