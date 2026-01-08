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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransportVonMessage record.
 *
 * @author hal.hildebrand
 */
class TransportVonMessageTest {

    @Test
    void testValidConstruction() {
        var msg = new TransportVonMessage(
            "GHOST_SYNC",
            "bubble-1",
            "bubble-2",
            1.0f, 2.0f, 3.0f,
            "entity-1",
            System.currentTimeMillis()
        );

        assertEquals("GHOST_SYNC", msg.type());
        assertEquals("bubble-1", msg.sourceBubbleId());
        assertEquals("bubble-2", msg.targetBubbleId());
        assertEquals(1.0f, msg.posX());
        assertEquals(2.0f, msg.posY());
        assertEquals(3.0f, msg.posZ());
        assertEquals("entity-1", msg.entityId());
    }

    @Test
    void testPositionReconstruction() {
        var msg = new TransportVonMessage(
            "GHOST_SYNC",
            "bubble-1",
            "bubble-2",
            10.5f, 20.25f, 30.75f,
            "entity-1",
            System.currentTimeMillis()
        );

        var pos = msg.position();
        assertEquals(10.5f, pos.x, 0.001f);
        assertEquals(20.25f, pos.y, 0.001f);
        assertEquals(30.75f, pos.z, 0.001f);
    }

    @Test
    void testNullType() {
        assertThrows(NullPointerException.class, () ->
            new TransportVonMessage(
                null,
                "bubble-1",
                "bubble-2",
                1.0f, 2.0f, 3.0f,
                "entity-1",
                System.currentTimeMillis()
            ),
            "Null type should be rejected"
        );
    }

    @Test
    void testNullSourceBubbleId() {
        assertThrows(NullPointerException.class, () ->
            new TransportVonMessage(
                "GHOST_SYNC",
                null,
                "bubble-2",
                1.0f, 2.0f, 3.0f,
                "entity-1",
                System.currentTimeMillis()
            ),
            "Null sourceBubbleId should be rejected"
        );
    }

    @Test
    void testNullTargetBubbleId() {
        assertThrows(NullPointerException.class, () ->
            new TransportVonMessage(
                "GHOST_SYNC",
                "bubble-1",
                null,
                1.0f, 2.0f, 3.0f,
                "entity-1",
                System.currentTimeMillis()
            ),
            "Null targetBubbleId should be rejected"
        );
    }

    @Test
    void testSerialization() throws IOException, ClassNotFoundException {
        var original = new TransportVonMessage(
            "GHOST_SYNC",
            "bubble-1",
            "bubble-2",
            10.5f, 20.25f, 30.75f,
            "entity-42",
            12345L
        );

        // Serialize
        var baos = new ByteArrayOutputStream();
        var oos = new ObjectOutputStream(baos);
        oos.writeObject(original);
        oos.close();

        // Deserialize
        var bais = new ByteArrayInputStream(baos.toByteArray());
        var ois = new ObjectInputStream(bais);
        var recovered = (TransportVonMessage) ois.readObject();
        ois.close();

        // Verify
        assertEquals(original.type(), recovered.type());
        assertEquals(original.sourceBubbleId(), recovered.sourceBubbleId());
        assertEquals(original.targetBubbleId(), recovered.targetBubbleId());
        assertEquals(original.posX(), recovered.posX(), 0.001f);
        assertEquals(original.posY(), recovered.posY(), 0.001f);
        assertEquals(original.posZ(), recovered.posZ(), 0.001f);
        assertEquals(original.entityId(), recovered.entityId());
        assertEquals(original.timestamp(), recovered.timestamp());
    }

    @Test
    void testEquality() {
        var msg1 = new TransportVonMessage(
            "GHOST_SYNC", "b1", "b2", 1.0f, 2.0f, 3.0f, "e1", 1000L
        );
        var msg2 = new TransportVonMessage(
            "GHOST_SYNC", "b1", "b2", 1.0f, 2.0f, 3.0f, "e1", 1000L
        );
        var msg3 = new TransportVonMessage(
            "GHOST_SYNC", "b1", "b2", 4.0f, 5.0f, 6.0f, "e2", 2000L
        );

        assertEquals(msg1, msg2, "Same values should be equal");
        assertNotEquals(msg1, msg3, "Different position should not be equal");
    }
}
