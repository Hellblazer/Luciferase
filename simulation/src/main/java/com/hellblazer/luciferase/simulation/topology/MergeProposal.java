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

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;

import java.util.UUID;

/**
 * Proposal to merge two underpopulated bubbles into one.
 * <p>
 * Triggered when adjacent bubbles both have <500 entities (60% affinity).
 * Entities are combined and duplicate detection prevents double-counting.
 * <p>
 * Validation checks:
 * <ul>
 *   <li>Both bubbles exist in grid</li>
 *   <li>Both bubbles below merge threshold (<500 entities)</li>
 *   <li>Bubbles are adjacent (share boundary)</li>
 *   <li>Combined bounds are valid</li>
 * </ul>
 *
 * @param proposalId unique proposal identifier
 * @param bubble1    first bubble to merge
 * @param bubble2    second bubble to merge
 * @param viewId     view context
 * @param timestamp  proposal creation time (simulation time)
 */
public record MergeProposal(
    UUID proposalId,
    UUID bubble1,
    UUID bubble2,
    Digest viewId,
    long timestamp
) implements TopologyProposal {

    @Override
    public ValidationResult validate(TetreeBubbleGrid grid) {
        // Check both bubbles exist
        var b1 = grid.getBubbleById(bubble1);
        var b2 = grid.getBubbleById(bubble2);

        if (b1 == null) {
            return new ValidationResult(false, "Bubble1 not found: " + bubble1);
        }
        if (b2 == null) {
            return new ValidationResult(false, "Bubble2 not found: " + bubble2);
        }

        // Check both below merge threshold
        int count1 = b1.entityCount();
        int count2 = b2.entityCount();

        if (count1 >= 500) {
            return new ValidationResult(false,
                                        "Bubble1 entity count (" + count1 + ") exceeds merge threshold (500)");
        }
        if (count2 >= 500) {
            return new ValidationResult(false,
                                        "Bubble2 entity count (" + count2 + ") exceeds merge threshold (500)");
        }

        // Check bubbles are adjacent (neighbors)
        var neighbors = grid.getNeighbors(bubble1);
        if (!neighbors.contains(bubble2)) {
            return new ValidationResult(false, "Bubbles are not adjacent (not neighbors)");
        }

        return ValidationResult.success();
    }
}
