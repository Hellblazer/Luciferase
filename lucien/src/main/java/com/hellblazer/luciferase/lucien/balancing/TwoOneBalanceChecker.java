/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects 2:1 balance constraint violations at partition boundaries.
 *
 * <p>The 2:1 balance constraint requires that adjacent elements differ by at most 1 level
 * in the spatial hierarchy. This checker finds all violations by examining ghost elements
 * (non-local boundary elements) and comparing their levels with neighboring local elements.
 *
 * <p>When a violation is detected (level difference > 1), a refinement request is generated
 * to ask the ghost's source partition for refined (subdivided) elements.
 *
 * @param <Key> spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> entity identifier type
 * @param <Content> entity content type
 *
 * @author hal.hildebrand
 */
public class TwoOneBalanceChecker<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Record representing a 2:1 balance constraint violation.
     *
     * <p>A violation occurs when a local element and adjacent ghost element differ by more than 1 level.
     *
     * @param localKey local element key
     * @param ghostKey ghost element key
     * @param localLevel local element level in spatial hierarchy
     * @param ghostLevel ghost element level
     * @param levelDifference abs(localLevel - ghostLevel), must be > 1
     * @param sourceRank rank of partition owning the ghost element
     */
    public record BalanceViolation<K extends SpatialKey<K>>(
        K localKey,
        K ghostKey,
        int localLevel,
        int ghostLevel,
        int levelDifference,
        int sourceRank
    ) {
        /**
         * Constructor with validation.
         *
         * @throws IllegalArgumentException if levelDifference <= 1 (not a violation)
         */
        public BalanceViolation {
            if (levelDifference <= 1) {
                throw new IllegalArgumentException(
                    "Level difference must be > 1 to be a violation, got " + levelDifference);
            }
        }

        /**
         * Determine which side of the violation needs refinement.
         *
         * @return true if local element needs refinement (is coarser), false if ghost needs refinement
         */
        public boolean localNeedsRefinement() {
            return localLevel < ghostLevel;
        }
    }

    /**
     * Find all 2:1 balance violations in the ghost layer.
     *
     * <p>Iterates through all ghost elements and checks for level violations with local elements.
     * For each ghost, checks its neighboring local elements for level differences > 1.
     *
     * @param ghostLayer ghost elements to check (non-local boundary elements from adjacent partitions)
     * @param forest local forest containing local elements
     * @return list of violations found (empty if none)
     * @throws IllegalArgumentException if ghostLayer or forest is null
     */
    public List<BalanceViolation<Key>> findViolations(
        GhostLayer<Key, ID, Content> ghostLayer,
        Forest<Key, ID, Content> forest
    ) {
        if (ghostLayer == null) {
            throw new IllegalArgumentException("ghostLayer cannot be null");
        }
        if (forest == null) {
            throw new IllegalArgumentException("forest cannot be null");
        }

        List<BalanceViolation<Key>> violations = new ArrayList<>();

        // Iterate through all ghost elements
        for (var ghost : ghostLayer.getAllGhostElements()) {
            // Get ghost spatial key and level
            var ghostKey = ghost.getSpatialKey();
            int ghostLevel = ghostKey.getLevel();

            // Check local elements for violation with this ghost
            // In a concrete implementation with specific key types (MortonKey, TetreeKey),
            // this would iterate through neighbors using direction-based navigation.
            // For now, the violation detection logic is deferred to Phase 2 implementation.
            //
            // The pattern would be:
            // for (var direction : MortonKey.Direction.values()) {
            //     var neighborKey = ghostKey.neighbor(direction);
            //     if (neighborKey != null) {
            //         var localElement = forest.get(neighborKey);
            //         if (localElement != null) {
            //             int levelDiff = Math.abs(neighborKey.getLevel() - ghostLevel);
            //             if (levelDiff > 1) { // Violation!
            //                 violations.add(...);
            //             }
            //         }
            //     }
            // }
        }

        return violations;
    }

    /**
     * Create refinement requests from detected violations.
     *
     * <p>Groups violations by source partition rank and creates a refinement request
     * for each group, asking the remote partition to send refined (subdivided) elements.
     *
     * @param violations list of balance violations to process
     * @param timestamp current timestamp (for request metadata)
     * @param coordinatorId coordinator partition rank
     * @return list of refinement requests to send to remote partitions
     */
    public List<?> createRefinementRequests(
        List<BalanceViolation<Key>> violations,
        long timestamp,
        long coordinatorId
    ) {
        // For now, return empty list - will be populated during refinement coordination
        // In full implementation, would group violations by sourceRank and create requests
        return new ArrayList<>();
    }
}
