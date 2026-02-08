/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying SocketTransport composition of MemberDirectory and ConnectionManager.
 * <p>
 * Tests end-to-end flow: member registration → connection → message send → receipt
 *
 * @author hal.hildebrand
 */
class SocketTransportCompositionTest {

    private final MessageFactory factory = MessageFactory.system();
    private SocketTransport transport1;
    private SocketTransport transport2;

    @AfterEach
    void tearDown() throws Exception {
        if (transport1 != null) {
            transport1.close();
        }
        if (transport2 != null) {
            transport2.close();
        }
    }

    /**
     * Verify that MemberDirectory, ConnectionManager, and SocketTransport work together
     * to enable end-to-end message delivery.
     */
    @Test
    void testCompositionIntegration() throws Exception {
        // Create two transports with Fireflies infrastructure
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        var addr1 = ProcessAddress.localhost("p1", findAvailablePort());
        var addr2 = ProcessAddress.localhost("p2", findAvailablePort());

        transport1 = TestTransportFactory.createTestTransport(uuid1, addr1);
        transport2 = TestTransportFactory.createTestTransport(uuid2, addr2);

        // Phase 1: Verify MemberDirectory works (register and lookup)
        transport1.registerMember(uuid2, addr2);
        var memberInfo = transport1.lookupMember(uuid2);
        assertTrue(memberInfo.isPresent(), "Member should be registered");
        assertEquals(uuid2, memberInfo.get().nodeId(), "Member ID should match");

        // Phase 2: Verify ConnectionManager works (listen and connect)
        assertFalse(transport1.isConnected(), "Not running before listenOn/connectTo");

        transport1.listenOn(addr1);
        assertTrue(transport1.isConnected(), "Running after listenOn (bug fix)");

        transport2.listenOn(addr2);
        transport1.connectTo(addr2);
        assertTrue(transport1.isConnected(), "Still running after connectTo");

        // Phase 3: Verify end-to-end messaging works
        var received = new AtomicReference<Message>();
        transport2.onMessage(received::set);

        // Register transport1 in transport2's directory so it can send back
        transport2.registerMember(uuid1, addr1);
        transport2.connectTo(addr1);

        // Give connections time to establish
        Thread.sleep(100);

        // Send message from transport1 to transport2
        var testMessage = factory.createAck(uuid1, uuid2);
        transport1.sendToNeighbor(uuid2, testMessage);

        // Wait for message receipt
        Thread.sleep(100);

        // Verify receipt - composition test just needs to verify end-to-end delivery
        assertNotNull(received.get(), "Message should be received via composition of MemberDirectory + ConnectionManager");
        assertTrue(received.get() instanceof Message.Ack, "Message type preserved through transport");
    }

    /**
     * Verify that composition components are properly isolated.
     * MemberDirectory operations should not affect ConnectionManager and vice versa.
     */
    @Test
    void testComponentIsolation() throws Exception {
        var uuid1 = UUID.randomUUID();
        var addr1 = ProcessAddress.localhost("p1", findAvailablePort());

        transport1 = TestTransportFactory.createTestTransport(uuid1, addr1);

        // MemberDirectory operation should work before connection
        var uuid2 = UUID.randomUUID();
        var addr2 = ProcessAddress.localhost("p2", findAvailablePort());
        transport1.registerMember(uuid2, addr2);

        assertTrue(transport1.lookupMember(uuid2).isPresent(), "Member registration independent of connection");
        assertFalse(transport1.isConnected(), "Not connected yet");

        // ConnectionManager operation should work without members
        transport1.listenOn(addr1);
        assertTrue(transport1.isConnected(), "Connection independent of members");
        assertTrue(transport1.lookupMember(uuid2).isPresent(), "Member still registered");
    }

    private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
}
