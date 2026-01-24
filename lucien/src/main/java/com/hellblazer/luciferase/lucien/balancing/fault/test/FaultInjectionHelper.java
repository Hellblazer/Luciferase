package com.hellblazer.luciferase.lucien.balancing.fault.test;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Utility for injecting faults in tests.
 * <p>
 * Provides methods to simulate random failures, timeout cascades, clock skew,
 * and other fault conditions for stress testing and chaos engineering.
 * <p>
 * Example usage:
 * <pre>{@code
 * // Inject random heartbeat failures
 * FaultInjectionHelper.injectRandomHeartbeatFailures(
 *     handler,
 *     0.1,  // 10% failure rate
 *     Duration.ofSeconds(10)
 * );
 *
 * // Simulate timeout cascade
 * FaultInjectionHelper.injectTimeoutCascade(
 *     handler,
 *     firstFailedPartition,
 *     3  // cascade depth
 * );
 * }</pre>
 */
public class FaultInjectionHelper {

    private static final Random RANDOM = new Random();

    /**
     * Inject random heartbeat failures for stress testing.
     * <p>
     * Randomly injects heartbeat failures with specified probability over
     * the test duration. Useful for chaos engineering and stability testing.
     *
     * @param handler fault handler to inject failures into
     * @param failureProbability probability of failure (0.0-1.0)
     * @param testDuration duration to inject failures
     */
    public static void injectRandomHeartbeatFailures(
        FaultHandler handler,
        double failureProbability,
        Duration testDuration
    ) {
        if (failureProbability < 0.0 || failureProbability > 1.0) {
            throw new IllegalArgumentException("failureProbability must be 0.0-1.0, got: " + failureProbability);
        }

        var executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "fault-injector");
            thread.setDaemon(true);
            return thread;
        });

        var partitions = getAllPartitions(handler);
        var nodeIds = generateNodeIds(partitions.size());

        var task = executor.scheduleAtFixedRate(() -> {
            for (int i = 0; i < partitions.size(); i++) {
                if (RANDOM.nextDouble() < failureProbability) {
                    handler.reportHeartbeatFailure(partitions.get(i), nodeIds.get(i));
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        // Stop after test duration
        executor.schedule(() -> {
            task.cancel(false);
            executor.shutdown();
        }, testDuration.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Simulate timeout cascade.
     * <p>
     * Creates a cascading failure pattern where one partition failure triggers
     * failures in dependent partitions up to specified depth.
     *
     * @param handler fault handler
     * @param firstFailedPartition initial failed partition
     * @param cascadeDepth number of cascade levels (1-10)
     */
    public static void injectTimeoutCascade(
        FaultHandler handler,
        UUID firstFailedPartition,
        int cascadeDepth
    ) {
        if (cascadeDepth < 1 || cascadeDepth > 10) {
            throw new IllegalArgumentException("cascadeDepth must be 1-10, got: " + cascadeDepth);
        }

        var executor = Executors.newScheduledThreadPool(cascadeDepth);
        var allPartitions = getAllPartitions(handler);
        var cascadePartitions = selectCascadePartitions(allPartitions, firstFailedPartition, cascadeDepth);

        // Initial failure
        handler.reportBarrierTimeout(firstFailedPartition);

        // Schedule cascade
        for (int i = 0; i < cascadePartitions.size(); i++) {
            var delay = (i + 1) * 100L; // 100ms between cascade steps
            var partitionId = cascadePartitions.get(i);

            executor.schedule(
                () -> handler.reportBarrierTimeout(partitionId),
                delay,
                TimeUnit.MILLISECONDS
            );
        }

        executor.shutdown();
    }

    /**
     * Inject clock skew for time-dependent tests.
     * <p>
     * Note: This is a simplified simulation. Real clock skew requires
     * Clock interface injection in the FaultHandler implementation.
     *
     * @param skewMs clock skew in milliseconds (can be negative)
     */
    public static void injectClockSkew(long skewMs) {
        // This is a placeholder for clock skew simulation.
        // Real implementation would require Clock interface injection
        // in FaultHandler and time-sensitive components.
        //
        // For now, we document the expected behavior:
        // - Positive skew: System appears to be in the future
        // - Negative skew: System appears to be in the past
        // - Affects timeout calculations and timestamp comparisons

        if (Math.abs(skewMs) > 60_000) {
            throw new IllegalArgumentException("Clock skew > 60s is unrealistic for testing");
        }

        // In a real implementation, this would set a clock offset in
        // TestClock or similar test fixture used by the FaultHandler.
    }

    /**
     * Inject periodic sync failures for specific partition.
     * <p>
     * Simulates intermittent ghost sync failures at regular intervals.
     *
     * @param handler fault handler
     * @param partitionId target partition
     * @param intervalMs interval between failures
     * @param duration test duration
     * @return ScheduledFuture for cancellation
     */
    public static ScheduledFuture<?> injectPeriodicSyncFailures(
        FaultHandler handler,
        UUID partitionId,
        long intervalMs,
        Duration duration
    ) {
        var executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "sync-failure-injector");
            thread.setDaemon(true);
            return thread;
        });

        var task = executor.scheduleAtFixedRate(
            () -> handler.reportSyncFailure(partitionId),
            0,
            intervalMs,
            TimeUnit.MILLISECONDS
        );

        // Auto-cleanup
        executor.schedule(() -> {
            task.cancel(false);
            executor.shutdown();
        }, duration.toMillis(), TimeUnit.MILLISECONDS);

        return task;
    }

    /**
     * Inject burst of failures (spike load simulation).
     * <p>
     * Simulates sudden spike in failures (e.g., during system load or
     * network congestion).
     *
     * @param handler fault handler
     * @param partitionIds partitions to fail
     * @param burstDurationMs duration of burst
     */
    public static void injectFailureBurst(
        FaultHandler handler,
        List<UUID> partitionIds,
        long burstDurationMs
    ) {
        var executor = Executors.newScheduledThreadPool(partitionIds.size());

        for (var partitionId : partitionIds) {
            // Random failures within burst window
            var failureCount = 3 + RANDOM.nextInt(5); // 3-7 failures per partition
            for (int i = 0; i < failureCount; i++) {
                var delay = RANDOM.nextLong(burstDurationMs);
                executor.schedule(
                    () -> {
                        // Randomly choose failure type
                        var failureType = RANDOM.nextInt(3);
                        switch (failureType) {
                            case 0 -> handler.reportBarrierTimeout(partitionId);
                            case 1 -> handler.reportSyncFailure(partitionId);
                            case 2 -> handler.reportHeartbeatFailure(partitionId, UUID.randomUUID());
                        }
                    },
                    delay,
                    TimeUnit.MILLISECONDS
                );
            }
        }

        executor.shutdown();
    }

    // ===== Internal Helpers =====

    private static List<UUID> getAllPartitions(FaultHandler handler) {
        // In real implementation, would query handler for all known partitions.
        // For testing, generate a fixed set.
        return List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );
    }

    private static List<UUID> generateNodeIds(int count) {
        var nodeIds = new ArrayList<UUID>(count);
        for (int i = 0; i < count; i++) {
            nodeIds.add(UUID.randomUUID());
        }
        return nodeIds;
    }

    private static List<UUID> selectCascadePartitions(
        List<UUID> allPartitions,
        UUID firstFailed,
        int depth
    ) {
        var remaining = new ArrayList<>(allPartitions);
        remaining.remove(firstFailed);

        Collections.shuffle(remaining, RANDOM);

        var count = Math.min(depth, remaining.size());
        return remaining.subList(0, count);
    }
}
