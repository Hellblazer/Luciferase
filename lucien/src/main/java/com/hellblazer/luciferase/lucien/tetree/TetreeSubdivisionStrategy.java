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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.HashSet;
import java.util.Set;

/**
 * Tetree-specific subdivision strategy implementation. Optimized for tetrahedral spatial decomposition.
 *
 * Key features: - Tetrahedral geometry-aware subdivision - Support for entity spanning across multiple tetrahedra -
 * Adaptive subdivision based on tetrahedral volume - Efficient child tetrahedron calculation
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class TetreeSubdivisionStrategy<ID extends EntityID, Content>
extends SubdivisionStrategy<BaseTetreeKey<? extends BaseTetreeKey>, ID, Content> {

    private static final int TETREE_CHILDREN = 8;

    /**
     * Create a balanced strategy for mixed workloads
     */
    public static <ID extends EntityID, Content> TetreeSubdivisionStrategy<ID, Content> balanced() {
        TetreeSubdivisionStrategy<ID, Content> strategy = new TetreeSubdivisionStrategy<>();
        strategy.withMinEntitiesForSplit(4).withLoadFactor(0.75).withSpanningThreshold(0.5);
        return strategy;
    }

    /**
     * Create a strategy optimized for dense point clouds
     */
    public static <ID extends EntityID, Content> TetreeSubdivisionStrategy<ID, Content> forDensePointClouds() {
        TetreeSubdivisionStrategy<ID, Content> strategy = new TetreeSubdivisionStrategy<>();
        strategy.withMinEntitiesForSplit(8).withLoadFactor(0.9).withSpanningThreshold(0.1);
        return strategy;
    }

    /**
     * Create a strategy optimized for large entities
     */
    public static <ID extends EntityID, Content> TetreeSubdivisionStrategy<ID, Content> forLargeEntities() {
        TetreeSubdivisionStrategy<ID, Content> strategy = new TetreeSubdivisionStrategy<>();
        strategy.withMinEntitiesForSplit(2).withLoadFactor(0.5).withSpanningThreshold(0.7);
        return strategy;
    }

    @Override
    public Set<BaseTetreeKey<?>> calculateTargetNodes(BaseTetreeKey<? extends BaseTetreeKey> parentIndex,
                                                      byte parentLevel, EntityBounds entityBounds,
                                                      AbstractSpatialIndex<BaseTetreeKey<? extends BaseTetreeKey>, ID, Content, ?> spatialIndex) {
        Set<BaseTetreeKey<?>> targetNodes = new HashSet<>();

        if (entityBounds == null) {
            return targetNodes;
        }

        // Get parent tetrahedron
        var parentTet = Tet.tetrahedron(parentIndex);
        byte childLevel = (byte) (parentLevel + 1);

        // Check each child tetrahedron
        for (int i = 0; i < TETREE_CHILDREN; i++) {
            Tet childTet = parentTet.child(i);

            // Get child tetrahedron vertices
            Point3i[] intVertices = childTet.coordinates();
            Point3f[] vertices = convertToFloat(intVertices);

            // Check if entity bounds intersect this tetrahedron
            if (entityBoundsIntersectsTetrahedron(entityBounds, vertices)) {
                targetNodes.add(childTet.tmIndex());
            }
        }

        return targetNodes;
    }

    @Override
    public SubdivisionResult determineStrategy(SubdivisionContext<BaseTetreeKey<? extends BaseTetreeKey>, ID> context) {
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
        double benefit = estimateSubdivisionBenefit(context);

        // Low benefit - keep in parent
        if (benefit < 0.3) {
            return SubdivisionResult.insertInParent("Low subdivision benefit score: " + benefit);
        }

        // For Tetree, check if entity should span multiple tetrahedra
        Tet parentTet = Tet.tetrahedron(context.nodeIndex);
        double tetSize = estimateTetSize(parentTet);

        if (shouldSpanEntity(context, tetSize)) {
            // Calculate which child tetrahedra the entity intersects
            var targetChildren = calculateTargetNodes(context.nodeIndex, context.nodeLevel, context.newEntityBounds,
                                                      null);

            if (targetChildren.size() > 1) {
                return SubdivisionResult.splitToChildren(targetChildren,
                                                         "Entity spans " + targetChildren.size() + " child tetrahedra");
            }
        }

        // High benefit and not spanning - use standard subdivision
        if (benefit > 0.7) {
            return SubdivisionResult.forceSubdivision("High subdivision benefit score: " + benefit);
        }

        // Medium benefit - consider single child creation if entity fits in one tetrahedron
        if (context.newEntityBounds != null) {
            var targetChild = calculateSingleTargetChild(context);
            if (targetChild != null) {
                return SubdivisionResult.createSingleChild(targetChild, "Entity fits in single child tetrahedron");
            }
        }

        // Default to standard subdivision
        return SubdivisionResult.forceSubdivision("Standard subdivision threshold reached");
    }

    @Override
    protected double estimateEntitySizeFactor(SubdivisionContext<BaseTetreeKey<? extends BaseTetreeKey>, ID> context) {
        if (context.newEntityBounds == null) {
            return 0.5; // Default for point entities
        }

        // Calculate entity size
        float entitySizeX = context.newEntityBounds.getMaxX() - context.newEntityBounds.getMinX();
        float entitySizeY = context.newEntityBounds.getMaxY() - context.newEntityBounds.getMinY();
        float entitySizeZ = context.newEntityBounds.getMaxZ() - context.newEntityBounds.getMinZ();
        float maxEntityDimension = Math.max(Math.max(entitySizeX, entitySizeY), entitySizeZ);

        // Estimate tetrahedron size
        Tet tet = Tet.tetrahedron(context.nodeIndex);
        double tetSize = estimateTetSize(tet);

        // Calculate relative size (0.0 to 1.0, clamped)
        double relativeFactor = maxEntityDimension / tetSize;
        return Math.min(relativeFactor, 1.0);
    }

    /**
     * Calculate the single child tetrahedron where a bounded entity would fit
     *
     * @return Tet index of the target child, or null if entity spans multiple children
     */
    private BaseTetreeKey<? extends BaseTetreeKey> calculateSingleTargetChild(
    SubdivisionContext<BaseTetreeKey<? extends BaseTetreeKey>, ID> context) {
        if (context.newEntityBounds == null) {
            return null;
        }

        // Calculate entity center
        float centerX = (context.newEntityBounds.getMinX() + context.newEntityBounds.getMaxX()) / 2.0f;
        float centerY = (context.newEntityBounds.getMinY() + context.newEntityBounds.getMaxY()) / 2.0f;
        float centerZ = (context.newEntityBounds.getMinZ() + context.newEntityBounds.getMaxZ()) / 2.0f;
        Point3f entityCenter = new Point3f(centerX, centerY, centerZ);

        // Get parent tetrahedron
        Tet parentTet = Tet.tetrahedron(context.nodeIndex);

        // Find which child contains the entity center
        for (int i = 0; i < TETREE_CHILDREN; i++) {
            Tet childTet = parentTet.child(i);
            Point3i[] intVertices = childTet.coordinates();
            Point3f[] vertices = convertToFloat(intVertices);

            if (pointInTetrahedron(entityCenter, vertices)) {
                // Check if entire entity bounds fit within this child
                if (entityBoundsContainedInTetrahedron(context.newEntityBounds, vertices)) {
                    return childTet.tmIndex();
                }
            }
        }

        return null; // Entity spans multiple tetrahedra
    }

    /**
     * Convert Point3i array to Point3f array
     */
    private Point3f[] convertToFloat(Point3i[] intVertices) {
        Point3f[] floatVertices = new Point3f[intVertices.length];
        for (int i = 0; i < intVertices.length; i++) {
            floatVertices[i] = new Point3f(intVertices[i].x, intVertices[i].y, intVertices[i].z);
        }
        return floatVertices;
    }

    /**
     * Check if entity bounds are completely contained within a tetrahedron
     */
    private boolean entityBoundsContainedInTetrahedron(EntityBounds bounds, Point3f[] tetVertices) {
        // Check all 8 corners of the bounding box
        Point3f[] corners = new Point3f[8];
        corners[0] = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
        corners[1] = new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMinZ());
        corners[2] = new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMinZ());
        corners[3] = new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMinZ());
        corners[4] = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMaxZ());
        corners[5] = new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMaxZ());
        corners[6] = new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMaxZ());
        corners[7] = new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());

        // All corners must be inside the tetrahedron
        for (Point3f corner : corners) {
            if (!pointInTetrahedron(corner, tetVertices)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if entity bounds intersect with a tetrahedron
     */
    private boolean entityBoundsIntersectsTetrahedron(EntityBounds bounds, Point3f[] tetVertices) {
        // First check AABB vs tetrahedron AABB
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (Point3f vertex : tetVertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        // Quick AABB intersection test
        return bounds.getMaxX() >= minX && bounds.getMinX() <= maxX && bounds.getMaxY() >= minY
        && bounds.getMinY() <= maxY && bounds.getMaxZ() >= minZ && bounds.getMinZ() <= maxZ;
    }

    /**
     * Estimate the size of a tetrahedron (length of longest edge)
     */
    private double estimateTetSize(Tet tet) {
        Point3i[] intVertices = tet.coordinates();
        Point3f[] vertices = convertToFloat(intVertices);
        double maxEdgeLength = 0;

        // Check all edges
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                double edgeLength = vertices[i].distance(vertices[j]);
                maxEdgeLength = Math.max(maxEdgeLength, edgeLength);
            }
        }

        return maxEdgeLength;
    }

    /**
     * Check if a point is inside a tetrahedron using barycentric coordinates
     */
    private boolean pointInTetrahedron(Point3f point, Point3f[] vertices) {
        // Use barycentric coordinates to check if point is inside tetrahedron
        // This is a simplified check - actual implementation would use
        // the TetrahedralGeometry class for precise calculations

        // For now, use a bounding box approximation
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (Point3f vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY && point.z >= minZ
        && point.z <= maxZ;
    }
}
