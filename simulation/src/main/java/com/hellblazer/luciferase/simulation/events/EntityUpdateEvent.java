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

package com.hellblazer.luciferase.simulation.events;

import com.hellblazer.luciferase.simulation.entity.StringEntityID;

import javax.vecmath.Point3f;

/**
 * EntityUpdateEvent - Cross-Bubble Entity State Update (Phase 7B.1)
 *
 * Carries entity position/velocity updates for cross-bubble synchronization.
 * Consumed in-process by {@link com.hellblazer.luciferase.simulation.ghost.GhostStateManager};
 * cross-process delivery rides the VON wire path ({@code Message.TransportGhost} via P2PGhostChannel),
 * not a dedicated binary format.
 *
 * IMMUTABILITY: This is a record class - all fields are final and immutable.
 *
 * DEAD RECKONING: Velocity field enables recipient bubbles to extrapolate
 * entity position between updates, reducing network traffic.
 *
 * CAUSALITY: Lamport clock ensures correct event ordering across distributed bubbles.
 *
 * PAYLOAD:
 * - entityId: Entity identifier (StringEntityID)
 * - position: Current entity position (Point3f)
 * - velocity: Velocity vector for dead reckoning (Point3f)
 * - timestamp: Simulation time when update was generated (long)
 * - lamportClock: Lamport clock for causality ordering (long)
 *
 * USAGE:
 * <pre>
 * var event = new EntityUpdateEvent(
 *     new StringEntityID("tank-42"),
 *     new Point3f(100.0f, 200.0f, 50.0f),  // position
 *     new Point3f(5.0f, 0.0f, 2.0f),       // velocity
 *     12345L,                               // simulation time
 *     67890L                                // lamport clock
 * );
 * </pre>
 *
 * PHASE 7B.1: Event type definitions
 * PHASE 7B.2: Delos transport prototype removed (Luciferase-j877j); cross-bubble delivery is P2PGhostChannel (VON-based)
 * PHASE 7B.3: Will enable dead reckoning in DistributedEntityTracker
 *
 * @param entityId Entity identifier
 * @param position Current position (x, y, z)
 * @param velocity Velocity vector for dead reckoning (dx/dt, dy/dt, dz/dt)
 * @param timestamp Simulation time (tick count from RealTimeController)
 * @param lamportClock Lamport clock for event ordering
 *
 * @author hal.hildebrand
 */
public record EntityUpdateEvent(
    StringEntityID entityId,
    Point3f position,
    Point3f velocity,
    long timestamp,
    long lamportClock
) {
    /**
     * Compact constructor — defensive copies of mutable Point3f fields.
     * <p>
     * javax.vecmath.Point3f exposes public mutable x/y/z fields. Without
     * copying, a caller that retains and mutates the supplied Point3f after
     * construction would silently corrupt this event's position/velocity
     * in-flight (an RDR-004-class silent-data-loss risk for cross-bubble
     * state synchronization). Copying restores the record's immutability
     * contract.
     */
    public EntityUpdateEvent {
        // Guard before any field deref: javax.vecmath copy constructors NPE on a null
        // argument with an opaque "t1 is null" message. A null position/velocity is a
        // caller contract violation for cross-bubble state sync, so fail loudly with an
        // actionable message that names the offending entity (restores the guard a wave-3
        // ghost change moved behind the Point3f copy — Luciferase-0frcy regression).
        if (position == null) {
            throw new NullPointerException(
                "position must not be null for entity " + (entityId == null ? "<null>" : entityId.toDebugString()));
        }
        if (velocity == null) {
            throw new NullPointerException(
                "velocity must not be null for entity " + (entityId == null ? "<null>" : entityId.toDebugString()));
        }
        position = new Point3f(position);
        velocity = new Point3f(velocity);
    }

    /**
     * Returns a defensive copy of the position so callers cannot mutate
     * this event's internal state.
     *
     * @return a copy of the position
     */
    public Point3f position() {
        return new Point3f(position);
    }

    /**
     * Returns a defensive copy of the velocity so callers cannot mutate
     * this event's internal state.
     *
     * @return a copy of the velocity
     */
    public Point3f velocity() {
        return new Point3f(velocity);
    }

    /**
     * Custom toString for debugging.
     * Includes all critical fields for trace logging.
     *
     * @return Human-readable representation
     */
    @Override
    public String toString() {
        return String.format(
            "EntityUpdateEvent{id=%s, pos=(%.2f,%.2f,%.2f), vel=(%.2f,%.2f,%.2f), time=%d, clock=%d}",
            entityId,
            position.x, position.y, position.z,
            velocity.x, velocity.y, velocity.z,
            timestamp,
            lamportClock
        );
    }
}
