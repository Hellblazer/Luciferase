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

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.io.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VonMessageConverter.
 * <p>
 * Phase 6A: Tests focus on serialization round-trip and field preservation.
 *
 * @author hal.hildebrand
 */
class VonMessageConverterTest {

    @Test
    void testToTransport_WithPosition() {
        var ackMessage = new VonMessage.Ack(UUID.randomUUID(), UUID.randomUUID());
        var position = new Point3f(10.5f, 20.25f, 30.75f);

        var transport = VonMessageConverter.toTransport(
            ackMessage,
            "bubble-1",
            "bubble-2",
            position,
            "entity-42"
        );

        assertEquals("Ack", transport.type());
        assertEquals("bubble-1", transport.sourceBubbleId());
        assertEquals("bubble-2", transport.targetBubbleId());
        assertEquals(10.5f, transport.posX(), 0.001f);
        assertEquals(20.25f, transport.posY(), 0.001f);
        assertEquals(30.75f, transport.posZ(), 0.001f);
        assertEquals("entity-42", transport.entityId());
        assertEquals(ackMessage.timestamp(), transport.timestamp());
    }

    @Test
    void testToTransport_NullPosition() {
        var ackMessage = new VonMessage.Ack(UUID.randomUUID(), UUID.randomUUID());

        var transport = VonMessageConverter.toTransport(
            ackMessage,
            "bubble-1",
            "bubble-2",
            null,
            "entity-1"
        );

        assertEquals(0.0f, transport.posX());
        assertEquals(0.0f, transport.posY());
        assertEquals(0.0f, transport.posZ());
    }

    @Test
    void testToTransport_NullEntityId() {
        var ackMessage = new VonMessage.Ack(UUID.randomUUID(), UUID.randomUUID());
        var position = new Point3f(1.0f, 2.0f, 3.0f);

        var transport = VonMessageConverter.toTransport(
            ackMessage,
            "bubble-1",
            "bubble-2",
            position,
            null
        );

        assertEquals("", transport.entityId());
    }

    @Test
    void testFromTransport() {
        var transport = new TransportVonMessage(
            "Ack",
            "bubble-1",
            "bubble-2",
            1.0f, 2.0f, 3.0f,
            "entity-1",
            System.currentTimeMillis()
        );

        var message = VonMessageConverter.fromTransport(transport);

        assertNotNull(message);
        assertInstanceOf(VonMessage.Ack.class, message);
        assertEquals(transport.timestamp(), ((VonMessage.Ack) message).timestamp());
    }

    @Test
    void testSerializationRoundTrip() throws IOException, ClassNotFoundException {
        // Create original message
        var originalMessage = new VonMessage.Ack(UUID.randomUUID(), UUID.randomUUID());
        var originalPosition = new Point3f(10.5f, 20.25f, 30.75f);

        // Convert to transport format
        var transport = VonMessageConverter.toTransport(
            originalMessage,
            "bubble-1",
            "bubble-2",
            originalPosition,
            "entity-42"
        );

        // Serialize to bytes
        var baos = new ByteArrayOutputStream();
        var oos = new ObjectOutputStream(baos);
        oos.writeObject(transport);
        oos.close();

        // Deserialize from bytes
        var bais = new ByteArrayInputStream(baos.toByteArray());
        var ois = new ObjectInputStream(bais);
        var recovered = (TransportVonMessage) ois.readObject();
        ois.close();

        // Verify field preservation
        assertEquals(transport.type(), recovered.type());
        assertEquals(transport.sourceBubbleId(), recovered.sourceBubbleId());
        assertEquals(transport.targetBubbleId(), recovered.targetBubbleId());
        assertEquals(transport.posX(), recovered.posX(), 0.001f);
        assertEquals(transport.posY(), recovered.posY(), 0.001f);
        assertEquals(transport.posZ(), recovered.posZ(), 0.001f);
        assertEquals(transport.entityId(), recovered.entityId());
        assertEquals(transport.timestamp(), recovered.timestamp());

        // Convert back to VonMessage
        var result = VonMessageConverter.fromTransport(recovered);
        assertNotNull(result);
        assertInstanceOf(VonMessage.Ack.class, result);
    }

    @Test
    void testPositionReconstruction() {
        var transport = new TransportVonMessage(
            "Ack",
            "bubble-1",
            "bubble-2",
            10.5f, 20.25f, 30.75f,
            "entity-1",
            System.currentTimeMillis()
        );

        var position = transport.position();
        assertEquals(10.5f, position.x, 0.001f);
        assertEquals(20.25f, position.y, 0.001f);
        assertEquals(30.75f, position.z, 0.001f);
    }

    @Test
    void testMultipleMessageTypes() {
        // Test JoinRequest
        var joinReq = new VonMessage.JoinRequest(UUID.randomUUID(), null, null);
        var transport1 = VonMessageConverter.toTransport(joinReq, "b1", "b2", new Point3f(1, 2, 3), "e1");
        assertEquals("JoinRequest", transport1.type());

        // Test Leave
        var leave = new VonMessage.Leave(UUID.randomUUID());
        var transport2 = VonMessageConverter.toTransport(leave, "b1", "b2", null, null);
        assertEquals("Leave", transport2.type());
    }
}
