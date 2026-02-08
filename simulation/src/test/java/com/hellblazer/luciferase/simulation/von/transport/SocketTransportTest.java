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

package com.hellblazer.luciferase.simulation.von.transport;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.fireflies.FirefliesMembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test SocketTransport implementation with focus on:
 * <ul>
 *   <li>Localhost enforcement (MANDATORY for Inc 6)</li>
 *   <li>Send/receive round-trip messaging</li>
 *   <li>Connection management</li>
 *   <li>Error handling</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class SocketTransportTest {

    private final List<SocketTransport> transports = new ArrayList<>();
    private final MessageFactory factory = MessageFactory.system();

    @AfterEach
    void cleanup() throws IOException {
        for (var transport : transports) {
            transport.closeAll();
        }
        transports.clear();
    }

    /**
     * MANDATORY TEST: Verify listenOn() rejects non-loopback addresses.
     * <p>
     * Inc 6 scope enforcement: Only localhost (127.0.0.1, localhost) permitted.
     */
    @Test
    void testListenOnRejectsNonLoopback() {
        var port = findAvailablePort();
        var bubbleId = UUID.randomUUID();
        var transport = TestTransportFactory.createTestTransport(
            bubbleId,
            ProcessAddress.localhost("process-1", port)
        );
        transports.add(transport);

        // Should reject 0.0.0.0 (all interfaces)
        var globalAddress = new ProcessAddress("process-1", "0.0.0.0", port);
        var ex1 = assertThrows(IllegalArgumentException.class, () -> transport.listenOn(globalAddress),
                               "Should reject 0.0.0.0 binding");
        assertTrue(ex1.getMessage().contains("localhost only"), "Error message should mention localhost constraint");

        // Should reject actual IP address
        var ipAddress = new ProcessAddress("process-1", "192.168.1.100", port);
        var ex2 = assertThrows(IllegalArgumentException.class, () -> transport.listenOn(ipAddress),
                               "Should reject non-loopback IP");
        assertTrue(ex2.getMessage().contains("localhost only"), "Error message should mention localhost constraint");
    }

    /**
     * MANDATORY TEST: Verify listenOn() accepts loopback addresses.
     */
    @Test
    void testListenOnAcceptsLoopback() throws IOException {
        var port = findAvailablePort();

        // Should accept 127.0.0.1
        var bubbleId1 = UUID.randomUUID();
        var transport1 = TestTransportFactory.createTestTransport(
            bubbleId1,
            ProcessAddress.localhost("p1", port)
        );
        transports.add(transport1);
        assertDoesNotThrow(() -> transport1.listenOn(ProcessAddress.localhost("p1", port)),
                           "Should accept 127.0.0.1");

        // Should accept "localhost"
        var port2 = findAvailablePort();
        var bubbleId2 = UUID.randomUUID();
        var transport2 = TestTransportFactory.createTestTransport(
            bubbleId2,
            new ProcessAddress("p2", "localhost", port2)
        );
        transports.add(transport2);
        assertDoesNotThrow(() -> transport2.listenOn(new ProcessAddress("p2", "localhost", port2)),
                           "Should accept localhost");
    }

    /**
     * MANDATORY TEST: Verify connectTo() rejects non-loopback addresses.
     */
    @Test
    void testConnectToRejectsNonLoopback() {
        var port = findAvailablePort();
        var bubbleId = UUID.randomUUID();
        var transport = TestTransportFactory.createTestTransport(
            bubbleId,
            ProcessAddress.localhost("process-1", port)
        );
        transports.add(transport);

        // Should reject connection to non-loopback
        var remoteAddress = new ProcessAddress("process-2", "192.168.1.100", 9999);
        var ex = assertThrows(IllegalArgumentException.class, () -> transport.connectTo(remoteAddress),
                              "Should reject non-loopback connection target");
        assertTrue(ex.getMessage().contains("localhost only"), "Error message should mention localhost constraint");
    }

    /**
     * Integration test: Send/receive message round-trip between two SocketTransports.
     */
    @Test
    void testSendReceiveRoundTrip() throws IOException, InterruptedException {
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();

        var process1Addr = ProcessAddress.localhost("process-1", port1);
        var process2Addr = ProcessAddress.localhost("process-2", port2);

        var bubbleId1 = UUID.randomUUID();
        var bubbleId2 = UUID.randomUUID();
        var transport1 = TestTransportFactory.createTestTransport(bubbleId1, process1Addr);
        var transport2 = TestTransportFactory.createTestTransport(bubbleId2, process2Addr);
        transports.add(transport1);
        transports.add(transport2);

        // Setup message capture for transport2
        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Start servers
        transport1.listenOn(process1Addr);
        transport2.listenOn(process2Addr);

        // Give servers time to start
        Thread.sleep(100);

        // Connect transport1 -> transport2
        transport1.connectTo(process2Addr);

        // Send message from transport1 to transport2
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var sentMessage = factory.createAck(uuid1, uuid2);

        // Register members so routing works
        transport1.registerMember(uuid2, process2Addr);

        transport1.sendToNeighbor(uuid2, sentMessage);

        // Receive and verify
        var receivedMessage = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedMessage, "Should receive message within 5 seconds");
        assertTrue(receivedMessage instanceof Message.Ack, "Should receive Ack message");

        var ack = (Message.Ack) receivedMessage;
        assertEquals(uuid2, ack.senderId(), "Sender ID should match");
    }

    /**
     * Test bidirectional communication.
     */
    @Test
    void testBidirectionalCommunication() throws IOException, InterruptedException {
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();

        var process1Addr = ProcessAddress.localhost("process-1", port1);
        var process2Addr = ProcessAddress.localhost("process-2", port2);

        var bubbleId1 = UUID.randomUUID();
        var bubbleId2 = UUID.randomUUID();
        var transport1 = TestTransportFactory.createTestTransport(bubbleId1, process1Addr);
        var transport2 = TestTransportFactory.createTestTransport(bubbleId2, process2Addr);
        transports.add(transport1);
        transports.add(transport2);

        // Setup message capture
        BlockingQueue<Message> received1 = new LinkedBlockingQueue<>();
        BlockingQueue<Message> received2 = new LinkedBlockingQueue<>();
        transport1.onMessage(received1::offer);
        transport2.onMessage(received2::offer);

        // Start servers
        transport1.listenOn(process1Addr);
        transport2.listenOn(process2Addr);
        Thread.sleep(100);

        // Establish bidirectional connections
        transport1.connectTo(process2Addr);
        transport2.connectTo(process1Addr);

        // Register members
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        transport1.registerMember(uuid2, process2Addr);
        transport2.registerMember(uuid1, process1Addr);

        // Send message 1 -> 2
        var msg1to2 = factory.createAck(UUID.randomUUID(), uuid1);
        transport1.sendToNeighbor(uuid2, msg1to2);

        // Send message 2 -> 1
        var msg2to1 = factory.createAck(UUID.randomUUID(), uuid2);
        transport2.sendToNeighbor(uuid1, msg2to1);

        // Verify both received
        assertNotNull(received2.poll(5, TimeUnit.SECONDS), "Transport2 should receive message");
        assertNotNull(received1.poll(5, TimeUnit.SECONDS), "Transport1 should receive message");
    }

    /**
     * Test getConnectedProcesses().
     */
    @Test
    void testGetConnectedProcesses() throws IOException, InterruptedException {
        var port1 = findAvailablePort();
        var port2 = findAvailablePort();
        var port3 = findAvailablePort();

        var addr1 = ProcessAddress.localhost("p1", port1);
        var addr2 = ProcessAddress.localhost("p2", port2);
        var addr3 = ProcessAddress.localhost("p3", port3);

        var bubbleId1 = UUID.randomUUID();
        var bubbleId2 = UUID.randomUUID();
        var bubbleId3 = UUID.randomUUID();
        var transport1 = TestTransportFactory.createTestTransport(bubbleId1, addr1);
        var transport2 = TestTransportFactory.createTestTransport(bubbleId2, addr2);
        var transport3 = TestTransportFactory.createTestTransport(bubbleId3, addr3);
        transports.add(transport1);
        transports.add(transport2);
        transports.add(transport3);

        // Start servers
        transport1.listenOn(addr1);
        transport2.listenOn(addr2);
        transport3.listenOn(addr3);
        Thread.sleep(100);

        // Initially no connections
        assertEquals(0, transport1.getConnectedProcesses().size(), "No connections initially");

        // Connect to two processes
        transport1.connectTo(addr2);
        transport1.connectTo(addr3);

        // Verify connected processes
        var connected = transport1.getConnectedProcesses();
        assertEquals(2, connected.size(), "Should have 2 connections");
        assertTrue(connected.contains(addr2), "Should include process-2");
        assertTrue(connected.contains(addr3), "Should include process-3");
    }

    /**
     * Test closeAll() properly shuts down.
     */
    @Test
    void testCloseAll() throws IOException, InterruptedException {
        var port = findAvailablePort();
        var addr = ProcessAddress.localhost("process-1", port);

        var bubbleId = UUID.randomUUID();
        var transport = TestTransportFactory.createTestTransport(bubbleId, addr);
        transports.add(transport);

        transport.listenOn(addr);
        assertTrue(transport.isConnected(), "Should be connected after listenOn");

        transport.closeAll();
        assertFalse(transport.isConnected(), "Should be disconnected after closeAll");
    }

    // NOTE: testRouteToKeyMemberRemovedRace and testRouteToKeyConcurrentMemberChurn
    // have been MIGRATED to ConcurrentMemberDirectoryTest.
    // They now use unregisterMember() API instead of reflection-based member removal.
    // See Phase 1 of NetworkTransport SRP refactoring (Luciferase-yth6).

    /**
     * Find an available port for testing.
     * <p>
     * Uses dynamic port allocation to avoid conflicts.
     *
     * @return Available port number
     */
    private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }

}
