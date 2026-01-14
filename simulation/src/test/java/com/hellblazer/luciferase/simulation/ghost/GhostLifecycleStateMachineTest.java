/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostLifecycleStateMachine - Layer 1 causality component for ghost state transitions.
 * <p>
 * GhostLifecycleStateMachine manages ghost lifecycle:
 * - State transitions: CREATED → ACTIVE → STALE → EXPIRED
 * - TTL management (configurable, default 500ms)
 * - Staleness detection (configurable threshold)
 * - Thread-safe concurrent operations
 * - Clock injection for deterministic testing
 * <p>
 * Test coverage:
 * - State transitions and lifecycle
 * - TTL expiration
 * - Staleness detection
 * - Clock injection
 * - Thread safety
 * - Metrics callbacks
 *
 * @author hal.hildebrand
 */
class GhostLifecycleStateMachineTest {

    private GhostLifecycleStateMachine lifecycle;
    private TestClock testClock;

    private static class TestClock implements Clock {
        private long currentTimeMillis = 0L;
        private long currentNanoTime = 0L;

        public void setMillis(long millis) {
            this.currentTimeMillis = millis;
            this.currentNanoTime = millis * 1_000_000;
        }

        public void advance(long deltaMillis) {
            setMillis(currentTimeMillis + deltaMillis);
        }

        @Override
        public long currentTimeMillis() {
            return currentTimeMillis;
        }

        @Override
        public long nanoTime() {
            return currentNanoTime;
        }
    }

    @BeforeEach
    void setUp() {
        lifecycle = new GhostLifecycleStateMachine();
        testClock = new TestClock();
        testClock.setMillis(1000L);
        lifecycle.setClock(testClock);
    }

    @Test
    void testInitialization() {
        assertNotNull(lifecycle, "Lifecycle state machine should initialize");
    }

    @Test
    void testCreateGhost() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();
        var timestamp = 1000L;

        lifecycle.onCreate(entityId, sourceBubbleId, timestamp);

        var state = lifecycle.getState(entityId);
        assertEquals(GhostLifecycleStateMachine.State.CREATED, state,
                    "New ghost should be in CREATED state");
    }

    @Test
    void testCreatedToActiveTransition() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        // Create ghost
        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);
        assertEquals(GhostLifecycleStateMachine.State.CREATED, lifecycle.getState(entityId));

        // First update transitions to ACTIVE
        lifecycle.onUpdate(entityId, 1100L);
        assertEquals(GhostLifecycleStateMachine.State.ACTIVE, lifecycle.getState(entityId),
                    "First update should transition ghost to ACTIVE");
    }

    @Test
    void testActiveToStaleTransition() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);
        lifecycle.onUpdate(entityId, 1000L); // CREATED → ACTIVE

        // Advance time beyond staleness threshold (500ms default, same as TTL)
        testClock.setMillis(1501L);

        assertTrue(lifecycle.isStale(entityId, testClock.currentTimeMillis()),
                  "Ghost should be stale after 501ms (threshold 500ms)");
    }

    @Test
    void testActiveToExpiredTransition() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);
        lifecycle.onUpdate(entityId, 1000L); // CREATED → ACTIVE

        // Advance time beyond TTL (500ms default)
        testClock.setMillis(1600L);

        assertTrue(lifecycle.isExpired(entityId, testClock.currentTimeMillis()),
                  "Ghost should be expired after 600ms (TTL 500ms)");
    }

    @Test
    void testFindExpiredGhosts() {
        var entityId1 = "entity1";
        var entityId2 = "entity2";
        var entityId3 = "entity3";
        var sourceBubbleId = UUID.randomUUID();

        // Create ghosts at different times
        lifecycle.onCreate(entityId1, sourceBubbleId, 1000L);
        lifecycle.onCreate(entityId2, sourceBubbleId, 1200L);
        lifecycle.onCreate(entityId3, sourceBubbleId, 1400L);

        lifecycle.onUpdate(entityId1, 1000L);
        lifecycle.onUpdate(entityId2, 1200L);
        lifecycle.onUpdate(entityId3, 1400L);

        // Advance time to expire entity1 and entity2 (TTL 500ms)
        testClock.setMillis(1800L); // entity1 expired (1800 - 1000 = 800ms > 500ms)
                                    // entity2 expired (1800 - 1200 = 600ms > 500ms)
                                    // entity3 not expired (1800 - 1400 = 400ms < 500ms)

        var expired = lifecycle.findExpired(testClock.currentTimeMillis());

        assertEquals(2, expired.size(), "Should find 2 expired ghosts");
        assertTrue(expired.contains(entityId1), "Entity1 should be expired");
        assertTrue(expired.contains(entityId2), "Entity2 should be expired");
        assertFalse(expired.contains(entityId3), "Entity3 should NOT be expired");
    }

    @Test
    void testExpireStaleGhosts() {
        var entityId1 = "entity1";
        var entityId2 = "entity2";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId1, sourceBubbleId, 1000L);
        lifecycle.onCreate(entityId2, sourceBubbleId, 1200L);

        lifecycle.onUpdate(entityId1, 1000L);
        lifecycle.onUpdate(entityId2, 1200L);

        // Advance time to expire entity1 only
        testClock.setMillis(1600L);

        var expiredCount = lifecycle.expireStaleGhosts(testClock.currentTimeMillis());

        assertEquals(1, expiredCount, "Should expire 1 ghost");
        assertNull(lifecycle.getState(entityId1), "Entity1 should be removed");
        assertNotNull(lifecycle.getState(entityId2), "Entity2 should still exist");
    }

    @Test
    void testRemoveGhost() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);
        assertNotNull(lifecycle.getState(entityId), "Ghost should exist");

        lifecycle.remove(entityId);
        assertNull(lifecycle.getState(entityId), "Ghost should be removed");
    }

    @Test
    void testCustomTTL() {
        var customLifecycle = new GhostLifecycleStateMachine(1000L, 600L); // TTL=1000ms, staleness=600ms
        customLifecycle.setClock(testClock);

        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        customLifecycle.onCreate(entityId, sourceBubbleId, 1000L);
        customLifecycle.onUpdate(entityId, 1000L);

        // After 700ms: stale but not expired
        testClock.setMillis(1700L);
        assertTrue(customLifecycle.isStale(entityId, testClock.currentTimeMillis()),
                  "Ghost should be stale after 700ms (threshold 600ms)");
        assertFalse(customLifecycle.isExpired(entityId, testClock.currentTimeMillis()),
                   "Ghost should NOT be expired after 700ms (TTL 1000ms)");

        // After 1100ms: expired
        testClock.setMillis(2100L);
        assertTrue(customLifecycle.isExpired(entityId, testClock.currentTimeMillis()),
                  "Ghost should be expired after 1100ms (TTL 1000ms)");
    }

    @Test
    void testConcurrentOperations() throws Exception {
        int threadCount = 10;
        int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var sourceBubbleId = UUID.randomUUID();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var entityId = "entity-" + threadId + "-" + i;
                        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);
                        lifecycle.onUpdate(entityId, 1100L);
                        lifecycle.getState(entityId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        // Verify all operations completed without exceptions
        // Total expected: threadCount * operationsPerThread
        var expired = lifecycle.findExpired(Long.MAX_VALUE);
        assertEquals(threadCount * operationsPerThread, expired.size(),
                    "All entities should be tracked");
    }

    @Test
    void testMetricsCallback() {
        var metricsCallback = new TestMetricsCallback();
        lifecycle.setMetrics(metricsCallback);

        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);
        assertEquals(1, metricsCallback.createCount.get(), "onCreate should trigger metrics");

        lifecycle.onUpdate(entityId, 1100L);
        assertEquals(1, metricsCallback.updateCount.get(), "onUpdate should trigger metrics");

        testClock.setMillis(1601L); // 1601 - 1100 = 501ms > 500ms TTL
        lifecycle.expireStaleGhosts(testClock.currentTimeMillis());
        assertEquals(1, metricsCallback.expireCount.get(), "expireStaleGhosts should trigger metrics");
    }

    @Test
    void testGetLifecycleState() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId, sourceBubbleId, 1000L);

        var lifecycleState = lifecycle.getLifecycleState(entityId);
        assertNotNull(lifecycleState, "Lifecycle state should exist");
        assertEquals(entityId, lifecycleState.entityId());
        assertEquals(GhostLifecycleStateMachine.State.CREATED, lifecycleState.state());
        assertEquals(1000L, lifecycleState.createdAt());
        assertEquals(1000L, lifecycleState.lastUpdateAt());
        assertEquals(sourceBubbleId, lifecycleState.sourceBubbleId());
    }

    @Test
    void testIsStaleReturnsFalseForNonExistentGhost() {
        assertFalse(lifecycle.isStale("nonexistent", 1000L),
                   "isStale should return false for nonexistent ghost");
    }

    @Test
    void testIsExpiredReturnsFalseForNonExistentGhost() {
        assertFalse(lifecycle.isExpired("nonexistent", 1000L),
                   "isExpired should return false for nonexistent ghost");
    }

    @Test
    void testClockSkewHandling() {
        var entityId = "entity1";
        var sourceBubbleId = UUID.randomUUID();

        lifecycle.onCreate(entityId, sourceBubbleId, 2000L);
        lifecycle.onUpdate(entityId, 2000L);

        // Simulate clock going backward
        testClock.setMillis(1500L);

        assertFalse(lifecycle.isStale(entityId, testClock.currentTimeMillis()),
                   "Should handle negative time delta gracefully (not stale)");
        assertFalse(lifecycle.isExpired(entityId, testClock.currentTimeMillis()),
                   "Should handle negative time delta gracefully (not expired)");
    }

    // Helper class for metrics testing
    private static class TestMetricsCallback implements GhostLifecycleStateMachine.GhostLifecycleMetrics {
        final AtomicInteger createCount = new AtomicInteger(0);
        final AtomicInteger updateCount = new AtomicInteger(0);
        final AtomicInteger expireCount = new AtomicInteger(0);

        @Override
        public void onGhostCreated(String entityId) {
            createCount.incrementAndGet();
        }

        @Override
        public void onGhostUpdated(String entityId) {
            updateCount.incrementAndGet();
        }

        @Override
        public void onGhostExpired(String entityId) {
            expireCount.incrementAndGet();
        }
    }
}
