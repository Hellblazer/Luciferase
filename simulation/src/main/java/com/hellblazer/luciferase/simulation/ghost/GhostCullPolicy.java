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

package com.hellblazer.luciferase.simulation.ghost;

/**
 * Ghost culling policy for removing stale ghost entities (Phase 7B.3).
 * <p>
 * GhostCullPolicy defines staleness criteria based on time since last update.
 * Ghosts that haven't received updates within the staleness threshold are
 * considered stale and eligible for removal.
 * <p>
 * <strong>Design Principles:</strong>
 * <ul>
 *   <li>Stateless: No internal state, thread-safe by design</li>
 *   <li>Configurable threshold: Default 500ms, customizable per use case</li>
 *   <li>Simple time-based staleness: (currentTime - lastUpdate) > threshold</li>
 *   <li>Clock-skew tolerant: Handles negative time deltas gracefully</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var policy = new GhostCullPolicy(); // Default 500ms staleness
 *
 * // Check individual ghost
 * if (policy.isStale(ghost.lastUpdateTime(), currentTime)) {
 *     removeGhost(ghost);
 * }
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> Stateless and thread-safe.
 *
 * @author hal.hildebrand
 */
public class GhostCullPolicy {

    /**
     * Default staleness threshold in milliseconds (500ms).
     * Ghosts that haven't been updated in this time are considered stale.
     */
    public static final long DEFAULT_STALENESS_MS = 500L;

    /**
     * Staleness threshold in milliseconds.
     */
    private final long stalenessMs;

    /**
     * Create cull policy with default staleness threshold (500ms).
     */
    public GhostCullPolicy() {
        this(DEFAULT_STALENESS_MS);
    }

    /**
     * Create cull policy with custom staleness threshold.
     *
     * @param stalenessMs Staleness threshold in milliseconds
     * @throws IllegalArgumentException if stalenessMs is negative
     */
    public GhostCullPolicy(long stalenessMs) {
        if (stalenessMs < 0) {
            throw new IllegalArgumentException("stalenessMs must be non-negative: " + stalenessMs);
        }
        this.stalenessMs = stalenessMs;
    }

    /**
     * Check if a ghost is stale based on time since last update.
     * <p>
     * A ghost is stale if: (currentTime - lastUpdateTime) > stalenessMs
     * <p>
     * <strong>Clock Skew Handling:</strong> If currentTime < lastUpdateTime
     * (negative time delta), the ghost is considered NOT stale. This handles
     * clock skew scenarios gracefully.
     *
     * @param lastUpdateTime Timestamp of last ghost update (milliseconds)
     * @param currentTime    Current simulation time (milliseconds)
     * @return true if ghost is stale and should be culled
     */
    public boolean isStale(long lastUpdateTime, long currentTime) {
        long timeSinceUpdate = currentTime - lastUpdateTime;

        // Handle clock skew: if time went backward, ghost is not stale
        if (timeSinceUpdate < 0) {
            return false;
        }

        return timeSinceUpdate > stalenessMs;
    }

    /**
     * Get the staleness threshold in milliseconds.
     *
     * @return Staleness threshold
     */
    public long getStalenessMs() {
        return stalenessMs;
    }

    @Override
    public String toString() {
        return String.format("GhostCullPolicy{stalenessMs=%dms}", stalenessMs);
    }
}
