/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.portal.esvo.bridge;

import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ESVOBridge integration layer.
 * 
 * @author hal.hildebrand
 */
class ESVOBridgeTest {
    
    private ESVOBridge bridge;
    
    @BeforeEach
    void setUp() {
        bridge = new ESVOBridge();
    }
    
    @Test
    void testBridgeInitialization() {
        assertNotNull(bridge);
        assertFalse(bridge.hasOctree(), "New bridge should have no octree");
        assertEquals(0, bridge.getCurrentMaxDepth(), "Initial depth should be 0");
        assertNull(bridge.getCurrentOctree(), "Initial octree should be null");
        assertNotNull(bridge.getPerformanceMonitor(), "Performance monitor should be initialized");
    }
    
    @Test
    void testBuildOctreeFromSimpleVoxels() {
        var voxels = createSimpleVoxelCube(8);
        var maxDepth = 3;
        
        var octree = bridge.buildOctree(voxels, maxDepth);
        
        assertNotNull(octree, "Built octree should not be null");
        assertTrue(bridge.hasOctree(), "Bridge should have octree after build");
        assertEquals(octree, bridge.getCurrentOctree(), "Current octree should match returned octree");
        assertEquals(maxDepth, bridge.getCurrentMaxDepth(), "Max depth should be stored");
        assertTrue(octree.getNodeCount() > 0, "Octree should have nodes");
    }
    
    @Test
    void testBuildOctreeNullVoxels() {
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.buildOctree(null, 5);
        }, "Should throw exception for null voxels");
    }
    
    @Test
    void testBuildOctreeInvalidDepth() {
        var voxels = createSimpleVoxelCube(8);
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.buildOctree(voxels, 0);
        }, "Should throw exception for depth < 1");
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.buildOctree(voxels, 16);
        }, "Should throw exception for depth > 15");
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.buildOctree(voxels, -1);
        }, "Should throw exception for negative depth");
    }
    
    @Test
    void testBuildOctreeEmptyVoxels() {
        var emptyVoxels = new ArrayList<Point3i>();
        var maxDepth = 5;
        
        // Should not throw, but create minimal octree
        var octree = bridge.buildOctree(emptyVoxels, maxDepth);
        
        assertNotNull(octree, "Octree should be created even for empty voxel list");
        assertTrue(bridge.hasOctree(), "Bridge should have octree");
    }
    
    @Test
    void testBuildOctreeMultipleTimes() {
        var voxels1 = createSimpleVoxelCube(8);
        var voxels2 = createSimpleVoxelCube(16);
        
        var octree1 = bridge.buildOctree(voxels1, 3);
        var count1 = octree1.getNodeCount();
        
        var octree2 = bridge.buildOctree(voxels2, 4);
        var count2 = octree2.getNodeCount();
        
        assertNotEquals(octree1, octree2, "Second build should replace first");
        assertEquals(octree2, bridge.getCurrentOctree(), "Current should be second octree");
        assertEquals(4, bridge.getCurrentMaxDepth(), "Depth should be updated");
        assertTrue(count2 >= count1, "More voxels should generally mean more nodes");
    }
    
    @Test
    void testCastRayBeforeOctreeBuild() {
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        assertThrows(IllegalStateException.class, () -> {
            bridge.castRay(origin, direction);
        }, "Should throw exception when no octree is available");
    }
    
    @Test
    void testCastRayWithOctree() {
        // Build a simple octree
        var voxels = createSimpleVoxelCube(16);
        bridge.buildOctree(voxels, 4);
        
        // Cast a ray through the center
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        var result = bridge.castRay(origin, direction);
        
        assertNotNull(result, "Ray cast should return a result");
        // Note: Whether hit() is true depends on octree structure
    }
    
    @Test
    void testCastRayWithExplicitOctree() {
        var voxels = createSimpleVoxelCube(16);
        var octree = bridge.buildOctree(voxels, 4);
        
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var direction = new Vector3f(0.0f, 1.0f, 0.0f);
        
        var result = bridge.castRay(octree, origin, direction);
        
        assertNotNull(result, "Ray cast with explicit octree should return result");
    }
    
    @Test
    void testCastRayNullOctree() {
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.castRay(null, origin, direction);
        }, "Should throw exception for null octree");
    }
    
    @Test
    void testCastRayNullOrigin() {
        var voxels = createSimpleVoxelCube(8);
        var octree = bridge.buildOctree(voxels, 3);
        
        var direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.castRay(octree, null, direction);
        }, "Should throw exception for null origin");
    }
    
    @Test
    void testCastRayNullDirection() {
        var voxels = createSimpleVoxelCube(8);
        var octree = bridge.buildOctree(voxels, 3);
        
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.castRay(octree, origin, null);
        }, "Should throw exception for null direction");
    }
    
    @Test
    void testCastRayZeroDirection() {
        var voxels = createSimpleVoxelCube(8);
        var octree = bridge.buildOctree(voxels, 3);
        
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var zeroDirection = new Vector3f(0.0f, 0.0f, 0.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            bridge.castRay(octree, origin, zeroDirection);
        }, "Should throw exception for zero direction vector");
    }
    
    @Test
    void testClear() {
        var voxels = createSimpleVoxelCube(8);
        bridge.buildOctree(voxels, 3);
        
        assertTrue(bridge.hasOctree(), "Should have octree after build");
        
        bridge.clear();
        
        assertFalse(bridge.hasOctree(), "Should have no octree after clear");
        assertNull(bridge.getCurrentOctree(), "Current octree should be null");
        assertEquals(0, bridge.getCurrentMaxDepth(), "Depth should be reset");
    }
    
    @Test
    void testGetOctreeStats() {
        // Before building
        var statsBefore = bridge.getOctreeStats();
        assertEquals("No octree loaded", statsBefore, "Should report no octree");
        
        // After building
        var voxels = createSimpleVoxelCube(8);
        bridge.buildOctree(voxels, 3);
        
        var statsAfter = bridge.getOctreeStats();
        assertTrue(statsAfter.contains("depth=3"), "Stats should include depth");
        assertTrue(statsAfter.contains("nodes="), "Stats should include node count");
    }
    
    @Test
    void testPerformanceMonitoring() {
        var voxels = createSimpleVoxelCube(16);
        bridge.buildOctree(voxels, 4);
        
        var origin = new Vector3f(0.5f, 0.5f, 0.5f);
        var direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        // Cast multiple rays
        for (int i = 0; i < 5; i++) {
            bridge.castRay(origin, direction);
        }
        
        var monitor = bridge.getPerformanceMonitor();
        assertNotNull(monitor, "Performance monitor should be available");
        
        // Verify stats were recorded (basic check)
        assertTrue(monitor.getTotalRaysTraced() > 0, "Should have recorded ray traces");
    }
    
    @Test
    void testMultipleRayCasts() {
        var voxels = createSimpleVoxelCube(16);
        bridge.buildOctree(voxels, 4);
        
        // Test rays from different origins and directions
        var origins = List.of(
            new Vector3f(0.5f, 0.5f, 0.5f),
            new Vector3f(0.2f, 0.3f, 0.4f),
            new Vector3f(0.8f, 0.7f, 0.6f)
        );
        
        var directions = List.of(
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(0.577f, 0.577f, 0.577f) // Diagonal
        );
        
        for (var origin : origins) {
            for (var direction : directions) {
                var result = bridge.castRay(origin, direction);
                assertNotNull(result, "Each ray cast should return a result");
            }
        }
    }
    
    @Test
    void testBuildWithDifferentDepths() {
        var voxels = createSimpleVoxelCube(32);
        
        // Test various depths
        for (int depth = 1; depth <= 8; depth++) {
            var octree = bridge.buildOctree(voxels, depth);
            assertNotNull(octree, "Should build octree at depth " + depth);
            assertEquals(depth, bridge.getCurrentMaxDepth(), "Depth should match");
        }
    }
    
    // Helper method to create a simple cube of voxels
    private List<Point3i> createSimpleVoxelCube(int size) {
        var voxels = new ArrayList<Point3i>();
        var halfSize = size / 2;
        var quarterSize = size / 4;
        
        // Create a small cube in the center
        for (int x = quarterSize; x < quarterSize + halfSize; x++) {
            for (int y = quarterSize; y < quarterSize + halfSize; y++) {
                for (int z = quarterSize; z < quarterSize + halfSize; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        
        return voxels;
    }
}
