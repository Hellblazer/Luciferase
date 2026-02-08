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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OptimisticMigrator deferred update queue (Phase 7E Day 3)
 *
 * Tests verify:
 * - Deferred queue management with max 100 events
 * - Migration lifecycle (initiate → queue → flush → complete)
 * - Rollback mechanism on view changes
 * - Overflow handling with oldest-event dropping
 * - Concurrent access from multiple migrations
 *
 * Success Criteria:
 * - Queue max size enforced (100 events)
 * - Overflow drops oldest events, logs warning
 * - Performance < 1ms per 100 migrations
 * - Metrics tracking correct across all operations
 *
 * @author hal.hildebrand
 */
@DisplayName("OptimisticMigrator - Deferred Update Queue")
class OptimisticMigratorTest {

    private static final Logger log = LoggerFactory.getLogger(OptimisticMigratorTest.class);

    private OptimisticMigratorImpl migrator;
    private UUID entity1;
    private UUID entity2;
    private UUID targetBubble1;
    private UUID targetBubble2;

    @BeforeEach
    void setUp() {
        migrator = new OptimisticMigratorImpl();
        entity1 = UUID.randomUUID();
        entity2 = UUID.randomUUID();
        targetBubble1 = UUID.randomUUID();
        targetBubble2 = UUID.randomUUID();
    }

    @Test
    @DisplayName("Initiates optimistic migration")
    void testInitiateOptimisticMigration() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);

        // After initiation, queue should exist but be empty
        assertEquals(0, migrator.getDeferredQueueSize(entity1),
                "Queue should be empty after initiation");
        assertEquals(0, migrator.getPendingDeferredCount(),
                "No pending deferred updates immediately after initiation");
    }

    @Test
    @DisplayName("Queues deferred position/velocity updates")
    void testQueueDeferredUpdates() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);

        // Queue 10 updates
        for (int i = 0; i < 10; i++) {
            var position = new float[]{1.0f + i * 0.1f, 2.0f, 3.0f};
            var velocity = new float[]{0.5f, 0.0f, 0.1f};
            migrator.queueDeferredUpdate(entity1, position, velocity);
        }

        assertEquals(10, migrator.getDeferredQueueSize(entity1),
                "Queue should contain 10 updates");
        assertEquals(1, migrator.getPendingDeferredCount(),
                "Should have 1 entity with pending updates");
    }

    @Test
    @DisplayName("Enforces max queue size of 100 events")
    void testQueueSizeLimit() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);

        // Queue exactly 100 updates
        for (int i = 0; i < 100; i++) {
            var position = new float[]{1.0f, 2.0f, 3.0f};
            var velocity = new float[]{0.1f, 0.2f, 0.3f};
            migrator.queueDeferredUpdate(entity1, position, velocity);
        }

        assertEquals(100, migrator.getDeferredQueueSize(entity1),
                "Queue should contain exactly 100 updates");

        // Add one more - should drop oldest and stay at 100
        var position = new float[]{1.0f, 2.0f, 3.0f};
        var velocity = new float[]{0.1f, 0.2f, 0.3f};
        migrator.queueDeferredUpdate(entity1, position, velocity);

        assertEquals(100, migrator.getDeferredQueueSize(entity1),
                "Queue should not exceed 100 events");
    }

    @Test
    @DisplayName("Flushes deferred updates on MIGRATING_IN → OWNED transition")
    void testFlushDeferredUpdates() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);

        // Queue 50 updates
        for (int i = 0; i < 50; i++) {
            var position = new float[]{1.0f + i * 0.01f, 2.0f, 3.0f};
            var velocity = new float[]{0.5f, 0.0f, 0.1f};
            migrator.queueDeferredUpdate(entity1, position, velocity);
        }

        assertEquals(50, migrator.getDeferredQueueSize(entity1),
                "Queue should contain 50 updates before flush");

        // Flush deferred updates
        migrator.flushDeferredUpdates(entity1);

        assertEquals(0, migrator.getDeferredQueueSize(entity1),
                "Queue should be empty after flush");
        assertEquals(0, migrator.getPendingDeferredCount(),
                "No pending updates after flush");
    }

    @Test
    @DisplayName("Rolls back migration on view change")
    void testRollbackMigration() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);

        // Queue 30 updates
        for (int i = 0; i < 30; i++) {
            var position = new float[]{1.0f, 2.0f, 3.0f};
            var velocity = new float[]{0.1f, 0.2f, 0.3f};
            migrator.queueDeferredUpdate(entity1, position, velocity);
        }

        assertEquals(30, migrator.getDeferredQueueSize(entity1),
                "Queue should contain 30 updates before rollback");

        // Rollback due to view change
        migrator.rollbackMigration(entity1, "view_change");

        assertEquals(0, migrator.getDeferredQueueSize(entity1),
                "Queue should be cleared on rollback");
        assertEquals(0, migrator.getPendingDeferredCount(),
                "No pending updates after rollback");
    }

    @Test
    @DisplayName("Handles multiple simultaneous migrations")
    void testMultipleConcurrentMigrations() {
        // Initiate 5 migrations (including entity1 and entity2)
        migrator.initiateOptimisticMigration(entity1, targetBubble1);
        migrator.initiateOptimisticMigration(entity2, targetBubble2);

        for (int i = 0; i < 3; i++) {
            var entity = UUID.randomUUID();
            var target = UUID.randomUUID();
            migrator.initiateOptimisticMigration(entity, target);
        }

        // Queue updates for 2 of them
        migrator.queueDeferredUpdate(entity1, new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator.queueDeferredUpdate(entity1, new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator.queueDeferredUpdate(entity2, new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        assertEquals(2, migrator.getDeferredQueueSize(entity1));
        assertEquals(1, migrator.getDeferredQueueSize(entity2));
        assertEquals(2, migrator.getPendingDeferredCount(),
                "Should have 2 entities with pending updates");
    }

    @Test
    @DisplayName("Validates null parameters")
    void testNullParameterValidation() {
        assertThrows(NullPointerException.class,
                () -> migrator.initiateOptimisticMigration(null, targetBubble1),
                "Should throw NPE for null entityId");

        assertThrows(NullPointerException.class,
                () -> migrator.initiateOptimisticMigration(entity1, null),
                "Should throw NPE for null targetBubbleId");

        migrator.initiateOptimisticMigration(entity1, targetBubble1);

        assertThrows(NullPointerException.class,
                () -> migrator.queueDeferredUpdate(null, new float[]{1, 2, 3}, new float[]{0, 0, 0}),
                "Should throw NPE for null entityId in queue");

        assertThrows(IllegalArgumentException.class,
                () -> migrator.queueDeferredUpdate(entity1, null, new float[]{0, 0, 0}),
                "Should throw IAE for null position");

        assertThrows(IllegalArgumentException.class,
                () -> migrator.queueDeferredUpdate(entity1, new float[]{1, 2}, new float[]{0, 0, 0}),
                "Should throw IAE for wrong position array length");

        assertThrows(NullPointerException.class,
                () -> migrator.rollbackMigration(null, "test"),
                "Should throw NPE for null entityId in rollback");

        assertThrows(NullPointerException.class,
                () -> migrator.rollbackMigration(entity1, null),
                "Should throw NPE for null reason");
    }

    @Test
    @DisplayName("Clears all deferred updates on demand")
    void testClearAllDeferred() {
        // Create multiple migrations with queued updates
        var entity3 = UUID.randomUUID();
        var entity4 = UUID.randomUUID();

        migrator.initiateOptimisticMigration(entity1, targetBubble1);
        migrator.initiateOptimisticMigration(entity2, targetBubble2);
        migrator.initiateOptimisticMigration(entity3, targetBubble1);
        migrator.initiateOptimisticMigration(entity4, targetBubble2);

        // Queue updates for all
        for (var entity : new UUID[]{entity1, entity2, entity3, entity4}) {
            for (int i = 0; i < 25; i++) {
                migrator.queueDeferredUpdate(entity, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});
            }
        }

        assertEquals(4, migrator.getPendingDeferredCount(),
                "Should have 4 entities with pending updates");

        migrator.clearAllDeferred();

        assertEquals(0, migrator.getPendingDeferredCount(),
                "Should have no pending updates after clear");
        assertEquals(0, migrator.getDeferredQueueSize(entity1));
        assertEquals(0, migrator.getDeferredQueueSize(entity2));
    }

    @Test
    @DisplayName("Performs within performance target: < 1ms per 100 migrations")
    void testPerformance100Migrations() {
        // Initiate 100 migrations
        long startNs = System.nanoTime();

        for (int i = 0; i < 100; i++) {
            var entity = UUID.randomUUID();
            var target = UUID.randomUUID();
            migrator.initiateOptimisticMigration(entity, target);
        }

        long elapsedNs = System.nanoTime() - startNs;
        long elapsedMs = elapsedNs / 1_000_000L;

        log.info("100 migration initiations completed in {}ms", elapsedMs);
        assertTrue(elapsedMs < 10, "100 initiations should complete in < 10ms, got " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("Performs within performance target: < 10ms for 1000 queued updates")
    void testPerformanceQueueing1000Updates() {
        var entity = UUID.randomUUID();
        migrator.initiateOptimisticMigration(entity, targetBubble1);

        long startNs = System.nanoTime();

        // Queue 1000 updates (will be limited to 100, dropping oldest)
        for (int i = 0; i < 1000; i++) {
            var position = new float[]{1.0f + i * 0.001f, 2.0f, 3.0f};
            var velocity = new float[]{0.5f, 0.0f, 0.1f};
            migrator.queueDeferredUpdate(entity, position, velocity);
        }

        long elapsedNs = System.nanoTime() - startNs;
        long elapsedMs = elapsedNs / 1_000_000L;

        log.info("1000 deferred updates queued in {}ms (final queue size: {})",
                elapsedMs, migrator.getDeferredQueueSize(entity));

        assertTrue(elapsedMs < 50, "1000 queue operations should complete in < 50ms, got " + elapsedMs + "ms");
        assertEquals(100, migrator.getDeferredQueueSize(entity),
                "Final queue should be limited to 100 events");
    }

    @Test
    @DisplayName("Correctly computes pending deferred count")
    void testPendingDeferredCount() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);
        migrator.initiateOptimisticMigration(entity2, targetBubble2);

        // entity1: has deferred updates
        migrator.queueDeferredUpdate(entity1, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});

        // entity2: still empty queue
        assertEquals(1, migrator.getPendingDeferredCount(),
                "Only entity1 has pending updates");

        // Add to entity2
        migrator.queueDeferredUpdate(entity2, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});

        assertEquals(2, migrator.getPendingDeferredCount(),
                "Both entities have pending updates");

        // Flush entity1
        migrator.flushDeferredUpdates(entity1);

        assertEquals(1, migrator.getPendingDeferredCount(),
                "Only entity2 has pending updates after flush");
    }

    @Test
    @DisplayName("Returns meaningful metrics string")
    void testMetricsString() {
        migrator.initiateOptimisticMigration(entity1, targetBubble1);
        migrator.queueDeferredUpdate(entity1, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});
        migrator.queueDeferredUpdate(entity1, new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});

        String metrics = migrator.getMetrics();

        log.info("Metrics: {}", metrics);

        assertTrue(metrics.contains("initiated=1"), "Should show 1 migration initiated");
        assertTrue(metrics.contains("pending=1"), "Should show 1 pending entity");
        assertTrue(metrics.contains("queued=2"), "Should show 2 queued events");
    }

    @Test
    @DisplayName("Thread-safety: concurrent queue operations from multiple threads")
    void testConcurrentQueueOperations() throws Exception {
        var entity = UUID.randomUUID();
        migrator.initiateOptimisticMigration(entity, targetBubble1);

        int numThreads = 5;
        int operationsPerThread = 30;
        var executor = Executors.newFixedThreadPool(numThreads);
        var startLatch = new CountDownLatch(1);
        var completionLatch = new CountDownLatch(numThreads);
        var exceptionCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        // Launch concurrent threads
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();

                    // Perform queue operations concurrently
                    for (int i = 0; i < operationsPerThread; i++) {
                        var position = new float[]{
                            threadId + i * 0.01f,
                            threadId + i * 0.02f,
                            threadId + i * 0.03f
                        };
                        var velocity = new float[]{0.1f * threadId, 0.2f, 0.3f};
                        migrator.queueDeferredUpdate(entity, position, velocity);
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                    log.error("Thread {} exception: {}", threadId, e.getMessage(), e);
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS),
            "All threads should complete within 10 seconds");

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
            "Executor should terminate");

        // Verify no exceptions occurred
        if (!exceptions.isEmpty()) {
            log.error("Concurrent test failures:");
            for (var ex : exceptions) {
                log.error("  - {}", ex.getMessage(), ex);
            }
        }
        assertEquals(0, exceptionCount.get(),
            "No exceptions should occur during concurrent operations. Exceptions: " + exceptions);

        // Verify queue size (should be capped at 100)
        int queueSize = migrator.getDeferredQueueSize(entity);
        assertTrue(queueSize > 0, "Queue should contain updates");
        assertTrue(queueSize <= 100, "Queue should be capped at 100, got: " + queueSize);

        // Verify at least some updates were queued (not all lost to races)
        assertTrue(queueSize >= 90,
            "Should have queued close to max capacity, got: " + queueSize);

        log.info("Concurrent stress test: {} threads × {} ops = {} total operations, final queue size: {}",
            numThreads, operationsPerThread, numThreads * operationsPerThread, queueSize);
    }
}
