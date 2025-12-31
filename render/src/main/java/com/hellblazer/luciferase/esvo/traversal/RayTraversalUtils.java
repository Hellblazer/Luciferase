/*
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

package com.hellblazer.luciferase.esvo.traversal;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.MultiLevelOctree;

import javax.vecmath.Vector3f;

/**
 * Utility methods for ESVO ray casting operations.
 * Provides convenient wrappers for creating EnhancedRay and MultiLevelOctree instances.
 * 
 * @author hal.hildebrand
 */
public class RayTraversalUtils {
    
    /**
     * Create an EnhancedRay from camera position and direction in world space.
     * Uses unified [0,1] octree coordinate space.
     *
     * @param cameraOrigin Camera position in world space [0,1]
     * @param cameraDirection Camera look direction (will be normalized)
     * @return EnhancedRay ready for traversal
     */
    public static EnhancedRay createRayFromCamera(Vector3f cameraOrigin, Vector3f cameraDirection) {
        return createRayFromCamera(cameraOrigin, cameraDirection, 0.0f, 0.0f);
    }

    /**
     * Create an EnhancedRay from camera position and direction with custom size parameters.
     * Uses unified [0,1] octree coordinate space.
     *
     * @param cameraOrigin Camera position in world space [0,1]
     * @param cameraDirection Camera look direction (will be normalized)
     * @param originSize Origin size parameter for termination (typically 0.0)
     * @param directionSize Direction size parameter for LOD control (typically 0.0)
     * @return EnhancedRay ready for traversal
     */
    public static EnhancedRay createRayFromCamera(Vector3f cameraOrigin, Vector3f cameraDirection,
                                                   float originSize, float directionSize) {
        // Camera origin is already in [0,1] world space, which matches octree space
        var octreeOrigin = new Vector3f(cameraOrigin);

        // Direction doesn't need transformation, just normalization (handled by EnhancedRay)
        var direction = new Vector3f(cameraDirection);

        return new EnhancedRay(octreeOrigin, originSize, direction, directionSize);
    }
    
    /**
     * Create a MultiLevelOctree from ESVOOctreeData.
     * Converts the flat node array structure to the format expected by StackBasedRayTraversal.
     * 
     * @param octreeData ESVO octree data from builder
     * @param maxDepth Maximum depth of the octree (typically matches builder depth)
     * @return MultiLevelOctree ready for traversal
     */
    public static MultiLevelOctree createOctreeFromData(ESVOOctreeData octreeData, int maxDepth) {
        if (octreeData == null) {
            throw new IllegalArgumentException("octreeData cannot be null");
        }
        
        // Use the MultiLevelOctree constructor that accepts ESVOOctreeData
        return new MultiLevelOctree(octreeData, maxDepth);
    }
    
    /**
     * Create a ray for picking/selection from screen coordinates.
     * Useful for mouse interaction with the octree visualization.
     * 
     * @param screenX Screen X coordinate (normalized to [0,1])
     * @param screenY Screen Y coordinate (normalized to [0,1])
     * @param cameraOrigin Camera position in world space
     * @param viewMatrix View transformation matrix (optional, can be null for simple orthographic)
     * @return EnhancedRay for picking
     */
    public static EnhancedRay createPickingRay(float screenX, float screenY, 
                                               Vector3f cameraOrigin,
                                               float[] viewMatrix) {
        // Convert screen coordinates to ray direction
        // Simple perspective projection assumes camera looking down -Z axis
        
        // Map screen coordinates from [0,1] to [-1,1]
        float ndcX = screenX * 2.0f - 1.0f;
        float ndcY = 1.0f - screenY * 2.0f; // Flip Y for screen coordinates
        
        // Simple ray direction (can be enhanced with proper view/projection matrices)
        var direction = new Vector3f(ndcX, ndcY, -1.0f);
        direction.normalize();
        
        // If view matrix provided, transform direction
        if (viewMatrix != null && viewMatrix.length >= 16) {
            direction = transformDirection(direction, viewMatrix);
        }
        
        return createRayFromCamera(cameraOrigin, direction);
    }
    
    /**
     * Helper to transform a direction vector by a 4x4 matrix (rotation only).
     */
    private static Vector3f transformDirection(Vector3f dir, float[] matrix) {
        // Apply 3x3 rotation part of 4x4 matrix
        float x = dir.x * matrix[0] + dir.y * matrix[4] + dir.z * matrix[8];
        float y = dir.x * matrix[1] + dir.y * matrix[5] + dir.z * matrix[9];
        float z = dir.x * matrix[2] + dir.y * matrix[6] + dir.z * matrix[10];
        
        var result = new Vector3f(x, y, z);
        result.normalize();
        return result;
    }
    
    /**
     * Validate that a ray is properly configured for traversal.
     * Checks that origin is in valid octree space and direction is normalized.
     *
     * @param ray Ray to validate
     * @return true if ray is valid for traversal
     */
    public static boolean validateRay(EnhancedRay ray) {
        if (ray == null) {
            return false;
        }

        // Check origin is in octree space [0,1]
        if (ray.origin.x < 0.0f || ray.origin.x > 1.0f ||
            ray.origin.y < 0.0f || ray.origin.y > 1.0f ||
            ray.origin.z < 0.0f || ray.origin.z > 1.0f) {
            return false;
        }

        // Check direction is normalized (within tolerance)
        float length = ray.direction.length();
        return Math.abs(length - 1.0f) < 0.001f;
    }
    
    /**
     * Create a ray from two points (origin and target).
     * Useful for creating rays between specific world positions.
     * 
     * @param origin Ray origin in world space [0,1]
     * @param target Target point in world space [0,1]
     * @return EnhancedRay from origin towards target
     */
    public static EnhancedRay createRayBetweenPoints(Vector3f origin, Vector3f target) {
        var direction = new Vector3f();
        direction.sub(target, origin);
        direction.normalize();
        
        return createRayFromCamera(origin, direction);
    }
    
    /**
     * Create a set of rays for frustum-based queries.
     * Generates rays for the four corners of a view frustum.
     * 
     * @param cameraOrigin Camera position
     * @param fovRadians Field of view in radians
     * @param aspect Aspect ratio (width/height)
     * @param forward Forward direction vector
     * @param up Up direction vector
     * @return Array of 4 corner rays [topLeft, topRight, bottomLeft, bottomRight]
     */
    public static EnhancedRay[] createFrustumRays(Vector3f cameraOrigin, float fovRadians,
                                                   float aspect, Vector3f forward, Vector3f up) {
        // Calculate right vector
        var right = new Vector3f();
        right.cross(forward, up);
        right.normalize();
        
        // Recalculate up to ensure orthogonal
        var trueUp = new Vector3f();
        trueUp.cross(right, forward);
        trueUp.normalize();
        
        // Calculate frustum dimensions at unit distance
        float halfHeight = (float) Math.tan(fovRadians / 2.0f);
        float halfWidth = halfHeight * aspect;
        
        // Create four corner directions
        var topLeft = calculateCornerDirection(forward, trueUp, right, halfWidth, halfHeight, -1, 1);
        var topRight = calculateCornerDirection(forward, trueUp, right, halfWidth, halfHeight, 1, 1);
        var bottomLeft = calculateCornerDirection(forward, trueUp, right, halfWidth, halfHeight, -1, -1);
        var bottomRight = calculateCornerDirection(forward, trueUp, right, halfWidth, halfHeight, 1, -1);
        
        return new EnhancedRay[] {
            createRayFromCamera(cameraOrigin, topLeft),
            createRayFromCamera(cameraOrigin, topRight),
            createRayFromCamera(cameraOrigin, bottomLeft),
            createRayFromCamera(cameraOrigin, bottomRight)
        };
    }
    
    /**
     * Helper to calculate a corner direction for frustum.
     */
    private static Vector3f calculateCornerDirection(Vector3f forward, Vector3f up, Vector3f right,
                                                     float halfWidth, float halfHeight,
                                                     int xSign, int ySign) {
        var direction = new Vector3f(forward);
        
        var rightOffset = new Vector3f(right);
        rightOffset.scale(halfWidth * xSign);
        direction.add(rightOffset);
        
        var upOffset = new Vector3f(up);
        upOffset.scale(halfHeight * ySign);
        direction.add(upOffset);
        
        direction.normalize();
        return direction;
    }
}
