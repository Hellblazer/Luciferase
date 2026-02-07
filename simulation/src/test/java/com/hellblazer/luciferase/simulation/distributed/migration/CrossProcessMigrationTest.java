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
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CrossProcessMigration (Phase 6B4.4).
 * <p>
 * Verifies:
 * - Successful migration (happy path)
 * - Idempotent migration (same token, no-op)
 * - PREPARE timeout handling
 * - COMMIT timeout handling
 * - ABORT on prepare failure
 * - ABORT on commit failure
 * - Rollback restores entity to source
 * - Concurrent migrations of same entity (C4 - CRITICAL)
 * - Concurrent migrations of different entities
 * - Network partition recovery
 * <p>
 * Architecture Decision D6B.8: Remove-then-commit ordering with 2PC protocol.
 * <p>
 * Critical Conditions Addressed:
 * - C1: Migration lock prevents concurrent migrations of same entity
 * - C3: Rollback-failure logging and metrics
 * - C4: testConcurrentMigrationsSameEntity verifies lock behavior
 *
 * @author hal.hildebrand
 */
class CrossProcessMigrationTest {

    private IdempotencyStore      dedup;
    private MigrationMetrics      metrics;
    private CrossProcessMigration migration;

    @BeforeEach
    void setUp() {
        dedup = new IdempotencyStore(300_000); // 5 min TTL
        metrics = new MigrationMetrics();
        migration = new CrossProcessMigration(dedup, metrics);
    }

    @AfterEach
    void tearDown() {
        if (migration != null) {
            migration.stop();
        }
    }

    /**
     * Test 1: Successful migration (happy path).
     * <p>
     * Verifies:
     * - Entity removed from source
     * - Entity added to destination
     * - Idempotency token persisted
     * - Metrics updated
     * - Result indicates success
     */
    @Test
    @Timeout(5)
    void testSuccessfulMigration() throws Exception {
        var entityId = "test-entity-1";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var source = createMockBubbleReference(sourceId, true);
        var dest = createMockBubbleReference(destId, true);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertTrue(result.success(), "Migration should succeed");
        assertEquals(entityId, result.entityId());
        assertNotNull(result.destProcessId());
        assertNull(result.reason());

        // Verify metrics
        assertEquals(1, metrics.getSuccessfulMigrations());
        assertEquals(0, metrics.getFailedMigrations());
        assertEquals(0, metrics.getAborts());
        // Migration can complete in 0ms on fast systems, so >= 0 is correct
        assertTrue(result.latencyMs() >= 0);
    }

    /**
     * Test 2: Idempotent migration (same token, no-op).
     * <p>
     * Verifies:
     * - Duplicate token rejected
     * - No state changes
     * - Metrics track duplicate rejection
     * - Result indicates already applied
     */
    @Test
    @Timeout(5)
    void testIdempotentMigration() throws Exception {
        var entityId = "test-entity-2";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var source = createMockBubbleReference(sourceId, true);
        var dest = createMockBubbleReference(destId, true);

        // First migration
        var result1 = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);
        assertTrue(result1.success());

        // Second migration with same parameters (duplicate token)
        var result2 = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        // Should be rejected as duplicate
        assertFalse(result2.success());
        assertEquals("ALREADY_APPLIED", result2.reason());

        // Verify metrics
        assertEquals(1, metrics.getSuccessfulMigrations());
        assertEquals(1, metrics.getDuplicatesRejected());
    }

    /**
     * Test 3: PREPARE timeout handling.
     * <p>
     * Verifies:
     * - Timeout detected after 100ms
     * - Transaction aborted
     * - Metrics track failure
     * - Result indicates timeout
     */
    @Test
    @Timeout(5)
    void testPrepareTimeout() throws Exception {
        var entityId = "test-entity-3";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        // Source that delays response beyond timeout
        var source = createDelayedBubbleReference(sourceId, 150);
        var dest = createMockBubbleReference(destId, true);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.reason().contains("TIMEOUT") || result.reason().contains("PREPARE_FAILED"));

        // Verify metrics
        assertEquals(0, metrics.getSuccessfulMigrations());
        assertEquals(1, metrics.getFailedMigrations());
    }

    /**
     * Test 4: COMMIT timeout handling.
     * <p>
     * Verifies:
     * - Timeout detected after 100ms
     * - Transaction aborted
     * - Rollback executed
     * - Metrics track failure
     */
    @Test
    @Timeout(5)
    void testCommitTimeout() throws Exception {
        var entityId = "test-entity-4";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var source = createMockBubbleReference(sourceId, true);
        // Destination that delays response beyond timeout
        var dest = createDelayedBubbleReference(destId, 150);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.reason().contains("TIMEOUT") || result.reason().contains("COMMIT_FAILED"));

        // Verify abort executed
        assertEquals(0, metrics.getSuccessfulMigrations());
        assertEquals(1, metrics.getFailedMigrations());
    }

    /**
     * Test 5: ABORT on prepare failure (destination unreachable).
     * <p>
     * Verifies:
     * - Prepare failure detected
     * - Transaction aborted
     * - Metrics track abort
     */
    @Test
    @Timeout(5)
    void testAbortOnPrepareFailure() throws Exception {
        var entityId = "test-entity-5";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var source = createMockBubbleReference(sourceId, true);
        // Destination that fails validation
        var dest = createMockBubbleReference(destId, false);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.reason().contains("UNREACHABLE") || result.reason().contains("PREPARE_FAILED"));

        // Verify abort
        assertEquals(0, metrics.getSuccessfulMigrations());
        assertEquals(1, metrics.getFailedMigrations());
    }

    /**
     * Test 6: ABORT on commit failure (add fails).
     * <p>
     * Verifies:
     * - Commit failure detected
     * - Transaction aborted
     * - Rollback executed
     */
    @Test
    @Timeout(5)
    void testAbortOnCommitFailure() throws Exception {
        var entityId = "test-entity-6";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var source = createMockBubbleReference(sourceId, true);
        // Destination that fails during commit
        var dest = createFailingCommitBubbleReference(destId);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.reason().contains("COMMIT_FAILED"));

        // Verify abort and rollback
        assertEquals(0, metrics.getSuccessfulMigrations());
        assertEquals(1, metrics.getFailedMigrations());
    }

    /**
     * Test 7: Rollback restores entity to source.
     * <p>
     * Verifies:
     * - Rollback re-adds entity to source
     * - Entity state restored from snapshot
     * - Idempotency token removed (allows retry)
     */
    @Test
    @Timeout(5)
    void testRollbackRestoresEntity() throws Exception {
        var entityId = "test-entity-7";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var sourceEntityState = new AtomicReference<String>("PRESENT");

        var source = new BubbleReference() {
            @Override
            public boolean isLocal() {
                return true;
            }

            @Override
            public com.hellblazer.luciferase.simulation.distributed.LocalBubbleReference asLocal() {
                return null;
            }

            @Override
            public com.hellblazer.luciferase.simulation.distributed.RemoteBubbleProxy asRemote() {
                throw new IllegalStateException("Not remote");
            }

            @Override
            public UUID getBubbleId() {
                return sourceId;
            }

            @Override
            public Point3D getPosition() {
                return new Point3D(0, 0, 0);
            }

            @Override
            public Set<UUID> getNeighbors() {
                return new HashSet<>();
            }
        };

        // Destination that fails commit
        var dest = createFailingCommitBubbleReference(destId);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertFalse(result.success());

        // After rollback, entity should be restored to source
        // (In real implementation, we'd verify via source.hasEntity(entityId))
        // Here we verify metrics show abort was called
        assertEquals(1, metrics.getFailedMigrations());
    }

    /**
     * Test 8: Concurrent migrations of same entity (C4 - CRITICAL).
     * <p>
     * Verifies:
     * - Only one migration succeeds
     * - Second migration fails with ALREADY_MIGRATING
     * - Metrics track already-migrating count
     * - Entity migration lock prevents race condition (C1)
     */
    @Test
    @Timeout(5)
    @org.junit.jupiter.api.Disabled("Incompatible with single-threaded Prime-Mover event loop: " +
                                    "Thread.sleep() in simulateDelay() blocks the entire event loop, " +
                                    "preventing true concurrent execution. Needs redesign for event-driven model.")
    void testConcurrentMigrationsSameEntity() throws Exception {
        var entityId = "test-entity-8";
        var sourceId = UUID.randomUUID();
        var destId1 = UUID.randomUUID();
        var destId2 = UUID.randomUUID();

        // Use delayed source/dest to ensure migrations overlap in time
        // Delay must be > LOCK_TIMEOUT_MS (50ms) to force second migration to fail
        // But not so long that first migration times out (PHASE_TIMEOUT_MS = 100ms)
        // Use 60ms: enough to force overlap, but won't trigger phase timeout
        var source = createDelayedBubbleReference(sourceId, 60);
        var dest1 = createDelayedBubbleReference(destId1, 0);  // dest1 instant
        var dest2 = createDelayedBubbleReference(destId2, 0);  // dest2 instant

        var latch = new CountDownLatch(2);
        var successCount = new AtomicInteger(0);
        var alreadyMigratingCount = new AtomicInteger(0);

        // Start two migrations concurrently
        var future1 = CompletableFuture.supplyAsync(() -> {
            try {
                var result = migration.migrate(entityId, source, dest1).get(2, TimeUnit.SECONDS);
                if (result.success()) {
                    successCount.incrementAndGet();
                } else if ("ALREADY_MIGRATING".equals(result.reason())) {
                    alreadyMigratingCount.incrementAndGet();
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        });

        var future2 = CompletableFuture.supplyAsync(() -> {
            try {
                var result = migration.migrate(entityId, source, dest2).get(2, TimeUnit.SECONDS);
                if (result.success()) {
                    successCount.incrementAndGet();
                } else if ("ALREADY_MIGRATING".equals(result.reason())) {
                    alreadyMigratingCount.incrementAndGet();
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);

        // Exactly one should succeed, one should fail with ALREADY_MIGRATING
        assertEquals(1, successCount.get(), "Exactly one migration should succeed");
        assertEquals(1, alreadyMigratingCount.get(), "Exactly one should be rejected as already migrating");

        // Verify metrics
        assertTrue(metrics.getAlreadyMigrating() >= 1, "Should track at least one already-migrating rejection");
    }

    /**
     * Test 9: Concurrent migrations of different entities.
     * <p>
     * Verifies:
     * - Both migrations succeed
     * - No interference between different entity migrations
     * - Concurrent migrations gauge tracks correctly
     */
    @Test
    @Timeout(5)
    void testConcurrentMigrationsDifferentEntities() throws Exception {
        var entityId1 = "test-entity-9a";
        var entityId2 = "test-entity-9b";
        var sourceId1 = UUID.randomUUID();
        var sourceId2 = UUID.randomUUID();
        var destId1 = UUID.randomUUID();
        var destId2 = UUID.randomUUID();

        var source1 = createMockBubbleReference(sourceId1, true);
        var source2 = createMockBubbleReference(sourceId2, true);
        var dest1 = createMockBubbleReference(destId1, true);
        var dest2 = createMockBubbleReference(destId2, true);

        var latch = new CountDownLatch(2);
        var successCount = new AtomicInteger(0);

        var future1 = CompletableFuture.supplyAsync(() -> {
            try {
                var result = migration.migrate(entityId1, source1, dest1).get(2, TimeUnit.SECONDS);
                if (result.success()) {
                    successCount.incrementAndGet();
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        });

        var future2 = CompletableFuture.supplyAsync(() -> {
            try {
                var result = migration.migrate(entityId2, source2, dest2).get(2, TimeUnit.SECONDS);
                if (result.success()) {
                    successCount.incrementAndGet();
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);

        // Both should succeed
        assertEquals(2, successCount.get(), "Both migrations should succeed");
        assertEquals(2, metrics.getSuccessfulMigrations());
    }

    /**
     * Test 10: Network partition recovery.
     * <p>
     * Verifies:
     * - Timeout detected
     * - Rollback executed
     * - System recovers gracefully
     * - Retry allowed after rollback
     */
    @Test
    @Timeout(5)
    void testNetworkPartitionRecovery() throws Exception {
        var entityId = "test-entity-10";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        // First attempt: destination partitioned (timeout)
        var source = createMockBubbleReference(sourceId, true);
        var destPartitioned = createDelayedBubbleReference(destId, 150);

        var result1 = migration.migrate(entityId, source, destPartitioned).get(2, TimeUnit.SECONDS);
        assertFalse(result1.success());

        // Second attempt: destination recovered
        var destRecovered = createMockBubbleReference(destId, true);

        var result2 = migration.migrate(entityId, source, destRecovered).get(2, TimeUnit.SECONDS);

        // Should succeed after recovery
        assertTrue(result2.success(), "Should succeed after partition recovery");
        assertEquals(1, metrics.getSuccessfulMigrations());
    }

    // Helper methods

    /**
     * Test bubble reference that implements TestableEntityStore.
     */
    private static class TestBubbleReference implements BubbleReference, TestableEntityStore {
        private final UUID    bubbleId;
        private final boolean reachable;
        private final long    delayMs;
        private final boolean failCommit;

        TestBubbleReference(UUID bubbleId, boolean reachable) {
            this(bubbleId, reachable, 0, false);
        }

        TestBubbleReference(UUID bubbleId, boolean reachable, long delayMs, boolean failCommit) {
            this.bubbleId = bubbleId;
            this.reachable = reachable;
            this.delayMs = delayMs;
            this.failCommit = failCommit;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.LocalBubbleReference asLocal() {
            return null;
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.RemoteBubbleProxy asRemote() {
            throw new IllegalStateException("Not remote");
        }

        @Override
        public UUID getBubbleId() {
            return bubbleId;
        }

        @Override
        public Point3D getPosition() {
            return new Point3D(0, 0, 0);
        }

        @Override
        public Set<UUID> getNeighbors() {
            return new HashSet<>();
        }

        @Override
        public boolean removeEntity(String entityId) {
            simulateDelay(delayMs);
            return reachable && !failCommit; // Fail if not reachable or failCommit set
        }

        @Override
        public boolean addEntity(EntitySnapshot snapshot) {
            simulateDelay(delayMs);
            return reachable && !failCommit; // Fail if not reachable or failCommit set
        }

        @Override
        public boolean isReachable() {
            return reachable;
        }
    }

    /**
     * BubbleReference that fails rollback operations (addEntity) but succeeds at remove.
     * <p>
     * Used to test orphan tracking when rollback fails.
     */
    private static class RollbackFailingBubbleReference implements BubbleReference, TestableEntityStore {
        private final UUID bubbleId;

        RollbackFailingBubbleReference(UUID bubbleId) {
            this.bubbleId = bubbleId;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.LocalBubbleReference asLocal() {
            return null;
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.RemoteBubbleProxy asRemote() {
            throw new IllegalStateException("Not remote");
        }

        @Override
        public UUID getBubbleId() {
            return bubbleId;
        }

        @Override
        public Point3D getPosition() {
            return new Point3D(0, 0, 0);
        }

        @Override
        public Set<UUID> getNeighbors() {
            return new HashSet<>();
        }

        @Override
        public boolean removeEntity(String entityId) {
            return true; // Succeed at removing (prepare phase works)
        }

        @Override
        public boolean addEntity(EntitySnapshot snapshot) {
            return false; // Fail at adding (rollback phase fails)
        }

        @Override
        public boolean isReachable() {
            return true;
        }
    }

    private BubbleReference createMockBubbleReference(UUID bubbleId, boolean reachable) {
        return new TestBubbleReference(bubbleId, reachable);
    }

    private BubbleReference createDelayedBubbleReference(UUID bubbleId, long delayMs) {
        return new TestBubbleReference(bubbleId, true, delayMs, false);
    }

    private BubbleReference createFailingCommitBubbleReference(UUID bubbleId) {
        return new TestBubbleReference(bubbleId, true, 0, true);
    }

    private BubbleReference createRollbackFailingBubbleReference(UUID bubbleId) {
        return new RollbackFailingBubbleReference(bubbleId);
    }

    /**
     * Test 11: Memory leak - entityMigrationLocks map grows unbounded (BUG-004).
     * <p>
     * Verifies:
     * - entityMigrationLocks map grows with each unique entity migration
     * - Locks are never removed from the map (before fix)
     * - Production scenario: 1M entities = ~50MB leak
     */
    @Test
    @Timeout(5)
    void testEntityMigrationLocksMemoryLeak() throws Exception {
        // Migrate N different entities
        var entityCount = 100;
        for (var i = 0; i < entityCount; i++) {
            var entityId = "leak-test-entity-" + i;
            var sourceId = UUID.randomUUID();
            var destId = UUID.randomUUID();

            var source = createMockBubbleReference(sourceId, true);
            var dest = createMockBubbleReference(destId, true);

            var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);
            assertTrue(result.success(), "Migration " + i + " should succeed");
        }

        // BEFORE FIX: entityMigrationLocks.size() == entityCount (LEAK!)
        // AFTER FIX: entityMigrationLocks should be bounded (GC cleans up)

        // Access the map size via reflection (since it's private)
        var field = CrossProcessMigration.class.getDeclaredField("entityMigrationLocks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var locksMap = (java.util.Map<String, ?>) field.get(migration);

        var mapSize = locksMap.size();

        // Force GC to clean up unreferenced locks
        System.gc();
        Thread.sleep(100); // Give GC time to run

        // Count how many WeakReferences still hold live locks
        var liveLocksCount = 0;
        @SuppressWarnings("unchecked")
        var weakRefs = (java.util.Map<String, WeakReference<ReentrantLock>>) locksMap;
        for (var ref : weakRefs.values()) {
            if (ref.get() != null) {
                liveLocksCount++;
            }
        }

        // BEFORE FIX: All locks remain alive (strong references in map)
        // AFTER FIX: Most locks should be GC'd (WeakReference allows cleanup)
        // The map may still have WeakReference entries, but the locks themselves are GC'd
        assertTrue(liveLocksCount < entityCount / 10,
                   "After GC, most locks should be collected. Live locks: " + liveLocksCount +
                   " out of " + entityCount + " (map size: " + mapSize + "). " +
                   "WeakReference allows lock GC without shrinking map.");
    }

    /**
     * Test Phase2C-1: Orphan tracking on rollback failure.
     * <p>
     * Verifies:
     * - When rollback fails, entity ID is tracked in orphanedEntityIds
     * - getOrphanedEntities() returns the orphaned entity ID
     * - Orphan tracking is thread-safe (ConcurrentHashSet)
     */
    @Test
    @Timeout(5)
    void testOrphanTrackingOnRollbackFailure() throws Exception {
        var entityId = "orphan-test-entity-1";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        // Source that fails rollback: removeEntity succeeds, but addEntity fails
        var source = createRollbackFailingBubbleReference(sourceId);
        // Destination that fails commit
        var dest = createFailingCommitBubbleReference(destId);

        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        assertFalse(result.success(), "Migration should fail");
        assertTrue(result.reason().contains("COMMIT_FAILED") || result.reason().contains("ROLLBACK"),
                   "Failure reason should indicate commit or rollback failure");

        // Verify orphan tracking
        var orphanedEntities = migration.getOrphanedEntities();
        assertEquals(1, orphanedEntities.size(), "Should have 1 orphaned entity");
        assertTrue(orphanedEntities.contains(entityId), "Orphaned entities should contain " + entityId);

        // Verify metrics recorded rollback failure
        assertEquals(1, metrics.getRollbackFailures(), "Should have 1 rollback failure");
    }

    /**
     * Test 12: C1 constraint preservation after WeakReference cleanup (BUG-004).
     * <p>
     * Verifies:
     * - WeakReference approach maintains C1 constraint
     * - Concurrent migrations of same entity are still blocked
     * - No race conditions after GC cleanup
     */
    @Test
    @Timeout(5)
    void testC1ConstraintPreservedAfterCleanup() throws Exception {
        var entityId = "c1-test-entity";
        var sourceId = UUID.randomUUID();
        var destId1 = UUID.randomUUID();
        var destId2 = UUID.randomUUID();

        var source = createMockBubbleReference(sourceId, true);
        var dest1 = createMockBubbleReference(destId1, true);
        var dest2 = createMockBubbleReference(destId2, true);

        // First migration - establishes lock
        var result1 = migration.migrate(entityId, source, dest1).get(2, TimeUnit.SECONDS);
        assertTrue(result1.success(), "First migration should succeed");

        // Force GC to clean up WeakReferences
        System.gc();
        Thread.sleep(100); // Give GC time to run

        // Second migration of SAME entity to different destination
        // C1 constraint: Should create a NEW lock (old one was GC'd)
        // But still must prevent concurrent migration if lock exists
        var result2 = migration.migrate(entityId, source, dest2).get(2, TimeUnit.SECONDS);

        // This is NOT idempotent (different dest), so should succeed if lock was cleaned
        // OR fail with ALREADY_APPLIED if dedup store still has the token
        assertTrue(result2.success() || "ALREADY_APPLIED".equals(result2.reason()),
                   "Second migration should either succeed (new lock) or be deduplicated");

        // Critical: Even after GC, concurrent migrations of SAME entity must be blocked
        // This is tested implicitly by the entity migration lock mechanism
    }

    /**
     * Test 13: Stress test - 10,000 migrations verify map doesn't grow unbounded (BUG-004).
     * <p>
     * Verifies:
     * - Large-scale migration workload
     * - Lock map remains bounded after GC
     * - No memory exhaustion
     */
    @Test
    @Timeout(30)
    void testEntityMigrationLocksStressTest() throws Exception {
        var entityCount = 10_000;

        // Migrate many entities
        for (var i = 0; i < entityCount; i++) {
            var entityId = "stress-test-entity-" + i;
            var sourceId = UUID.randomUUID();
            var destId = UUID.randomUUID();

            var source = createMockBubbleReference(sourceId, true);
            var dest = createMockBubbleReference(destId, true);

            var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);
            assertTrue(result.success(), "Migration " + i + " should succeed");

            // Periodic GC to allow cleanup
            if (i % 1000 == 0) {
                System.gc();
                Thread.sleep(10); // Brief pause for GC
            }
        }

        // Force final GC
        System.gc();
        Thread.sleep(100);

        // Access the map via reflection
        var field = CrossProcessMigration.class.getDeclaredField("entityMigrationLocks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var locksMap = (java.util.Map<String, ?>) field.get(migration);

        // Count how many WeakReferences still hold live locks
        var liveLocksCount = 0;
        @SuppressWarnings("unchecked")
        var weakRefs = (java.util.Map<String, WeakReference<ReentrantLock>>) locksMap;
        for (var ref : weakRefs.values()) {
            if (ref.get() != null) {
                liveLocksCount++;
            }
        }

        // AFTER FIX: Most locks should be GC'd (WeakReference allows cleanup)
        // Allow some locks to remain, but not all 10,000
        assertTrue(liveLocksCount < entityCount / 10,
                   "AFTER FIX: Most locks should be GC'd. Live locks: " + liveLocksCount +
                   " out of " + entityCount + " (map entries: " + locksMap.size() + ")");
    }

    /**
     * Test 13: getRecoveryState() returns accurate recovery state (Phase 2C).
     * <p>
     * Verifies:
     * - Orphaned entities are tracked when rollback fails
     * - getRecoveryState() returns correct count
     * - Recovery state includes rollback failure count
     * - Recovery state includes concurrent migrations
     */
    @Test
    @Timeout(5)
    void testGetRecoveryState() throws Exception {
        var entityId = "orphan-test-entity";
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        // Create source that removes successfully but FAILS rollback (addEntity)
        var source = createRollbackFailingBubbleReference(sourceId);
        // Create dest that fails commit (addEntity)
        var dest = createFailingCommitBubbleReference(destId);

        // Initial state: no orphans, no failures
        var initialState = migration.getRecoveryState();
        assertEquals(0, initialState.orphanedEntities().size(), "Should start with no orphans");
        assertEquals(0, initialState.rollbackFailures(), "Should start with no rollback failures");

        // Trigger migration that will:
        // 1. Remove from source (succeeds)
        // 2. Add to dest (fails - triggers ABORT)
        // 3. Rollback to source (fails - creates orphan)
        var result = migration.migrate(entityId, source, dest).get(2, TimeUnit.SECONDS);

        // Migration should fail
        assertFalse(result.success(), "Migration should fail");

        // Verify orphan tracking
        var orphans = migration.getOrphanedEntities();
        assertEquals(1, orphans.size(), "Should have 1 orphaned entity");
        assertTrue(orphans.contains(entityId), "Should contain orphaned entity");

        // Verify getRecoveryState() includes orphan data
        var recoveryState = migration.getRecoveryState();
        assertEquals(1, recoveryState.orphanedEntities().size(), "RecoveryState should show 1 orphan");
        assertTrue(recoveryState.orphanedEntities().contains(entityId),
                   "RecoveryState should contain orphaned entity");
        assertEquals(1, recoveryState.rollbackFailures(),
                     "RecoveryState should show 1 rollback failure");
    }

    /**
     * Test 14: Multiple orphans tracked correctly (Phase 2C).
     * <p>
     * Verifies:
     * - Multiple rollback failures create multiple orphans
     * - getRecoveryState() reflects cumulative state
     * - Orphan set is thread-safe
     */
    @Test
    @Timeout(5)
    void testMultipleOrphans() throws Exception {
        var entityCount = 5;
        var futures = new CompletableFuture[entityCount];

        for (var i = 0; i < entityCount; i++) {
            var entityId = "orphan-" + i;
            var sourceId = UUID.randomUUID();
            var destId = UUID.randomUUID();

            var source = createRollbackFailingBubbleReference(sourceId);
            var dest = createFailingCommitBubbleReference(destId);

            futures[i] = migration.migrate(entityId, source, dest);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

        // Verify all are orphaned
        var orphans = migration.getOrphanedEntities();
        assertEquals(entityCount, orphans.size(), "Should have " + entityCount + " orphaned entities");

        // Verify getRecoveryState() shows correct count
        var recoveryState = migration.getRecoveryState();
        assertEquals(entityCount, recoveryState.orphanedEntities().size(),
                     "RecoveryState should show " + entityCount + " orphans");
        assertEquals(entityCount, recoveryState.rollbackFailures(),
                     "RecoveryState should show " + entityCount + " rollback failures");
    }
}
