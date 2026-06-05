package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.common.time.Clock;
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
    private volatile Clock clock = Clock.system();
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

        PartitionState(UUID partitionId, long nowMs) {
            this.partitionId = partitionId;
            this.status = PartitionStatus.HEALTHY;
            this.lastSeenMs = nowMs;
            this.nodeCount = 1;
            this.healthyNodes = 1;
            this.metrics = FaultMetrics.zero();
            this.failedNodes = ConcurrentHashMap.newKeySet();
        }

        synchronized void updateStatus(PartitionStatus newStatus, long nowMs) {
            this.status = newStatus;
            this.lastSeenMs = nowMs;
        }

        /**
         * Atomically escalate fault status: HEALTHY->SUSPECTED or SUSPECTED->FAILED.
         * Reads and updates status under this object's monitor to close the TOCTOU
         * window where two concurrent callers both observe HEALTHY and both transition
         * to SUSPECTED (or both skip the SUSPECTED->FAILED step).
         *
         * @param firstStepReason  reason used when HEALTHY->SUSPECTED
         * @param secondStepReason reason used when SUSPECTED->FAILED
         * @param nowMs            current time from the outer handler's injected Clock
         * @return a PartitionChangeEvent describing the transition, or null
         *         if no transition was needed (status already FAILED)
         */
        synchronized PartitionChangeEvent escalate(String firstStepReason, String secondStepReason, long nowMs) {
            var oldStatus = this.status;
            PartitionStatus newStatus;
            String reason;
            if (oldStatus == PartitionStatus.HEALTHY) {
                newStatus = PartitionStatus.SUSPECTED;
                reason = firstStepReason;
            } else if (oldStatus == PartitionStatus.SUSPECTED) {
                newStatus = PartitionStatus.FAILED;
                reason = secondStepReason;
            } else {
                return null; // already FAILED, no further escalation
            }
            this.status = newStatus;
            this.lastSeenMs = nowMs;
            this.metrics = metrics.withIncrementedFailureCount();
            return new PartitionChangeEvent(partitionId, oldStatus, newStatus, this.lastSeenMs, reason);
        }

        /**
         * Atomically transition directly to FAILED regardless of current status.
         * Returns a PartitionChangeEvent if the status actually changed, or
         * null if the partition was already FAILED.
         *
         * @param reason description of why the partition was forced to FAILED
         * @param nowMs  current time from the injected Clock (not System.currentTimeMillis())
         */
        synchronized PartitionChangeEvent forceFailed(String reason, long nowMs) {
            var oldStatus = this.status;
            if (oldStatus == PartitionStatus.FAILED) {
                return null; // already FAILED, no-op
            }
            this.status = PartitionStatus.FAILED;
            this.lastSeenMs = nowMs;
            this.metrics = metrics.withIncrementedFailureCount();
            return new PartitionChangeEvent(partitionId, oldStatus, PartitionStatus.FAILED, this.lastSeenMs, reason);
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

    /**
     * Set clock for deterministic testing (mirrors DefaultFaultHandler.setClock).
     *
     * @param clock the clock to use; must not be null
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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

        var nowMs = clock.currentTimeMillis();
        // Check if partition exists - throw if unknown
        var state = partitions.get(partitionId);
        if (state == null) {
            // Auto-register on first markHealthy call
            state = partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, nowMs));
            notifySubscribers(new PartitionChangeEvent(
                partitionId,
                PartitionStatus.HEALTHY,
                PartitionStatus.HEALTHY,
                nowMs,
                "Partition registered as healthy"
            ));
            log.info("Partition {} registered as HEALTHY", partitionId);
        } else {
            var oldStatus = state.status;
            if (oldStatus != PartitionStatus.HEALTHY) {
                state.updateStatus(PartitionStatus.HEALTHY, nowMs);
                notifySubscribers(new PartitionChangeEvent(
                    partitionId,
                    oldStatus,
                    PartitionStatus.HEALTHY,
                    nowMs,
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
        var nowMs = clock.currentTimeMillis();
        var state = partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, nowMs));
        applyEscalation(state, "Barrier timeout detected", "Repeated barrier timeout", nowMs);
    }

    @Override
    public void reportSyncFailure(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        var nowMs = clock.currentTimeMillis();
        var state = partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, nowMs));
        applyEscalation(state, "Ghost sync failure", "Repeated sync failure", nowMs);
    }

    @Override
    public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        var nowMs = clock.currentTimeMillis();
        var state = partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, nowMs));
        state.failedNodes.add(nodeId);
        applyEscalation(state, "Heartbeat failure for node " + nodeId, "Multiple heartbeat failures", nowMs);
    }

    @Override
    public void reportHeartbeatFailure(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        var nowMs = clock.currentTimeMillis();
        var state = partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, nowMs));
        // No nodeId added - avoids polluting failedNodes with fabricated UUIDs
        applyEscalation(state, "Heartbeat timeout for partition", "Multiple heartbeat timeouts", nowMs);
    }

    @Override
    public void reportPartitionFailed(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");
        var nowMs = clock.currentTimeMillis();
        var state = partitions.computeIfAbsent(partitionId, id -> new PartitionState(id, nowMs));
        var event = state.forceFailed("Definitive partition failure reported", nowMs);
        if (event != null) {
            notifySubscribers(event);
            log.error("Partition {} driven directly to FAILED: {} -> FAILED", partitionId, event.oldStatus());
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

        // Transition to recovery (Phase 4.1: Recovery tracked separately via RecoveryPhase)
        var oldStatus = state.status;
        if (oldStatus == PartitionStatus.FAILED || oldStatus == PartitionStatus.SUSPECTED) {
            // TODO Phase 4.2: Implement proper recovery coordination
            // state.updateStatus(PartitionStatus.RECOVERING);
            state.recordRecoveryAttempt();

            notifySubscribers(new PartitionChangeEvent(
                partitionId,
                oldStatus,
                oldStatus, // TEMP: Keep same status until Phase 4.2
                clock.currentTimeMillis(),
                "Recovery initiated"
            ));

            log.info("Partition {} initiating recovery (Phase 4.2)", partitionId);

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
        var nowMs = clock.currentTimeMillis();

        state.updateStatus(newStatus, nowMs);

        if (success) {
            state.recordRecoverySuccess();
        } else {
            state.recordRecoveryFailure();
        }

        notifySubscribers(new PartitionChangeEvent(
            partitionId,
            oldStatus,
            newStatus,
            nowMs,
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

    /**
     * Atomically escalate state by one fault level (HEALTHY->SUSPECTED or
     * SUSPECTED->FAILED) under state's monitor, then notify subscribers and log.
     * If the state is already FAILED, this is a no-op.
     *
     * @param state             the partition to escalate
     * @param firstStepReason   reason string for the HEALTHY->SUSPECTED transition
     * @param secondStepReason  reason string for the SUSPECTED->FAILED transition
     * @param nowMs             current time from the outer handler's injected Clock
     */
    private void applyEscalation(PartitionState state, String firstStepReason, String secondStepReason, long nowMs) {
        var event = state.escalate(firstStepReason, secondStepReason, nowMs);
        if (event == null) {
            return; // already FAILED, no further escalation
        }
        notifySubscribers(event);
        if (event.newStatus() == PartitionStatus.SUSPECTED) {
            log.warn("Partition {} transitioned {} -> SUSPECTED: {}",
                state.partitionId, event.oldStatus(), event.reason());
        } else {
            log.error("Partition {} transitioned {} -> FAILED: {}",
                state.partitionId, event.oldStatus(), event.reason());
        }
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
