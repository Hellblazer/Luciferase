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
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(TwoOneBalanceChecker.class);

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

            // Check each neighbor of this ghost element
            // For MortonKey, use Direction-based neighbor iteration
            if (ghostKey instanceof MortonKey mortonGhost) {
                checkMortonNeighborsForViolations(mortonGhost, ghostLevel, forest,
                                                 ghost.getOwnerRank(), violations);
            }
            // Additional key types (TetreeKey, etc.) would be handled similarly
        }

        log.debug("Found {} violations in ghost layer with {} ghost elements",
                 violations.size(), ghostLayer.getNumGhostElements());

        return violations;
    }

    /**
     * Check MortonKey neighbors for 2:1 balance violations.
     */
    @SuppressWarnings("unchecked")
    private void checkMortonNeighborsForViolations(
        MortonKey ghostKey,
        int ghostLevel,
        Forest<Key, ID, Content> forest,
        int sourceRank,
        List<BalanceViolation<Key>> violations
    ) {
        // Iterate through all possible directions
        for (var direction : MortonKey.Direction.values()) {
            MortonKey neighborKey = ghostKey.neighbor(direction);
            if (neighborKey == null) continue;

            // Check if neighbor exists in any tree in the forest
            boolean foundNeighbor = false;
            for (var tree : forest.getAllTrees()) {
                var spatialIndex = tree.getSpatialIndex();
                if (spatialIndex.containsSpatialKey((Key) neighborKey)) {
                    foundNeighbor = true;
                    break;
                }
            }

            if (foundNeighbor) {
                int localLevel = neighborKey.getLevel();
                int levelDiff = Math.abs(localLevel - ghostLevel);

                // Violation detected if level difference > 1
                if (levelDiff > 1) {
                    violations.add(new BalanceViolation<>((Key) neighborKey, (Key) ghostKey,
                                                         localLevel, ghostLevel,
                                                         levelDiff, sourceRank));
                    log.trace("Violation: local level {} vs ghost level {} (diff={})",
                             localLevel, ghostLevel, levelDiff);
                }
            }
        }
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
