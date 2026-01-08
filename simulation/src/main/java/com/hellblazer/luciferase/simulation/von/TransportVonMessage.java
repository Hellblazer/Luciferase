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
import java.util.Objects;

/**
 * Serializable wrapper for VonMessage for network transport.
 * <p>
 * Problem: VonMessage contains JavaFX types (Point3D) and javax.vecmath types (Point3f)
 * that are not Serializable or have problematic serialization.
 * <p>
 * Solution: Decompose Point3f into 3x float (posX, posY, posZ) to ensure reliable
 * Java Serialization without depending on external type serialization behavior.
 * <p>
 * Used by SocketTransport for cross-process communication via VonMessageConverter.
 *
 * @param type            VonMessage type name (e.g., "GHOST_SYNC")
 * @param sourceBubbleId  Source bubble UUID as string
 * @param targetBubbleId  Target bubble UUID as string
 * @param posX            Entity X position (decomposed from Point3f)
 * @param posY            Entity Y position (decomposed from Point3f)
 * @param posZ            Entity Z position (decomposed from Point3f)
 * @param entityId        Entity identifier as string
 * @param timestamp       Message timestamp in millis
 * @author hal.hildebrand
 */
public record TransportVonMessage(
    String type,
    String sourceBubbleId,
    String targetBubbleId,
    float posX,
    float posY,
    float posZ,
    String entityId,
    long timestamp
) implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public TransportVonMessage {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(sourceBubbleId, "sourceBubbleId cannot be null");
        Objects.requireNonNull(targetBubbleId, "targetBubbleId cannot be null");
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
