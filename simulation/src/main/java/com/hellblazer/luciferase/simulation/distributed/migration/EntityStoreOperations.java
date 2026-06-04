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

package com.hellblazer.luciferase.simulation.distributed.migration;

/**
 * Entity-store operations a {@link BubbleReference} may expose to the cross-process migration
 * 2PC state machine: removing/adding entities, snapshotting current state, and reporting
 * reachability. When a {@code BubbleReference} implements this interface, the migration
 * coordinator drives these operations directly during PREPARE/COMMIT/ABORT.
 * <p>
 * This is a production interface and intentionally contains NO timing/sleep hooks — test
 * doubles that need to simulate latency add that behavior in the test source tree
 * (Luciferase-0frcy.111). Previously this seam lived in a type named {@code TestableEntityStore}
 * carrying a {@code default simulateDelay()} that called {@link Thread#sleep(long)} from the
 * production tree, which could block a simulation thread under PrimeMover.
 *
 * @author hal.hildebrand
 */
public interface EntityStoreOperations {

    /**
     * Remove an entity from this store.
     *
     * @param entityId Entity identifier
     * @return true if removed successfully, false if failed
     */
    boolean removeEntity(String entityId);

    /**
     * Add an entity to this store.
     *
     * @param snapshot Entity snapshot to add
     * @return true if added successfully, false if failed
     */
    boolean addEntity(EntitySnapshot snapshot);

    /**
     * Capture a snapshot of an entity's current state from this store (Luciferase-x8pwi).
     * <p>
     * Used by the migration PREPARE phase to capture the entity's real position, content,
     * epoch, and version <em>before</em> the entity is removed, so that an aborted migration
     * can restore the exact original state rather than fabricated data.
     *
     * @param entityId Entity identifier
     * @return the entity's current snapshot, or {@code null} if this store does not track the
     *         entity's full state
     */
    default EntitySnapshot getEntitySnapshot(String entityId) {
        return null;
    }

    /**
     * Check if this store is reachable (simulates network connectivity).
     *
     * @return true if reachable, false if unreachable/partitioned
     */
    boolean isReachable();
}
