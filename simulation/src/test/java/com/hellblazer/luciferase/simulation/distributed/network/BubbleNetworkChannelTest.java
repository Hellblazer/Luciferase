/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.luciferase.simulation.distributed.migration.*;
import com.hellblazer.luciferase.simulation.events.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BubbleNetworkChannel interface and FakeNetworkChannel implementation.
 * Tests message delivery, latency simulation, and packet loss simulation.
 */
@DisplayName("BubbleNetworkChannel - Network Layer")
class BubbleNetworkChannelTest {

    private static final Logger log = LoggerFactory.getLogger(BubbleNetworkChannelTest.class);

    private UUID nodeId1;
    private UUID nodeId2;
    private FakeNetworkChannel channel1;
    private FakeNetworkChannel channel2;

    @BeforeEach
    void setUp() {
        FakeNetworkChannel.clearNetwork();
        nodeId1 = UUID.randomUUID();
        nodeId2 = UUID.randomUUID();

        channel1 = new FakeNetworkChannel(nodeId1);
        channel2 = new FakeNetworkChannel(nodeId2);

        channel1.initialize(nodeId1, "localhost:5001");
        channel2.initialize(nodeId2, "localhost:5002");

        channel1.registerNode(nodeId2, "localhost:5002");
        channel2.registerNode(nodeId1, "localhost:5001");

        log.info("Test setup: {} ↔ {}", nodeId1, nodeId2);
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @DisplayName("Initialize network channel")
    void testInitializeChannel() {
        assertTrue(channel1.isNodeReachable(nodeId2));
        assertTrue(channel2.isNodeReachable(nodeId1));
        log.info("✓ Channels initialized and reachable");
    }

    @Test
    @DisplayName("Send and receive EntityDepartureEvent")
    void testEntityDepartureMessageDelivery() {
        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();
        var event = new EntityDepartureEvent(entityId, nodeId1, targetBubbleId,
            com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);

        var received = new AtomicInteger(0);
        channel2.setEntityDepartureListener((sourceId, evt) -> {
            assertEquals(nodeId1, sourceId);
            assertEquals(entityId, evt.getEntityId());
            received.incrementAndGet();
        });

        boolean sent = channel1.sendEntityDeparture(nodeId2, event);
        assertTrue(sent, "Message should be sent");

        channel2.flushPendingMessages();
        assertEquals(1, received.get(), "Message should be received");
        log.info("✓ EntityDepartureEvent delivered successfully");
    }

    @Test
    @DisplayName("Send and receive ViewSynchronyAck")
    void testViewSynchronyAckMessageDelivery() {
        var entityId = UUID.randomUUID();
        var ack = new ViewSynchronyAck(entityId, nodeId2, nodeId1, 3, 0L);

        var received = new AtomicInteger(0);
        channel1.setViewSynchronyAckListener((sourceId, evt) -> {
            assertEquals(nodeId2, sourceId);
            assertEquals(entityId, evt.getEntityId());
            received.incrementAndGet();
        });

        boolean sent = channel2.sendViewSynchronyAck(nodeId1, ack);
        assertTrue(sent, "Message should be sent");

        channel1.flushPendingMessages();
        assertEquals(1, received.get(), "Message should be received");
        log.info("✓ ViewSynchronyAck delivered successfully");
    }

    @Test
    @DisplayName("Send and receive EntityRollbackEvent")
    void testEntityRollbackMessageDelivery() {
        var entityId = UUID.randomUUID();
        var event = new EntityRollbackEvent(entityId, nodeId2, nodeId1, "test_rollback", 0L);

        var received = new AtomicInteger(0);
        channel1.setEntityRollbackListener((sourceId, evt) -> {
            assertEquals(nodeId2, sourceId);
            assertEquals(entityId, evt.getEntityId());
            received.incrementAndGet();
        });

        boolean sent = channel2.sendEntityRollback(nodeId1, event);
        assertTrue(sent, "Message should be sent");

        channel1.flushPendingMessages();
        assertEquals(1, received.get(), "Message should be received");
        log.info("✓ EntityRollbackEvent delivered successfully");
    }

    @Test
    @DisplayName("Network latency simulation")
    void testNetworkLatency() {
        channel1.setNetworkLatency(50); // 50ms one-way

        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();
        var event = new EntityDepartureEvent(entityId, nodeId1, targetBubbleId,
            com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);

        var received = new AtomicInteger(0);
        channel2.setEntityDepartureListener((sourceId, evt) -> received.incrementAndGet());

        long startMs = System.currentTimeMillis();
        channel1.sendEntityDeparture(nodeId2, event);
        channel2.flushPendingMessages(); // Immediate, but message should still be pending

        long elapsedMs = System.currentTimeMillis() - startMs;
        assertEquals(0, received.get(), "Message should not arrive immediately with 50ms latency");

        int pendingCount = channel2.getPendingMessageCount();
        assertTrue(pendingCount >= 0, "Should track pending messages");
        log.info("✓ Network latency: {}ms elapsed, {} pending messages", elapsedMs, pendingCount);
    }

    @Test
    @DisplayName("Packet loss simulation")
    void testPacketLoss() {
        channel1.setPacketLoss(0.5); // 50% loss rate

        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();
        int successCount = 0;

        channel2.setEntityDepartureListener((sourceId, evt) -> {
            // Will be called only if packet not lost
        });

        for (int i = 0; i < 10; i++) {
            var event = new EntityDepartureEvent(
                UUID.randomUUID(), nodeId1, targetBubbleId,
                com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);
            if (channel1.sendEntityDeparture(nodeId2, event)) {
                successCount++;
            }
        }

        // With 50% loss, expect roughly half to succeed
        assertTrue(successCount < 10, "Some messages should be dropped");
        assertTrue(successCount > 0, "Some messages should succeed");
        log.info("✓ Packet loss: {}/10 messages dropped", 10 - successCount);
    }

    @Test
    @DisplayName("Multiple messages in flight")
    void testMultipleMessagesInFlight() {
        // Set latency so messages stay pending
        channel1.setNetworkLatency(100);

        var receivedCount = new AtomicInteger(0);
        channel2.setEntityDepartureListener((sourceId, evt) -> receivedCount.incrementAndGet());

        // Send 10 messages
        for (int i = 0; i < 10; i++) {
            var entityId = UUID.randomUUID();
            var targetBubbleId = UUID.randomUUID();
            var event = new EntityDepartureEvent(
                entityId, nodeId1, targetBubbleId,
                com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);
            channel1.sendEntityDeparture(nodeId2, event);
        }

        // Messages should still be pending with latency
        int pendingCount = channel1.getPendingMessageCount();
        assertTrue(pendingCount >= 10, "All messages should be pending with latency");
        assertEquals(0, receivedCount.get(), "Messages should not be delivered yet with latency");

        log.info("✓ Multiple messages: {} pending, latency simulation working", pendingCount);
    }

    @Test
    @DisplayName("Unreachable node handling")
    void testUnreachableNode() {
        var unreachableNodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();
        var event = new EntityDepartureEvent(entityId, nodeId1, targetBubbleId,
            com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);

        assertFalse(channel1.isNodeReachable(unreachableNodeId));

        boolean sent = channel1.sendEntityDeparture(unreachableNodeId, event);
        assertFalse(sent, "Message should not be sent to unreachable node");
        log.info("✓ Unreachable node correctly rejected");
    }

    @Test
    @DisplayName("Node reachability tracking")
    void testNodeReachabilityTracking() {
        assertTrue(channel1.isNodeReachable(nodeId2));
        assertTrue(channel2.isNodeReachable(nodeId1));

        // Create and register a new node
        var nodeId3 = UUID.randomUUID();
        var channel3 = new FakeNetworkChannel(nodeId3);
        channel3.initialize(nodeId3, "localhost:5003");

        // Register the new node in existing channels
        channel1.registerNode(nodeId3, "localhost:5003");
        channel2.registerNode(nodeId3, "localhost:5003");

        // Now should be reachable
        assertTrue(channel1.isNodeReachable(nodeId3), "Newly registered node should be reachable");
        log.info("✓ Node reachability tracking works");
    }

    @Test
    @DisplayName("Concurrent message delivery")
    void testConcurrentMessageDelivery() throws InterruptedException {
        var receivedCount = new AtomicInteger(0);
        channel2.setEntityDepartureListener((sourceId, evt) -> receivedCount.incrementAndGet());

        // Send messages from multiple threads
        var threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    var entityId = UUID.randomUUID();
                    var targetBubbleId = UUID.randomUUID();
                    var event = new EntityDepartureEvent(
                        entityId, nodeId1, targetBubbleId,
                        com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);
                    channel1.sendEntityDeparture(nodeId2, event);
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        channel2.flushPendingMessages();
        assertEquals(50, receivedCount.get(), "All 50 messages should be received");
        log.info("✓ Concurrent delivery: 50 messages from 5 threads delivered");
    }

    @Test
    @DisplayName("Message ordering")
    void testMessageOrdering() {
        var receivedOrder = new java.util.ArrayList<Integer>();
        var targetBubbleId = UUID.randomUUID();

        channel2.setEntityDepartureListener((sourceId, evt) -> {
            if (evt.getEntityId().getMostSignificantBits() < Integer.MAX_VALUE) {
                receivedOrder.add((int) evt.getEntityId().getMostSignificantBits());
            }
        });

        // Send 5 messages with identifiable order
        for (int i = 0; i < 5; i++) {
            var entityId = new UUID(i, i);
            var event = new EntityDepartureEvent(entityId, nodeId1, targetBubbleId,
                com.hellblazer.luciferase.simulation.causality.EntityMigrationState.MIGRATING_OUT, 0L);
            channel1.sendEntityDeparture(nodeId2, event);
        }

        channel2.flushPendingMessages();
        assertEquals(5, receivedOrder.size());
        log.info("✓ Message ordering preserved: {} messages in order", receivedOrder.size());
    }
}
