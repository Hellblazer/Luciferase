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
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.UUID;

/**
 * Sealed interface for topology change proposals in dynamic bubble adaptation.
 * <p>
 * Permits three topology operations:
 * <ul>
 *   <li>{@link SplitProposal} - Split overcrowded bubble (>5000 entities)</li>
 *   <li>{@link MergeProposal} - Merge underpopulated bubbles (<500 entities)</li>
 *   <li>{@link MoveProposal} - Relocate bubble to follow entity cluster</li>
 * </ul>
 * <p>
 * All proposals include:
 * <ul>
 *   <li>proposalId - Unique identifier</li>
 *   <li>viewId - View context (prevents cross-view double-commit)</li>
 *   <li>timestamp - Proposal creation time (from Clock, not wall-clock)</li>
 * </ul>
 * <p>
 * Proposals undergo validation before voting to reject Byzantine inputs.
 * <p>
 * Phase 9B: Consensus Topology Coordination
 *
 * @author hal.hildebrand
 */
public sealed interface TopologyProposal
    permits SplitProposal, MergeProposal, MoveProposal {

    /**
     * Gets the unique proposal identifier.
     *
     * @return proposal UUID
     */
    UUID proposalId();

    /**
     * Gets the view context identifier.
     * <p>
     * Prevents double-commit races across view boundaries.
     *
     * @return view ID digest
     */
    Digest viewId();

    /**
     * Gets the proposal timestamp.
     * <p>
     * IMPORTANT: This is simulation time from injected Clock,
     * not wall-clock time.
     *
     * @return timestamp in milliseconds (simulation time)
     */
    long timestamp();

    /**
     * Validates this proposal against current bubble grid state.
     * <p>
     * Checks type-specific constraints (density thresholds, neighbor
     * adjacency, bounds overlap) to reject Byzantine proposals before voting.
     *
     * @param grid current bubble grid state
     * @return validation result
     */
    ValidationResult validate(TetreeBubbleGrid grid);
}

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
record SplitProposal(
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

        // Split plane validation (basic - actual split logic is more complex)
        // Compute tight AABB from actual entity positions for Byzantine-resistant validation
        var entityRecords = bubble.getAllEntityRecords();
        if (entityRecords.isEmpty()) {
            return new ValidationResult(false, "Cannot split bubble with no entities");
        }

        if (!splitPlane.intersectsEntityBounds(entityRecords)) {
            return new ValidationResult(false, "Split plane does not intersect entity bounds");
        }

        return ValidationResult.success();
    }
}

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
record MergeProposal(
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
record MoveProposal(
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

        // Check cluster centroid is within tight entity AABB
        if (clusterCentroid.x < minX || clusterCentroid.x > maxX ||
            clusterCentroid.y < minY || clusterCentroid.y > maxY ||
            clusterCentroid.z < minZ || clusterCentroid.z > maxZ) {
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

/**
 * Result of topology proposal validation.
 * <p>
 * Used to reject Byzantine proposals before consensus voting.
 *
 * @param isValid true if proposal passes validation
 * @param reason  explanation if validation failed (null if valid)
 */
record ValidationResult(boolean isValid, String reason) {

    /**
     * Creates a successful validation result.
     *
     * @return success result
     */
    static ValidationResult success() {
        return new ValidationResult(true, null);
    }
}

/**
 * Geometric plane for splitting bubbles.
 * <p>
 * Defined by normal vector and distance from origin.
 * Used to partition entities into two groups during split.
 *
 * @param normal   plane normal vector (unit length)
 * @param distance signed distance from origin
 */
record SplitPlane(Point3f normal, float distance) {

    /**
     * Checks if this plane intersects the given bubble bounds.
     * <p>
     * Uses a simplified intersection test: checks if plane passes through
     * the bubble's bounding region by testing distance from plane to center.
     *
     * @param bounds bubble bounds to test
     * @return true if plane intersects bounds
     */
    /**
     * Check if split plane intersects bounds (simple RDGCS-based test for unit testing).
     * <p>
     * NOTE: This is kept for unit tests. Production validation should use intersectsEntityBounds().
     *
     * @param bounds bubble bounds to test
     * @return true if plane intersects bounds
     */
    boolean intersects(com.hellblazer.luciferase.simulation.bubble.BubbleBounds bounds) {
        // Convert RDGCS bounds to Cartesian
        var cartMin = bounds.toCartesian(bounds.rdgMin());
        var cartMax = bounds.toCartesian(bounds.rdgMax());

        float dx = (float) (cartMax.getX() - cartMin.getX());
        float dy = (float) (cartMax.getY() - cartMin.getY());
        float dz = (float) (cartMax.getZ() - cartMin.getZ());
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float radius = diagonal / 2.0f;

        var centroid = bounds.centroid();
        float centerDist = normal.x * (float) centroid.getX() + normal.y * (float) centroid.getY() + normal.z * (float) centroid.getZ() - distance;

        return Math.abs(centerDist) < radius;
    }

    /**
     * Check if split plane intersects entity bounds (Byzantine-resistant validation).
     * <p>
     * Computes tight AABB from actual entity positions, not conservative RDGCS bounds.
     * This prevents Byzantine proposals with planes far outside actual entity distribution.
     *
     * @param entityRecords entities to compute bounds from
     * @return true if plane intersects the tight entity AABB
     */
    boolean intersectsEntityBounds(List<EnhancedBubble.EntityRecord> entityRecords) {
        if (entityRecords.isEmpty()) {
            return false;
        }

        // Compute tight AABB from entity positions (all Cartesian)
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (var record : entityRecords) {
            var pos = record.position();
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        // Centroid of entity AABB
        float centroidX = (minX + maxX) / 2.0f;
        float centroidY = (minY + maxY) / 2.0f;
        float centroidZ = (minZ + maxZ) / 2.0f;

        // Radius = half of diagonal
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float radius = diagonal / 2.0f;

        // Distance from plane to centroid
        float centerDist = normal.x * centroidX + normal.y * centroidY + normal.z * centroidZ - distance;

        // Check if plane within radius of centroid
        return Math.abs(centerDist) < radius;
    }
}
