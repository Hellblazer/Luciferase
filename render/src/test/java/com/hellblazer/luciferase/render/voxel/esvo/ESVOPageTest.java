package com.hellblazer.luciferase.render.voxel.esvo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVO page memory layout.
 * Validates 8KB page structure with nodes, far pointers, and attachments.
 */
public class ESVOPageTest {
    
    private Arena arena;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }
    
    @Test
    @DisplayName("Page should be exactly 8KB")
    void testPageSize() {
        var page = new ESVOPage(arena);
        assertEquals(8192, page.getSizeInBytes(), "ESVO page must be exactly 8KB");
        assertEquals(13, ESVOPage.PAGE_BYTES_LOG2, "Page size log2 should be 13");
    }
    
    @Test
    @DisplayName("Should align to 8KB boundaries")
    void testPageAlignment() {
        var page = new ESVOPage(arena);
        long address = page.getBaseAddress();
        assertEquals(0, address % 8192, "Page must be 8KB aligned");
    }
    
    @Test
    @DisplayName("Should allocate nodes correctly")
    void testNodeAllocation() {
        var page = new ESVOPage(arena);
        
        // Allocate first node
        int nodeOffset = page.allocateNode();
        assertEquals(0, nodeOffset, "First node should be at offset 0");
        
        // Allocate more nodes
        int offset2 = page.allocateNode();
        assertEquals(8, offset2, "Second node should be at offset 8");
        
        int offset3 = page.allocateNode();
        assertEquals(16, offset3, "Third node should be at offset 16");
        
        // Verify node count
        assertEquals(3, page.getNodeCount());
    }
    
    @Test
    @DisplayName("Should handle far pointers")
    void testFarPointers() {
        var page = new ESVOPage(arena);
        
        // Add far pointers
        int farIndex1 = page.addFarPointer(0x12345678);
        assertEquals(0, farIndex1, "First far pointer should have index 0");
        
        int farIndex2 = page.addFarPointer(0xABCDEF00);
        assertEquals(1, farIndex2, "Second far pointer should have index 1");
        
        // Retrieve far pointers
        assertEquals(0x12345678, page.getFarPointer(0));
        assertEquals(0xABCDEF00, page.getFarPointer(1));
        
        // Verify count
        assertEquals(2, page.getFarPointerCount());
    }
    
    @Test
    @DisplayName("Should store node data correctly")
    void testNodeStorage() {
        var page = new ESVOPage(arena);
        
        // Allocate and write node
        int offset = page.allocateNode();
        var node = new ESVONode();
        node.setValidMask((byte) 0xFF);
        node.setNonLeafMask((byte) 0xAA);
        node.setChildPointer(0x1234, false);
        node.setContourMask((byte) 0x55);
        node.setContourPointer(0xABCDEF);
        
        page.writeNode(offset, node);
        
        // Read back
        var readNode = page.readNode(offset);
        assertEquals(node.getValidMask(), readNode.getValidMask());
        assertEquals(node.getNonLeafMask(), readNode.getNonLeafMask());
        assertEquals(node.getChildPointer(), readNode.getChildPointer());
        assertEquals(node.isFarPointer(), readNode.isFarPointer());
        assertEquals(node.getContourMask(), readNode.getContourMask());
        assertEquals(node.getContourPointer(), readNode.getContourPointer());
    }
    
    @Test
    @DisplayName("Should manage attachment data")
    void testAttachmentData() {
        var page = new ESVOPage(arena);
        
        // Add attachment data
        byte[] colorData = new byte[]{(byte)0xFF, 0x00, 0x00, (byte)0xFF}; // Red
        int attachOffset = page.addAttachment(colorData);
        assertTrue(attachOffset >= 0, "Should return valid attachment offset");
        
        // Read back
        byte[] readData = page.readAttachment(attachOffset, 4);
        assertArrayEquals(colorData, readData);
    }
    
    @Test
    @DisplayName("Should handle page header correctly")
    void testPageHeader() {
        var page = new ESVOPage(arena);
        
        // Set header fields
        page.setNodeCount(10);
        page.setFarPointerCount(5);
        page.setAttachmentSize(256);
        page.setNextPageOffset(8192);
        
        // Verify
        assertEquals(10, page.getNodeCount());
        assertEquals(5, page.getFarPointerCount());
        assertEquals(256, page.getAttachmentSize());
        assertEquals(8192, page.getNextPageOffset());
    }
    
    @Test
    @DisplayName("Should calculate remaining space")
    void testSpaceCalculation() {
        var page = new ESVOPage(arena);
        
        int initialSpace = page.getRemainingSpace();
        assertTrue(initialSpace > 0, "New page should have space");
        
        // Allocate nodes
        for (int i = 0; i < 10; i++) {
            page.allocateNode();
        }
        
        int afterNodes = page.getRemainingSpace();
        assertTrue(afterNodes < initialSpace, "Space should decrease after allocation");
        
        // Add far pointers
        for (int i = 0; i < 5; i++) {
            page.addFarPointer(i * 1000);
        }
        
        int afterFar = page.getRemainingSpace();
        assertTrue(afterFar < afterNodes, "Space should decrease after far pointers");
    }
    
    @Test
    @DisplayName("Should serialize to ByteBuffer")
    void testSerialization() {
        var page = new ESVOPage(arena);
        
        // Add some data
        page.allocateNode();
        var node = new ESVONode();
        node.setValidMask((byte) 0x12);
        page.writeNode(0, node);
        
        page.addFarPointer(0xDEADBEEF);
        page.addAttachment(new byte[]{1, 2, 3, 4});
        
        // Serialize
        ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        page.writeTo(buffer);
        
        // Create new page from buffer
        buffer.flip();
        var page2 = ESVOPage.readFrom(buffer, arena);
        
        // Verify
        assertEquals(page.getNodeCount(), page2.getNodeCount());
        assertEquals(page.getFarPointerCount(), page2.getFarPointerCount());
        assertEquals(page.getAttachmentSize(), page2.getAttachmentSize());
        
        var readNode = page2.readNode(0);
        assertEquals((byte) 0x12, readNode.getValidMask());
        assertEquals(0xDEADBEEF, page2.getFarPointer(0));
    }
    
    @Test
    @DisplayName("Should handle maximum capacity")
    void testMaxCapacity() {
        var page = new ESVOPage(arena);
        
        // Try to fill page with nodes
        int maxNodes = ESVOPage.MAX_NODES_PER_PAGE;
        int allocated = 0;
        
        while (page.getRemainingSpace() >= 8 && allocated < maxNodes) {
            int offset = page.allocateNode();
            if (offset < 0) break;
            allocated++;
        }
        
        assertTrue(allocated > 0, "Should allocate at least some nodes");
        assertTrue(allocated <= maxNodes, "Should not exceed max nodes");
    }
    
    @Test
    @DisplayName("Should support direct memory access")
    void testDirectMemoryAccess() {
        var page = new ESVOPage(arena);
        
        // Get memory segment
        MemorySegment segment = page.getMemorySegment();
        assertNotNull(segment);
        assertEquals(8192, segment.byteSize());
        
        // Direct write
        segment.set(ValueLayout.JAVA_INT, 100, 0x12345678);
        
        // Direct read
        int value = segment.get(ValueLayout.JAVA_INT, 100);
        assertEquals(0x12345678, value);
    }
}