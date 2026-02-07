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

import javax.vecmath.Point3f;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Serializable wrapper for Message for network transport.
 * <p>
 * Problem: Message contains JavaFX types (Point3D) and javax.vecmath types (Point3f)
 * that are not Serializable or have problematic serialization.
 * <p>
 * Solution: Decompose Point3f into 3x float (posX, posY, posZ) to ensure reliable
 * Java Serialization without depending on external type serialization behavior.
 * <p>
 * Supports all Message types:
 * <ul>
 *   <li>JoinRequest/JoinResponse - Join protocol messages</li>
 *   <li>Move - Position/bounds change notification</li>
 *   <li>Leave - Graceful departure</li>
 *   <li>GhostSync - Ghost entity batching (ghosts and bucket fields)</li>
 *   <li>Ack - Acknowledgment</li>
 *   <li>Query - Remote bubble query</li>
 * </ul>
 * <p>
 * Used by SocketTransport for cross-process communication via MessageConverter.
 *
 * @author hal.hildebrand
 */
public record TransportVonMessage(
    String type,                      // Message type: "GHOST_SYNC", "ACK", "MOVE", etc.
    String sourceBubbleId,            // Source bubble UUID as string
    String targetBubbleId,            // Target bubble UUID as string
    float posX,                       // Entity X position (decomposed from Point3f)
    float posY,                       // Entity Y position (decomposed from Point3f)
    float posZ,                       // Entity Z position (decomposed from Point3f)
    String entityId,                  // Entity identifier as string
    long timestamp,                   // Message timestamp in millis
    List<TransportGhostData> ghosts,  // Ghost list for GhostSync (null for other types)
    Long bucket                       // Simulation bucket for GhostSync (null for other types)
) implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Compact constructor with validation.
     * Handles legacy calls with 8 parameters by creating new record with null ghosts/bucket.
     */
    public TransportVonMessage {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(sourceBubbleId, "sourceBubbleId cannot be null");
        Objects.requireNonNull(targetBubbleId, "targetBubbleId cannot be null");
    }

    /**
     * Create TransportVonMessage for non-ghost messages (legacy constructor).
     *
     * @param type           Message type
     * @param sourceBubbleId Source bubble ID
     * @param targetBubbleId Target bubble ID
     * @param posX           X position
     * @param posY           Y position
     * @param posZ           Z position
     * @param entityId       Entity ID
     * @param timestamp      Timestamp
     */
    public TransportVonMessage(
        String type,
        String sourceBubbleId,
        String targetBubbleId,
        float posX,
        float posY,
        float posZ,
        String entityId,
        long timestamp
    ) {
        this(type, sourceBubbleId, targetBubbleId, posX, posY, posZ, entityId, timestamp, null, null);
    }

    /**
     * Reconstruct Point3f from decomposed components.
     *
     * @return Point3f(posX, posY, posZ)
     */
    public Point3f position() {
        return new Point3f(posX, posY, posZ);
    }
}
