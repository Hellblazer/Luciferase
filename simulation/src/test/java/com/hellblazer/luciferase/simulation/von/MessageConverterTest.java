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

import com.hellblazer.luciferase.simulation.distributed.migration.EntitySnapshot;
import com.hellblazer.luciferase.simulation.distributed.migration.IdempotencyToken;
import javax.vecmath.Point3d;
import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.simulation.von.MigrationProtocolMessages;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageConverter.
 * <p>
 * Tests bidirectional conversion of all Message types
 * to/from TransportVonMessage wire format.
 *
 * @author hal.hildebrand
 */
class MessageConverterTest {

    private final MessageFactory factory = MessageFactory.system();

    @Test
    void testAckRoundTrip() {
        var ackFor = UUID.randomUUID();
        var senderId = UUID.randomUUID();
        var originalAck = factory.createAck(ackFor, senderId);

        // Convert to transport
        var transport = MessageConverter.toTransport(originalAck);

        assertEquals("Ack", transport.type());
        assertEquals(senderId.toString(), transport.sourceBubbleId());
        assertEquals(ackFor.toString(), transport.targetBubbleId());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.Ack.class, recovered);
        var recoveredAck = (Message.Ack) recovered;
        assertEquals(ackFor, recoveredAck.ackFor());
        assertEquals(senderId, recoveredAck.senderId());
    }

    @Test
    void testMoveRoundTrip() {
        var nodeId = UUID.randomUUID();
        var newPosition = new Point3d(10.5, 20.25, 30.75);
        var originalMove = factory.createMove(nodeId, newPosition, null);

        // Convert to transport
        var transport = MessageConverter.toTransport(originalMove);

        assertEquals("Move", transport.type());
        assertEquals(nodeId.toString(), transport.sourceBubbleId());
        assertEquals(10.5f, transport.posX(), 0.001f);
        assertEquals(20.25f, transport.posY(), 0.001f);
        assertEquals(30.75f, transport.posZ(), 0.001f);

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.Move.class, recovered);
        var recoveredMove = (Message.Move) recovered;
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
        var transport = MessageConverter.toTransport(originalLeave);

        assertEquals("Leave", transport.type());
        assertEquals(nodeId.toString(), transport.sourceBubbleId());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.Leave.class, recovered);
        var recoveredLeave = (Message.Leave) recovered;
        assertEquals(nodeId, recoveredLeave.nodeId());
    }

    @Test
    void testJoinRequestRoundTrip() {
        var joinerId = UUID.randomUUID();
        var position = new Point3d(5.0, 10.0, 15.0);
        var originalJoinReq = factory.createJoinRequest(joinerId, position, null);

        // Convert to transport
        var transport = MessageConverter.toTransport(originalJoinReq);

        assertEquals("JoinRequest", transport.type());
        assertEquals(joinerId.toString(), transport.sourceBubbleId());
        assertEquals(5.0f, transport.posX(), 0.001f);

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.JoinRequest.class, recovered);
        var recoveredJoinReq = (Message.JoinRequest) recovered;
        assertEquals(joinerId, recoveredJoinReq.joinerId());
    }

    @Test
    void testJoinResponseRoundTrip() {
        var acceptorId = UUID.randomUUID();
        var originalJoinResp = factory.createJoinResponse(acceptorId, java.util.Set.of());

        // Convert to transport
        var transport = MessageConverter.toTransport(originalJoinResp);

        assertEquals("JoinResponse", transport.type());
        assertEquals(acceptorId.toString(), transport.sourceBubbleId());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.JoinResponse.class, recovered);
        var recoveredJoinResp = (Message.JoinResponse) recovered;
        assertEquals(acceptorId, recoveredJoinResp.acceptorId());
    }

    @Test
    void testJoinResponseWithNeighborsRoundTrip() {
        var acceptorId = UUID.randomUUID();

        // Create neighbor set with multiple neighbors
        var neighbors = java.util.Set.of(
            new Message.NeighborInfo(
                UUID.randomUUID(),
                new Point3d(1.5, 2.5, 3.5),
                null  // Phase 6A: bounds not transmitted
            ),
            new Message.NeighborInfo(
                UUID.randomUUID(),
                new Point3d(10.0, 20.0, 30.0),
                null  // Phase 6A: bounds not transmitted
            ),
            new Message.NeighborInfo(
                UUID.randomUUID(),
                new Point3d(100.25, 200.5, 300.75),
                null  // Phase 6A: bounds not transmitted
            )
        );

        var originalJoinResp = factory.createJoinResponse(acceptorId, neighbors);

        // Convert to transport
        var transport = MessageConverter.toTransport(originalJoinResp);

        assertEquals("JoinResponse", transport.type());
        assertEquals(acceptorId.toString(), transport.sourceBubbleId());
        assertNotNull(transport.neighbors());
        assertEquals(3, transport.neighbors().size());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.JoinResponse.class, recovered);
        var recoveredJoinResp = (Message.JoinResponse) recovered;
        assertEquals(acceptorId, recoveredJoinResp.acceptorId());
        assertNotNull(recoveredJoinResp.neighbors());
        assertEquals(3, recoveredJoinResp.neighbors().size());

        // Verify each neighbor's data is preserved
        var originalList = new java.util.ArrayList<>(neighbors);
        var recoveredList = new java.util.ArrayList<>(recoveredJoinResp.neighbors());

        // Sort both lists by nodeId for comparison
        originalList.sort((a, b) -> a.nodeId().compareTo(b.nodeId()));
        recoveredList.sort((a, b) -> a.nodeId().compareTo(b.nodeId()));

        for (int i = 0; i < 3; i++) {
            var orig = originalList.get(i);
            var recv = recoveredList.get(i);

            assertEquals(orig.nodeId(), recv.nodeId());
            assertEquals(orig.position().getX(), recv.position().getX(), 0.001);
            assertEquals(orig.position().getY(), recv.position().getY(), 0.001);
            assertEquals(orig.position().getZ(), recv.position().getZ(), 0.001);
            assertNull(recv.bounds());  // Phase 6A: bounds not transmitted
        }
    }

    @Test
    void testQueryRoundTrip() {
        var senderId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var originalQuery = factory.createQuery(senderId, targetId, "position");

        // Convert to transport
        var transport = MessageConverter.toTransport(originalQuery);

        assertEquals("Query", transport.type());
        assertEquals(senderId.toString(), transport.sourceBubbleId());
        assertEquals(targetId.toString(), transport.targetBubbleId());
        assertEquals("position", transport.entityId());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.Query.class, recovered);
        var recoveredQuery = (Message.Query) recovered;
        assertEquals(senderId, recoveredQuery.senderId());
        assertEquals(targetId, recoveredQuery.targetId());
        assertEquals("position", recoveredQuery.queryType());
    }

    @Test
    void testGhostSyncRoundTrip() {
        var sourceBubbleId = UUID.randomUUID();
        var ghosts = new ArrayList<Message.TransportGhost>();

        var ghost1 = new Message.TransportGhost(
            "entity-1",
            new javax.vecmath.Point3f(1.0f, 2.0f, 3.0f),
            "TestContent",
            "test-value",
            "tree-1",
            1L,
            1L,
            System.currentTimeMillis()
        );
        ghosts.add(ghost1);

        var originalGhostSync = factory.createGhostSync(sourceBubbleId, ghosts, 42L);

        // Convert to transport
        var transport = MessageConverter.toTransport(originalGhostSync);

        assertEquals("GhostSync", transport.type());
        assertEquals(sourceBubbleId.toString(), transport.sourceBubbleId());
        assertEquals(42L, transport.bucket());
        assertNotNull(transport.ghosts());
        assertEquals(1, transport.ghosts().size());
        assertEquals("entity-1", transport.ghosts().get(0).entityId());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.GhostSync.class, recovered);
        var recoveredGhostSync = (Message.GhostSync) recovered;
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
        var transport = MessageConverter.toTransport(originalGhostSync);

        assertEquals("GhostSync", transport.type());
        assertNotNull(transport.ghosts());
        assertEquals(0, transport.ghosts().size());

        // Convert back
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.GhostSync.class, recovered);
        var recoveredGhostSync = (Message.GhostSync) recovered;
        assertEquals(0, recoveredGhostSync.ghosts().size());
    }

    // ---- Migration subtype round-trips (Luciferase-7wzml.180) ----
    // Each test asserts exact field-by-field equality to pin the slot contract
    // (positional-overload reuse across subtypes — see MessageConverter comments).

    @Test
    void testPrepareResponseRoundTrip() {
        var txId = UUID.randomUUID();
        var destProcessId = UUID.randomUUID();
        var msg = new MigrationProtocolMessages.PrepareResponse(
            txId, true, null, destProcessId, 5000L);

        var recovered = (MigrationProtocolMessages.PrepareResponse)
            MessageConverter.fromTransport(MessageConverter.toTransport(msg));

        assertEquals(txId, recovered.transactionId());
        assertTrue(recovered.success());
        assertNull(recovered.reason());
        assertEquals(destProcessId, recovered.destProcessId());
        assertEquals(5000L, recovered.timestamp());
    }

    @Test
    void testPrepareResponseFailureRoundTrip() {
        var txId = UUID.randomUUID();
        var msg = new MigrationProtocolMessages.PrepareResponse(
            txId, false, "capacity full", null, 6000L);

        var recovered = (MigrationProtocolMessages.PrepareResponse)
            MessageConverter.fromTransport(MessageConverter.toTransport(msg));

        assertEquals(txId, recovered.transactionId());
        assertFalse(recovered.success());
        assertEquals("capacity full", recovered.reason());
        assertNull(recovered.destProcessId());
    }

    @Test
    void testCommitRequestRoundTrip() {
        var txId = UUID.randomUUID();
        var msg = new MigrationProtocolMessages.CommitRequest(txId, true, 7000L);

        var recovered = (MigrationProtocolMessages.CommitRequest)
            MessageConverter.fromTransport(MessageConverter.toTransport(msg));

        assertEquals(txId, recovered.transactionId());
        assertTrue(recovered.confirmed());
        assertEquals(7000L, recovered.timestamp());
    }

    @Test
    void testCommitResponseRoundTrip() {
        var txId = UUID.randomUUID();
        var msg = new MigrationProtocolMessages.CommitResponse(txId, true, null, 8000L);

        var recovered = (MigrationProtocolMessages.CommitResponse)
            MessageConverter.fromTransport(MessageConverter.toTransport(msg));

        assertEquals(txId, recovered.transactionId());
        assertTrue(recovered.success());
        assertNull(recovered.reason());
        assertEquals(8000L, recovered.timestamp());
    }

    @Test
    void testAbortRequestRoundTrip() {
        var txId = UUID.randomUUID();
        var msg = new MigrationProtocolMessages.AbortRequest(txId, "destination unreachable", 9000L);

        var recovered = (MigrationProtocolMessages.AbortRequest)
            MessageConverter.fromTransport(MessageConverter.toTransport(msg));

        assertEquals(txId, recovered.transactionId());
        assertEquals("destination unreachable", recovered.reason());
        assertEquals(9000L, recovered.timestamp());
    }

    @Test
    void testAbortResponseRoundTrip() {
        // Slot reuse: rolledBack is stored in the 'success' slot of TransportMigrationMessage.
        // Verify the round-trip preserves the boolean correctly.
        var txId = UUID.randomUUID();
        var msg = new MigrationProtocolMessages.AbortResponse(txId, true, 10000L);

        var recovered = (MigrationProtocolMessages.AbortResponse)
            MessageConverter.fromTransport(MessageConverter.toTransport(msg));

        assertEquals(txId, recovered.transactionId());
        assertTrue(recovered.rolledBack(), "rolledBack must survive the success-slot round-trip");
        assertEquals(10000L, recovered.timestamp());
    }

    // ---- EntitySnapshot content fidelity (Luciferase-7wzml.179) ----

    /**
     * String content in an EntitySnapshot must survive the PrepareRequest round-trip unchanged.
     */
    @Test
    void testPrepareRequestStringContentRoundTrip() {
        var txId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var snapshot = new EntitySnapshot(
            entityId.toString(),
            new Point3d(1.0, 2.0, 3.0),
            "hello-world",          // String content
            UUID.randomUUID(),
            7L, 3L, 1000L
        );
        var token = new IdempotencyToken(
            entityId.toString(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1000L,
            UUID.randomUUID()
        );
        var msg = new MigrationProtocolMessages.PrepareRequest(
            txId, token, snapshot,
            UUID.randomUUID(), UUID.randomUUID(),
            1000L
        );

        var transport = MessageConverter.toTransport(msg);
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(MigrationProtocolMessages.PrepareRequest.class, recovered);
        var req = (MigrationProtocolMessages.PrepareRequest) recovered;
        assertNotNull(req.entitySnapshot());
        assertEquals("hello-world", req.entitySnapshot().content(),
            "String content must survive the round-trip unchanged");
    }

    /**
     * Non-String content in an EntitySnapshot must be rejected at serialization time
     * (fail-loud) rather than silently collapsed via toString().
     */
    @Test
    void testPrepareRequestNonStringContentFailsLoud() {
        var txId = UUID.randomUUID();
        var entityId = UUID.randomUUID();

        // A structured non-String content object — e.g. an Integer
        var snapshot = new EntitySnapshot(
            entityId.toString(),
            new Point3d(1.0, 2.0, 3.0),
            42,                     // NON-String content
            UUID.randomUUID(),
            7L, 3L, 1000L
        );
        var token = new IdempotencyToken(
            entityId.toString(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1000L,
            UUID.randomUUID()
        );
        var msg = new MigrationProtocolMessages.PrepareRequest(
            txId, token, snapshot,
            UUID.randomUUID(), UUID.randomUUID(),
            1000L
        );

        // Must throw, not silently return "42"
        assertThrows(IllegalArgumentException.class, () -> MessageConverter.toTransport(msg),
            "Non-String EntitySnapshot content must be rejected at toTransport, not silently collapsed");
    }

    @Test
    void testUnknownMessageTypeThrows() {
        var transport = new TransportVonMessage(
            "UnknownType",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            0f, 0f, 0f,
            "",
            System.currentTimeMillis()
        );

        assertThrows(IllegalArgumentException.class, () ->
            MessageConverter.fromTransport(transport)
        );
    }

    @Test
    void testMultipleGhostSync() {
        var sourceBubbleId = UUID.randomUUID();
        var ghosts = new ArrayList<Message.TransportGhost>();

        for (int i = 0; i < 5; i++) {
            ghosts.add(new Message.TransportGhost(
                "entity-" + i,
                new javax.vecmath.Point3f(i, i + 1, i + 2),
                "Content" + i,
                "value-" + i,
                "tree-" + i,
                (long) i,
                (long) i,
                System.currentTimeMillis()
            ));
        }

        var originalGhostSync = factory.createGhostSync(sourceBubbleId, ghosts, 123L);

        // Convert to transport and back
        var transport = MessageConverter.toTransport(originalGhostSync);
        var recovered = MessageConverter.fromTransport(transport);

        assertInstanceOf(Message.GhostSync.class, recovered);
        var recoveredGhostSync = (Message.GhostSync) recovered;
        assertEquals(5, recoveredGhostSync.ghosts().size());

        for (int i = 0; i < 5; i++) {
            assertEquals("entity-" + i, recoveredGhostSync.ghosts().get(i).entityId());
        }
    }
}
