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

package com.hellblazer.luciferase.esvo.ray;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.traversal.EnhancedRay;
import com.hellblazer.luciferase.esvo.traversal.RayTraversalUtils;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.MultiLevelOctree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RayTraversalUtils ray casting wrapper utilities.
 */
class RayTraversalUtilsTest {

    @Test
    void testCreateRayFromCamera() {
        // Test creating ray from camera at center looking along positive X axis
        Vector3f cameraOrigin = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f cameraDirection = new Vector3f(1.0f, 0.0f, 0.0f);

        EnhancedRay ray = RayTraversalUtils.createRayFromCamera(cameraOrigin, cameraDirection);

        assertNotNull(ray, "Ray should not be null");

        // Verify origin stays in unified [0,1] octree space (no transformation)
        assertEquals(0.5f, ray.origin.x, 0.0001f, "Origin X should stay in [0,1] space");
        assertEquals(0.5f, ray.origin.y, 0.0001f, "Origin Y should stay in [0,1] space");
        assertEquals(0.5f, ray.origin.z, 0.0001f, "Origin Z should stay in [0,1] space");

        // Verify direction is normalized
        assertEquals(1.0f, ray.direction.length(), 0.0001f, "Direction should be normalized");
        assertEquals(1.0f, ray.direction.x, 0.0001f, "Direction X should be preserved");
    }

    @Test
    void testCreateRayFromCameraWithNonNormalizedDirection() {
        // Test that direction is normalized
        Vector3f cameraOrigin = new Vector3f(0.0f, 0.0f, 0.0f);
        Vector3f cameraDirection = new Vector3f(2.0f, 0.0f, 0.0f);

        EnhancedRay ray = RayTraversalUtils.createRayFromCamera(cameraOrigin, cameraDirection);

        assertNotNull(ray, "Ray should not be null");
        assertEquals(1.0f, ray.direction.length(), 0.0001f, "Direction should be normalized");
    }

    @Test
    void testCreateOctreeFromData() {
        // Create simple octree data with a root node
        ESVOOctreeData octreeData = new ESVOOctreeData(1024);
        ESVONodeUnified rootNode = new ESVONodeUnified(
            (byte) 0xFF,  // childMask - all children present
            (byte) 0x00,  // leafMask - no leaves
            false,        // isLeaf
            0,            // childPointer
            (byte) 0,     // contour
            0             // level
        );
        octreeData.setNode(0, rootNode);

        MultiLevelOctree octree = RayTraversalUtils.createOctreeFromData(octreeData, 8);

        assertNotNull(octree, "Octree should not be null");
    }

    @Test
    void testCreateOctreeFromEmptyData() {
        // Test with empty octree data
        ESVOOctreeData octreeData = new ESVOOctreeData(1024);

        MultiLevelOctree octree = RayTraversalUtils.createOctreeFromData(octreeData, 8);

        assertNotNull(octree, "Octree should not be null even with empty data");
    }

    @Test
    void testCreateOctreeFromNullDataThrowsException() {
        // Test that null octree data throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            RayTraversalUtils.createOctreeFromData(null, 8);
        }, "Null octreeData should throw IllegalArgumentException");
    }

    @Test
    void testCreatePickingRay() {
        // Test creating picking ray from normalized screen coordinates
        Vector3f cameraOrigin = new Vector3f(0.5f, 0.5f, 0.5f);
        
        // Screen coordinates normalized to [0,1]
        float screenX = 0.5f;
        float screenY = 0.5f;
        
        EnhancedRay ray = RayTraversalUtils.createPickingRay(screenX, screenY, cameraOrigin, null);

        assertNotNull(ray, "Picking ray should not be null");
        assertEquals(1.0f, ray.direction.length(), 0.0001f, "Direction should be normalized");
    }

    @Test
    void testCreateRayBetweenPoints() {
        // Test creating ray between two points
        Vector3f start = new Vector3f(0.0f, 0.0f, 0.0f);
        Vector3f end = new Vector3f(1.0f, 0.0f, 0.0f);

        EnhancedRay ray = RayTraversalUtils.createRayBetweenPoints(start, end);

        assertNotNull(ray, "Ray should not be null");
        // Origin stays in unified [0,1] space (no transformation)
        assertEquals(0.0f, ray.origin.x, 0.0001f, "Origin should be at start point (no transform in [0,1] space)");
        assertEquals(1.0f, ray.direction.x, 0.0001f, "Direction should point from start to end");
        assertEquals(1.0f, ray.direction.length(), 0.0001f, "Direction should be normalized");
    }

    @Test
    void testValidateRayWithValidRay() {
        // Test validating a good ray in unified [0,1] octree space
        Vector3f origin = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        EnhancedRay ray = new EnhancedRay(origin, 0.0f, direction, 0.0f);

        assertTrue(RayTraversalUtils.validateRay(ray), "Valid ray should pass validation");
    }

    @Test
    void testValidateRayWithInvalidOrigin() {
        // Test validating ray with origin outside unified [0,1] octree space
        Vector3f origin = new Vector3f(1.5f, 1.5f, 1.5f);  // Outside [0,1] range
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        EnhancedRay ray = new EnhancedRay(origin, 0.0f, direction, 0.0f);

        assertFalse(RayTraversalUtils.validateRay(ray), "Ray with origin outside octree space should fail validation");
    }

    @Test
    void testCreateFrustumRays() {
        // Test creating frustum corner rays
        Vector3f cameraOrigin = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f forward = new Vector3f(0.0f, 0.0f, -1.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        float fovRadians = (float) Math.toRadians(60.0);
        float aspect = 1.333f;  // 4:3 aspect ratio

        EnhancedRay[] frustumRays = RayTraversalUtils.createFrustumRays(
            cameraOrigin,
            fovRadians,
            aspect,
            forward,
            up
        );

        assertNotNull(frustumRays, "Frustum rays should not be null");
        assertEquals(4, frustumRays.length, "Should create 4 corner rays");
        
        for (int i = 0; i < 4; i++) {
            assertNotNull(frustumRays[i], "Frustum ray " + i + " should not be null");
            assertEquals(1.0f, frustumRays[i].direction.length(), 0.0001f, 
                "Frustum ray " + i + " direction should be normalized");
        }
    }

    @Test
    void testCoordinateSpaceTransformation() {
        // Verify unified [0,1] coordinate space (no transformation needed)
        Vector3f worldMin = new Vector3f(0.0f, 0.0f, 0.0f);
        Vector3f worldMax = new Vector3f(1.0f, 1.0f, 1.0f);

        EnhancedRay rayMin = RayTraversalUtils.createRayFromCamera(worldMin, new Vector3f(1.0f, 0.0f, 0.0f));
        EnhancedRay rayMax = RayTraversalUtils.createRayFromCamera(worldMax, new Vector3f(1.0f, 0.0f, 0.0f));

        // World [0,0,0] stays at octree [0,0,0] in unified space
        assertEquals(0.0f, rayMin.origin.x, 0.0001f);
        assertEquals(0.0f, rayMin.origin.y, 0.0001f);
        assertEquals(0.0f, rayMin.origin.z, 0.0001f);

        // World [1,1,1] stays at octree [1,1,1] in unified space
        assertEquals(1.0f, rayMax.origin.x, 0.0001f);
        assertEquals(1.0f, rayMax.origin.y, 0.0001f);
        assertEquals(1.0f, rayMax.origin.z, 0.0001f);
    }

    @Test
    void testCreateRayWithCustomSizeParameters() {
        // Test creating ray with custom origin and direction sizes
        Vector3f cameraOrigin = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f cameraDirection = new Vector3f(1.0f, 0.0f, 0.0f);
        float originSize = 0.1f;
        float directionSize = 0.05f;

        EnhancedRay ray = RayTraversalUtils.createRayFromCamera(
            cameraOrigin, cameraDirection, originSize, directionSize
        );

        assertNotNull(ray, "Ray should not be null");
        assertEquals(originSize, ray.originSize, 0.0001f, "Origin size should match");
        assertEquals(directionSize, ray.directionSize, 0.0001f, "Direction size should match");
    }
}
