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

import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SocketTransport implementation with focus on:
 * <ul>
 *   <li>Localhost enforcement (MANDATORY for Inc 6)</li>
 *   <li>Send/receive round-trip messaging</li>
 *   <li>Connection management</li>
 *   <li>Error handling</li>
 * </ul>
 *
 * <p>Port allocation: All tests use port 0 (OS-assigned) via listenOn(), then read the
 * actual bound port from getBoundAddress(). This eliminates the find-then-bind TOCTOU
 * race where a port is discovered free but grabbed by another process before we bind.
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
        var transport = TestTransportFactory.createTestTransport(
            UUID.randomUUID(),
            ProcessAddress.localhost("process-1", 0)
        );
        transports.add(transport);

        // Should reject 0.0.0.0 (all interfaces)
        var globalAddress = new ProcessAddress("process-1", "0.0.0.0", 0);
        var ex1 = assertThrows(IllegalArgumentException.class, () -> transport.listenOn(globalAddress),
                               "Should reject 0.0.0.0 binding");
        assertTrue(ex1.getMessage().contains("localhost only"), "Error message should mention localhost constraint");

        // Should reject actual IP address
        var ipAddress = new ProcessAddress("process-1", "192.168.1.100", 0);
        var ex2 = assertThrows(IllegalArgumentException.class, () -> transport.listenOn(ipAddress),
                               "Should reject non-loopback IP");
        assertTrue(ex2.getMessage().contains("localhost only"), "Error message should mention localhost constraint");
    }

    /**
     * MANDATORY TEST: Verify listenOn() accepts loopback addresses.
     */
    @Test
    void testListenOnAcceptsLoopback() {
        // Should accept 127.0.0.1
        var transport1 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(),
            ProcessAddress.localhost("p1", 0)
        );
        transports.add(transport1);
        assertDoesNotThrow(() -> transport1.listenOn(ProcessAddress.localhost("p1", 0)),
                           "Should accept 127.0.0.1");

        // Should accept "localhost"
        var transport2 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(),
            new ProcessAddress("p2", "localhost", 0)
        );
        transports.add(transport2);
        assertDoesNotThrow(() -> transport2.listenOn(new ProcessAddress("p2", "localhost", 0)),
                           "Should accept localhost");
    }

    /**
     * MANDATORY TEST: Verify connectTo() rejects non-loopback addresses.
     */
    @Test
    void testConnectToRejectsNonLoopback() {
        var transport = TestTransportFactory.createTestTransport(
            UUID.randomUUID(),
            ProcessAddress.localhost("process-1", 0)
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
        var transport1 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("process-1", 0));
        var transport2 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("process-2", 0));
        transports.add(transport1);
        transports.add(transport2);

        // Setup message capture for transport2
        BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        transport2.onMessage(received::offer);

        // Start servers on OS-assigned ports; getBoundAddress() gives the actual port
        transport1.listenOn(ProcessAddress.localhost("process-1", 0));
        transport2.listenOn(ProcessAddress.localhost("process-2", 0));
        var process2Addr = transport2.getBoundAddress();
        Thread.sleep(100);

        // Connect transport1 -> transport2 using the actual bound address
        transport1.connectTo(process2Addr);

        // Send message from transport1 to transport2
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var sentMessage = factory.createAck(uuid1, uuid2);
        transport1.registerMember(uuid2, process2Addr);
        transport1.sendToNeighbor(uuid2, sentMessage);

        // Receive and verify
        var receivedMessage = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedMessage, "Should receive message within 5 seconds");
        assertTrue(receivedMessage instanceof Message.Ack, "Should receive Ack message");
        assertEquals(uuid2, ((Message.Ack) receivedMessage).senderId(), "Sender ID should match");
    }

    /**
     * Test bidirectional communication.
     */
    @Test
    void testBidirectionalCommunication() throws IOException, InterruptedException {
        var transport1 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("process-1", 0));
        var transport2 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("process-2", 0));
        transports.add(transport1);
        transports.add(transport2);

        // Setup message capture
        BlockingQueue<Message> received1 = new LinkedBlockingQueue<>();
        BlockingQueue<Message> received2 = new LinkedBlockingQueue<>();
        transport1.onMessage(received1::offer);
        transport2.onMessage(received2::offer);

        // Start servers on OS-assigned ports
        transport1.listenOn(ProcessAddress.localhost("process-1", 0));
        transport2.listenOn(ProcessAddress.localhost("process-2", 0));
        var process1Addr = transport1.getBoundAddress();
        var process2Addr = transport2.getBoundAddress();
        Thread.sleep(100);

        // Establish bidirectional connections using actual bound addresses
        transport1.connectTo(process2Addr);
        transport2.connectTo(process1Addr);

        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        transport1.registerMember(uuid2, process2Addr);
        transport2.registerMember(uuid1, process1Addr);

        transport1.sendToNeighbor(uuid2, factory.createAck(UUID.randomUUID(), uuid1));
        transport2.sendToNeighbor(uuid1, factory.createAck(UUID.randomUUID(), uuid2));

        assertNotNull(received2.poll(5, TimeUnit.SECONDS), "Transport2 should receive message");
        assertNotNull(received1.poll(5, TimeUnit.SECONDS), "Transport1 should receive message");
    }

    /**
     * Test getConnectedProcesses().
     */
    @Test
    void testGetConnectedProcesses() throws IOException, InterruptedException {
        var transport1 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("p1", 0));
        var transport2 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("p2", 0));
        var transport3 = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("p3", 0));
        transports.add(transport1);
        transports.add(transport2);
        transports.add(transport3);

        // Start servers on OS-assigned ports
        transport1.listenOn(ProcessAddress.localhost("p1", 0));
        transport2.listenOn(ProcessAddress.localhost("p2", 0));
        transport3.listenOn(ProcessAddress.localhost("p3", 0));
        var addr2 = transport2.getBoundAddress();
        var addr3 = transport3.getBoundAddress();
        Thread.sleep(100);

        assertEquals(0, transport1.getConnectedProcesses().size(), "No connections initially");

        // Connect to two processes using actual bound addresses
        transport1.connectTo(addr2);
        transport1.connectTo(addr3);

        var connected = transport1.getConnectedProcesses();
        assertEquals(2, connected.size(), "Should have 2 connections");
        assertTrue(connected.contains(addr2), "Should include process-2");
        assertTrue(connected.contains(addr3), "Should include process-3");
    }

    /**
     * Test closeAll() properly shuts down.
     */
    @Test
    void testCloseAll() throws IOException {
        var transport = TestTransportFactory.createTestTransport(
            UUID.randomUUID(), ProcessAddress.localhost("process-1", 0));
        transports.add(transport);

        transport.listenOn(ProcessAddress.localhost("process-1", 0));
        assertTrue(transport.isConnected(), "Should be connected after listenOn");

        transport.closeAll();
        assertFalse(transport.isConnected(), "Should be disconnected after closeAll");
    }

    // NOTE: testRouteToKeyMemberRemovedRace and testRouteToKeyConcurrentMemberChurn
    // have been MIGRATED to ConcurrentMemberDirectoryTest.
    // They now use unregisterMember() API instead of reflection-based member removal.
    // See Phase 1 of NetworkTransport SRP refactoring (Luciferase-yth6).
}
