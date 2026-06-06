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
import javax.vecmath.Vector3f;
import java.io.Serializable;

/**
 * Serializable transport representation of a ghost entity.
 * <p>
 * Decomposes Point3f into individual float components (posX, posY, posZ)
 * for reliable Java Serialization over network sockets. This is the wire format
 * used in TransportMessage for cross-process ghost synchronization.
 *
 * <h3>BREAKING wire-format change — serialVersionUID 1 → 2 (Luciferase-chmxx)</h3>
 * <p>
 * Three velocity fields ({@code velX}, {@code velY}, {@code velZ}) were added in the
 * Luciferase-chmxx bead, changing the record from 10 fields to 13 fields.
 * The {@code serialVersionUID} was bumped from {@code 1L} to {@code 2L} to reflect this.
 * <p>
 * This is a <strong>BREAKING</strong> change: a v1 receiver that deserializes a v2
 * record (or vice versa) will throw {@link java.io.InvalidClassException}. Rolling
 * upgrades and mixed-version clusters are <strong>NOT</strong> supported for this record.
 * A <strong>full cluster restart</strong> is required when upgrading across this change.
 *
 * @author hal.hildebrand
 */
public record TransportGhostData(
    String entityId,
    float posX,
    float posY,
    float posZ,
    String contentClass,
    String contentValue,
    String sourceTreeId,
    long epoch,
    long version,
    long timestamp,
    float velX,
    float velY,
    float velZ
) implements Serializable {

    /**
     * Wire-format version 2: added velX, velY, velZ (Luciferase-chmxx).
     * Version 1 had 10 fields; version 2 has 13 fields.
     * INCOMPATIBLE with version 1 — mixed-version deserialization throws
     * {@link java.io.InvalidClassException}.
     */
    private static final long serialVersionUID = 2L;

    /**
     * Create TransportGhostData from a Message.TransportGhost.
     *
     * @param ghost TransportGhost to convert
     * @return TransportGhostData with decomposed position and velocity
     */
    public static TransportGhostData from(Message.TransportGhost ghost) {
        var vel = ghost.velocity();
        return new TransportGhostData(
            ghost.entityId(),
            ghost.position().x,
            ghost.position().y,
            ghost.position().z,
            ghost.contentClass(),
            ghost.contentValue(),
            ghost.sourceTreeId(),
            ghost.epoch(),
            ghost.version(),
            ghost.timestamp(),
            vel.x,
            vel.y,
            vel.z
        );
    }

    /**
     * Convert back to Message.TransportGhost.
     *
     * @return TransportGhost with reconstructed Point3f and Vector3f velocity
     */
    public Message.TransportGhost toTransportGhost() {
        return new Message.TransportGhost(
            entityId,
            new Point3f(posX, posY, posZ),
            contentClass,
            contentValue,
            sourceTreeId,
            epoch,
            version,
            timestamp,
            new Vector3f(velX, velY, velZ)
        );
    }
}
