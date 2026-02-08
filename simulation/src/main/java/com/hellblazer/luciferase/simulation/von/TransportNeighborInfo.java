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
import javafx.geometry.Point3D;

import java.io.Serializable;
import java.util.UUID;

/**
 * Serializable transport representation of a neighbor's information.
 * <p>
 * Decomposes Point3D into individual components for reliable
 * Java Serialization over network sockets. This is the wire format used in
 * TransportVonMessage for neighbor set transmission in JoinResponse messages.
 * <p>
 * Phase 6A: BubbleBounds not transmitted (set to null on reconstruction)
 * Phase 6B: Will add BubbleBounds serialization when needed
 *
 * @author hal.hildebrand
 */
public record TransportNeighborInfo(
    String nodeId,
    double posX,
    double posY,
    double posZ
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Create TransportNeighborInfo from a Message.NeighborInfo.
     *
     * @param neighbor NeighborInfo to convert
     * @return TransportNeighborInfo with decomposed position
     */
    public static TransportNeighborInfo from(Message.NeighborInfo neighbor) {
        var pos = neighbor.position();
        return new TransportNeighborInfo(
            neighbor.nodeId().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ()
        );
    }

    /**
     * Convert back to Message.NeighborInfo.
     * <p>
     * Phase 6A: BubbleBounds set to null (not transmitted in wire format)
     *
     * @return NeighborInfo with reconstructed Point3D, null bounds
     */
    public Message.NeighborInfo toNeighborInfo() {
        return new Message.NeighborInfo(
            UUID.fromString(nodeId),
            new Point3D(posX, posY, posZ),
            null  // BubbleBounds not transmitted in Phase 6A
        );
    }
}
