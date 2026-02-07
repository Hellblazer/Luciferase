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
package com.hellblazer.luciferase.simulation.distributed.integration;

import java.util.*;

/**
 * Selects valid migration paths between bubbles.
 * <p>
 * Only allows migrations to neighbor bubbles based on topology.
 * <p>
 * Phase 6B5.4: Cross-Process Migration Validation
 *
 * @author hal.hildebrand
 */
public class MigrationPathSelector {

    private final TestProcessTopology topology;
    private final Random random = new Random();

    /**
     * Creates a path selector for the given topology.
     *
     * @param topology the process topology
     */
    public MigrationPathSelector(TestProcessTopology topology) {
        this.topology = topology;
    }

    /**
     * Gets valid destination bubbles for an entity in a source bubble.
     *
     * @param sourceBubble source bubble UUID
     * @return set of valid destination bubble UUIDs
     */
    public Set<UUID> getValidDestinations(UUID sourceBubble) {
        return topology.getNeighbors(sourceBubble);
    }

    /**
     * Selects a random valid destination for a migration.
     *
     * @param sourceBubble source bubble UUID
     * @return random neighbor bubble UUID, or empty if no neighbors
     */
    public Optional<UUID> selectRandomDestination(UUID sourceBubble) {
        var neighbors = new ArrayList<>(topology.getNeighbors(sourceBubble));
        if (neighbors.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(neighbors.get(random.nextInt(neighbors.size())));
    }

    /**
     * Selects a destination in a different process (cross-process migration).
     *
     * @param sourceBubble source bubble UUID
     * @return cross-process neighbor, or empty if all neighbors are in same process
     */
    public Optional<UUID> selectCrossProcessDestination(UUID sourceBubble) {
        var sourceProcess = topology.getProcessForBubble(sourceBubble);
        return topology.getNeighbors(sourceBubble).stream()
            .filter(b -> !topology.getProcessForBubble(b).equals(sourceProcess))
            .findFirst();
    }

    /**
     * Checks if a migration path is valid.
     *
     * @param sourceBubble source bubble UUID
     * @param destBubble   destination bubble UUID
     * @return true if the path is valid (bubbles are neighbors)
     */
    public boolean isValidPath(UUID sourceBubble, UUID destBubble) {
        return topology.getNeighbors(sourceBubble).contains(destBubble);
    }
}
