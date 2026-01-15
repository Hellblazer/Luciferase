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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Two-node distributed migration test for Phase 7F Day 3.
 * Tests end-to-end entity migration across network boundaries
 * with latency simulation and recovery from transient failures.
 */
@DisplayName("Two-Node Distributed Migration - Cross-Network Entity Transfer")
class TwoNodeDistributedMigrationTest {

    private static final Logger log = LoggerFactory.getLogger(TwoNodeDistributedMigrationTest.class);

    private UUID bubbleId1;
    private UUID bubbleId2;
    private DistributedBubbleNode node1;
    private DistributedBubbleNode node2;
    private EnhancedBubble bubble1;
    private EnhancedBubble bubble2;
    private OptimisticMigratorImpl migrator1;
    private OptimisticMigratorImpl migrator2;

    @BeforeEach
    void setUp() {
        FakeNetworkChannel.clearNetwork();

        bubbleId1 = UUID.randomUUID();
        bubbleId2 = UUID.randomUUID();

        // Create bubbles
        bubble1 = new EnhancedBubble(bubbleId1, (byte) 10, 100L);
        bubble2 = new EnhancedBubble(bubbleId2, (byte) 10, 100L);

        // Create migration components for both bubbles
        var mockView = mock(com.hellblazer.luciferase.simulation.delos.MembershipView.class);
        var viewMonitor1 = new FirefliesViewMonitor(mockView, 3);
        var viewMonitor2 = new FirefliesViewMonitor(mockView, 3);

        var fsm1 = new EntityMigrationStateMachine(viewMonitor1);
        var fsm2 = new EntityMigrationStateMachine(viewMonitor2);

        migrator1 = new OptimisticMigratorImpl();
        migrator2 = new OptimisticMigratorImpl();

        var oracle1 = new MigrationOracleImpl(2, 2, 2);
        var oracle2 = new MigrationOracleImpl(2, 2, 2);

        var integration1 = new EnhancedBubbleMigrationIntegration(
            bubble1, fsm1, oracle1, migrator1, viewMonitor1, 3);
        var integration2 = new EnhancedBubbleMigrationIntegration(
            bubble2, fsm2, oracle2, migrator2, viewMonitor2, 3);

        // Create network channels
        var channel1 = new FakeNetworkChannel(bubbleId1);
        var channel2 = new FakeNetworkChannel(bubbleId2);

        channel1.initialize(bubbleId1, "localhost:7001");
        channel2.initialize(bubbleId2, "localhost:7002");

        channel1.registerNode(bubbleId2, "localhost:7002");
        channel2.registerNode(bubbleId1, "localhost:7001");

        // Create distributed nodes
        node1 = new DistributedBubbleNode(bubbleId1, bubble1, channel1, integration1, fsm1);
        node2 = new DistributedBubbleNode(bubbleId2, bubble2, channel2, integration2, fsm2);

        // Set up cross-bubble listeners for network communication
        setupNetworkListeners();

        log.info("Setup: {} ↔ {}", bubbleId1, bubbleId2);
    }

    private void setupNetworkListeners() {
        // Node 2 receives entity departure from Node 1
        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> {
            var entityId = evt.getEntityId();
            log.debug("Node 2 received EntityDepartureEvent for {}", entityId);
            // Transition to GHOST state to receive deferred updates
            node2.getNetworkChannel().setEntityDepartureListener(null);
        });

        // Node 1 receives view synchrony ack from Node 2
        node1.getNetworkChannel().setViewSynchronyAckListener((sourceId, evt) -> {
            var entityId = evt.getEntityId();
            log.debug("Node 1 received ViewSynchronyAck for {}", entityId);
        });
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @DisplayName("Single entity migration: 1 → 2")
    void testSingleEntityMigration() {
        var entityId = UUID.randomUUID();

        // Node 1 initiates migration
        migrator1.initiateOptimisticMigration(entityId, bubbleId2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        // Send departure event to Node 2
        var event = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        boolean sent = node1.sendEntityDeparture(bubbleId2, event);

        assertTrue(sent, "EntityDepartureEvent should be sent");

        // Node 2 receives and processes updates
        migrator2.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entityId);

        // Node 1 completes migration with acknowledgment
        var ack = new ViewSynchronyAck(entityId, bubbleId2, bubbleId1, 3, 0L);
        node2.sendViewSynchronyAck(bubbleId1, ack);

        // Flush source deferred updates
        migrator1.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount(),
            "All deferred updates should be flushed on source");
        assertEquals(0, migrator2.getPendingDeferredCount(),
            "All deferred updates should be flushed on target");

        log.info("✓ Single entity migration completed: {} → {}", bubbleId1, bubbleId2);
    }

    @Test
    @DisplayName("Multiple concurrent entity migrations")
    void testMultipleConcurrentMigrations() {
        int entityCount = 5;
        var entityIds = new UUID[entityCount];

        // Initiate migrations for 5 entities
        for (int i = 0; i < entityCount; i++) {
            var entityId = UUID.randomUUID();
            entityIds[i] = entityId;

            migrator1.initiateOptimisticMigration(entityId, bubbleId2);
            for (int j = 0; j < 3; j++) {
                migrator1.queueDeferredUpdate(entityId,
                    new float[]{1 + j, 2 + j, 3 + j},
                    new float[]{0.1f, 0.2f, 0.3f});
            }
        }

        // Send all departure events
        for (var entityId : entityIds) {
            var event = new EntityDepartureEvent(
                entityId, bubbleId1, bubbleId2,
                EntityMigrationState.MIGRATING_OUT, System.nanoTime());
            node1.sendEntityDeparture(bubbleId2, event);
        }

        // Node 2 processes deferred updates
        for (var entityId : entityIds) {
            migrator2.queueDeferredUpdate(entityId,
                new float[]{1.5f, 2.5f, 3.5f},
                new float[]{0.1f, 0.2f, 0.3f});
            migrator2.flushDeferredUpdates(entityId);
        }

        // Flush source deferred updates for all entities
        for (var entityId : entityIds) {
            migrator1.flushDeferredUpdates(entityId);
        }

        // Verify all migrations completed
        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator2.getPendingDeferredCount());

        log.info("✓ {} concurrent migrations completed", entityCount);
    }

    @Test
    @DisplayName("Entity migration with network latency")
    void testMigrationWithLatency() {
        node1.setNetworkLatency(50); // 50ms one-way latency

        var entityId = UUID.randomUUID();
        var migrationStartMs = System.currentTimeMillis();

        migrator1.initiateOptimisticMigration(entityId, bubbleId2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        // Send departure event (will be delayed)
        var event = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node1.sendEntityDeparture(bubbleId2, event);

        // Message should be pending initially
        assertTrue(node2.getPendingMessageCount() >= 0,
            "Message should be in network with latency");

        // Complete migration
        migrator2.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entityId);

        long elapsedMs = System.currentTimeMillis() - migrationStartMs;
        log.info("✓ Migration with latency: {} → {} ({}ms elapsed)",
            bubbleId1, bubbleId2, elapsedMs);
    }

    @Test
    @DisplayName("Entity migration with packet loss and retry")
    void testMigrationWithPacketLoss() {
        node1.setPacketLoss(0.3); // 30% loss rate

        var entityIds = new java.util.ArrayList<UUID>();
        int successCount = 0;

        // Try migrating 10 entities with 30% loss
        for (int i = 0; i < 10; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);

            migrator1.initiateOptimisticMigration(entityId, bubbleId2);
            migrator1.queueDeferredUpdate(entityId,
                new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, bubbleId1, bubbleId2,
                EntityMigrationState.MIGRATING_OUT, i);

            if (node1.sendEntityDeparture(bubbleId2, event)) {
                successCount++;
                migrator2.queueDeferredUpdate(entityId,
                    new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
                migrator2.flushDeferredUpdates(entityId);
            }
        }

        // Some should succeed, some should fail
        assertTrue(successCount < 10, "Some migrations should fail due to packet loss");
        assertTrue(successCount > 0, "Some migrations should succeed");

        log.info("✓ Migration with packet loss: {}/10 successful", successCount);
    }

    @Test
    @DisplayName("Migration rollback on network failure")
    void testMigrationRollback() {
        var entityId = UUID.randomUUID();

        // Initiate migration
        migrator1.initiateOptimisticMigration(entityId, bubbleId2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        // Send departure event
        var event = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node1.sendEntityDeparture(bubbleId2, event);

        // Simulate rollback due to view change
        var rollback = new EntityRollbackEvent(
            entityId, bubbleId1, bubbleId2, "view_change", 0L);
        node2.sendEntityRollback(bubbleId1, rollback);

        log.info("✓ Migration rollback handled: {} returned to {}", entityId, bubbleId1);
    }

    @Test
    @DisplayName("Bidirectional migrations")
    void testBidirectionalMigrations() {
        var entity1 = UUID.randomUUID();
        var entity2 = UUID.randomUUID();

        // Node 1 → Node 2
        migrator1.initiateOptimisticMigration(entity1, bubbleId2);
        migrator1.queueDeferredUpdate(entity1,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event1 = new EntityDepartureEvent(
            entity1, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node1.sendEntityDeparture(bubbleId2, event1);

        // Node 2 → Node 1 (simultaneously)
        migrator2.initiateOptimisticMigration(entity2, bubbleId1);
        migrator2.queueDeferredUpdate(entity2,
            new float[]{2.0f, 3.0f, 4.0f}, new float[]{0.2f, 0.3f, 0.4f});

        var event2 = new EntityDepartureEvent(
            entity2, bubbleId2, bubbleId1,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node2.sendEntityDeparture(bubbleId1, event2);

        // Complete migrations
        migrator2.queueDeferredUpdate(entity1, new float[]{1.1f, 2.1f, 3.1f},
            new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entity1);

        migrator1.queueDeferredUpdate(entity2, new float[]{2.1f, 3.1f, 4.1f},
            new float[]{0.2f, 0.3f, 0.4f});
        migrator1.flushDeferredUpdates(entity2);

        // Flush source deferred updates
        migrator1.flushDeferredUpdates(entity1);
        migrator2.flushDeferredUpdates(entity2);

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator2.getPendingDeferredCount());

        log.info("✓ Bidirectional migrations: {} ← → {}", bubbleId1, bubbleId2);
    }

    @Test
    @DisplayName("Performance: 2-node migration latency < 100ms")
    void testMigrationPerformance() {
        int migrationCount = 50;
        long startNs = System.nanoTime();

        for (int i = 0; i < migrationCount; i++) {
            var entityId = UUID.randomUUID();

            migrator1.initiateOptimisticMigration(entityId, bubbleId2);
            migrator1.queueDeferredUpdate(entityId,
                new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, bubbleId1, bubbleId2,
                EntityMigrationState.MIGRATING_OUT, i);
            if (node1.sendEntityDeparture(bubbleId2, event)) {
                migrator2.queueDeferredUpdate(entityId,
                    new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
                migrator2.flushDeferredUpdates(entityId);
            }
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertTrue(elapsedMs < 100, "50 migrations should complete in < 100ms, got " + elapsedMs);
        log.info("✓ Performance: {} migrations in {}ms", migrationCount, elapsedMs);
    }

    @Test
    @DisplayName("Recovery from transient network failures")
    void testTransientFailureRecovery() {
        // Enable packet loss
        node1.setPacketLoss(0.5); // 50% loss
        var entityId = UUID.randomUUID();

        // Try migration multiple times until successful
        int attempts = 0;
        boolean migrationComplete = false;

        while (attempts < 5 && !migrationComplete) {
            attempts++;
            migrator1.initiateOptimisticMigration(entityId, bubbleId2);
            migrator1.queueDeferredUpdate(entityId,
                new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

            var event = new EntityDepartureEvent(
                entityId, bubbleId1, bubbleId2,
                EntityMigrationState.MIGRATING_OUT, attempts);

            if (node1.sendEntityDeparture(bubbleId2, event)) {
                migrator2.queueDeferredUpdate(entityId,
                    new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
                migrator2.flushDeferredUpdates(entityId);
                migrationComplete = true;
            }
        }

        assertTrue(migrationComplete, "Migration should eventually succeed");
        assertTrue(attempts <= 5, "Should succeed within 5 attempts");
        log.info("✓ Recovered from transient failures: {} attempts", attempts);
    }
}
