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

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;

import java.util.UUID;
import java.util.Objects;

/**
 * EntityDepartureEvent - Entity leaving source bubble (Phase 7E Day 1)
 *
 * Sent by source bubble to target bubble when an entity crosses a spatial boundary.
 * Initiates the target side of entity migration (GHOST → MIGRATING_IN).
 * Entity physics are frozen on source side until ViewSynchronyAck is received.
 *
 * STATE MACHINE:
 * Source: OWNED → MIGRATING_OUT (entity frozen, awaiting ack)
 * Target: GHOST → MIGRATING_IN (deferred updates, awaiting stability)
 *
 * PAYLOAD:
 * - entityId: Entity being migrated
 * - sourceBubbleId: Bubble sending this event
 * - targetBubbleId: Bubble receiving the entity
 * - stateSnapshot: Entity state at departure (position, velocity, etc.)
 * - lamportClock: Lamport clock for causality
 *
 * CAUSALITY:
 * The lamportClock field ensures target can order EntityDepartureEvents
 * correctly with other events, even if they arrive out of network order.
 *
 * FAILURE RECOVERY:
 * If ViewSynchronyAck is not received within timeout (8 seconds default):
 * - Source: transition MIGRATING_OUT → ROLLBACK_OWNED
 * - Thaw physics and resume local control
 * - Send EntityRollbackEvent to target (if reachable)
 *
 * If view change occurs during migration:
 * - Source: transition MIGRATING_OUT → ROLLBACK_OWNED immediately
 * - Target: transition MIGRATING_IN → GHOST immediately
 * - Both discard in-flight state
 *
 * @author hal.hildebrand
 * @see ViewSynchronyAck
 * @see EntityRollbackEvent
 */
public class EntityDepartureEvent {

    private UUID entityId;
    private UUID sourceBubbleId;
    private UUID targetBubbleId;
    private EntityMigrationState stateSnapshot;  // Entity state at departure
    private long lamportClock;

    // Default constructor for deserialization
    public EntityDepartureEvent() {
    }

    /**
     * Create an entity departure event.
     *
     * @param entityId Entity being migrated (must not be null)
     * @param sourceBubbleId Source bubble (must not be null)
     * @param targetBubbleId Target bubble (must not be null)
     * @param stateSnapshot Entity state snapshot (must not be null)
     * @param lamportClock Lamport clock for causality
     * @throws NullPointerException if any parameter is null
     */
    public EntityDepartureEvent(UUID entityId, UUID sourceBubbleId, UUID targetBubbleId,
                               EntityMigrationState stateSnapshot, long lamportClock) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
        this.sourceBubbleId = Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");
        this.targetBubbleId = Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        this.stateSnapshot = Objects.requireNonNull(stateSnapshot, "stateSnapshot must not be null");
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
     * @return UUID of the bubble sending this event
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
     * @return UUID of the bubble receiving the entity
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
     * Get the entity state snapshot at departure.
     *
     * @return Entity state at the time of migration initiation
     */
    public EntityMigrationState getStateSnapshot() {
        return stateSnapshot;
    }

    /**
     * Set the entity state snapshot.
     *
     * @param stateSnapshot Entity state at departure
     */
    public void setStateSnapshot(EntityMigrationState stateSnapshot) {
        this.stateSnapshot = Objects.requireNonNull(stateSnapshot, "stateSnapshot must not be null");
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
        return String.format("EntityDepartureEvent{entity=%s, source=%s, target=%s, state=%s, clock=%d}",
                entityId, sourceBubbleId.toString().substring(0, 8),
                targetBubbleId.toString().substring(0, 8), stateSnapshot, lamportClock);
    }
}
