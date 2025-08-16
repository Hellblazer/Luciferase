package com.hellblazer.luciferase.render.voxel.esvo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.foreign.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVO-compatible octree node format.
 * Validates binary compatibility with ESVO's 8-byte node structure.
 */
public class ESVONodeTest {
    
    private Arena arena;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }
    
    @Test
    @DisplayName("Node should be exactly 8 bytes")
    void testNodeSize() {
        var node = new ESVONode();
        assertEquals(8, node.getSizeInBytes(), "ESVO node must be exactly 8 bytes");
    }
    
    @Test
    @DisplayName("Should pack and unpack valid mask correctly")
    void testValidMask() {
        var node = new ESVONode();
        
        // Test all 8 bits
        node.setValidMask((byte) 0xFF);
        assertEquals((byte) 0xFF, node.getValidMask());
        
        // Test specific pattern
        node.setValidMask((byte) 0b10101010);
        assertEquals((byte) 0b10101010, node.getValidMask());
        
        // Test child existence
        node.setValidMask((byte) 0b00000001);
        assertTrue(node.hasChild(0));
        assertFalse(node.hasChild(1));
        
        node.setValidMask((byte) 0b10000000);
        assertTrue(node.hasChild(7));
        assertFalse(node.hasChild(0));
    }
    
    @Test
    @DisplayName("Should pack and unpack non-leaf mask correctly")
    void testNonLeafMask() {
        var node = new ESVONode();
        
        node.setNonLeafMask((byte) 0b11110000);
        assertEquals((byte) 0b11110000, node.getNonLeafMask());
        
        // Test leaf vs internal detection
        node.setValidMask((byte) 0xFF);
        node.setNonLeafMask((byte) 0b11110000);
        
        assertTrue(node.isChildInternal(4));
        assertTrue(node.isChildInternal(7));
        assertFalse(node.isChildInternal(0));
        assertFalse(node.isChildInternal(3));
    }
    
    @Test
    @DisplayName("Should handle near pointers (15-bit)")
    void testNearPointer() {
        var node = new ESVONode();
        
        // Maximum 15-bit value
        int maxNear = 0x7FFF;
        node.setChildPointer(maxNear, false);
        assertEquals(maxNear, node.getChildPointer());
        assertFalse(node.isFarPointer());
        
        // Test various values
        node.setChildPointer(12345, false);
        assertEquals(12345, node.getChildPointer());
        assertFalse(node.isFarPointer());
    }
    
    @Test
    @DisplayName("Should handle far pointer flag")
    void testFarPointer() {
        var node = new ESVONode();
        
        // Set as far pointer
        node.setChildPointer(100, true);
        assertEquals(100, node.getChildPointer());
        assertTrue(node.isFarPointer());
        
        // Switch back to near
        node.setChildPointer(200, false);
        assertEquals(200, node.getChildPointer());
        assertFalse(node.isFarPointer());
    }
    
    @Test
    @DisplayName("Should pack and unpack contour mask correctly")
    void testContourMask() {
        var node = new ESVONode();
        
        node.setContourMask((byte) 0b01010101);
        assertEquals((byte) 0b01010101, node.getContourMask());
        
        // Test contour presence
        node.setContourMask((byte) 0b00000010);
        assertTrue(node.hasContour(1));
        assertFalse(node.hasContour(0));
        assertFalse(node.hasContour(2));
    }
    
    @Test
    @DisplayName("Should handle contour pointer (24-bit)")
    void testContourPointer() {
        var node = new ESVONode();
        
        // Maximum 24-bit value
        int max24Bit = 0xFFFFFF;
        node.setContourPointer(max24Bit);
        assertEquals(max24Bit, node.getContourPointer());
        
        // Test various values
        node.setContourPointer(0x123456);
        assertEquals(0x123456, node.getContourPointer());
    }
    
    @Test
    @DisplayName("Should serialize to/from ByteBuffer correctly")
    void testSerialization() {
        var node = new ESVONode();
        
        // Set test data
        node.setValidMask((byte) 0xAA);
        node.setNonLeafMask((byte) 0x55);
        node.setChildPointer(0x1234, true);
        node.setContourMask((byte) 0xCC);
        node.setContourPointer(0xABCDEF);
        
        // Serialize
        ByteBuffer buffer = ByteBuffer.allocateDirect(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        node.writeTo(buffer);
        
        // Deserialize
        buffer.flip();
        var node2 = ESVONode.readFrom(buffer);
        
        // Verify
        assertEquals(node.getValidMask(), node2.getValidMask());
        assertEquals(node.getNonLeafMask(), node2.getNonLeafMask());
        assertEquals(node.getChildPointer(), node2.getChildPointer());
        assertEquals(node.isFarPointer(), node2.isFarPointer());
        assertEquals(node.getContourMask(), node2.getContourMask());
        assertEquals(node.getContourPointer(), node2.getContourPointer());
    }
    
    @Test
    @DisplayName("Should work with MemorySegment (FFM)")
    void testMemorySegment() {
        MemorySegment segment = arena.allocate(8);
        
        var node = new ESVONode(segment);
        
        // Test operations on memory segment
        node.setValidMask((byte) 0x12);
        node.setNonLeafMask((byte) 0x34);
        node.setChildPointer(0x5678, false);
        node.setContourMask((byte) 0x9A);
        node.setContourPointer(0xBCDEF0);
        
        // Read back directly from memory
        int word0 = segment.get(ValueLayout.JAVA_INT, 0);
        int word1 = segment.get(ValueLayout.JAVA_INT, 4);
        
        // Verify bit packing
        assertEquals(0x12, word0 & 0xFF);           // Valid mask
        assertEquals(0x34, (word0 >> 8) & 0xFF);    // Non-leaf mask
        assertEquals(0, (word0 >> 16) & 1);         // Far flag
        assertEquals(0x5678, (word0 >> 17) & 0x7FFF); // Child pointer
        
        assertEquals(0x9A, word1 & 0xFF);           // Contour mask
        assertEquals(0xBCDEF0, (word1 >> 8) & 0xFFFFFF); // Contour pointer
    }
    
    @Test
    @DisplayName("Should compute child addresses correctly")
    void testChildAddressing() {
        var node = new ESVONode();
        int baseAddr = 1000;
        
        // Near pointer
        node.setChildPointer(100, false);
        
        // Child offset calculation
        for (int i = 0; i < 8; i++) {
            int childAddr = node.getChildAddress(baseAddr, i);
            // Near pointers are qword (8-byte) offsets
            assertEquals(baseAddr + (100 + i) * 8, childAddr);
        }
    }
    
    @Test
    @DisplayName("Should handle empty node correctly")
    void testEmptyNode() {
        var node = new ESVONode();
        
        assertEquals(0, node.getValidMask());
        assertEquals(0, node.getNonLeafMask());
        assertEquals(0, node.getChildPointer());
        assertFalse(node.isFarPointer());
        assertEquals(0, node.getContourMask());
        assertEquals(0, node.getContourPointer());
        
        for (int i = 0; i < 8; i++) {
            assertFalse(node.hasChild(i));
            assertFalse(node.hasContour(i));
            assertFalse(node.isChildInternal(i));
        }
    }
}