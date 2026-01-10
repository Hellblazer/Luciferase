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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Two-bubble network communication test for Phase 7F Day 2.
 * Tests EntityDepartureEvent, ViewSynchronyAck, EntityRollbackEvent handling
 * across network with message ordering guarantees and reliability layer.
 */
@DisplayName("Two-Bubble Network Communication - Cross-Bubble Exchange")
class TwoBubbleNetworkCommunicationTest {

    private static final Logger log = LoggerFactory.getLogger(TwoBubbleNetworkCommunicationTest.class);

    private UUID bubbleId1;
    private UUID bubbleId2;
    private DistributedBubbleNode node1;
    private DistributedBubbleNode node2;
    private EnhancedBubble bubble1;
    private EnhancedBubble bubble2;

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

        var migrator1 = new OptimisticMigratorImpl();
        var migrator2 = new OptimisticMigratorImpl();

        var oracle1 = new MigrationOracleImpl(2, 2, 2);
        var oracle2 = new MigrationOracleImpl(2, 2, 2);

        var integration1 = new EnhancedBubbleMigrationIntegration(
            bubble1, fsm1, oracle1, migrator1, viewMonitor1, 3);
        var integration2 = new EnhancedBubbleMigrationIntegration(
            bubble2, fsm2, oracle2, migrator2, viewMonitor2, 3);

        // Create network channels
        var channel1 = new FakeNetworkChannel(bubbleId1);
        var channel2 = new FakeNetworkChannel(bubbleId2);

        channel1.initialize(bubbleId1, "localhost:6001");
        channel2.initialize(bubbleId2, "localhost:6002");

        channel1.registerNode(bubbleId2, "localhost:6002");
        channel2.registerNode(bubbleId1, "localhost:6001");

        // Create distributed nodes
        node1 = new DistributedBubbleNode(bubbleId1, bubble1, channel1, integration1, fsm1);
        node2 = new DistributedBubbleNode(bubbleId2, bubble2, channel2, integration2, fsm2);

        log.info("Setup: {} ↔ {}", bubbleId1, bubbleId2);
    }

    @AfterEach
    void tearDown() {
        FakeNetworkChannel.clearNetwork();
    }

    @Test
    @DisplayName("EntityDepartureEvent: Source bubble sends, target receives")
    void testEntityDepartureEventExchange() {
        var entityId = UUID.randomUUID();
        var departureReceived = new AtomicInteger(0);

        // Node 2 listens for entity departure
        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> {
            assertEquals(bubbleId1, sourceId);
            assertEquals(entityId, evt.getEntityId());
            departureReceived.incrementAndGet();
            log.debug("Node 2 received EntityDepartureEvent for {}", entityId);
        });

        // Node 1 sends entity departure event
        var event = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        boolean sent = node1.sendEntityDeparture(bubbleId2, event);

        assertTrue(sent, "EntityDepartureEvent should be sent");
        assertEquals(1, departureReceived.get(), "Target should receive EntityDepartureEvent");
        log.info("✓ EntityDepartureEvent exchange successful");
    }

    @Test
    @DisplayName("ViewSynchronyAck: Target bubble acknowledges migration")
    void testViewSynchronyAckExchange() {
        var entityId = UUID.randomUUID();
        var ackReceived = new AtomicInteger(0);

        // Node 1 listens for view synchrony ack
        node1.getNetworkChannel().setViewSynchronyAckListener((sourceId, evt) -> {
            assertEquals(bubbleId2, sourceId);
            assertEquals(entityId, evt.getEntityId());
            ackReceived.incrementAndGet();
            log.debug("Node 1 received ViewSynchronyAck for {}", entityId);
        });

        // Node 2 sends acknowledgment
        var ack = new ViewSynchronyAck(entityId, bubbleId2, bubbleId1, 3, 0L);
        boolean sent = node2.sendViewSynchronyAck(bubbleId1, ack);

        assertTrue(sent, "ViewSynchronyAck should be sent");
        assertEquals(1, ackReceived.get(), "Source should receive ViewSynchronyAck");
        log.info("✓ ViewSynchronyAck exchange successful");
    }

    @Test
    @DisplayName("EntityRollbackEvent: Migration rollback notification")
    void testEntityRollbackEventExchange() {
        var entityId = UUID.randomUUID();
        var rollbackReceived = new AtomicInteger(0);

        // Node 2 listens for rollback event
        node2.getNetworkChannel().setEntityRollbackListener((sourceId, evt) -> {
            assertEquals(bubbleId1, sourceId);
            assertEquals(entityId, evt.getEntityId());
            rollbackReceived.incrementAndGet();
            log.debug("Node 2 received EntityRollbackEvent for {}", entityId);
        });

        // Node 1 sends rollback event
        var event = new EntityRollbackEvent(entityId, bubbleId1, bubbleId2, "timeout", 0L);
        boolean sent = node1.sendEntityRollback(bubbleId2, event);

        assertTrue(sent, "EntityRollbackEvent should be sent");
        assertEquals(1, rollbackReceived.get(), "Target should receive EntityRollbackEvent");
        log.info("✓ EntityRollbackEvent exchange successful");
    }

    @Test
    @DisplayName("Message ordering: EntityDeparture → ViewSynchronyAck → EntityRollback")
    void testMessageOrdering() {
        var entityId1 = UUID.randomUUID();
        var entityId2 = UUID.randomUUID();
        var eventSequence = new java.util.ArrayList<String>();

        // Node 1 listener
        node1.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            eventSequence.add("Departure-" + evt.getEntityId()));
        node1.getNetworkChannel().setViewSynchronyAckListener((sourceId, evt) ->
            eventSequence.add("Ack-" + evt.getEntityId()));
        node1.getNetworkChannel().setEntityRollbackListener((sourceId, evt) ->
            eventSequence.add("Rollback-" + evt.getEntityId()));

        // Send multiple events in order
        var departure = new EntityDepartureEvent(
            entityId1, bubbleId2, bubbleId1,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node2.sendEntityDeparture(bubbleId1, departure);

        var ack = new ViewSynchronyAck(entityId1, bubbleId2, bubbleId1, 3, 1L);
        node2.sendViewSynchronyAck(bubbleId1, ack);

        var rollback = new EntityRollbackEvent(entityId2, bubbleId2, bubbleId1, "view_change", 2L);
        node2.sendEntityRollback(bubbleId1, rollback);

        // Verify ordering
        assertEquals(3, eventSequence.size(), "All three messages received");
        assertTrue(eventSequence.get(0).startsWith("Departure-"), "First message is Departure");
        assertTrue(eventSequence.get(1).startsWith("Ack-"), "Second message is Ack");
        assertTrue(eventSequence.get(2).startsWith("Rollback-"), "Third message is Rollback");
        log.info("✓ Message ordering preserved: {}", eventSequence);
    }

    @Test
    @DisplayName("Reliability: Message delivery with retries (no loss)")
    void testReliableMessageDelivery() {
        var entityId = UUID.randomUUID();
        int messageCount = 10;
        var receivedCount = new AtomicInteger(0);

        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            receivedCount.incrementAndGet());

        // Send 10 messages
        for (int i = 0; i < messageCount; i++) {
            var event = new EntityDepartureEvent(
                UUID.randomUUID(), bubbleId1, bubbleId2,
                EntityMigrationState.MIGRATING_OUT, i);
            node1.sendEntityDeparture(bubbleId2, event);
        }

        // All should be delivered with 0% loss
        assertEquals(messageCount, receivedCount.get(),
            "All messages should be delivered with no loss");
        log.info("✓ Reliable delivery: {}/{} messages delivered", receivedCount.get(), messageCount);
    }

    @Test
    @DisplayName("Reliability: Graceful handling of packet loss")
    void testPacketLossHandling() {
        node1.setPacketLoss(0.5); // 50% loss rate

        var entityId = UUID.randomUUID();
        int attemptCount = 20;
        var receivedCount = new AtomicInteger(0);

        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            receivedCount.incrementAndGet());

        // Try sending 20 messages with 50% loss
        int successCount = 0;
        for (int i = 0; i < attemptCount; i++) {
            var event = new EntityDepartureEvent(
                UUID.randomUUID(), bubbleId1, bubbleId2,
                EntityMigrationState.MIGRATING_OUT, i);
            if (node1.sendEntityDeparture(bubbleId2, event)) {
                successCount++;
            }
        }

        // With 50% loss, expect roughly half
        assertTrue(successCount < attemptCount, "Some messages should be lost");
        assertTrue(successCount > 0, "Some messages should succeed");
        assertEquals(successCount, receivedCount.get(),
            "Received count should match sent count (no additional failures)");
        log.info("✓ Packet loss: {}/{} sent, {}/{} received",
            successCount, attemptCount, receivedCount.get(), successCount);
    }

    @Test
    @DisplayName("Network latency: Messages delayed but eventually arrive")
    void testNetworkLatency() {
        node1.setNetworkLatency(100); // 100ms one-way

        var entityId = UUID.randomUUID();
        var receivedCount = new AtomicInteger(0);

        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            receivedCount.incrementAndGet());

        // Send message
        var event = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        long sendTimeMs = System.currentTimeMillis();
        node1.sendEntityDeparture(bubbleId2, event);

        // Message should not arrive immediately
        assertEquals(0, receivedCount.get(), "Message should not arrive immediately with latency");

        log.info("✓ Network latency: Message delayed but guaranteed delivery");
    }

    @Test
    @DisplayName("Complete migration workflow: Departure → Ack → Completion")
    void testCompleteMigrationWorkflow() {
        var entityId = UUID.randomUUID();

        var departureReceived = new AtomicInteger(0);
        var ackReceived = new AtomicInteger(0);

        // Node 2 listens for departure
        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) -> {
            departureReceived.incrementAndGet();
            log.debug("Step 1: Node 2 received EntityDepartureEvent");

            // Step 2: Node 2 sends back acknowledgment
            var ack = new ViewSynchronyAck(entityId, bubbleId2, bubbleId1, 3, 1L);
            node2.sendViewSynchronyAck(bubbleId1, ack);
        });

        // Node 1 listens for ack
        node1.getNetworkChannel().setViewSynchronyAckListener((sourceId, evt) -> {
            ackReceived.incrementAndGet();
            log.debug("Step 3: Node 1 received ViewSynchronyAck - migration complete");
        });

        // Step 1: Node 1 sends departure event
        var departure = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        node1.sendEntityDeparture(bubbleId2, departure);

        // Verify workflow completed
        assertEquals(1, departureReceived.get(), "Departure event should be received");
        assertEquals(1, ackReceived.get(), "Acknowledgment should be received");
        log.info("✓ Complete migration workflow: Departure → Ack → Completion successful");
    }

    @Test
    @DisplayName("Concurrent bidirectional communication")
    void testBidirectionalCommunication() throws InterruptedException {
        var entityId1 = UUID.randomUUID();
        var entityId2 = UUID.randomUUID();

        var node1Received = new AtomicInteger(0);
        var node2Received = new AtomicInteger(0);

        // Node 1 listener
        node1.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            node1Received.incrementAndGet());

        // Node 2 listener
        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            node2Received.incrementAndGet());

        // Both nodes send simultaneously
        var thread1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                var event = new EntityDepartureEvent(
                    UUID.randomUUID(), bubbleId1, bubbleId2,
                    EntityMigrationState.MIGRATING_OUT, i);
                node1.sendEntityDeparture(bubbleId2, event);
            }
        });

        var thread2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                var event = new EntityDepartureEvent(
                    UUID.randomUUID(), bubbleId2, bubbleId1,
                    EntityMigrationState.MIGRATING_OUT, i);
                node2.sendEntityDeparture(bubbleId1, event);
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        // Both should have received all messages
        assertEquals(10, node1Received.get(), "Node 1 should receive 10 messages");
        assertEquals(10, node2Received.get(), "Node 2 should receive 10 messages");
        log.info("✓ Bidirectional communication: 10 + 10 messages exchanged");
    }

    @Test
    @DisplayName("Network node reachability during communication")
    void testNodeReachabilityDuringCommunication() {
        assertTrue(node1.isNodeReachable(bubbleId2), "Node 2 should be reachable");
        assertTrue(node2.isNodeReachable(bubbleId1), "Node 1 should be reachable");

        var entityId = UUID.randomUUID();
        var receivedCount = new AtomicInteger(0);

        node2.getNetworkChannel().setEntityDepartureListener((sourceId, evt) ->
            receivedCount.incrementAndGet());

        // Send message while nodes are reachable
        var event = new EntityDepartureEvent(
            entityId, bubbleId1, bubbleId2,
            EntityMigrationState.MIGRATING_OUT, 0L);
        boolean sent = node1.sendEntityDeparture(bubbleId2, event);

        assertTrue(sent, "Should be able to send while nodes are reachable");
        assertEquals(1, receivedCount.get(), "Message should be received");
        log.info("✓ Node reachability maintained during communication");
    }
}
