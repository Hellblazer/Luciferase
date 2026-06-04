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

import javax.vecmath.Point3d;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Serializable wrapper for Message for network transport.
 * <p>
 * Problem: Message contains JavaFX types (Point3d) and javax.vecmath types (Point3f)
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
    String type,                           // Message type: "GHOST_SYNC", "ACK", "MOVE", etc.
    String sourceBubbleId,                 // Source bubble UUID as string
    String targetBubbleId,                 // Target bubble UUID as string
    double posX,                           // Entity X position (Luciferase-0frcy.127: double to avoid precision loss)
    double posY,                           // Entity Y position (Luciferase-0frcy.127: double to avoid precision loss)
    double posZ,                           // Entity Z position (Luciferase-0frcy.127: double to avoid precision loss)
    String entityId,                       // Entity identifier as string
    long timestamp,                        // Message timestamp in millis
    List<TransportGhostData> ghosts,       // Ghost list for GhostSync (null for other types)
    Long bucket,                           // Simulation bucket for GhostSync (null for other types)
    List<TransportNeighborInfo> neighbors, // Neighbor list for JoinResponse (null for other types)
    String queryId,                        // Query correlation ID (null for non-query types)
    TransportBubbleBounds bounds,          // Spatial bounds for JoinRequest/Move (null for other types)
    TransportMigrationMessage migration    // 2PC payload for MigrationProtocolMessages (null for other types)
) implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 6L; // Incremented: posX/Y/Z widened float->double (Luciferase-0frcy.127)

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
     * 12-argument constructor (pre-bounds wire shape). Defaults {@code bounds} to {@code null}.
     */
    public TransportVonMessage(
        String type,
        String sourceBubbleId,
        String targetBubbleId,
        double posX,
        double posY,
        double posZ,
        String entityId,
        long timestamp,
        List<TransportGhostData> ghosts,
        Long bucket,
        List<TransportNeighborInfo> neighbors,
        String queryId
    ) {
        this(type, sourceBubbleId, targetBubbleId, posX, posY, posZ, entityId, timestamp,
             ghosts, bucket, neighbors, queryId, null, null);
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
        double posX,
        double posY,
        double posZ,
        String entityId,
        long timestamp
    ) {
        this(type, sourceBubbleId, targetBubbleId, posX, posY, posZ, entityId, timestamp,
             null, null, null, null, null, null);
    }

    /**
     * 13-argument constructor (bounds wire shape, pre-migration). Defaults {@code migration} to
     * {@code null}. Used by JoinRequest/Move conversions which carry spatial bounds.
     */
    public TransportVonMessage(
        String type,
        String sourceBubbleId,
        String targetBubbleId,
        double posX,
        double posY,
        double posZ,
        String entityId,
        long timestamp,
        List<TransportGhostData> ghosts,
        Long bucket,
        List<TransportNeighborInfo> neighbors,
        String queryId,
        TransportBubbleBounds bounds
    ) {
        this(type, sourceBubbleId, targetBubbleId, posX, posY, posZ, entityId, timestamp,
             ghosts, bucket, neighbors, queryId, bounds, null);
    }

    /**
     * Reconstruct Point3d from decomposed components.
     * <p>
     * Luciferase-0frcy.127: returns double-precision {@link Point3d} so coordinates round-trip
     * through the wire without the silent double&rarr;float&rarr;double truncation that previously
     * perturbed near-boundary spatial classification.
     *
     * @return Point3d(posX, posY, posZ)
     */
    public Point3d position() {
        return new Point3d(posX, posY, posZ);
    }
}
