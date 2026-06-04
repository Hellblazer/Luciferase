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
 * Test-only extension of the production {@link EntityStoreOperations} seam that adds a latency
 * simulation hook for timeout/2PC tests. The {@code simulateDelay} default lives HERE in the
 * test source tree (Luciferase-0frcy.111) — it must never appear in production code because its
 * {@link Thread#sleep(long)} would block a simulation thread under PrimeMover.
 * <p>
 * Production {@code CrossProcessMigration} references only {@link EntityStoreOperations}; test
 * doubles implement this interface to layer in delay behavior.
 *
 * @author hal.hildebrand
 */
public interface TestableEntityStore extends EntityStoreOperations {

    /**
     * Simulate operation delay (for timeout testing). Test-only; not part of the production seam.
     *
     * @param ms Delay in milliseconds
     */
    default void simulateDelay(long ms) {
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
