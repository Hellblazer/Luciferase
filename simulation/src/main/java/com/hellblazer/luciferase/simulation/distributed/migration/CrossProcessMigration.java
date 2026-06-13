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

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import javax.vecmath.Point3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
public class CrossProcessMigration implements AutoCloseable {

    private static final Logger                log                   = LoggerFactory.getLogger(
    CrossProcessMigration.class);
    // Luciferase-65qu: Configurable timeouts (was hardcoded constants)
    private final        MigrationConfig       config;
    private volatile     Clock                 clock                 = Clock.system();
    private final        IdempotencyStore      dedup;
    private final        MigrationMetrics      metrics;
    // C1: Per-entity migration locks to prevent concurrent migrations
    // BUG-004 FIX: Use WeakReference to allow GC cleanup of unused locks (prevents memory leak)
    private final        Map<String, WeakReference<ReentrantLock>> entityMigrationLocks = new ConcurrentHashMap<>();
    // Active transactions (for cleanup and monitoring)
    private final        Map<UUID, com.hellblazer.luciferase.simulation.distributed.migration.MigrationTransaction> activeTransactions = new ConcurrentHashMap<>();
    // Phase 2C: Orphaned entity tracking for rollback failure observability
    private final        Set<String>           orphanedEntityIds                                                    = ConcurrentHashMap.newKeySet();
    // Phase 4.2.2: Prime-Mover controller for event-driven execution
    private final        RealTimeController    controller;
    // Luciferase-77tn: Track active entities with strong references for lifecycle management
    // Use entityId as key for cleanup on future completion
    private final        Map<String, CrossProcessMigrationEntity> activeEntities = new ConcurrentHashMap<>();
    // Periodic cleanup task for orphaned entities (>5min timeout)
    private final        ScheduledExecutorService cleanupScheduler;
    // Luciferase-0frcy.30: optional Write-Ahead Log for crash recovery of in-flight 2PC
    // transactions. When wired, recordPrepare is called before the entity is removed from the
    // source, recordCommit after it is added to the destination, and recordAbort after rollback.
    // When null, the orchestrator provides at-most-once semantics with no crash recovery.
    private final        MigrationLogPersistence walPersistence;

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Create CrossProcessMigration with default configuration.
     * <p>
     * Uses MigrationConfig.defaults() for typical LAN deployments.
     *
     * @param dedup   Idempotency store for exactly-once semantics
     * @param metrics Migration metrics collector
     */
    public CrossProcessMigration(IdempotencyStore dedup, MigrationMetrics metrics) {
        this(dedup, metrics, MigrationConfig.defaults());
    }

    /**
     * Create CrossProcessMigration with explicit configuration (Luciferase-65qu).
     *
     * @param dedup   Idempotency store for exactly-once semantics
     * @param metrics Migration metrics collector
     * @param config  Timeout and retry configuration
     */
    public CrossProcessMigration(IdempotencyStore dedup, MigrationMetrics metrics, MigrationConfig config) {
        this(dedup, metrics, config, null);
    }

    /**
     * Create CrossProcessMigration with explicit configuration and a Write-Ahead Log for crash
     * recovery (Luciferase-0frcy.30).
     *
     * @param dedup          Idempotency store for exactly-once semantics
     * @param metrics        Migration metrics collector
     * @param config         Timeout and retry configuration
     * @param walPersistence Write-Ahead Log for crash recovery; may be {@code null} for
     *                       at-most-once semantics with no recovery
     */
    public CrossProcessMigration(IdempotencyStore dedup, MigrationMetrics metrics, MigrationConfig config,
                                 MigrationLogPersistence walPersistence) {
        this.dedup = dedup;
        this.metrics = metrics;
        this.config = config;
        this.walPersistence = walPersistence;
        // Phase 4.2.2: Initialize Prime-Mover controller
        this.controller = new RealTimeController("CrossProcessMigration");
        this.controller.start();

        // Luciferase-77tn: Initialize periodic cleanup scheduler
        // Runs every 60 seconds to clean up orphaned entities (>5min timeout)
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "CrossProcessMigration-Cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupOrphanedEntities, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Stop the controller and cleanup scheduler.
     */
    public void stop() {
        if (controller != null) {
            controller.stop();
        }
        // Luciferase-77tn: Shutdown cleanup scheduler
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Delegates to {@link #stop()} so callers can manage the controller thread and cleanup scheduler with
     * try-with-resources; cleanup then runs on exception paths too (Luciferase-rr07g).
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * BUG-004 FIX: Get or create migration lock for an entity, handling WeakReference cleanup.
     * <p>
     * Uses WeakReference to allow GC to clean up locks when no thread holds a reference.
     * This prevents unbounded map growth (memory leak).
     * <p>
     * CRITICAL: Holds strong reference during compute() to prevent GC race where:
     * 1. compute() returns WeakReference
     * 2. GC runs before .get() is called
     * 3. .get() returns null → NullPointerException
     *
     * @param entityId Entity identifier
     * @return ReentrantLock for this entity (never null)
     */
    private ReentrantLock getLockForEntity(String entityId) {
        // Hold strong reference to prevent GC between compute() and return
        final ReentrantLock[] strongRef = new ReentrantLock[1];

        entityMigrationLocks.compute(entityId, (key, existingRef) -> {
            // Try to get existing lock from WeakReference
            var lock = existingRef != null ? existingRef.get() : null;
            if (lock == null) {
                // Lock was GC'd or doesn't exist - create new one
                lock = new ReentrantLock();
            }
            // Store in strong reference to prevent GC during this operation
            strongRef[0] = lock;
            return new WeakReference<>(lock);
        });

        return strongRef[0];
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
     * @throws IllegalArgumentException if validation fails (Byzantine protection)
     */
    public CompletableFuture<MigrationResult> migrate(String entityId, BubbleReference source, BubbleReference dest) {
        // Byzantine input validation (Luciferase-brtp)
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(dest, "dest must not be null");

        if (entityId.isEmpty()) {
            throw new IllegalArgumentException("entityId must not be empty");
        }

        if (entityId.length() > 36) {
            throw new IllegalArgumentException("entityId length must not exceed 36 characters (UUID standard)");
        }

        if (source.equals(dest)) {
            throw new IllegalArgumentException(
                String.format("source and dest must be different (self-migration not allowed): %s", source));
        }

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
            dedup,
            orphanedEntityIds::add,  // Phase 2C: Pass orphaned entity tracking callback
            config,  // Luciferase-65qu: Pass timeout configuration
            this::walRecordPrepare,  // Luciferase-0frcy.30: WAL crash-recovery hooks
            this::walRecordCommit,
            this::walRecordAbort
        );

        // Luciferase-77tn: Track entity with strong reference for lifecycle management
        activeEntities.put(entityId, entity);

        // Luciferase-77tn: Chain cleanup on future completion (success or failure)
        // This guarantees cleanup when migration finishes, preventing memory leak
        future.whenComplete((result, exception) -> {
            activeEntities.remove(entityId);
            if (log.isDebugEnabled()) {
                var resultStr = result != null ? (result.success() ? "SUCCESS" : "FAILURE:" + result.reason()) : "null";
                var exStr = exception != null ? exception.getClass().getSimpleName() : "none";
                log.debug("Entity {} removed from active tracking (result={}, exception={})",
                         entityId, resultStr, exStr);
            }
        });

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
     * Luciferase-0frcy.30: durably record the PREPARE phase to the WAL before the entity is
     * removed from the source. If the process crashes after this point but before COMMIT, the
     * WAL record drives crash recovery. No-op when no WAL is wired. IOExceptions are propagated
     * to the caller so a PREPARE that cannot be made durable aborts the migration rather than
     * removing the entity with no recovery record.
     */
    private void walRecordPrepare(TransactionState state) {
        if (walPersistence == null) {
            return;
        }
        try {
            walPersistence.recordPrepare(state);
        } catch (IOException e) {
            throw new UncheckedIOException("WAL recordPrepare failed for txn " + state.transactionId(), e);
        }
    }

    /**
     * Luciferase-0frcy.30: record COMMIT to the WAL after the entity has been added to the
     * destination. A COMMIT record prevents recovery from rolling the transaction back.
     */
    private void walRecordCommit(UUID transactionId) {
        if (walPersistence == null) {
            return;
        }
        try {
            walPersistence.recordCommit(transactionId);
        } catch (IOException e) {
            // The migration already succeeded on the destination; a failure to durably record
            // COMMIT is non-fatal (recovery would, at worst, re-validate the destination).
            log.error("WAL recordCommit failed for txn {}: {}", transactionId, e.getMessage(), e);
        }
    }

    /**
     * Luciferase-0frcy.30: record ABORT to the WAL after rollback so recovery treats the
     * transaction as resolved.
     */
    private void walRecordAbort(UUID transactionId) {
        if (walPersistence == null) {
            return;
        }
        try {
            walPersistence.recordAbort(transactionId);
        } catch (IOException e) {
            log.error("WAL recordAbort failed for txn {}: {}", transactionId, e.getMessage(), e);
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
     * Phase 2C: Get orphaned entity IDs (entities that failed rollback).
     * <p>
     * Returns a defensive copy of the orphaned entity set.
     * <p>
     * Orphaned entities are those where:
     * - Migration failed during COMMIT phase
     * - Rollback (restore to source) also failed
     * - Entity may be lost and requires manual intervention
     * <p>
     * Use this API for admin tooling and runbook procedures.
     *
     * @return Immutable set of orphaned entity IDs
     */
    public Set<String> getOrphanedEntities() {
        return Set.copyOf(orphanedEntityIds);
    }

    /**
     * Luciferase-77tn: Get active entity count (for monitoring).
     * <p>
     * Returns the number of CrossProcessMigrationEntity instances currently tracked.
     * This includes entities in all states: ACQUIRING_LOCK, PREPARE, COMMIT, ABORT.
     * <p>
     * Use this for:
     * - Memory leak detection (should be 0 after all migrations complete)
     * - Operational health monitoring (detect stuck migrations)
     * - Capacity planning (track concurrent migration load)
     *
     * @return Number of active migration entities
     */
    public int getActiveEntityCount() {
        return activeEntities.size();
    }

    /**
     * Luciferase-77tn: Periodic cleanup of orphaned entities (>5min timeout).
     * <p>
     * This is a safety net for entities that never complete due to:
     * - CompletableFuture never resolved (programming error)
     * - PrimeMover controller shutdown without cleanup
     * - Unhandled exceptions in state machine
     * <p>
     * Runs every 60 seconds. Removes entities older than 5 minutes.
     * <p>
     * Normal operations should NOT trigger this cleanup - it indicates
     * a bug if entities are being cleaned up here.
     */
    private void cleanupOrphanedEntities() {
        try {
            var now = clock.currentTimeMillis();
            var timeout = 5 * 60 * 1000; // 5 minutes

            var iterator = activeEntities.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var entityId = entry.getKey();
                var entity = entry.getValue();

                // Check entity age (use phaseStartTime from entity)
                var entityAge = now - entity.phaseStartTime;
                if (entityAge > timeout) {
                    log.warn("Cleaning up orphaned entity {} (age={}ms > timeout={}ms) - indicates bug",
                             entityId, entityAge, timeout);
                    // Luciferase-7wzml.41: complete the resultFuture BEFORE removing the entity
                    // so any caller blocked on migrate(...).get() unblocks immediately.
                    // CompletableFuture.complete() is a no-op if already done, so double-complete
                    // (e.g. state-machine raced to finish) is safe.
                    entity.resultFuture.complete(MigrationResult.failure(entityId, "ORPHANED_CLEANUP"));
                    iterator.remove();
                    metrics.recordFailure("ORPHANED_CLEANUP");
                }
            }
        } catch (Exception e) {
            log.error("Error during orphaned entity cleanup", e);
        }
    }

    /**
     * Luciferase-7wzml.41: Package-private entry point so unit tests can trigger orphan cleanup
     * without waiting 60 seconds for the scheduled task to fire.
     */
    void triggerOrphanCleanupForTesting() {
        cleanupOrphanedEntities();
    }

    /**
     * Luciferase-7wzml.41: Package-private accessor for tests that need to inspect or
     * manipulate active-entity state (e.g. force-age an entity to simulate an orphan).
     */
    CrossProcessMigrationEntity getActiveEntityForTesting(String entityId) {
        return activeEntities.get(entityId);
    }

    /**
     * Luciferase-7wzml.41: Package-private injection point so tests can insert a pre-built
     * entity with a back-dated {@code phaseStartTime} to simulate an orphan without waiting
     * 5 minutes of real time.
     */
    void injectActiveEntityForTesting(String entityId, CrossProcessMigrationEntity entity) {
        activeEntities.put(entityId, entity);
    }

    /**
     * Phase 2C: Recovery state for admin tooling and observability.
     * <p>
     * Provides snapshot of migration recovery state including:
     * - Orphaned entities (rollback failures requiring manual intervention)
     * - Active transactions (in-flight migrations)
     * - Rollback failure count (cumulative)
     * - Concurrent migrations (current gauge)
     * <p>
     * Use this for:
     * - Admin dashboards
     * - Runbook procedures
     * - Alerting and monitoring
     * - Operational health checks
     *
     * @param orphanedEntities   Set of entity IDs that failed rollback
     * @param activeTransactions Count of in-flight migrations
     * @param rollbackFailures   Cumulative count of rollback failures
     * @param concurrentMigrations Current concurrent migration gauge
     */
    public record RecoveryState(
        Set<String> orphanedEntities,
        int activeTransactions,
        long rollbackFailures,
        int concurrentMigrations
    ) {}

    /**
     * Phase 2C: Get current recovery state for admin tooling.
     * <p>
     * Returns comprehensive recovery state including:
     * - Orphaned entities
     * - Active transactions
     * - Rollback failure count
     * - Concurrent migrations
     * <p>
     * Thread-safe. Returns snapshot of current state.
     * <p>
     * <b>Note</b>: The snapshot is not atomic. If migrations complete
     * during this method call, the returned values may be slightly
     * inconsistent across fields. This is acceptable for admin
     * monitoring where exact consistency is not required.
     *
     * @return RecoveryState snapshot
     */
    public RecoveryState getRecoveryState() {
        return new RecoveryState(
            Set.copyOf(orphanedEntityIds),
            activeTransactions.size(),
            metrics.getRollbackFailures(),
            metrics.getConcurrentMigrations()
        );
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
        // Package-private for cleanup access (Luciferase-77tn).
        // Luciferase-7wzml.41: volatile so cleanupOrphanedEntities() reads a consistent value
        // across threads without acquiring a lock (written by state-machine thread, read by
        // cleanup scheduler thread).
        volatile      long                                phaseStartTime;
        // Luciferase-0frcy.31/.32: transaction start time, captured ONCE when the lock is
        // acquired and never overwritten. Used for the total-2PC timeout guard in abort() and
        // for the end-to-end totalLatency metric in commit(), both of which previously measured
        // only a single phase because they reused phaseStartTime.
        private       long                                txnStartTime;
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
        private final java.util.function.Consumer<String> recordOrphanedEntity; // Phase 2C: Orphan tracking callback
        private final MigrationConfig                     config;  // Luciferase-65qu: Timeout configuration
        // Luciferase-0frcy.30: WAL crash-recovery hooks (no-op when no WAL is wired)
        private final java.util.function.Consumer<TransactionState> walRecordPrepare;
        private final java.util.function.Consumer<UUID>             walRecordCommit;
        private final java.util.function.Consumer<UUID>             walRecordAbort;

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
            IdempotencyStore dedup,
            java.util.function.Consumer<String> recordOrphanedEntity,  // Phase 2C: Orphan tracking
            MigrationConfig config,  // Luciferase-65qu: Timeout configuration
            java.util.function.Consumer<TransactionState> walRecordPrepare,  // Luciferase-0frcy.30
            java.util.function.Consumer<UUID> walRecordCommit,
            java.util.function.Consumer<UUID> walRecordAbort
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
            this.recordOrphanedEntity = recordOrphanedEntity;  // Phase 2C
            this.config = config;  // Luciferase-65qu
            this.walRecordPrepare = walRecordPrepare;  // Luciferase-0frcy.30
            this.walRecordCommit = walRecordCommit;
            this.walRecordAbort = walRecordAbort;
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
                // Luciferase-0frcy.31/.32: capture the transaction start time exactly once.
                txnStartTime = phaseStartTime;

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
                if (lockRetries > config.maxLockRetries()) {
                    recordAlreadyMigrating.run();
                    log.debug("Entity {} already being migrated, rejecting concurrent attempt", entityId);
                    resultFuture.complete(MigrationResult.failure(entityId, "ALREADY_MIGRATING"));
                } else {
                    // Retry after delay
                    Kronos.sleep(config.lockRetryIntervalNs());
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
                if (dest instanceof EntityStoreOperations testDest) {
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

                // Luciferase-0frcy.30: durably record PREPARE to the WAL BEFORE removing the
                // entity from the source. If this throws (WAL unwritable), we abort the migration
                // without removing the entity, so there is never a removed-but-unrecorded entity.
                try {
                    walRecordPrepare.accept(new TransactionState(
                        txnId, entityId, source.getBubbleId(), dest.getBubbleId(),
                        source.getBubbleId(), dest.getBubbleId(),
                        TransactionState.SerializedSnapshot.from(snapshot),
                        token != null ? token.toUUID() : null,
                        TransactionState.MigrationPhase.PREPARE, clockSupplier.getAsLong()));
                } catch (RuntimeException walEx) {
                    log.error("WAL PREPARE record failed for entity {} (txn={}); aborting before removal: {}",
                              entityId, txnId, walEx.getMessage(), walEx);
                    recordFailure.accept("WAL_PREPARE_FAILED");
                    failAndUnlock("WAL_PREPARE_FAILED");
                    return;
                }

                // Remove entity from source
                var prepareStart = clockSupplier.getAsLong();
                boolean removed;
                if (source instanceof EntityStoreOperations testSource) {
                    removed = testSource.removeEntity(entityId);
                } else {
                    // Production code would call source.asLocal().removeEntity(entityId)
                    removed = true;
                }
                var prepareElapsed = clockSupplier.getAsLong() - prepareStart;

                // Check per-phase timeout
                if (prepareElapsed > config.phaseTimeoutMs()) {
                    log.warn("PREPARE phase timed out for entity {} ({}ms > {}ms)",
                            entityId, prepareElapsed, config.phaseTimeoutMs());
                    recordFailure.accept("PREPARE_TIMEOUT");
                    // No WAL ABORT tombstone is written on timeout — see the no-tombstone note on abort()
                    // (Luciferase-9kjpt). The dangling PREPARE is left for recovery to roll back.
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
                if (dest instanceof EntityStoreOperations testDest) {
                    added = testDest.addEntity(snapshot);
                } else {
                    // Production code would call dest.asLocal().addEntity(snapshot)
                    added = true;
                }
                var commitElapsed = clockSupplier.getAsLong() - commitStart;

                // Check per-phase timeout
                if (commitElapsed > config.phaseTimeoutMs()) {
                    log.warn("COMMIT phase timed out for entity {} ({}ms > {}ms)",
                            entityId, commitElapsed, config.phaseTimeoutMs());
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

                // Luciferase-0frcy.30: durably record COMMIT after the entity is at the
                // destination so crash recovery does not roll the transaction back.
                walRecordCommit.accept(txnId);

                // Success!
                // Luciferase-0frcy.32: measure end-to-end (PREPARE→COMMIT) latency from the
                // transaction start, not the COMMIT-phase start, otherwise the PREPARE half is
                // silently excluded and the metric under-reports.
                var totalLatency = clockSupplier.getAsLong() - txnStartTime;
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
         * <p>
         * Phase 2C: Enhanced logging with full entity state for observability.
         * <p>
         * NOTE: the snapshot restored on rollback carries content as a String only — that is all
         * EntitySnapshot.content holds on the wire (RDR-004 hygiene). Rollback therefore restores
         * the entity's String content (and identity/position/epoch), not any richer in-memory
         * content type that may have existed before serialization.
         * <p>
         * <b>No-tombstone-on-failure (Luciferase-9kjpt) — deliberate, deferred.</b> Only a SUCCESSFUL
         * rollback writes a WAL ABORT record ({@code walRecordAbort} below). The ABORT_TIMEOUT branch
         * and the catch (ROLLBACK_FAILED), like PREPARE_TIMEOUT in {@code prepare()}, complete the
         * migration WITHOUT a WAL resolution record — so {@code MigrationLogPersistence.loadIncomplete()}
         * returns the dangling PREPARE for a timed-out / failed-rollback / orphaned transaction. No production
         * code consumes {@code loadIncomplete()} yet, so that dangling PREPARE is currently inert (nothing
         * acts on it); leaving it is the correct shape for a FUTURE recovery consumer (uncertainty should
         * bias toward re-attempting rollback once such a consumer exists).
         * A distinct TIMEOUT/ORPHANED tombstone (so recovery could route those differently and operators
         * could audit them separately) is intentionally NOT written yet: there is currently no production
         * consumer of {@code loadIncomplete()} that re-attempts rollback, and the cross-process add path is
         * still stubbed ({@code added = true}). Designing the tombstone format before its recovery consumer
         * exists would be speculative; it is deferred to migration productionization (tracked by 9kjpt).
         * The current no-tombstone behavior is pinned by
         * {@code CrossProcessMigration2PCInstrumentationTest.timedOutMigrationLeavesPrepareIncompleteForRecovery}.
         */
        private void abort() {
            try {
                // Phase 2C: Structured logging with full context
                log.info("ABORT: Rolling back entity {} to source {} (txn={}, reason={}, snapshot=[epoch={}, position={}])",
                         entityId, source.getBubbleId(), txnId, abortReason,
                         snapshot.epoch(), snapshot.position());

                // Check total timeout
                // Luciferase-0frcy.31: measure against the transaction start time, not the
                // ABORT-phase start (phaseStartTime was just reset at the COMMIT→ABORT
                // transition), otherwise the total-2PC-duration guard can never fire.
                var totalElapsed = clockSupplier.getAsLong() - txnStartTime;
                if (totalElapsed > config.totalTimeoutMs()) {
                    // Phase 2C: Enhanced error logging with full state
                    log.error("ABORT timed out for entity {} - CRITICAL: Entity may be lost " +
                              "(txn={}, source={}, dest={}, elapsed={}ms, snapshot=[epoch={}, position={}], reason={})",
                              entityId, txnId, source.getBubbleId(), dest.getBubbleId(), totalElapsed,
                              snapshot.epoch(), snapshot.position(), abortReason);
                    recordRollbackFailure.run();
                    recordOrphanedEntity.accept(entityId); // Phase 2C: Track orphaned entity
                    recordAbort.accept("TIMEOUT");  // metrics only — NOT a WAL tombstone (see abort() note, 9kjpt)
                    failAndUnlock("ABORT_TIMEOUT");
                    return;
                }

                // Re-add entity to source from snapshot
                boolean restored;
                if (source instanceof EntityStoreOperations testSource) {
                    restored = testSource.addEntity(snapshot);
                } else {
                    // Production code would call source.asLocal().addEntity(snapshot)
                    restored = true;
                }

                if (!restored) {
                    // Phase 2C: Enhanced error logging with full entity state
                    log.error("ABORT/Rollback FAILED for entity {} - CRITICAL: Manual intervention required " +
                              "(txn={}, source={}, dest={}, snapshot=[epoch={}, position={}], reason={})",
                              entityId, txnId, source.getBubbleId(), dest.getBubbleId(),
                              snapshot.epoch(), snapshot.position(), abortReason);
                    recordRollbackFailure.run();
                    recordOrphanedEntity.accept(entityId); // Phase 2C: Track orphaned entity
                }

                // Luciferase-0frcy.109: only log the "Restored entity" success message when the
                // entity was actually re-added to the source. Logging it unconditionally (even when
                // restored == false) produces a false success trace that masks rollback-failure data
                // loss for operators watching debug logs.
                if (restored) {
                    log.debug("ABORT: Restored entity {} to source {} with epoch {} (txn={}, position={})",
                              entityId, source.getBubbleId(), snapshot.epoch(), txnId, snapshot.position());
                }

                // Luciferase-0frcy.30: record ABORT to the WAL after rollback so recovery treats
                // the transaction as resolved (entity is back at the source).
                walRecordAbort.accept(txnId);

                recordAbort.accept(abortReason != null ? abortReason : "COMMIT_FAILED");
                // Return the original failure reason, not "ROLLBACK_COMPLETE"
                failAndUnlock(abortReason != null ? abortReason : "ROLLBACK_COMPLETE");

            } catch (Exception e) {
                // Phase 2C: Enhanced exception logging with full state dump
                log.error("ABORT/Rollback FAILED for entity {} - CRITICAL: Manual intervention required " +
                          "(txn={}, source={}, dest={}, snapshot=[epoch={}, position={}], reason={}, exception={})",
                          entityId, txnId, source.getBubbleId(), dest.getBubbleId(),
                          snapshot.epoch(), snapshot.position(), abortReason, e.getMessage(), e);
                recordRollbackFailure.run();
                recordOrphanedEntity.accept(entityId); // Phase 2C: Track orphaned entity
                recordAbort.accept("ROLLBACK_FAILED");  // metrics only — NOT a WAL tombstone (see abort() note, 9kjpt)
                failAndUnlock("ROLLBACK_FAILED");
            }
        }

        /**
         * Complete migration successfully and unlock.
         */
        private void succeedAndUnlock(long latency) {
            // Luciferase-zwyf2: guarantee the result future is completed even if unlock() throws
            // (e.g. IllegalMonitorStateException when a PrimeMover continuation resumes on a
            // different thread than the one that locked). The previous single try/catch swallowed
            // the unlock exception and skipped decrementConcurrent + resultFuture.complete, hanging
            // any caller blocked on migrate(...).get() forever.
            try {
                migrationLock.unlock();
            } catch (Exception e) {
                log.error("Error unlocking migration for entity {}: {}", entityId, e.getMessage(), e);
            } finally {
                decrementConcurrent.run();
                resultFuture.complete(MigrationResult.success(entityId, dest.getBubbleId(), latency));
            }
        }

        /**
         * Complete migration with failure and unlock.
         * Removes migration key from dedup store to allow retries after failures.
         */
        private void failAndUnlock(String reason) {
            // Luciferase-zwyf2: guarantee future completion even if unlock() throws (see
            // succeedAndUnlock). Unlock in its own try; cleanup + completion run in finally so a
            // blocked caller never hangs.
            try {
                migrationLock.unlock();
            } catch (Exception e) {
                log.error("Error unlocking migration for entity {}: {}", entityId, e.getMessage(), e);
            } finally {
                decrementConcurrent.run();
                // Remove migration key to allow retry after failure
                if (token != null) {
                    dedup.removeMigration(token);
                }
                resultFuture.complete(MigrationResult.failure(entityId, reason));
            }
        }

        /**
         * Capture a snapshot of the entity's real state from the source for rollback
         * (Luciferase-x8pwi).
         * <p>
         * Must be called during PREPARE <em>before</em> the entity is removed from the source.
         * Queries the source store for the entity's actual position, content, epoch, and
         * version. If the source can provide a real snapshot, that snapshot is used verbatim so
         * an aborted migration restores the exact original state.
         * <p>
         * If the source cannot provide full entity state (no entity store wired), an
         * identity-only snapshot is returned with {@code null} position and content rather than
         * fabricated {@code (0,0,0)}/{@code "MockContent"} data. This preserves the entity's
         * identity and authority for rollback bookkeeping while making it unambiguous that no
         * real spatial/content state was captured — eliminating the previous silent
         * garbage-restore.
         */
        private static EntitySnapshot createEntitySnapshot(String entityId, BubbleReference source, long timestamp) {
            if (source instanceof EntityStoreOperations store) {
                var captured = store.getEntitySnapshot(entityId);
                if (captured != null) {
                    log.debug("Captured real entity snapshot for {} from source {} [position={}, epoch={}, version={}]",
                              entityId, source.getBubbleId(), captured.position(), captured.epoch(), captured.version());
                    return captured;
                }
            }
            // No full entity state available: return an honest identity-only snapshot rather
            // than fabricated position/content that would silently corrupt the entity on rollback.
            log.warn("No entity state available to snapshot for {} from source {}; "
                     + "rollback will restore identity only (position/content unavailable)",
                     entityId, source.getBubbleId());
            return new EntitySnapshot(entityId, null, null, source.getBubbleId(), 0L, 0L, timestamp);
        }
    }
}
