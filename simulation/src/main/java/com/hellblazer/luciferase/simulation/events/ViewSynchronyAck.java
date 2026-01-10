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
 * ViewSynchronyAck - Target confirms entity arrival and ownership transfer (Phase 7E Day 1)
 *
 * Sent by target bubble back to source after the distributed view has become stable.
 * This signal allows the source to complete the migration:
 * MIGRATING_OUT → DEPARTED
 *
 * SEMANTICS:
 * "View has been stable for N ticks. I have received EntityDepartureEvent and
 * am now the authoritative owner. You may forget this entity."
 *
 * TIMING:
 * Target waits for view stability (typically 3 ticks) before sending this ack.
 * This ensures:
 * 1. No process failures are happening right now
 * 2. All bubbles have converged on the same membership view
 * 3. The entity is safe on the target and will not be lost
 *
 * STATE MACHINE:
 * Source: MIGRATING_OUT → DEPARTED (entity removed from local tracking)
 * Target: MIGRATING_IN → OWNED (entity now authoritative)
 *
 * RECOVERY:
 * If source does not receive this ack within timeout (8 seconds default):
 * - Source: rollback MIGRATING_OUT → ROLLBACK_OWNED
 * - Source: thaw entity and resume physics
 * - Source: send EntityRollbackEvent to target (if reachable)
 *
 * If view change occurs after ack but before DEPARTED is set:
 * - Source: immediately transition to DEPARTED (view change is final)
 * - Target: stays OWNED (view change does not rollback confirmed migrations)
 *
 * @author hal.hildebrand
 * @see EntityDepartureEvent
 * @see EntityRollbackEvent
 */
public class ViewSynchronyAck {

    private UUID entityId;
    private UUID sourceBubbleId;
    private UUID targetBubbleId;
    private int stabilityTicksVerified;  // How many ticks the view remained stable
    private long lamportClock;

    // Default constructor for deserialization
    public ViewSynchronyAck() {
    }

    /**
     * Create a view synchrony acknowledgment.
     *
     * @param entityId Entity being acknowledged (must not be null)
     * @param sourceBubbleId Source bubble that can now forget this entity (must not be null)
     * @param targetBubbleId Target bubble that now owns the entity (must not be null)
     * @param stabilityTicksVerified Number of stable ticks before ack (typically 3)
     * @param lamportClock Lamport clock for causality
     * @throws NullPointerException if any UUID is null
     * @throws IllegalArgumentException if stabilityTicksVerified < 0
     */
    public ViewSynchronyAck(UUID entityId, UUID sourceBubbleId, UUID targetBubbleId,
                           int stabilityTicksVerified, long lamportClock) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
        this.sourceBubbleId = Objects.requireNonNull(sourceBubbleId, "sourceBubbleId must not be null");
        this.targetBubbleId = Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        if (stabilityTicksVerified < 0) {
            throw new IllegalArgumentException("stabilityTicksVerified must be >= 0");
        }
        this.stabilityTicksVerified = stabilityTicksVerified;
        this.lamportClock = lamportClock;
    }

    /**
     * Get the entity ID.
     *
     * @return UUID of the entity being acknowledged
     */
    public UUID getEntityId() {
        return entityId;
    }

    /**
     * Set the entity ID.
     *
     * @param entityId UUID of the entity being acknowledged
     */
    public void setEntityId(UUID entityId) {
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
    }

    /**
     * Get the source bubble ID.
     *
     * @return UUID of the bubble that sent EntityDepartureEvent
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
     * @return UUID of the bubble that is now the owner
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
     * Get the number of ticks the view remained stable.
     * Used for debugging and metrics - typical value is 3.
     *
     * @return Number of stable ticks before sending ack
     */
    public int getStabilityTicksVerified() {
        return stabilityTicksVerified;
    }

    /**
     * Set the number of stable ticks.
     *
     * @param stabilityTicksVerified Number of ticks the view was stable
     */
    public void setStabilityTicksVerified(int stabilityTicksVerified) {
        if (stabilityTicksVerified < 0) {
            throw new IllegalArgumentException("stabilityTicksVerified must be >= 0");
        }
        this.stabilityTicksVerified = stabilityTicksVerified;
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
        return String.format("ViewSynchronyAck{entity=%s, source=%s, target=%s, stable=%d ticks, clock=%d}",
                entityId, sourceBubbleId.toString().substring(0, 8),
                targetBubbleId.toString().substring(0, 8), stabilityTicksVerified, lamportClock);
    }
}
