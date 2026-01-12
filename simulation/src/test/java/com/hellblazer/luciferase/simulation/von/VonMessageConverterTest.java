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

import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VonMessageConverter.
 * <p>
 * Tests bidirectional conversion of all VonMessage types
 * to/from TransportVonMessage wire format.
 *
 * @author hal.hildebrand
 */
class VonMessageConverterTest {

    private final VonMessageFactory factory = VonMessageFactory.system();

    @Test
    void testAckRoundTrip() {
        var ackFor = UUID.randomUUID();
        var senderId = UUID.randomUUID();
        var originalAck = factory.createAck(ackFor, senderId);

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalAck);

        assertEquals("Ack", transport.type());
        assertEquals(senderId.toString(), transport.sourceBubbleId());
        assertEquals(ackFor.toString(), transport.targetBubbleId());

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.Ack.class, recovered);
        var recoveredAck = (VonMessage.Ack) recovered;
        assertEquals(ackFor, recoveredAck.ackFor());
        assertEquals(senderId, recoveredAck.senderId());
    }

    @Test
    void testMoveRoundTrip() {
        var nodeId = UUID.randomUUID();
        var newPosition = new Point3D(10.5, 20.25, 30.75);
        var originalMove = factory.createMove(nodeId, newPosition, null);

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalMove);

        assertEquals("Move", transport.type());
        assertEquals(nodeId.toString(), transport.sourceBubbleId());
        assertEquals(10.5f, transport.posX(), 0.001f);
        assertEquals(20.25f, transport.posY(), 0.001f);
        assertEquals(30.75f, transport.posZ(), 0.001f);

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.Move.class, recovered);
        var recoveredMove = (VonMessage.Move) recovered;
        assertEquals(nodeId, recoveredMove.nodeId());
        assertEquals(10.5, recoveredMove.newPosition().getX(), 0.001);
        assertEquals(20.25, recoveredMove.newPosition().getY(), 0.001);
        assertEquals(30.75, recoveredMove.newPosition().getZ(), 0.001);
    }

    @Test
    void testLeaveRoundTrip() {
        var nodeId = UUID.randomUUID();
        var originalLeave = factory.createLeave(nodeId);

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalLeave);

        assertEquals("Leave", transport.type());
        assertEquals(nodeId.toString(), transport.sourceBubbleId());

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.Leave.class, recovered);
        var recoveredLeave = (VonMessage.Leave) recovered;
        assertEquals(nodeId, recoveredLeave.nodeId());
    }

    @Test
    void testJoinRequestRoundTrip() {
        var joinerId = UUID.randomUUID();
        var position = new Point3D(5.0, 10.0, 15.0);
        var originalJoinReq = factory.createJoinRequest(joinerId, position, null);

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalJoinReq);

        assertEquals("JoinRequest", transport.type());
        assertEquals(joinerId.toString(), transport.sourceBubbleId());
        assertEquals(5.0f, transport.posX(), 0.001f);

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.JoinRequest.class, recovered);
        var recoveredJoinReq = (VonMessage.JoinRequest) recovered;
        assertEquals(joinerId, recoveredJoinReq.joinerId());
    }

    @Test
    void testJoinResponseRoundTrip() {
        var acceptorId = UUID.randomUUID();
        var originalJoinResp = factory.createJoinResponse(acceptorId, java.util.Set.of());

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalJoinResp);

        assertEquals("JoinResponse", transport.type());
        assertEquals(acceptorId.toString(), transport.sourceBubbleId());

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.JoinResponse.class, recovered);
        var recoveredJoinResp = (VonMessage.JoinResponse) recovered;
        assertEquals(acceptorId, recoveredJoinResp.acceptorId());
    }

    @Test
    void testQueryRoundTrip() {
        var senderId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var originalQuery = factory.createQuery(senderId, targetId, "position");

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalQuery);

        assertEquals("Query", transport.type());
        assertEquals(senderId.toString(), transport.sourceBubbleId());
        assertEquals(targetId.toString(), transport.targetBubbleId());
        assertEquals("position", transport.entityId());

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.Query.class, recovered);
        var recoveredQuery = (VonMessage.Query) recovered;
        assertEquals(senderId, recoveredQuery.senderId());
        assertEquals(targetId, recoveredQuery.targetId());
        assertEquals("position", recoveredQuery.queryType());
    }

    @Test
    void testGhostSyncRoundTrip() {
        var sourceBubbleId = UUID.randomUUID();
        var ghosts = new ArrayList<VonMessage.TransportGhost>();

        var ghost1 = new VonMessage.TransportGhost(
            "entity-1",
            new javax.vecmath.Point3f(1.0f, 2.0f, 3.0f),
            "TestContent",
            "tree-1",
            1L,
            1L,
            System.currentTimeMillis()
        );
        ghosts.add(ghost1);

        var originalGhostSync = factory.createGhostSync(sourceBubbleId, ghosts, 42L);

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalGhostSync);

        assertEquals("GhostSync", transport.type());
        assertEquals(sourceBubbleId.toString(), transport.sourceBubbleId());
        assertEquals(42L, transport.bucket());
        assertNotNull(transport.ghosts());
        assertEquals(1, transport.ghosts().size());
        assertEquals("entity-1", transport.ghosts().get(0).entityId());

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.GhostSync.class, recovered);
        var recoveredGhostSync = (VonMessage.GhostSync) recovered;
        assertEquals(sourceBubbleId, recoveredGhostSync.sourceBubbleId());
        assertEquals(42L, recoveredGhostSync.bucket());
        assertEquals(1, recoveredGhostSync.ghosts().size());
        assertEquals("entity-1", recoveredGhostSync.ghosts().get(0).entityId());
    }

    @Test
    void testGhostSyncEmptyList() {
        var sourceBubbleId = UUID.randomUUID();
        var originalGhostSync = factory.createGhostSync(sourceBubbleId, java.util.List.of(), 100L);

        // Convert to transport
        var transport = VonMessageConverter.toTransport(originalGhostSync);

        assertEquals("GhostSync", transport.type());
        assertNotNull(transport.ghosts());
        assertEquals(0, transport.ghosts().size());

        // Convert back
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.GhostSync.class, recovered);
        var recoveredGhostSync = (VonMessage.GhostSync) recovered;
        assertEquals(0, recoveredGhostSync.ghosts().size());
    }

    @Test
    void testUnknownMessageTypeThrows() {
        var transport = new TransportVonMessage(
            "UnknownType",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            0f, 0f, 0f,
            "",
            System.currentTimeMillis(),
            null,
            null
        );

        assertThrows(IllegalArgumentException.class, () ->
            VonMessageConverter.fromTransport(transport)
        );
    }

    @Test
    void testMultipleGhostSync() {
        var sourceBubbleId = UUID.randomUUID();
        var ghosts = new ArrayList<VonMessage.TransportGhost>();

        for (int i = 0; i < 5; i++) {
            ghosts.add(new VonMessage.TransportGhost(
                "entity-" + i,
                new javax.vecmath.Point3f(i, i + 1, i + 2),
                "Content" + i,
                "tree-" + i,
                (long) i,
                (long) i,
                System.currentTimeMillis()
            ));
        }

        var originalGhostSync = factory.createGhostSync(sourceBubbleId, ghosts, 123L);

        // Convert to transport and back
        var transport = VonMessageConverter.toTransport(originalGhostSync);
        var recovered = VonMessageConverter.fromTransport(transport);

        assertInstanceOf(VonMessage.GhostSync.class, recovered);
        var recoveredGhostSync = (VonMessage.GhostSync) recovered;
        assertEquals(5, recoveredGhostSync.ghosts().size());

        for (int i = 0; i < 5; i++) {
            assertEquals("entity-" + i, recoveredGhostSync.ghosts().get(i).entityId());
        }
    }
}
