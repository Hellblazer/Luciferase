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
package com.hellblazer.luciferase.sparse.core;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

/**
 * Unified coordinate space utilities for sparse voxel structures.
 *
 * <p><b>Coordinate Space:</b> Both ESVO (octree) and ESVT (tetrahedral) structures
 * use a normalized [0,1] coordinate space. This simplifies:
 * <ul>
 *   <li>Cross-structure operations and comparisons</li>
 *   <li>Integration with standard graphics pipelines (NDC)</li>
 *   <li>Barycentric coordinate calculations</li>
 * </ul>
 *
 * <p><b>Transformation Chain:</b>
 * <pre>
 * World Space → Object Space (via objectToWorld⁻¹)
 * Object Space → Voxel Space (via voxelToObject⁻¹)
 * Voxel Space is ALWAYS [0,1]³ regardless of actual object size
 * </pre>
 *
 * <p><b>Historical Note:</b> The original ESVO paper used [1,2] coordinate space
 * for IEEE 754 bit manipulation optimizations. This implementation uses [0,1]
 * for consistency with ESVT and standard conventions, with equivalent performance
 * on modern hardware.
 *
 * <p><b>Thread Safety:</b> All methods are stateless and thread-safe.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.core.CoordinateSpace
 * @see com.hellblazer.luciferase.esvt.util.ESVTNodeGeometry
 */
public final class SparseCoordinateSpace {

    private SparseCoordinateSpace() {
        // Utility class - no instantiation
    }

    // === Coordinate Bounds ===

    /** Minimum coordinate value in voxel space */
    public static final float VOXEL_MIN = 0.0f;

    /** Maximum coordinate value in voxel space */
    public static final float VOXEL_MAX = 1.0f;

    /** Size of the voxel space (1.0) */
    public static final float VOXEL_SIZE = VOXEL_MAX - VOXEL_MIN;

    /** Center of the voxel space (0.5) */
    public static final float VOXEL_CENTER = (VOXEL_MIN + VOXEL_MAX) * 0.5f;

    /** Mirror constant: 2 * center = 1.0 (used for octant mirroring) */
    public static final float MIRROR_CONSTANT = 2.0f * VOXEL_CENTER; // 1.0f

    // === Point Transformation ===

    /**
     * Transform a point from world space to voxel space [0,1].
     *
     * @param worldPoint          Point in world coordinates
     * @param worldToVoxelMatrix Combined transformation matrix
     * @return Point in voxel space [0,1]
     */
    public static Vector3f transformToVoxelSpace(Vector3f worldPoint, Matrix4f worldToVoxelMatrix) {
        var worldPos = new Vector4f(worldPoint.x, worldPoint.y, worldPoint.z, 1.0f);
        var voxelPos = new Vector4f();

        worldToVoxelMatrix.transform(worldPos, voxelPos);

        return new Vector3f(voxelPos.x, voxelPos.y, voxelPos.z);
    }

    /**
     * Transform a direction vector from world space to voxel space.
     * Directions are not affected by translation (w=0).
     *
     * @param worldDirection      Direction in world coordinates
     * @param worldToVoxelMatrix Combined transformation matrix
     * @return Normalized direction in voxel space
     */
    public static Vector3f transformDirectionToVoxelSpace(Vector3f worldDirection, Matrix4f worldToVoxelMatrix) {
        var worldDir = new Vector4f(worldDirection.x, worldDirection.y, worldDirection.z, 0.0f);
        var voxelDir = new Vector4f();

        worldToVoxelMatrix.transform(worldDir, voxelDir);

        var result = new Vector3f(voxelDir.x, voxelDir.y, voxelDir.z);
        result.normalize();
        return result;
    }

    // === Matrix Creation ===

    /**
     * Create the combined world-to-voxel transformation matrix.
     *
     * @param objectToWorld  Transform from object to world space
     * @param voxelToObject Transform from voxel [0,1] to object space
     * @return Combined transformation matrix
     */
    public static Matrix4f createWorldToVoxelMatrix(Matrix4f objectToWorld, Matrix4f voxelToObject) {
        var worldToObject = new Matrix4f();
        worldToObject.invert(objectToWorld);

        var objectToVoxel = new Matrix4f();
        objectToVoxel.invert(voxelToObject);

        var worldToVoxel = new Matrix4f();
        worldToVoxel.mul(objectToVoxel, worldToObject);

        return worldToVoxel;
    }

    /**
     * Create voxel-to-object transformation matrix for given object bounds.
     *
     * @param objectCenter Center of the object in object space
     * @param objectSize   Size of the object (assumed cubic)
     * @return Matrix transforming [0,1] voxel space to object space
     */
    public static Matrix4f createVoxelToObjectMatrix(Vector3f objectCenter, float objectSize) {
        var matrix = new Matrix4f();
        matrix.setIdentity();

        // Scale from [0,1] range to object size
        matrix.setScale(objectSize);

        // Translate to place voxel center at object center
        // Voxel center is at 0.5, so we need to offset by (objectCenter - 0.5 * objectSize)
        var translation = new Vector3f(objectCenter);
        translation.x -= VOXEL_CENTER * objectSize;
        translation.y -= VOXEL_CENTER * objectSize;
        translation.z -= VOXEL_CENTER * objectSize;

        matrix.setTranslation(translation);

        return matrix;
    }

    // === Validation ===

    /**
     * Validate that a point is within voxel coordinate bounds [0,1].
     *
     * @param point Point to validate
     * @return true if point is within [0,1] in all dimensions
     */
    public static boolean isInVoxelSpace(Vector3f point) {
        return point.x >= VOXEL_MIN && point.x <= VOXEL_MAX &&
               point.y >= VOXEL_MIN && point.y <= VOXEL_MAX &&
               point.z >= VOXEL_MIN && point.z <= VOXEL_MAX;
    }

    /**
     * Clamp a point to voxel coordinate bounds [0,1].
     *
     * @param point Point to clamp (modified in place)
     */
    public static void clampToVoxelSpace(Vector3f point) {
        point.x = Math.max(VOXEL_MIN, Math.min(VOXEL_MAX, point.x));
        point.y = Math.max(VOXEL_MIN, Math.min(VOXEL_MAX, point.y));
        point.z = Math.max(VOXEL_MIN, Math.min(VOXEL_MAX, point.z));
    }

    // === Ray-Box Intersection ===

    /**
     * Calculate ray-voxel space intersection t-values.
     * The voxel box is always [0,1] x [0,1] x [0,1].
     *
     * @param rayOrigin    Ray origin in voxel space
     * @param rayDirection Ray direction in voxel space (normalized)
     * @return Array of [tMin, tMax] or null if no intersection
     */
    public static float[] calculateVoxelIntersection(Vector3f rayOrigin, Vector3f rayDirection) {
        final float epsilon = 1e-6f;
        var safeDirection = new Vector3f(rayDirection);
        if (Math.abs(safeDirection.x) < epsilon) safeDirection.x = Math.signum(safeDirection.x) * epsilon;
        if (Math.abs(safeDirection.y) < epsilon) safeDirection.y = Math.signum(safeDirection.y) * epsilon;
        if (Math.abs(safeDirection.z) < epsilon) safeDirection.z = Math.signum(safeDirection.z) * epsilon;

        float txMin = (VOXEL_MIN - rayOrigin.x) / safeDirection.x;
        float txMax = (VOXEL_MAX - rayOrigin.x) / safeDirection.x;
        if (txMin > txMax) { float temp = txMin; txMin = txMax; txMax = temp; }

        float tyMin = (VOXEL_MIN - rayOrigin.y) / safeDirection.y;
        float tyMax = (VOXEL_MAX - rayOrigin.y) / safeDirection.y;
        if (tyMin > tyMax) { float temp = tyMin; tyMin = tyMax; tyMax = temp; }

        float tzMin = (VOXEL_MIN - rayOrigin.z) / safeDirection.z;
        float tzMax = (VOXEL_MAX - rayOrigin.z) / safeDirection.z;
        if (tzMin > tzMax) { float temp = tzMin; tzMin = tzMax; tzMax = temp; }

        float tMin = Math.max(Math.max(txMin, tyMin), tzMin);
        float tMax = Math.min(Math.min(txMax, tyMax), tzMax);

        if (tMax < 0 || tMin > tMax) {
            return null;
        }

        return new float[]{Math.max(tMin, 0), tMax};
    }

    // === Octant Operations (for ray traversal optimization) ===

    /**
     * Calculate octant mask for ray direction mirroring.
     * This optimizes traversal by ensuring ray directions are negative.
     *
     * @param rayDirection Ray direction vector
     * @return Octant mask (0-7)
     */
    public static int calculateOctantMask(Vector3f rayDirection) {
        int mask = 7; // Start with all bits set (111 binary)
        if (rayDirection.x > 0.0f) mask ^= 1;
        if (rayDirection.y > 0.0f) mask ^= 2;
        if (rayDirection.z > 0.0f) mask ^= 4;
        return mask;
    }

    /**
     * Apply octant mirroring to ray direction.
     * Ensures all ray direction components become negative for traversal optimization.
     *
     * @param rayDirection Original ray direction
     * @param octantMask   Octant mask from calculateOctantMask
     * @return Mirrored ray direction (all components <= 0)
     */
    public static Vector3f applyOctantMirroring(Vector3f rayDirection, int octantMask) {
        var mirrored = new Vector3f(rayDirection);
        if ((octantMask & 1) == 0) mirrored.x = -mirrored.x;
        if ((octantMask & 2) == 0) mirrored.y = -mirrored.y;
        if ((octantMask & 4) == 0) mirrored.z = -mirrored.z;
        return mirrored;
    }

    /**
     * Apply octant mirroring to ray origin.
     * The origin is transformed to match the mirrored coordinate system.
     *
     * <p>For [0,1] space, mirroring is around center 0.5: x' = 1.0 - x
     *
     * @param rayOrigin  Original ray origin
     * @param octantMask Octant mask from calculateOctantMask
     * @return Mirrored ray origin
     */
    public static Vector3f applyOctantMirroringToOrigin(Vector3f rayOrigin, int octantMask) {
        var mirrored = new Vector3f(rayOrigin);
        if ((octantMask & 1) == 0) mirrored.x = MIRROR_CONSTANT - mirrored.x; // 1.0 - x
        if ((octantMask & 2) == 0) mirrored.y = MIRROR_CONSTANT - mirrored.y;
        if ((octantMask & 4) == 0) mirrored.z = MIRROR_CONSTANT - mirrored.z;
        return mirrored;
    }

    /**
     * Undo octant mirroring at a specific scale level.
     *
     * <p>This is used to convert hit positions back to original coordinates
     * after traversal completes.
     *
     * @param position   Position in mirrored space
     * @param scaleExp2  Scale factor (2^-level)
     * @param octantMask Octant mask used during traversal
     * @return Position in original coordinate space
     */
    public static Vector3f undoOctantMirroring(Vector3f position, float scaleExp2, int octantMask) {
        var result = new Vector3f(position);
        if ((octantMask & 1) == 0) result.x = MIRROR_CONSTANT - scaleExp2 - result.x;
        if ((octantMask & 2) == 0) result.y = MIRROR_CONSTANT - scaleExp2 - result.y;
        if ((octantMask & 4) == 0) result.z = MIRROR_CONSTANT - scaleExp2 - result.z;
        return result;
    }

    // === Level/Depth Utilities ===

    /**
     * Get voxel level from a coordinate value.
     * Used for multi-resolution operations.
     *
     * @param coordinate Coordinate value in [0,1] range
     * @param maxLevel   Maximum tree depth
     * @return Level (0 = root, maxLevel = finest)
     */
    public static int getVoxelLevel(float coordinate, int maxLevel) {
        if (coordinate < VOXEL_MIN || coordinate > VOXEL_MAX) {
            throw new IllegalArgumentException("Coordinate must be in [0,1] range: " + coordinate);
        }

        float normalized = (coordinate - VOXEL_MIN) / VOXEL_SIZE; // [0,1]
        int level = (int)(normalized * (1 << maxLevel));
        return Math.min(level, (1 << maxLevel) - 1);
    }

    // === Simple Transformations (for demos/testing) ===

    /**
     * Simple world-to-voxel coordinate transformation.
     * For basic demos where world space maps directly to voxel space.
     *
     * @param worldPos Position in world coordinates (assumed [-1,1])
     * @return Position in voxel coordinate space [0,1]
     */
    public static Vector3f worldToVoxel(Vector3f worldPos) {
        var voxelPos = new Vector3f(worldPos);
        voxelPos.x = VOXEL_MIN + (voxelPos.x * 0.5f + 0.5f) * VOXEL_SIZE;
        voxelPos.y = VOXEL_MIN + (voxelPos.y * 0.5f + 0.5f) * VOXEL_SIZE;
        voxelPos.z = VOXEL_MIN + (voxelPos.z * 0.5f + 0.5f) * VOXEL_SIZE;
        return voxelPos;
    }

    /**
     * Simple world-to-voxel direction transformation.
     *
     * @param worldDir Direction in world coordinates
     * @return Normalized direction in voxel coordinate space
     */
    public static Vector3f worldToVoxelDirection(Vector3f worldDir) {
        var voxelDir = new Vector3f(worldDir);
        voxelDir.normalize();
        return voxelDir;
    }
}
