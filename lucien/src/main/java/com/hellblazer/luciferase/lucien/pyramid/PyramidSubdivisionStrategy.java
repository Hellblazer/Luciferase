/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

/**
 * Pyramid-specific subdivision strategy (RDR-010 pi1.3 Phase E, bead Luciferase-ioz).
 *
 * <p>Mirrors {@link com.hellblazer.luciferase.lucien.octree.OctreeSubdivisionStrategy} in structure:
 * entity-count threshold + {@code Pyramid.child(0..9)} descent for target-node calculation,
 * and the same balanced/forDensePointClouds/forLargeEntities factory trio.
 *
 * <p>A pyramid refines into 10 children (6 pyramids + 4 tets). Only pyramid children
 * (types 6/7) are considered for target-node purposes; tet-child keys are handled by the
 * hybrid context and are not inserted as PyramidIndex nodes.
 *
 * @param <ID>      entity-ID type
 * @param <Content> content type
 * @author hal.hildebrand
 */
public class PyramidSubdivisionStrategy<ID extends EntityID, Content>
extends SubdivisionStrategy<PyramidKey, ID, Content> {

    /** A pyramid has 10 children (6 pyramids + 4 tets per Knapp 4.2 / t8code). */
    private static final int CHILDREN_PER_PYRAMID = TetreeConnectivity.CHILDREN_PER_PYRAMID;

    // ===== Factory methods =====

    /** Create a balanced strategy for mixed workloads (mirrors Octree's default). */
    public static <ID extends EntityID, Content> PyramidSubdivisionStrategy<ID, Content> balanced() {
        var s = new PyramidSubdivisionStrategy<ID, Content>();
        s.withMinEntitiesForSplit(4).withLoadFactor(0.75).withSpanningThreshold(0.5);
        return s;
    }

    /** Create a strategy optimised for dense point clouds. */
    public static <ID extends EntityID, Content> PyramidSubdivisionStrategy<ID, Content> forDensePointClouds() {
        var s = new PyramidSubdivisionStrategy<ID, Content>();
        s.withMinEntitiesForSplit(8).withLoadFactor(0.9).withSpanningThreshold(0.1);
        return s;
    }

    /** Create a strategy optimised for large entities. */
    public static <ID extends EntityID, Content> PyramidSubdivisionStrategy<ID, Content> forLargeEntities() {
        var s = new PyramidSubdivisionStrategy<ID, Content>();
        s.withMinEntitiesForSplit(2).withLoadFactor(0.5).withSpanningThreshold(0.7);
        return s;
    }

    // ===== Core interface =====

    @Override
    public Set<PyramidKey> calculateTargetNodes(PyramidKey parentIndex, byte parentLevel,
                                                EntityBounds entityBounds,
                                                AbstractSpatialIndex<PyramidKey, ID, Content> spatialIndex) {
        var targets = new HashSet<PyramidKey>();
        if (entityBounds == null) {
            return targets;
        }

        // Reconstruct the parent pyramid from its key via the root-to-key descent
        var parentPyramid = pyramidFromKey(parentIndex);
        if (parentPyramid == null) {
            return targets; // Cannot descend from a tet-rooted key
        }

        for (int i = 0; i < CHILDREN_PER_PYRAMID; i++) {
            var child = parentPyramid.child(i);
            if (!(child instanceof Pyramid childPyramid)) {
                continue; // Skip tet children — not inserted as PyramidIndex nodes
            }
            // Conservative cube overlap test. A type-6/7 pyramid's 5 vertices span its FULL
            // surrounding cube [x, x+h]^3 in every axis (base = the complete bottom/top face, apex
            // = the opposite corner — see Pyramid.coordinates()), so the surrounding cube is exactly
            // (anchor, length). Use those directly rather than a per-axis vertex-AABB extent:
            // EntityBounds.intersectsCube treats its 4th arg as a UNIFORM edge, so passing a single
            // axis extent would be wrong the moment the AABB-is-cubic invariant ever changes.
            int cubeSize = childPyramid.length();
            if (entityBounds.intersectsCube(childPyramid.x(), childPyramid.y(), childPyramid.z(), cubeSize)) {
                // Build the child key from the parent key + this child's coord/type bits
                int row = parentPyramid.type() - Pyramid.TYPE_6;
                int cb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                int tb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                targets.add(appendBits(parentIndex, parentLevel, cb, tb));
            }
        }
        return targets;
    }

    @Override
    public SubdivisionResult determineStrategy(SubdivisionContext<PyramidKey, ID> context) {
        if (context == null) {
            return SubdivisionResult.deferSubdivision("null context");
        }
        if (context.isAtMaxDepth()) {
            return SubdivisionResult.insertInParent("At maximum depth");
        }
        if (context.isBulkOperation && !context.isCriticallyOverloaded()) {
            return SubdivisionResult.deferSubdivision("Bulk operation in progress");
        }
        if (context.isCriticallyOverloaded()) {
            return SubdivisionResult.forceSubdivision("Node critically overloaded");
        }
        if (context.currentNodeSize < minEntitiesForSplit) {
            return SubdivisionResult.insertInParent("Too few entities for efficient subdivision");
        }

        double benefit = estimateSubdivisionBenefit(context);
        if (benefit < 0.3) {
            return SubdivisionResult.insertInParent("Low subdivision benefit score: " + benefit);
        }

        // Check spanning
        var pyramid = pyramidFromKey(context.nodeIndex);
        double nodeSize = pyramid != null ? pyramid.length() : Constants.lengthAtLevel(context.nodeLevel);
        if (shouldSpanEntity(context, nodeSize)) {
            var targets = calculateTargetNodes(context.nodeIndex, context.nodeLevel, context.newEntityBounds, null);
            if (targets.size() > 1) {
                return SubdivisionResult.splitToChildren(targets, "Entity spans " + targets.size() + " children");
            }
        }

        if (benefit > 0.7) {
            return SubdivisionResult.forceSubdivision("High subdivision benefit score: " + benefit);
        }

        if (context.newEntityBounds != null) {
            var target = calculateSingleTargetChild(context);
            if (target != null) {
                return SubdivisionResult.createSingleChild(target, "Entity fits in single pyramid child");
            }
        }

        return SubdivisionResult.forceSubdivision("Standard subdivision threshold reached");
    }

    @Override
    protected double estimateEntitySizeFactor(SubdivisionContext<PyramidKey, ID> context) {
        if (context.newEntityBounds == null) {
            return 0.5;
        }
        float sizeX = context.newEntityBounds.getMaxX() - context.newEntityBounds.getMinX();
        float sizeY = context.newEntityBounds.getMaxY() - context.newEntityBounds.getMinY();
        float sizeZ = context.newEntityBounds.getMaxZ() - context.newEntityBounds.getMinZ();
        float maxDim = Math.max(Math.max(sizeX, sizeY), sizeZ);
        double nodeSize = Constants.lengthAtLevel(context.nodeLevel);
        return Math.min(maxDim / nodeSize, 1.0);
    }

    // ===== Private helpers =====

    /**
     * Find the single pyramid child whose AABB contains the entity center.
     * Returns null if entity spans multiple children.
     */
    private PyramidKey calculateSingleTargetChild(SubdivisionContext<PyramidKey, ID> context) {
        if (context.newEntityBounds == null) return null;
        float cx = (context.newEntityBounds.getMinX() + context.newEntityBounds.getMaxX()) / 2f;
        float cy = (context.newEntityBounds.getMinY() + context.newEntityBounds.getMaxY()) / 2f;
        float cz = (context.newEntityBounds.getMinZ() + context.newEntityBounds.getMaxZ()) / 2f;
        var center = new Point3f(cx, cy, cz);

        var parentPyramid = pyramidFromKey(context.nodeIndex);
        if (parentPyramid == null) return null;

        int row = parentPyramid.type() - Pyramid.TYPE_6;
        for (int i = 0; i < CHILDREN_PER_PYRAMID; i++) {
            var child = parentPyramid.child(i);
            if (!(child instanceof Pyramid cp)) continue;
            // Use vertex AABB for single-child check
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var v : cp.coordinates()) {
                minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
                maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
            }
            if (center.x >= minX && center.x <= maxX
                && center.y >= minY && center.y <= maxY
                && center.z >= minZ && center.z <= maxZ) {
                // Check entire bounds fit inside
                if (context.newEntityBounds.getMinX() >= minX && context.newEntityBounds.getMinY() >= minY
                    && context.newEntityBounds.getMinZ() >= minZ
                    && context.newEntityBounds.getMaxX() <= maxX && context.newEntityBounds.getMaxY() <= maxY
                    && context.newEntityBounds.getMaxZ() <= maxZ) {
                    int cb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                    int tb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                    return appendBits(context.nodeIndex, context.nodeLevel, cb, tb);
                }
            }
        }
        return null;
    }

    /**
     * Reconstruct the Pyramid element for a key by descending from root. Package-private mirror of
     * PyramidIndex.pyramidFromKey — needed here since SubdivisionStrategy has no back-reference
     * to the index.
     */
    static Pyramid pyramidFromKey(PyramidKey key) {
        byte level = key.getLevel();
        if (level == 0) {
            return new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        }
        var type6Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var type7Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);
        int cb1 = key.getCoordBitsAtLevel(1);
        int tb1 = key.getTypeAtLevel(1);
        Pyramid current = null;
        outer:
        for (var root : new Pyramid[]{ type6Root, type7Root }) {
            int row = root.type() - Pyramid.TYPE_6;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == cb1
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == tb1) {
                    var child = root.child(i);
                    if (child instanceof Pyramid pc) current = pc;
                    break outer;
                }
            }
        }
        if (current == null || level == 1) {
            return (level == 1 && current != null) ? current : null;
        }
        for (int l = 2; l <= level; l++) {
            int cb = key.getCoordBitsAtLevel(l);
            int tb = key.getTypeAtLevel(l);
            int row = current.type() - Pyramid.TYPE_6;
            Pyramid next = null;
            for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i] == cb
                    && TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i] == tb) {
                    var child = current.child(i);
                    if (child instanceof Pyramid pc) next = pc;
                    break;
                }
            }
            if (next == null) return current;
            current = next;
        }
        return current;
    }

    /**
     * Append one level's worth of coord/type bits to an existing key to produce a child key.
     */
    private static PyramidKey appendBits(PyramidKey parent, byte parentLevel, int coordBits, int typeBits) {
        int childLevel = parentLevel + 1;
        // Re-build coord and type bit arrays for fromLevels
        int[] cbArr = new int[childLevel + 1];
        int[] tbArr = new int[childLevel + 1];
        for (int l = 1; l <= parentLevel; l++) {
            cbArr[l] = parent.getCoordBitsAtLevel(l);
            tbArr[l] = parent.getTypeAtLevel(l);
        }
        cbArr[childLevel] = coordBits;
        tbArr[childLevel] = typeBits;
        return PyramidKey.fromLevels((byte) childLevel, cbArr, tbArr);
    }
}
