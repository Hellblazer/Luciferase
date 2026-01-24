package com.hellblazer.luciferase.lucien.balancing.fault.test;

import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Simulator for partition fault scenarios.
 * <p>
 * Provides controlled simulation of various failure patterns including:
 * <ul>
 *   <li>Healthy operation with periodic heartbeats</li>
 *   <li>Gradual degradation (slowdowns)</li>
 *   <li>Immediate failures (crashes)</li>
 *   <li>Cascading failures (domino effect)</li>
 *   <li>Barrier timeouts</li>
 *   <li>Recovery sequences</li>
 * </ul>
 * <p>
 * Uses ScheduledExecutorService for time-based simulations. Call {@link #cleanup()}
 * after tests to prevent resource leaks.
 * <p>
 * Example usage:
 * <pre>{@code
 * var sim = new PartitionSimulator(partitionId);
 *
 * // Simulate healthy operation
 * sim.simulateHealthy(1000);
 * await().until(() -> sim.getCurrentStatus() == PartitionStatus.HEALTHY);
 *
 * // Simulate failure
 * sim.simulateFailure();
 * assertThat(sim.getCurrentStatus()).isEqualTo(PartitionStatus.FAILED);
 *
 * // Cleanup
 * sim.cleanup();
 * }</pre>
 */
public class PartitionSimulator {

    /**
     * Status transition event.
     */
    public record StatusTransition(
        UUID partitionId,
        PartitionStatus oldStatus,
        PartitionStatus newStatus,
        long timestamp,
        String reason
    ) {
    }

    private final UUID partitionId;
    private final ScheduledExecutorService executor;
    private final List<StatusTransition> statusHistory = new CopyOnWriteArrayList<>();
    private volatile PartitionStatus currentStatus = PartitionStatus.HEALTHY;
    private volatile ScheduledFuture<?> currentTask;

    /**
     * Create simulator for partition.
     *
     * @param partitionId partition identifier
     */
    public PartitionSimulator(UUID partitionId) {
        this.partitionId = Objects.requireNonNull(partitionId, "partitionId must not be null");
        this.executor = Executors.newScheduledThreadPool(1, r -> {
            var thread = new Thread(r, "partition-simulator-" + partitionId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Simulate healthy partition with periodic status checks.
     * <p>
     * Maintains HEALTHY status and simulates regular heartbeats at specified interval.
     *
     * @param heartbeatIntervalMs interval between heartbeats
     */
    public void simulateHealthy(int heartbeatIntervalMs) {
        cancelCurrentTask();
        transitionTo(PartitionStatus.HEALTHY, "simulation started");

        currentTask = executor.scheduleAtFixedRate(
            () -> transitionTo(PartitionStatus.HEALTHY, "heartbeat received"),
            0,
            heartbeatIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Simulate partition slowing down before failure.
     * <p>
     * Transitions to SUSPECTED after specified delay, simulating gradual degradation.
     *
     * @param delayMs delay before transitioning to SUSPECTED
     */
    public void simulateSlowDown(int delayMs) {
        cancelCurrentTask();

        currentTask = executor.schedule(
            () -> transitionTo(PartitionStatus.SUSPECTED, "partition slowing down"),
            delayMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Simulate immediate partition failure.
     * <p>
     * Transitions to FAILED immediately, simulating crash or network partition.
     */
    public void simulateFailure() {
        cancelCurrentTask();
        transitionTo(PartitionStatus.FAILED, "partition crashed");
    }

    /**
     * Simulate cascading failure affecting multiple partitions.
     * <p>
     * This partition fails first, then triggers failures in affected partitions
     * with cascading delay.
     *
     * @param affectedPartitions other partitions to fail
     * @param cascadeDelayMs delay between cascade steps
     * @param simulators simulator instances for affected partitions
     */
    public void simulateCascadingFailure(
        List<UUID> affectedPartitions,
        int cascadeDelayMs,
        List<PartitionSimulator> simulators
    ) {
        cancelCurrentTask();

        // This partition fails immediately
        transitionTo(PartitionStatus.FAILED, "initial failure (cascade trigger)");

        // Schedule cascading failures
        for (int i = 0; i < Math.min(affectedPartitions.size(), simulators.size()); i++) {
            var delay = (i + 1) * cascadeDelayMs;
            var sim = simulators.get(i);

            executor.schedule(
                () -> sim.transitionTo(PartitionStatus.FAILED, "cascading failure"),
                delay,
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Simulate barrier timeout.
     * <p>
     * Transitions to SUSPECTED, simulating barrier synchronization failure.
     */
    public void simulateBarrierTimeout() {
        cancelCurrentTask();
        transitionTo(PartitionStatus.SUSPECTED, "barrier timeout detected");
    }

    /**
     * Simulate recovery in progress.
     * <p>
     * Transitions to RECOVERING and schedules completion after specified duration.
     *
     * @param recoveryDurationMs time to complete recovery
     */
    public void simulateRecoveryInProgress(int recoveryDurationMs) {
        cancelCurrentTask();
        transitionTo(PartitionStatus.RECOVERING, "recovery initiated");

        currentTask = executor.schedule(
            () -> transitionTo(PartitionStatus.HEALTHY, "recovery completed"),
            recoveryDurationMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Simulate recovery completion (immediate transition to HEALTHY).
     */
    public void simulateRecoveryComplete() {
        cancelCurrentTask();
        transitionTo(PartitionStatus.HEALTHY, "recovery completed successfully");
    }

    /**
     * Simulate recovery failure (transition to DEGRADED).
     */
    public void simulateRecoveryFailure() {
        cancelCurrentTask();
        transitionTo(PartitionStatus.DEGRADED, "recovery failed, running degraded");
    }

    /**
     * Get current partition status.
     *
     * @return current status
     */
    public PartitionStatus getCurrentStatus() {
        return currentStatus;
    }

    /**
     * Get status transition history.
     *
     * @return unmodifiable list of transitions
     */
    public List<StatusTransition> getStatusHistory() {
        return List.copyOf(statusHistory);
    }

    /**
     * Get partition ID.
     *
     * @return partition identifier
     */
    public UUID getPartitionId() {
        return partitionId;
    }

    /**
     * Cleanup resources (stop executor).
     * <p>
     * MUST be called after simulation to prevent thread leaks.
     */
    public void cleanup() {
        cancelCurrentTask();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                // Force termination
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== Internal Helpers =====

    private void transitionTo(PartitionStatus newStatus, String reason) {
        var oldStatus = currentStatus;
        currentStatus = newStatus;

        var transition = new StatusTransition(
            partitionId,
            oldStatus,
            newStatus,
            System.currentTimeMillis(),
            reason
        );
        statusHistory.add(transition);
    }

    private void cancelCurrentTask() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(false);
        }
    }
}
