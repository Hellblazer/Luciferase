package com.hellblazer.luciferase.gpu.esvo.correct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ESVONode to validate the correct implementation
 * of the sparse octree node structure matching the reference CUDA implementation.
 */
public class ESVONodeTest {
    
    @Test
    @DisplayName("Test node creation with default values")
    void testNodeCreation() {
        ESVONode node = new ESVONode();
        assertEquals(0, node.getChildMask(), "Default child mask should be 0");
        assertEquals(0, node.getChildPointer(), "Default child pointer should be 0");
        assertEquals(0, node.getLeafMask(), "Default leaf mask should be 0");
        assertFalse(node.isValid(), "Default node should not be valid");
    }
    
    @Test
    @DisplayName("Test child mask setting and retrieval")
    void testChildMask() {
        ESVONode node = new ESVONode();
        
        // Test setting individual children
        node.setChildMask(0b10101010);
        assertEquals(0b10101010, node.getChildMask());
        
        // Test hasChild method
        assertTrue(node.hasChild(1));
        assertFalse(node.hasChild(0));
        assertTrue(node.hasChild(3));
        assertFalse(node.hasChild(2));
        assertTrue(node.hasChild(5));
        assertFalse(node.hasChild(4));
        assertTrue(node.hasChild(7));
        assertFalse(node.hasChild(6));
    }
    
    @Test
    @DisplayName("Test child pointer with 17-bit limit")
    void testChildPointer() {
        ESVONode node = new ESVONode();
        
        // Test normal values
        node.setChildPointer(12345);
        assertEquals(12345, node.getChildPointer());
        
        // Test maximum 17-bit value (131071)
        node.setChildPointer(0x1FFFF);
        assertEquals(0x1FFFF, node.getChildPointer());
        
        // Test that values beyond 17 bits are masked
        node.setChildPointer(0x20000); // 18th bit set
        assertEquals(0, node.getChildPointer(), "Should mask to 17 bits");
    }
    
    @Test
    @DisplayName("Test voxel data storage")
    void testVoxelData() {
        ESVONode node = new ESVONode();
        
        // Test setting voxel data
        node.setVoxelData(0xABCDEF12);
        assertEquals(0xABCDEF12, node.getVoxelData());
        
        // Test that voxel data doesn't affect child info
        node.setChildMask(0xFF);
        node.setChildPointer(1000);
        assertEquals(0xFF, node.getChildMask());
        assertEquals(1000, node.getChildPointer());
        assertEquals(0xABCDEF12, node.getVoxelData());
    }
    
    @Test
    @DisplayName("Test sparse child indexing with popcount")
    void testSparseChildIndexing() {
        ESVONode node = new ESVONode();
        
        // Set up sparse children: children at indices 0, 2, 3, 6
        node.setChildMask(0b01001101);
        node.setChildPointer(1000);
        
        // Test child node index calculation
        assertEquals(1000, node.getChildNodeIndex(0), "First child at base pointer");
        assertEquals(-1, node.getChildNodeIndex(1), "No child at index 1");
        assertEquals(1001, node.getChildNodeIndex(2), "Second child at base + 1");
        assertEquals(1002, node.getChildNodeIndex(3), "Third child at base + 2");
        assertEquals(-1, node.getChildNodeIndex(4), "No child at index 4");
        assertEquals(-1, node.getChildNodeIndex(5), "No child at index 5");
        assertEquals(1003, node.getChildNodeIndex(6), "Fourth child at base + 3");
        assertEquals(-1, node.getChildNodeIndex(7), "No child at index 7");
    }
    
    @Test
    @DisplayName("Test popcount algorithm for all patterns")
    void testPopcountAllPatterns() {
        ESVONode node = new ESVONode();
        node.setChildPointer(100);
        
        // Test all 256 possible child mask patterns
        for (int mask = 0; mask < 256; mask++) {
            node.setChildMask(mask);
            
            int expectedIndex = 100;
            for (int childIdx = 0; childIdx < 8; childIdx++) {
                if ((mask & (1 << childIdx)) != 0) {
                    int actualIndex = node.getChildNodeIndex(childIdx);
                    assertEquals(expectedIndex, actualIndex, 
                        String.format("Mask 0x%02X, child %d", mask, childIdx));
                    expectedIndex++;
                } else {
                    assertEquals(-1, node.getChildNodeIndex(childIdx),
                        String.format("Mask 0x%02X, child %d should not exist", mask, childIdx));
                }
            }
        }
    }
    
    @Test
    @DisplayName("Test leaf node detection")
    void testLeafDetection() {
        ESVONode node = new ESVONode();
        
        // Initially a leaf
        assertTrue(node.isLeaf());
        
        // Add a child - no longer a leaf
        node.setChildMask(0b00000001);
        assertFalse(node.isLeaf());
        
        // Multiple children - still not a leaf
        node.setChildMask(0b11111111);
        assertFalse(node.isLeaf());
        
        // Remove all children - becomes a leaf again
        node.setChildMask(0);
        assertTrue(node.isLeaf());
    }
    
    @Test
    @DisplayName("Test data packing and unpacking")
    void testDataPacking() {
        ESVONode node = new ESVONode();
        
        // Test maximum values don't interfere
        node.setChildMask(0xFF);           // All 8 bits
        node.setChildPointer(0x1FFFF);     // All 17 bits
        node.setVoxelData(0xFFFFFFFF);     // All 32 bits
        
        assertEquals(0xFF, node.getChildMask());
        assertEquals(0x1FFFF, node.getChildPointer());
        assertEquals(0xFFFFFFFF, node.getVoxelData());
    }
    
    @Test
    @DisplayName("Test edge cases in sparse indexing")
    void testSparseIndexingEdgeCases() {
        ESVONode node = new ESVONode();
        node.setChildPointer(5000);
        
        // Test single child at different positions
        for (int pos = 0; pos < 8; pos++) {
            node.setChildMask(1 << pos);
            assertEquals(5000, node.getChildNodeIndex(pos),
                String.format("Single child at position %d", pos));
            
            // All other positions should return -1
            for (int other = 0; other < 8; other++) {
                if (other != pos) {
                    assertEquals(-1, node.getChildNodeIndex(other));
                }
            }
        }
    }
    
    @Test
    @DisplayName("Test invalid child index handling")
    void testInvalidChildIndex() {
        ESVONode node = new ESVONode();
        node.setChildMask(0xFF);
        node.setChildPointer(1000);
        
        // Test out of bounds indices
        assertThrows(IllegalArgumentException.class, () -> node.hasChild(-1));
        assertThrows(IllegalArgumentException.class, () -> node.hasChild(8));
        assertThrows(IllegalArgumentException.class, () -> node.getChildNodeIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> node.getChildNodeIndex(8));
    }
    
    @Test
    @DisplayName("Test consistency between hasChild and getChildNodeIndex")
    void testConsistency() {
        ESVONode node = new ESVONode();
        
        // Test various patterns
        int[] testMasks = {0b00000000, 0b11111111, 0b10101010, 0b01010101, 
                           0b11110000, 0b00001111, 0b11001100, 0b00110011};
        
        for (int mask : testMasks) {
            node.setChildMask(mask);
            node.setChildPointer(2000);
            
            for (int i = 0; i < 8; i++) {
                boolean hasChild = node.hasChild(i);
                int nodeIndex = node.getChildNodeIndex(i);
                
                if (hasChild) {
                    assertTrue(nodeIndex >= 2000, 
                        String.format("Child exists but invalid index: mask=0x%02X, child=%d", mask, i));
                } else {
                    assertEquals(-1, nodeIndex,
                        String.format("No child but returned index: mask=0x%02X, child=%d", mask, i));
                }
            }
        }
    }
    
    @Test
    @DisplayName("Test node copying")
    void testNodeCopy() {
        ESVONode original = new ESVONode();
        original.setChildMask(0b10110011);
        original.setChildPointer(12345);
        original.setVoxelData(0xDEADBEEF);
        
        // Create a copy
        ESVONode copy = new ESVONode();
        copy.childDescriptor = original.childDescriptor;
        copy.voxelData = original.voxelData;
        
        // Verify copy has same values
        assertEquals(original.getChildMask(), copy.getChildMask());
        assertEquals(original.getChildPointer(), copy.getChildPointer());
        assertEquals(original.getVoxelData(), copy.getVoxelData());
        
        // Verify they're independent
        original.setChildMask(0xFF);
        assertNotEquals(original.getChildMask(), copy.getChildMask());
    }
}