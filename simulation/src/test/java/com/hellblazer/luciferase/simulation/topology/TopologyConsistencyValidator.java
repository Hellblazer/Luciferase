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
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;

import java.util.*;

/**
 * Validates topology consistency across distributed bubble grids.
 * <p>
 * Ensures all nodes agree on bubble structure, entity distribution,
 * and neighbor relationships after topology changes.
 *
 * @author hal.hildebrand
 */
public class TopologyConsistencyValidator {

    /**
     * Validates that all bubble grids have consistent topology.
     * <p>
     * Checks:
     * - Same number of bubbles across all grids
     * - Same bubble IDs across all grids
     * - Entity conservation across all grids
     *
     * @param grids       list of bubble grids to validate
     * @param accountants list of entity accountants (parallel to grids)
     * @return validation result
     */
    public ConsistencyResult validateTopology(List<TetreeBubbleGrid> grids,
                                               List<EntityAccountant> accountants) {
        if (grids.isEmpty()) {
            return new ConsistencyResult(false, "No grids to validate");
        }

        if (grids.size() != accountants.size()) {
            return new ConsistencyResult(false,
                                         "Grid count (" + grids.size() + ") doesn't match accountant count (" + accountants.size() + ")");
        }

        var errors = new ArrayList<String>();

        // Check bubble count consistency
        var bubbleCounts = new HashSet<Integer>();
        for (var grid : grids) {
            bubbleCounts.add(grid.getAllBubbles().size());
        }
        if (bubbleCounts.size() > 1) {
            errors.add("Inconsistent bubble counts across grids: " + bubbleCounts);
        }

        // Check bubble ID consistency
        var firstBubbleIds = new HashSet<UUID>();
        for (var bubble : grids.get(0).getAllBubbles()) {
            firstBubbleIds.add(bubble.id());
        }

        for (int i = 1; i < grids.size(); i++) {
            var otherBubbleIds = new HashSet<UUID>();
            for (var bubble : grids.get(i).getAllBubbles()) {
                otherBubbleIds.add(bubble.id());
            }

            if (!firstBubbleIds.equals(otherBubbleIds)) {
                errors.add("Grid " + i + " has different bubble IDs than grid 0");
            }
        }

        // Check entity conservation in each accountant
        for (int i = 0; i < accountants.size(); i++) {
            var validation = accountants.get(i).validate();
            if (!validation.success()) {
                errors.add("Accountant " + i + " validation failed: " + validation.details());
            }
        }

        // Check total entity counts match
        var entityCounts = new HashSet<Long>();
        for (var accountant : accountants) {
            var distribution = accountant.getDistribution();
            long totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
            entityCounts.add(totalEntities);
        }
        if (entityCounts.size() > 1) {
            errors.add("Inconsistent total entity counts across accountants: " + entityCounts);
        }

        return new ConsistencyResult(errors.isEmpty(),
                                     errors.isEmpty() ? "Topology consistent across all nodes" :
                                     String.join("; ", errors));
    }

    /**
     * Validates entity distribution statistics.
     * <p>
     * Checks for reasonable entity distribution (no empty bubbles,
     * no severely imbalanced bubbles).
     *
     * @param accountant entity accountant to analyze
     * @return distribution result
     */
    public DistributionResult validateDistribution(EntityAccountant accountant) {
        var distribution = accountant.getDistribution();

        if (distribution.isEmpty()) {
            return new DistributionResult(false, "No bubbles in distribution", 0, 0, 0.0);
        }

        var counts = distribution.values();
        int min = counts.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
        double avg = counts.stream().mapToInt(Integer::intValue).average().orElse(0.0);

        var warnings = new ArrayList<String>();

        // Check for empty bubbles (should have been removed by merge)
        if (min == 0) {
            warnings.add("Found empty bubble(s)");
        }

        // Check for severe imbalance (max > 10x avg)
        if (max > 10 * avg) {
            warnings.add("Severe imbalance: max (" + max + ") > 10x avg (" + avg + ")");
        }

        boolean success = warnings.isEmpty();
        String message = success ? "Distribution healthy" : String.join("; ", warnings);

        return new DistributionResult(success, message, min, max, avg);
    }

    /**
     * Result of topology consistency validation.
     *
     * @param consistent true if all nodes agree on topology
     * @param message    explanation of result
     */
    public record ConsistencyResult(boolean consistent, String message) {
    }

    /**
     * Result of entity distribution analysis.
     *
     * @param healthy true if distribution is reasonable
     * @param message explanation of result
     * @param minEntitiesPerBubble minimum entities in any bubble
     * @param maxEntitiesPerBubble maximum entities in any bubble
     * @param avgEntitiesPerBubble average entities per bubble
     */
    public record DistributionResult(
        boolean healthy,
        String message,
        int minEntitiesPerBubble,
        int maxEntitiesPerBubble,
        double avgEntitiesPerBubble
    ) {
    }
}
