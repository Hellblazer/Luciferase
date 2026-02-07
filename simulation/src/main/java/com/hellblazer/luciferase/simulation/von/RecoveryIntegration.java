/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionChangeEvent;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionTopology;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Connects VON (Virtual Organization Network) topology with partition recovery system (Phase 4.2).
 * <p>
 * RecoveryIntegration provides bidirectional synchronization between:
 * <ul>
 *   <li><strong>VON Layer</strong> - Distributed spatial perception via Manager/Bubble</li>
 *   <li><strong>Recovery Layer</strong> - Partition fault tolerance via FaultHandler/PartitionTopology</li>
 * </ul>
 * <p>
 * <strong>Integration Points:</strong>
 * <ol>
 *   <li><strong>VON Topology → Partition Topology</strong>: Map VON neighbor relationships to partition neighbors</li>
 *   <li><strong>Failure Detection</strong>: VON neighbor unreachability triggers partition fault detection</li>
 *   <li><strong>Recovery Propagation</strong>: Partition recovery re-establishes VON connections</li>
 *   <li><strong>Consistency Maintenance</strong>: Keep VON and partition views synchronized</li>
 * </ol>
 * <p>
 * <strong>Event Flow:</strong>
 * <pre>
 * VON Events → Recovery System:
 *   VON Leave → FaultHandler.reportSyncFailure()
 *   Multiple failures from same partition → Escalate to partition-level fault
 *
 * Recovery Events → VON System:
 *   Partition FAILED → HEALTHY → Rejoin VON bubbles in that partition
 *   Recovery completion → Update VON neighbor topology
 * </pre>
 * <p>
 * <strong>Mapping Strategy:</strong>
 * <ul>
 *   <li>Each VON bubble belongs to exactly one partition</li>
 *   <li>Partition neighbors derived from VON neighbor relationships</li>
 *   <li>Partition rank assigned based on registration order</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> All public methods are thread-safe. Uses concurrent collections
 * for bubble-partition mapping and event subscription.
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var integration = new RecoveryIntegration(vonManager, topology, faultHandler);
 *
 * // Register bubbles with their partitions
 * integration.registerBubble(bubble1.id(), partition1);
 * integration.registerBubble(bubble2.id(), partition2);
 *
 * // Integration handles events automatically:
 * // - VON neighbor leave → partition fault detection
 * // - Partition recovery → VON rejoin
 * </pre>
 *
 * @author hal.hildebrand
 * @see Manager
 * @see FaultHandler
 * @see PartitionTopology
 */
public class RecoveryIntegration {

    private static final Logger log = LoggerFactory.getLogger(RecoveryIntegration.class);
    private static final long DEFAULT_RECOVERY_TIMEOUT_MS = 30_000L;  // 30 seconds
    private static final int MAX_RECOVERY_DEPTH = 10;  // Phase 3: Maximum cascading recovery depth
    private static final long RECOVERY_COOLDOWN_MS = 1000L;  // Phase 3: Minimum time between recoveries

    private final Manager vonManager;
    private final PartitionTopology topology;
    private final FaultHandler faultHandler;
    private final Map<UUID, UUID> bubbleToPartition;
    private final Map<UUID, Set<UUID>> partitionBubbles;
    private final AtomicInteger rankCounter;
    private final Consumer<Event> vonEventHandler;
    private final Consumer<PartitionChangeEvent> recoveryEventHandler;
    private final Map<UUID, Integer> partitionRanks;

    // Phase 2: Clock injection for deterministic testing
    private volatile Clock clock;

    // Phase 2: Recovery timeout
    private volatile long recoveryTimeoutMs = DEFAULT_RECOVERY_TIMEOUT_MS;

    // Phase 2: Recovery dependencies (partition → set of partitions it depends on)
    private final Map<UUID, Set<UUID>> recoveryDependencies;

    // Phase 2: Recovery metrics tracking
    private final Map<UUID, RecoveryMetricsTracker> recoveryMetricsTrackers;

    // Phase 3: Cooldown tracking - last recovery time per partition
    private final Map<UUID, Long> lastRecoveryTime;

    /**
     * Create VON Recovery Integration with all dependencies using system clock.
     *
     * @param vonManager   VON manager coordinating distributed bubbles
     * @param topology     Partition topology for UUID ↔ rank mapping
     * @param faultHandler Fault handler for partition recovery coordination
     * @throws NullPointerException if any parameter is null
     */
    public RecoveryIntegration(Manager vonManager,
                                  PartitionTopology topology,
                                  FaultHandler faultHandler) {
        this(vonManager, topology, faultHandler, Clock.system());
    }

    /**
     * Create VON Recovery Integration with all dependencies and injected clock.
     * <p>
     * Use this constructor for deterministic testing with a TestClock.
     *
     * @param vonManager   VON manager coordinating distributed bubbles
     * @param topology     Partition topology for UUID ↔ rank mapping
     * @param faultHandler Fault handler for partition recovery coordination
     * @param clock        Clock for timestamps (use TestClock for testing)
     * @throws NullPointerException if any parameter is null
     */
    public RecoveryIntegration(Manager vonManager,
                                  PartitionTopology topology,
                                  FaultHandler faultHandler,
                                  Clock clock) {
        this.vonManager = Objects.requireNonNull(vonManager, "vonManager cannot be null");
        this.topology = Objects.requireNonNull(topology, "topology cannot be null");
        this.faultHandler = Objects.requireNonNull(faultHandler, "faultHandler cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");

        this.bubbleToPartition = new ConcurrentHashMap<>();
        this.partitionBubbles = new ConcurrentHashMap<>();
        this.partitionRanks = new ConcurrentHashMap<>();
        this.rankCounter = new AtomicInteger(0);

        // Phase 2 collections
        this.recoveryDependencies = new ConcurrentHashMap<>();
        this.recoveryMetricsTrackers = new ConcurrentHashMap<>();
        this.lastRecoveryTime = new ConcurrentHashMap<>();

        // Create event handlers
        this.vonEventHandler = this::handleVonEvent;
        this.recoveryEventHandler = this::handleRecoveryEvent;

        // Subscribe to events
        vonManager.addEventListener(vonEventHandler);
        faultHandler.subscribeToChanges(recoveryEventHandler);

        log.info("RecoveryIntegration initialized with clock");
    }

    /**
     * Set the recovery timeout for bubble rejoin operations.
     *
     * @param timeoutMs Timeout in milliseconds (default: 30000)
     */
    public void setRecoveryTimeoutMs(long timeoutMs) {
        this.recoveryTimeoutMs = timeoutMs;
        log.debug("Recovery timeout set to {}ms", timeoutMs);
    }

    /**
     * Add a recovery dependency between partitions.
     * <p>
     * When the dependent partition recovers, cascading recovery will be triggered
     * for partitions that depend on it.
     *
     * @param partition   Partition that has the dependency
     * @param dependsOn   Partition that must recover first
     */
    public void addRecoveryDependency(UUID partition, UUID dependsOn) {
        recoveryDependencies.computeIfAbsent(dependsOn, k -> ConcurrentHashMap.newKeySet()).add(partition);
        log.debug("Added recovery dependency: {} depends on {}", partition, dependsOn);
    }

    /**
     * Remove a recovery dependency.
     *
     * @param partition   Partition that has the dependency
     * @param dependsOn   Partition it depends on
     */
    public void removeRecoveryDependency(UUID partition, UUID dependsOn) {
        var dependents = recoveryDependencies.get(dependsOn);
        if (dependents != null) {
            dependents.remove(partition);
            if (dependents.isEmpty()) {
                recoveryDependencies.remove(dependsOn);
            }
        }
    }

    /**
     * Get recovery metrics for a partition.
     *
     * @param partitionId Partition UUID
     * @return RecoveryMetrics or null if no metrics tracked
     */
    public RecoveryMetrics getRecoveryMetrics(UUID partitionId) {
        var tracker = recoveryMetricsTrackers.get(partitionId);
        return tracker != null ? tracker.toMetrics() : null;
    }

    /**
     * Register a VON bubble as part of a partition.
     * <p>
     * Establishes the bubble → partition mapping and registers the partition in
     * the topology (if not already registered). Partition rank is assigned on
     * first registration.
     * <p>
     * Thread-safe: Multiple threads can register bubbles concurrently.
     *
     * @param bubbleId    VON bubble UUID
     * @param partitionId Partition UUID
     * @throws NullPointerException if bubbleId or partitionId is null
     */
    public void registerBubble(UUID bubbleId, UUID partitionId) {
        Objects.requireNonNull(bubbleId, "bubbleId cannot be null");
        Objects.requireNonNull(partitionId, "partitionId cannot be null");

        bubbleToPartition.put(bubbleId, partitionId);
        partitionBubbles.computeIfAbsent(partitionId, k -> ConcurrentHashMap.newKeySet()).add(bubbleId);

        // Register partition in topology if not already present
        if (topology.rankFor(partitionId).isEmpty()) {
            var rank = rankCounter.getAndIncrement();
            topology.register(partitionId, rank);
            partitionRanks.put(partitionId, rank);
            log.debug("Registered partition {} with rank {}", partitionId, rank);
        }

        log.debug("Registered bubble {} to partition {}", bubbleId, partitionId);
    }

    /**
     * Unregister a VON bubble from its partition.
     * <p>
     * Removes the bubble → partition mapping. If this was the last bubble in the
     * partition, the partition is unregistered from the topology.
     *
     * @param bubbleId VON bubble UUID
     */
    public void unregisterBubble(UUID bubbleId) {
        var partitionId = bubbleToPartition.remove(bubbleId);
        if (partitionId != null) {
            var bubbles = partitionBubbles.get(partitionId);
            if (bubbles != null) {
                bubbles.remove(bubbleId);
                if (bubbles.isEmpty()) {
                    partitionBubbles.remove(partitionId);
                    topology.unregister(partitionId);
                    partitionRanks.remove(partitionId);
                    log.debug("Unregistered partition {} (no more bubbles)", partitionId);
                }
            }
            log.debug("Unregistered bubble {} from partition {}", bubbleId, partitionId);
        }
    }

    /**
     * Get the partition ID for a given bubble.
     *
     * @param bubbleId VON bubble UUID
     * @return Partition UUID, or null if bubble not registered
     */
    public UUID getPartitionForBubble(UUID bubbleId) {
        return bubbleToPartition.get(bubbleId);
    }

    /**
     * Get all bubbles belonging to a partition.
     *
     * @param partitionId Partition UUID
     * @return Unmodifiable set of bubble UUIDs, or empty set if partition unknown
     */
    public Set<UUID> getBubblesForPartition(UUID partitionId) {
        var bubbles = partitionBubbles.get(partitionId);
        return bubbles != null ? Collections.unmodifiableSet(bubbles) : Set.of();
    }

    /**
     * Get the partition rank for a bubble.
     *
     * @param bubbleId VON bubble UUID
     * @return Partition rank, or empty if bubble not registered
     */
    public Optional<Integer> getPartitionRank(UUID bubbleId) {
        var partitionId = bubbleToPartition.get(bubbleId);
        return partitionId != null ? topology.rankFor(partitionId) : Optional.empty();
    }

    /**
     * Update partition topology based on VON neighbor relationships.
     * <p>
     * Analyzes VON neighbor connections and derives partition-level neighbor
     * relationships. This is useful for detecting partition connectivity issues.
     */
    public void synchronizeTopology() {
        log.debug("Synchronizing partition topology from VON neighbors");

        // Analyze VON neighbor connections
        for (var bubble : vonManager.getAllBubbles()) {
            var bubblePartition = bubbleToPartition.get(bubble.id());
            if (bubblePartition == null) continue;

            var neighbors = bubble.neighbors();
            for (var neighborId : neighbors) {
                var neighborPartition = bubbleToPartition.get(neighborId);
                if (neighborPartition != null && !neighborPartition.equals(bubblePartition)) {
                    // Cross-partition neighbor relationship detected
                    log.trace("VON neighbor {} → {} crosses partitions {} → {}",
                              bubble.id(), neighborId, bubblePartition, neighborPartition);
                }
            }
        }
    }

    /**
     * Close the integration and release resources.
     * <p>
     * Unsubscribes from all events and clears internal state.
     */
    public void close() {
        vonManager.removeEventListener(vonEventHandler);
        // Note: FaultHandler doesn't provide removeEventListener - subscription is permanent
        bubbleToPartition.clear();
        partitionBubbles.clear();
        partitionRanks.clear();
        recoveryDependencies.clear();
        recoveryMetricsTrackers.clear();
        lastRecoveryTime.clear();
        log.info("RecoveryIntegration closed");
    }

    // ========== Phase 3: Recovery Task Record ==========

    /**
     * Recovery task for BFS traversal with depth tracking.
     *
     * @param partitionId Partition to recover
     * @param depth       Current depth in the dependency graph
     */
    private record RecoveryTask(UUID partitionId, int depth) {}

    // ========== Private Event Handlers ==========

    /**
     * Handle VON events (Join/Leave/Move) and propagate to recovery system.
     */
    private void handleVonEvent(Event event) {
        switch (event) {
            case Event.Join join -> onNeighborJoin(join);
            case Event.Leave leave -> onNeighborLeave(leave);
            case Event.Move move -> onNeighborMove(move);
            case Event.GhostSync ghostSync -> onGhostSync(ghostSync);
            default -> log.trace("Ignoring VON event: {}", event.getClass().getSimpleName());
        }
    }

    /**
     * Handle recovery events (partition status changes) and propagate to VON system.
     */
    private void handleRecoveryEvent(PartitionChangeEvent event) {
        log.debug("Recovery event: {} {} → {}",
                  event.partitionId(), event.oldStatus(), event.newStatus());

        // Partition recovered: rejoin VON bubbles
        if (event.newStatus() == PartitionStatus.HEALTHY &&
            event.oldStatus() == PartitionStatus.FAILED) {
            onPartitionRecovered(event.partitionId());
        }

        // Partition failed: notify VON bubbles
        if (event.newStatus() == PartitionStatus.FAILED) {
            onPartitionFailed(event.partitionId());
        }
    }

    private void onNeighborJoin(Event.Join join) {
        var partitionId = bubbleToPartition.get(join.nodeId());
        if (partitionId != null) {
            // Neighbor partition is healthy (bubble successfully joined)
            faultHandler.markHealthy(partitionId);
            log.debug("Marked partition {} healthy (bubble {} joined VON)", partitionId, join.nodeId());
        }
    }

    private void onNeighborLeave(Event.Leave leave) {
        var partitionId = bubbleToPartition.get(leave.nodeId());
        if (partitionId != null) {
            // Potential partition failure - report sync failure
            faultHandler.reportSyncFailure(partitionId);
            log.debug("Reported sync failure for partition {} (bubble {} left VON)", partitionId, leave.nodeId());
        }
    }

    private void onNeighborMove(Event.Move move) {
        // MOVE events don't directly affect partition health
        // Ghost sync handles entity-level updates
        log.trace("Bubble {} moved to {}", move.nodeId(), move.newPosition());
    }

    private void onGhostSync(Event.GhostSync ghostSync) {
        var partitionId = bubbleToPartition.get(ghostSync.sourceBubbleId());
        if (partitionId != null) {
            // Successful ghost sync indicates healthy partition
            faultHandler.markHealthy(partitionId);
            log.trace("Marked partition {} healthy (ghost sync from bubble {})",
                      partitionId, ghostSync.sourceBubbleId());
        }
    }

    /**
     * Phase 3: BFS-based partition recovery with depth limiting and cooldown.
     * <p>
     * Replaces recursive implementation to prevent stack overflow and enforce safety constraints:
     * <ul>
     *   <li>MAX_RECOVERY_DEPTH (10): Limits cascading dependency depth</li>
     *   <li>RECOVERY_COOLDOWN_MS (1000): Prevents rapid re-triggering</li>
     *   <li>BFS traversal: Prevents stack overflow from deep dependency chains</li>
     *   <li>Visited set: Prevents circular dependency loops</li>
     * </ul>
     *
     * @param partitionId Root partition to recover
     */
    private void onPartitionRecovered(UUID partitionId) {
        log.info("Partition {} recovered - rejoining VON bubbles", partitionId);

        // Cooldown check
        var lastTime = lastRecoveryTime.get(partitionId);
        var now = clock.currentTimeMillis();
        if (lastTime != null && (now - lastTime) < RECOVERY_COOLDOWN_MS) {
            log.debug("Skipping recovery for {} - cooldown active ({}ms since last)",
                      partitionId, now - lastTime);
            return;
        }
        lastRecoveryTime.put(partitionId, now);

        // BFS with depth tracking
        var queue = new ArrayDeque<RecoveryTask>();
        var visited = new HashSet<UUID>();
        queue.add(new RecoveryTask(partitionId, 0));
        visited.add(partitionId);

        while (!queue.isEmpty()) {
            var task = queue.poll();
            if (task.depth >= MAX_RECOVERY_DEPTH) {
                log.warn("Max recovery depth ({}) reached for partition {} at depth {}",
                         MAX_RECOVERY_DEPTH, task.partitionId, task.depth);
                continue;
            }

            // Process this partition's recovery
            processPartitionRecovery(task.partitionId);

            // Queue dependent partitions
            var dependents = recoveryDependencies.get(task.partitionId);
            if (dependents != null) {
                for (var dependent : dependents) {
                    if (visited.add(dependent)) {
                        // Update cooldown timestamp for dependent
                        lastRecoveryTime.put(dependent, clock.currentTimeMillis());
                        queue.add(new RecoveryTask(dependent, task.depth + 1));
                    } else {
                        log.debug("Skipping already visited partition {} (cycle prevention)", dependent);
                    }
                }
            }
        }
    }

    /**
     * Phase 3: Process recovery for a single partition (extracted from onPartitionRecovered).
     * <p>
     * Handles bubble rejoin, metrics tracking, and event emission.
     *
     * @param partitionId Partition to process
     */
    private void processPartitionRecovery(UUID partitionId) {
        var recoveryStartTime = clock.currentTimeMillis();
        var bubbles = partitionBubbles.get(partitionId);

        if (bubbles == null || bubbles.isEmpty()) {
            log.warn("No bubbles found for recovered partition {}", partitionId);
            emitRecoveryEvent(partitionId, 0, 0, 0, 0, false);
            return;
        }

        int totalBubbles = bubbles.size();
        int successfulRejoins = 0;
        int failedRejoins = 0;

        // Rejoin all bubbles in this partition
        for (var bubbleId : bubbles) {
            // Check timeout
            if (clock.currentTimeMillis() - recoveryStartTime > recoveryTimeoutMs) {
                log.warn("Recovery timeout ({}ms) for partition {} - {} bubbles not processed",
                        recoveryTimeoutMs, partitionId, totalBubbles - successfulRejoins - failedRejoins);
                failedRejoins += (totalBubbles - successfulRejoins - failedRejoins);
                break;
            }

            var bubble = vonManager.getBubble(bubbleId);
            if (bubble != null) {
                var position = bubble.position();
                var rejoined = vonManager.joinAt(bubble, position);
                if (rejoined) {
                    successfulRejoins++;
                    log.debug("Rejoined bubble {} to VON at {}", bubbleId, position);
                } else {
                    failedRejoins++;
                    log.warn("Failed to rejoin bubble {} to VON", bubbleId);
                }
            } else {
                failedRejoins++;
                log.warn("Bubble {} not found in Manager during recovery", bubbleId);
            }
        }

        var recoveryTimeMs = clock.currentTimeMillis() - recoveryStartTime;

        // Check if cascading was triggered (for metrics)
        boolean cascadeTriggered = recoveryDependencies.containsKey(partitionId)
                                    && !recoveryDependencies.get(partitionId).isEmpty();

        // Update metrics tracker
        var tracker = recoveryMetricsTrackers.computeIfAbsent(partitionId, k -> new RecoveryMetricsTracker());
        tracker.recordRecovery(successfulRejoins, failedRejoins, recoveryTimeMs);

        // Emit recovery event
        emitRecoveryEvent(partitionId, totalBubbles, successfulRejoins, failedRejoins, recoveryTimeMs, cascadeTriggered);

        log.info("Partition {} recovery complete: {}/{} bubbles rejoined in {}ms (cascade={})",
                partitionId, successfulRejoins, totalBubbles, recoveryTimeMs, cascadeTriggered);
    }

    /**
     * Emit a PartitionRecovered event through the VON manager.
     */
    private void emitRecoveryEvent(UUID partitionId, int totalBubbles, int successfulRejoins,
                                   int failedRejoins, long recoveryTimeMs, boolean cascadeTriggered) {
        var event = new Event.PartitionRecovered(
            partitionId, totalBubbles, successfulRejoins, failedRejoins, recoveryTimeMs, cascadeTriggered
        );
        // Emit through vonManager so tests can capture it
        vonManager.dispatchEvent(event);
    }

    private void onPartitionFailed(UUID partitionId) {
        log.warn("Partition {} failed - VON bubbles may become isolated", partitionId);

        var bubbles = partitionBubbles.get(partitionId);
        if (bubbles == null || bubbles.isEmpty()) {
            return;
        }

        // Notify bubbles of partition failure
        // (In production, this might trigger local fallback or isolation mode)
        for (var bubbleId : bubbles) {
            log.debug("Bubble {} affected by partition {} failure", bubbleId, partitionId);
        }
    }

    // ========== Phase 2: Recovery Metrics ==========

    /**
     * Recovery metrics for a partition.
     *
     * @param recoveryCount          Total number of recoveries
     * @param totalSuccessfulRejoins Total bubbles successfully rejoined
     * @param totalFailedRejoins     Total bubbles that failed to rejoin
     * @param averageRecoveryTimeMs  Average recovery time in milliseconds
     * @param lastRecoveryTimeMs     Most recent recovery time
     */
    public record RecoveryMetrics(
        int recoveryCount,
        int totalSuccessfulRejoins,
        int totalFailedRejoins,
        double averageRecoveryTimeMs,
        long lastRecoveryTimeMs
    ) {
        /**
         * @return Overall success ratio across all recoveries
         */
        public double overallSuccessRatio() {
            int total = totalSuccessfulRejoins + totalFailedRejoins;
            return total > 0 ? (double) totalSuccessfulRejoins / total : 1.0;
        }
    }

    /**
     * Internal tracker for recovery metrics.
     */
    private static class RecoveryMetricsTracker {
        private final AtomicInteger recoveryCount = new AtomicInteger(0);
        private final AtomicInteger totalSuccessful = new AtomicInteger(0);
        private final AtomicInteger totalFailed = new AtomicInteger(0);
        private final AtomicLong totalRecoveryTimeMs = new AtomicLong(0);
        private volatile long lastRecoveryTimeMs = 0;

        void recordRecovery(int successful, int failed, long recoveryTimeMs) {
            recoveryCount.incrementAndGet();
            totalSuccessful.addAndGet(successful);
            totalFailed.addAndGet(failed);
            totalRecoveryTimeMs.addAndGet(recoveryTimeMs);
            lastRecoveryTimeMs = recoveryTimeMs;
        }

        RecoveryMetrics toMetrics() {
            int count = recoveryCount.get();
            double avgTime = count > 0 ? (double) totalRecoveryTimeMs.get() / count : 0.0;
            return new RecoveryMetrics(
                count,
                totalSuccessful.get(),
                totalFailed.get(),
                avgTime,
                lastRecoveryTimeMs
            );
        }
    }
}
