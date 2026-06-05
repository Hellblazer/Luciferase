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

package com.hellblazer.luciferase.simulation.persistence;

/**
 * RecoveryStateSink — receives replayed WAL events during crash recovery so that actual
 * state (migration FSM, entity positions) can be reconstructed.
 *
 * <p>Implementations must be idempotent: recovery may be invoked more than once on the
 * same node without side effects (duplicate-migration dedup already happens in
 * {@link EventRecovery} before the sink is called).
 *
 * <p>A no-op implementation ({@link #NOOP}) is provided for use-cases where recovery state
 * reconstruction is intentionally deferred or handled elsewhere.
 *
 * @author hal.hildebrand
 */
public interface RecoveryStateSink {

    /** No-op sink: all events are accepted and silently discarded. */
    RecoveryStateSink NOOP = new RecoveryStateSink() {};

    /**
     * Called when an ENTITY_DEPARTURE event is replayed.
     *
     * @param entityId     the entity that departed (String UUID from WAL)
     * @param sourceBubble source bubble UUID string, may be null
     * @param targetBubble destination bubble UUID string, may be null
     */
    default void onEntityDeparture(String entityId, String sourceBubble, String targetBubble) {}

    /**
     * Called when a VIEW_SYNC_ACK event is replayed.
     *
     * @param entityId entity for which the ack was received
     * @param success  whether the view-synchrony ack indicated success
     */
    default void onViewSynchronyAck(String entityId, boolean success) {}

    /**
     * Called when a DEFERRED_UPDATE event is replayed.
     * Position and velocity are raw float arrays encoded as lists in the WAL.
     *
     * @param entityId entity to update
     * @param position float[3] position, or null if absent
     * @param velocity float[3] velocity, or null if absent
     */
    default void onDeferredUpdate(String entityId, float[] position, float[] velocity) {}

    /**
     * Called when a MIGRATION_COMMIT event is replayed.
     *
     * @param entityId entity whose migration was committed
     */
    default void onMigrationCommit(String entityId) {}
}
