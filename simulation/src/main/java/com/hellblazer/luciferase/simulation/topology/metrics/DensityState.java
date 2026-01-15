/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.metrics;

/**
 * Density state for a bubble in the hysteresis state machine.
 * <p>
 * State transitions (split path):
 * <ul>
 *   <li>NORMAL → APPROACHING_SPLIT (entity count > 90% of split threshold)</li>
 *   <li>APPROACHING_SPLIT → NEEDS_SPLIT (entity count > split threshold)</li>
 *   <li>NEEDS_SPLIT → APPROACHING_SPLIT (entity count < 90% of split threshold)</li>
 * </ul>
 * <p>
 * State transitions (merge path):
 * <ul>
 *   <li>NORMAL → APPROACHING_MERGE (entity count < 110% of merge threshold)</li>
 *   <li>APPROACHING_MERGE → NEEDS_MERGE (entity count < merge threshold)</li>
 *   <li>NEEDS_MERGE → APPROACHING_MERGE (entity count > 110% of merge threshold)</li>
 * </ul>
 * <p>
 * Hysteresis prevents oscillation: requires 10% change to clear NEEDS_* states.
 *
 * @author hal.hildebrand
 */
public enum DensityState {
    /**
     * Normal density - not approaching any threshold.
     */
    NORMAL,

    /**
     * Approaching split threshold (>90% of split threshold).
     */
    APPROACHING_SPLIT,

    /**
     * Exceeds split threshold - split recommended.
     */
    NEEDS_SPLIT,

    /**
     * Approaching merge threshold (<110% of merge threshold).
     */
    APPROACHING_MERGE,

    /**
     * Below merge threshold - merge recommended.
     */
    NEEDS_MERGE
}
