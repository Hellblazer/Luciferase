/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.events;

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EntityUpdateEventSerializationTest - Phase 7B.1 TDD Tests
 *
 * Validates custom binary serialization for EntityUpdateEvent.
 * Tests serialization correctness, round-trip fidelity, and error handling.
 *
 * @author hal.hildebrand
 */
class EntityUpdateEventSerializationTest {

    /**
     * Test 1: Event Creation
     * Verify EntityUpdateEvent can be created with all fields.
     */
    @Test
    void testEventCreation() {
        var entityId = new StringEntityID("test-entity-1");
        var position = new Point3f(1.0f, 2.0f, 3.0f);
        var velocity = new Point3f(0.5f, -0.3f, 0.8f);
        var timestamp = 12345L;
        var lamportClock = 67890L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, lamportClock);

        assertNotNull(event, "Event should not be null");
        assertEquals(entityId, event.entityId(), "Entity ID should match");
        assertEquals(position, event.position(), "Position should match");
        assertEquals(velocity, event.velocity(), "Velocity should match");
        assertEquals(timestamp, event.timestamp(), "Timestamp should match");
        assertEquals(lamportClock, event.lamportClock(), "Lamport clock should match");
    }

    /**
     * Test 2: Event toString()
     * Verify toString() is informative and contains key data.
     */
    @Test
    void testEventToString() {
        var entityId = new StringEntityID("debug-entity");
        var position = new Point3f(10.0f, 20.0f, 30.0f);
        var velocity = new Point3f(1.0f, 2.0f, 3.0f);
        var timestamp = 555L;
        var lamportClock = 999L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, lamportClock);
        var str = event.toString();

        assertNotNull(str, "toString() should not return null");
        assertTrue(str.contains("debug-entity"), "toString() should contain entity ID");
        assertTrue(str.contains("555"), "toString() should contain timestamp");
        assertTrue(str.contains("999"), "toString() should contain lamport clock");
    }

    /**
     * Test 3: Serialization Round-Trip
     * Serialize and deserialize, verify all fields match.
     */
    @Test
    void testSerializationRoundTrip() {
        var entityId = new StringEntityID("round-trip-entity");
        var position = new Point3f(100.5f, 200.75f, 300.25f);
        var velocity = new Point3f(5.0f, -10.0f, 15.0f);
        var timestamp = 987654321L;
        var lamportClock = 123456789L;

        var original = new EntityUpdateEvent(entityId, position, velocity, timestamp, lamportClock);
        var serializer = new EventSerializer();

        // Serialize
        var bytes = serializer.toBytes(original);
        assertNotNull(bytes, "Serialized bytes should not be null");
        assertTrue(bytes.length > 0, "Serialized bytes should not be empty");

        // Deserialize
        var deserialized = serializer.fromBytes(bytes);
        assertNotNull(deserialized, "Deserialized event should not be null");

        // Verify all fields match
        assertEquals(original.entityId(), deserialized.entityId(), "Entity ID should match after round-trip");
        assertEquals(original.position(), deserialized.position(), "Position should match after round-trip");
        assertEquals(original.velocity(), deserialized.velocity(), "Velocity should match after round-trip");
        assertEquals(original.timestamp(), deserialized.timestamp(), "Timestamp should match after round-trip");
        assertEquals(original.lamportClock(), deserialized.lamportClock(), "Lamport clock should match after round-trip");
    }

    /**
     * Test 4: Velocity Preserved
     * Verify velocity field survives round-trip with precision.
     */
    @Test
    void testVelocityPreserved() {
        var entityId = new StringEntityID("velocity-test");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(123.456f, -789.012f, 345.678f);
        var timestamp = 111L;
        var lamportClock = 222L;

        var original = new EntityUpdateEvent(entityId, position, velocity, timestamp, lamportClock);
        var serializer = new EventSerializer();

        var bytes = serializer.toBytes(original);
        var deserialized = serializer.fromBytes(bytes);

        // Verify velocity components with float precision
        assertEquals(velocity.x, deserialized.velocity().x, 0.0001f, "Velocity X should be preserved");
        assertEquals(velocity.y, deserialized.velocity().y, 0.0001f, "Velocity Y should be preserved");
        assertEquals(velocity.z, deserialized.velocity().z, 0.0001f, "Velocity Z should be preserved");
    }

    /**
     * Test 5: Multiple Round Trips
     * Serialize and deserialize 100 events, verify all match.
     */
    @Test
    void testMultipleRoundTrips() {
        var serializer = new EventSerializer();
        var random = new Random(42L); // Fixed seed for reproducibility

        for (int i = 0; i < 100; i++) {
            var entityId = new StringEntityID("entity-" + i);
            var position = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000, random.nextFloat() * 1000);
            var velocity = new Point3f(random.nextFloat() * 10 - 5, random.nextFloat() * 10 - 5, random.nextFloat() * 10 - 5);
            var timestamp = random.nextLong() & Long.MAX_VALUE;
            var lamportClock = random.nextLong() & Long.MAX_VALUE;

            var original = new EntityUpdateEvent(entityId, position, velocity, timestamp, lamportClock);
            var bytes = serializer.toBytes(original);
            var deserialized = serializer.fromBytes(bytes);

            assertEquals(original.entityId(), deserialized.entityId(), "Entity ID should match for event " + i);
            assertEquals(original.position().x, deserialized.position().x, 0.0001f, "Position X should match for event " + i);
            assertEquals(original.position().y, deserialized.position().y, 0.0001f, "Position Y should match for event " + i);
            assertEquals(original.position().z, deserialized.position().z, 0.0001f, "Position Z should match for event " + i);
            assertEquals(original.velocity().x, deserialized.velocity().x, 0.0001f, "Velocity X should match for event " + i);
            assertEquals(original.velocity().y, deserialized.velocity().y, 0.0001f, "Velocity Y should match for event " + i);
            assertEquals(original.velocity().z, deserialized.velocity().z, 0.0001f, "Velocity Z should match for event " + i);
            assertEquals(original.timestamp(), deserialized.timestamp(), "Timestamp should match for event " + i);
            assertEquals(original.lamportClock(), deserialized.lamportClock(), "Lamport clock should match for event " + i);
        }
    }

    /**
     * Test 6: Different Velocities
     * Test with various velocity vectors (zero, positive, negative).
     */
    @Test
    void testDifferentVelocities() {
        var serializer = new EventSerializer();

        // Test zero velocity
        var zeroVel = new EntityUpdateEvent(
            new StringEntityID("zero"),
            new Point3f(0, 0, 0),
            new Point3f(0, 0, 0),
            100L,
            200L
        );
        var zeroBytes = serializer.toBytes(zeroVel);
        var zeroDeser = serializer.fromBytes(zeroBytes);
        assertEquals(0.0f, zeroDeser.velocity().x, 0.0001f, "Zero velocity X");
        assertEquals(0.0f, zeroDeser.velocity().y, 0.0001f, "Zero velocity Y");
        assertEquals(0.0f, zeroDeser.velocity().z, 0.0001f, "Zero velocity Z");

        // Test positive velocity
        var posVel = new EntityUpdateEvent(
            new StringEntityID("positive"),
            new Point3f(1, 1, 1),
            new Point3f(100.0f, 200.0f, 300.0f),
            300L,
            400L
        );
        var posBytes = serializer.toBytes(posVel);
        var posDeser = serializer.fromBytes(posBytes);
        assertEquals(100.0f, posDeser.velocity().x, 0.0001f, "Positive velocity X");
        assertEquals(200.0f, posDeser.velocity().y, 0.0001f, "Positive velocity Y");
        assertEquals(300.0f, posDeser.velocity().z, 0.0001f, "Positive velocity Z");

        // Test negative velocity
        var negVel = new EntityUpdateEvent(
            new StringEntityID("negative"),
            new Point3f(2, 2, 2),
            new Point3f(-50.0f, -75.0f, -100.0f),
            500L,
            600L
        );
        var negBytes = serializer.toBytes(negVel);
        var negDeser = serializer.fromBytes(negBytes);
        assertEquals(-50.0f, negDeser.velocity().x, 0.0001f, "Negative velocity X");
        assertEquals(-75.0f, negDeser.velocity().y, 0.0001f, "Negative velocity Y");
        assertEquals(-100.0f, negDeser.velocity().z, 0.0001f, "Negative velocity Z");
    }

    /**
     * Test 7: Lamport Clock Preserved
     * Verify Lamport clock survives serialization with full long range.
     */
    @Test
    void testLamportClockPreserved() {
        var serializer = new EventSerializer();

        // Test with max long value
        var maxClock = new EntityUpdateEvent(
            new StringEntityID("max-clock"),
            new Point3f(0, 0, 0),
            new Point3f(0, 0, 0),
            0L,
            Long.MAX_VALUE
        );
        var maxBytes = serializer.toBytes(maxClock);
        var maxDeser = serializer.fromBytes(maxBytes);
        assertEquals(Long.MAX_VALUE, maxDeser.lamportClock(), "Max Lamport clock should be preserved");

        // Test with min long value (0 for unsigned clock)
        var minClock = new EntityUpdateEvent(
            new StringEntityID("min-clock"),
            new Point3f(0, 0, 0),
            new Point3f(0, 0, 0),
            0L,
            0L
        );
        var minBytes = serializer.toBytes(minClock);
        var minDeser = serializer.fromBytes(minBytes);
        assertEquals(0L, minDeser.lamportClock(), "Min Lamport clock should be preserved");
    }

    /**
     * Test 8: Binary Format Size
     * Verify serialized size is reasonable (< 100 bytes per event).
     */
    @Test
    void testBinaryFormatSize() {
        var serializer = new EventSerializer();
        var event = new EntityUpdateEvent(
            new StringEntityID("size-test"),
            new Point3f(1.0f, 2.0f, 3.0f),
            new Point3f(4.0f, 5.0f, 6.0f),
            12345L,
            67890L
        );

        var bytes = serializer.toBytes(event);
        assertTrue(bytes.length < 100, "Serialized size should be < 100 bytes, was: " + bytes.length);

        // Verify format version is included
        assertEquals(0x01, bytes[0], "First byte should be format version 0x01");
    }

    /**
     * Test 9: Deserialization Failure on Corrupt Data
     * Corrupt byte array, verify exception thrown.
     */
    @Test
    void testDeserializationFailureOnCorruptData() {
        var serializer = new EventSerializer();
        var event = new EntityUpdateEvent(
            new StringEntityID("corrupt-test"),
            new Point3f(1.0f, 2.0f, 3.0f),
            new Point3f(4.0f, 5.0f, 6.0f),
            111L,
            222L
        );

        var bytes = serializer.toBytes(event);

        // Corrupt the data - truncate to half size
        var corruptBytes = new byte[bytes.length / 2];
        System.arraycopy(bytes, 0, corruptBytes, 0, corruptBytes.length);

        assertThrows(IllegalArgumentException.class, () -> {
            serializer.fromBytes(corruptBytes);
        }, "Should throw IllegalArgumentException on corrupt data");
    }

    /**
     * Test 10: StringEntityID with Special Characters
     * Handle entity ID with unicode/special characters.
     */
    @Test
    void testStringEntityIDWithSpecialCharacters() {
        var serializer = new EventSerializer();

        // Test with unicode and special characters
        var specialId = new StringEntityID("entity-€-日本語-🚀-test");
        var event = new EntityUpdateEvent(
            specialId,
            new Point3f(1.0f, 2.0f, 3.0f),
            new Point3f(4.0f, 5.0f, 6.0f),
            999L,
            888L
        );

        var bytes = serializer.toBytes(event);
        var deserialized = serializer.fromBytes(bytes);

        assertEquals(specialId, deserialized.entityId(), "Special character entity ID should be preserved");
        assertEquals("entity-€-日本語-🚀-test", deserialized.entityId().getValue(), "Special character string value should match");
    }
}
