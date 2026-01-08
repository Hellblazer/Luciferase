/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for VONDiscoveryProtocol.
 *
 * @author hal.hildebrand
 */
class SimpleVONDiscoveryProtocolTest {

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

    @Test
    void testJoinEventEmitted() throws Exception {
        var latch = new CountDownLatch(1);
        protocol.subscribeToEvents(e -> {
            if (e instanceof Event.Join) {
                latch.countDown();
            }
        });

        var bubbleId = UUID.randomUUID();
        protocol.handleJoin(bubbleId, new Point3D(0, 0, 0));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void testMoveEventEmitted() throws Exception {
        var bubbleId = UUID.randomUUID();

        // Join first
        protocol.handleJoin(bubbleId, new Point3D(0, 0, 0));

        var latch = new CountDownLatch(1);
        protocol.subscribeToEvents(e -> {
            if (e instanceof Event.Move) {
                latch.countDown();
            }
        });

        protocol.handleMove(bubbleId, new Point3D(10, 10, 10));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void testLeaveEventEmitted() throws Exception {
        var bubbleId = UUID.randomUUID();

        // Join first
        protocol.handleJoin(bubbleId, new Point3D(0, 0, 0));

        var latch = new CountDownLatch(1);
        protocol.subscribeToEvents(e -> {
            if (e instanceof Event.Leave) {
                latch.countDown();
            }
        });

        protocol.handleLeave(bubbleId);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void testMessageOrdering() throws Exception {
        var coordinatorId = UUID.randomUUID();
        coordinator.registerProcess(coordinatorId, List.of());

        // Create messages with sequence numbers
        var msg1 = new TopologyUpdateMessage(coordinatorId, java.util.Map.of(), 1L, System.currentTimeMillis());
        var msg2 = new TopologyUpdateMessage(coordinatorId, java.util.Map.of(), 2L, System.currentTimeMillis());

        // Process in order
        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg1));
        assertDoesNotThrow(() -> protocol.processTopologyUpdate(msg2));
    }

    @Test
    void testGetNeighborsForUnknownBubble() {
        var neighbors = protocol.getNeighbors(UUID.randomUUID());
        assertNotNull(neighbors);
        assertTrue(neighbors.isEmpty());
    }

    @Test
    void testSubscribeUnsubscribe() {
        java.util.function.Consumer<Event> subscriber = (Event e) -> {};

        protocol.subscribeToEvents(subscriber);
        protocol.unsubscribeFromEvents(subscriber);

        // Should not crash
        protocol.handleJoin(UUID.randomUUID(), new Point3D(0, 0, 0));
    }

    @Test
    void testShutdown() {
        assertDoesNotThrow(() -> protocol.shutdown());
    }
}
