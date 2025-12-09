package com.hellblazer.luciferase.esvo.test;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the buildFromVoxels() method in OctreeBuilder
 */
public class OctreeBuilderVoxelTest {
    
    @Test
    void testBuildFromEmptyVoxelList() {
        try (OctreeBuilder builder = new OctreeBuilder(3)) {
            ESVOOctreeData octree = builder.buildFromVoxels(new ArrayList<>(), 2);
            assertNotNull(octree);
            assertEquals(0, octree.getNodeCount());
        }
    }
    
    @Test
    void testBuildFromNullVoxelList() {
        try (OctreeBuilder builder = new OctreeBuilder(3)) {
            ESVOOctreeData octree = builder.buildFromVoxels(null, 2);
            assertNotNull(octree);
            assertEquals(0, octree.getNodeCount());
        }
    }
    
    @Test
    void testBuildFromSingleVoxel() {
        List<Point3i> voxels = new ArrayList<>();
        voxels.add(new Point3i(0, 0, 0));
        
        try (OctreeBuilder builder = new OctreeBuilder(3)) {
            ESVOOctreeData octree = builder.buildFromVoxels(voxels, 2);
            assertNotNull(octree);
            assertTrue(octree.getNodeCount() > 0, "Should have at least one node");
            
            // Should have root node + intermediate nodes + leaf node
            assertTrue(octree.getNodeCount() >= 3, "Should have root, intermediate, and leaf nodes");
        }
    }
    
    @Test
    void testBuildFromMultipleVoxels() {
        List<Point3i> voxels = new ArrayList<>();
        voxels.add(new Point3i(0, 0, 0));
        voxels.add(new Point3i(1, 0, 0));
        voxels.add(new Point3i(0, 1, 0));
        voxels.add(new Point3i(1, 1, 0));
        
        try (OctreeBuilder builder = new OctreeBuilder(5)) {
            ESVOOctreeData octree = builder.buildFromVoxels(voxels, 2);
            assertNotNull(octree);
            assertTrue(octree.getNodeCount() > 0, "Should have nodes");
            
            // Verify capacity was set correctly
            int expectedCapacity = octree.getNodeCount() * 8;
            assertEquals(expectedCapacity, octree.getCapacity(), "Capacity should match node count * 8 bytes");
        }
    }
    
    @Test
    void testBuildFromVoxelsCreatesValidNodes() {
        List<Point3i> voxels = new ArrayList<>();
        voxels.add(new Point3i(0, 0, 0));
        voxels.add(new Point3i(1, 1, 1));
        
        try (OctreeBuilder builder = new OctreeBuilder(5)) {
            ESVOOctreeData octree = builder.buildFromVoxels(voxels, 3);
            assertNotNull(octree);
            
            // Get node indices and verify nodes exist
            int[] nodeIndices = octree.getNodeIndices();
            assertTrue(nodeIndices.length > 0, "Should have node indices");
            
            // Check that all nodes are valid
            for (int index : nodeIndices) {
                ESVONodeUnified node = octree.getNode(index);
                assertNotNull(node, "Node at index " + index + " should not be null");
                assertTrue(node.isValid(), "Node at index " + index + " should be valid");
            }
        }
    }
    
    @Test
    void testBuildFromVoxelsWithDepth() {
        List<Point3i> voxels = new ArrayList<>();
        // Create a small cube of voxels
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        
        try (OctreeBuilder builder = new OctreeBuilder(5)) {
            ESVOOctreeData octree = builder.buildFromVoxels(voxels, 3);
            assertNotNull(octree);
            assertEquals(8, voxels.size(), "Should have 8 voxels");
            assertTrue(octree.getNodeCount() > 8, "Should have more nodes than voxels due to hierarchy");
        }
    }
    
    @Test
    void testBuildFromVoxelsRootNodeExists() {
        List<Point3i> voxels = new ArrayList<>();
        voxels.add(new Point3i(0, 0, 0));
        
        try (OctreeBuilder builder = new OctreeBuilder(3)) {
            ESVOOctreeData octree = builder.buildFromVoxels(voxels, 2);
            
            // Root node should be at index 0
            assertTrue(octree.hasNode(0), "Root node should exist at index 0");
            ESVONodeUnified root = octree.getNode(0);
            assertNotNull(root);
            assertTrue(root.isValid(), "Root node should be valid");
            
            // Root should have at least one child
            assertTrue(root.getChildCount() > 0, "Root should have children");
        }
    }
    
    @Test
    void testBuildFromVoxelsChildMasksAreSet() {
        List<Point3i> voxels = new ArrayList<>();
        voxels.add(new Point3i(0, 0, 0));
        voxels.add(new Point3i(1, 1, 1));
        
        try (OctreeBuilder builder = new OctreeBuilder(5)) {
            ESVOOctreeData octree = builder.buildFromVoxels(voxels, 2);
            
            // Find nodes with children
            int[] nodeIndices = octree.getNodeIndices();
            boolean foundNodeWithChildren = false;
            
            for (int index : nodeIndices) {
                ESVONodeUnified node = octree.getNode(index);
                if (node.getChildCount() > 0) {
                    foundNodeWithChildren = true;
                    assertTrue(node.getChildMask() > 0, "Node with children should have non-zero child mask");
                    break;
                }
            }
            
            assertTrue(foundNodeWithChildren, "Should have at least one node with children");
        }
    }
}
