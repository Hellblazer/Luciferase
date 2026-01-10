/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.causality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationCoordinatorTest - Test FSM/2PC Bridge Integration (Phase 7D Day 2)
 *
 * Tests the bridge between EntityMigrationStateMachine and CrossProcessMigration 2PC protocol.
 * MigrationCoordinator observes FSM state transitions and coordinates with 2PC operations.
 *
 * Test Coverage:
 * 1. Outbound migration flow (OWNED → MIGRATING_OUT → DEPARTED)
 * 2. Inbound migration flow (GHOST → MIGRATING_IN → OWNED)
 * 3. Commit operations (source and target)
 * 4. View change rollback coordination
 * 5. Concurrent migrations
 * 6. Migration abort handling
 * 7. Metrics tracking
 * 8. Failed prepare handling
 *
 * Architecture:
 * - MigrationCoordinator implements MigrationStateListener
 * - Observes EntityMigrationStateMachine state transitions
 * - Maps transitions to CrossProcessMigration 2PC operations
 * - Thread-safe with <1ms listener execution time
 *
 * @author hal.hildebrand
 */
class MigrationCoordinatorTest {

    private EntityMigrationStateMachine fsm;
    private MockCrossProcessMigration crossProcessMigration;
    private MigrationCoordinator coordinator;
    private UUID localBubbleId;
    private UUID remoteBubbleId;

    /**
     * Mock implementation of CrossProcessMigration for testing.
     * Tracks method calls and provides synchronous responses.
     */
    public static class MockCrossProcessMigration {
        public final ConcurrentHashMap<Object, PrepareRequest> prepareRequests = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<Object, CommitRequest> commitRequests = new ConcurrentHashMap<>();
        public final ConcurrentHashMap<Object, AbortRequest> abortRequests = new ConcurrentHashMap<>();
        public final AtomicInteger totalPrepares = new AtomicInteger(0);
        public final AtomicInteger totalCommits = new AtomicInteger(0);
        public final AtomicInteger totalAborts = new AtomicInteger(0);

        public void sendPrepareRequest(Object entityId, UUID sourceBubble, UUID targetBubble) {
            totalPrepares.incrementAndGet();
            prepareRequests.put(entityId, new PrepareRequest(entityId, sourceBubble, targetBubble));
        }

        public void sendCommitRequest(Object entityId, UUID targetBubble) {
            totalCommits.incrementAndGet();
            commitRequests.put(entityId, new CommitRequest(entityId, targetBubble));
        }

        public void sendAbortRequest(Object entityId, UUID targetBubble) {
            totalAborts.incrementAndGet();
            abortRequests.put(entityId, new AbortRequest(entityId, targetBubble));
        }

        public void reset() {
            prepareRequests.clear();
            commitRequests.clear();
            abortRequests.clear();
            totalPrepares.set(0);
            totalCommits.set(0);
            totalAborts.set(0);
        }

        public record PrepareRequest(Object entityId, UUID sourceBubble, UUID targetBubble) {}
        public record CommitRequest(Object entityId, UUID targetBubble) {}
        public record AbortRequest(Object entityId, UUID targetBubble) {}
    }

    /**
     * Mock FirefliesViewMonitor for testing.
     */
    static class MockFirefliesViewMonitor extends FirefliesViewMonitor {
        private volatile boolean viewStable = true;

        public MockFirefliesViewMonitor() {
            super(new com.hellblazer.luciferase.simulation.delos.MembershipView<Object>() {
                @Override
                public Stream<Object> getMembers() {
                    return Stream.empty();
                }

                @Override
                public void addListener(java.util.function.Consumer<ViewChange<Object>> listener) {
                }
            }, 10);
        }

        @Override
        public boolean isViewStable() {
            return viewStable;
        }

        void setViewStable(boolean stable) {
            this.viewStable = stable;
        }
    }

    @BeforeEach
    void setUp() {
        var viewMonitor = new MockFirefliesViewMonitor();
        fsm = new EntityMigrationStateMachine(viewMonitor);
        crossProcessMigration = new MockCrossProcessMigration();
        localBubbleId = UUID.randomUUID();
        remoteBubbleId = UUID.randomUUID();
        coordinator = new MigrationCoordinator(fsm, crossProcessMigration, localBubbleId);

        // Register coordinator as listener
        fsm.addListener(coordinator);
    }

    /**
     * Test 1: Outbound migration flow (OWNED → MIGRATING_OUT).
     * Verify PREPARE request sent to target bubble.
     */
    @Test
    @Timeout(5)
    void testOutboundMigrationFlow() {
        var entityId = "entity-1";

        // Initialize entity as OWNED
        fsm.initializeOwned(entityId);
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));

        // Set target bubble for migration
        coordinator.setTargetBubble(entityId, remoteBubbleId);

        // Transition to MIGRATING_OUT
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertTrue(result.success, "Transition should succeed");

        // Verify PrepareRequest sent
        assertEquals(1, crossProcessMigration.totalPrepares.get(),
            "PrepareRequest should be sent");
        var prepareReq = crossProcessMigration.prepareRequests.get(entityId);
        assertNotNull(prepareReq, "PrepareRequest should exist for entity");
        assertEquals(entityId, prepareReq.entityId);
        assertEquals(localBubbleId, prepareReq.sourceBubble);
        assertEquals(remoteBubbleId, prepareReq.targetBubble);
    }

    /**
     * Test 2: Inbound migration flow (GHOST → MIGRATING_IN).
     * Verify PREPARED reply sent.
     */
    @Test
    @Timeout(5)
    void testInboundMigrationFlow() {
        var entityId = "entity-2";

        // Initialize entity as GHOST (remote entity)
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));

        // Receive PrepareRequest from remote bubble
        coordinator.handlePrepareRequest(entityId, remoteBubbleId);

        // Transition to MIGRATING_IN
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);
        assertTrue(result.success, "Transition should succeed");

        // Verify entity is in MIGRATING_IN state
        assertEquals(EntityMigrationState.MIGRATING_IN, fsm.getState(entityId));

        // Verify coordinator accepted the prepare
        assertTrue(coordinator.isPrepared(entityId),
            "Coordinator should have accepted prepare");
    }

    /**
     * Test 3: Commit outbound migration (MIGRATING_OUT → DEPARTED).
     * Verify CommitRequest sent to target.
     */
    @Test
    @Timeout(5)
    void testCommitOutboundMigration() {
        var entityId = "entity-3";

        // Setup: Entity in MIGRATING_OUT state
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, remoteBubbleId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Simulate PREPARED received from target
        coordinator.onPrepareReply(entityId, true);

        // Transition to DEPARTED
        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);
        assertTrue(result.success, "Transition should succeed");

        // Verify CommitRequest sent
        assertEquals(1, crossProcessMigration.totalCommits.get(),
            "CommitRequest should be sent");
        var commitReq = crossProcessMigration.commitRequests.get(entityId);
        assertNotNull(commitReq, "CommitRequest should exist");
        assertEquals(entityId, commitReq.entityId);
        assertEquals(remoteBubbleId, commitReq.targetBubble);
    }

    /**
     * Test 4: Commit inbound migration (MIGRATING_IN → OWNED).
     * Verify entity accepted locally.
     */
    @Test
    @Timeout(5)
    void testCommitInboundMigration() {
        var entityId = "entity-4";

        // Setup: Entity in MIGRATING_IN state
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        coordinator.handlePrepareRequest(entityId, remoteBubbleId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // Receive CommitRequest from source
        coordinator.handleCommitRequest(entityId, remoteBubbleId);

        // Transition to OWNED
        var result = fsm.transition(entityId, EntityMigrationState.OWNED);
        assertTrue(result.success, "Transition should succeed");

        // Verify entity is OWNED
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));

        // Verify coordinator accepted the entity
        assertTrue(coordinator.isOwned(entityId),
            "Coordinator should show entity as owned");
    }

    /**
     * Test 5: View change abort outbound (MIGRATING_OUT → ROLLBACK_OWNED).
     * Verify AbortRequest sent to target.
     */
    @Test
    @Timeout(5)
    void testViewChangeAbortOutbound() {
        var entityId = "entity-5";

        // Setup: Entity in MIGRATING_OUT state
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, remoteBubbleId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Trigger view change
        fsm.onViewChange();

        // Verify FSM rolled back to ROLLBACK_OWNED
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId),
            "Entity should be rolled back to ROLLBACK_OWNED");

        // Verify AbortRequest sent
        assertEquals(1, crossProcessMigration.totalAborts.get(),
            "AbortRequest should be sent");
        var abortReq = crossProcessMigration.abortRequests.get(entityId);
        assertNotNull(abortReq, "AbortRequest should exist");
        assertEquals(entityId, abortReq.entityId);
        assertEquals(remoteBubbleId, abortReq.targetBubble);

        // Verify metrics updated
        assertEquals(1, coordinator.getTotalViewChangeAborts(),
            "View change abort count should increment");
    }

    /**
     * Test 6: View change abort inbound (MIGRATING_IN → GHOST).
     * Verify no CommitRequest accepted.
     */
    @Test
    @Timeout(5)
    void testViewChangeAbortInbound() {
        var entityId = "entity-6";

        // Setup: Entity in MIGRATING_IN state
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        coordinator.handlePrepareRequest(entityId, remoteBubbleId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // Trigger view change
        fsm.onViewChange();

        // Verify FSM converted to GHOST
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId),
            "Entity should become GHOST");

        // Verify coordinator rejects any subsequent commit
        coordinator.handleCommitRequest(entityId, remoteBubbleId);
        assertNotEquals(EntityMigrationState.OWNED, fsm.getState(entityId),
            "Entity should not transition to OWNED after view change");
    }

    /**
     * Test 7: Concurrent migrations (multiple entities).
     * Verify all migrations handled correctly without interference.
     */
    @Test
    @Timeout(5)
    void testConcurrentMigrations() throws InterruptedException {
        var entity1 = "entity-7a";
        var entity2 = "entity-7b";
        var entity3 = "entity-7c";

        var latch = new CountDownLatch(3);

        // Setup: Initialize 3 entities as OWNED
        fsm.initializeOwned(entity1);
        fsm.initializeOwned(entity2);
        fsm.initializeOwned(entity3);

        coordinator.setTargetBubble(entity1, remoteBubbleId);
        coordinator.setTargetBubble(entity2, remoteBubbleId);
        coordinator.setTargetBubble(entity3, remoteBubbleId);

        // Migrate all 3 entities concurrently
        var t1 = new Thread(() -> {
            fsm.transition(entity1, EntityMigrationState.MIGRATING_OUT);
            latch.countDown();
        });

        var t2 = new Thread(() -> {
            fsm.transition(entity2, EntityMigrationState.MIGRATING_OUT);
            latch.countDown();
        });

        var t3 = new Thread(() -> {
            fsm.transition(entity3, EntityMigrationState.MIGRATING_OUT);
            latch.countDown();
        });

        t1.start();
        t2.start();
        t3.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS),
            "All migrations should complete");

        // Verify all 3 entities transitioned
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entity1));
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entity2));
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entity3));

        // Verify 3 PrepareRequests sent
        assertEquals(3, crossProcessMigration.totalPrepares.get(),
            "Should have sent 3 PrepareRequests");

        // Verify metrics track all 3
        assertEquals(3, coordinator.getTotalPrepares(),
            "Coordinator should track 3 prepares");
    }

    /**
     * Test 8: Migration abort (reject prepare).
     * Verify AbortRequest sent when prepare rejected.
     */
    @Test
    @Timeout(5)
    void testMigrationAbort() {
        var entityId = "entity-8";

        // Setup: Entity in MIGRATING_OUT state
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, remoteBubbleId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Simulate ABORT response from target (prepare rejected)
        coordinator.onPrepareReply(entityId, false);

        // Transition to ROLLBACK_OWNED
        var result = fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED);
        assertTrue(result.success, "Transition should succeed");

        // Verify AbortRequest sent
        assertEquals(1, crossProcessMigration.totalAborts.get(),
            "AbortRequest should be sent");

        // Verify entity rolled back
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));

        // Verify metrics updated
        assertEquals(1, coordinator.getTotalAborts(),
            "Abort count should increment");
    }

    /**
     * Test 9: Metrics tracking.
     * Verify all metrics correctly tracked.
     */
    @Test
    @Timeout(5)
    void testMetricsTracking() {
        var entity1 = "entity-9a";
        var entity2 = "entity-9b";
        var entity3 = "entity-9c";

        // Scenario 1: Successful migration
        fsm.initializeOwned(entity1);
        coordinator.setTargetBubble(entity1, remoteBubbleId);
        fsm.transition(entity1, EntityMigrationState.MIGRATING_OUT);
        coordinator.onPrepareReply(entity1, true);
        fsm.transition(entity1, EntityMigrationState.DEPARTED);

        // Scenario 2: Aborted migration
        fsm.initializeOwned(entity2);
        coordinator.setTargetBubble(entity2, remoteBubbleId);
        fsm.transition(entity2, EntityMigrationState.MIGRATING_OUT);
        coordinator.onPrepareReply(entity2, false);
        fsm.transition(entity2, EntityMigrationState.ROLLBACK_OWNED);

        // Scenario 3: View change abort
        fsm.initializeOwned(entity3);
        coordinator.setTargetBubble(entity3, remoteBubbleId);
        fsm.transition(entity3, EntityMigrationState.MIGRATING_OUT);
        fsm.onViewChange();

        // Verify metrics
        assertEquals(3, coordinator.getTotalPrepares(),
            "Should have 3 prepare attempts");
        assertEquals(1, coordinator.getTotalCommits(),
            "Should have 1 commit");
        assertEquals(2, coordinator.getTotalAborts(),
            "Should have 2 aborts");
        assertEquals(1, coordinator.getTotalViewChangeAborts(),
            "Should have 1 view change abort");
    }

    /**
     * Test 10: Rejected prepare (entity not in GHOST state).
     * Verify ABORT reply sent when prepare received for non-GHOST entity.
     */
    @Test
    @Timeout(5)
    void testRejectedPrepare() {
        var entityId = "entity-10";

        // Setup: Entity in OWNED state (not GHOST)
        fsm.initializeOwned(entityId);
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));

        // Receive PrepareRequest (should be rejected since not GHOST)
        coordinator.handlePrepareRequest(entityId, remoteBubbleId);

        // Verify entity did NOT transition to MIGRATING_IN
        assertNotEquals(EntityMigrationState.MIGRATING_IN, fsm.getState(entityId),
            "Entity should not transition to MIGRATING_IN");

        // Verify ABORT reply sent (implicit in not accepting prepare)
        assertFalse(coordinator.isPrepared(entityId),
            "Prepare should be rejected");
    }
}
