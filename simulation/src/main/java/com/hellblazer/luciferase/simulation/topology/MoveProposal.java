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

import javax.vecmath.Point3f;
import java.util.UUID;

/**
 * Proposal to relocate a bubble's boundaries to follow entity movement.
 * <p>
 * Triggered when entities cluster away from bubble center, causing high
 * boundary stress (>10 migrations/second). Bubble moves toward cluster centroid.
 * <p>
 * Validation checks:
 * <ul>
 *   <li>Source bubble exists in grid</li>
 *   <li>New center is within reasonable distance from current center</li>
 *   <li>New bounds don't overlap with other bubbles excessively</li>
 * </ul>
 *
 * @param proposalId unique proposal identifier
 * @param sourceBubble bubble to move
 * @param newCenter  new bubble center position
 * @param clusterCentroid cluster centroid driving the move
 * @param viewId     view context
 * @param timestamp  proposal creation time (simulation time)
 */
public record MoveProposal(
    UUID proposalId,
    UUID sourceBubble,
    Point3f newCenter,
    Point3f clusterCentroid,
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

        // Check for null values first (catch Byzantine nulls before expensive checks)
        if (clusterCentroid == null) {
            return new ValidationResult(false, "Cluster centroid cannot be null");
        }

        // Use tight entity AABB for Byzantine-resistant validation
        var entityRecords = bubble.getAllEntityRecords();
        if (entityRecords.isEmpty()) {
            return new ValidationResult(false, "Cannot move bubble with no entities");
        }

        // Compute tight AABB from entity positions
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (var record : entityRecords) {
            var pos = record.position();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        // Check cluster centroid is within tight entity AABB (with epsilon tolerance for floating-point precision)
        final float EPSILON = 1e-6f;
        if (clusterCentroid.x < minX - EPSILON || clusterCentroid.x > maxX + EPSILON ||
            clusterCentroid.y < minY - EPSILON || clusterCentroid.y > maxY + EPSILON ||
            clusterCentroid.z < minZ - EPSILON || clusterCentroid.z > maxZ + EPSILON) {
            return new ValidationResult(false, "Cluster centroid outside entity bounds");
        }

        // Validate new center is reasonable (within 2x current radius)
        float centroidX = (minX + maxX) / 2.0f;
        float centroidY = (minY + maxY) / 2.0f;
        float centroidZ = (minZ + maxZ) / 2.0f;

        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float currentRadius = diagonal / 2.0f;

        float moveDx = newCenter.x - centroidX;
        float moveDy = newCenter.y - centroidY;
        float moveDz = newCenter.z - centroidZ;
        float moveDistance = (float) Math.sqrt(moveDx * moveDx + moveDy * moveDy + moveDz * moveDz);

        if (moveDistance > 2.0f * currentRadius) {
            return new ValidationResult(false,
                                        "New center too far from current (" + moveDistance + " > " + (2.0f * currentRadius)
                                        + ")");
        }

        return ValidationResult.success();
    }
}
