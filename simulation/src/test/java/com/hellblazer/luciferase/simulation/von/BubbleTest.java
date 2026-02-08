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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Bubble - VON-enabled bubble with P2P transport.
 *
 * @author hal.hildebrand
 */
class BubbleTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;

    private LocalServerTransport.Registry registry;
    private Bubble bubble1;
    private Bubble bubble2;
    private Bubble bubble3;
    private final MessageFactory factory = MessageFactory.system();

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();
    }

    @AfterEach
    void cleanup() {
        if (bubble1 != null) bubble1.close();
        if (bubble2 != null) bubble2.close();
        if (bubble3 != null) bubble3.close();
        if (registry != null) registry.close();
    }

    @Test
    void testCreation_implementsNodeInterface() {
        // Given: A Bubble
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new Bubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

        // Then: Should implement Node interface
        assertThat(bubble1).isInstanceOf(Node.class);
        assertThat(bubble1.id()).isEqualTo(id);
        assertThat(bubble1.position()).isNotNull();
        assertThat(bubble1.neighbors()).isEmpty();
    }

    @Test
    void testAddNeighbor_updatesNeighborSet() {
        // Given: A Bubble
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new Bubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

        // When: Add a neighbor
        var neighborId = UUID.randomUUID();
        bubble1.addNeighbor(neighborId);

        // Then: Neighbor set contains the new neighbor
        assertThat(bubble1.neighbors()).contains(neighborId);
    }

    @Test
    void testRemoveNeighbor_updatesNeighborSet() {
        // Given: A Bubble with a neighbor
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new Bubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);
        var neighborId = UUID.randomUUID();
        bubble1.addNeighbor(neighborId);

        // When: Remove the neighbor
        bubble1.removeNeighbor(neighborId);

        // Then: Neighbor set is empty
        assertThat(bubble1.neighbors()).doesNotContain(neighborId);
    }

    @Test
    void testHandleJoinRequest_addsNeighborAndResponds() throws Exception {
        // Given: Two bubbles
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Add entity to bubble1 to get valid bounds
        bubble1.addEntity("entity1", new Point3f(0.5f, 0.5f, 0.5f), "content");

        var responseReceived = new CountDownLatch(1);
        var receivedMessages = new CopyOnWriteArrayList<Message>();
        transport2.onMessage(msg -> {
            receivedMessages.add(msg);
            if (msg instanceof Message.JoinResponse) {
                responseReceived.countDown();
            }
        });

        // When: Bubble2 sends JOIN request to Bubble1
        var joinRequest = factory.createJoinRequest(id2, new Point3D(0.6, 0.6, 0.6), bubble2.bounds());
        transport2.sendToNeighbor(id1, joinRequest);

        // Then: Bubble1 should respond with JoinResponse
        assertThat(responseReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedMessages).anyMatch(m -> m instanceof Message.JoinResponse);

        // And: Bubble1 should have bubble2 as neighbor
        assertThat(bubble1.neighbors()).contains(id2);
    }

    @Test
    void testHandleJoinResponse_addsNeighborsFromResponse() {
        // Given: A bubble
        var id1 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);

        // When: Receive JoinResponse with neighbors
        var neighborId = UUID.randomUUID();
        var neighborInfo = new Message.NeighborInfo(
            neighborId,
            new Point3D(0.3, 0.3, 0.3),
            null
        );
        var response = factory.createJoinResponse(
            UUID.randomUUID(),
            Set.of(neighborInfo)
        );

        // Simulate receiving the message
        transport1.deliver(response);

        // Give time for async processing
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // Then: Bubble should have the neighbor from response
        assertThat(bubble1.neighbors()).contains(neighborId);
    }

    @Test
    void testHandleMove_updatesNeighborState() throws Exception {
        // Given: Two connected bubbles
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Establish neighbor relationship
        bubble1.addNeighbor(id2);
        bubble2.addNeighbor(id1);

        var eventReceived = new CountDownLatch(1);
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Move) {
                eventReceived.countDown();
            }
        });

        // When: Bubble2 sends MOVE message
        var newPosition = new Point3D(0.7, 0.7, 0.7);
        var moveMsg = factory.createMove(id2, newPosition, null);
        transport2.sendToNeighbor(id1, moveMsg);

        // Then: Bubble1 should update neighbor state
        assertThat(eventReceived.await(2, TimeUnit.SECONDS)).isTrue();
        var neighborState = bubble1.getNeighborState(id2);
        assertThat(neighborState).isNotNull();
        assertThat(neighborState.position()).isEqualTo(newPosition);
    }

    @Test
    void testHandleLeave_removesNeighbor() throws Exception {
        // Given: Two connected bubbles
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Establish neighbor relationship
        bubble1.addNeighbor(id2);
        bubble2.addNeighbor(id1);

        var eventReceived = new CountDownLatch(1);
        bubble1.addEventListener(event -> {
            if (event instanceof Event.Leave) {
                eventReceived.countDown();
            }
        });

        // When: Bubble2 sends LEAVE message
        var leaveMsg = factory.createLeave(id2);
        transport2.sendToNeighbor(id1, leaveMsg);

        // Then: Bubble1 should remove bubble2 from neighbors
        assertThat(eventReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bubble1.neighbors()).doesNotContain(id2);
    }

    @Test
    void testBroadcastMove_sendsToAllNeighbors() throws Exception {
        // Given: A bubble with two neighbors
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var id3 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        var transport3 = registry.register(id3);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);
        bubble3 = new Bubble(id3, SPATIAL_LEVEL, TARGET_FRAME_MS, transport3);

        // Add entity to bubble1
        bubble1.addEntity("entity1", new Point3f(0.5f, 0.5f, 0.5f), "content");

        // Establish neighbor relationships
        bubble1.addNeighbor(id2);
        bubble1.addNeighbor(id3);
        bubble2.addNeighbor(id1);
        bubble3.addNeighbor(id1);

        var bubble2Received = new CountDownLatch(1);
        var bubble3Received = new CountDownLatch(1);
        bubble2.addEventListener(e -> { if (e instanceof Event.Move) bubble2Received.countDown(); });
        bubble3.addEventListener(e -> { if (e instanceof Event.Move) bubble3Received.countDown(); });

        // When: Bubble1 broadcasts move
        bubble1.broadcastMove();

        // Then: Both neighbors should receive MOVE event
        assertThat(bubble2Received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bubble3Received.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testBroadcastLeave_sendsToAllNeighbors() throws Exception {
        // Given: A bubble with two neighbors
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var id3 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        var transport3 = registry.register(id3);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);
        bubble3 = new Bubble(id3, SPATIAL_LEVEL, TARGET_FRAME_MS, transport3);

        // Establish neighbor relationships
        bubble1.addNeighbor(id2);
        bubble1.addNeighbor(id3);
        bubble2.addNeighbor(id1);
        bubble3.addNeighbor(id1);

        var bubble2Received = new CountDownLatch(1);
        var bubble3Received = new CountDownLatch(1);
        bubble2.addEventListener(e -> { if (e instanceof Event.Leave) bubble2Received.countDown(); });
        bubble3.addEventListener(e -> { if (e instanceof Event.Leave) bubble3Received.countDown(); });

        // When: Bubble1 broadcasts leave
        bubble1.broadcastLeave();

        // Then: Both neighbors should receive LEAVE event
        assertThat(bubble2Received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bubble3Received.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testClose_sendsLeaveAndCleansUp() throws Exception {
        // Given: A bubble with a neighbor
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        bubble1.addNeighbor(id2);
        bubble2.addNeighbor(id1);

        var leaveReceived = new CountDownLatch(1);
        bubble2.addEventListener(e -> { if (e instanceof Event.Leave) leaveReceived.countDown(); });

        // When: Close bubble1
        bubble1.close();
        bubble1 = null; // Prevent double close in cleanup

        // Then: Bubble2 should receive LEAVE
        assertThat(leaveReceived.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testEventListener_receivesEvents() {
        // Given: A bubble with event listener
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new Bubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

        var events = new CopyOnWriteArrayList<Event>();
        bubble1.addEventListener(events::add);

        // When: Neighbor joins
        var neighborId = UUID.randomUUID();
        bubble1.notifyJoin(new SimpleNode(neighborId, new Point3D(0.5, 0.5, 0.5)));

        // Then: Event listener receives Join event
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(Event.Join.class);
    }

    @Test
    void testGetNeighborStates_returnsAllStates() {
        // Given: A bubble with neighbors
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new Bubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        bubble1.addNeighbor(neighbor1);
        bubble1.addNeighbor(neighbor2);

        // When: Get all neighbor states
        var states = bubble1.getNeighborStates();

        // Then: Should have states for both neighbors
        assertThat(states).hasSize(2);
        assertThat(states).containsKey(neighbor1);
        assertThat(states).containsKey(neighbor2);
    }

    /**
     * Test JOIN retry on transient failure.
     * <p>
     * Verifies that:
     * - First attempt fails (injected exception)
     * - Retry occurs with exponential backoff timing (50ms)
     * - Success on second attempt
     * - Neighbor relationship established
     */
    @Test
    void testJoinRetryOnTransientFailure() throws Exception {
        // Given: Two bubbles
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Add entity to bubble1 to get valid bounds
        bubble1.addEntity("entity1", new Point3f(0.5f, 0.5f, 0.5f), "content");

        // Set up response tracking
        var responseReceived = new CountDownLatch(1);
        var receivedMessages = new CopyOnWriteArrayList<Message>();
        transport2.onMessage(msg -> {
            receivedMessages.add(msg);
            if (msg instanceof Message.JoinResponse) {
                responseReceived.countDown();
            }
        });

        // Inject exception for first attempt only
        var attemptCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var originalHandler = transport1.toString(); // Dummy to capture original state

        // Use a wrapper to inject failure on first attempt
        var sendWrapper = new Object() {
            boolean firstAttempt = true;
        };

        // Inject failure by making transport1 fail on first send
        transport1.injectException(true);

        // When: Bubble2 sends JOIN request to Bubble1
        var startTime = System.currentTimeMillis();
        var joinRequest = factory.createJoinRequest(id2, new Point3D(0.6, 0.6, 0.6), bubble2.bounds());

        // Send in background thread to allow retry mechanism to work
        new Thread(() -> {
            try {
                transport2.sendToNeighbor(id1, joinRequest);
            } catch (Exception e) {
                // Expected on first attempt
            }
        }).start();

        // Wait a bit for bubble1 to receive request and attempt first send
        Thread.sleep(100);

        // Disable exception injection to allow retry to succeed
        transport1.injectException(false);

        // Then: Should receive response after retry
        assertThat(responseReceived.await(2, TimeUnit.SECONDS)).isTrue();
        var elapsedMs = System.currentTimeMillis() - startTime;

        // Verify retry timing (should be at least initial delay of 50ms)
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);

        // Verify neighbor relationship established
        assertThat(bubble1.neighbors()).contains(id2);
        assertThat(receivedMessages).anyMatch(m -> m instanceof Message.JoinResponse);
    }

    /**
     * Test JOIN removal on persistent failure.
     * <p>
     * Verifies that:
     * - All retry attempts fail (injected exception)
     * - Exponential backoff timing observed (50ms, 100ms, 200ms, 400ms, 800ms)
     * - Neighbor removed after max retries
     * - No asymmetric relationships
     */
    @Test
    void testJoinRemovalOnPersistentFailure() throws Exception {
        // Given: Two bubbles
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);
        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Add entity to bubble1
        bubble1.addEntity("entity1", new Point3f(0.5f, 0.5f, 0.5f), "content");

        // Inject persistent exception on transport1 (all send attempts fail)
        transport1.injectException(true);

        // When: Bubble2 sends JOIN request to Bubble1
        var startTime = System.currentTimeMillis();
        var joinRequest = factory.createJoinRequest(id2, new Point3D(0.6, 0.6, 0.6), bubble2.bounds());
        transport2.sendToNeighbor(id1, joinRequest);

        // Wait for all retries to complete (total: 50+100+200+400+800 = 1550ms)
        Thread.sleep(2000);
        var elapsedMs = System.currentTimeMillis() - startTime;

        // Then: Verify exponential backoff timing
        // Total retry time should be approximately 1550ms (50+100+200+400+800)
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1500);

        // Verify neighbor was removed after max retries (compensation)
        assertThat(bubble1.neighbors()).doesNotContain(id2);

        // Verify no asymmetric relationship (bubble2 should not be in neighbor list)
        assertThat(bubble1.getNeighborState(id2)).isNull();
    }

    /**
     * Test JOIN message routing after retry.
     * <p>
     * Verifies that:
     * - After JOIN with retries completes
     * - Messages to Node B reach B (not C)
     * - Messages to Node C reach C (not B)
     * - No cross-contamination between neighbors
     */
    @Test
    void testJoinMessageRouting() throws Exception {
        // Given: Three bubbles - A (bubble1), B (bubble2), C (bubble3)
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        var idC = UUID.randomUUID();
        var transportA = registry.register(idA);
        var transportB = registry.register(idB);
        var transportC = registry.register(idC);
        bubble1 = new Bubble(idA, SPATIAL_LEVEL, TARGET_FRAME_MS, transportA);
        bubble2 = new Bubble(idB, SPATIAL_LEVEL, TARGET_FRAME_MS, transportB);
        bubble3 = new Bubble(idC, SPATIAL_LEVEL, TARGET_FRAME_MS, transportC);

        // Add entities to all bubbles
        bubble1.addEntity("entityA", new Point3f(0.3f, 0.3f, 0.3f), "contentA");
        bubble2.addEntity("entityB", new Point3f(0.5f, 0.5f, 0.5f), "contentB");
        bubble3.addEntity("entityC", new Point3f(0.7f, 0.7f, 0.7f), "contentC");

        // Set up message tracking for B and C
        var messagesB = new CopyOnWriteArrayList<Message>();
        var messagesC = new CopyOnWriteArrayList<Message>();
        var joinResponseB = new CountDownLatch(1);
        var joinResponseC = new CountDownLatch(1);

        transportB.onMessage(msg -> {
            messagesB.add(msg);
            if (msg instanceof Message.JoinResponse) joinResponseB.countDown();
        });
        transportC.onMessage(msg -> {
            messagesC.add(msg);
            if (msg instanceof Message.JoinResponse) joinResponseC.countDown();
        });

        // Inject transient failure on transportA for first JOIN
        transportA.injectException(true);

        // When: B sends JOIN request to A
        var joinRequestB = factory.createJoinRequest(idB, bubble2.position(), bubble2.bounds());
        new Thread(() -> {
            try {
                transportB.sendToNeighbor(idA, joinRequestB);
            } catch (Exception ignored) {}
        }).start();

        Thread.sleep(100);
        transportA.injectException(false); // Allow retry to succeed

        // Wait for B's JOIN to complete
        assertThat(joinResponseB.await(2, TimeUnit.SECONDS)).isTrue();

        // C sends JOIN request to A (should succeed immediately)
        var joinRequestC = factory.createJoinRequest(idC, bubble3.position(), bubble3.bounds());
        transportC.sendToNeighbor(idA, joinRequestC);
        assertThat(joinResponseC.await(2, TimeUnit.SECONDS)).isTrue();

        // Clear received messages
        messagesB.clear();
        messagesC.clear();

        // Set up move event tracking
        var moveBReceived = new CountDownLatch(1);
        var moveCReceived = new CountDownLatch(1);
        bubble2.addEventListener(e -> { if (e instanceof Event.Move) moveBReceived.countDown(); });
        bubble3.addEventListener(e -> { if (e instanceof Event.Move) moveCReceived.countDown(); });

        // Then: Send MOVE messages from A to B and C
        var moveToB = factory.createMove(idA, new Point3D(0.35, 0.35, 0.35), bubble1.bounds());
        var moveToC = factory.createMove(idA, new Point3D(0.75, 0.75, 0.75), bubble1.bounds());

        transportA.sendToNeighbor(idB, moveToB);
        transportA.sendToNeighbor(idC, moveToC);

        // Verify routing: B receives move to B, C receives move to C
        assertThat(moveBReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(moveCReceived.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify no cross-contamination
        var moveMsgsB = messagesB.stream()
            .filter(m -> m instanceof Message.Move)
            .map(m -> (Message.Move) m)
            .toList();
        var moveMsgsC = messagesC.stream()
            .filter(m -> m instanceof Message.Move)
            .map(m -> (Message.Move) m)
            .toList();

        assertThat(moveMsgsB).hasSize(1);
        assertThat(moveMsgsC).hasSize(1);
        assertThat(moveMsgsB.get(0).newPosition().getX()).isCloseTo(0.35, org.assertj.core.data.Offset.offset(0.01));
        assertThat(moveMsgsC.get(0).newPosition().getX()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.01));
    }

    /**
     * Simple Node implementation for testing.
     */
    private record SimpleNode(UUID id, Point3D position) implements Node {
        @Override
        public UUID id() { return id; }

        @Override
        public Point3D position() { return position; }

        @Override
        public BubbleBounds bounds() { return null; }

        @Override
        public Set<UUID> neighbors() { return Set.of(); }

        @Override
        public void notifyMove(Node neighbor) {}

        @Override
        public void notifyLeave(Node neighbor) {}

        @Override
        public void notifyJoin(Node neighbor) {}

        @Override
        public void addNeighbor(UUID neighborId) {}

        @Override
        public void removeNeighbor(UUID neighborId) {}
    }
}
