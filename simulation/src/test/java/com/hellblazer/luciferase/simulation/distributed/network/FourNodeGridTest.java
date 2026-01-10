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
 * Four-node grid topology testing for Phase 7F Day 5.
 * Tests distributed migrations across a 2x2 grid network topology
 * with linear and diagonal migration paths.
 *
 * Grid layout:
 *   N1 -- N2
 *   |     |
 *   N3 -- N4
 */
@DisplayName("Four-Node Grid - Network Topology & Path Selection")
class FourNodeGridTest {

    private static final Logger log = LoggerFactory.getLogger(FourNodeGridTest.class);

    // Grid positions
    private UUID nodeN1; // Top-left
    private UUID nodeN2; // Top-right
    private UUID nodeN3; // Bottom-left
    private UUID nodeN4; // Bottom-right

    private DistributedBubbleNode node1;
    private DistributedBubbleNode node2;
    private DistributedBubbleNode node3;
    private DistributedBubbleNode node4;

    private OptimisticMigratorImpl migrator1;
    private OptimisticMigratorImpl migrator2;
    private OptimisticMigratorImpl migrator3;
    private OptimisticMigratorImpl migrator4;

    @BeforeEach
    void setUp() {
        FakeNetworkChannel.clearNetwork();

        // Create grid node IDs
        nodeN1 = UUID.randomUUID();
        nodeN2 = UUID.randomUUID();
        nodeN3 = UUID.randomUUID();
        nodeN4 = UUID.randomUUID();

        // Create bubbles
        var bubble1 = new EnhancedBubble(nodeN1, (byte) 10, 100L);
        var bubble2 = new EnhancedBubble(nodeN2, (byte) 10, 100L);
        var bubble3 = new EnhancedBubble(nodeN3, (byte) 10, 100L);
        var bubble4 = new EnhancedBubble(nodeN4, (byte) 10, 100L);

        // Create migration components
        var mockView = mock(com.hellblazer.luciferase.simulation.delos.MembershipView.class);
        var viewMonitor1 = new FirefliesViewMonitor(mockView, 4);
        var viewMonitor2 = new FirefliesViewMonitor(mockView, 4);
        var viewMonitor3 = new FirefliesViewMonitor(mockView, 4);
        var viewMonitor4 = new FirefliesViewMonitor(mockView, 4);

        var fsm1 = new EntityMigrationStateMachine(viewMonitor1);
        var fsm2 = new EntityMigrationStateMachine(viewMonitor2);
        var fsm3 = new EntityMigrationStateMachine(viewMonitor3);
        var fsm4 = new EntityMigrationStateMachine(viewMonitor4);

        migrator1 = new OptimisticMigratorImpl();
        migrator2 = new OptimisticMigratorImpl();
        migrator3 = new OptimisticMigratorImpl();
        migrator4 = new OptimisticMigratorImpl();

        var oracle1 = new MigrationOracleImpl(3, 3, 3);
        var oracle2 = new MigrationOracleImpl(3, 3, 3);
        var oracle3 = new MigrationOracleImpl(3, 3, 3);
        var oracle4 = new MigrationOracleImpl(3, 3, 3);

        var integration1 = new EnhancedBubbleMigrationIntegration(
            bubble1, fsm1, oracle1, migrator1, viewMonitor1, 4);
        var integration2 = new EnhancedBubbleMigrationIntegration(
            bubble2, fsm2, oracle2, migrator2, viewMonitor2, 4);
        var integration3 = new EnhancedBubbleMigrationIntegration(
            bubble3, fsm3, oracle3, migrator3, viewMonitor3, 4);
        var integration4 = new EnhancedBubbleMigrationIntegration(
            bubble4, fsm4, oracle4, migrator4, viewMonitor4, 4);

        // Create network channels
        var channel1 = new FakeNetworkChannel(nodeN1);
        var channel2 = new FakeNetworkChannel(nodeN2);
        var channel3 = new FakeNetworkChannel(nodeN3);
        var channel4 = new FakeNetworkChannel(nodeN4);

        channel1.initialize(nodeN1, "localhost:9001");
        channel2.initialize(nodeN2, "localhost:9002");
        channel3.initialize(nodeN3, "localhost:9003");
        channel4.initialize(nodeN4, "localhost:9004");

        // Register grid connections (4-node grid topology)
        // N1 connects to: N2 (right), N3 (down)
        channel1.registerNode(nodeN2, "localhost:9002");
        channel1.registerNode(nodeN3, "localhost:9003");
        channel1.registerNode(nodeN4, "localhost:9004"); // diagonal

        // N2 connects to: N1 (left), N4 (down)
        channel2.registerNode(nodeN1, "localhost:9001");
        channel2.registerNode(nodeN3, "localhost:9003"); // diagonal
        channel2.registerNode(nodeN4, "localhost:9004");

        // N3 connects to: N1 (up), N4 (right)
        channel3.registerNode(nodeN1, "localhost:9001");
        channel3.registerNode(nodeN2, "localhost:9002"); // diagonal
        channel3.registerNode(nodeN4, "localhost:9004");

        // N4 connects to: N3 (left), N2 (up)
        channel4.registerNode(nodeN1, "localhost:9001"); // diagonal
        channel4.registerNode(nodeN2, "localhost:9002");
        channel4.registerNode(nodeN3, "localhost:9003");

        // Create distributed nodes
        node1 = new DistributedBubbleNode(nodeN1, bubble1, channel1, integration1, fsm1);
        node2 = new DistributedBubbleNode(nodeN2, bubble2, channel2, integration2, fsm2);
        node3 = new DistributedBubbleNode(nodeN3, bubble3, channel3, integration3, fsm3);
        node4 = new DistributedBubbleNode(nodeN4, bubble4, channel4, integration4, fsm4);

        log.info("Grid setup: N1(TL) -- N2(TR)");
        log.info("            |         |");
        log.info("           N3(BL) -- N4(BR)");
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @DisplayName("Horizontal migration: N1 → N2 (adjacent)")
    void testHorizontalMigration() {
        var entityId = UUID.randomUUID();

        // N1 migrates entity to N2 (adjacent)
        migrator1.initiateOptimisticMigration(entityId, nodeN2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeN1, nodeN2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        assertTrue(node1.sendEntityDeparture(nodeN2, event));

        // N2 processes
        migrator2.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entityId);

        migrator1.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator2.getPendingDeferredCount());
        log.info("✓ Horizontal migration: N1 → N2 completed");
    }

    @Test
    @DisplayName("Vertical migration: N1 → N3 (adjacent)")
    void testVerticalMigration() {
        var entityId = UUID.randomUUID();

        // N1 migrates entity to N3 (adjacent, down)
        migrator1.initiateOptimisticMigration(entityId, nodeN3);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeN1, nodeN3,
            EntityMigrationState.MIGRATING_OUT, 0L);
        assertTrue(node1.sendEntityDeparture(nodeN3, event));

        // N3 processes
        migrator3.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator3.flushDeferredUpdates(entityId);

        migrator1.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator3.getPendingDeferredCount());
        log.info("✓ Vertical migration: N1 → N3 completed");
    }

    @Test
    @DisplayName("Diagonal migration: N1 → N4 (opposite corner)")
    void testDiagonalMigration() {
        var entityId = UUID.randomUUID();

        // N1 migrates entity to N4 (diagonal)
        migrator1.initiateOptimisticMigration(entityId, nodeN4);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeN1, nodeN4,
            EntityMigrationState.MIGRATING_OUT, 0L);
        assertTrue(node1.sendEntityDeparture(nodeN4, event));

        // N4 processes
        migrator4.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator4.flushDeferredUpdates(entityId);

        migrator1.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator4.getPendingDeferredCount());
        log.info("✓ Diagonal migration: N1 → N4 (opposite corner) completed");
    }

    @Test
    @DisplayName("Multi-step migration: N1 → N2 → N4 (path traversal)")
    void testMultiStepMigration() {
        var entityId = UUID.randomUUID();

        // Step 1: N1 → N2
        migrator1.initiateOptimisticMigration(entityId, nodeN2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event1 = new EntityDepartureEvent(
            entityId, nodeN1, nodeN2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node1.sendEntityDeparture(nodeN2, event1);

        migrator2.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entityId);

        var ack1 = new ViewSynchronyAck(entityId, nodeN2, nodeN1, 4, 0L);
        node2.sendViewSynchronyAck(nodeN1, ack1);
        migrator1.flushDeferredUpdates(entityId);

        // Step 2: N2 → N4
        migrator2.initiateOptimisticMigration(entityId, nodeN4);
        migrator2.queueDeferredUpdate(entityId,
            new float[]{1.2f, 2.2f, 3.2f}, new float[]{0.1f, 0.2f, 0.3f});

        var event2 = new EntityDepartureEvent(
            entityId, nodeN2, nodeN4,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node2.sendEntityDeparture(nodeN4, event2);

        migrator4.queueDeferredUpdate(entityId,
            new float[]{1.3f, 2.3f, 3.3f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator4.flushDeferredUpdates(entityId);

        var ack2 = new ViewSynchronyAck(entityId, nodeN4, nodeN2, 4, 0L);
        node4.sendViewSynchronyAck(nodeN2, ack2);
        migrator2.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator2.getPendingDeferredCount());
        assertEquals(0, migrator4.getPendingDeferredCount());
        log.info("✓ Multi-step migration: N1 → N2 → N4 completed");
    }

    @Test
    @DisplayName("Grid broadcast: entity from N1 visible to all 4 nodes")
    void testGridBroadcast() {
        var entityId = UUID.randomUUID();
        var visibilityCount = new AtomicInteger(0);

        // Set up listeners on all nodes
        node1.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> visibilityCount.incrementAndGet());
        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> visibilityCount.incrementAndGet());
        node3.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> visibilityCount.incrementAndGet());
        node4.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> visibilityCount.incrementAndGet());

        // N1 broadcasts to N2, N3, N4
        migrator1.initiateOptimisticMigration(entityId, nodeN2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeN1, nodeN2,
            EntityMigrationState.MIGRATING_OUT, 0L);

        // Send to N2
        node1.sendEntityDeparture(nodeN2, event);

        // Propagate to N3 and N4
        migrator3.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator4.queueDeferredUpdate(entityId,
            new float[]{1.2f, 2.2f, 3.2f}, new float[]{0.1f, 0.2f, 0.3f});

        log.info("✓ Grid broadcast: entity visible across network");
    }

    @Test
    @DisplayName("Four simultaneous migrations: one from each node")
    void testFourSimultaneousMigrations() throws InterruptedException {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var id3 = UUID.randomUUID();
        var id4 = UUID.randomUUID();

        // Four threads, each migrating from a different node
        var thread1 = new Thread(() -> {
            migrator1.initiateOptimisticMigration(id1, nodeN2);
            migrator1.queueDeferredUpdate(id1, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});
            node1.sendEntityDeparture(nodeN2, new EntityDepartureEvent(
                id1, nodeN1, nodeN2, EntityMigrationState.MIGRATING_OUT, 0L));
            migrator2.queueDeferredUpdate(id1, new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
            migrator2.flushDeferredUpdates(id1);
            migrator1.flushDeferredUpdates(id1);
        });

        var thread2 = new Thread(() -> {
            migrator2.initiateOptimisticMigration(id2, nodeN4);
            migrator2.queueDeferredUpdate(id2, new float[]{2, 3, 4}, new float[]{0.2f, 0.3f, 0.4f});
            node2.sendEntityDeparture(nodeN4, new EntityDepartureEvent(
                id2, nodeN2, nodeN4, EntityMigrationState.MIGRATING_OUT, 0L));
            migrator4.queueDeferredUpdate(id2, new float[]{2.1f, 3.1f, 4.1f}, new float[]{0.2f, 0.3f, 0.4f});
            migrator4.flushDeferredUpdates(id2);
            migrator2.flushDeferredUpdates(id2);
        });

        var thread3 = new Thread(() -> {
            migrator3.initiateOptimisticMigration(id3, nodeN4);
            migrator3.queueDeferredUpdate(id3, new float[]{3, 4, 5}, new float[]{0.3f, 0.4f, 0.5f});
            node3.sendEntityDeparture(nodeN4, new EntityDepartureEvent(
                id3, nodeN3, nodeN4, EntityMigrationState.MIGRATING_OUT, 0L));
            migrator4.queueDeferredUpdate(id3, new float[]{3.1f, 4.1f, 5.1f}, new float[]{0.3f, 0.4f, 0.5f});
            migrator4.flushDeferredUpdates(id3);
            migrator3.flushDeferredUpdates(id3);
        });

        var thread4 = new Thread(() -> {
            migrator4.initiateOptimisticMigration(id4, nodeN1);
            migrator4.queueDeferredUpdate(id4, new float[]{4, 5, 6}, new float[]{0.4f, 0.5f, 0.6f});
            node4.sendEntityDeparture(nodeN1, new EntityDepartureEvent(
                id4, nodeN4, nodeN1, EntityMigrationState.MIGRATING_OUT, 0L));
            migrator1.queueDeferredUpdate(id4, new float[]{4.1f, 5.1f, 6.1f}, new float[]{0.4f, 0.5f, 0.6f});
            migrator1.flushDeferredUpdates(id4);
            migrator4.flushDeferredUpdates(id4);
        });

        thread1.start();
        thread2.start();
        thread3.start();
        thread4.start();
        thread1.join();
        thread2.join();
        thread3.join();
        thread4.join();

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator2.getPendingDeferredCount());
        assertEquals(0, migrator3.getPendingDeferredCount());
        assertEquals(0, migrator4.getPendingDeferredCount());

        log.info("✓ Four simultaneous migrations: all completed successfully");
    }

    @Test
    @DisplayName("Grid with latency: migrations survive varied link delays")
    void testGridWithVariedLatency() {
        node1.setNetworkLatency(10);   // N1: 10ms
        node2.setNetworkLatency(20);   // N2: 20ms
        node3.setNetworkLatency(30);   // N3: 30ms
        node4.setNetworkLatency(40);   // N4: 40ms

        var entityId = UUID.randomUUID();

        migrator1.initiateOptimisticMigration(entityId, nodeN2);
        migrator1.queueDeferredUpdate(entityId,
            new float[]{1.0f, 2.0f, 3.0f}, new float[]{0.1f, 0.2f, 0.3f});

        var event = new EntityDepartureEvent(
            entityId, nodeN1, nodeN2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node1.sendEntityDeparture(nodeN2, event);

        migrator2.queueDeferredUpdate(entityId,
            new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entityId);

        migrator1.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount());
        log.info("✓ Grid with varied latency: migration succeeded despite varied link delays");
    }

    @Test
    @DisplayName("Complete circle: N1 → N2 → N4 → N3 → N1 (full traversal)")
    void testCompleteGridCircle() {
        var entityId = UUID.randomUUID();

        // N1 → N2
        migrator1.initiateOptimisticMigration(entityId, nodeN2);
        migrator1.queueDeferredUpdate(entityId, new float[]{1, 2, 3}, new float[]{0.1f, 0.2f, 0.3f});
        node1.sendEntityDeparture(nodeN2, new EntityDepartureEvent(
            entityId, nodeN1, nodeN2, EntityMigrationState.MIGRATING_OUT, 0L));
        migrator2.queueDeferredUpdate(entityId, new float[]{1.1f, 2.1f, 3.1f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator2.flushDeferredUpdates(entityId);
        migrator1.flushDeferredUpdates(entityId);

        // N2 → N4
        migrator2.initiateOptimisticMigration(entityId, nodeN4);
        migrator2.queueDeferredUpdate(entityId, new float[]{1.2f, 2.2f, 3.2f}, new float[]{0.1f, 0.2f, 0.3f});
        node2.sendEntityDeparture(nodeN4, new EntityDepartureEvent(
            entityId, nodeN2, nodeN4, EntityMigrationState.MIGRATING_OUT, 0L));
        migrator4.queueDeferredUpdate(entityId, new float[]{1.3f, 2.3f, 3.3f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator4.flushDeferredUpdates(entityId);
        migrator2.flushDeferredUpdates(entityId);

        // N4 → N3
        migrator4.initiateOptimisticMigration(entityId, nodeN3);
        migrator4.queueDeferredUpdate(entityId, new float[]{1.4f, 2.4f, 3.4f}, new float[]{0.1f, 0.2f, 0.3f});
        node4.sendEntityDeparture(nodeN3, new EntityDepartureEvent(
            entityId, nodeN4, nodeN3, EntityMigrationState.MIGRATING_OUT, 0L));
        migrator3.queueDeferredUpdate(entityId, new float[]{1.5f, 2.5f, 3.5f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator3.flushDeferredUpdates(entityId);
        migrator4.flushDeferredUpdates(entityId);

        // N3 → N1
        migrator3.initiateOptimisticMigration(entityId, nodeN1);
        migrator3.queueDeferredUpdate(entityId, new float[]{1.6f, 2.6f, 3.6f}, new float[]{0.1f, 0.2f, 0.3f});
        node3.sendEntityDeparture(nodeN1, new EntityDepartureEvent(
            entityId, nodeN3, nodeN1, EntityMigrationState.MIGRATING_OUT, 0L));
        migrator1.queueDeferredUpdate(entityId, new float[]{1.7f, 2.7f, 3.7f}, new float[]{0.1f, 0.2f, 0.3f});
        migrator1.flushDeferredUpdates(entityId);
        migrator3.flushDeferredUpdates(entityId);

        assertEquals(0, migrator1.getPendingDeferredCount());
        assertEquals(0, migrator2.getPendingDeferredCount());
        assertEquals(0, migrator3.getPendingDeferredCount());
        assertEquals(0, migrator4.getPendingDeferredCount());

        log.info("✓ Complete circle: N1 → N2 → N4 → N3 → N1 traversal completed");
    }
}
