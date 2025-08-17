/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.voxel.esvo;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ESVO core components.
 * Tests the CPU-side data structures and their interactions without requiring OpenGL.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ESVOIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(ESVOIntegrationTest.class);
    
    private Arena arena;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }
    
    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("ESVONode bit field validation")
    void testESVONodeBitFields() {
        log.info("Testing ESVONode bit field operations...");
        
        var node = new ESVONode();
        
        // Test valid mask operations
        assertFalse(node.hasChild(0));
        node.setChildValid(0, true);
        node.setChildValid(2, true);
        node.setChildValid(7, true);
        
        assertEquals(0b10000101, node.getValidMask() & 0xFF);
        assertTrue(node.hasChild(0));
        assertFalse(node.hasChild(1));
        assertTrue(node.hasChild(2));
        assertTrue(node.hasChild(7));
        assertEquals(3, node.getChildCount());
        
        // Test non-leaf mask operations
        node.setNonLeafMask((byte) 0b10000001);
        assertTrue(node.isChildInternal(0));
        assertTrue(node.isChildLeaf(2));
        assertTrue(node.isChildInternal(7));
        assertEquals(2, node.getInternalChildCount());
        assertEquals(1, node.getLeafChildCount());
        
        // Test child pointer operations
        node.setChildPointer(0x7ABC, false);
        assertEquals(0x7ABC, node.getChildPointer());
        assertFalse(node.isFarPointer());
        
        node.setChildPointer(0x1234, true);
        assertEquals(0x1234, node.getChildPointer());
        assertTrue(node.isFarPointer());
        
        // Test contour operations
        node.setContourPresent(1, true);
        node.setContourPresent(3, true);
        node.setContourPresent(5, true);
        assertEquals(0b00101010, node.getContourMask() & 0xFF);
        assertEquals(3, node.getContourCount());
        
        node.setContourPointer(0x123456);
        assertEquals(0x123456, node.getContourPointer());
        
        log.info("ESVONode bit field validation passed. Node: {}", node);
    }
    
    @Test
    @Order(2)
    @DisplayName("ESVONode binary serialization")
    void testESVONodeSerialization() {
        log.info("Testing ESVONode binary serialization...");
        
        var original = new ESVONode();
        original.setValidMask((byte) 0xFF);
        original.setNonLeafMask((byte) 0xAA);
        original.setChildPointer(0x1234, true);
        original.setContourMask((byte) 0x55);
        original.setContourPointer(0x789ABC);
        
        // Test byte serialization
        byte[] bytes = original.toBytes();
        assertEquals(8, bytes.length);
        
        // Test ByteBuffer round-trip
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        original.writeTo(buffer);
        
        buffer.flip();
        var restored = ESVONode.readFrom(buffer);
        
        assertEquals(original.getValidMask(), restored.getValidMask());
        assertEquals(original.getNonLeafMask(), restored.getNonLeafMask());
        assertEquals(original.getChildPointer(), restored.getChildPointer());
        assertEquals(original.isFarPointer(), restored.isFarPointer());
        assertEquals(original.getContourMask(), restored.getContourMask());
        assertEquals(original.getContourPointer(), restored.getContourPointer());
        
        assertEquals(original.toString(), restored.toString());
        
        log.info("Binary serialization test passed. Original: {}", original);
    }
    
    @Test
    @Order(3)
    @DisplayName("ESVONode addressing calculations")
    void testESVONodeAddressing() {
        log.info("Testing ESVONode addressing calculations...");
        
        var node = new ESVONode();
        node.setValidMask((byte) 0xFF); // All children valid
        
        // Test near pointers (relative addressing)
        node.setChildPointer(10, false);
        int baseAddr = 0x1000;
        
        for (int i = 0; i < 8; i++) {
            int childAddr = node.getChildAddress(baseAddr, i);
            int expectedAddr = baseAddr + (10 + i) * 8;
            assertEquals(expectedAddr, childAddr, "Child " + i + " address mismatch");
        }
        
        // Test far pointers (absolute indices)
        node.setChildPointer(0x5000, true);
        for (int i = 0; i < 8; i++) {
            int childAddr = node.getChildAddress(baseAddr, i);
            assertEquals(0x5000, childAddr, "Far pointer should return pointer value directly");
        }
        
        // Test contour addressing
        node.setContourMask((byte) 0b10101010); // Children 1,3,5,7 have contours
        node.setContourPointer(20);
        int contourBaseAddr = 0x2000;
        
        assertEquals(-1, node.getContourAddress(contourBaseAddr, 0)); // No contour
        assertEquals(contourBaseAddr + 20 * 4, node.getContourAddress(contourBaseAddr, 1)); // First contour
        assertEquals(-1, node.getContourAddress(contourBaseAddr, 2)); // No contour
        assertEquals(contourBaseAddr + 21 * 4, node.getContourAddress(contourBaseAddr, 3)); // Second contour
        assertEquals(-1, node.getContourAddress(contourBaseAddr, 4)); // No contour
        assertEquals(contourBaseAddr + 22 * 4, node.getContourAddress(contourBaseAddr, 5)); // Third contour
        assertEquals(-1, node.getContourAddress(contourBaseAddr, 6)); // No contour
        assertEquals(contourBaseAddr + 23 * 4, node.getContourAddress(contourBaseAddr, 7)); // Fourth contour
        
        log.info("Addressing calculation test passed.");
    }
    
    @Test
    @Order(4)
    @DisplayName("ESVOPage basic operations")
    void testESVOPageBasics() {
        log.info("Testing ESVOPage basic operations...");
        
        var page = new ESVOPage(arena);
        
        // Test initial state
        assertTrue(page.isEmpty());
        assertEquals(0, page.getNodeCount());
        assertEquals(0, page.getFarPointerCount());
        assertEquals(0, page.getAttachmentSize());
        assertEquals(ESVOPage.PAGE_BYTES, page.getSizeInBytes());
        assertTrue(page.hasSpaceForNode());
        assertTrue(page.getRemainingSpace() > 1000); // Should have most of 8KB free
        
        // Test node allocation
        int offset1 = page.allocateNode();
        assertEquals(0, offset1);
        assertEquals(1, page.getNodeCount());
        assertTrue(page.hasSpaceForNode());
        
        int offset2 = page.allocateNode();
        assertEquals(8, offset2);
        assertEquals(2, page.getNodeCount());
        
        // Create test nodes
        var node1 = new ESVONode();
        node1.setValidMask((byte) 0x0F);
        node1.setChildPointer(100, false);
        
        var node2 = new ESVONode();
        node2.setValidMask((byte) 0xF0);
        node2.setChildPointer(200, true);
        
        // Write and read back
        page.writeNode(offset1, node1);
        page.writeNode(offset2, node2);
        
        var readNode1 = page.readNode(offset1);
        var readNode2 = page.readNode(offset2);
        
        assertEquals(node1.getValidMask(), readNode1.getValidMask());
        assertEquals(node1.getChildPointer(), readNode1.getChildPointer());
        assertEquals(node1.isFarPointer(), readNode1.isFarPointer());
        
        assertEquals(node2.getValidMask(), readNode2.getValidMask());
        assertEquals(node2.getChildPointer(), readNode2.getChildPointer());
        assertEquals(node2.isFarPointer(), readNode2.isFarPointer());
        
        assertFalse(page.isEmpty());
        
        log.info("ESVOPage basic operations test passed. Page: {}", page);
    }
    
    @Test
    @Order(5)
    @DisplayName("ESVOPage far pointers")
    void testESVOPageFarPointers() {
        log.info("Testing ESVOPage far pointer operations...");
        
        var page = new ESVOPage(arena);
        
        // Add several far pointers
        int ptr1Index = page.addFarPointer(0x10000);
        int ptr2Index = page.addFarPointer(0x20000);
        int ptr3Index = page.addFarPointer(0x30000);
        
        assertEquals(0, ptr1Index);
        assertEquals(1, ptr2Index);
        assertEquals(2, ptr3Index);
        assertEquals(3, page.getFarPointerCount());
        
        // Verify far pointer values
        assertEquals(0x10000, page.getFarPointer(0));
        assertEquals(0x20000, page.getFarPointer(1));
        assertEquals(0x30000, page.getFarPointer(2));
        
        // Test bounds checking
        assertThrows(IndexOutOfBoundsException.class, () -> page.getFarPointer(3));
        assertThrows(IndexOutOfBoundsException.class, () -> page.getFarPointer(-1));
        
        // Verify space tracking
        assertTrue(page.getRemainingSpace() < ESVOPage.PAGE_BYTES - 32); // 32 = header size
        
        log.info("Far pointer test passed. Page: {}", page);
    }
    
    @Test
    @Order(6)
    @DisplayName("ESVOPage attachments")
    void testESVOPageAttachments() {
        log.info("Testing ESVOPage attachment operations...");
        
        var page = new ESVOPage(arena);
        
        // Add test attachment data
        byte[] data1 = new byte[]{1, 2, 3, 4, 5};
        byte[] data2 = new byte[]{10, 20, 30, 40, 50, 60, 70, 80};
        byte[] data3 = new byte[]{100, 101, 102};
        
        int offset1 = page.addAttachment(data1);
        int offset2 = page.addAttachment(data2);
        int offset3 = page.addAttachment(data3);
        
        assertTrue(offset1 > 0);
        assertTrue(offset2 > 0);
        assertTrue(offset3 > 0);
        assertEquals(data1.length + data2.length + data3.length, page.getAttachmentSize());
        
        // Verify data integrity
        byte[] read1 = page.readAttachment(offset1, data1.length);
        byte[] read2 = page.readAttachment(offset2, data2.length);
        byte[] read3 = page.readAttachment(offset3, data3.length);
        
        assertArrayEquals(data1, read1);
        assertArrayEquals(data2, read2);
        assertArrayEquals(data3, read3);
        
        log.info("Attachment test passed. Page: {}", page);
    }
    
    @Test
    @Order(7)
    @DisplayName("ESVOPage space management")
    void testESVOPageSpaceManagement() {
        log.info("Testing ESVOPage space management...");
        
        var page = new ESVOPage(arena);
        
        // Fill with nodes until space runs out
        int nodeCount = 0;
        while (page.hasSpaceForNode()) {
            int offset = page.allocateNode();
            assertTrue(offset >= 0);
            
            var node = new ESVONode();
            node.setValidMask((byte) (nodeCount & 0xFF));
            page.writeNode(offset, node);
            
            nodeCount++;
            if (nodeCount > ESVOPage.MAX_NODES_PER_PAGE + 100) {
                fail("Too many nodes allocated - space management broken");
            }
        }
        
        log.info("Allocated {} nodes before running out of space", nodeCount);
        assertTrue(nodeCount > 0, "Should fit at least 1 node, got: " + nodeCount); // Very conservative
        assertTrue(nodeCount <= ESVOPage.MAX_NODES_PER_PAGE, "Node count " + nodeCount + " should not exceed MAX " + ESVOPage.MAX_NODES_PER_PAGE); // But not unlimited
        assertEquals(-1, page.allocateNode()); // Should fail when full
        
        // Add some far pointers and attachments to test interaction
        int farPtr = page.addFarPointer(0x12345);
        byte[] attachment = new byte[100];
        int attachOffset = page.addAttachment(attachment);
        
        // Should succeed if there's space
        if (farPtr >= 0 && attachOffset >= 0) {
            assertEquals(0x12345, page.getFarPointer(farPtr));
            assertEquals(100, page.getAttachmentSize());
        }
        
        log.info("Space management test passed. Final node count: {}, page: {}", nodeCount, page);
    }
    
    @Test
    @Order(8)
    @DisplayName("ESVOPage serialization round-trip")
    void testESVOPageSerialization() {
        log.info("Testing ESVOPage serialization round-trip...");
        
        var originalPage = new ESVOPage(arena);
        
        // Add some nodes
        for (int i = 0; i < 10; i++) {
            int offset = originalPage.allocateNode();
            var node = new ESVONode();
            node.setValidMask((byte) (i * 17));
            node.setChildPointer(i * 100, i % 2 == 0);
            originalPage.writeNode(offset, node);
        }
        
        // Add far pointers
        originalPage.addFarPointer(0x11111);
        originalPage.addFarPointer(0x22222);
        
        // Add attachments
        byte[] attachment = new byte[50];
        for (int i = 0; i < attachment.length; i++) {
            attachment[i] = (byte) (i * 3);
        }
        originalPage.addAttachment(attachment);
        
        // Serialize to bytes
        byte[] serialized = originalPage.serialize();
        assertEquals(ESVOPage.PAGE_BYTES, serialized.length);
        
        // Create new page from bytes
        ByteBuffer buffer = ByteBuffer.wrap(serialized);
        var restoredPage = ESVOPage.readFrom(buffer, arena);
        
        // Verify header
        assertEquals(originalPage.getNodeCount(), restoredPage.getNodeCount());
        assertEquals(originalPage.getFarPointerCount(), restoredPage.getFarPointerCount());
        assertEquals(originalPage.getAttachmentSize(), restoredPage.getAttachmentSize());
        
        // Verify nodes
        for (int i = 0; i < originalPage.getNodeCount(); i++) {
            var originalNode = originalPage.readNode(i * 8);
            var restoredNode = restoredPage.readNode(i * 8);
            
            assertEquals(originalNode.getValidMask(), restoredNode.getValidMask());
            assertEquals(originalNode.getChildPointer(), restoredNode.getChildPointer());
            assertEquals(originalNode.isFarPointer(), restoredNode.isFarPointer());
        }
        
        // Verify far pointers
        for (int i = 0; i < originalPage.getFarPointerCount(); i++) {
            assertEquals(originalPage.getFarPointer(i), restoredPage.getFarPointer(i));
        }
        
        log.info("Serialization round-trip test passed. Original: {}, Restored: {}", 
                 originalPage, restoredPage);
    }
    
    @Test
    @Order(9)
    @DisplayName("ESVO octree structure simulation")
    void testESVOOctreeStructure() {
        log.info("Testing ESVO octree structure simulation...");
        
        var page = new ESVOPage(arena);
        
        // Create a simple 2-level octree structure
        // Root node with 3 children, one of which has its own children
        
        // Root node
        int rootOffset = page.allocateNode();
        var rootNode = new ESVONode();
        rootNode.setValidMask((byte) 0b00000111); // Children 0, 1, 2
        rootNode.setNonLeafMask((byte) 0b00000100); // Child 2 is internal
        rootNode.setChildPointer(1, false); // Points to offset 8 (next nodes)
        page.writeNode(rootOffset, rootNode);
        
        // Leaf child 0 (at offset 8)
        int child0Offset = page.allocateNode();
        var child0 = new ESVONode();
        child0.setValidMask((byte) 0b11110000); // Has voxel data
        child0.setContourMask((byte) 0b11110000);
        child0.setContourPointer(0);
        page.writeNode(child0Offset, child0);
        
        // Leaf child 1 (at offset 16)
        int child1Offset = page.allocateNode();
        var child1 = new ESVONode();
        child1.setValidMask((byte) 0b00001111);
        child1.setContourMask((byte) 0b00001111);
        child1.setContourPointer(4);
        page.writeNode(child1Offset, child1);
        
        // Internal child 2 (at offset 24) - points to its own children
        int child2Offset = page.allocateNode();
        var child2 = new ESVONode();
        child2.setValidMask((byte) 0b11111111); // All children
        child2.setNonLeafMask((byte) 0b00000000); // All are leaves
        child2.setChildPointer(4, false); // Points to offset 32
        page.writeNode(child2Offset, child2);
        
        // Grandchildren (8 leaf nodes starting at offset 32)
        for (int i = 0; i < 8; i++) {
            int grandchildOffset = page.allocateNode();
            var grandchild = new ESVONode();
            grandchild.setValidMask((byte) (1 << i)); // Each has one voxel
            grandchild.setContourMask((byte) (1 << i));
            grandchild.setContourPointer(8 + i);
            page.writeNode(grandchildOffset, grandchild);
        }
        
        // Add some contour data
        byte[] contourData = new byte[64]; // 16 contours * 4 bytes each
        for (int i = 0; i < contourData.length; i++) {
            contourData[i] = (byte) (i ^ (i >> 4)); // Some pattern
        }
        page.addAttachment(contourData);
        
        // Verify structure by traversing
        var root = page.readNode(rootOffset);
        assertEquals(3, root.getChildCount());
        assertEquals(1, root.getInternalChildCount());
        assertEquals(2, root.getLeafChildCount());
        
        // Check child addresses
        int baseAddr = 0;
        assertEquals(8, root.getChildAddress(baseAddr, 0));
        assertEquals(16, root.getChildAddress(baseAddr, 1));
        assertEquals(24, root.getChildAddress(baseAddr, 2));
        
        // Verify internal child
        var internalChild = page.readNode(child2Offset);
        assertEquals(8, internalChild.getChildCount());
        assertEquals(0, internalChild.getInternalChildCount());
        assertEquals(8, internalChild.getLeafChildCount());
        
        // Check grandchild addresses
        for (int i = 0; i < 8; i++) {
            assertEquals(32 + i * 8, internalChild.getChildAddress(baseAddr, i));
        }
        
        assertEquals(12, page.getNodeCount()); // 1 root + 3 children + 8 grandchildren
        
        log.info("Octree structure test passed. Created {} nodes in octree", page.getNodeCount());
    }
    
    @Test
    @Order(10)
    @DisplayName("ESVO performance stress test")
    void testESVOPerformanceStress() {
        log.info("Testing ESVO performance under stress...");
        
        long startTime = System.nanoTime();
        
        // Create multiple pages
        List<ESVOPage> pages = new ArrayList<>();
        Random random = new Random(42); // Deterministic
        
        int pageCount = 10;
        int nodesPerPage = 100;
        
        for (int p = 0; p < pageCount; p++) {
            var page = new ESVOPage(arena);
            pages.add(page);
            
            // Fill with nodes
            for (int n = 0; n < nodesPerPage; n++) {
                int offset = page.allocateNode();
                if (offset < 0) break; // Page full
                
                var node = new ESVONode();
                node.setValidMask((byte) random.nextInt(256));
                node.setNonLeafMask((byte) random.nextInt(256));
                node.setChildPointer(random.nextInt(0x8000), random.nextBoolean());
                node.setContourMask((byte) random.nextInt(256));
                node.setContourPointer(random.nextInt(0x1000000));
                
                page.writeNode(offset, node);
            }
            
            // Add random far pointers
            for (int f = 0; f < 5; f++) {
                page.addFarPointer(random.nextInt(0x100000));
            }
            
            // Add random attachments
            for (int a = 0; a < 3; a++) {
                byte[] attachment = new byte[10 + random.nextInt(50)];
                random.nextBytes(attachment);
                page.addAttachment(attachment);
            }
        }
        
        // Verify all data by reading back
        for (int p = 0; p < pages.size(); p++) {
            var page = pages.get(p);
            assertTrue(page.getNodeCount() > 0);
            
            // Read all nodes
            for (int n = 0; n < page.getNodeCount(); n++) {
                var node = page.readNode(n * 8);
                assertNotNull(node);
                // Nodes should have consistent bit fields
                assertTrue(node.getChildCount() <= 8);
                assertTrue(node.getContourCount() <= 8);
            }
            
            // Verify far pointers
            for (int f = 0; f < page.getFarPointerCount(); f++) {
                int ptr = page.getFarPointer(f);
                assertTrue(ptr >= 0);
            }
        }
        
        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;
        
        int totalNodes = pages.stream().mapToInt(ESVOPage::getNodeCount).sum();
        double nodesPerMs = totalNodes / durationMs;
        
        log.info("Stress test completed in {:.2f}ms. Created {} nodes in {} pages ({:.1f} nodes/ms)", 
                 durationMs, totalNodes, pageCount, nodesPerMs);
        
        assertTrue(totalNodes > pageCount * 10); // Should create reasonable number of nodes
        assertTrue(durationMs < 1000); // Should complete quickly
    }
}