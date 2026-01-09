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

/**
 * Serializable transport representation of a ghost entity.
 * <p>
 * Decomposes Point3f into individual float components (posX, posY, posZ)
 * for reliable Java Serialization over network sockets. This is the wire format
 * used in TransportVonMessage for cross-process ghost synchronization.
 *
 * @author hal.hildebrand
 */
public record TransportGhostData(
    String entityId,
    float posX,
    float posY,
    float posZ,
    String contentClass,
    String sourceTreeId,
    long epoch,
    long version,
    long timestamp
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Create TransportGhostData from a VonMessage.TransportGhost.
     *
     * @param ghost TransportGhost to convert
     * @return TransportGhostData with decomposed position
     */
    public static TransportGhostData from(VonMessage.TransportGhost ghost) {
        return new TransportGhostData(
            ghost.entityId(),
            ghost.position().x,
            ghost.position().y,
            ghost.position().z,
            ghost.contentClass(),
            ghost.sourceTreeId(),
            ghost.epoch(),
            ghost.version(),
            ghost.timestamp()
        );
    }

    /**
     * Convert back to VonMessage.TransportGhost.
     *
     * @return TransportGhost with reconstructed Point3f
     */
    public VonMessage.TransportGhost toTransportGhost() {
        return new VonMessage.TransportGhost(
            entityId,
            new Point3f(posX, posY, posZ),
            contentClass,
            sourceTreeId,
            epoch,
            version,
            timestamp
        );
    }
}
