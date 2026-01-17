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
 * Proposal to split an overcrowded bubble into two bubbles.
 * <p>
 * Triggered when bubble exceeds 5000 entities (120% frame utilization).
 * Entities are redistributed based on spatial clustering.
 * <p>
 * Validation checks:
 * <ul>
 *   <li>Source bubble exists in grid</li>
 *   <li>Entity count exceeds split threshold (>5000)</li>
 *   <li>Proposed split plane creates valid partitions</li>
 * </ul>
 *
 * @param proposalId   unique proposal identifier
 * @param sourceBubble bubble to split
 * @param splitPlane   plane dividing entities into two groups
 * @param viewId       view context
 * @param timestamp    proposal creation time (simulation time)
 */
public record SplitProposal(
    UUID proposalId,
    UUID sourceBubble,
    SplitPlane splitPlane,
    Digest viewId,
    long timestamp
) implements TopologyProposal {

    @Override
    public ValidationResult validate(TetreeBubbleGrid grid) {
        // Check bubble exists
        var bubble = grid.getBubbleById(sourceBubble);
        if (bubble == null) {
            return new ValidationResult(false, "Source bubble not found: " + sourceBubble);
        }

        // Check entity count exceeds split threshold
        int entityCount = bubble.entityCount();
        if (entityCount <= 5000) {
            return new ValidationResult(false,
                                        "Entity count (" + entityCount + ") does not exceed split threshold (5000)");
        }

        // Validate split plane creates non-empty partitions
        if (splitPlane == null) {
            return new ValidationResult(false, "Split plane cannot be null");
        }

        // Check for empty entities early (consistency with MoveProposal)
        var entityRecords = bubble.getAllEntityRecords();
        if (entityRecords.isEmpty()) {
            return new ValidationResult(false, "Cannot split bubble with no entities");
        }

        // Split plane validation (basic - actual split logic is more complex)
        // Compute tight AABB from actual entity positions for Byzantine-resistant validation
        if (!splitPlane.intersectsEntityBounds(entityRecords)) {
            return new ValidationResult(false, "Split plane does not intersect entity bounds");
        }

        return ValidationResult.success();
    }
}
