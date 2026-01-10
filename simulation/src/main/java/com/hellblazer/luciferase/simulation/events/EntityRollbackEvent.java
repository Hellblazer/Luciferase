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
 * EntityRollbackEvent - Migration failed, entity stays on source (Phase 7E Day 1)
 *
 * Sent by source bubble to target bubble when an in-flight migration is cancelled.
 * This can happen due to:
 * 1. View change (process failure detected by Fireflies)
 * 2. Timeout (ViewSynchronyAck not received within configured timeout)
 * 3. Explicit cancellation (rare, only in special recovery scenarios)
 *
 * STATE MACHINE:
 * Source: MIGRATING_OUT → ROLLBACK_OWNED (entity thawed, resumes physics)
 * Target: MIGRATING_IN → GHOST (discard deferred updates)
 *
 * PAYLOAD:
 * - entityId: Entity whose migration is being rolled back
 * - sourceBubbleId: Bubble re-acquiring ownership
 * - targetBubbleId: Bubble abandoning the incoming migration
 * - reason: Why rollback occurred (view_change, timeout, manual)
 * - lamportClock: Lamport clock for causality
 *
 * SEMANTICS:
 * This event indicates the entity has returned to OWNED state on the source bubble
 * and is no longer coming to the target. The target should discard any deferred
 * updates, revert MIGRATING_IN → GHOST, and treat as a normal ghost.
 *
 * IDEMPOTENCY:
 * Safe to receive duplicate rollback events (entity already in GHOST state).
 * Will simply re-set GHOST state.
 *
 * @author hal.hildebrand
 * @see EntityDepartureEvent
 * @see ViewSynchronyAck
 */
public class EntityRollbackEvent {

    private UUID entityId;
    private UUID sourceBubbleId;
    private UUID targetBubbleId;
    private String reason;  // "view_change", "timeout", "manual"
    private long lamportClock;

    // Default constructor for deserialization
    public EntityRollbackEvent() {
    }

    /**
     * Create an entity rollback event.
     *
     * @param entityId Entity being rolled back (must not be null)
     * @param sourceBubbleId Source bubble re-acquiring ownership (must not be null)
     * @param targetBubbleId Target bubble abandoning migration (must not be null)
     * @param reason Why rollback occurred (must not be null or empty)
     * @param lamportClock Lamport clock for causality
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if reason is empty
     */
    public EntityRollbackEvent(UUID entityId, UUID sourceBubbleId, UUID targetBubbleId,
                              String reason, long lamportClock) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
        this.sourceBubbleId = Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");
        this.targetBubbleId = Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be empty");
        }
        this.lamportClock = lamportClock;
    }

    /**
     * Get the entity ID.
     *
     * @return UUID of the entity being rolled back
     */
    public UUID getEntityId() {
        return entityId;
    }

    /**
     * Set the entity ID.
     *
     * @param entityId UUID of the entity being rolled back
     */
    public void setEntityId(UUID entityId) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
    }

    /**
     * Get the source bubble ID.
     *
     * @return UUID of the bubble re-acquiring ownership
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
     * @return UUID of the bubble abandoning the migration
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
     * Get the reason for rollback.
     *
     * @return Reason: "view_change", "timeout", or "manual"
     */
    public String getReason() {
        return reason;
    }

    /**
     * Set the reason for rollback.
     *
     * @param reason Reason why rollback occurred
     */
    public void setReason(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be empty");
        }
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
        return String.format("EntityRollbackEvent{entity=%s, source=%s, target=%s, reason=%s, clock=%d}",
                entityId, sourceBubbleId.toString().substring(0, 8),
                targetBubbleId.toString().substring(0, 8), reason, lamportClock);
    }
}
