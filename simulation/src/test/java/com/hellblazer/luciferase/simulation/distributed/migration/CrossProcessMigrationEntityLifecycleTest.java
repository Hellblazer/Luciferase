/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
import javax.vecmath.Point3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for entity lifecycle tracking in CrossProcessMigration (Luciferase-77tn).
 * <p>
 * Verifies that:
 * - All entities are tracked with strong references
 * - Cleanup occurs on future completion (success or failure)
 * - No memory leaks after 1000 migrations
 * - Periodic cleanup handles orphaned entities
 * - getActiveEntityCount() accurately reflects lifecycle state
 *
 * @author hal.hildebrand
 */
class CrossProcessMigrationEntityLifecycleTest {

    private CrossProcessMigration migration;
    private IdempotencyStore      dedup;
    private MigrationMetrics      metrics;
    private BubbleReference       source;
    private BubbleReference       dest;

    @BeforeEach
    void setUp() {
        dedup = new IdempotencyStore(300_000); // 5 min TTL
        metrics = new MigrationMetrics();
        migration = new CrossProcessMigration(dedup, metrics);

        source = createMockBubbleReference(UUID.randomUUID(), true);
        dest = createMockBubbleReference(UUID.randomUUID(), true);
    }

    private BubbleReference createMockBubbleReference(UUID bubbleId, boolean reachable) {
        return new TestBubbleReference(bubbleId, reachable);
    }

    @AfterEach
    void tearDown() {
        if (migration != null) {
            migration.stop();
        }
    }

    /**
     * Test: Entities tracked during migration, cleaned up on success.
     */
    @Test
    void testEntityTrackedAndCleanedOnSuccess() throws Exception {
        // No need to add entity - TestBubbleReference automatically handles it
        var entityId = "entity1";

        // Start migration
        var future = migration.migrate(entityId, source, dest);

        // Wait for completion
        var result = future.get(2, TimeUnit.SECONDS);
        assertTrue(result.success(), "Migration should succeed");

        // After completion, entity should be cleaned up
        // Note: Cleanup happens via future.whenComplete(), may be immediate
        Thread.sleep(50); // Allow cleanup to complete
        assertEquals(0, migration.getActiveEntityCount(),
                    "Entity should be cleaned up after successful migration");
    }

    /**
     * Test: Entities cleaned up on failure.
     */
    @Test
    void testEntityCleanedOnFailure() throws Exception {
        // Make destination unreachable
        dest = createMockBubbleReference(UUID.randomUUID(), false);

        var entityId = "entity2";

        // Start migration
        var future = migration.migrate(entityId, source, dest);

        // Wait for completion (should fail)
        var result = future.get(2, TimeUnit.SECONDS);
        assertFalse(result.success(), "Migration should fail (destination unreachable)");

        // After completion, entity should be cleaned up
        Thread.sleep(50); // Allow cleanup to complete
        assertEquals(0, migration.getActiveEntityCount(),
                    "Entity should be cleaned up after failed migration");
    }

    /**
     * Test: No memory leaks after 1000 migrations.
     */
    @Test
    void testNoLeaksAfter1000Migrations() throws Exception {
        var entityCount = 1000;
        var futures = new ArrayList<CompletableFuture<MigrationResult>>(entityCount);

        // Run 1000 migrations (TestBubbleReference handles entity storage automatically)
        for (int i = 0; i < entityCount; i++) {
            var entityId = "entity" + i;
            var future = migration.migrate(entityId, source, dest);
            futures.add(future);
        }

        // Wait for all to complete
        for (var future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }

        // Allow cleanup to complete
        Thread.sleep(100);

        // All entities should be cleaned up
        assertEquals(0, migration.getActiveEntityCount(),
                    "No entities should be active after all migrations complete (no memory leak)");

        // Verify metrics show successful migrations
        assertTrue(metrics.getSuccessfulMigrations() > 0, "Some migrations should have succeeded");
    }

    /**
     * Test: Concurrent migrations tracked independently.
     */
    @Test
    void testConcurrentMigrationsTrackedIndependently() throws Exception {
        // Start 10 concurrent migrations (TestBubbleReference handles entity storage)
        var futures = new ArrayList<CompletableFuture<MigrationResult>>(10);
        for (int i = 0; i < 10; i++) {
            var entityId = "entity" + i;
            var future = migration.migrate(entityId, source, dest);
            futures.add(future);
        }

        // Wait for all to complete
        for (var future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }

        // All should be cleaned up
        Thread.sleep(100);
        assertEquals(0, migration.getActiveEntityCount(),
                    "All entities should be cleaned up after concurrent migrations");
    }

    /**
     * Test: getActiveEntityCount() reflects current state accurately.
     */
    @Test
    void testGetActiveEntityCountAccuracy() throws Exception {
        assertEquals(0, migration.getActiveEntityCount(), "Initially no entities");

        // Start migration
        var entityId = "entity1";
        var future = migration.migrate(entityId, source, dest);

        // Complete migration
        future.get(2, TimeUnit.SECONDS);

        // Count should return to 0 after cleanup
        Thread.sleep(50);
        assertEquals(0, migration.getActiveEntityCount(), "Count should return to 0 after completion");
    }

    /**
     * Test: Cleanup future chain handles exceptions gracefully.
     */
    @Test
    void testCleanupHandlesExceptions() throws Exception {
        // Simulate exceptional completion by making destination unreachable
        dest = createMockBubbleReference(UUID.randomUUID(), false);

        var entityId = "entity1";
        var future = migration.migrate(entityId, source, dest);
        future.get(2, TimeUnit.SECONDS);

        // Cleanup should still occur despite failure
        Thread.sleep(50);
        assertEquals(0, migration.getActiveEntityCount(),
                    "Entity should be cleaned up even after exceptional completion");
    }

    /**
     * Test: Duplicate migration doesn't leak (entity cleaned up on ALREADY_APPLIED).
     */
    @Test
    void testDuplicateMigrationCleansUp() throws Exception {
        var entityId = "entity1";

        // First migration
        var future1 = migration.migrate(entityId, source, dest);
        var result1 = future1.get(2, TimeUnit.SECONDS);
        assertTrue(result1.success(), "First migration should succeed");

        Thread.sleep(50);
        assertEquals(0, migration.getActiveEntityCount(), "First migration cleaned up");

        // Second migration (duplicate, should be rejected)
        var future2 = migration.migrate(entityId, source, dest);
        var result2 = future2.get(2, TimeUnit.SECONDS);
        assertFalse(result2.success(), "Duplicate migration should fail");
        assertEquals("ALREADY_APPLIED", result2.reason(), "Should reject duplicate");

        // Cleanup should still occur
        Thread.sleep(50);
        assertEquals(0, migration.getActiveEntityCount(),
                    "Duplicate migration should also be cleaned up");
    }

    /**
     * Test: Concurrent migration of same entity cleans up both attempts.
     */
    @Test
    void testConcurrentSameEntityCleansUpBoth() throws Exception {
        var entityId = "entity1";

        // Start two migrations of same entity concurrently
        var future1 = migration.migrate(entityId, source, dest);
        var future2 = migration.migrate(entityId, source, dest);

        // One should succeed, one should fail with ALREADY_MIGRATING
        var result1 = future1.get(2, TimeUnit.SECONDS);
        var result2 = future2.get(2, TimeUnit.SECONDS);

        // Exactly one should succeed
        var successCount = (result1.success() ? 1 : 0) + (result2.success() ? 1 : 0);
        assertEquals(1, successCount, "Exactly one migration should succeed");

        // Both should be cleaned up
        Thread.sleep(100);
        assertEquals(0, migration.getActiveEntityCount(),
                    "Both concurrent attempts should be cleaned up");
    }

    // ===== Luciferase-7wzml.41 tests =====

    /**
     * Luciferase-7wzml.41: Orphan cleanup must complete resultFuture so blocked callers unblock.
     * <p>
     * Scenario: an entity is stuck in activeEntities with a phaseStartTime >5 min ago (simulates
     * a programming-error orphan). When triggerOrphanCleanupForTesting() fires:
     * - The returned future must be done (isDone() == true).
     * - future.get(timeout) must return immediately (not hang).
     * - The result must be a failure with reason "ORPHANED_CLEANUP".
     * - The entity must be removed from activeEntities.
     */
    @Test
    void testOrphanCleanupCompletesFuturePromptly() throws Exception {
        var testClock = new SimpleTestClock(1_000L);
        migration.setClock(testClock);

        var entityId = "orphan-entity";
        var future = new CompletableFuture<MigrationResult>();

        // Build a minimal entity; supply a no-op lock (already unlocked) and a clock
        // that returns the current testClock time.
        var lock = new ReentrantLock();
        var entity = new CrossProcessMigration.CrossProcessMigrationEntity(
            entityId,
            source, dest,
            future,
            lock,
            testClock::currentTimeMillis,
            () -> {}, // incrementConcurrent
            () -> {}, // decrementConcurrent
            token -> {}, // checkAndStoreMigration
            () -> {}, // recordDuplicateRejection
            reason -> {}, // recordFailure
            latency -> {}, // recordSuccess
            () -> {}, // recordAlreadyMigrating
            () -> {}, // recordRollbackFailure
            reason -> {}, // recordAbort
            dedup,
            id -> {}, // recordOrphanedEntity
            MigrationConfig.defaults(),
            state -> {}, // walRecordPrepare
            txnId -> {}, // walRecordCommit
            txnId -> {}  // walRecordAbort
        );

        // Back-date phaseStartTime to 6 minutes ago (well past the 5-min orphan threshold).
        long sixMinutesAgoMs = testClock.currentTimeMillis() - (6 * 60 * 1000);
        entity.phaseStartTime = sixMinutesAgoMs;

        // Inject the stuck entity directly (simulates the "orphan" bug scenario).
        migration.injectActiveEntityForTesting(entityId, entity);
        assertFalse(future.isDone(), "Future must be incomplete before cleanup");

        // Trigger cleanup — must complete the future before returning.
        migration.triggerOrphanCleanupForTesting();

        // Future must be done immediately (no blocking).
        assertTrue(future.isDone(), "Orphan cleanup must complete the resultFuture");

        // Bounded get — if this hangs the fix regressed (fail fast, not hang the suite).
        var result = future.get(1, TimeUnit.SECONDS);
        assertNotNull(result, "Result must not be null");
        assertFalse(result.success(), "Orphaned entity result must be a failure");
        assertEquals("ORPHANED_CLEANUP", result.reason(),
                     "Failure reason must be ORPHANED_CLEANUP");

        // Entity must be removed from active tracking.
        assertEquals(0, migration.getActiveEntityCount(),
                     "Orphaned entity must be removed from activeEntities after cleanup");
    }

    /**
     * Luciferase-7wzml.41: phaseStartTime must be declared volatile.
     * Verifies the field annotation via reflection so a refactor that drops volatile is caught.
     */
    @Test
    void testPhaseStartTimeIsVolatile() throws Exception {
        var field = CrossProcessMigration.CrossProcessMigrationEntity.class
                        .getDeclaredField("phaseStartTime");
        int modifiers = field.getModifiers();
        assertTrue(java.lang.reflect.Modifier.isVolatile(modifiers),
                   "CrossProcessMigrationEntity.phaseStartTime must be volatile for cross-thread visibility");
    }

    // ===== Helper Classes =====

    /**
     * Minimal controllable clock for deterministic testing (local to avoid cross-module deps).
     */
    private static class SimpleTestClock implements Clock {
        private volatile long millis;

        SimpleTestClock(long initialMillis) {
            this.millis = initialMillis;
        }

        void advance(long deltaMs) {
            millis += deltaMs;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        @Override
        public long nanoTime() {
            return millis * 1_000_000L;
        }
    }

    /**
     * Test bubble reference that implements TestableEntityStore.
     */
    private static class TestBubbleReference implements BubbleReference, TestableEntityStore {
        private final UUID    bubbleId;
        private final boolean reachable;

        TestBubbleReference(UUID bubbleId, boolean reachable) {
            this.bubbleId = bubbleId;
            this.reachable = reachable;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public LocalBubbleReference asLocal() {
            return null;
        }

        @Override
        public RemoteBubbleProxy asRemote() {
            throw new IllegalStateException("Not remote");
        }

        @Override
        public UUID getBubbleId() {
            return bubbleId;
        }

        @Override
        public Point3d getPosition() {
            return new Point3d(0, 0, 0);
        }

        @Override
        public Set<UUID> getNeighbors() {
            return new HashSet<>();
        }

        @Override
        public boolean removeEntity(String entityId) {
            return reachable;
        }

        @Override
        public boolean addEntity(EntitySnapshot snapshot) {
            return reachable;
        }

        @Override
        public boolean isReachable() {
            return reachable;
        }
    }
}
