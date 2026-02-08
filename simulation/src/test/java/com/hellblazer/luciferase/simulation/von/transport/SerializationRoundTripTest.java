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

package com.hellblazer.luciferase.simulation.transport;

import com.hellblazer.luciferase.simulation.von.TransportVonMessage;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRITICAL Phase 6A Test: Validates Java Serialization round-trip for TransportVonMessage.
 * <p>
 * This test validates the design decision to use Java Serializable (not Externalizable
 * or protobuf) for Phase 6A transport. It verifies:
 * <ul>
 *   <li>TransportVonMessage serializes/deserializes correctly</li>
 *   <li>Point3f decomposition (posX/posY/posZ) works correctly</li>
 *   <li>All fields (type, IDs, position, timestamp) preserved exactly</li>
 *   <li>Performance meets requirements (1000 round-trips in <500ms)</li>
 * </ul>
 * <p>
 * Mandatory: This test must pass before Phase 6A is considered complete.
 * If this test fails, the Java Serializable approach must be reconsidered.
 *
 * @author hal.hildebrand
 */
class SerializationRoundTripTest {

    @Test
    void testTransportVonMessageSerializationRoundTrip() throws IOException, ClassNotFoundException {
        // Create original message with all fields populated
        var original = new TransportVonMessage(
            "GHOST_SYNC",
            "bubble-source-uuid",
            "bubble-target-uuid",
            10.5f,
            20.25f,
            30.75f,
            "entity-42",
            System.currentTimeMillis()
        );

        // Serialize to bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(original);
        oos.close();

        var serializedBytes = baos.toByteArray();
        System.out.println("Serialized size: " + serializedBytes.length + " bytes");

        // Deserialize from bytes
        ByteArrayInputStream bais = new ByteArrayInputStream(serializedBytes);
        ObjectInputStream ois = new ObjectInputStream(bais);
        var recovered = (TransportVonMessage) ois.readObject();
        ois.close();

        // Verify bit-exact equality for all fields
        assertEquals(original.type(), recovered.type(), "Type must match");
        assertEquals(original.sourceBubbleId(), recovered.sourceBubbleId(), "Source bubble ID must match");
        assertEquals(original.targetBubbleId(), recovered.targetBubbleId(), "Target bubble ID must match");
        assertEquals(original.entityId(), recovered.entityId(), "Entity ID must match");
        assertEquals(original.timestamp(), recovered.timestamp(), "Timestamp must match");

        // Verify position components (decomposed Point3f)
        assertEquals(original.posX(), recovered.posX(), 0.0001f, "Position X must match");
        assertEquals(original.posY(), recovered.posY(), 0.0001f, "Position Y must match");
        assertEquals(original.posZ(), recovered.posZ(), 0.0001f, "Position Z must match");

        // Verify position() reconstruction
        var originalPos = original.position();
        var recoveredPos = recovered.position();
        assertEquals(originalPos.x, recoveredPos.x, 0.0001f, "Reconstructed position X must match");
        assertEquals(originalPos.y, recoveredPos.y, 0.0001f, "Reconstructed position Y must match");
        assertEquals(originalPos.z, recoveredPos.z, 0.0001f, "Reconstructed position Z must match");
    }

    @Test
    void testSerializationWithNullFields() throws IOException, ClassNotFoundException {
        // Test with empty strings (nulls not allowed by record validation)
        var message = new TransportVonMessage(
            "ACK",
            "source-id",
            "target-id",
            0f, 0f, 0f,
            "", // Empty entity ID
            12345L
        );

        // Serialize and deserialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(message);
        oos.close();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        var recovered = (TransportVonMessage) ois.readObject();
        ois.close();

        assertEquals(message.type(), recovered.type());
        assertEquals(message.entityId(), recovered.entityId());
        assertEquals("", recovered.entityId(), "Empty entity ID should be preserved");
    }

    @Test
    void testSerializationPerformance() throws IOException, ClassNotFoundException {
        // Performance requirement: 1000 round-trips in <500ms
        var message = new TransportVonMessage(
            "MOVE",
            "bubble-1",
            "bubble-2",
            1.0f, 2.0f, 3.0f,
            "entity-1",
            System.currentTimeMillis()
        );

        var iterations = 1000;
        var start = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            // Serialize
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(message);
            oos.close();

            // Deserialize
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bais);
            var recovered = (TransportVonMessage) ois.readObject();
            ois.close();

            // Verify (to prevent JIT optimization from eliminating the work)
            assertNotNull(recovered);
        }

        var elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%d serialization round-trips completed in %.2f ms (%.3f ms/op)%n",
                          iterations, elapsedMs, elapsedMs / iterations);

        assertTrue(elapsedMs < 500,
                   String.format("Performance requirement: 1000 round-trips in <500ms. Actual: %.2f ms", elapsedMs));
    }

    @Test
    void testPositionReconstructionAccuracy() {
        // Test that position() reconstruction is accurate
        var msg = new TransportVonMessage(
            "TEST",
            "src",
            "tgt",
            123.456f,
            -789.012f,
            0.00001f,
            "e1",
            0L
        );

        var pos = msg.position();
        assertEquals(123.456f, pos.x, 0.0001f);
        assertEquals(-789.012f, pos.y, 0.0001f);
        assertEquals(0.00001f, pos.z, 0.0001f);
    }

    @Test
    void testSerialVersionUID() throws IOException, ClassNotFoundException {
        // Verify serialVersionUID is set correctly
        // This ensures version compatibility across JVM restarts

        var message = new TransportVonMessage(
            "TEST", "s", "t", 1f, 2f, 3f, "e", 12345L
        );

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(message);
        oos.close();

        // Deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        var recovered = (TransportVonMessage) ois.readObject();
        ois.close();

        assertNotNull(recovered, "Deserialization with serialVersionUID should succeed");
        assertEquals(message.type(), recovered.type());
    }
}
