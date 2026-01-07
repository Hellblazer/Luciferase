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
 * Tests for VonBubble - VON-enabled bubble with P2P transport.
 *
 * @author hal.hildebrand
 */
class VonBubbleTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;

    private LocalServerTransport.Registry registry;
    private VonBubble bubble1;
    private VonBubble bubble2;
    private VonBubble bubble3;

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
        // Given: A VonBubble
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new VonBubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

        // Then: Should implement Node interface
        assertThat(bubble1).isInstanceOf(Node.class);
        assertThat(bubble1.id()).isEqualTo(id);
        assertThat(bubble1.position()).isNotNull();
        assertThat(bubble1.neighbors()).isEmpty();
    }

    @Test
    void testAddNeighbor_updatesNeighborSet() {
        // Given: A VonBubble
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new VonBubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

        // When: Add a neighbor
        var neighborId = UUID.randomUUID();
        bubble1.addNeighbor(neighborId);

        // Then: Neighbor set contains the new neighbor
        assertThat(bubble1.neighbors()).contains(neighborId);
    }

    @Test
    void testRemoveNeighbor_updatesNeighborSet() {
        // Given: A VonBubble with a neighbor
        var id = UUID.randomUUID();
        var transport = registry.register(id);
        bubble1 = new VonBubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);
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
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new VonBubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        // Add entity to bubble1 to get valid bounds
        bubble1.addEntity("entity1", new Point3f(0.5f, 0.5f, 0.5f), "content");

        var responseReceived = new CountDownLatch(1);
        var receivedMessages = new CopyOnWriteArrayList<VonMessage>();
        transport2.onMessage(msg -> {
            receivedMessages.add(msg);
            if (msg instanceof VonMessage.JoinResponse) {
                responseReceived.countDown();
            }
        });

        // When: Bubble2 sends JOIN request to Bubble1
        var joinRequest = new VonMessage.JoinRequest(id2, new Point3D(0.6, 0.6, 0.6), bubble2.bounds());
        transport2.sendToNeighbor(id1, joinRequest);

        // Then: Bubble1 should respond with JoinResponse
        assertThat(responseReceived.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedMessages).anyMatch(m -> m instanceof VonMessage.JoinResponse);

        // And: Bubble1 should have bubble2 as neighbor
        assertThat(bubble1.neighbors()).contains(id2);
    }

    @Test
    void testHandleJoinResponse_addsNeighborsFromResponse() {
        // Given: A bubble
        var id1 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);

        // When: Receive JoinResponse with neighbors
        var neighborId = UUID.randomUUID();
        var neighborInfo = new VonMessage.NeighborInfo(
            neighborId,
            new Point3D(0.3, 0.3, 0.3),
            null
        );
        var response = new VonMessage.JoinResponse(
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
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new VonBubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

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
        var moveMsg = new VonMessage.Move(id2, newPosition, null);
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
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new VonBubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

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
        var leaveMsg = new VonMessage.Leave(id2);
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
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new VonBubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);
        bubble3 = new VonBubble(id3, SPATIAL_LEVEL, TARGET_FRAME_MS, transport3);

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
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new VonBubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);
        bubble3 = new VonBubble(id3, SPATIAL_LEVEL, TARGET_FRAME_MS, transport3);

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
        bubble1 = new VonBubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new VonBubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

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
        bubble1 = new VonBubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

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
        bubble1 = new VonBubble(id, SPATIAL_LEVEL, TARGET_FRAME_MS, transport);

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
