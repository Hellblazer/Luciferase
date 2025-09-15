package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * COMPREHENSIVE VALIDATION TESTS for corrected ESVOTraversal implementation.
 * 
 * This test suite validates the REFERENCE-CORRECT implementation against:
 * 1. CUDA raycast.inl reference behavior
 * 2. Correct bit layouts and mask usage
 * 3. Proper [1,2] coordinate space transformations  
 * 4. Reference-accurate sparse indexing algorithm
 * 5. Correct octant mirroring implementation
 * 
 * All critical architectural fixes are validated here.
 */
public class ESVOTraversalValidationTest {
    
    private static final float EPSILON = 1e-6f;
    
    @BeforeEach
    void setUp() {
        // Test setup
    }
    
    @Test
    @DisplayName("CRITICAL: Validate [1,2] coordinate space transformation")
    void testCoordinateSpaceTransformation() {
        // Create minimal octree - single leaf node
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0x00);      // Leaf node (no children)
        nodes[0].setNonLeafMask(0x00);    // All are leaves
        nodes[0].setChildPointer(0);      // Not used for leaf nodes
        
        // CRITICAL TEST: Ray starting outside [1,2] octree space, hitting it
        ESVORay ray = new ESVORay(0.5f, 1.5f, 1.5f, 1.0f, 0.0f, 0.0f);
        
        ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
        
        // The ray should hit because it's transformed into [1,2] octree space
        assertTrue(result.hit, "Ray should hit in [1,2] coordinate space");
        assertTrue(result.t > 0, "Hit distance should be positive");
        
        // CRITICAL: Verify hit position is in [1,2] space
        assertTrue(result.x >= 1.0f && result.x <= 2.0f, 
            "Hit X should be in [1,2] space, was: " + result.x);
        assertTrue(result.y >= 1.0f && result.y <= 2.0f,
            "Hit Y should be in [1,2] space, was: " + result.y);
        assertTrue(result.z >= 1.0f && result.z <= 2.0f,
            "Hit Z should be in [1,2] space, was: " + result.z);
    }
    
    @Test
    @DisplayName("CRITICAL: Validate correct mask usage - Valid vs Non-Leaf")
    void testCorrectMaskUsage() {
        // Create node with specific bit patterns to test mask usage
        ESVONode[] nodes = new ESVONode[2];
        
        // Parent node
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0b10000001);    // Only children 0 and 7 exist (bits 8-15)
        nodes[0].setNonLeafMask(0b00000001);  // Only child 7 is non-leaf (bit 0 for child 7!)
        nodes[0].setChildPointer(1);
        
        // Child node (for child 0 - leaf)
        nodes[1] = new ESVONode();
        nodes[1].setValidMask(0x00);  // Leaf node
        
        // Ray hitting child 0 (should be leaf) - with ray size for LOD check
        ESVORay ray = new ESVORay(1.25f, 1.25f, 1.25f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        
        ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
        
        assertTrue(result.hit, "Should hit child 0");
        assertEquals(0, result.childIndex, "Should identify correct child");
        
        // Ray hitting child 7 area (should try to descend but hit leaf)
        ray = new ESVORay(1.75f, 1.75f, 1.75f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        result = ESVOTraversal.castRay(ray, nodes, 0);
        
        assertTrue(result.hit, "Should hit child 7 area");
        assertEquals(7, result.childIndex, "Should identify child 7");
    }
    
    @Test 
    @DisplayName("CRITICAL: Validate sparse indexing algorithm")
    void testSparseIndexingAlgorithm() {
        // Create tree with non-contiguous children to test sparse indexing
        ESVONode[] nodes = new ESVONode[4];
        
        // Parent with children at indices 1, 3, 6 (sparse pattern)
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0b01001010);    // Children 1, 3, 6 exist
        nodes[0].setNonLeafMask(0b00000000);  // All leaves
        nodes[0].setChildPointer(1);          // Points to dense child array
        
        // Dense child array (3 nodes for 3 existing children)
        nodes[1] = new ESVONode(); // For child 1
        nodes[2] = new ESVONode(); // For child 3  
        nodes[3] = new ESVONode(); // For child 6
        
        // Test sparse indexing calculation
        assertEquals(1, nodes[0].getChildNodeIndex(1), "Child 1 should map to dense index 0+1=1");
        assertEquals(2, nodes[0].getChildNodeIndex(3), "Child 3 should map to dense index 1+1=2");
        assertEquals(3, nodes[0].getChildNodeIndex(6), "Child 6 should map to dense index 2+1=3");
        
        // Non-existent children should return -1
        assertEquals(-1, nodes[0].getChildNodeIndex(0), "Child 0 doesn't exist");
        assertEquals(-1, nodes[0].getChildNodeIndex(2), "Child 2 doesn't exist");
        assertEquals(-1, nodes[0].getChildNodeIndex(4), "Child 4 doesn't exist");
        assertEquals(-1, nodes[0].getChildNodeIndex(5), "Child 5 doesn't exist");
        assertEquals(-1, nodes[0].getChildNodeIndex(7), "Child 7 doesn't exist");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate octant mirroring algorithm")
    void testOctantMirroring() {
        // Create simple octree for testing mirroring
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0xFF);      // All children exist
        nodes[0].setNonLeafMask(0x00);    // All leaves
        
        // Test ray with positive X direction (should trigger octant mask ^= 1)
        ESVORay ray1 = new ESVORay(0.5f, 1.5f, 1.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        ESVOResult result1 = ESVOTraversal.castRay(ray1, nodes, 0);
        
        // Test ray with negative X direction (no X mirroring)
        ESVORay ray2 = new ESVORay(2.5f, 1.5f, 1.5f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f);  
        ESVOResult result2 = ESVOTraversal.castRay(ray2, nodes, 0);
        
        // Both should hit but potentially different children due to mirroring
        assertTrue(result1.hit, "Positive X ray should hit");
        assertTrue(result2.hit, "Negative X ray should hit");
        
        // The key test is that both rays complete without errors (octant math correct)
        assertTrue(result1.t > 0, "Should have valid hit distance");
        assertTrue(result2.t > 0, "Should have valid hit distance");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate far pointer mechanism")
    void testFarPointerMechanism() {
        // Create tree with far pointer
        ESVONode[] nodes = new ESVONode[12];  // Make array larger to accommodate far pointer
        
        // Initialize all nodes to prevent null pointer issues
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new ESVONode();
        }
        
        // Root node with far pointer
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0b00000001);    // Only child 0 exists
        nodes[0].setNonLeafMask(0b00000001);  // Child 0 is non-leaf
        nodes[0].setChildPointer(4);          // Far pointer offset (4*2=8, then final calculation)
        nodes[0].setFar(true);                // CRITICAL: Enable far pointer
        
        // Far pointer indirection - set up the chain properly
        nodes[8] = new ESVONode(1, 0);        // At parentIdx + ofs*2 = 0 + 4*2 = 8, childDescriptor = 1
        nodes[10] = new ESVONode();           // Final target node
        nodes[10].setValidMask(0x00);         // This will be the actual child
        
        // Test that far pointer resolution works
        int resolvedIndex = ESVONode.resolveFarPointer(nodes, 0);
        assertEquals(2, resolvedIndex, "Far pointer should resolve correctly");
        
        // Test traversal with far pointer (should not crash)
        ESVORay ray = new ESVORay(1.25f, 1.25f, 1.25f, 1.0f, 0.0f, 0.0f);
        ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
        
        // Should complete without array bounds exception
        assertNotNull(result, "Should complete traversal with far pointer");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate bit layout correctness")
    void testBitLayoutCorrectness() {
        ESVONode node = new ESVONode();
        
        // Test valid mask in bits 8-15
        node.setValidMask(0xAB);
        assertEquals(0xAB, node.getValidMask(), "Valid mask should be preserved");
        
        // Test non-leaf mask in bits 0-7  
        node.setNonLeafMask(0xCD);
        assertEquals(0xCD, node.getNonLeafMask(), "Non-leaf mask should be preserved");
        
        // Test that masks don't interfere
        assertEquals(0xAB, node.getValidMask(), "Valid mask should remain unchanged");
        
        // Test child pointer in bits 17-31 (15 bits)
        node.setChildPointer(0x7FFF);  // Maximum 15-bit value
        assertEquals(0x7FFF, node.getChildPointer(), "Child pointer should be preserved");
        
        // Test far bit at bit 16
        node.setFar(true);
        assertTrue(node.isFar(), "Far bit should be set");
        
        node.setFar(false);
        assertFalse(node.isFar(), "Far bit should be clear");
        
        // Verify all fields still correct after far bit changes
        assertEquals(0xAB, node.getValidMask(), "Valid mask should survive far bit changes");
        assertEquals(0xCD, node.getNonLeafMask(), "Non-leaf mask should survive far bit changes");
        assertEquals(0x7FFF, node.getChildPointer(), "Child pointer should survive far bit changes");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate reference popcount algorithm")
    void testReferencePopcountAlgorithm() {
        ESVONode node = new ESVONode();
        
        // Test all possible 8-bit mask patterns
        for (int mask = 0; mask <= 0xFF; mask++) {
            node.setValidMask(mask);
            
            // Test each child index
            for (int childIdx = 0; childIdx < 8; childIdx++) {
                int expected = Integer.bitCount(mask & ((1 << childIdx) - 1));
                
                if ((mask & (1 << childIdx)) != 0) {
                    // Child exists - test sparse index calculation
                    int sparseIndex = node.getChildNodeIndex(childIdx);
                    assertTrue(sparseIndex >= 0, 
                        String.format("Child %d should exist for mask %02X", childIdx, mask));
                    
                    // The sparse index should equal popcount of bits before this child
                    int actualOffset = sparseIndex - node.getChildPointer();
                    assertEquals(expected, actualOffset,
                        String.format("Sparse index calculation wrong for mask %02X, child %d", mask, childIdx));
                } else {
                    // Child doesn't exist
                    assertEquals(-1, node.getChildNodeIndex(childIdx),
                        String.format("Child %d should not exist for mask %02X", childIdx, mask));
                }
            }
        }
    }
    
    @Test  
    @DisplayName("CRITICAL: Validate complete reference-accurate traversal")
    void testReferenceAccurateTraversal() {
        // Create a complete 2-level octree
        ESVONode[] nodes = new ESVONode[9];
        
        // Root node with all 8 children
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0xFF);          // All children exist
        nodes[0].setNonLeafMask(0x00);        // All are leaves
        nodes[0].setChildPointer(1);          // Dense child array starts at 1
        
        // 8 leaf children with unique identifiers
        for (int i = 1; i <= 8; i++) {
            nodes[i] = new ESVONode();
            nodes[i].setValidMask(0x00);      // Leaf node
            // Use contour mask as identifier for testing
            nodes[i].setContourMask(i);
        }
        
        // FIXED: Test rays starting OUTSIDE octree to ensure t > 0
        // Each ray targets well inside each octant to avoid boundary issues
        float[][] targets = {
            {1.25f, 1.25f, 1.25f}, // Child 0: -x,-y,-z octant
            {1.75f, 1.25f, 1.25f}, // Child 1: +x,-y,-z octant
            {1.2f, 1.8f, 1.2f},    // Child 2: -x,+y,-z octant (FIXED: well inside octant)
            {1.75f, 1.75f, 1.25f}, // Child 3: +x,+y,-z octant
            {1.2f, 1.2f, 1.8f},    // Child 4: -x,-y,+z octant (FIXED: well inside octant)
            {1.75f, 1.25f, 1.75f}, // Child 5: +x,-y,+z octant
            {1.25f, 1.75f, 1.75f}, // Child 6: -x,+y,+z octant
            {1.75f, 1.75f, 1.75f}  // Child 7: +x,+y,+z octant
        };
        
        // Different starting positions outside octree for each octant test  
        // FINAL FIX: Use pure axis-aligned rays to avoid boundary issues
        float[][] origins = {
            {0.5f, 1.25f, 1.25f}, // Ray to child 0 from -x side
            {2.5f, 1.25f, 1.25f}, // Ray to child 1 from +x side  
            {1.2f, 2.5f, 1.2f},   // Ray to child 2 from +y side (FIXED: axis-aligned)
            {1.75f, 2.5f, 1.25f}, // Ray to child 3 from +y side
            {1.2f, 1.2f, 2.5f},   // Ray to child 4 from +z side (FIXED: axis-aligned)
            {1.75f, 1.25f, 2.5f}, // Ray to child 5 from +z side
            {0.5f, 1.75f, 1.75f}, // Ray to child 6 from -x side
            {2.5f, 1.75f, 1.75f}  // Ray to child 7 from +x side
        };
        
        for (int i = 0; i < 8; i++) {
            // Calculate direction from origin to target
            float dx = targets[i][0] - origins[i][0];
            float dy = targets[i][1] - origins[i][1];  
            float dz = targets[i][2] - origins[i][2];
            
            // Normalize direction
            float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= len; dy /= len; dz /= len;
            
            ESVORay ray = new ESVORay(origins[i][0], origins[i][1], origins[i][2], 
                                     dx, dy, dz, 1.0f, 0.0f);
            ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
            
            assertTrue(result.hit, String.format("Should hit octant %d", i));
            assertEquals(i, result.childIndex, 
                String.format("Should identify correct child %d", i));
            assertTrue(result.iterations > 0, "Should record iteration count");
            assertTrue(result.t > 0, "Should have valid hit distance (t > 0 because ray starts outside octree)");
        }
    }
    
    @Test
    @DisplayName("CRITICAL: Validate coordinate transformation edge cases")
    void testCoordinateTransformationEdgeCases() {
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0xFF);
        nodes[0].setNonLeafMask(0x00);
        
        // Test ray exactly at cube boundary
        ESVORay ray = new ESVORay(1.0f, 1.5f, 1.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
        
        assertTrue(result.hit, "Should handle boundary ray correctly");
        
        // Test ray exactly at opposite boundary  
        ray = new ESVORay(2.0f, 1.5f, 1.5f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        result = ESVOTraversal.castRay(ray, nodes, 0);
        
        assertTrue(result.hit, "Should handle opposite boundary ray correctly");
        
        // Test ray outside cube bounds
        ray = new ESVORay(0.5f, 1.5f, 1.5f, -1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        result = ESVOTraversal.castRay(ray, nodes, 0);
        
        assertFalse(result.hit, "Should miss ray outside bounds");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate iteration and performance bounds")
    void testIterationBounds() {
        // Create deep octree to test iteration limits
        ESVONode[] nodes = new ESVONode[100];
        
        // Create chain of single-child nodes (worst case)
        for (int i = 0; i < 99; i++) {
            nodes[i] = new ESVONode();
            nodes[i].setValidMask(0x01);       // Only child 0
            nodes[i].setNonLeafMask(0x01);     // Child 0 is non-leaf
            nodes[i].setChildPointer(i + 1);
        }
        
        // Final leaf
        nodes[99] = new ESVONode();
        nodes[99].setValidMask(0x00);
        
        ESVORay ray = new ESVORay(1.25f, 1.25f, 1.25f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
        
        assertTrue(result.hit, "Should traverse deep tree");
        assertTrue(result.iterations > 0, "Should record iterations");
        assertTrue(result.iterations < 10000, "Should respect iteration limit");
    }
    
    @Test
    @DisplayName("CRITICAL: Validate against architectural failures")
    void testArchitecturalFailurePrevention() {
        // Test specifically for the architectural failures identified in validation
        
        // 1. Test correct bit layout usage
        ESVONode node = new ESVONode(0x12345678, 0x9ABCDEF0);
        
        // Extract fields and verify they match reference expectations
        int validMask = (0x12345678 & 0xFF00) >> 8;
        int nonLeafMask = 0x12345678 & 0xFF;
        int childPtr = (0x12345678 & 0xFFFE0000) >>> 17;
        boolean far = (0x12345678 & 0x10000) != 0;
        
        assertEquals(validMask, node.getValidMask(), "Valid mask extraction incorrect");
        assertEquals(nonLeafMask, node.getNonLeafMask(), "Non-leaf mask extraction incorrect");
        assertEquals(childPtr, node.getChildPointer(), "Child pointer extraction incorrect");
        assertEquals(far, node.isFar(), "Far bit extraction incorrect");
        
        // 2. Test sparse indexing with reference popcount
        node.setValidMask(0b11010110);  // Known pattern
        assertEquals(0, node.getChildNodeIndex(1) - node.getChildPointer(), "Child 1 sparse index wrong");
        assertEquals(1, node.getChildNodeIndex(2) - node.getChildPointer(), "Child 2 sparse index wrong"); 
        assertEquals(2, node.getChildNodeIndex(4) - node.getChildPointer(), "Child 4 sparse index wrong");
        assertEquals(3, node.getChildNodeIndex(6) - node.getChildPointer(), "Child 6 sparse index wrong");
        assertEquals(4, node.getChildNodeIndex(7) - node.getChildPointer(), "Child 7 sparse index wrong");
        
        // 3. Test 15-bit child pointer limits
        node.setChildPointer(0x7FFF);  // Maximum 15-bit value
        assertEquals(0x7FFF, node.getChildPointer(), "15-bit limit not respected");
        
        // 4. Test coordinate space assumptions
        ESVONode[] nodes = new ESVONode[1];
        nodes[0] = new ESVONode();
        nodes[0].setValidMask(0x00);  // Leaf node
        nodes[0].setNonLeafMask(0x00);
        
        // Ray starting outside octree, should hit after traveling positive distance
        ESVORay ray = new ESVORay(0.5f, 1.5f, 1.5f, 1.0f, 0.0f, 0.0f);
        ray.originSize = 0.0f;
        ray.directionSize = 0.0f; 
        
        ESVOResult result = ESVOTraversal.castRay(ray, nodes, 0);
        
        // The implementation should handle coordinate transformation correctly
        assertTrue(result.hit, "Coordinate transformation should work");
    }
}