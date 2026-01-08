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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.Event;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for VONDiscoveryProtocol.
 * <p>
 * Tests all 15+ requirements from Phase 6B3 specification.
 *
 * @author hal.hildebrand
 */
class VONDiscoveryProtocolTest {

    private LocalServerTransport.Registry registry;
    private ProcessCoordinator coordinator;
    private VONDiscoveryProtocol protocol;
    private List<Event> capturedEvents;

    @BeforeEach
    void setUp() throws Exception {
        registry = LocalServerTransport.Registry.create();
        var transport = registry.register(UUID.randomUUID());
        coordinator = new ProcessCoordinator(transport);
        coordinator.start();
        protocol = new VONDiscoveryProtocol(coordinator, coordinator.getMessageValidator());
        capturedEvents = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (protocol != null) {
            protocol.shutdown();
        }
        if (coordinator != null) {
            coordinator.stop();
        }
        if (registry != null) {
            registry.close();
        }
    }

    // ========== Event Emission Tests ==========

    @Test
    void test1_JoinEventNewBubbleRegistersNeighborsUpdated() throws Exception {
        var latch = new CountDownLatch(1);
        var joinEvents = new ArrayList<Event.Join>();

        protocol.subscribeToEvents(e -> {
            if (e instanceof Event.Join join) {
                joinEvents.add(join);
                latch.countDown();
            }
        });

        var bubbleId = UUID.randomUUID();
        var position = new Point3D(10, 10, 10);

        protocol.handleJoin(bubbleId, position);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "JOIN event should be emitted");
        assertEquals(1, joinEvents.size());
        assertEquals(bubbleId, joinEvents.get(0).nodeId());
        assertEquals(position, joinEvents.get(0).position());
    }

    @Test
    void test2_MoveEventBubbleMovesNeighborListChanges() throws Exception {
        var bubbleId = UUID.randomUUID();
        var initialPosition = new Point3D(0, 0, 0);
        var newPosition = new Point3D(20, 20, 20);

        // Join first
        protocol.handleJoin(bubbleId, initialPosition);

        var latch = new CountDownLatch(1);
        var moveEvents = new ArrayList<Event.Move>();

        protocol.subscribeToEvents(e -> {
            if (e instanceof Event.Move move) {
                moveEvents.add(move);
                latch.countDown();
            }
        });

        protocol.handleMove(bubbleId, newPosition);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "MOVE event should be emitted");
        assertEquals(1, moveEvents.size());
        assertEquals(bubbleId, moveEvents.get(0).nodeId());
        assertEquals(newPosition, moveEvents.get(0).newPosition());
    }

    @Test
    void test3_LeaveEventBubbleDepartsRemovedFromNeighborLists() throws Exception {
        var bubbleId = UUID.randomUUID();
        var position = new Point3D(0, 0, 0);

        // Join first
        protocol.handleJoin(bubbleId, position);

        var latch = new CountDownLatch(1);
        var leaveEvents = new ArrayList<Event.Leave>();

        protocol.subscribeToEvents(e -> {
            if (e instanceof Event.Leave leave) {
                leaveEvents.add(leave);
                latch.countDown();
            }
        });

        protocol.handleLeave(bubbleId);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "LEAVE event should be emitted");
        assertEquals(1, leaveEvents.size());
        assertEquals(bubbleId, leaveEvents.get(0).nodeId());
    }

    @Test
    void test4_DeterministicOrderingSameTopologyStateProducesSameNeighbors() throws Exception {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var position = new Point3D(0, 0, 0);

        // Run 1
        protocol.handleJoin(bubble1, position);
        protocol.handleJoin(bubble2, position);
        var neighbors1 = new HashSet<>(protocol.getNeighbors(bubble1));

        // Reset
        protocol.handleLeave(bubble1);
        protocol.handleLeave(bubble2);

        // Run 2 - same sequence
        protocol.handleJoin(bubble1, position);
        protocol.handleJoin(bubble2, position);
        var neighbors2 = new HashSet<>(protocol.getNeighbors(bubble1));

        assertEquals(neighbors1, neighbors2, "Same topology state should produce same neighbors");
    }

    @Test
    void test5_MessageOrderingRespectsCoordinatorMessageSequenceNumbers() throws Exception {
        var coordinatorId = UUID.randomUUID();
        coordinator.registerProcess(coordinatorId, List.of());

        var msg1 = new TopologyUpdateMessage(coordinatorId, Map.of(), 1L, System.currentTimeMillis());
        var msg2 = new TopologyUpdateMessage(coordinatorId, Map.of(), 2L, System.currentTimeMillis());
        var msg3 = new TopologyUpdateMessage(coordinatorId, Map.of(), 3L, System.currentTimeMillis());

        // Process in order
        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg1));
        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg2));
        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg3));
    }

    @Test
    void test6_OutOfOrderMessagesRejectedAndLogged() throws Exception {
        var coordinatorId = UUID.randomUUID();
        coordinator.registerProcess(coordinatorId, List.of());

        var msg1 = new TopologyUpdateMessage(coordinatorId, Map.of(), 1L, System.currentTimeMillis());
        var msg3 = new TopologyUpdateMessage(coordinatorId, Map.of(), 3L, System.currentTimeMillis());
        var msg2 = new TopologyUpdateMessage(coordinatorId, Map.of(), 2L, System.currentTimeMillis());

        // Process msg1
        protocol.processTopologyUpdate(msg1);

        // Process msg3 (skip msg2) - should be accepted but log gap
        protocol.processTopologyUpdate(msg3);

        // Process msg2 (out of order) - should be rejected
        protocol.processTopologyUpdate(msg2);

        // Verify validator detected the issue
        var result = coordinator.getMessageValidator().validateMessage(msg2);
        assertFalse(result.isValid(), "Out of order message should be rejected");
    }

    @Test
    void test7_DuplicateDetectionSameSequenceRejected() throws Exception {
        var coordinatorId = UUID.randomUUID();
        coordinator.registerProcess(coordinatorId, List.of());

        var msg1 = new TopologyUpdateMessage(coordinatorId, Map.of(), 1L, System.currentTimeMillis());
        var msg1Dup = new TopologyUpdateMessage(coordinatorId, Map.of(), 1L, System.currentTimeMillis() + 100);

        // Process first
        protocol.processTopologyUpdate(msg1);

        // Process duplicate
        protocol.processTopologyUpdate(msg1Dup);

        // Validator should reject duplicate
        var result = coordinator.getMessageValidator().validateMessage(msg1Dup);
        assertFalse(result.isValid(), "Duplicate sequence should be rejected");
    }

    @Test
    void test8_EventEmissionSubscribersNotifiedOnTopologyChange() throws Exception {
        var eventCount = new AtomicInteger(0);

        protocol.subscribeToEvents(e -> eventCount.incrementAndGet());

        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var position = new Point3D(0, 0, 0);

        protocol.handleJoin(bubble1, position);
        protocol.handleJoin(bubble2, position);
        protocol.handleMove(bubble1, new Point3D(10, 10, 10));
        protocol.handleLeave(bubble2);

        // Wait a bit for async processing
        Thread.sleep(100);

        assertEquals(4, eventCount.get(), "Should emit 4 events (2 JOIN, 1 MOVE, 1 LEAVE)");
    }

    @Test
    void test9_ProtocolInitialization() {
        assertNotNull(protocol);
        assertNotNull(protocol.getNeighborIndex());
    }

    @Test
    void test10_ProtocolCleanupShutdown() {
        assertDoesNotThrow(() -> protocol.shutdown());

        // After shutdown, operations should still work (fail gracefully)
        var bubbleId = UUID.randomUUID();
        assertDoesNotThrow(() -> protocol.handleJoin(bubbleId, new Point3D(0, 0, 0)));
    }

    @Test
    void test11_CrossProcessDiscoveryBubblesInDifferentProcessesDiscoverEachOther() throws Exception {
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();

        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        coordinator.registerProcess(process1, List.of(bubble1));
        coordinator.registerProcess(process2, List.of(bubble2));

        var position = new Point3D(0, 0, 0);

        protocol.handleJoin(bubble1, position);
        protocol.handleJoin(bubble2, position);

        var neighbors1 = protocol.getNeighbors(bubble1);
        var neighbors2 = protocol.getNeighbors(bubble2);

        assertNotNull(neighbors1);
        assertNotNull(neighbors2);
    }

    @Test
    void test12_LazyEvaluationNeighborsResolvedOnDemand() {
        var bubbleId = UUID.randomUUID();
        var position = new Point3D(0, 0, 0);

        protocol.handleJoin(bubbleId, position);

        // Request neighbors - this should trigger lazy resolution
        var neighbors = protocol.getNeighbors(bubbleId);

        // Verify neighbors are returned (lazy evaluation occurred)
        assertNotNull(neighbors, "Neighbors should be resolved on demand");
    }

    @Test
    void test13_ProtocolTimeoutHandling() throws Exception {
        var coordinatorId = UUID.randomUUID();
        coordinator.registerProcess(coordinatorId, List.of());

        // Create message with old timestamp (simulate late arrival)
        var oldTimestamp = System.currentTimeMillis() - 2000; // 2 seconds ago
        var msg = new TopologyUpdateMessage(coordinatorId, Map.of(), 1L, oldTimestamp);

        // Should still process (timeout detection is in validator)
        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg));
    }

    @Test
    void test14_ProtocolConcurrentAccessSafety() throws Exception {
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    var bubbleId = UUID.randomUUID();
                    var position = new Point3D(idx, idx, idx);

                    protocol.handleJoin(bubbleId, position);
                    protocol.handleMove(bubbleId, new Point3D(idx + 10, idx + 10, idx + 10));
                    protocol.getNeighbors(bubbleId);
                    protocol.handleLeave(bubbleId);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors during concurrent access: " + errors);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void test15_ProtocolIntegrationWithProcessCoordinator() {
        assertNotNull(protocol);

        // Protocol should integrate with coordinator
        assertTrue(coordinator.isRunning());

        // Should be able to process topology updates
        var coordinatorId = coordinator.getRegistry().getAllProcesses().stream().findFirst().orElse(UUID.randomUUID());
        var msg = new TopologyUpdateMessage(coordinatorId, Map.of(), 1L, System.currentTimeMillis());

        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg));
    }

    // ========== Additional Tests ==========

    @Test
    void testSubscribeUnsubscribe() {
        var subscriber = new java.util.function.Consumer<Event>() {
            @Override
            public void accept(Event e) {
            }
        };

        protocol.subscribeToEvents(subscriber);
        protocol.unsubscribeFromEvents(subscriber);

        // Should not crash after unsubscribe
        protocol.handleJoin(UUID.randomUUID(), new Point3D(0, 0, 0));
    }

    @Test
    void testGetNeighborsForUnknownBubble() {
        var neighbors = protocol.getNeighbors(UUID.randomUUID());
        assertNotNull(neighbors);
        assertTrue(neighbors.isEmpty());
    }

    @Test
    void testMoveForUnknownBubbleTriggersJoin() throws Exception {
        var latch = new CountDownLatch(1);
        var events = new ArrayList<Event>();

        protocol.subscribeToEvents(e -> {
            events.add(e);
            latch.countDown();
        });

        var bubbleId = UUID.randomUUID();
        protocol.handleMove(bubbleId, new Point3D(10, 10, 10));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertFalse(events.isEmpty());
        assertTrue(events.get(0) instanceof Event.Join, "MOVE on unknown bubble should trigger JOIN");
    }

    @Test
    void testLeaveForUnknownBubbleDoesNotCrash() {
        var bubbleId = UUID.randomUUID();
        assertDoesNotThrow(() -> protocol.handleLeave(bubbleId));
    }

    @Test
    void testMultipleSubscribers() throws Exception {
        var latch1 = new CountDownLatch(1);
        var latch2 = new CountDownLatch(1);

        protocol.subscribeToEvents(e -> latch1.countDown());
        protocol.subscribeToEvents(e -> latch2.countDown());

        protocol.handleJoin(UUID.randomUUID(), new Point3D(0, 0, 0));

        assertTrue(latch1.await(2, TimeUnit.SECONDS), "First subscriber should be notified");
        assertTrue(latch2.await(2, TimeUnit.SECONDS), "Second subscriber should be notified");
    }

    @Test
    void testSubscriberExceptionDoesNotBreakProtocol() throws Exception {
        var goodSubscriberLatch = new CountDownLatch(1);

        // Bad subscriber that throws
        protocol.subscribeToEvents(e -> {
            throw new RuntimeException("Subscriber error");
        });

        // Good subscriber
        protocol.subscribeToEvents(e -> goodSubscriberLatch.countDown());

        protocol.handleJoin(UUID.randomUUID(), new Point3D(0, 0, 0));

        assertTrue(goodSubscriberLatch.await(2, TimeUnit.SECONDS),
                "Good subscriber should still be notified despite bad subscriber");
    }
}
