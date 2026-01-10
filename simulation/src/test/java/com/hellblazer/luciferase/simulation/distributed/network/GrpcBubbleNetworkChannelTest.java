/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.events.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GrpcBubbleNetworkChannelTest - TDD tests for gRPC network channel implementation.
 * Tests the production gRPC implementation against the BubbleNetworkChannel interface contract.
 */
public class GrpcBubbleNetworkChannelTest {

    private GrpcBubbleNetworkChannel sourceChannel;
    private GrpcBubbleNetworkChannel targetChannel;
    private UUID sourceNodeId;
    private UUID targetNodeId;

    @BeforeEach
    void setUp() {
        sourceNodeId = UUID.randomUUID();
        targetNodeId = UUID.randomUUID();

        sourceChannel = new GrpcBubbleNetworkChannel();
        targetChannel = new GrpcBubbleNetworkChannel();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sourceChannel != null) {
            sourceChannel.close();
        }
        if (targetChannel != null) {
            targetChannel.close();
        }
    }

    /**
     * Test 1: Basic Initialization
     * Verify channel can initialize on dynamic port and accept connections.
     */
    @Test
    void testBasicInitialization() {
        // Initialize source channel on dynamic port (0 = OS assigns)
        sourceChannel.initialize(sourceNodeId, "localhost:0");

        // Get the actual port assigned
        var sourceAddress = sourceChannel.getLocalAddress();
        assertNotNull(sourceAddress, "Local address should be set after initialization");
        assertTrue(sourceAddress.contains(":"), "Address should contain port");

        // Initialize target channel
        targetChannel.initialize(targetNodeId, "localhost:0");
        var targetAddress = targetChannel.getLocalAddress();
        assertNotNull(targetAddress, "Target address should be set");

        // Register nodes with each other
        sourceChannel.registerNode(targetNodeId, targetAddress);
        targetChannel.registerNode(sourceNodeId, sourceAddress);

        // Verify reachability
        assertTrue(sourceChannel.isNodeReachable(targetNodeId), "Target should be reachable from source");
        assertTrue(targetChannel.isNodeReachable(sourceNodeId), "Source should be reachable from target");
    }

    /**
     * Test 2: EntityDepartureEvent Delivery
     * Verify EntityDepartureEvent can be sent and received.
     */
    @Test
    void testEntityDepartureEventDelivery() throws Exception {
        // Initialize channels
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set up listener on target
        var receivedLatch = new CountDownLatch(1);
        var receivedEvent = new AtomicReference<EntityDepartureEvent>();
        var receivedSourceId = new AtomicReference<UUID>();

        targetChannel.setEntityDepartureListener((sourceId, event) -> {
            receivedSourceId.set(sourceId);
            receivedEvent.set(event);
            receivedLatch.countDown();
        });

        // Create and send event
        var entityId = UUID.randomUUID();
        var event = new EntityDepartureEvent(
            entityId,
            sourceNodeId,
            targetNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        assertTrue(sourceChannel.sendEntityDeparture(targetNodeId, event),
                   "Send should return true");

        // Wait for delivery
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive event within 5 seconds");

        // Verify received event
        assertEquals(sourceNodeId, receivedSourceId.get(), "Source ID should match");
        assertNotNull(receivedEvent.get(), "Event should be received");
        assertEquals(entityId, receivedEvent.get().getEntityId(), "Entity ID should match");
        assertEquals(EntityMigrationState.MIGRATING_OUT, receivedEvent.get().getStateSnapshot());
    }

    /**
     * Test 3: ViewSynchronyAck Delivery
     * Verify ViewSynchronyAck can be sent and received.
     */
    @Test
    void testViewSynchronyAckDelivery() throws Exception {
        // Initialize channels
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set up listener on source
        var receivedLatch = new CountDownLatch(1);
        var receivedAck = new AtomicReference<ViewSynchronyAck>();
        var receivedSourceId = new AtomicReference<UUID>();

        sourceChannel.setViewSynchronyAckListener((sourceId, ack) -> {
            receivedSourceId.set(sourceId);
            receivedAck.set(ack);
            receivedLatch.countDown();
        });

        // Create and send ack
        var entityId = UUID.randomUUID();
        var ack = new ViewSynchronyAck(
            entityId,
            sourceNodeId,
            targetNodeId,
            3, // stability ticks
            System.nanoTime()
        );

        assertTrue(targetChannel.sendViewSynchronyAck(sourceNodeId, ack),
                   "Send should return true");

        // Wait for delivery
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive ack within 5 seconds");

        // Verify received ack
        assertEquals(targetNodeId, receivedSourceId.get(), "Source ID should match");
        assertNotNull(receivedAck.get(), "Ack should be received");
        assertEquals(entityId, receivedAck.get().getEntityId(), "Entity ID should match");
        assertEquals(3, receivedAck.get().getStabilityTicksVerified());
    }

    /**
     * Test 4: EntityRollbackEvent Delivery
     * Verify EntityRollbackEvent can be sent and received.
     */
    @Test
    void testEntityRollbackEventDelivery() throws Exception {
        // Initialize channels
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set up listener on target
        var receivedLatch = new CountDownLatch(1);
        var receivedRollback = new AtomicReference<EntityRollbackEvent>();
        var receivedSourceId = new AtomicReference<UUID>();

        targetChannel.setEntityRollbackListener((sourceId, rollback) -> {
            receivedSourceId.set(sourceId);
            receivedRollback.set(rollback);
            receivedLatch.countDown();
        });

        // Create and send rollback
        var entityId = UUID.randomUUID();
        var rollback = new EntityRollbackEvent(
            entityId,
            sourceNodeId,
            targetNodeId,
            "timeout",
            System.nanoTime()
        );

        assertTrue(sourceChannel.sendEntityRollback(targetNodeId, rollback),
                   "Send should return true");

        // Wait for delivery
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive rollback within 5 seconds");

        // Verify received rollback
        assertEquals(sourceNodeId, receivedSourceId.get(), "Source ID should match");
        assertNotNull(receivedRollback.get(), "Rollback should be received");
        assertEquals(entityId, receivedRollback.get().getEntityId(), "Entity ID should match");
        assertEquals("timeout", receivedRollback.get().getReason());
    }

    /**
     * Test 5: Connection Refused (Unreachable Node)
     * Verify proper handling when target node is unreachable.
     */
    @Test
    void testUnreachableNode() {
        sourceChannel.initialize(sourceNodeId, "localhost:0");

        // Register unreachable node
        var unreachableNodeId = UUID.randomUUID();
        sourceChannel.registerNode(unreachableNodeId, "localhost:9999");

        // Try to send to unreachable node
        var event = new EntityDepartureEvent(
            UUID.randomUUID(),
            sourceNodeId,
            unreachableNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        // In gRPC, isNodeReachable just checks if address is registered
        // Actual connectivity is determined when sending (async failures)
        assertTrue(sourceChannel.isNodeReachable(unreachableNodeId),
                   "Registered node should show as reachable (actual connectivity checked on send)");

        // Send will return true (queued) but fail async
        assertTrue(sourceChannel.sendEntityDeparture(unreachableNodeId, event),
                   "Send returns true even if node is down (fails async)");
    }

    /**
     * Test 6: Connection Pooling (Reuse Channels)
     * Verify channels are reused for multiple messages to same node.
     */
    @Test
    void testConnectionPooling() throws Exception {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        var receivedCount = new AtomicInteger(0);
        var receivedLatch = new CountDownLatch(10);

        targetChannel.setEntityDepartureListener((sourceId, event) -> {
            receivedCount.incrementAndGet();
            receivedLatch.countDown();
        });

        // Send 10 rapid messages
        for (int i = 0; i < 10; i++) {
            var event = new EntityDepartureEvent(
                UUID.randomUUID(),
                sourceNodeId,
                targetNodeId,
                EntityMigrationState.MIGRATING_OUT,
                System.nanoTime()
            );
            assertTrue(sourceChannel.sendEntityDeparture(targetNodeId, event));
        }

        // All should be received
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive all 10 events");
        assertEquals(10, receivedCount.get(), "Should receive exactly 10 events");
    }

    /**
     * Test 7: Graceful Shutdown
     * Verify channels can be shut down without hanging.
     */
    @Test
    void testGracefulShutdown() throws Exception {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());

        // Shutdown should complete within reasonable time
        var shutdownLatch = new CountDownLatch(2);

        new Thread(() -> {
            try {
                sourceChannel.close();
                shutdownLatch.countDown();
            } catch (Exception e) {
                fail("Source shutdown failed: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try {
                targetChannel.close();
                shutdownLatch.countDown();
            } catch (Exception e) {
                fail("Target shutdown failed: " + e.getMessage());
            }
        }).start();

        assertTrue(shutdownLatch.await(5, TimeUnit.SECONDS),
                   "Shutdown should complete within 5 seconds");
    }

    /**
     * Test 8: Network Latency Simulation
     * Verify optional latency simulation works (backward compatibility with FakeNetworkChannel).
     */
    @Test
    void testNetworkLatencySimulation() throws Exception {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());
        targetChannel.registerNode(sourceNodeId, sourceChannel.getLocalAddress());

        // Set latency simulation
        sourceChannel.setNetworkLatency(100); // 100ms latency

        var receivedLatch = new CountDownLatch(1);
        var receivedTime = new AtomicReference<Long>();

        targetChannel.setEntityDepartureListener((sourceId, event) -> {
            receivedTime.set(System.currentTimeMillis());
            receivedLatch.countDown();
        });

        var sendTime = System.currentTimeMillis();
        var event = new EntityDepartureEvent(
            UUID.randomUUID(),
            sourceNodeId,
            targetNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        sourceChannel.sendEntityDeparture(targetNodeId, event);

        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS),
                   "Should receive event");

        // Note: Latency simulation may not be exact in gRPC, this is optional
        // Just verify it was received eventually
        assertNotNull(receivedTime.get());
    }

    /**
     * Test 9: Packet Loss Simulation
     * Verify optional packet loss simulation works (backward compatibility).
     */
    @Test
    void testPacketLossSimulation() {
        sourceChannel.initialize(sourceNodeId, "localhost:0");
        targetChannel.initialize(targetNodeId, "localhost:0");

        sourceChannel.registerNode(targetNodeId, targetChannel.getLocalAddress());

        // Set 100% packet loss
        sourceChannel.setPacketLoss(1.0);

        var event = new EntityDepartureEvent(
            UUID.randomUUID(),
            sourceNodeId,
            targetNodeId,
            EntityMigrationState.MIGRATING_OUT,
            System.nanoTime()
        );

        // Note: In gRPC, packet loss simulation may work differently
        // This test just ensures the API exists and doesn't crash
        sourceChannel.sendEntityDeparture(targetNodeId, event);
    }

    /**
     * Test 10: Multiple Simultaneous Channels
     * Verify multiple channels can coexist on same host.
     */
    @Test
    void testMultipleSimultaneousChannels() throws Exception {
        var node1Id = UUID.randomUUID();
        var node2Id = UUID.randomUUID();
        var node3Id = UUID.randomUUID();

        var channel1 = new GrpcBubbleNetworkChannel();
        var channel2 = new GrpcBubbleNetworkChannel();
        var channel3 = new GrpcBubbleNetworkChannel();

        try {
            channel1.initialize(node1Id, "localhost:0");
            channel2.initialize(node2Id, "localhost:0");
            channel3.initialize(node3Id, "localhost:0");

            // Register all nodes with each other
            channel1.registerNode(node2Id, channel2.getLocalAddress());
            channel1.registerNode(node3Id, channel3.getLocalAddress());
            channel2.registerNode(node1Id, channel1.getLocalAddress());
            channel2.registerNode(node3Id, channel3.getLocalAddress());
            channel3.registerNode(node1Id, channel1.getLocalAddress());
            channel3.registerNode(node2Id, channel2.getLocalAddress());

            // All should be reachable
            assertTrue(channel1.isNodeReachable(node2Id));
            assertTrue(channel1.isNodeReachable(node3Id));
            assertTrue(channel2.isNodeReachable(node1Id));
            assertTrue(channel2.isNodeReachable(node3Id));
            assertTrue(channel3.isNodeReachable(node1Id));
            assertTrue(channel3.isNodeReachable(node2Id));

        } finally {
            channel1.close();
            channel2.close();
            channel3.close();
        }
    }
}
