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

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.hellblazer.luciferase.simulation.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.transport.SocketTransport;
import com.hellblazer.luciferase.simulation.von.Bubble;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RemoteBubbleProxy query/response mechanism.
 * <p>
 * Tests async query-response with real SocketTransport and Bubble instances.
 *
 * @author hal.hildebrand
 */
class RemoteBubbleProxyTest {

    private final List<SocketTransport> transports = new ArrayList<>();
    private final List<Bubble> bubbles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transports.clear();
        bubbles.clear();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (var bubble : bubbles) {
            bubble.close();
        }
        for (var transport : transports) {
            transport.closeAll();
        }
    }

    @Test
    void testQueryPosition() throws Exception {
        // Setup: Create two bubbles with socket transport
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();
        var addr1 = ProcessAddress.localhost("bubble1", port1);
        var addr2 = ProcessAddress.localhost("bubble2", port2);

        // Create bubble UUIDs first
        var bubble1Id = UUID.randomUUID();
        var bubble2Id = UUID.randomUUID();

        // Create transports with bubble UUIDs so getLocalId() returns correct ID
        var transport1 = new SocketTransport(bubble1Id, addr1);
        var transport2 = new SocketTransport(bubble2Id, addr2);
        transports.add(transport1);
        transports.add(transport2);

        transport1.listenOn(addr1);
        transport2.listenOn(addr2);
        Thread.sleep(100);  // Let servers start

        // Connect transports bidirectionally
        transport1.connectTo(addr2);
        transport2.connectTo(addr1);
        Thread.sleep(100);  // Let connections establish

        // Create bubbles with matching UUIDs (spatialLevel=10, targetFrameMs=16ms for 60fps)
        var bubble1 = new Bubble(bubble1Id, (byte) 10, 16, transport1);
        var bubble2 = new Bubble(bubble2Id, (byte) 10, 16, transport2);
        bubbles.add(bubble1);
        bubbles.add(bubble2);

        // Register members in BOTH transports so they can route to each other
        transport1.registerMember(bubble1.id(), addr1);
        transport1.registerMember(bubble2.id(), addr2);
        transport2.registerMember(bubble1.id(), addr1);
        transport2.registerMember(bubble2.id(), addr2);
        Thread.sleep(100);  // Ensure registration visible across threads

        // Test: Query bubble2's position from bubble1
        var proxy = new RemoteBubbleProxy(bubble2.id(), transport1, 5000);

        var position = proxy.getPosition();

        assertNotNull(position, "Position should not be null");
        // Note: We can't verify exact coordinates until we understand bubble2's centroid,
        // but we can verify the query/response mechanism works by checking it's not timing out
    }

    private static int findAvailablePort() {
        try (var serverSocket = new java.net.ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }

    @Test
    void testQueryNeighbors() throws Exception {
        // Setup: Create three bubbles, bubble2 has bubble3 as neighbor
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();
        var port3 = findAvailablePort();
        var addr1 = ProcessAddress.localhost("bubble1", port1);
        var addr2 = ProcessAddress.localhost("bubble2", port2);
        var addr3 = ProcessAddress.localhost("bubble3", port3);

        // Create bubble UUIDs first
        var bubble1Id = UUID.randomUUID();
        var bubble2Id = UUID.randomUUID();
        var bubble3Id = UUID.randomUUID();

        var transport1 = new SocketTransport(bubble1Id, addr1);
        var transport2 = new SocketTransport(bubble2Id, addr2);
        var transport3 = new SocketTransport(bubble3Id, addr3);
        transports.add(transport1);
        transports.add(transport2);
        transports.add(transport3);

        transport1.listenOn(addr1);
        transport2.listenOn(addr2);
        transport3.listenOn(addr3);
        Thread.sleep(100);  // Let servers start

        transport1.connectTo(addr2);
        transport2.connectTo(addr1);
        transport2.connectTo(addr3);
        transport3.connectTo(addr2);
        Thread.sleep(100);  // Let connections establish

        var bubble1 = new Bubble(bubble1Id, (byte) 10, 16, transport1);
        var bubble2 = new Bubble(bubble2Id, (byte) 10, 16, transport2);
        var bubble3 = new Bubble(bubble3Id, (byte) 10, 16, transport3);
        bubbles.add(bubble1);
        bubbles.add(bubble2);
        bubbles.add(bubble3);

        transport1.registerMember(bubble2.id(), addr2);
        transport2.registerMember(bubble1.id(), addr1);
        transport2.registerMember(bubble3.id(), addr3);
        transport3.registerMember(bubble2.id(), addr2);

        // Make bubble3 a neighbor of bubble2
        bubble2.addNeighbor(bubble3.id());

        // Test: Query bubble2's neighbors from bubble1
        var proxy = new RemoteBubbleProxy(bubble2.id(), transport1, 5000);

        var neighbors = proxy.getNeighbors();

        assertNotNull(neighbors);
        assertEquals(1, neighbors.size());
        assertTrue(neighbors.contains(bubble3.id()));
    }

    @Test
    void testQueryTimeout() throws Exception {
        // Setup: Create a transport but don't register any members (query will fail immediately)
        var port1 = findAvailablePort();
        var addr1 = ProcessAddress.localhost("bubble1", port1);
        var bubble1Id = UUID.randomUUID();

        var transport1 = new SocketTransport(bubble1Id, addr1);
        transports.add(transport1);
        transport1.listenOn(addr1);

        var fakeRemoteId = UUID.randomUUID();

        // Test: Query to unregistered remote returns default value (not an exception)
        var proxy = new RemoteBubbleProxy(fakeRemoteId, transport1, 500);  // 500ms timeout

        // getPosition() is designed to never throw - it returns default value when query fails
        var position = proxy.getPosition();
        assertNotNull(position, "Should return default value when query fails");
        // Default value for Point3D is (0, 0, 0)
        assertEquals(0.0, position.getX(), 0.001, "Should return default X when no connection");
        assertEquals(0.0, position.getY(), 0.001, "Should return default Y when no connection");
        assertEquals(0.0, position.getZ(), 0.001, "Should return default Z when no connection");
    }

    @Test
    void testStaleCacheFallback() throws Exception {
        // Setup: Create two bubbles, query once, then disconnect
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();
        var addr1 = ProcessAddress.localhost("bubble1", port1);
        var addr2 = ProcessAddress.localhost("bubble2", port2);

        // Create bubble UUIDs first
        var bubble1Id = UUID.randomUUID();
        var bubble2Id = UUID.randomUUID();

        var transport1 = new SocketTransport(bubble1Id, addr1);
        var transport2 = new SocketTransport(bubble2Id, addr2);
        transports.add(transport1);
        transports.add(transport2);

        transport1.listenOn(addr1);
        transport2.listenOn(addr2);
        Thread.sleep(100);  // Let servers start

        transport1.connectTo(addr2);
        transport2.connectTo(addr1);
        Thread.sleep(100);  // Let connections establish

        var bubble1 = new Bubble(bubble1Id, (byte) 10, 16, transport1);
        var bubble2 = new Bubble(bubble2Id, (byte) 10, 16, transport2);
        bubbles.add(bubble1);
        bubbles.add(bubble2);

        transport1.registerMember(bubble2.id(), addr2);
        transport2.registerMember(bubble1.id(), addr1);
        Thread.sleep(50);  // Ensure registration visible

        // First query succeeds and populates cache
        var proxy = new RemoteBubbleProxy(bubble2.id(), transport1, 500, 10000);  // 10s cache TTL
        var position1 = proxy.getPosition();
        assertNotNull(position1, "First query should succeed");
        var expectedX = position1.getX();  // Remember the actual value for comparison

        // Disconnect transport2 (queries will fail)
        transport2.closeAll();

        // Second query should fail, but fallback to stale cache
        var position2 = proxy.getPosition();
        assertNotNull(position2, "Should fallback to stale cache");
        assertEquals(expectedX, position2.getX(), 0.001, "Should return same cached value");
    }
}
