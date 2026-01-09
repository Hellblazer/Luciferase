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

import javax.vecmath.Point3f;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * EventSerializer - Custom Binary Serialization for EntityUpdateEvent (Phase 7B.1)
 *
 * Provides efficient binary serialization for cross-bubble entity updates.
 * Uses custom format (NOT Java serialization) for network transmission via Delos.
 *
 * BINARY FORMAT (Version 0x01):
 * <pre>
 * [Version: 1 byte = 0x01]
 * [EntityID length: 2 bytes BE] [EntityID: UTF-8 bytes]
 * [Position X: 4 bytes float BE]
 * [Position Y: 4 bytes float BE]
 * [Position Z: 4 bytes float BE]
 * [Velocity X: 4 bytes float BE]
 * [Velocity Y: 4 bytes float BE]
 * [Velocity Z: 4 bytes float BE]
 * [Timestamp: 8 bytes long BE]
 * [Lamport Clock: 8 bytes long BE]
 * </pre>
 *
 * FORMAT RATIONALE:
 * - Version byte: Future compatibility (can evolve format)
 * - Big-endian: Network byte order (Java default for DataOutputStream)
 * - UTF-8 strings: Standard encoding, supports unicode
 * - Float32: Sufficient precision for simulation coordinates
 * - Fixed-size fields: Predictable layout except for entity ID string
 *
 * THREAD SAFETY:
 * - This class is stateless and thread-safe
 * - Each method creates temporary streams internally
 * - Can be safely shared across threads
 *
 * USAGE:
 * <pre>
 * var serializer = new EventSerializer();
 *
 * // Serialize event for network transmission
 * var event = new EntityUpdateEvent(...);
 * var bytes = serializer.toBytes(event);
 *
 * // Deserialize received event
 * var received = serializer.fromBytes(bytes);
 * </pre>
 *
 * PHASE 7B.1: Custom binary serialization
 * PHASE 7B.2: Will integrate with Delos for reliable multicast
 * PHASE 7B.3: Events will be used for dead reckoning updates
 *
 * @author hal.hildebrand
 */
public class EventSerializer {

    /**
     * Binary format version.
     * Increment this when format changes for backward compatibility checks.
     */
    private static final byte FORMAT_VERSION = 0x01;

    /**
     * Maximum entity ID length (safety limit for malicious/corrupt data).
     * 1024 characters should be sufficient for any reasonable entity ID.
     */
    private static final int MAX_ENTITY_ID_LENGTH = 1024;

    /**
     * Serialize EntityUpdateEvent to binary format.
     *
     * @param event Event to serialize (must not be null)
     * @return Binary representation as byte array
     * @throws NullPointerException if event is null
     * @throws IllegalArgumentException if entity ID is too long
     */
    public byte[] toBytes(EntityUpdateEvent event) {
        if (event == null) {
            throw new NullPointerException("Event cannot be null");
        }

        try (var baos = new ByteArrayOutputStream();
             var dos = new DataOutputStream(baos)) {

            // Write format version
            dos.writeByte(FORMAT_VERSION);

            // Write entity ID (length-prefixed UTF-8)
            var idString = event.entityId().getValue();
            var idBytes = idString.getBytes(StandardCharsets.UTF_8);

            if (idBytes.length > MAX_ENTITY_ID_LENGTH) {
                throw new IllegalArgumentException(
                    "Entity ID too long: " + idBytes.length + " bytes (max " + MAX_ENTITY_ID_LENGTH + ")"
                );
            }

            dos.writeShort(idBytes.length);
            dos.write(idBytes);

            // Write position (3 floats)
            dos.writeFloat(event.position().x);
            dos.writeFloat(event.position().y);
            dos.writeFloat(event.position().z);

            // Write velocity (3 floats)
            dos.writeFloat(event.velocity().x);
            dos.writeFloat(event.velocity().y);
            dos.writeFloat(event.velocity().z);

            // Write timestamps (2 longs)
            dos.writeLong(event.timestamp());
            dos.writeLong(event.lamportClock());

            dos.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            // This should never happen with ByteArrayOutputStream
            throw new IllegalStateException("Unexpected IOException during serialization", e);
        }
    }

    /**
     * Deserialize EntityUpdateEvent from binary format.
     *
     * @param bytes Binary data to deserialize (must not be null)
     * @return Deserialized event
     * @throws NullPointerException if bytes is null
     * @throws IllegalArgumentException if data is corrupt or format is invalid
     */
    public EntityUpdateEvent fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("Bytes cannot be null");
        }

        try (var bais = new ByteArrayInputStream(bytes);
             var dis = new DataInputStream(bais)) {

            // Read and validate format version
            var version = dis.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException(
                    "Unsupported format version: 0x" + Integer.toHexString(version & 0xFF)
                );
            }

            // Read entity ID (length-prefixed UTF-8)
            var idLength = dis.readShort() & 0xFFFF; // Unsigned short
            if (idLength <= 0 || idLength > MAX_ENTITY_ID_LENGTH) {
                throw new IllegalArgumentException(
                    "Invalid entity ID length: " + idLength
                );
            }

            var idBytes = new byte[idLength];
            dis.readFully(idBytes);
            var idString = new String(idBytes, StandardCharsets.UTF_8);
            var entityId = new StringEntityID(idString);

            // Read position (3 floats)
            var posX = dis.readFloat();
            var posY = dis.readFloat();
            var posZ = dis.readFloat();
            var position = new Point3f(posX, posY, posZ);

            // Read velocity (3 floats)
            var velX = dis.readFloat();
            var velY = dis.readFloat();
            var velZ = dis.readFloat();
            var velocity = new Point3f(velX, velY, velZ);

            // Read timestamps (2 longs)
            var timestamp = dis.readLong();
            var lamportClock = dis.readLong();

            return new EntityUpdateEvent(entityId, position, velocity, timestamp, lamportClock);

        } catch (EOFException e) {
            throw new IllegalArgumentException("Corrupt data: unexpected end of stream", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize event", e);
        }
    }

    /**
     * Get the binary format version.
     *
     * @return Format version string (e.g., "7B.1.0")
     */
    public String getFormat() {
        return "7B.1.0";
    }
}
