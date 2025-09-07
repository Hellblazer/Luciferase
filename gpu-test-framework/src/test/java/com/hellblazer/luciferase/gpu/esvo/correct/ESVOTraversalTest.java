package com.hellblazer.luciferase.gpu.esvo.correct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ESVOTraversal to validate the correct implementation
 * of the ray traversal algorithm matching the reference CUDA implementation.
 */
public class ESVOTraversalTest {
    
    private static final float EPSILON = 1e-6f;
    private ESVOTestDataGenerator generator;
    
    @BeforeEach
    void setUp() {
        generator = new ESVOTestDataGenerator();
    }
    
    @Test
    @DisplayName("Test ray initialization with mirroring")
    void testRayMirroring() {
        // Test ray with negative X direction
        float[] origin = {0.5f, 0.5f, 0.5f};
        float[] direction = {-1.0f, 0.5f, 0.25f};
        
        ESVOTraversal.Ray ray = new ESVOTraversal.Ray(origin, direction, 0.0f, 100.0f);
        
        // Check mirroring was applied
        assertTrue(ray.direction[0] >= 0, "X direction should be positive after mirroring");
        assertEquals(0b001, ray.octantMask & 0b001, "X octant mask should be set");
        
        // Test ray with all negative directions
        direction = new float[] {-1.0f, -0.5f, -0.25f};
        ray = new ESVOTraversal.Ray(origin, direction, 0.0f, 100.0f);
        
        assertTrue(ray.direction[0] >= 0, "X should be positive");
        assertTrue(ray.direction[1] >= 0, "Y should be positive");
        assertTrue(ray.direction[2] >= 0, "Z should be positive");
        assertEquals(0b111, ray.octantMask, "All octant bits should be set");
    }
    
    @Test
    @DisplayName("Test AABB intersection with epsilon handling")
    void testAABBIntersection() {
        float[] origin = {0.0f, 0.5f, 0.5f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        ESVOTraversal.Ray ray = new ESVOTraversal.Ray(origin, direction, 0.0f, 10.0f);
        
        // Test intersection with unit cube [1,2]
        float[] result = ESVOTraversal.intersectAABB(
            ray.origin, ray.invDirection,
            new float[] {1.0f, 1.0f, 1.0f},
            new float[] {2.0f, 2.0f, 2.0f},
            ray.tMin, ray.tMax
        );
        
        assertNotNull(result, "Should intersect unit cube");
        assertEquals(1.0f, result[0], EPSILON, "Entry at x=1");
        assertEquals(2.0f, result[1], EPSILON, "Exit at x=2");
        
        // Test miss
        origin = new float[] {0.0f, 3.0f, 0.5f};
        ray = new ESVOTraversal.Ray(origin, direction, 0.0f, 10.0f);
        
        result = ESVOTraversal.intersectAABB(
            ray.origin, ray.invDirection,
            new float[] {1.0f, 1.0f, 1.0f},
            new float[] {2.0f, 2.0f, 2.0f},
            ray.tMin, ray.tMax
        );
        
        assertNull(result, "Should miss cube");
    }
    
    @Test
    @DisplayName("Test traversal of single leaf node")
    void testSingleLeafTraversal() {
        // Create single leaf node with voxel data
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setVoxelData(0x12345678);
        
        // Ray through center
        float[] origin = {0.5f, 1.5f, 1.5f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit leaf voxel");
        assertEquals(0x12345678, result.voxelData, "Should return correct voxel data");
        assertEquals(0.5f, result.t, EPSILON, "Should hit at correct distance");
    }
    
    @Test
    @DisplayName("Test traversal of simple two-level tree")
    void testTwoLevelTraversal() {
        // Create parent with 4 children (corners)
        ESVONode[] nodes = new ESVONode[5];
        
        // Parent node
        nodes[0] = new ESVONode();
        nodes[0].setChildMask(0b10011001); // Children at 0,3,4,7
        nodes[0].setChildPointer(1);
        
        // Child nodes with different voxel data
        nodes[1] = new ESVONode();
        nodes[1].setVoxelData(0xAAA); // Child 0
        
        nodes[2] = new ESVONode();
        nodes[2].setVoxelData(0xBBB); // Child 3
        
        nodes[3] = new ESVONode();
        nodes[3].setVoxelData(0xCCC); // Child 4
        
        nodes[4] = new ESVONode();
        nodes[4].setVoxelData(0xDDD); // Child 7
        
        // Ray hitting child 0 (lower-left-front)
        float[] origin = {0.5f, 1.25f, 1.25f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit child 0");
        assertEquals(0xAAA, result.voxelData, "Should hit correct child");
        
        // Ray hitting child 7 (upper-right-back)
        origin = new float[] {0.5f, 1.75f, 1.75f};
        
        result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit child 7");
        assertEquals(0xDDD, result.voxelData, "Should hit correct child");
    }
    
    @Test
    @DisplayName("Test complete octree traversal")
    void testCompleteOctreeTraversal() {
        // Generate test octree with known structure
        ESVONode[] nodes = generator.generateTestOctree(3, 0.3f);
        
        // Cast multiple rays and verify hits
        int hits = 0;
        int misses = 0;
        
        // Grid of rays
        for (float y = 1.1f; y < 1.9f; y += 0.1f) {
            for (float z = 1.1f; z < 1.9f; z += 0.1f) {
                float[] origin = {0.5f, y, z};
                float[] direction = {1.0f, 0.0f, 0.0f};
                
                ESVOTraversal.HitResult result = ESVOTraversal.castRay(
                    origin, direction, 0.0f, 10.0f, nodes
                );
                
                if (result.hit) {
                    hits++;
                    assertTrue(result.t >= 0.5f && result.t <= 1.5f,
                        "Hit distance should be within cube bounds");
                    assertNotEquals(0, result.voxelData, "Should have voxel data");
                } else {
                    misses++;
                }
            }
        }
        
        assertTrue(hits > 0, "Should have some hits");
        assertTrue(misses > 0, "Should have some misses for 30% density");
    }
    
    @Test
    @DisplayName("Test early termination on first hit")
    void testEarlyTermination() {
        // Create octree with multiple voxels along ray path
        ESVONode[] nodes = new ESVONode[3];
        
        // Parent with two children
        nodes[0] = new ESVONode();
        nodes[0].setChildMask(0b00010001); // Children at 0 and 4
        nodes[0].setChildPointer(1);
        
        // Near child
        nodes[1] = new ESVONode();
        nodes[1].setVoxelData(0x111);
        
        // Far child
        nodes[2] = new ESVONode();
        nodes[2].setVoxelData(0x222);
        
        // Ray through both children
        float[] origin = {0.5f, 1.25f, 1.25f};
        float[] direction = {0.0f, 0.0f, 1.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit");
        assertEquals(0x111, result.voxelData, "Should hit near child first");
    }
    
    @Test
    @DisplayName("Test stack push optimization")
    void testStackPushOptimization() {
        // Create deep tree to test stack usage
        ESVONode[] nodes = generator.generateTestOctree(5, 0.5f);
        
        // Ray through dense area
        float[] origin = {0.5f, 1.5f, 1.5f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        // Just verify it completes without stack overflow
        assertNotNull(result, "Should complete traversal");
    }
    
    @Test
    @DisplayName("Test contiguous node optimization")
    void testContiguousNodes() {
        // Create parent with contiguous children
        ESVONode[] nodes = new ESVONode[9];
        
        // Parent
        nodes[0] = new ESVONode();
        nodes[0].setChildMask(0xFF); // All 8 children
        nodes[0].setChildPointer(1);
        
        // 8 contiguous children
        for (int i = 1; i <= 8; i++) {
            nodes[i] = new ESVONode();
            nodes[i].setVoxelData(0x1000 + i);
        }
        
        // Ray hitting child 0
        float[] origin = {0.5f, 1.25f, 1.25f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit");
        assertEquals(0x1001, result.voxelData, "Should hit first child");
    }
    
    @Test
    @DisplayName("Test epsilon handling for edge rays")
    void testEpsilonHandling() {
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setVoxelData(0xEEE);
        
        // Ray exactly on cube edge
        float[] origin = {1.0f - 1e-7f, 1.5f, 1.5f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit with epsilon adjustment");
    }
    
    @Test
    @DisplayName("Test coordinate space [1,2] bounds")
    void testCoordinateSpace() {
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setVoxelData(0xFFF);
        
        // Ray outside [1,2] cube should miss
        float[] origin = {-1.0f, 0.5f, 0.5f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 0.5f, nodes
        );
        
        assertFalse(result.hit, "Should miss - ray doesn't reach [1,2] cube");
        
        // Ray through [1,2] cube should hit
        origin = new float[] {0.5f, 1.5f, 1.5f};
        
        result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit inside [1,2] cube");
    }
    
    @Test
    @DisplayName("Test traversal order for coherent rays")
    void testTraversalOrder() {
        // Create parent with children in specific pattern
        ESVONode[] nodes = new ESVONode[5];
        
        nodes[0] = new ESVONode();
        nodes[0].setChildMask(0b11110000); // Children 4,5,6,7 (upper half)
        nodes[0].setChildPointer(1);
        
        for (int i = 1; i <= 4; i++) {
            nodes[i] = new ESVONode();
            nodes[i].setVoxelData(0x2000 + i);
        }
        
        // Ray from below hitting upper children
        float[] origin = {1.5f, 1.5f, 0.5f};
        float[] direction = {0.0f, 0.0f, 1.0f};
        
        ESVOTraversal.HitResult result = ESVOTraversal.castRay(
            origin, direction, 0.0f, 10.0f, nodes
        );
        
        assertTrue(result.hit, "Should hit upper child");
        assertEquals(0x2001, result.voxelData, "Should hit child 4 first");
    }
    
    @Test
    @DisplayName("Test invalid node handling")
    void testInvalidNodeHandling() {
        // Test with null nodes array
        float[] origin = {0.5f, 1.5f, 1.5f};
        float[] direction = {1.0f, 0.0f, 0.0f};
        
        assertThrows(NullPointerException.class, () -> {
            ESVOTraversal.castRay(origin, direction, 0.0f, 10.0f, null);
        });
        
        // Test with empty nodes array
        ESVONode[] empty = new ESVONode[0];
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> {
            ESVOTraversal.castRay(origin, direction, 0.0f, 10.0f, empty);
        });
    }
}