package com.hellblazer.luciferase.esvo.core;

import javax.vecmath.Vector3f;
import javax.vecmath.Matrix4f;

/**
 * Coordinate Space Utilities for ESVO
 *
 * The octree ALWAYS resides at coordinates [0, 1] regardless of actual object size.
 * This is fundamental to the ESVO algorithm for ray intersection.
 *
 * Transformation Chain:
 * World Space → Object Space (via objectToWorld matrix)
 * Object Space → Octree Space (via octreeToObject matrix)
 * Octree Space is ALWAYS [0, 1] regardless of actual size
 *
 * Historical Note: The original ESVO paper used [1, 2] for IEEE 754 bit manipulation
 * optimizations. This implementation uses [0, 1] for consistency with ESVT and
 * standard conventions, with equivalent performance on modern hardware.
 *
 * @see com.hellblazer.luciferase.sparse.core.SparseCoordinateSpace
 */
public final class CoordinateSpace {

    // Octree coordinate bounds - unified with ESVT [0, 1] space
    public static final float OCTREE_MIN = 0.0f;
    public static final float OCTREE_MAX = 1.0f;
    public static final float OCTREE_SIZE = OCTREE_MAX - OCTREE_MIN; // 1.0f
    public static final float OCTREE_CENTER = (OCTREE_MIN + OCTREE_MAX) * 0.5f; // 0.5f

    /** Mirror constant: 2 * center = 1.0 (used for octant mirroring) */
    private static final float MIRROR_CONSTANT = 2.0f * OCTREE_CENTER; // 1.0f

    private CoordinateSpace() {
        // Utility class - no instantiation
    }

    /**
     * Transform a point from world space to octree space [0,1]
     *
     * @param worldPoint Point in world coordinates
     * @param worldToOctreeMatrix Combined transformation matrix
     * @return Point in octree space [0,1]
     */
    public static Vector3f transformToOctreeSpace(Vector3f worldPoint, Matrix4f worldToOctreeMatrix) {
        javax.vecmath.Vector4f worldPos = new javax.vecmath.Vector4f(worldPoint.x, worldPoint.y, worldPoint.z, 1.0f);
        javax.vecmath.Vector4f octreePos = new javax.vecmath.Vector4f();

        worldToOctreeMatrix.transform(worldPos, octreePos);

        return new Vector3f(octreePos.x, octreePos.y, octreePos.z);
    }

    /**
     * Transform a direction vector from world space to octree space
     * Note: Directions are not affected by translation, so w=0
     *
     * @param worldDirection Direction in world coordinates
     * @param worldToOctreeMatrix Combined transformation matrix
     * @return Direction in octree space
     */
    public static Vector3f transformDirectionToOctreeSpace(Vector3f worldDirection, Matrix4f worldToOctreeMatrix) {
        javax.vecmath.Vector4f worldDir = new javax.vecmath.Vector4f(worldDirection.x, worldDirection.y, worldDirection.z, 0.0f);
        javax.vecmath.Vector4f octreeDir = new javax.vecmath.Vector4f();

        worldToOctreeMatrix.transform(worldDir, octreeDir);

        Vector3f result = new Vector3f(octreeDir.x, octreeDir.y, octreeDir.z);
        result.normalize();
        return result;
    }

    /**
     * Create the combined world-to-octree transformation matrix
     *
     * @param objectToWorld Transform from object to world space
     * @param octreeToObject Transform from octree [0,1] to object space
     * @return Combined transformation matrix
     */
    public static Matrix4f createWorldToOctreeMatrix(Matrix4f objectToWorld, Matrix4f octreeToObject) {
        Matrix4f worldToObject = new Matrix4f();
        worldToObject.invert(objectToWorld);

        Matrix4f objectToOctree = new Matrix4f();
        objectToOctree.invert(octreeToObject);

        Matrix4f worldToOctree = new Matrix4f();
        worldToOctree.mul(objectToOctree, worldToObject);

        return worldToOctree;
    }

    /**
     * Create octree-to-object transformation matrix for a given object bounds
     *
     * @param objectCenter Center of the object in object space
     * @param objectSize Size of the object (assumed cubic)
     * @return Matrix transforming [0,1] octree space to object space
     */
    public static Matrix4f createOctreeToObjectMatrix(Vector3f objectCenter, float objectSize) {
        Matrix4f matrix = new Matrix4f();
        matrix.setIdentity();

        // Scale from [0,1] range to object size
        matrix.setScale(objectSize);

        // Translate to place octree center at object center
        // Octree center is at 0.5, so we need to offset by (objectCenter - 0.5 * objectSize)
        Vector3f translation = new Vector3f(objectCenter);
        translation.x -= OCTREE_CENTER * objectSize;
        translation.y -= OCTREE_CENTER * objectSize;
        translation.z -= OCTREE_CENTER * objectSize;

        matrix.setTranslation(translation);

        return matrix;
    }

    /**
     * Validate that a point is within octree coordinate bounds [0,1]
     *
     * @param point Point to validate
     * @return true if point is within [0,1] in all dimensions
     */
    public static boolean isInOctreeSpace(Vector3f point) {
        return point.x >= OCTREE_MIN && point.x <= OCTREE_MAX &&
               point.y >= OCTREE_MIN && point.y <= OCTREE_MAX &&
               point.z >= OCTREE_MIN && point.z <= OCTREE_MAX;
    }

    /**
     * Clamp a point to octree coordinate bounds [0,1]
     *
     * @param point Point to clamp (modified in place)
     */
    public static void clampToOctreeSpace(Vector3f point) {
        point.x = Math.max(OCTREE_MIN, Math.min(OCTREE_MAX, point.x));
        point.y = Math.max(OCTREE_MIN, Math.min(OCTREE_MAX, point.y));
        point.z = Math.max(OCTREE_MIN, Math.min(OCTREE_MAX, point.z));
    }

    /**
     * Calculate ray-octree intersection t-values
     * The octree box is always [0,1] x [0,1] x [0,1]
     *
     * @param rayOrigin Ray origin in octree space
     * @param rayDirection Ray direction in octree space (normalized)
     * @return Array of [tMin, tMax] or null if no intersection
     */
    public static float[] calculateOctreeIntersection(Vector3f rayOrigin, Vector3f rayDirection) {
        // Handle zero direction components to avoid division by zero
        final float epsilon = 1e-6f;
        Vector3f safeDirection = new Vector3f(rayDirection);
        if (Math.abs(safeDirection.x) < epsilon) safeDirection.x = Math.signum(safeDirection.x) * epsilon;
        if (Math.abs(safeDirection.y) < epsilon) safeDirection.y = Math.signum(safeDirection.y) * epsilon;
        if (Math.abs(safeDirection.z) < epsilon) safeDirection.z = Math.signum(safeDirection.z) * epsilon;

        // Calculate intersection with octree bounds [0,1]
        float txMin = (OCTREE_MIN - rayOrigin.x) / safeDirection.x;
        float txMax = (OCTREE_MAX - rayOrigin.x) / safeDirection.x;
        if (txMin > txMax) { float temp = txMin; txMin = txMax; txMax = temp; }

        float tyMin = (OCTREE_MIN - rayOrigin.y) / safeDirection.y;
        float tyMax = (OCTREE_MAX - rayOrigin.y) / safeDirection.y;
        if (tyMin > tyMax) { float temp = tyMin; tyMin = tyMax; tyMax = temp; }

        float tzMin = (OCTREE_MIN - rayOrigin.z) / safeDirection.z;
        float tzMax = (OCTREE_MAX - rayOrigin.z) / safeDirection.z;
        if (tzMin > tzMax) { float temp = tzMin; tzMin = tzMax; tzMax = temp; }

        float tMin = Math.max(Math.max(txMin, tyMin), tzMin);
        float tMax = Math.min(Math.min(txMax, tyMax), tzMax);

        // Check for valid intersection
        if (tMax < 0 || tMin > tMax) {
            return null; // No intersection
        }

        return new float[]{Math.max(tMin, 0), tMax};
    }

    /**
     * Calculate octant mask for ray direction mirroring
     * This optimizes traversal by ensuring ray directions are negative
     *
     * @param rayDirection Ray direction vector
     * @return Octant mask (0-7)
     */
    public static int calculateOctantMask(Vector3f rayDirection) {
        int mask = 7; // Start with all bits set (111 binary)
        if (rayDirection.x > 0.0f) mask ^= 1; // Flip X bit
        if (rayDirection.y > 0.0f) mask ^= 2; // Flip Y bit
        if (rayDirection.z > 0.0f) mask ^= 4; // Flip Z bit
        return mask;
    }

    /**
     * Apply octant mirroring to ray direction
     * This ensures all ray directions become negative for traversal optimization
     *
     * @param rayDirection Original ray direction
     * @param octantMask Octant mask from calculateOctantMask
     * @return Mirrored ray direction (all components <= 0)
     */
    public static Vector3f applyOctantMirroring(Vector3f rayDirection, int octantMask) {
        Vector3f mirrored = new Vector3f(rayDirection);
        if ((octantMask & 1) == 0) mirrored.x = -mirrored.x;
        if ((octantMask & 2) == 0) mirrored.y = -mirrored.y;
        if ((octantMask & 4) == 0) mirrored.z = -mirrored.z;
        return mirrored;
    }

    /**
     * Apply octant mirroring to ray origin
     * The origin is transformed to match the mirrored coordinate system
     *
     * For [0,1] space, mirroring is around center 0.5: x' = 1.0 - x
     *
     * @param rayOrigin Original ray origin
     * @param octantMask Octant mask from calculateOctantMask
     * @return Mirrored ray origin
     */
    public static Vector3f applyOctantMirroringToOrigin(Vector3f rayOrigin, int octantMask) {
        Vector3f mirrored = new Vector3f(rayOrigin);
        if ((octantMask & 1) == 0) mirrored.x = MIRROR_CONSTANT - mirrored.x; // 1.0 - x
        if ((octantMask & 2) == 0) mirrored.y = MIRROR_CONSTANT - mirrored.y;
        if ((octantMask & 4) == 0) mirrored.z = MIRROR_CONSTANT - mirrored.z;
        return mirrored;
    }

    /**
     * Get octree level from a coordinate value
     * Used for multi-resolution operations
     *
     * @param coordinate Coordinate value in [0,1] range
     * @param maxLevel Maximum octree depth
     * @return Level (0 = root, maxLevel = finest)
     */
    public static int getOctreeLevel(float coordinate, int maxLevel) {
        if (coordinate < OCTREE_MIN || coordinate > OCTREE_MAX) {
            throw new IllegalArgumentException("Coordinate must be in [0,1] range");
        }

        float normalized = (coordinate - OCTREE_MIN) / OCTREE_SIZE; // [0,1]
        int level = (int)(normalized * (1 << maxLevel));
        return Math.min(level, (1 << maxLevel) - 1);
    }

    // Note: Ray mirroring functionality has been moved to StackBasedRayTraversal
    // to avoid circular dependency between CoordinateSpace and StackBasedRayTraversal.
    // The ray mirroring method should be implemented directly in the StackBasedRayTraversal
    // class where it can use the local Ray class definition.

    /**
     * Simple world-to-octree coordinate transformation
     * For basic demos where we assume world space maps directly to octree space
     *
     * @param worldPos Position in world coordinates (assumed [-1,1])
     * @return Position in octree coordinate space [0,1]
     */
    public static Vector3f worldToOctree(Vector3f worldPos) {
        // Simple mapping for demo purposes
        // In real implementation, this would use proper transformation matrices
        Vector3f octreePos = new Vector3f(worldPos);
        octreePos.x = OCTREE_MIN + (octreePos.x * 0.5f + 0.5f) * OCTREE_SIZE;
        octreePos.y = OCTREE_MIN + (octreePos.y * 0.5f + 0.5f) * OCTREE_SIZE;
        octreePos.z = OCTREE_MIN + (octreePos.z * 0.5f + 0.5f) * OCTREE_SIZE;
        return octreePos;
    }

    /**
     * Simple world-to-octree direction transformation
     * For basic demos where we assume world space maps directly to octree space
     *
     * @param worldDir Direction in world coordinates
     * @return Direction in octree coordinate space (normalized)
     */
    public static Vector3f worldToOctreeDirection(Vector3f worldDir) {
        // Simple pass-through for demo purposes
        // In real implementation, this would apply rotation from transformation matrices
        Vector3f octreeDir = new Vector3f(worldDir);
        octreeDir.normalize();
        return octreeDir;
    }
}
