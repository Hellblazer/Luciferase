package com.hellblazer.luciferase.gpu.esvo.correct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ESVOTestDataGenerator to validate correct
 * octree generation with proper sparse indexing and structure.
 */
public class ESVOTestDataGeneratorTest {
    
    private ESVOTestDataGenerator generator;
    
    @BeforeEach
    void setUp() {
        generator = new ESVOTestDataGenerator();
    }
    
    @Test
    @DisplayName("Test single level octree generation")
    void testSingleLevelGeneration() {
        ESVONode[] nodes = generator.generateTestOctree(1, 1.0f);
        
        assertEquals(1, nodes.length, "Single level should have 1 node");
        assertTrue(nodes[0].isLeaf(), "Root should be leaf at depth 1");
        assertNotEquals(0, nodes[0].getVoxelData(), "Leaf should have voxel data");
    }
    
    @Test
    @DisplayName("Test two level octree with full density")
    void testTwoLevelFullDensity() {
        ESVONode[] nodes = generator.generateTestOctree(2, 1.0f);
        
        // Should have root + 8 children
        assertEquals(9, nodes.length, "Should have 9 nodes total");
        
        // Root should have all children
        assertEquals(0xFF, nodes[0].getChildMask(), "Root should have all 8 children");
        assertEquals(1, nodes[0].getChildPointer(), "Root children start at index 1");
        assertFalse(nodes[0].isLeaf(), "Root should not be leaf");
        
        // All children should be leaves
        for (int i = 1; i <= 8; i++) {
            assertTrue(nodes[i].isLeaf(), "Child " + i + " should be leaf");
            assertNotEquals(0, nodes[i].getVoxelData(), "Child should have voxel data");
        }
    }
    
    @Test
    @DisplayName("Test sparse octree generation")
    void testSparseGeneration() {
        // Use fixed seed for reproducible testing
        ESVONode[] nodes = generator.generateTestOctreeWithSeed(3, 0.5f, 12345L);
        
        // Verify structure is valid
        assertTrue(generator.validateOctreeStructure(nodes), "Structure should be valid");
        
        // Check that not all possible nodes exist (sparse)
        int maxPossible = 1 + 8 + 64; // Root + level 1 + level 2
        assertTrue(nodes.length < maxPossible, "Should be sparse tree");
        
        // Verify root node
        assertFalse(nodes[0].isLeaf(), "Root should not be leaf at depth 3");
        assertTrue(nodes[0].getChildMask() > 0, "Root should have at least one child");
    }
    
    @Test
    @DisplayName("Test octree structure validation")
    void testStructureValidation() {
        // Generate valid octree
        ESVONode[] valid = generator.generateTestOctree(3, 0.7f);
        assertTrue(generator.validateOctreeStructure(valid), "Generated tree should be valid");
        
        // Create invalid octree - child pointer out of bounds
        ESVONode[] invalid = new ESVONode[2];
        invalid[0] = new ESVONode();
        invalid[0].setChildMask(0x01);
        invalid[0].setChildPointer(5); // Out of bounds
        invalid[1] = new ESVONode();
        
        assertFalse(generator.validateOctreeStructure(invalid), "Invalid pointer should fail");
        
        // Create invalid octree - wrong child count
        invalid = new ESVONode[3];
        invalid[0] = new ESVONode();
        invalid[0].setChildMask(0xFF); // Says 8 children
        invalid[0].setChildPointer(1);
        invalid[1] = new ESVONode(); // Only 2 children exist
        invalid[2] = new ESVONode();
        
        assertFalse(generator.validateOctreeStructure(invalid), "Wrong child count should fail");
    }
    
    @Test
    @DisplayName("Test sparse child indexing correctness")
    void testSparseChildIndexing() {
        ESVONode[] nodes = generator.generateTestOctree(2, 0.5f);
        
        // For each non-leaf node
        for (int i = 0; i < nodes.length; i++) {
            ESVONode node = nodes[i];
            if (!node.isLeaf()) {
                int childMask = node.getChildMask();
                int childPtr = node.getChildPointer();
                int childCount = Integer.bitCount(childMask);
                
                // Verify children are contiguous
                assertTrue(childPtr + childCount <= nodes.length,
                    "Children should fit in array");
                
                // Verify each child index is correct
                int expectedChildIdx = childPtr;
                for (int bit = 0; bit < 8; bit++) {
                    if ((childMask & (1 << bit)) != 0) {
                        assertEquals(expectedChildIdx, node.getChildNodeIndex(bit),
                            "Child " + bit + " should be at correct index");
                        expectedChildIdx++;
                    }
                }
            }
        }
    }
    
    @Test
    @DisplayName("Test depth limit enforcement")
    void testDepthLimit() {
        // Generate trees of different depths
        for (int depth = 1; depth <= 5; depth++) {
            ESVONode[] nodes = generator.generateTestOctree(depth, 1.0f);
            
            // Count maximum actual depth by traversing
            int maxDepth = computeMaxDepth(nodes, 0, 0);
            
            assertEquals(depth, maxDepth, "Max depth should match requested");
        }
    }
    
    @Test
    @DisplayName("Test deterministic generation with seed")
    void testDeterministicGeneration() {
        long seed = 98765L;
        
        // Generate twice with same seed
        ESVONode[] nodes1 = generator.generateTestOctreeWithSeed(3, 0.6f, seed);
        ESVONode[] nodes2 = generator.generateTestOctreeWithSeed(3, 0.6f, seed);
        
        // Should be identical
        assertEquals(nodes1.length, nodes2.length, "Same seed should produce same size");
        
        for (int i = 0; i < nodes1.length; i++) {
            assertEquals(nodes1[i].getChildMask(), nodes2[i].getChildMask(),
                "Node " + i + " child mask should match");
            assertEquals(nodes1[i].getChildPointer(), nodes2[i].getChildPointer(),
                "Node " + i + " child pointer should match");
            assertEquals(nodes1[i].getVoxelData(), nodes2[i].getVoxelData(),
                "Node " + i + " voxel data should match");
        }
        
        // Different seed should produce different tree
        ESVONode[] nodes3 = generator.generateTestOctreeWithSeed(3, 0.6f, seed + 1);
        
        // Very unlikely to be identical (but possible with small trees)
        boolean different = nodes1.length != nodes3.length;
        if (!different) {
            for (int i = 0; i < nodes1.length; i++) {
                if (nodes1[i].getChildMask() != nodes3[i].getChildMask() ||
                    nodes1[i].getVoxelData() != nodes3[i].getVoxelData()) {
                    different = true;
                    break;
                }
            }
        }
        
        assertTrue(different, "Different seeds should produce different trees");
    }
    
    @Test
    @DisplayName("Test density parameter effect")
    void testDensityParameter() {
        int depth = 4;
        long seed = 54321L;
        
        // Generate with different densities
        ESVONode[] sparse = generator.generateTestOctreeWithSeed(depth, 0.2f, seed);
        ESVONode[] medium = generator.generateTestOctreeWithSeed(depth, 0.5f, seed);
        ESVONode[] dense = generator.generateTestOctreeWithSeed(depth, 0.8f, seed);
        ESVONode[] full = generator.generateTestOctreeWithSeed(depth, 1.0f, seed);
        
        // Node count should increase with density
        assertTrue(sparse.length < medium.length, "Sparse should have fewer nodes");
        assertTrue(medium.length < dense.length, "Medium should have fewer than dense");
        assertTrue(dense.length < full.length, "Dense should have fewer than full");
        
        // Count leaf nodes with voxel data
        int sparseVoxels = countVoxels(sparse);
        int mediumVoxels = countVoxels(medium);
        int denseVoxels = countVoxels(dense);
        int fullVoxels = countVoxels(full);
        
        assertTrue(sparseVoxels < mediumVoxels, "Sparse should have fewer voxels");
        assertTrue(mediumVoxels < denseVoxels, "Medium should have fewer voxels than dense");
        assertTrue(denseVoxels <= fullVoxels, "Dense should have fewer or equal voxels to full");
    }
    
    @Test
    @DisplayName("Test boundary conditions")
    void testBoundaryConditions() {
        // Zero density should create minimal tree
        ESVONode[] empty = generator.generateTestOctree(3, 0.0f);
        assertEquals(1, empty.length, "Zero density should create single root");
        assertTrue(empty[0].isLeaf(), "Root should be leaf with zero density");
        
        // Depth 0 should create single node
        ESVONode[] depth0 = generator.generateTestOctree(0, 1.0f);
        assertEquals(1, depth0.length, "Depth 0 should create single node");
        
        // Very deep tree should still work
        ESVONode[] deep = generator.generateTestOctree(8, 0.1f);
        assertTrue(generator.validateOctreeStructure(deep), "Deep tree should be valid");
    }
    
    @Test
    @DisplayName("Test unique voxel data generation")
    void testUniqueVoxelData() {
        ESVONode[] nodes = generator.generateTestOctree(3, 0.8f);
        
        // Collect all voxel data values
        java.util.Set<Integer> voxelValues = new java.util.HashSet<>();
        for (ESVONode node : nodes) {
            if (node.isLeaf() && node.getVoxelData() != 0) {
                boolean added = voxelValues.add(node.getVoxelData());
                assertTrue(added, "Voxel data should be unique");
            }
        }
        
        // Should have multiple different values
        assertTrue(voxelValues.size() > 1, "Should have multiple voxel values");
    }
    
    // Helper method to compute max depth
    private int computeMaxDepth(ESVONode[] nodes, int nodeIdx, int currentDepth) {
        if (nodeIdx >= nodes.length) return currentDepth;
        
        ESVONode node = nodes[nodeIdx];
        if (node.isLeaf()) {
            return currentDepth + 1;
        }
        
        int maxChildDepth = currentDepth;
        int childMask = node.getChildMask();
        
        for (int i = 0; i < 8; i++) {
            if ((childMask & (1 << i)) != 0) {
                int childIdx = node.getChildNodeIndex(i);
                if (childIdx >= 0) {
                    int childDepth = computeMaxDepth(nodes, childIdx, currentDepth + 1);
                    maxChildDepth = Math.max(maxChildDepth, childDepth);
                }
            }
        }
        
        return maxChildDepth;
    }
    
    // Helper method to count voxels
    private int countVoxels(ESVONode[] nodes) {
        int count = 0;
        for (ESVONode node : nodes) {
            if (node.isLeaf() && node.getVoxelData() != 0) {
                count++;
            }
        }
        return count;
    }
}