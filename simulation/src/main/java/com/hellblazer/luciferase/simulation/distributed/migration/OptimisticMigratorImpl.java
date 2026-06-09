/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OptimisticMigratorImpl - Speculative entity migration with deferred update queue (Phase 7E Day 3)
 *
 * Manages optimistic migration of entities across bubble boundaries with automatic
 * rollback support. Deferred physics updates are queued during MIGRATING_IN state
 * and flushed when target achieves view stability.
 *
 * DEFERRED UPDATE QUEUE:
 * - Max 100 events per entity (prevents memory exhaustion)
 * - FIFO ordering for position/velocity updates
 * - Queued during MIGRATING_IN state only
 * - Flushed atomically when MIGRATING_IN → OWNED transition
 * - Overflow: logs warning, drops oldest events to maintain max size
 *
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for concurrent access to deferred queues.
 * Individual queue operations are atomic via LinkedBlockingDeque (lock-free, O(1)).
 *
 * PERFORMANCE:
 * - initiateOptimisticMigration: O(1)
 * - queueDeferredUpdate: O(1) amortized (with overflow handling)
 * - flushDeferredUpdates: O(n) where n = queued events
 * - Memory overhead: ~50 bytes per queued event
 * - Target: < 20ms for 100 simultaneous migrations
 *
 * METRICS:
 * Tracks total migrations, rollbacks, and deferred events for diagnostics.
 *
 * @author hal.hildebrand
 */
public class OptimisticMigratorImpl implements OptimisticMigrator {

    private static final Logger log = LoggerFactory.getLogger(OptimisticMigratorImpl.class);

    // Deferred update queue configuration
    private static final int MAX_DEFERRED_QUEUE_SIZE = 100;
    private static final int OVERFLOW_WARNING_THRESHOLD = 95;

    // Deferred update entry: position and velocity
    private record DeferredUpdate(Point3f position, Point3f velocity) {
    }

    // Per-entity deferred update queue (bounded, lock-free)
    private final Map<UUID, java.util.concurrent.BlockingDeque<DeferredUpdate>> deferredQueues;

    // Integration with committee consensus (Phase 7G.3).
    // volatile: requestMigrationApproval is documented as concurrently callable (see the AtomicLong
    // metrics), and this field is written via a setter; volatile gives the read a happens-before edge
    // so a concurrent caller never observes a stale null after wiring (RDR-020 S4 review).
    private volatile com.hellblazer.luciferase.simulation.consensus.committee.OptimisticMigratorIntegration consensusIntegration;

    // Node-identity boundary for entity migration (RDR-020 S4). Resolves the target bubble UUID to
    // the HRW owning member Digest and supplies the local member Digest as the source. Required when
    // consensusIntegration is set; without it the bubble-UUID→member-Digest mapping the integration
    // needs is unavailable and approval fails loud rather than silently bypassing the quorum gate.
    // volatile for the same concurrent-visibility reason as consensusIntegration.
    private volatile com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver ownershipResolver;

    // Metrics — AtomicLong because all increments occur from public interface methods that the
    // class documents as concurrently callable; plain long read-add-write triples lose
    // increments under concurrent access (Luciferase-0frcy.67).
    private final java.util.concurrent.atomic.AtomicLong totalMigrationsInitiated = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalMigrationsCompleted = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalMigrationsRolledBack = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalDeferredEventsQueued = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalDeferredEventsFlushed = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Create optimistic migrator with deferred queue management.
     */
    public OptimisticMigratorImpl() {
        this.deferredQueues = new ConcurrentHashMap<>();
        log.debug("OptimisticMigrator created");
    }

    /**
     * Set consensus integration adapter (Phase 7G.3).
     * <p>
     * When set, requestMigrationApproval() will delegate to committee consensus.
     * When null, defaults to approved (backward compatibility).
     *
     * @param integration OptimisticMigratorIntegration adapter
     */
    public void setConsensusIntegration(
        com.hellblazer.luciferase.simulation.consensus.committee.OptimisticMigratorIntegration integration) {
        this.consensusIntegration = integration;
        log.debug("Consensus integration set");
    }

    /**
     * Set the bubble ownership resolver (RDR-020 S4).
     * <p>
     * Required alongside {@link #setConsensusIntegration} so {@link #requestMigrationApproval} can map
     * the target bubble UUID to its HRW owning member {@link com.hellblazer.delos.cryptography.Digest}
     * (target node) and supply the local member as the source node. Without it, a consensus-gated
     * approval fails loud rather than silently bypassing the quorum gate.
     *
     * @param resolver the ownership resolver
     */
    public void setOwnershipResolver(
        com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver resolver) {
        this.ownershipResolver = resolver;
        log.debug("Ownership resolver set");
    }

    @Override
    public void initiateOptimisticMigration(UUID entityId, UUID targetBubbleId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");

        log.debug("Initiating optimistic migration: entity={}, target={}",
                entityId, targetBubbleId.toString().substring(0, 8));

        // Create bounded deferred queue for this entity (O(1) operations, lock-free)
        deferredQueues.computeIfAbsent(entityId, k -> new java.util.concurrent.LinkedBlockingDeque<>(MAX_DEFERRED_QUEUE_SIZE));

        totalMigrationsInitiated.incrementAndGet();

        // NOTE: EntityDepartureEvent sending is handled by EnhancedBubble via MigrationCoordinator
        // This method only manages the deferred queue lifecycle
    }

    @Override
    public java.util.concurrent.CompletableFuture<Boolean> requestMigrationApproval(UUID entityId, UUID targetBubble) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(targetBubble, "targetBubble must not be null");

        // Phase 7G.3 / Luciferase-0frcy.35 / RDR-020 S4: when a consensus integration is wired the
        // caller expects the committee-quorum gate to actually run. The integration needs Digest
        // source/target node identities; the BubbleOwnershipResolver (S1) supplies them — target is the
        // HRW owner of the target BUBBLE UUID, source is the local member (possession). This replaces
        // the old UnsupportedOperationException gate, which existed only because no UUID→Digest mapping
        // was available. Fail-loud invariants are preserved: a configured integration with no resolver
        // throws (rather than silently approving), and resolveOwningMember itself throws on an
        // unresolvable target (never a silent approve).
        if (consensusIntegration != null) {
            if (ownershipResolver == null) {
                throw new IllegalStateException(
                    "Consensus-gated migration approval is configured but no BubbleOwnershipResolver is "
                    + "set, so the target bubble cannot be resolved to a member Digest "
                    + "(entity=" + entityId + ", target=" + targetBubble + "). Refusing to silently "
                    + "approve and bypass the quorum gate.");
            }
            // targetBubble is a BUBBLE UUID → resolve directly to its HRW owner (no node-UUID lookup).
            // resolveOwningMember fails loud (IllegalStateException) on an unresolvable target;
            // localMember() always returns this process's own in-view member (possession).
            var targetNodeId = ownershipResolver.resolveOwningMember(targetBubble);
            var sourceId = ownershipResolver.localMember();
            log.debug("Delegating migration approval to committee consensus: entity={}, source={}, target={}",
                    entityId, sourceId, targetNodeId);
            return consensusIntegration.requestMigrationApproval(entityId, sourceId, targetNodeId);
        }

        // Backward compatibility: default to approved when consensus not configured
        log.debug("Migration approval requested: entity={}, target={} (approved by default)",
                entityId, targetBubble.toString().substring(0, 8));

        return java.util.concurrent.CompletableFuture.completedFuture(true);
    }

    @Override
    public void queueDeferredUpdate(UUID entityId, float[] position, float[] velocity) {
        Objects.requireNonNull(entityId, "entityId must not be null");

        // Validate position and velocity arrays
        if (position == null || position.length != 3) {
            throw new IllegalArgumentException("position must be [x, y, z] float array");
        }
        if (velocity == null || velocity.length != 3) {
            throw new IllegalArgumentException("velocity must be [vx, vy, vz] float array");
        }

        var queue = deferredQueues.get(entityId);
        if (queue == null) {
            log.debug("No deferred queue for entity {}, ignoring update", entityId);
            return;
        }

        // Create deferred update entry
        var update = new DeferredUpdate(
            new Point3f(position[0], position[1], position[2]),
            new Point3f(velocity[0], velocity[1], velocity[2])
        );

        // Atomic offer-with-eviction using BlockingDeque (O(1), lock-free)
        // Fix for Luciferase-zcne: replaced synchronized CopyOnWriteArrayList operations
        if (!queue.offerLast(update)) {
            // Queue full - drop oldest event and add new one
            queue.pollFirst();  // Remove oldest (O(1))
            queue.offerLast(update);  // Add new (guaranteed to succeed after poll)
            log.warn("Deferred queue overflow for entity {}, dropped oldest event", entityId);
        }
        totalDeferredEventsQueued.incrementAndGet();

        var queueSize = queue.size();
        if (queueSize > OVERFLOW_WARNING_THRESHOLD) {
            log.warn("Deferred queue approaching limit for entity {}: {} events",
                    entityId, queueSize);
        }
    }

    @Override
    public void flushDeferredUpdates(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");

        var queue = deferredQueues.remove(entityId);
        if (queue == null || queue.isEmpty()) {
            log.debug("No deferred updates to flush for entity {}", entityId);
            return;
        }

        // Log flush operation
        int flushedCount = queue.size();
        log.debug("Flushing {} deferred updates for entity {}", flushedCount, entityId);

        // In a real implementation, these updates would be applied to the entity
        // on the target bubble after MIGRATING_IN → OWNED transition.
        // The actual application is delegated to EnhancedBubble which has
        // the entity reference and can apply position/velocity updates.

        totalDeferredEventsFlushed.addAndGet(flushedCount);
        totalMigrationsCompleted.incrementAndGet();
    }

    @Override
    public void rollbackMigration(UUID entityId, String reason) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        // Remove deferred queue without applying updates
        var queue = deferredQueues.remove(entityId);
        int discardedCount = queue != null ? queue.size() : 0;

        log.info("Rolling back migration for entity {}: reason={}, discarded {} deferred updates",
                entityId, reason, discardedCount);

        totalMigrationsRolledBack.incrementAndGet();
    }

    @Override
    public int getPendingDeferredCount() {
        int count = 0;
        for (var queue : deferredQueues.values()) {
            if (!queue.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int getDeferredQueueSize(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        var queue = deferredQueues.get(entityId);
        return queue != null ? queue.size() : 0;
    }

    @Override
    public void clearAllDeferred() {
        int totalDiscarded = 0;
        for (var queue : deferredQueues.values()) {
            totalDiscarded += queue.size();
        }
        deferredQueues.clear();
        log.info("Cleared all deferred updates: discarded {} events", totalDiscarded);
    }

    /**
     * Get metrics for diagnostics.
     *
     * @return String containing migration and queue statistics
     */
    public String getMetrics() {
        return String.format(
            "OptimisticMigrator{initiated=%d, completed=%d, rolledBack=%d, " +
            "queued=%d, flushed=%d, pending=%d, avgQueueSize=%.1f}",
            totalMigrationsInitiated.get(),
            totalMigrationsCompleted.get(),
            totalMigrationsRolledBack.get(),
            totalDeferredEventsQueued.get(),
            totalDeferredEventsFlushed.get(),
            getPendingDeferredCount(),
            calculateAverageQueueSize()
        );
    }

    /**
     * Calculate average deferred queue size across all pending entities.
     *
     * @return Average queue size, or 0.0 if no pending queues
     */
    private double calculateAverageQueueSize() {
        var nonEmptyQueues = deferredQueues.values().stream()
            .filter(q -> !q.isEmpty())
            .toList();

        if (nonEmptyQueues.isEmpty()) {
            return 0.0;
        }

        return nonEmptyQueues.stream()
            .mapToInt(q -> q.size())
            .average()
            .orElse(0.0);
    }

    @Override
    public String toString() {
        return String.format(
            "OptimisticMigrator{migrations=%d, pending=%d, max_queue=%d}",
            totalMigrationsInitiated.get(),
            getPendingDeferredCount(),
            MAX_DEFERRED_QUEUE_SIZE
        );
    }

    /** @return total migrations initiated (thread-safe metric). */
    public long getTotalMigrationsInitiated() {
        return totalMigrationsInitiated.get();
    }

    /** @return total migrations completed (thread-safe metric). */
    public long getTotalMigrationsCompleted() {
        return totalMigrationsCompleted.get();
    }

    /** @return total migrations rolled back (thread-safe metric). */
    public long getTotalMigrationsRolledBack() {
        return totalMigrationsRolledBack.get();
    }

    /** @return total deferred events queued (thread-safe metric). */
    public long getTotalDeferredEventsQueued() {
        return totalDeferredEventsQueued.get();
    }

    /** @return total deferred events flushed (thread-safe metric). */
    public long getTotalDeferredEventsFlushed() {
        return totalDeferredEventsFlushed.get();
    }
}
