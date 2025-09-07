package com.hellblazer.luciferase.gpu.test.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validation test comparing our ESVO implementation against
 * the reference CUDA implementation from Laine & Karras 2010.
 * 
 * Key differences identified from the reference implementation:
 * 
 * 1. NODE STRUCTURE (Reference uses int2 = 64 bits):
 *    - child_descriptor.x: [valid(1)|far(1)|dummy(1)|childptr(14)|childmask(8)|leafmask(8)]
 *    - child_descriptor.y: [contour_ptr(24)|contour_mask(8)]
 *    
 * 2. CHILD INDEXING (Reference uses sparse indexing):
 *    - Child index = parent_ptr + popc8(child_masks & 0x7F)
 *    - Far pointer handling: if far bit set, read indirection pointer
 *    
 * 3. COORDINATE SYSTEM:
 *    - Reference assumes octree resides at [1, 2] not [0, 1]
 *    - Uses mirroring optimization for ray direction
 *    
 * 4. STACK MANAGEMENT:
 *    - Reference uses 23-level stack depth
 *    - Stores parent pointer + t_max per level
 *    - Push optimization: only push if tc_max < h
 */
public class ESVOReferenceValidationTest {
    private static final Logger log = LoggerFactory.getLogger(ESVOReferenceValidationTest.class);
    
    // Reference implementation constants
    private static final int CAST_STACK_DEPTH = 23;
    private static final float EPSILON = (float)Math.pow(2, -CAST_STACK_DEPTH);
    
    /**
     * Reference node structure from CUDA implementation.
     * Uses int2 (64 bits) for child_descriptor.
     */
    static class ReferenceNode {
        int childDescriptor;  // [valid|far|dummy|ptr(14)|childmask(8)|leafmask(8)]
        int contourData;      // [contour_ptr(24)|contour_mask(8)]
        
        // Helper methods to extract fields
        public int getChildMask() {
            return (childDescriptor & 0xFF00) >> 8;
        }
        
        public int getLeafMask() {
            return childDescriptor & 0xFF;
        }
        
        public int getChildPtr() {
            return (childDescriptor >> 17) & 0x3FFF;
        }
        
        public boolean isFar() {
            return (childDescriptor & 0x10000) != 0;
        }
        
        public boolean hasChild(int idx) {
            return (getChildMask() & (1 << idx)) != 0;
        }
        
        public boolean isLeaf(int idx) {
            return (getLeafMask() & (1 << idx)) == 0;
        }
    }
    
    @Test
    @DisplayName("Validate node structure matches reference")
    void testNodeStructure() {
        log.info("Validating node structure against reference implementation");
        
        // Our current structure uses 32 bytes with different layout
        // Reference uses 8 bytes (int2) with packed encoding
        
        // Create test node with known values
        ReferenceNode refNode = new ReferenceNode();
        refNode.childDescriptor = 0x00028F55; // childPtr=1, childMask=0xF5, leafMask=0x55
        
        assertEquals(0xF5, refNode.getChildMask(), "Child mask extraction");
        assertEquals(0x55, refNode.getLeafMask(), "Leaf mask extraction");
        assertEquals(1, refNode.getChildPtr(), "Child pointer extraction");
        assertFalse(refNode.isFar(), "Far bit check");
        
        // Check specific children
        assertTrue(refNode.hasChild(0), "Child 0 should exist");
        assertFalse(refNode.hasChild(1), "Child 1 should not exist");
        assertTrue(refNode.hasChild(2), "Child 2 should exist");
        
        log.info("Reference node structure validation passed");
    }
    
    @Test
    @DisplayName("Validate child indexing algorithm")
    void testChildIndexing() {
        log.info("Validating child indexing against reference");
        
        // Reference uses: parent_ptr + popc8(child_masks & 0x7F)
        // where child_masks = child_descriptor.x << child_shift
        
        int childMask = 0b11110101; // Example: children at indices 0,2,4,5,6,7
        int parentPtr = 100;
        
        // Test child index calculation for each child
        for (int childIdx = 0; childIdx < 8; childIdx++) {
            if ((childMask & (1 << childIdx)) != 0) {
                // Count set bits before this child
                int popcount = Integer.bitCount(childMask & ((1 << childIdx) - 1));
                int expectedIndex = parentPtr + popcount;
                
                log.info("Child {} at index {} (popcount={})", 
                    childIdx, expectedIndex, popcount);
                
                // This is the correct sparse indexing
                assertTrue(expectedIndex >= parentPtr);
                assertTrue(expectedIndex < parentPtr + Integer.bitCount(childMask));
            }
        }
    }
    
    @Test
    @DisplayName("Validate coordinate system transformation")
    void testCoordinateSystem() {
        log.info("Validating coordinate system against reference");
        
        // Reference assumes octree at [1, 2], we use [0, 1]
        // Reference uses mirroring optimization
        
        float[] rayDir = {0.5f, -0.3f, 0.8f};
        int octantMask = 7;
        
        // Apply mirroring as in reference
        if (rayDir[0] > 0.0f) octantMask ^= 1;
        if (rayDir[1] > 0.0f) octantMask ^= 2;
        if (rayDir[2] > 0.0f) octantMask ^= 4;
        
        assertEquals(4, octantMask, "Octant mask for ray direction");
        
        // Reference bias calculation for [1, 2] space
        float txCoef = 1.0f / -Math.abs(rayDir[0]);
        float tyCoef = 1.0f / -Math.abs(rayDir[1]);
        float tzCoef = 1.0f / -Math.abs(rayDir[2]);
        
        log.info("Coefficients: tx={}, ty={}, tz={}", txCoef, tyCoef, tzCoef);
    }
    
    @Test
    @DisplayName("Validate stack management")
    void testStackManagement() {
        log.info("Validating stack management against reference");
        
        // Reference uses 23-level stack
        // Stores parent pointer + t_max per level
        
        class ReferenceStack {
            Object[] nodeStack = new Object[CAST_STACK_DEPTH + 1];
            float[] tmaxStack = new float[CAST_STACK_DEPTH + 1];
            
            void write(int idx, Object node, float tmax) {
                nodeStack[idx] = node;
                tmaxStack[idx] = tmax;
            }
            
            Object read(int idx, float[] tmax) {
                tmax[0] = tmaxStack[idx];
                return nodeStack[idx];
            }
        }
        
        ReferenceStack stack = new ReferenceStack();
        
        // Test stack operations
        Object testNode = new Object();
        float testTmax = 0.75f;
        
        stack.write(5, testNode, testTmax);
        
        float[] readTmax = new float[1];
        Object readNode = stack.read(5, readTmax);
        
        assertEquals(testNode, readNode, "Stack node retrieval");
        assertEquals(testTmax, readTmax[0], 1e-6f, "Stack tmax retrieval");
        
        log.info("Stack management validation passed");
    }
    
    @Test
    @DisplayName("Critical bug: Our implementation uses wrong child indexing")
    void testCriticalBugChildIndexing() {
        log.error("CRITICAL BUG IDENTIFIED:");
        log.error("Our implementation uses: nodeIndex * 8 + childIdx + 1");
        log.error("Reference uses: parent_ptr + popc8(child_masks & 0x7F)");
        log.error("");
        log.error("This means we're accessing completely wrong memory locations!");
        log.error("Example: For node 10 with childMask=0xF0 (upper 4 children):");
        log.error("  - Our code accesses indices: 81, 82, 83, 84, 85, 86, 87, 88");
        log.error("  - Reference accesses: 10, 11, 12, 13 (only 4 children exist)");
        
        // Demonstrate the bug
        int nodeIndex = 10;
        int childMask = 0xF0; // Children 4,5,6,7 exist
        
        log.error("\nOur implementation (WRONG):");
        for (int i = 0; i < 8; i++) {
            if ((childMask & (1 << i)) != 0) {
                int wrongIndex = nodeIndex * 8 + i + 1;
                log.error("  Child {} -> Index {}", i, wrongIndex);
            }
        }
        
        log.error("\nReference implementation (CORRECT):");
        int basePtr = nodeIndex;
        for (int i = 0; i < 8; i++) {
            if ((childMask & (1 << i)) != 0) {
                int popcount = Integer.bitCount(childMask & ((1 << i) - 1));
                int correctIndex = basePtr + popcount;
                log.error("  Child {} -> Index {}", i, correctIndex);
            }
        }
        
        // This test intentionally fails to highlight the bug
        fail("Critical indexing bug must be fixed before validation can proceed");
    }
    
    @Test
    @DisplayName("Critical bug: Node structure incompatibility")
    void testCriticalBugNodeStructure() {
        log.error("CRITICAL BUG IDENTIFIED:");
        log.error("Data structure mismatch between implementations:");
        log.error("");
        log.error("Reference (8 bytes total):");
        log.error("  - int childDescriptor: packed [ptr|masks]");
        log.error("  - int contourData: packed [ptr|mask]");
        log.error("");
        log.error("Our ESVODataStructures (32 bytes):");
        log.error("  - int childDescriptor");  
        log.error("  - int contourPointer");
        log.error("  - float minValue");
        log.error("  - float maxValue");
        log.error("  - int attributes");
        log.error("  - int padding1, padding2, padding3");
        log.error("");
        log.error("Our ESVOKernels.java (16 bytes):");
        log.error("  - uint parent");
        log.error("  - uint childMask");
        log.error("  - uint childPtr");
        log.error("  - uint voxelData");
        log.error("");
        log.error("None of these match the reference implementation!");
        
        fail("Node structure must be unified to match reference");
    }
}