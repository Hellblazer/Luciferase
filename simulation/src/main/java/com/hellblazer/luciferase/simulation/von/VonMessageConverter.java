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

/**
 * Bidirectional converter between VonMessage (domain) and TransportVonMessage (wire format).
 * <p>
 * Phase 6A Implementation Note:
 * This converter currently handles a simplified subset of VonMessage types needed
 * for Phase 6A testing. Specifically, it supports generic message transport with
 * position and entity ID fields.
 * <p>
 * For Phase 6B/6C, this converter will be extended to handle full VonMessage
 * sealed interface hierarchy (JoinRequest, JoinResponse, Move, Leave, GhostSync, Ack).
 * <p>
 * Design:
 * - toTransport: Extracts serializable fields from VonMessage
 * - fromTransport: Reconstructs VonMessage from wire format
 *
 * @author hal.hildebrand
 */
public class VonMessageConverter {

    /**
     * Convert domain VonMessage to serializable TransportVonMessage.
     * <p>
     * Phase 6A: Handles generic message structure. For specific VonMessage
     * subtypes (Join/Move/Leave/GhostSync), this extracts common fields.
     *
     * @param message Domain VonMessage (can be any sealed subtype)
     * @param sourceBubbleId Source bubble UUID as string
     * @param targetBubbleId Target bubble UUID as string
     * @param position Entity position (may be null for some message types)
     * @param entityId Entity identifier (may be null for some message types)
     * @return TransportVonMessage ready for serialization
     */
    public static TransportVonMessage toTransport(
        VonMessage message,
        String sourceBubbleId,
        String targetBubbleId,
        Point3f position,
        String entityId
    ) {
        // Determine message type
        var type = message.getClass().getSimpleName();

        // Decompose position (or use 0,0,0 if null)
        var posX = position != null ? position.x : 0.0f;
        var posY = position != null ? position.y : 0.0f;
        var posZ = position != null ? position.z : 0.0f;

        // Extract timestamp (all VonMessage types have this)
        var timestamp = extractTimestamp(message);

        return new TransportVonMessage(
            type,
            sourceBubbleId,
            targetBubbleId,
            posX, posY, posZ,
            entityId != null ? entityId : "",
            timestamp
        );
    }

    /**
     * Convert serializable TransportVonMessage back to domain VonMessage.
     * <p>
     * Phase 6A: Returns a synthetic VonMessage.Ack for testing purposes.
     * Phase 6B will implement full reconstruction of all VonMessage subtypes.
     *
     * @param transport Wire-format message
     * @return Reconstructed VonMessage
     */
    public static VonMessage fromTransport(TransportVonMessage transport) {
        // Phase 6A: For testing, return a simple Ack message with correct UUIDs
        // Phase 6B will use switch on transport.type() to reconstruct proper subtype
        var ackFor = transport.targetBubbleId().isEmpty()
            ? java.util.UUID.randomUUID()
            : java.util.UUID.fromString(transport.targetBubbleId());
        var senderId = java.util.UUID.fromString(transport.sourceBubbleId());

        return new VonMessage.Ack(
            ackFor,
            senderId,
            transport.timestamp()
        );
    }

    /**
     * Extract timestamp from any VonMessage subtype.
     * All VonMessage record types have a timestamp() method.
     */
    private static long extractTimestamp(VonMessage message) {
        return switch (message) {
            case VonMessage.JoinRequest m -> m.timestamp();
            case VonMessage.JoinResponse m -> m.timestamp();
            case VonMessage.Move m -> m.timestamp();
            case VonMessage.Leave m -> m.timestamp();
            case VonMessage.GhostSync m -> m.timestamp();
            case VonMessage.Ack m -> m.timestamp();
            default -> System.currentTimeMillis(); // Phase 6B distributed messages
        };
    }
}
