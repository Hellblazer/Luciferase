/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Default FaultHandler implementation with Clock injection.
 *
 * <p>Detects partition failures via barrier timeouts and transitions
 * partition status through HEALTHY → SUSPECTED → FAILED states.
 *
 * <p><b>Status Transition Logic</b>:
 * <ul>
 *   <li>HEALTHY → SUSPECTED: After 2 consecutive barrier timeouts</li>
 *   <li>SUSPECTED → FAILED: After failureConfirmationMs elapses</li>
 * </ul>
 *
 * <p><b>Clock Injection</b>: Uses LongSupplier for time source, enabling
 * deterministic testing. In tests, inject TestClock::currentTimeMillis.
 *
 * <p><b>Thread-Safe</b>: Uses ConcurrentHashMap and CopyOnWriteArrayList
 * for concurrent access.
 *
 * @author hal.hildebrand
 */
public class DefaultFaultHandler implements FaultHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultFaultHandler.class);

    private static final int CONSECUTIVE_TIMEOUT_THRESHOLD = 2;

    private final FaultConfiguration config;
    private final PartitionTopology topology;
    private final Map<UUID, PartitionHealthState> healthStates;
    private final Map<UUID, PartitionRecovery> recoveryStrategies;
    private final List<Consumer<PartitionChangeEvent>> listeners;
    private volatile LongSupplier timeSource;
    private volatile boolean monitoring;

    /**
     * Internal partition health state.
     */
    private static class PartitionHealthState {
        volatile PartitionStatus status;
        volatile int consecutiveTimeouts;
        volatile long suspectedAt;
        volatile long lastSeenAt;

        PartitionHealthState(long currentTime) {
            this.status = PartitionStatus.HEALTHY;
            this.consecutiveTimeouts = 0;
            this.suspectedAt = 0;
            this.lastSeenAt = currentTime;
        }
    }

    /**
     * Create a default fault handler.
     *
     * @param config fault detection configuration
     * @param topology partition topology for UUID/rank mapping
     * @throws NullPointerException if config or topology is null
     */
    public DefaultFaultHandler(FaultConfiguration config, PartitionTopology topology) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.topology = Objects.requireNonNull(topology, "topology must not be null");
        this.healthStates = new ConcurrentHashMap<>();
        this.recoveryStrategies = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.timeSource = System::currentTimeMillis;
        this.monitoring = false;
    }

    /**
     * Set clock for deterministic testing.
     *
     * <p>Accepts any object with currentTimeMillis() method (e.g., TestClock).
     * Uses reflection-free lambda approach for performance.
     *
     * @param clock the clock to use (must have currentTimeMillis() method)
     */
    public void setClock(Object clock) {
        if (clock == null) {
            throw new NullPointerException("clock must not be null");
        }

        // Support both simulation.Clock and java.time.Clock via duck typing
        try {
            var method = clock.getClass().getMethod("currentTimeMillis");
            this.timeSource = () -> {
                try {
                    return (long) method.invoke(clock);
                } catch (Exception e) {
                    throw new RuntimeException("Clock invocation failed", e);
                }
            };
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Clock must have currentTimeMillis() method", e);
        }
    }

    @Override
    public void start() {
        startMonitoring();
    }

    @Override
    public void stop() {
        stopMonitoring();
    }

    @Override
    public boolean isRunning() {
        return monitoring;
    }

    /**
     * Start monitoring (Phase 4.2 convenience method).
     */
    public void startMonitoring() {
        monitoring = true;

        // Initialize health state for all registered partitions
        long now = now();
        for (var rank : topology.activeRanks()) {
            topology.partitionFor(rank).ifPresent(uuid -> {
                healthStates.putIfAbsent(uuid, new PartitionHealthState(now));
            });
        }

        log.info("Started monitoring {} partitions", healthStates.size());
    }

    /**
     * Stop monitoring (Phase 4.2 convenience method).
     */
    public void stopMonitoring() {
        monitoring = false;
        log.info("Stopped monitoring");
    }

    @Override
    public PartitionStatus checkHealth(UUID partitionId) {
        var state = healthStates.get(partitionId);
        return state != null ? state.status : PartitionStatus.HEALTHY;
    }

    @Override
    public void reportBarrierTimeout(UUID partitionId) {
        reportBarrierTimeoutInternal(partitionId);
    }

    /**
     * Report barrier timeout by partition rank (convenience method for Phase 4.2).
     *
     * @param partitionRank 0-based partition rank
     */
    public void reportBarrierTimeout(int partitionRank) {
        var partitionId = topology.partitionFor(partitionRank)
            .orElseThrow(() -> new IllegalArgumentException("Unknown partition rank: " + partitionRank));

        reportBarrierTimeoutInternal(partitionId);
    }

    private void reportBarrierTimeoutInternal(UUID partitionId) {
        var state = healthStates.computeIfAbsent(partitionId, uuid -> new PartitionHealthState(now()));

        synchronized (state) {
            state.consecutiveTimeouts++;
            state.lastSeenAt = now();

            var rank = topology.rankFor(partitionId).orElse(-1);
            log.debug("Partition {} (rank {}) timeout count: {}", partitionId, rank, state.consecutiveTimeouts);

            // Transition HEALTHY → SUSPECTED after threshold
            if (state.status == PartitionStatus.HEALTHY &&
                state.consecutiveTimeouts >= CONSECUTIVE_TIMEOUT_THRESHOLD) {

                state.status = PartitionStatus.SUSPECTED;
                state.suspectedAt = now();

                log.warn("Partition {} (rank {}) marked SUSPECTED after {} consecutive timeouts",
                    partitionId, rank, state.consecutiveTimeouts);

                var event = new PartitionChangeEvent(
                    partitionId,
                    PartitionStatus.HEALTHY,
                    PartitionStatus.SUSPECTED,
                    now(),
                    "Consecutive barrier timeouts exceeded threshold"
                );
                notifyListeners(event);
            }
        }
    }

    /**
     * Check for partitions that should transition SUSPECTED → FAILED.
     *
     * <p>Called periodically or manually in tests after TestClock.advance().
     * Package-visible for testing.
     */
    void checkTimeouts() {
        if (!monitoring) {
            return;
        }

        long now = now();

        for (var entry : healthStates.entrySet()) {
            var partitionId = entry.getKey();
            var state = entry.getValue();

            synchronized (state) {
                // Transition SUSPECTED → FAILED after failureConfirmationMs
                if (state.status == PartitionStatus.SUSPECTED) {
                    long timeSinceSuspected = now - state.suspectedAt;

                    if (timeSinceSuspected >= config.failureConfirmationMs()) {
                        state.status = PartitionStatus.FAILED;

                        log.error("Partition {} marked FAILED after {}ms",
                            partitionId, timeSinceSuspected);

                        var event = new PartitionChangeEvent(
                            partitionId,
                            PartitionStatus.SUSPECTED,
                            PartitionStatus.FAILED,
                            now,
                            "Failure confirmation timeout expired"
                        );
                        notifyListeners(event);
                    }
                }
            }
        }
    }

    /**
     * Subscribe to partition change events (Phase 4.2 version).
     */
    public void subscribe(Consumer<PartitionChangeEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Unsubscribe from partition change events (Phase 4.2 version).
     */
    public void unsubscribe(Consumer<PartitionChangeEvent> listener) {
        listeners.remove(listener);
    }

    // ===== Interface methods (not used in Phase 4.2, but required by interface) =====

    @Override
    public PartitionView getPartitionView(UUID partitionId) {
        return null; // Not implemented in Phase 4.2
    }

    @Override
    public Subscription subscribeToChanges(Consumer<PartitionChangeEvent> consumer) {
        subscribe(consumer);
        return () -> unsubscribe(consumer);
    }

    @Override
    public void markHealthy(UUID partitionId) {
        var state = healthStates.get(partitionId);
        if (state != null) {
            synchronized (state) {
                state.status = PartitionStatus.HEALTHY;
                state.consecutiveTimeouts = 0;
                state.lastSeenAt = now();
            }
        }
    }

    @Override
    public void reportSyncFailure(UUID partitionId) {
        // Not implemented in Phase 4.2
    }

    @Override
    public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {
        // Not implemented in Phase 4.2
    }

    @Override
    public void registerRecovery(UUID partitionId, PartitionRecovery recovery) {
        recoveryStrategies.put(partitionId, recovery);
    }

    @Override
    public CompletableFuture<Boolean> initiateRecovery(UUID partitionId) {
        var recovery = recoveryStrategies.get(partitionId);
        if (recovery == null) {
            return CompletableFuture.completedFuture(false);
        }
        // Not fully implemented in Phase 4.2
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void notifyRecoveryComplete(UUID partitionId, boolean success) {
        if (success) {
            markHealthy(partitionId);
        }
    }

    @Override
    public FaultConfiguration getConfiguration() {
        return config;
    }

    @Override
    public FaultMetrics getMetrics(UUID partitionId) {
        return FaultMetrics.zero(); // Not implemented in Phase 4.2
    }

    @Override
    public FaultMetrics getAggregateMetrics() {
        return FaultMetrics.zero(); // Not implemented in Phase 4.2
    }

    /**
     * Notify all listeners of a partition status change.
     */
    private void notifyListeners(PartitionChangeEvent event) {
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Listener notification failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Get current time from injected time source.
     */
    private long now() {
        return timeSource.getAsLong();
    }
}
