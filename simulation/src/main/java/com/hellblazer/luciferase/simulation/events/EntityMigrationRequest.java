/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

import java.util.UUID;
import java.util.Objects;

/**
 * EntityMigrationRequest - Request to initiate entity migration (Phase 7E Day 1)
 *
 * Encapsulates the parameters needed to initiate an optimistic entity migration.
 * Sent by source bubble to target bubble when entity crosses a spatial boundary.
 *
 * INVARIANT: Exactly one OWNED entity per entity ID globally across all bubbles.
 *
 * PAYLOAD:
 * - entityId: Unique identifier for the entity being migrated
 * - sourceBubbleId: UUID of the bubble that currently owns the entity
 * - targetBubbleId: UUID of the bubble that will receive the entity
 * - lamportClock: Lamport clock value for causality ordering
 *
 * SEMANTICS:
 * This request initiates an OPTIMISTIC migration:
 * 1. Source sends this request immediately (without waiting for ack)
 * 2. Source freezes entity physics (OWNED → MIGRATING_OUT)
 * 3. Target receives and queues updates (GHOST → MIGRATING_IN)
 * 4. Target waits for view stability (3 ticks)
 * 5. Target commits ownership and sends ViewSynchronyAck
 * 6. Source receives ack and marks entity as DEPARTED
 *
 * FAILURE HANDLING:
 * If view change occurs during migration:
 * - Source: rollback to ROLLBACK_OWNED, thaw physics
 * - Target: rollback to GHOST, discard deferred updates
 *
 * @author hal.hildebrand
 * @see EntityDepartureEvent
 * @see ViewSynchronyAck
 */
public class EntityMigrationRequest {

    private UUID entityId;
    private UUID sourceBubbleId;
    private UUID targetBubbleId;
    private long lamportClock;

    // Default constructor for deserialization
    public EntityMigrationRequest() {
    }

    /**
     * Create a migration request.
     *
     * @param entityId Entity to migrate (must not be null)
     * @param sourceBubbleId Source bubble (must not be null)
     * @param targetBubbleId Target bubble (must not be null)
     * @param lamportClock Lamport clock value for causality
     * @throws NullPointerException if any UUID is null
     */
    public EntityMigrationRequest(UUID entityId, UUID sourceBubbleId, UUID targetBubbleId, long lamportClock) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
        this.sourceBubbleId = Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");
        this.targetBubbleId = Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        this.lamportClock = lamportClock;
    }

    /**
     * Get the entity ID.
     *
     * @return UUID of the entity being migrated
     */
    public UUID getEntityId() {
        return entityId;
    }

    /**
     * Set the entity ID.
     *
     * @param entityId UUID of the entity being migrated
     */
    public void setEntityId(UUID entityId) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
    }

    /**
     * Get the source bubble ID.
     *
     * @return UUID of the bubble that owns the entity
     */
    public UUID getSourceBubbleId() {
        return sourceBubbleId;
    }

    /**
     * Set the source bubble ID.
     *
     * @param sourceBubbleId UUID of the source bubble
     */
    public void setSourceBubbleId(UUID sourceBubbleId) {
        this.sourceBubbleId = Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");
    }

    /**
     * Get the target bubble ID.
     *
     * @return UUID of the bubble that will receive the entity
     */
    public UUID getTargetBubbleId() {
        return targetBubbleId;
    }

    /**
     * Set the target bubble ID.
     *
     * @param targetBubbleId UUID of the target bubble
     */
    public void setTargetBubbleId(UUID targetBubbleId) {
        this.targetBubbleId = Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
    }

    /**
     * Get the Lamport clock value.
     * Used for causality ordering across bubbles.
     *
     * @return Lamport clock value
     */
    public long getLamportClock() {
        return lamportClock;
    }

    /**
     * Set the Lamport clock value.
     *
     * @param lamportClock Lamport clock value
     */
    public void setLamportClock(long lamportClock) {
        this.lamportClock = lamportClock;
    }

    @Override
    public String toString() {
        return String.format("EntityMigrationRequest{entity=%s, source=%s, target=%s, clock=%d}",
                entityId, sourceBubbleId.toString().substring(0, 8),
                targetBubbleId.toString().substring(0, 8), lamportClock);
    }
}
