package com.hellblazer.luciferase.simulation.bubble;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import com.hellblazer.luciferase.geometry.Point3i;

/**
 * TDD tests for BubbleEntry record.
 * Tests written BEFORE implementation to drive design.
 */
class BubbleEntryTest {

    @Test
    void testCreation() {
        // Given
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(
            new Point3f(0, 0, 0),
            new Point3f(10, 10, 10)
        ));
        var timestamp = System.currentTimeMillis();

        // When
        var entry = new BubbleEntry(bubbleId, serverId, bounds, timestamp);

        // Then
        assertEquals(bubbleId, entry.bubbleId());
        assertEquals(serverId, entry.serverId());
        assertEquals(bounds, entry.bounds());
        assertEquals(timestamp, entry.timestamp());
    }

    @Test
    void testEquality() {
        // Given
        var bubbleId = UUID.randomUUID();
        var serverId1 = UUID.randomUUID();
        var serverId2 = UUID.randomUUID();
        var bounds1 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0)));
        var bounds2 = BubbleBounds.fromEntityPositions(List.of(new Point3f(10, 10, 10)));
        var timestamp1 = 1000L;
        var timestamp2 = 2000L;

        // When - same bubbleId, different other fields
        var entry1 = new BubbleEntry(bubbleId, serverId1, bounds1, timestamp1);
        var entry2 = new BubbleEntry(bubbleId, serverId2, bounds2, timestamp2);

        // Then - should be equal based on bubbleId
        assertEquals(entry1, entry2);
        assertEquals(entry1.hashCode(), entry2.hashCode());

        // When - different bubbleId
        var differentId = UUID.randomUUID();
        var entry3 = new BubbleEntry(differentId, serverId1, bounds1, timestamp1);

        // Then - should not be equal
        assertNotEquals(entry1, entry3);
    }

    @Test
    void testSerialization() throws IOException, ClassNotFoundException {
        // Given
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(
            new Point3f(10.5f, 20.5f, 30.5f),
            new Point3f(15.5f, 25.5f, 35.5f)
        ));
        var timestamp = 1234567890L;
        var original = new BubbleEntry(bubbleId, serverId, bounds, timestamp);

        // When - manually serialize essential fields (like gossip protocol would)
        byte[] bytes;
        try (var baos = new ByteArrayOutputStream();
             var dos = new DataOutputStream(baos)) {
            // Serialize UUIDs
            dos.writeLong(original.bubbleId().getMostSignificantBits());
            dos.writeLong(original.bubbleId().getLeastSignificantBits());
            dos.writeLong(original.serverId().getMostSignificantBits());
            dos.writeLong(original.serverId().getLeastSignificantBits());

            // Serialize bounds (RDGCS coordinates only)
            dos.writeInt(original.bounds().rdgMin().x);
            dos.writeInt(original.bounds().rdgMin().y);
            dos.writeInt(original.bounds().rdgMin().z);
            dos.writeInt(original.bounds().rdgMax().x);
            dos.writeInt(original.bounds().rdgMax().y);
            dos.writeInt(original.bounds().rdgMax().z);

            // Serialize timestamp
            dos.writeLong(original.timestamp());

            bytes = baos.toByteArray();
        }

        // Then - bytes should not be empty
        assertTrue(bytes.length > 0);

        // When - deserialize from bytes
        UUID bubbleIdDeserialized, serverIdDeserialized;
        Point3i rdgMin, rdgMax;
        long timestampDeserialized;

        try (var bais = new ByteArrayInputStream(bytes);
             var dis = new DataInputStream(bais)) {
            bubbleIdDeserialized = new UUID(dis.readLong(), dis.readLong());
            serverIdDeserialized = new UUID(dis.readLong(), dis.readLong());

            rdgMin = new Point3i(dis.readInt(), dis.readInt(), dis.readInt());
            rdgMax = new Point3i(dis.readInt(), dis.readInt(), dis.readInt());

            timestampDeserialized = dis.readLong();
        }

        // Then - should match original
        assertEquals(original.bubbleId(), bubbleIdDeserialized);
        assertEquals(original.serverId(), serverIdDeserialized);
        // Compare Point3i components individually (toString() formats differ)
        assertEquals(original.bounds().rdgMin().x, rdgMin.x);
        assertEquals(original.bounds().rdgMin().y, rdgMin.y);
        assertEquals(original.bounds().rdgMin().z, rdgMin.z);
        assertEquals(original.bounds().rdgMax().x, rdgMax.x);
        assertEquals(original.bounds().rdgMax().y, rdgMax.y);
        assertEquals(original.bounds().rdgMax().z, rdgMax.z);
        assertEquals(original.timestamp(), timestampDeserialized);
    }
}
