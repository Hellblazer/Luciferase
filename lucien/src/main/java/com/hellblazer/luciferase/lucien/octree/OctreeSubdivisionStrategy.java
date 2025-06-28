/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

/**
 * Octree-specific subdivision strategy implementation. Optimized for cubic spatial decomposition with Morton ordering.
 *
 * Key features: - Efficient child node calculation using Morton codes - Support for entity spanning across multiple
 * octants - Adaptive subdivision based on entity distribution - Memory-efficient single child creation
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class OctreeSubdivisionStrategy<ID extends EntityID, Content>
extends SubdivisionStrategy<MortonKey, ID, Content> {

    private static final int OCTREE_CHILDREN = 8;

    /**
     * Create a balanced strategy for mixed workloads
     */
    public static <ID extends EntityID, Content> OctreeSubdivisionStrategy<ID, Content> balanced() {
        var strategy = new OctreeSubdivisionStrategy<ID, Content>();
        strategy.withMinEntitiesForSplit(4).withLoadFactor(0.75).withSpanningThreshold(0.5);
        return strategy;
    }

    /**
     * Create a strategy optimized for dense point clouds
     */
    public static <ID extends EntityID, Content> OctreeSubdivisionStrategy<ID, Content> forDensePointClouds() {
        var strategy = new OctreeSubdivisionStrategy<ID, Content>();
        strategy.withMinEntitiesForSplit(8).withLoadFactor(0.9).withSpanningThreshold(0.1);
        return strategy;
    }

    /**
     * Create a strategy optimized for large entities
     */
    public static <ID extends EntityID, Content> OctreeSubdivisionStrategy<ID, Content> forLargeEntities() {
        var strategy = new OctreeSubdivisionStrategy<ID, Content>();
        strategy.withMinEntitiesForSplit(2).withLoadFactor(0.5).withSpanningThreshold(0.7);
        return strategy;
    }

    @Override
    public Set<MortonKey> calculateTargetNodes(MortonKey parentIndex, byte parentLevel, EntityBounds entityBounds,
                                               AbstractSpatialIndex<MortonKey, ID, Content, ?> spatialIndex) {
        var targetNodes = new HashSet<MortonKey>();

        if (entityBounds == null) {
            return targetNodes;
        }

        // Decode parent node location
        var parentCoords = MortonCurve.decode(parentIndex.getMortonCode());
        var parentCellSize = Constants.lengthAtLevel(parentLevel);
        var childCellSize = parentCellSize / 2;
        var childLevel = (byte) (parentLevel + 1);

        // Check each octant
        for (var i = 0; i < OCTREE_CHILDREN; i++) {
            var childX = parentCoords[0] + ((i & 1) != 0 ? childCellSize : 0);
            var childY = parentCoords[1] + ((i & 2) != 0 ? childCellSize : 0);
            var childZ = parentCoords[2] + ((i & 4) != 0 ? childCellSize : 0);

            // Check if entity bounds intersect this octant
            if (entityBounds.intersectsCube(childX, childY, childZ, childCellSize)) {
                // Calculate child Morton code
                var childCenter = new Point3f(childX + childCellSize / 2.0f, childY + childCellSize / 2.0f,
                                                  childZ + childCellSize / 2.0f);
                var childIndex = new MortonKey(Constants.calculateMortonIndex(childCenter, childLevel), childLevel);
                targetNodes.add(childIndex);
            }
        }

        return targetNodes;
    }

    @Override
    public SubdivisionResult determineStrategy(SubdivisionContext<MortonKey, ID> context) {
        // Check if we're at maximum depth
        if (context.isAtMaxDepth()) {
            return SubdivisionResult.insertInParent("At maximum depth");
        }

        // During bulk operations, defer subdivision for better performance
        if (context.isBulkOperation && !context.isCriticallyOverloaded()) {
            return SubdivisionResult.deferSubdivision("Bulk operation in progress");
        }

        // If critically overloaded, force immediate subdivision
        if (context.isCriticallyOverloaded()) {
            return SubdivisionResult.forceSubdivision("Node critically overloaded");
        }

        // Check if we have enough entities to warrant subdivision
        if (context.currentNodeSize < minEntitiesForSplit) {
            return SubdivisionResult.insertInParent("Too few entities for efficient subdivision");
        }

        // Estimate subdivision benefit
        var benefit = estimateSubdivisionBenefit(context);

        // Low benefit - keep in parent
        if (benefit < 0.3) {
            return SubdivisionResult.insertInParent("Low subdivision benefit score: " + benefit);
        }

        // Check if entity should span multiple children
        var nodeSize = Constants.lengthAtLevel(context.nodeLevel);
        if (shouldSpanEntity(context, nodeSize)) {
            // Calculate which children the entity intersects
            var targetChildren = calculateTargetNodes(context.nodeIndex, context.nodeLevel, context.newEntityBounds,
                                                      null);

            if (targetChildren.size() > 1) {
                return SubdivisionResult.splitToChildren(targetChildren,
                                                         "Entity spans " + targetChildren.size() + " children");
            }
        }

        // High benefit and not spanning - use standard subdivision
        if (benefit > 0.7) {
            return SubdivisionResult.forceSubdivision("High subdivision benefit score: " + benefit);
        }

        // Medium benefit - consider single child creation if entity fits in one octant
        if (context.newEntityBounds != null) {
            var targetChild = calculateSingleTargetChild(context);
            if (targetChild != null) {
                return SubdivisionResult.createSingleChild(targetChild, "Entity fits in single child octant");
            }
        }

        // Default to standard subdivision
        return SubdivisionResult.forceSubdivision("Standard subdivision threshold reached");
    }

    @Override
    protected double estimateEntitySizeFactor(SubdivisionContext<MortonKey, ID> context) {
        if (context.newEntityBounds == null) {
            return 0.5; // Default for point entities
        }

        // Calculate entity size
        var entitySizeX = context.newEntityBounds.getMaxX() - context.newEntityBounds.getMinX();
        var entitySizeY = context.newEntityBounds.getMaxY() - context.newEntityBounds.getMinY();
        var entitySizeZ = context.newEntityBounds.getMaxZ() - context.newEntityBounds.getMinZ();
        var maxEntityDimension = Math.max(Math.max(entitySizeX, entitySizeY), entitySizeZ);

        // Get node size at current level
        var nodeSize = Constants.lengthAtLevel(context.nodeLevel);

        // Calculate relative size (0.0 to 1.0, clamped)
        var relativeFactor = maxEntityDimension / nodeSize;
        return Math.min(relativeFactor, 1.0);
    }

    /**
     * Calculate the single child octant where a bounded entity would fit
     *
     * @return Morton index of the target child, or null if entity spans multiple children
     */
    private MortonKey calculateSingleTargetChild(SubdivisionContext<MortonKey, ID> context) {
        if (context.newEntityBounds == null) {
            return null;
        }

        // Calculate entity center
        var centerX = (context.newEntityBounds.getMinX() + context.newEntityBounds.getMaxX()) / 2.0f;
        var centerY = (context.newEntityBounds.getMinY() + context.newEntityBounds.getMaxY()) / 2.0f;
        var centerZ = (context.newEntityBounds.getMinZ() + context.newEntityBounds.getMaxZ()) / 2.0f;
        var entityCenter = new Point3f(centerX, centerY, centerZ);

        // Decode parent node
        var parentCoords = MortonCurve.decode(context.nodeIndex.getMortonCode());
        var parentCellSize = Constants.lengthAtLevel(context.nodeLevel);
        var childCellSize = parentCellSize / 2;

        // Determine which octant the center falls into
        var octant = 0;
        if (entityCenter.x >= parentCoords[0] + childCellSize) {
            octant |= 1;
        }
        if (entityCenter.y >= parentCoords[1] + childCellSize) {
            octant |= 2;
        }
        if (entityCenter.z >= parentCoords[2] + childCellSize) {
            octant |= 4;
        }

        // Calculate child coordinates
        var childX = parentCoords[0] + ((octant & 1) != 0 ? childCellSize : 0);
        var childY = parentCoords[1] + ((octant & 2) != 0 ? childCellSize : 0);
        var childZ = parentCoords[2] + ((octant & 4) != 0 ? childCellSize : 0);

        // Verify the entity fits entirely within this octant
        if (context.newEntityBounds.getMinX() >= childX && context.newEntityBounds.getMinY() >= childY
        && context.newEntityBounds.getMinZ() >= childZ && context.newEntityBounds.getMaxX() <= childX + childCellSize
        && context.newEntityBounds.getMaxY() <= childY + childCellSize
        && context.newEntityBounds.getMaxZ() <= childZ + childCellSize) {

            // Entity fits in single octant
            var childCenter = new Point3f(childX + childCellSize / 2.0f, childY + childCellSize / 2.0f,
                                              childZ + childCellSize / 2.0f);
            var childLevel = (byte) (context.nodeLevel + 1);
            return new MortonKey(Constants.calculateMortonIndex(childCenter, childLevel), childLevel);
        }

        return null; // Entity spans multiple octants
    }
}
