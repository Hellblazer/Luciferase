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

import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketTransport;
import com.hellblazer.luciferase.simulation.von.Bubble;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.hellblazer.luciferase.simulation.von.transport.TestTransportFactory;

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
        // Setup: Use port 0 (OS-assigned) to avoid TOCTOU race
        var bubble1Id = UUID.randomUUID();
        var bubble2Id = UUID.randomUUID();

        var transport1 = TestTransportFactory.createTestTransport(bubble1Id, ProcessAddress.localhost("bubble1", 0));
        var transport2 = TestTransportFactory.createTestTransport(bubble2Id, ProcessAddress.localhost("bubble2", 0));
        transports.add(transport1);
        transports.add(transport2);

        transport1.listenOn(ProcessAddress.localhost("bubble1", 0));
        transport2.listenOn(ProcessAddress.localhost("bubble2", 0));
        var addr1 = transport1.getBoundAddress();
        var addr2 = transport2.getBoundAddress();
        Thread.sleep(100);  // Let servers start

        transport1.connectTo(addr2);
        transport2.connectTo(addr1);
        Thread.sleep(100);  // Let connections establish

        var bubble1 = new Bubble(bubble1Id, (byte) 10, 16, transport1);
        var bubble2 = new Bubble(bubble2Id, (byte) 10, 16, transport2);
        bubbles.add(bubble1);
        bubbles.add(bubble2);

        transport1.registerMember(bubble1.id(), addr1);
        transport1.registerMember(bubble2.id(), addr2);
        transport2.registerMember(bubble1.id(), addr1);
        transport2.registerMember(bubble2.id(), addr2);
        Thread.sleep(100);

        var proxy = new RemoteBubbleProxy(bubble2.id(), transport1, 5000);
        var position = proxy.getPosition();
        assertNotNull(position, "Position should not be null");
    }

    @Test
    void testQueryNeighbors() throws Exception {
        // Setup: Use port 0 (OS-assigned) to avoid TOCTOU race
        var bubble1Id = UUID.randomUUID();
        var bubble2Id = UUID.randomUUID();
        var bubble3Id = UUID.randomUUID();

        var transport1 = TestTransportFactory.createTestTransport(bubble1Id, ProcessAddress.localhost("bubble1", 0));
        var transport2 = TestTransportFactory.createTestTransport(bubble2Id, ProcessAddress.localhost("bubble2", 0));
        var transport3 = TestTransportFactory.createTestTransport(bubble3Id, ProcessAddress.localhost("bubble3", 0));
        transports.add(transport1);
        transports.add(transport2);
        transports.add(transport3);

        transport1.listenOn(ProcessAddress.localhost("bubble1", 0));
        transport2.listenOn(ProcessAddress.localhost("bubble2", 0));
        transport3.listenOn(ProcessAddress.localhost("bubble3", 0));
        var addr1 = transport1.getBoundAddress();
        var addr2 = transport2.getBoundAddress();
        var addr3 = transport3.getBoundAddress();
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

        bubble2.addNeighbor(bubble3.id());

        var proxy = new RemoteBubbleProxy(bubble2.id(), transport1, 5000);
        var neighbors = proxy.getNeighbors();

        assertNotNull(neighbors);
        assertEquals(1, neighbors.size());
        assertTrue(neighbors.contains(bubble3.id()));
    }

    @Test
    void testQueryTimeout() throws Exception {
        // Setup: Use port 0 (OS-assigned) to avoid TOCTOU race
        var bubble1Id = UUID.randomUUID();
        var transport1 = TestTransportFactory.createTestTransport(bubble1Id, ProcessAddress.localhost("bubble1", 0));
        transports.add(transport1);
        transport1.listenOn(ProcessAddress.localhost("bubble1", 0));

        var fakeRemoteId = UUID.randomUUID();

        var proxy = new RemoteBubbleProxy(fakeRemoteId, transport1, 500);
        var position = proxy.getPosition();
        assertNotNull(position, "Should return default value when query fails");
        assertEquals(0.0, position.getX(), 0.001, "Should return default X when no connection");
        assertEquals(0.0, position.getY(), 0.001, "Should return default Y when no connection");
        assertEquals(0.0, position.getZ(), 0.001, "Should return default Z when no connection");
    }

    @Test
    void testStaleCacheFallback() throws Exception {
        // Setup: Use port 0 (OS-assigned) to avoid TOCTOU race
        var bubble1Id = UUID.randomUUID();
        var bubble2Id = UUID.randomUUID();

        var transport1 = TestTransportFactory.createTestTransport(bubble1Id, ProcessAddress.localhost("bubble1", 0));
        var transport2 = TestTransportFactory.createTestTransport(bubble2Id, ProcessAddress.localhost("bubble2", 0));
        transports.add(transport1);
        transports.add(transport2);

        transport1.listenOn(ProcessAddress.localhost("bubble1", 0));
        transport2.listenOn(ProcessAddress.localhost("bubble2", 0));
        var addr1 = transport1.getBoundAddress();
        var addr2 = transport2.getBoundAddress();
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
        Thread.sleep(50);

        var proxy = new RemoteBubbleProxy(bubble2.id(), transport1, 500, 10000);
        var position1 = proxy.getPosition();
        assertNotNull(position1, "First query should succeed");
        var expectedX = position1.getX();

        transport2.closeAll();

        var position2 = proxy.getPosition();
        assertNotNull(position2, "Should fallback to stale cache");
        assertEquals(expectedX, position2.getX(), 0.001, "Should return same cached value");
    }
}
