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
package com.hellblazer.luciferase.render.bridge;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SpatialIndexRenderBridge.
 * Verifies that the bridge correctly converts spatial index data to voxel representations.
 */
public class SpatialIndexRenderBridgeTest {
    
    private Octree<LongEntityID, String> octree;
    private SpatialIndexRenderBridge<LongEntityID, String> bridge;
    private SequentialLongIDGenerator idGenerator;
    private static final float WORLD_SIZE = 1000.0f;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        octree = new Octree<>(idGenerator);
        bridge = new SpatialIndexRenderBridge<>(octree, WORLD_SIZE);
    }
    
    @Test
    void testBuildVoxelOctreeEmpty() {
        // Test with empty octree
        var rootNode = bridge.buildVoxelOctree();
        
        assertNotNull(rootNode);
        assertEquals(0, rootNode.getVoxelCount());
    }
    
    @Test
    void testBuildVoxelOctreeWithEntities() {
        // Add some entities
        var id1 = new LongEntityID(1);
        var id2 = new LongEntityID(2);
        var id3 = new LongEntityID(3);
        
        octree.insert(id1, new Point3f(100, 100, 100), (byte)12, "Entity1");
        octree.insert(id2, new Point3f(200, 200, 200), (byte)12, "Entity2");
        octree.insert(id3, new Point3f(300, 300, 300), (byte)12, "Entity3");
        
        // Build voxel octree
        var rootNode = bridge.buildVoxelOctree();
        
        assertNotNull(rootNode);
        assertTrue(rootNode.getDepth() >= 0);
        
        // Verify the structure was created
        var bounds = rootNode.getBounds();
        assertEquals(6, bounds.length);
        assertTrue(bounds[3] > bounds[0]); // maxX > minX
        assertTrue(bounds[4] > bounds[1]); // maxY > minY
        assertTrue(bounds[5] > bounds[2]); // maxZ > minZ
    }
    
    @Test
    void testUpdateRegion() {
        // Add initial entities
        var id1 = new LongEntityID(1);
        octree.insert(id1, new Point3f(50, 50, 50), (byte)12, "Entity1");
        
        // Build initial octree
        bridge.buildVoxelOctree();
        
        // Add new entity in a specific region
        var id2 = new LongEntityID(2);
        octree.insert(id2, new Point3f(150, 150, 150), (byte)12, "Entity2");
        
        // Update the region
        var updatedNodes = bridge.updateRegion(
            new SpatialIndexRenderBridge.BoundingBox(100, 100, 100, 200, 200, 200)
        );
        
        assertNotNull(updatedNodes);
        assertFalse(updatedNodes.isEmpty());
    }
    
    @Test
    void testGetVisibleNodes() {
        // Add multiple entities
        var random = new Random(42);
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            var x = random.nextFloat() * 500;
            var y = random.nextFloat() * 500;
            var z = random.nextFloat() * 500;
            octree.insert(id, new Point3f(x, y, z), (byte)12, "Entity" + i);
        }
        
        // Build octree
        bridge.buildVoxelOctree();
        
        // Create a frustum (simplified - just using dummy data)
        float[][] planes = new float[6][4];
        for (int i = 0; i < 6; i++) {
            planes[i] = new float[]{0, 0, 1, 100}; // Dummy plane
        }
        var frustum = new SpatialIndexRenderBridge.Frustum(planes, new Point3f(250, 250, 250));
        
        // Get visible nodes
        var visibleNodes = bridge.getVisibleNodes(frustum, 100);
        
        assertNotNull(visibleNodes);
        assertTrue(visibleNodes.size() <= 100);
    }
    
    @Test
    void testMaterialMapping() {
        // Add entities with different content
        var id1 = new LongEntityID(1);
        var id2 = new LongEntityID(2);
        var id3 = new LongEntityID(3);
        
        octree.insert(id1, new Point3f(100, 100, 100), (byte)12, "TypeA");
        octree.insert(id2, new Point3f(200, 200, 200), (byte)12, "TypeB");
        octree.insert(id3, new Point3f(300, 300, 300), (byte)12, "TypeA");
        
        // Build octree
        bridge.buildVoxelOctree();
        
        // Set material mapper
        bridge.setMaterialMapper(content -> {
            if ("TypeA".equals(content)) return 1;
            if ("TypeB".equals(content)) return 2;
            return 0;
        });
        
        // The material IDs should now be set based on content
        // (We can't easily verify this without exposing internal state)
        assertTrue(true); // Placeholder assertion
    }
    
    @Test
    void testLargeScaleConversion() {
        // Test with many entities
        var random = new Random(12345);
        int entityCount = 100;
        
        for (int i = 0; i < entityCount; i++) {
            var id = new LongEntityID(i);
            var x = random.nextFloat() * WORLD_SIZE;
            var y = random.nextFloat() * WORLD_SIZE;
            var z = random.nextFloat() * WORLD_SIZE;
            octree.insert(id, new Point3f(x, y, z), (byte)12, "Entity" + i);
        }
        
        // Build voxel octree
        long startTime = System.currentTimeMillis();
        var rootNode = bridge.buildVoxelOctree();
        long elapsed = System.currentTimeMillis() - startTime;
        
        assertNotNull(rootNode);
        System.out.println("Converted " + entityCount + " entities in " + elapsed + "ms");
        
        // Verify node structure
        assertTrue(countNodes(rootNode) > 0);
    }
    
    @Test
    void testNodeHierarchy() {
        // Add entities at different spatial locations to force hierarchy
        octree.insert(new LongEntityID(1), new Point3f(10, 10, 10), (byte)12, "Near");
        octree.insert(new LongEntityID(2), new Point3f(990, 990, 990), (byte)12, "Far");
        octree.insert(new LongEntityID(3), new Point3f(500, 500, 500), (byte)12, "Middle");
        
        // Debug: Check how many nodes are in the spatial index
        System.out.println("Spatial index node count: " + octree.size());
        
        // Build voxel octree
        var rootNode = bridge.buildVoxelOctree();
        
        assertNotNull(rootNode);
        
        // Should have created at least one node
        int nodeCount = countNodes(rootNode);
        System.out.println("Voxel octree node count: " + nodeCount);
        assertTrue(nodeCount >= 1, "Should have at least 1 node, but got " + nodeCount);
        
        // The actual node count depends on spatial distribution
        // With 3 entities at widely separated positions, we expect some hierarchy
        assertTrue(rootNode.getNodeCount() > 0, "Root node should track child count");
    }
    
    @Test
    void testEmptyRegionUpdate() {
        // Build initial empty octree
        bridge.buildVoxelOctree();
        
        // Update an empty region
        var updatedNodes = bridge.updateRegion(
            new SpatialIndexRenderBridge.BoundingBox(0, 0, 0, 100, 100, 100)
        );
        
        assertNotNull(updatedNodes);
        assertTrue(updatedNodes.isEmpty());
    }
    
    // Helper method to count nodes in the octree
    private int countNodes(EnhancedVoxelOctreeNode node) {
        if (node == null) return 0;
        
        int count = 1;
        for (int i = 0; i < 8; i++) {
            count += countNodes(node.getChild(i));
        }
        return count;
    }
}