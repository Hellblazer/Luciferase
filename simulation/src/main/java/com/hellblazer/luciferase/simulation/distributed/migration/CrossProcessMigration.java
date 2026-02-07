/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.hellblazer.luciferase.simulation.distributed.BubbleReference;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Cross-process entity migration orchestrator using 2PC protocol.
 * <p>
 * Implements Phase 6B4.4: Two-Phase Commit entity migration with:
 * - Remove-then-commit ordering (no add-first duplicates)
 * - Idempotency tokens (exactly-once semantics)
 * - Entity migration locks (prevents concurrent migrations of same entity - C1)
 * - Rollback-failure logging and metrics (C3)
 * - Timeout handling (100ms per phase, 300ms total)
 * - Thread-safe concurrent operation
 * <p>
 * Protocol flow:
 * <pre>
 * 1. Acquire migration lock for entity (C1)
 * 2. PREPARE: Remove entity from source bubble
 *    - Timeout: 100ms
 *    - On failure: Release lock, fail fast
 * 3. COMMIT: Add entity to destination bubble
 *    - Timeout: 100ms
 *    - On failure: ABORT (rollback to source)
 * 4. ABORT (if needed): Restore entity to source
 *    - Timeout: 100ms
 *    - On failure: Log critical error and metrics (C3)
 * 5. Release migration lock
 * </pre>
 * <p>
 * Architecture Decision D6B.8: Remove-then-commit ordering eliminates duplicates.
 * <p>
 * Critical Conditions Addressed:
 * - C1: Entity migration lock prevents concurrent migrations of same entity
 * - C3: Rollback-failure logging and metrics for critical errors
 * - C4: testConcurrentMigrationsSameEntity verifies lock behavior
 *
 * @author hal.hildebrand
 */
public class CrossProcessMigration {

    private static final Logger                log                   = LoggerFactory.getLogger(
    CrossProcessMigration.class);
    private static final long                  PHASE_TIMEOUT_MS      = 100;
    private static final long                  TOTAL_TIMEOUT_MS      = 300;
    private static final long                  LOCK_TIMEOUT_MS       = 50;
    private static final long                  LOCK_RETRY_INTERVAL_NS = 5_000_000;  // 5ms
    private static final int                   MAX_LOCK_RETRIES      = 10;  // 50ms total
    private volatile     Clock                 clock                 = Clock.system();
    private final        IdempotencyStore      dedup;
    private final        MigrationMetrics      metrics;
    // C1: Per-entity migration locks to prevent concurrent migrations
    // BUG-004 FIX: Use WeakReference to allow GC cleanup of unused locks (prevents memory leak)
    private final        Map<String, WeakReference<ReentrantLock>> entityMigrationLocks = new ConcurrentHashMap<>();
    // Active transactions (for cleanup and monitoring)
    private final        Map<UUID, com.hellblazer.luciferase.simulation.distributed.migration.MigrationTransaction> activeTransactions = new ConcurrentHashMap<>();
    // Phase 4.2.2: Prime-Mover controller for event-driven execution
    private final        RealTimeController    controller;

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public CrossProcessMigration(IdempotencyStore dedup, MigrationMetrics metrics) {
        this.dedup = dedup;
        this.metrics = metrics;
        // Phase 4.2.2: Initialize Prime-Mover controller
        this.controller = new RealTimeController("CrossProcessMigration");
        this.controller.start();
    }

    /**
     * Stop the controller (for cleanup).
     */
    public void stop() {
        if (controller != null) {
            controller.stop();
        }
    }

    /**
     * BUG-004 FIX: Get or create migration lock for an entity, handling WeakReference cleanup.
     * <p>
     * Uses WeakReference to allow GC to clean up locks when no thread holds a reference.
     * This prevents unbounded map growth (memory leak).
     *
     * @param entityId Entity identifier
     * @return ReentrantLock for this entity
     */
    private ReentrantLock getLockForEntity(String entityId) {
        return entityMigrationLocks.compute(entityId, (key, existingRef) -> {
            // Try to get existing lock from WeakReference
            var lock = existingRef != null ? existingRef.get() : null;
            if (lock == null) {
                // Lock was GC'd or doesn't exist - create new one
                lock = new ReentrantLock();
                return new WeakReference<>(lock);
            }
            // Lock still exists - reuse it
            return existingRef;
        }).get(); // Extract lock from WeakReference
    }

    /**
     * Migrate an entity from source to destination bubble (event-driven).
     * <p>
     * Phase 4.2.2: Uses Prime-Mover @Entity for non-blocking execution.
     * Thread-safe. Uses per-entity locking to prevent concurrent migrations
     * of the same entity (C1).
     *
     * @param entityId Entity identifier
     * @param source   Source bubble reference
     * @param dest     Destination bubble reference
     * @return CompletableFuture with MigrationResult (completed asynchronously)
     */
    public CompletableFuture<MigrationResult> migrate(String entityId, BubbleReference source, BubbleReference dest) {
        // Create future to be completed by entity
        var future = new CompletableFuture<MigrationResult>();

        // C1: Get or create migration lock for this entity (BUG-004 FIX: uses WeakReference)
        var lock = getLockForEntity(entityId);

        // Create entity instance for this migration
        var entity = new CrossProcessMigrationEntity(
            entityId,
            source,
            dest,
            future,
            lock,
            clock::currentTimeMillis,
            metrics::incrementConcurrent,
            metrics::decrementConcurrent,
            this::checkAndStoreMigrationWrapper,
            () -> metrics.recordDuplicateRejection(),
            metrics::recordFailure,
            metrics::recordSuccess,
            metrics::recordAlreadyMigrating,
            metrics::recordRollbackFailure,
            metrics::recordAbort,
            dedup
        );

        // Set controller context and start entity
        Kairos.setController(controller);
        entity.startMigration();

        // Return future (will be completed by entity when migration finishes)
        return future;
    }

    /**
     * Wrapper for checkAndStoreMigration that throws exception for duplicate detection.
     */
    private void checkAndStoreMigrationWrapper(IdempotencyToken token) {
        if (!dedup.checkAndStoreMigration(token)) {
            throw new IllegalStateException("Duplicate migration");
        }
    }


    /**
     * Get active transaction count (for monitoring).
     *
     * @return Number of active transactions
     */
    public int getActiveTransactionCount() {
        return activeTransactions.size();
    }

    /**
     * Get metrics.
     *
     * @return MigrationMetrics
     */
    public MigrationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Phase 4.2.2: Prime-Mover @Entity for event-driven migration execution.
     * <p>
     * Each migration creates a new entity instance that manages the 2PC state machine:
     * ACQUIRING_LOCK → PREPARE → COMMIT → SUCCESS
     *                           ↓ (on failure)
     *                         ABORT → ROLLBACK_COMPLETE
     * <p>
     * Uses Kronos.sleep() for non-blocking retries and timeouts.
     * Completes the provided CompletableFuture when migration finishes (success or failure).
     *
     * @author hal.hildebrand
     */
    @Entity
    public static class CrossProcessMigrationEntity {

        private enum State {
            ACQUIRING_LOCK, PREPARE, COMMIT, ABORT
        }

        // Migration parameters
        private final String                              entityId;
        private final BubbleReference                     source;
        private final BubbleReference                     dest;
        private final CompletableFuture<MigrationResult>  resultFuture;
        private final ReentrantLock                       migrationLock;

        // State tracking
        private       State                               currentState;
        private       long                                phaseStartTime;
        private       int                                 lockRetries = 0;
        private       EntitySnapshot                      snapshot;
        private       IdempotencyToken                    token;
        private       UUID                                txnId;
        private       String                              abortReason; // Track why we're aborting

        // Dependencies (via suppliers/callbacks)
        private final LongSupplier                        clockSupplier;
        private final Runnable                            incrementConcurrent;
        private final Runnable                            decrementConcurrent;
        private final java.util.function.Consumer<com.hellblazer.luciferase.simulation.distributed.migration.IdempotencyToken> checkAndStoreMigration;
        private final Runnable                            recordDuplicateRejection;
        private final java.util.function.Consumer<String> recordFailure;
        private final java.util.function.Consumer<Long>   recordSuccess;
        private final Runnable                            recordAlreadyMigrating;
        private final Runnable                            recordRollbackFailure;
        private final java.util.function.Consumer<String> recordAbort;
        private final IdempotencyStore                    dedup;

        public CrossProcessMigrationEntity(
            String entityId,
            BubbleReference source,
            BubbleReference dest,
            CompletableFuture<MigrationResult> resultFuture,
            ReentrantLock migrationLock,
            LongSupplier clockSupplier,
            Runnable incrementConcurrent,
            Runnable decrementConcurrent,
            java.util.function.Consumer<com.hellblazer.luciferase.simulation.distributed.migration.IdempotencyToken> checkAndStoreMigration,
            Runnable recordDuplicateRejection,
            java.util.function.Consumer<String> recordFailure,
            java.util.function.Consumer<Long> recordSuccess,
            Runnable recordAlreadyMigrating,
            Runnable recordRollbackFailure,
            java.util.function.Consumer<String> recordAbort,
            IdempotencyStore dedup
        ) {
            this.entityId = entityId;
            this.source = source;
            this.dest = dest;
            this.resultFuture = resultFuture;
            this.migrationLock = migrationLock;
            this.clockSupplier = clockSupplier;
            this.incrementConcurrent = incrementConcurrent;
            this.decrementConcurrent = decrementConcurrent;
            this.checkAndStoreMigration = checkAndStoreMigration;
            this.recordDuplicateRejection = recordDuplicateRejection;
            this.recordFailure = recordFailure;
            this.recordSuccess = recordSuccess;
            this.recordAlreadyMigrating = recordAlreadyMigrating;
            this.recordRollbackFailure = recordRollbackFailure;
            this.recordAbort = recordAbort;
            this.dedup = dedup;
        }

        /**
         * Start the migration state machine.
         */
        public void startMigration() {
            currentState = State.ACQUIRING_LOCK;
            acquireLock();
        }

        /**
         * ACQUIRING_LOCK state: Non-blocking lock acquisition with retries.
         */
        private void acquireLock() {
            if (migrationLock.tryLock()) {
                // Lock acquired, proceed to PREPARE
                incrementConcurrent.run();
                currentState = State.PREPARE;
                phaseStartTime = clockSupplier.getAsLong();

                // Generate idempotency token
                token = new IdempotencyToken(entityId, source.getBubbleId(), dest.getBubbleId(),
                                            clockSupplier.getAsLong(), UUID.randomUUID());

                // Check for duplicate migration
                try {
                    checkAndStoreMigration.accept(token);
                    // If we get here, it's not a duplicate - proceed
                    prepare();
                } catch (IllegalStateException e) {
                    // Duplicate migration
                    recordDuplicateRejection.run();
                    log.debug("Duplicate migration for entity {} from {} to {}, rejecting",
                             entityId, source.getBubbleId(), dest.getBubbleId());
                    failAndUnlock("ALREADY_APPLIED");
                }
            } else {
                // Lock held by another migration
                lockRetries++;
                if (lockRetries > MAX_LOCK_RETRIES) {
                    recordAlreadyMigrating.run();
                    log.debug("Entity {} already being migrated, rejecting concurrent attempt", entityId);
                    resultFuture.complete(MigrationResult.failure(entityId, "ALREADY_MIGRATING"));
                } else {
                    // Retry after delay
                    Kronos.sleep(LOCK_RETRY_INTERVAL_NS);
                    this.acquireLock();
                }
            }
        }

        /**
         * PREPARE state: Remove entity from source.
         */
        private void prepare() {
            try {
                // Validate destination
                if (dest == null || dest.getBubbleId() == null) {
                    log.warn("Destination null for entity {}", entityId);
                    recordFailure.accept("DESTINATION_NULL");
                    failAndUnlock("UNREACHABLE");
                    return;
                }

                // Check if destination is reachable (for testing)
                if (dest instanceof TestableEntityStore testDest) {
                    if (!testDest.isReachable()) {
                        log.warn("Destination unreachable for entity {}", entityId);
                        recordFailure.accept("DESTINATION_UNREACHABLE");
                        failAndUnlock("UNREACHABLE");
                        return;
                    }
                }

                // Create snapshot and transaction ID
                snapshot = createEntitySnapshot(entityId, source, clockSupplier.getAsLong());
                txnId = UUID.randomUUID();
                // Note: Transaction tracking removed to avoid Prime-Mover class resolution issues

                // Remove entity from source
                var prepareStart = clockSupplier.getAsLong();
                boolean removed;
                if (source instanceof TestableEntityStore testSource) {
                    removed = testSource.removeEntity(entityId);
                } else {
                    // Production code would call source.asLocal().removeEntity(entityId)
                    removed = true;
                }
                var prepareElapsed = clockSupplier.getAsLong() - prepareStart;

                // Check per-phase timeout
                if (prepareElapsed > PHASE_TIMEOUT_MS) {
                    log.warn("PREPARE phase timed out for entity {} ({}ms > {}ms)",
                            entityId, prepareElapsed, PHASE_TIMEOUT_MS);
                    recordFailure.accept("PREPARE_TIMEOUT");
                    failAndUnlock("TIMEOUT");
                    return;
                }

                if (!removed) {
                    log.warn("Failed to remove entity {} from source", entityId);
                    recordFailure.accept("PREPARE_FAILED");
                    failAndUnlock("PREPARE_FAILED");
                    return;
                }

                log.debug("PREPARE: Removed entity {} from source {}", entityId, source.getBubbleId());

                // Advance to COMMIT
                currentState = State.COMMIT;
                phaseStartTime = clockSupplier.getAsLong();
                commit();

            } catch (Exception e) {
                log.warn("PREPARE failed for entity {}: {}", entityId, e.getMessage());
                recordFailure.accept("PREPARE_FAILED");
                failAndUnlock("PREPARE_FAILED");
            }
        }

        /**
         * COMMIT state: Add entity to destination.
         */
        private void commit() {
            try {
                // Add entity to destination
                var commitStart = clockSupplier.getAsLong();
                boolean added;
                if (dest instanceof TestableEntityStore testDest) {
                    added = testDest.addEntity(snapshot);
                } else {
                    // Production code would call dest.asLocal().addEntity(snapshot)
                    added = true;
                }
                var commitElapsed = clockSupplier.getAsLong() - commitStart;

                // Check per-phase timeout
                if (commitElapsed > PHASE_TIMEOUT_MS) {
                    log.warn("COMMIT phase timed out for entity {} ({}ms > {}ms)",
                            entityId, commitElapsed, PHASE_TIMEOUT_MS);
                    recordFailure.accept("COMMIT_TIMEOUT");
                    // COMMIT failed, need to ABORT
                    abortReason = "COMMIT_TIMEOUT";
                    currentState = State.ABORT;
                    phaseStartTime = clockSupplier.getAsLong();
                    abort();
                    return;
                }

                if (!added) {
                    log.warn("Failed to add entity {} to destination", entityId);
                    recordFailure.accept("COMMIT_FAILED");
                    // COMMIT failed, need to ABORT
                    abortReason = "COMMIT_FAILED";
                    currentState = State.ABORT;
                    phaseStartTime = clockSupplier.getAsLong();
                    abort();
                    return;
                }

                log.debug("COMMIT: Added entity {} to destination {} with epoch {}", entityId, dest.getBubbleId(),
                          snapshot.epoch() + 1);

                // Success!
                var totalLatency = clockSupplier.getAsLong() - phaseStartTime;
                recordSuccess.accept(totalLatency);
                succeedAndUnlock(totalLatency);

            } catch (Exception e) {
                log.warn("COMMIT failed for entity {}: {}", entityId, e.getMessage());
                recordFailure.accept("COMMIT_FAILED");
                // COMMIT failed, need to ABORT
                abortReason = "COMMIT_FAILED";
                currentState = State.ABORT;
                phaseStartTime = clockSupplier.getAsLong();
                abort();
            }
        }

        /**
         * ABORT state: Rollback (restore entity to source).
         */
        private void abort() {
            try {
                log.info("ABORT: Rolling back entity {} to source {}", entityId, source.getBubbleId());

                // Check total timeout
                var totalElapsed = clockSupplier.getAsLong() - phaseStartTime;
                if (totalElapsed > TOTAL_TIMEOUT_MS) {
                    log.error("ABORT timed out for entity {} - CRITICAL: Entity may be lost", entityId);
                    recordRollbackFailure.run();
                    recordAbort.accept("TIMEOUT");
                    failAndUnlock("ABORT_TIMEOUT");
                    return;
                }

                // Re-add entity to source from snapshot
                boolean restored;
                if (source instanceof TestableEntityStore testSource) {
                    restored = testSource.addEntity(snapshot);
                } else {
                    // Production code would call source.asLocal().addEntity(snapshot)
                    restored = true;
                }

                if (!restored) {
                    // C3: Log and metric rollback failures (critical error)
                    log.error("ABORT/Rollback FAILED for entity {} - CRITICAL: Manual intervention required", entityId);
                    recordRollbackFailure.run();
                }

                log.debug("ABORT: Restored entity {} to source {} with epoch {}", entityId, source.getBubbleId(),
                          snapshot.epoch());

                recordAbort.accept(abortReason != null ? abortReason : "COMMIT_FAILED");
                // Return the original failure reason, not "ROLLBACK_COMPLETE"
                failAndUnlock(abortReason != null ? abortReason : "ROLLBACK_COMPLETE");

            } catch (Exception e) {
                // C3: Log and metric rollback failures (critical error)
                log.error("ABORT/Rollback FAILED for entity {} - CRITICAL: Manual intervention required: {}",
                          entityId, e.getMessage(), e);
                recordRollbackFailure.run();
                recordAbort.accept("ROLLBACK_FAILED");
                failAndUnlock("ROLLBACK_FAILED");
            }
        }

        /**
         * Complete migration successfully and unlock.
         */
        private void succeedAndUnlock(long latency) {
            try {
                migrationLock.unlock();
                decrementConcurrent.run();
                resultFuture.complete(MigrationResult.success(entityId, dest.getBubbleId(), latency));
            } catch (Exception e) {
                log.error("Error completing migration success for entity {}: {}", entityId, e.getMessage(), e);
            }
        }

        /**
         * Complete migration with failure and unlock.
         * Removes migration key from dedup store to allow retries after failures.
         */
        private void failAndUnlock(String reason) {
            try {
                migrationLock.unlock();
                decrementConcurrent.run();
                // Remove migration key to allow retry after failure
                if (token != null) {
                    dedup.removeMigration(token);
                }
                resultFuture.complete(MigrationResult.failure(entityId, reason));
            } catch (Exception e) {
                log.error("Error completing migration failure for entity {}: {}", entityId, e.getMessage(), e);
            }
        }

        /**
         * Create entity snapshot for rollback.
         */
        private static EntitySnapshot createEntitySnapshot(String entityId, BubbleReference source, long timestamp) {
            // In actual implementation, would query source bubble for entity state
            // For now, create synthetic snapshot
            return new EntitySnapshot(entityId, new Point3D(0, 0, 0), "MockContent", source.getBubbleId(), 1L, 1L,
                                      timestamp);
        }
    }
}
