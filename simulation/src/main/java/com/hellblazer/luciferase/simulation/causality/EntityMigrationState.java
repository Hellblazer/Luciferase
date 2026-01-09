/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.causality;

/**
 * EntityMigrationState - Entity Ownership States (Phase 7C.5)
 *
 * Defines the six possible ownership states for an entity during
 * distributed migration across bubble boundaries.
 *
 * State Diagram:
 *
 * ```
 *                      ┌─────────────────────────────────┐
 *                      │                                 │
 *                      v                                 │
 *     ┌─────────┐  boundary  ┌───────────────┐  commit    ┌─────────┐
 *     │  OWNED  │──crossing──>│ MIGRATING_OUT │──────────>│ DEPARTED │
 *     └─────────┘            └───────────────┘            └────┬────┘
 *          ^                        │                          │
 *          │                        │ timeout/                 │ receive
 *          │                        │ view_change              │ ghost
 *     ┌────┴──────────┐             v                          v
 *     │ ROLLBACK_OWNED│<────────────────────────────     ┌─────────┐
 *     └───────────────┘                                  │  GHOST  │
 *                                                        └────┬────┘
 *                                                             │ boundary
 *                                                             │ crossing
 *                                                             v
 *     ┌─────────┐  transfer   ┌───────────────┐         ┌────────────┐
 *     │  OWNED  │<────────────│ MIGRATING_IN  │<────────│   (claim)  │
 *     └─────────┘             └───────────────┘         └────────────┘
 *          ^                        │
 *          │                        │ timeout/view_change
 *          │                        v
 *          └────────────────── GHOST (stay)
 * ```
 *
 * State Invariants:
 * - OWNED: This bubble owns the entity (single authoritative instance per entity globally)
 * - MIGRATING_OUT: Entity is being transferred to another bubble, awaiting COMMIT_ACK
 * - DEPARTED: Entity has left this bubble, no longer tracked locally
 * - GHOST: Remote entity, local copy exists for rendering/tracking purposes
 * - MIGRATING_IN: Entity is arriving from another bubble, pending local acceptance
 * - ROLLBACK_OWNED: Failed migration attempt, restored to OWNED state (temporary)
 *
 * CRITICAL INVARIANT: Exactly one bubble globally can have OWNED or MIGRATING_IN state
 * for any entity at any given time. All other bubbles have GHOST or DEPARTED.
 *
 * @author hal.hildebrand
 */
public enum EntityMigrationState {
    /**
     * This bubble is the authoritative owner of the entity.
     * Entity is fully under control and responsibility of this bubble.
     * Can transition to: MIGRATING_OUT
     */
    OWNED("Owner"),

    /**
     * Entity is in the process of leaving this bubble.
     * Awaiting confirmation (COMMIT_ACK) from destination bubble.
     * If confirmation arrives: transitions to DEPARTED
     * If timeout or view change: transitions to ROLLBACK_OWNED
     */
    MIGRATING_OUT("Leaving"),

    /**
     * Entity has permanently left this bubble.
     * No longer tracked in local ownership structures.
     * If ghost update received: transitions to GHOST
     */
    DEPARTED("Gone"),

    /**
     * Remote entity, local copy exists for rendering/tracking.
     * Read-only local state derived from authoritative bubble.
     * Can transition to: MIGRATING_IN (if entity crosses back)
     */
    GHOST("Remote"),

    /**
     * Entity is arriving from remote bubble.
     * Awaiting acceptance (TRANSFER received) before becoming OWNED.
     * If acceptance arrives: transitions to OWNED
     * If timeout or view change: stays as GHOST
     */
    MIGRATING_IN("Incoming"),

    /**
     * Migration failed, entity restored to OWNED.
     * Temporary state during rollback recovery.
     * Transitions automatically to OWNED after processing
     */
    ROLLBACK_OWNED("Rollback");

    private final String displayName;

    EntityMigrationState(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Get human-readable name for this state.
     *
     * @return Display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if this is a transitional state that may require view stability.
     * Transitional states are MIGRATING_OUT and MIGRATING_IN.
     * Note: Specific transitions MIGRATING_OUT->DEPARTED and MIGRATING_IN->OWNED require stable view,
     * but MIGRATING_OUT->ROLLBACK_OWNED and MIGRATING_IN->GHOST do not.
     *
     * @return true if this is a transitional state
     */
    public boolean requiresViewStabilityForCommit() {
        return this == MIGRATING_OUT || this == MIGRATING_IN;
    }

    /**
     * Check if entity is locally owned (can be modified).
     *
     * @return true if OWNED or ROLLBACK_OWNED
     */
    public boolean isLocallyOwned() {
        return this == OWNED || this == ROLLBACK_OWNED;
    }

    /**
     * Check if entity exists locally (not DEPARTED).
     *
     * @return true if entity has local representation
     */
    public boolean existsLocally() {
        return this != DEPARTED;
    }

    /**
     * Check if entity is in transition (mid-migration).
     *
     * @return true if MIGRATING_OUT or MIGRATING_IN
     */
    public boolean isInTransition() {
        return this == MIGRATING_OUT || this == MIGRATING_IN;
    }
}
