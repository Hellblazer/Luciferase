package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Basic in-memory FaultHandler implementation for testing and integration scaffolding.
 * <p>
 * This implementation:
 * <ul>
 *   <li>Uses ConcurrentHashMap for partition state storage</li>
 *   <li>Tracks status transitions locally without distributed communication</li>
 *   <li>Provides thread-safe concurrent access</li>
 *   <li>Accumulates metrics per partition</li>
 *   <li>Supports event subscription with CopyOnWriteArrayList</li>
 * </ul>
 * <p>
 * Suitable for:
 * <ul>
 *   <li>Unit testing fault detection logic</li>
 *   <li>Integration test scaffolding</li>
 *   <li>Single-JVM distributed forest testing</li>
 * </ul>
 * <p>
 * Not suitable for:
 * <ul>
 *   <li>Production distributed deployments (no cross-process coordination)</li>
 *   <li>Consensus-based failure detection (no quorum)</li>
 * </ul>
 */
public class SimpleFaultHandler implements FaultHandler {

    private static final Logger log = LoggerFactory.getLogger(SimpleFaultHandler.class);

    private final FaultConfiguration config;
    private final Map<UUID, PartitionState> partitions;
    private final Map<UUID, PartitionRecovery> recoveryStrategies;
    private final List<Consumer<PartitionChangeEvent>> subscribers;
    private final AtomicBoolean running;

    /**
     * Internal partition state.
     */
    private static class PartitionState {
        final UUID partitionId;
        volatile PartitionStatus status;
        volatile long lastSeenMs;
        volatile int nodeCount;
        volatile int healthyNodes;
        volatile FaultMetrics metrics;
        final Set<UUID> failedNodes;

        PartitionState(UUID partitionId) {
            this.partitionId = partitionId;
            this.status = PartitionStatus.HEALTHY;
            this.lastSeenMs = System.currentTimeMillis();
            this.nodeCount = 1;
            this.healthyNodes = 1;
            this.metrics = FaultMetrics.zero();
            this.failedNodes = ConcurrentHashMap.newKeySet();
        }

        synchronized void updateStatus(PartitionStatus newStatus) {
            this.status = newStatus;
            this.lastSeenMs = System.currentTimeMillis();
        }

        synchronized void recordFailure() {
            this.metrics = metrics.withIncrementedFailureCount();
        }

        synchronized void recordRecoveryAttempt() {
            this.metrics = metrics.withIncrementedRecoveryAttempts();
        }

        synchronized void recordRecoverySuccess() {
            this.metrics = metrics.withIncrementedSuccessfulRecoveries();
        }

        synchronized void recordRecoveryFailure() {
            this.metrics = metrics.withIncrementedFailedRecoveries();
        }
    }

    /**
     * Simple view implementation.
     */
    private static class SimplePartitionView implements PartitionView {
        private final UUID partitionId;
        private final PartitionStatus status;
        private final long lastSeenMs;
        private final int nodeCount;
        private final int healthyNodes;
        private final FaultMetrics metrics;

        SimplePartitionView(PartitionState state) {
            this.partitionId = state.partitionId;
            this.status = state.status;
            this.lastSeenMs = state.lastSeenMs;
            this.nodeCount = state.nodeCount;
            this.healthyNodes = state.healthyNodes;
            this.metrics = state.metrics;
        }

        @Override
        public UUID partitionId() {
            return partitionId;
        }

        @Override
        public PartitionStatus status() {
            return status;
        }

        @Override
        public long lastSeenMs() {
            return lastSeenMs;
        }

        @Override
        public int nodeCount() {
            return nodeCount;
        }

        @Override
        public int healthyNodes() {
            return healthyNodes;
        }

        @Override
        public FaultMetrics metrics() {
            return metrics;
        }
    }

    /**
     * Simple subscription implementation.
     */
    private class SimpleSubscription implements Subscription {
        private final Consumer<PartitionChangeEvent> consumer;
        private volatile boolean active = true;

        SimpleSubscription(Consumer<PartitionChangeEvent> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void unsubscribe() {
            if (active) {
                active = false;
                subscribers.remove(consumer);
            }
        }

        boolean isActive() {
            return active;
        }
    }

    /**
     * Construct SimpleFaultHandler with given configuration.
     *
     * @param config fault configuration (heartbeat timeouts, retry limits, etc.)
     */
    public SimpleFaultHandler(FaultConfiguration config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.partitions = new ConcurrentHashMap<>();
        this.recoveryStrategies = new ConcurrentHashMap<>();
        this.subscribers = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
    }

    // ===== Lifecycle =====

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("SimpleFaultHandler started");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            subscribers.clear();
            log.info("SimpleFaultHandler stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ===== Fault Detection =====

    @Override
    public PartitionStatus checkHealth(UUID partitionId) {
        var state = partitions.get(partitionId);
        return state != null ? state.status : null;
    }

    @Override
    public PartitionView getPartitionView(UUID partitionId) {
        var state = partitions.get(partitionId);
        return state != null ? new SimplePartitionView(state) : null;
    }

    @Override
    public Subscription subscribeToChanges(Consumer<PartitionChangeEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        subscribers.add(consumer);
        return new SimpleSubscription(consumer);
    }

    // ===== Health Marking =====

    @Override
    public void markHealthy(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        // Check if partition exists - throw if unknown
        var state = partitions.get(partitionId);
        if (state == null) {
            // Auto-register on first markHealthy call
            state = partitions.computeIfAbsent(partitionId, PartitionState::new);
            notifySubscribers(new PartitionChangeEvent(
                partitionId,
                PartitionStatus.HEALTHY,
                PartitionStatus.HEALTHY,
                System.currentTimeMillis(),
                "Partition registered as healthy"
            ));
            log.info("Partition {} registered as HEALTHY", partitionId);
        } else {
            var oldStatus = state.status;
            if (oldStatus != PartitionStatus.HEALTHY) {
                state.updateStatus(PartitionStatus.HEALTHY);
                notifySubscribers(new PartitionChangeEvent(
                    partitionId,
                    oldStatus,
                    PartitionStatus.HEALTHY,
                    System.currentTimeMillis(),
                    "Partition marked healthy"
                ));
                log.info("Partition {} transitioned {} -> HEALTHY", partitionId, oldStatus);
            }
        }
    }

    // ===== Failure Reporting =====

    @Override
    public void reportBarrierTimeout(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        var state = partitions.computeIfAbsent(partitionId, PartitionState::new);
        var oldStatus = state.status;

        if (oldStatus == PartitionStatus.HEALTHY) {
            transitionToSuspected(state, "Barrier timeout detected");
        } else if (oldStatus == PartitionStatus.SUSPECTED) {
            transitionToFailed(state, "Repeated barrier timeout");
        }
    }

    @Override
    public void reportSyncFailure(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        var state = partitions.computeIfAbsent(partitionId, PartitionState::new);
        var oldStatus = state.status;

        if (oldStatus == PartitionStatus.HEALTHY) {
            transitionToSuspected(state, "Ghost sync failure");
        } else if (oldStatus == PartitionStatus.SUSPECTED) {
            transitionToFailed(state, "Repeated sync failure");
        }
    }

    @Override
    public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        var state = partitions.computeIfAbsent(partitionId, PartitionState::new);
        state.failedNodes.add(nodeId);

        var oldStatus = state.status;
        if (oldStatus == PartitionStatus.HEALTHY) {
            transitionToSuspected(state, "Heartbeat failure for node " + nodeId);
        } else if (oldStatus == PartitionStatus.SUSPECTED) {
            transitionToFailed(state, "Multiple heartbeat failures");
        }
    }

    // ===== Recovery Coordination =====

    @Override
    public void registerRecovery(UUID partitionId, PartitionRecovery recovery) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(recovery, "recovery must not be null");

        recoveryStrategies.put(partitionId, recovery);
        log.debug("Registered recovery strategy for partition {}", partitionId);
    }

    @Override
    public CompletableFuture<Boolean> initiateRecovery(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        var state = partitions.get(partitionId);
        if (state == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Unknown partition: " + partitionId)
            );
        }

        var recovery = recoveryStrategies.get(partitionId);
        if (recovery == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No recovery registered for partition: " + partitionId)
            );
        }

        // Transition to RECOVERING
        var oldStatus = state.status;
        if (oldStatus == PartitionStatus.FAILED || oldStatus == PartitionStatus.SUSPECTED) {
            state.updateStatus(PartitionStatus.RECOVERING);
            state.recordRecoveryAttempt();

            notifySubscribers(new PartitionChangeEvent(
                partitionId,
                oldStatus,
                PartitionStatus.RECOVERING,
                System.currentTimeMillis(),
                "Recovery initiated"
            ));

            log.info("Partition {} transitioned {} -> RECOVERING", partitionId, oldStatus);

            // Delegate to recovery strategy
            return recovery.initiateRecovery(partitionId);
        } else {
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public void notifyRecoveryComplete(UUID partitionId, boolean success) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        var state = partitions.get(partitionId);
        if (state == null) {
            log.warn("Recovery complete notification for unknown partition {}", partitionId);
            return;
        }

        var oldStatus = state.status;
        var newStatus = success ? PartitionStatus.HEALTHY : PartitionStatus.FAILED;

        state.updateStatus(newStatus);

        if (success) {
            state.recordRecoverySuccess();
        } else {
            state.recordRecoveryFailure();
        }

        notifySubscribers(new PartitionChangeEvent(
            partitionId,
            oldStatus,
            newStatus,
            System.currentTimeMillis(),
            success ? "Recovery completed successfully" : "Recovery failed"
        ));

        log.info("Partition {} recovery {}: {} -> {}",
            partitionId, success ? "succeeded" : "failed", oldStatus, newStatus);
    }

    // ===== Configuration & Metrics =====

    @Override
    public FaultConfiguration getConfiguration() {
        return config;
    }

    @Override
    public FaultMetrics getMetrics(UUID partitionId) {
        var state = partitions.get(partitionId);
        return state != null ? state.metrics : null;
    }

    @Override
    public FaultMetrics getAggregateMetrics() {
        var aggregate = FaultMetrics.zero();

        for (var state : partitions.values()) {
            var m = state.metrics;
            aggregate = new FaultMetrics(
                Math.max(aggregate.detectionLatencyMs(), m.detectionLatencyMs()),
                Math.max(aggregate.recoveryLatencyMs(), m.recoveryLatencyMs()),
                aggregate.failureCount() + m.failureCount(),
                aggregate.recoveryAttempts() + m.recoveryAttempts(),
                aggregate.successfulRecoveries() + m.successfulRecoveries(),
                aggregate.failedRecoveries() + m.failedRecoveries()
            );
        }

        return aggregate;
    }

    // ===== Internal Helpers =====

    private void transitionToSuspected(PartitionState state, String reason) {
        var oldStatus = state.status;
        state.updateStatus(PartitionStatus.SUSPECTED);
        state.recordFailure();

        notifySubscribers(new PartitionChangeEvent(
            state.partitionId,
            oldStatus,
            PartitionStatus.SUSPECTED,
            System.currentTimeMillis(),
            reason
        ));

        log.warn("Partition {} transitioned {} -> SUSPECTED: {}",
            state.partitionId, oldStatus, reason);
    }

    private void transitionToFailed(PartitionState state, String reason) {
        var oldStatus = state.status;
        state.updateStatus(PartitionStatus.FAILED);
        state.recordFailure();

        notifySubscribers(new PartitionChangeEvent(
            state.partitionId,
            oldStatus,
            PartitionStatus.FAILED,
            System.currentTimeMillis(),
            reason
        ));

        log.error("Partition {} transitioned {} -> FAILED: {}",
            state.partitionId, oldStatus, reason);
    }

    private void notifySubscribers(PartitionChangeEvent event) {
        if (!running.get()) {
            return; // Don't deliver events after stop
        }

        for (var subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("Subscriber threw exception processing event {}: {}",
                    event, e.getMessage());
            }
        }
    }
}
