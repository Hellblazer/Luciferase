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

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import javax.vecmath.Point3d;

import java.io.Serializable;
import java.util.UUID;

/**
 * Serializable transport representation of a neighbor's information.
 * <p>
 * Decomposes Point3d into individual components for reliable
 * Java Serialization over network sockets. This is the wire format used in
 * TransportVonMessage for neighbor set transmission in JoinResponse messages.
 * <p>
 * Phase 6B (Luciferase-vzyrf): BubbleBounds now transmitted via the primitive-decomposed
 * {@link TransportBubbleBounds} (null only when the source NeighborInfo had null bounds).
 *
 * @author hal.hildebrand
 */
public record TransportNeighborInfo(
    String nodeId,
    double posX,
    double posY,
    double posZ,
    TransportBubbleBounds bounds
) implements Serializable {

    private static final long serialVersionUID = 2L; // Incremented: added bounds (Luciferase-vzyrf)

    /**
     * Legacy 4-argument constructor (pre-bounds). Defaults {@code bounds} to {@code null}.
     */
    public TransportNeighborInfo(String nodeId, double posX, double posY, double posZ) {
        this(nodeId, posX, posY, posZ, null);
    }

    /**
     * Create TransportNeighborInfo from a Message.NeighborInfo, including its bounds.
     *
     * @param neighbor NeighborInfo to convert
     * @return TransportNeighborInfo with decomposed position and bounds
     */
    public static TransportNeighborInfo from(Message.NeighborInfo neighbor) {
        var pos = neighbor.position();
        return new TransportNeighborInfo(
            neighbor.nodeId().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            TransportBubbleBounds.from(neighbor.bounds())
        );
    }

    /**
     * Convert back to Message.NeighborInfo, reconstructing bounds when present.
     *
     * @return NeighborInfo with reconstructed Point3d and bounds (null iff the wire bounds were null)
     */
    public Message.NeighborInfo toNeighborInfo() {
        return new Message.NeighborInfo(
            UUID.fromString(nodeId),
            new Point3d(posX, posY, posZ),
            bounds != null ? bounds.toBubbleBounds() : null
        );
    }
}
