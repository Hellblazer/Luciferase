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
 * Serialized via custom binary format (EventSerializer) for network transmission via Delos.
 *
 * IMMUTABILITY: This is a record class - all fields are final and immutable.
 * No Java serialization - custom binary format ensures efficient wire protocol.
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
 *
 * // Serialize for network transmission
 * var serializer = new EventSerializer();
 * var bytes = serializer.toBytes(event);
 *
 * // Deserialize on recipient bubble
 * var received = serializer.fromBytes(bytes);
 * </pre>
 *
 * PHASE 7B.1: Event type definitions and serialization
 * PHASE 7B.2: Will integrate with Delos for cross-bubble delivery
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
