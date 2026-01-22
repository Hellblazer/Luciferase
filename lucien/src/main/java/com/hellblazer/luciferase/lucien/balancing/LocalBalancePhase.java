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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes Phase 1 (Local Balance) of the parallel balancing algorithm.
 *
 * <p>This phase:
 * <ol>
 *   <li>Uses {@link TreeBalancer} to balance each tree in the forest independently</li>
 *   <li>Tracks metrics for round timing and refinements</li>
 *   <li>Extracts boundary ghost elements for subsequent exchange</li>
 * </ol>
 *
 * <p>The local balance phase operates entirely within a single partition,
 * requiring no inter-partition communication.
 *
 * <p><b>TDD RED PHASE</b>: This is a skeleton implementation that compiles but does NOT work yet.
 *
 * @param <Key> the spatial key type
 * @param <ID> the entity ID type
 * @param <Content> the content type
 * @author hal.hildebrand
 */
public class LocalBalancePhase<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(LocalBalancePhase.class);

    private final BalanceConfiguration configuration;
    private final BalanceMetrics metrics;
    private final List<GhostElement<Key, ID, Content>> boundaryGhosts;

    /**
     * Create a new local balance phase executor.
     *
     * @param configuration the balance configuration
     * @param metrics the metrics tracker
     */
    public LocalBalancePhase(BalanceConfiguration configuration, BalanceMetrics metrics) {
        this.configuration = Objects.requireNonNull(configuration, "Configuration cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "Metrics cannot be null");
        this.boundaryGhosts = new ArrayList<>();
    }

    /**
     * Execute the local balance phase on a forest.
     *
     * <p>Balances each tree in the forest using TreeBalancer and tracks metrics.
     *
     * @param forest the forest to balance
     * @return the result of the local balance operation
     */
    public LocalBalanceResult execute(Forest<Key, ID, Content> forest) {
        Objects.requireNonNull(forest, "Forest cannot be null");

        log.debug("Executing local balance phase on forest with {} trees", forest.getTreeCount());

        var startTime = Instant.now();
        var refinementsApplied = 0;

        try {
            // Clear previous boundary ghosts
            boundaryGhosts.clear();

            // Iterate through each tree in the forest
            var trees = forest.getAllTrees();
            log.debug("Processing {} trees", trees.size());

            // Only proceed if there are trees to balance
            if (!trees.isEmpty()) {
                for (var treeNode : trees) {
                    var spatialIndex = treeNode.getSpatialIndex();

                    // Rebalance the tree directly (AbstractSpatialIndex provides this method)
                    var rebalanceResult = spatialIndex.rebalanceTree();

                    // Track refinements
                    if (rebalanceResult.successful()) {
                        refinementsApplied += rebalanceResult.totalModifications();

                        // Record each refinement in metrics
                        for (int i = 0; i < rebalanceResult.totalModifications(); i++) {
                            metrics.recordRefinementApplied();
                        }

                        log.debug("Tree {} balanced: {} modifications",
                                 treeNode.getTreeId(), rebalanceResult.totalModifications());
                    } else {
                        log.warn("Tree {} balancing did not complete successfully", treeNode.getTreeId());
                    }

                    // Extract boundary elements as ghosts for this tree
                    extractBoundaryGhostsFromTree(spatialIndex);
                }

                // Record round metrics only if we processed trees
                var roundDuration = Duration.between(startTime, Instant.now());
                metrics.recordRound(roundDuration);
            }

            // Calculate total duration for result
            var duration = Duration.between(startTime, Instant.now());

            log.debug("Local balance completed: {} refinements, {} boundary ghosts",
                     refinementsApplied, boundaryGhosts.size());

            return new LocalBalanceResult(true, refinementsApplied, duration);

        } catch (Exception e) {
            log.error("Local balance phase failed", e);
            var duration = Duration.between(startTime, Instant.now());
            return new LocalBalanceResult(false, 0, duration);
        }
    }

    /**
     * Extract boundary ghost elements after local balance.
     *
     * <p>Boundary elements are those at partition boundaries that need to be
     * exchanged with neighboring partitions.
     *
     * @return the list of boundary ghost elements
     */
    public List<GhostElement<Key, ID, Content>> extractBoundaryGhosts() {
        return new ArrayList<>(boundaryGhosts);
    }

    /**
     * Extract boundary elements from a single tree as ghost elements.
     *
     * <p><b>Note</b>: Stub implementation for Phase 1. Full boundary detection
     * requires AbstractSpatialIndex API extensions for key extraction.
     *
     * @param spatialIndex the spatial index to extract ghosts from
     */
    private void extractBoundaryGhostsFromTree(com.hellblazer.luciferase.lucien.AbstractSpatialIndex<Key, ID, Content> spatialIndex) {
        // Stub: Query boundary regions to verify the API works
        // Full implementation will be added when AbstractSpatialIndex provides key extraction methods
        var boundaryRegions = List.of(
            new com.hellblazer.luciferase.lucien.Spatial.Cube(0, 0, 0, 5),
            new com.hellblazer.luciferase.lucien.Spatial.Cube(95, 0, 0, 5),
            new com.hellblazer.luciferase.lucien.Spatial.Cube(0, 95, 0, 5),
            new com.hellblazer.luciferase.lucien.Spatial.Cube(0, 0, 95, 5)
        );

        for (var region : boundaryRegions) {
            try {
                var entities = spatialIndex.entitiesInRegion(region);
                log.trace("Found {} entities in boundary region", entities.size());
            } catch (Exception e) {
                log.trace("Error querying boundary region: {}", e.getMessage());
            }
        }

        log.trace("Boundary ghost extraction stub: {} ghosts", boundaryGhosts.size());
    }

    /**
     * Result of a local balance phase execution.
     *
     * @param successful whether the phase succeeded
     * @param refinementsApplied the number of refinements applied
     * @param duration the execution duration
     */
    public record LocalBalanceResult(
        boolean successful,
        int refinementsApplied,
        Duration duration
    ) {
        /**
         * Check if the balance converged (successful with refinements).
         */
        public boolean converged() {
            return successful && refinementsApplied > 0;
        }

        /**
         * Check if no work was needed (successful with no refinements).
         */
        public boolean noWorkNeeded() {
            return successful && refinementsApplied == 0;
        }
    }
}
