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
package com.hellblazer.luciferase.render.voxel.compression;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SVOCompressor compression and decompression.
 */
public class SVOCompressorTest {
    
    private SVOCompressor compressor;
    
    @BeforeEach
    void setUp() {
        compressor = new SVOCompressor();
    }
    
    @Test
    void testCompressionDecompression() {
        // Create a test octree with some structure
        var root = new EnhancedVoxelOctreeNode(0, 0, 0, 100, 100, 100, 0, 0);
        
        // Manually subdivide to create children array
        root.subdivide();
        
        // Set specific children as leaves (don't use insertVoxel which creates sub-children)
        var child1 = new EnhancedVoxelOctreeNode(0, 0, 0, 50, 50, 50, 1, 1);
        child1.setVoxelData(new com.hellblazer.luciferase.render.voxel.core.VoxelData(255, 0, 0, 255, 0)); // Red
        child1.setVoxelCount(1);
        root.setChild(0, child1);
        
        var child2 = new EnhancedVoxelOctreeNode(50, 0, 0, 100, 50, 50, 1, 2);
        child2.setVoxelData(new com.hellblazer.luciferase.render.voxel.core.VoxelData(0, 255, 0, 255, 0)); // Green
        child2.setVoxelCount(1);
        root.setChild(1, child2);
        
        var child3 = new EnhancedVoxelOctreeNode(0, 50, 0, 50, 100, 50, 1, 3);
        child3.setVoxelData(new com.hellblazer.luciferase.render.voxel.core.VoxelData(0, 0, 255, 255, 0)); // Blue
        child3.setVoxelCount(1);
        root.setChild(2, child3);
        
        // Explicitly set other children to null
        for (int i = 3; i < 8; i++) {
            root.setChild(i, null);
        }
        
        // Compress
        byte[] compressed = compressor.compress(root);
        assertNotNull(compressed, "Compressed data should not be null");
        assertTrue(compressed.length > 0, "Compressed data should have content");
        
        // Decompress
        var decompressed = compressor.decompress(compressed);
        assertNotNull(decompressed, "Decompressed node should not be null");
        
        // Verify structure
        assertNotNull(decompressed.getChild(0), "Child 0 should exist");
        assertNotNull(decompressed.getChild(1), "Child 1 should exist");
        assertNotNull(decompressed.getChild(2), "Child 2 should exist");
        assertNull(decompressed.getChild(3), "Child 3 should not exist");
        
        // Verify child properties
        assertTrue(decompressed.getChild(0).isLeaf(), "Child 0 should be a leaf");
        assertTrue(decompressed.getChild(1).isLeaf(), "Child 1 should be a leaf");
        assertTrue(decompressed.getChild(2).isLeaf(), "Child 2 should be a leaf");
        
        // Verify colors
        assertEquals(0xFFFF0000, decompressed.getChild(0).getPackedColor(), "Child 0 should be red");
        assertEquals(0xFF00FF00, decompressed.getChild(1).getPackedColor(), "Child 1 should be green");
        assertEquals(0xFF0000FF, decompressed.getChild(2).getPackedColor(), "Child 2 should be blue");
    }
    
    @Test
    void testEmptyNode() {
        // Create an empty root node
        var root = new EnhancedVoxelOctreeNode(0, 0, 0, 100, 100, 100, 0, 0);
        
        // Compress
        byte[] compressed = compressor.compress(root);
        assertNotNull(compressed);
        
        // Decompress
        var decompressed = compressor.decompress(compressed);
        assertNotNull(decompressed);
        
        // Should have no children
        for (int i = 0; i < 8; i++) {
            assertNull(decompressed.getChild(i), "Child " + i + " should be null");
        }
    }
    
    @Test
    void testUniformNode() {
        // Create a uniform leaf node
        var root = new EnhancedVoxelOctreeNode(0, 0, 0, 100, 100, 100, 0, 0);
        root.setVoxelData(new com.hellblazer.luciferase.render.voxel.core.VoxelData(255, 255, 255, 255, 0));
        root.setVoxelCount(1);
        
        // Compress
        byte[] compressed = compressor.compress(root);
        assertNotNull(compressed);
        
        // Decompress
        var decompressed = compressor.decompress(compressed);
        assertNotNull(decompressed);
        assertTrue(decompressed.isLeaf());
        assertEquals(1, decompressed.getVoxelCount());
        assertEquals(0xFFFFFFFF, decompressed.getPackedColor());
    }
    
    @Test
    void testDeepHierarchy() {
        // Create a deeper hierarchy
        var root = new EnhancedVoxelOctreeNode(0, 0, 0, 100, 100, 100, 0, 0);
        
        var level1 = new EnhancedVoxelOctreeNode(0, 0, 0, 50, 50, 50, 1, 1);
        root.setChild(0, level1);
        
        var level2 = new EnhancedVoxelOctreeNode(0, 0, 0, 25, 25, 25, 2, 2);
        level2.setVoxelData(new com.hellblazer.luciferase.render.voxel.core.VoxelData(170, 187, 204, 255, 0));
        level2.setVoxelCount(1);
        level1.setChild(0, level2);
        
        // Compress
        byte[] compressed = compressor.compress(root);
        assertNotNull(compressed);
        
        // Decompress
        var decompressed = compressor.decompress(compressed);
        assertNotNull(decompressed);
        
        // Verify hierarchy
        assertNotNull(decompressed.getChild(0), "Level 1 should exist");
        assertNotNull(decompressed.getChild(0).getChild(0), "Level 2 should exist");
        assertTrue(decompressed.getChild(0).getChild(0).isLeaf(), "Level 2 should be a leaf");
        assertEquals(0xFFAABBCC, decompressed.getChild(0).getChild(0).getPackedColor());
    }
    
    @Test
    void testCompressionWithoutZlib() {
        // Test without zlib compression
        compressor = new SVOCompressor(true, true, true, false, 256);
        
        var root = new EnhancedVoxelOctreeNode(0, 0, 0, 100, 100, 100, 0, 0);
        root.setVoxelData(new com.hellblazer.luciferase.render.voxel.core.VoxelData(18, 52, 86, 255, 0));
        root.setVoxelCount(1);
        
        // Compress
        byte[] compressed = compressor.compress(root);
        assertNotNull(compressed);
        
        // Decompress
        var decompressed = compressor.decompress(compressed);
        assertNotNull(decompressed);
        assertEquals(0xFF123456, decompressed.getPackedColor());
    }
}