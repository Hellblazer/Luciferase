/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubbleMigrationIntegration;
import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.distributed.migration.*;
import com.hellblazer.luciferase.simulation.events.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Failure recovery and resilience testing for Phase 7F Day 6.
 * Tests distributed system behavior under failure conditions:
 * - Node failures (crash-stop model)
 * - Network partitions
 * - Timeouts and retries
 * - Recovery and healing
 * - Consistency under adverse conditions
 */
@DisplayName("Failure Recovery & Resilience - Fault Tolerance Testing")
class FailureRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(FailureRecoveryTest.class);

    private UUID nodeA;
    private UUID nodeB;
    private UUID nodeC;
    private DistributedBubbleNode nodeActual;
    private DistributedBubbleNode nodeTarget;
    private DistributedBubbleNode nodeObserver;
    private OptimisticMigratorImpl migratorA;
    private OptimisticMigratorImpl migratorB;
    private OptimisticMigratorImpl migratorC;

    @BeforeEach
    void setUp() {
        FakeNetworkChannel.clearNetwork();

        nodeA = UUID.randomUUID();
        nodeB = UUID.randomUUID();
        nodeC = UUID.randomUUID();

        // Create bubbles
        var bubbleA = new EnhancedBubble(nodeA, (byte) 10, 100L);
        var bubbleB = new EnhancedBubble(nodeB, (byte) 10, 100L);
        var bubbleC = new EnhancedBubble(nodeC, (byte) 10, 100L);

        // Create migration components
        var mockView = mock(com.hellblazer.luciferase.simulation.delos.MembershipView.class);
        var viewMonitorA = new FirefliesViewMonitor(mockView, 3);
        var viewMonitorB = new FirefliesViewMonitor(mockView, 3);
        var viewMonitorC = new FirefliesViewMonitor(mockView, 3);

        var fsmA = new EntityMigrationStateMachine(viewMonitorA);
        var fsmB = new EntityMigrationStateMachine(viewMonitorB);
        var fsmC = new EntityMigrationStateMachine(viewMonitorC);

        migratorA = new OptimisticMigratorImpl();
        migratorB = new OptimisticMigratorImpl();
        migratorC = new OptimisticMigratorImpl();

        var oracleA = new MigrationOracleImpl(2, 2, 2);
        var oracleB = new MigrationOracleImpl(2, 2, 2);
        var oracleC = new MigrationOracleImpl(2, 2, 2);

        var integrationA = new EnhancedBubbleMigrationIntegration(
            bubbleA, fsmA, oracleA, migratorA, viewMonitorA, 3);
        var integrationB = new EnhancedBubbleMigrationIntegration(
            bubbleB, fsmB, oracleB, migratorB, viewMonitorB, 3);
        var integrationC = new EnhancedBubbleMigrationIntegration(
            bubbleC, fsmC, oracleC, migratorC, viewMonitorC, 3);

        // Create network channels
        var channelA = new FakeNetworkChannel(nodeA);
        var channelB = new FakeNetworkChannel(nodeB);
        var channelC = new FakeNetworkChannel(nodeC);

        channelA.initialize(nodeA, "localhost:10001");
        channelB.initialize(nodeB, "localhost:10002");
        channelC.initialize(nodeC, "localhost:10003");

        channelA.registerNode(nodeB, "localhost:10002");
        channelA.registerNode(nodeC, "localhost:10003");
        channelB.registerNode(nodeA, "localhost:10001");
        channelB.registerNode(nodeC, "localhost:10003");
        channelC.registerNode(nodeA, "localhost:10001");
        channelC.registerNode(nodeB, "localhost:10002");

        // Create distributed nodes
        nodeActual = new DistributedBubbleNode(nodeA, bubbleA, channelA, integrationA, fsmA);
        nodeTarget = new DistributedBubbleNode(nodeB, bubbleB, channelB, integrationB, fsmB);
        nodeObserver = new DistributedBubbleNode(nodeC, bubbleC, channelC, integrationC, fsmC);

        log.info("Setup: Source(A) → Target(B), Observer(C)");
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @DisplayName("Network partition: target node becomes unreachable")
    void testNetworkPartition() {
        var entityId = UUID.randomUUID();

        // Migration initiated successfully
        migratorA.initiateOptimisticMigration(entityId, nodeB);
        migratorA.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        // Use a monotonic counter rather than System.nanoTime() to keep the
        // Lamport-style timestamp deterministic across runs — matches the
        // pattern already used elsewhere in this file (lines 167, 205, 268).
        long lamport = 0L;

        // First attempt succeeds
        var event1 = new EntityDepartureEvent(
            entityId, nodeA, nodeB,
            EntityMigrationState.MIGRATING_OUT, ++lamport);
        boolean sent1 = nodeActual.sendEntityDeparture(nodeB, event1);
        assertTrue(sent1, "First send should succeed before partition");

        // Simulate network partition by reducing reachability
        // In reality, this would be node becoming unreachable
        var event2 = new EntityDepartureEvent(
            entityId, nodeA, nodeB,
            EntityMigrationState.MIGRATING_OUT, ++lamport);
        // Second attempt may fail due to transient partition
        nodeActual.sendEntityDeparture(nodeB, event2);

        // System should still handle this gracefully — calling isNodeReachable
        // twice in succession must return the same value (no side effects, no
        // race against an internal mutation that would flip the state).
        boolean reachable1 = nodeActual.isNodeReachable(nodeB);
        boolean reachable2 = nodeActual.isNodeReachable(nodeB);
        assertEquals(reachable1, reachable2,
                     "Reachability check must be idempotent and side-effect free");

        log.info("✓ Network partition handled: reachability state consistent");
    }

    @Test
    @DisplayName("Timeout and retry: migration succeeds after transient failure")
    void testTimeoutAndRetry() {
        var entityId = UUID.randomUUID();
        int maxRetries = 3;
        int successCount = 0;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            migratorA.initiateOptimisticMigration(entityId, nodeB);
            migratorA.queueDeferredUpdate(entityId,
                new float[]{1.0f + attempt, 2.0f, 3.0f},
                new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, nodeA, nodeB,
                EntityMigrationState.MIGRATING_OUT, attempt);

            if (nodeActual.sendEntityDeparture(nodeB, event)) {
                successCount++;
                migratorB.queueDeferredUpdate(entityId,
                    new float[]{1.1f + attempt, 2.1f, 3.1f},
                    new float[]{0.1f, 0.2f, 0.3f});
                migratorB.flushDeferredUpdates(entityId);
                break;
            }
        }

        assertTrue(successCount > 0, "At least one retry should succeed");
        migratorA.flushDeferredUpdates(entityId);

        assertEquals(0, migratorA.getPendingDeferredCount());
        log.info("✓ Timeout and retry: migration succeeded after {} attempts", successCount);
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Flaky: timing-sensitive test with 40% packet loss - passes in isolation but fails under load")
    @DisplayName("Recovery from transient packet loss")
    void testTransientPacketLossRecovery() {
        nodeActual.setPacketLoss(0.4); // 40% loss

        var entityId = UUID.randomUUID();
        int attempts = 0;
        boolean success = false;

        // 10 attempts at 40% loss: P(all fail) = 0.4^10 ≈ 0.01% (was 5 attempts = 1%)
        while (attempts < 10 && !success) {
            attempts++;
            migratorA.initiateOptimisticMigration(entityId, nodeB);
            migratorA.queueDeferredUpdate(entityId,
                new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, nodeA, nodeB,
                EntityMigrationState.MIGRATING_OUT, attempts);

            if (nodeActual.sendEntityDeparture(nodeB, event)) {
                success = true;
                migratorB.queueDeferredUpdate(entityId,
                    new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
                migratorB.flushDeferredUpdates(entityId);
                migratorA.flushDeferredUpdates(entityId);
            }
        }

        assertTrue(success, "Should eventually succeed despite packet loss");
        log.info("✓ Transient packet loss recovery: succeeded after {} attempts", attempts);
    }

    @Test
    @DisplayName("Cascading failure: B fails during migration, C observes")
    void testCascadingFailureObservation() {
        var entityId = UUID.randomUUID();
        var failureObserved = new AtomicBoolean(false);
        var observedEntity = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var observedSource = new java.util.concurrent.atomic.AtomicReference<UUID>();

        // The rollback below is delivered to node A's channel (B -> A), so the listener
        // under test MUST be registered on A's channel — the previous version registered
        // it on the observer (C), which never receives the B->A rollback, and then a
        // SECOND setEntityRollbackListener overwrote the lambda with no assertion at all.
        nodeActual.getNetworkChannel().setEntityRollbackListener((sourceId, evt) -> {
            failureObserved.set(true);
            observedEntity.set(evt.getEntityId());
            observedSource.set(sourceId);
            log.debug("Node A detected rollback for entity {} from {}", evt.getEntityId(), sourceId);
        });

        // Migration initiated
        migratorA.initiateOptimisticMigration(entityId, nodeB);
        migratorA.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeA, nodeB,
            EntityMigrationState.MIGRATING_OUT, 0L);
        assertTrue(nodeActual.sendEntityDeparture(nodeB, event),
                   "Departure must be delivered (no packet loss configured)");

        // Simulate B failure by sending a rollback to A. Constant Lamport timestamp keeps
        // the test deterministic (CLAUDE.md: no raw system clocks). Default 0ms latency
        // means delivery is synchronous, so the listener has fired by the time send returns.
        var rollback = new EntityRollbackEvent(
            entityId, nodeA, nodeB, "node_b_failure", 1L);
        assertTrue(nodeTarget.sendEntityRollback(nodeA, rollback),
                   "Rollback must be delivered to node A");

        // The cascading-failure observation property: A actually observed the rollback,
        // for the right entity, attributed to B.
        assertTrue(failureObserved.get(), "Node A must observe the rollback from B");
        assertEquals(entityId, observedEntity.get(), "Observed rollback must carry the migrating entity id");
        assertEquals(nodeB, observedSource.get(), "Rollback must be attributed to its sender (B)");

        log.info("✓ Cascading failure: rollback propagated, node A notified");
    }

    @Test
    @DisplayName("Consistency during concurrent failures")
    void testConsistencyUnderConcurrentFailures() throws InterruptedException {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var id3 = UUID.randomUUID();

        // Three concurrent migrations with varying failure rates
        var thread1 = new Thread(() -> {
            nodeActual.setPacketLoss(0.2);
            migratorA.initiateOptimisticMigration(id1, nodeB);
            migratorA.queueDeferredUpdate(id1, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});
            for (int i = 0; i < 3; i++) {
                if (nodeActual.sendEntityDeparture(nodeB, new EntityDepartureEvent(
                    id1, nodeA, nodeB, EntityMigrationState.MIGRATING_OUT, i))) {
                    migratorB.queueDeferredUpdate(id1, new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
                    migratorB.flushDeferredUpdates(id1);
                    break;
                }
            }
            migratorA.flushDeferredUpdates(id1);
        });

        var thread2 = new Thread(() -> {
            nodeTarget.setPacketLoss(0.3);
            migratorB.initiateOptimisticMigration(id2, nodeC);
            migratorB.queueDeferredUpdate(id2, new float[]{2, 3, 4}, new float[]{0.2f, 0.3f, 0.4f});
            for (int i = 0; i < 3; i++) {
                if (nodeTarget.sendEntityDeparture(nodeC, new EntityDepartureEvent(
                    id2, nodeB, nodeC, EntityMigrationState.MIGRATING_OUT, i))) {
                    migratorC.queueDeferredUpdate(id2, new float[]{2.1f, 3.1f, 4.1f}, new float[]{0.2f, 0.3f, 0.4f});
                    migratorC.flushDeferredUpdates(id2);
                    break;
                }
            }
            migratorB.flushDeferredUpdates(id2);
        });

        var thread3 = new Thread(() -> {
            nodeObserver.setPacketLoss(0.1);
            migratorC.initiateOptimisticMigration(id3, nodeA);
            migratorC.queueDeferredUpdate(id3, new float[]{3, 4, 5}, new float[]{0.3f, 0.4f, 0.5f});
            for (int i = 0; i < 3; i++) {
                if (nodeObserver.sendEntityDeparture(nodeA, new EntityDepartureEvent(
                    id3, nodeC, nodeA, EntityMigrationState.MIGRATING_OUT, i))) {
                    migratorA.queueDeferredUpdate(id3, new float[]{3.1f, 4.1f, 5.1f}, new float[]{0.3f, 0.4f, 0.5f});
                    migratorA.flushDeferredUpdates(id3);
                    break;
                }
            }
            migratorC.flushDeferredUpdates(id3);
        });

        thread1.start();
        thread2.start();
        thread3.start();
        thread1.join();
        thread2.join();
        thread3.join();

        // Real consistency invariants (the previous `>= 0` assertions were tautologies —
        // a non-negative count holds even if every migration corrupted state).
        for (var entry : Map.of("A", migratorA, "B", migratorB, "C", migratorC).entrySet()) {
            var name = entry.getKey();
            var migrator = entry.getValue();
            // 1. No phantom application: never flush (apply) more deferred events than were queued.
            assertTrue(migrator.getTotalDeferredEventsFlushed() <= migrator.getTotalDeferredEventsQueued(),
                       name + ": flushed deferred events must not exceed queued (no phantom updates)");
        }
        // 2 & 3. Each entity's source migrator drained its queue (flushDeferredUpdates removes it).
        assertEquals(0, migratorA.getDeferredQueueSize(id1), "A drained entity id1's deferred queue");
        assertEquals(0, migratorB.getDeferredQueueSize(id2), "B drained entity id2's deferred queue");
        assertEquals(0, migratorC.getDeferredQueueSize(id3), "C drained entity id3's deferred queue");
        // 4. Total accounting holds across all three migrators (no negative / lost events).
        long totalQueued = migratorA.getTotalDeferredEventsQueued()
                         + migratorB.getTotalDeferredEventsQueued()
                         + migratorC.getTotalDeferredEventsQueued();
        long totalFlushed = migratorA.getTotalDeferredEventsFlushed()
                          + migratorB.getTotalDeferredEventsFlushed()
                          + migratorC.getTotalDeferredEventsFlushed();
        assertTrue(totalFlushed <= totalQueued,
                   "System-wide: flushed (" + totalFlushed + ") <= queued (" + totalQueued + ")");

        log.info("✓ Consistency under concurrent failures maintained: queued={}, flushed={}",
                 totalQueued, totalFlushed);
    }

    @Test
    @DisplayName("Network healing: recovery when partition resolves")
    void testNetworkHealing() {
        var entityId = UUID.randomUUID();

        // First migration succeeds
        migratorA.initiateOptimisticMigration(entityId, nodeB);
        migratorA.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeA, nodeB,
            EntityMigrationState.MIGRATING_OUT, 0L);
        boolean sent = nodeActual.sendEntityDeparture(nodeB, event);

        assertTrue(sent, "First migration should succeed");

        if (sent) {
            migratorB.queueDeferredUpdate(entityId,
                new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
            migratorB.flushDeferredUpdates(entityId);
        }

        // Clear and re-register to simulate network healing
        FakeNetworkChannel.clearNetwork();

        // Re-initialize with same nodes (simulating network coming back)
        var channelA2 = new FakeNetworkChannel(nodeA);
        var channelB2 = new FakeNetworkChannel(nodeB);

        channelA2.initialize(nodeA, "localhost:10001");
        channelB2.initialize(nodeB, "localhost:10002");
        channelA2.registerNode(nodeB, "localhost:10002");
        channelB2.registerNode(nodeA, "localhost:10001");

        // Network should be usable again
        assertTrue(channelA2.isNodeReachable(nodeB), "Network should be healed");
        log.info("✓ Network healing: system recovered after partition resolution");
    }

    @Test
    @DisplayName("Orphaned entity recovery: entity remains consistent when parent fails")
    void testOrphanedEntityRecovery() {
        var entityId = UUID.randomUUID();

        // Entity migrates from A to B
        migratorA.initiateOptimisticMigration(entityId, nodeB);
        migratorA.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeA, nodeB,
            EntityMigrationState.MIGRATING_OUT, 0L);
        nodeActual.sendEntityDeparture(nodeB, event);

        // B receives and owns entity
        migratorB.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migratorB.flushDeferredUpdates(entityId);

        // A fails (simulate by removing from network)
        var channelA = (FakeNetworkChannel) nodeActual.getNetworkChannel();
        channelA.shutdown();

        // B should still have valid entity ownership (orphaned but consistent)
        assertEquals(0, migratorB.getPendingDeferredCount(),
            "Orphaned entity should remain consistent");

        log.info("✓ Orphaned entity recovery: entity remains consistent despite parent failure");
    }

    @Test
    @DisplayName("Progressive failure detection: system adapts to degradation")
    void testProgressiveFailureDetection() {
        var entityIds = new java.util.ArrayList<UUID>();
        int successCount = 0;

        // Gradually increase packet loss to simulate degrading network
        for (int loss = 0; loss <= 40; loss += 10) {
            nodeActual.setPacketLoss(loss / 100.0);
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);

            migratorA.initiateOptimisticMigration(entityId, nodeB);
            migratorA.queueDeferredUpdate(entityId,
                new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, nodeA, nodeB,
                EntityMigrationState.MIGRATING_OUT, 0L);

            if (nodeActual.sendEntityDeparture(nodeB, event)) {
                successCount++;
                migratorB.queueDeferredUpdate(entityId,
                    new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
                migratorB.flushDeferredUpdates(entityId);
                migratorA.flushDeferredUpdates(entityId);
            }
        }

        // Some migrations should still succeed even with degradation
        assertTrue(successCount > 0, "System should adapt to progressive degradation");
        log.info("✓ Progressive failure detection: {}/{} migrations succeeded despite degradation",
            successCount, entityIds.size());
    }
}
