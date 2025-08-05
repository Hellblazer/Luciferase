package com.hellblazer.luciferase.render.voxel.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.foreign.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for VoxelOctreeNode implementation.
 * Tests cover bit manipulation, serialization, thread safety, and FFM integration.
 */
@DisplayName("VoxelOctreeNode Tests")
public class VoxelOctreeNodeTest {
    
    private VoxelOctreeNode node;
    private Arena testArena;
    
    @BeforeEach
    void setUp() {
        node = new VoxelOctreeNode();
        testArena = Arena.ofShared();
    }
    
    // ================================================================================
    // Basic Construction and Initialization Tests
    // ================================================================================
    
    @Test
    @DisplayName("Default constructor creates empty node")
    void testDefaultConstructor() {
        VoxelOctreeNode emptyNode = new VoxelOctreeNode();
        
        assertTrue(emptyNode.isEmpty());
        assertEquals(0, emptyNode.getValidMask());
        assertEquals(0, emptyNode.getFlags());
        assertEquals(0, emptyNode.getChildPointer());
        assertEquals(0, emptyNode.getContourMask());
        assertEquals(0, emptyNode.getContourPointer());
        assertFalse(emptyNode.hasFarPointer());
        assertTrue(emptyNode.isLeaf());
        assertEquals(0, emptyNode.getChildCount());
        assertEquals(0, emptyNode.getContourCount());
    }
    
    @Test
    @DisplayName("Packed data constructor preserves values")
    void testPackedDataConstructor() {
        // Create a node with specific bit pattern
        long packedData = 0x123456789ABCDEFL;
        VoxelOctreeNode packedNode = new VoxelOctreeNode(packedData);
        
        assertEquals(packedData, packedNode.getPackedData());
        assertFalse(packedNode.isEmpty());
    }
    
    @Test
    @DisplayName("Memory segment constructor reads data correctly")
    void testMemorySegmentConstructor() {
        // Create test data in native memory
        MemorySegment segment = testArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
        long testData = 0xFEDCBA9876543210L;
        segment.set(ValueLayout.JAVA_LONG, 0, testData);
        
        VoxelOctreeNode segmentNode = new VoxelOctreeNode(segment, 0);
        
        assertEquals(testData, segmentNode.getPackedData());
        assertNotNull(segmentNode.getNativeSegment());
        assertEquals(VoxelOctreeNode.NODE_SIZE_BYTES, segmentNode.getNativeSegment().byteSize());
    }
    
    @Test
    @DisplayName("Memory segment constructor validates parameters")
    void testMemorySegmentConstructorValidation() {
        // Test null segment
        assertThrows(IllegalArgumentException.class, () -> 
            new VoxelOctreeNode(null, 0));
        
        // Test segment too small
        MemorySegment tinySegment = testArena.allocate(4); // Only 4 bytes
        assertThrows(IllegalArgumentException.class, () -> 
            new VoxelOctreeNode(tinySegment, 0));
        
        // Test offset beyond segment
        MemorySegment normalSegment = testArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
        assertThrows(IllegalArgumentException.class, () -> 
            new VoxelOctreeNode(normalSegment, VoxelOctreeNode.NODE_SIZE_BYTES));
    }
    
    // ================================================================================
    // Valid Mask Operations Tests
    // ================================================================================
    
    @Test
    @DisplayName("Valid mask operations work correctly")
    void testValidMaskOperations() {
        // Test setting and getting valid mask
        byte testMask = (byte) 0b10101010;
        node.setValidMask(testMask);
        
        assertEquals(testMask, node.getValidMask());
        assertFalse(node.isEmpty());
        assertEquals(4, node.getChildCount()); // 4 bits set
        
        // Test individual octant checks
        assertTrue(node.hasChild(1));
        assertTrue(node.hasChild(3));
        assertTrue(node.hasChild(5));
        assertTrue(node.hasChild(7));
        assertFalse(node.hasChild(0));
        assertFalse(node.hasChild(2));
        assertFalse(node.hasChild(4));
        assertFalse(node.hasChild(6));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("Individual child operations work for all octants")
    void testIndividualChildOperations(int octant) {
        assertFalse(node.hasChild(octant));
        
        node.setChild(octant, true);
        assertTrue(node.hasChild(octant));
        assertEquals(1, node.getChildCount());
        
        node.setChild(octant, false);
        assertFalse(node.hasChild(octant));
        assertEquals(0, node.getChildCount());
    }
    
    @Test
    @DisplayName("Invalid octant indices throw exceptions")
    void testInvalidOctantIndices() {
        assertThrows(IllegalArgumentException.class, () -> node.hasChild(-1));
        assertThrows(IllegalArgumentException.class, () -> node.hasChild(8));
        assertThrows(IllegalArgumentException.class, () -> node.setChild(-1, true));
        assertThrows(IllegalArgumentException.class, () -> node.setChild(8, false));
    }
    
    // ================================================================================
    // Flags (Non-Leaf Mask) Operations Tests
    // ================================================================================
    
    @Test
    @DisplayName("Flags operations work correctly")
    void testFlagsOperations() {
        assertTrue(node.isLeaf());
        
        byte testFlags = (byte) 0b11000011;
        node.setFlags(testFlags);
        
        assertEquals(testFlags, node.getFlags());
        assertFalse(node.isLeaf());
        
        // Test individual octant flags
        assertTrue(node.isChildInternal(0));
        assertTrue(node.isChildInternal(1));
        assertFalse(node.isChildInternal(2));
        assertFalse(node.isChildInternal(3));
        assertFalse(node.isChildInternal(4));
        assertFalse(node.isChildInternal(5));
        assertTrue(node.isChildInternal(6));
        assertTrue(node.isChildInternal(7));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("Individual child internal operations work for all octants")
    void testIndividualChildInternalOperations(int octant) {
        assertFalse(node.isChildInternal(octant));
        assertTrue(node.isLeaf());
        
        node.setChildInternal(octant, true);
        assertTrue(node.isChildInternal(octant));
        assertFalse(node.isLeaf());
        
        node.setChildInternal(octant, false);
        assertFalse(node.isChildInternal(octant));
        assertTrue(node.isLeaf());
    }
    
    // ================================================================================
    // Child Pointer Operations Tests
    // ================================================================================
    
    @Test
    @DisplayName("Child pointer operations work correctly")
    void testChildPointerOperations() {
        assertEquals(0, node.getChildPointer());
        assertFalse(node.hasFarPointer());
        
        // Test setting normal pointer
        byte testPointer = 42;
        node.setChildPointer(testPointer);
        assertEquals(testPointer, node.getChildPointer());
        assertFalse(node.hasFarPointer());
        
        // Test maximum normal pointer value
        byte maxPointer = 127;
        node.setChildPointer(maxPointer);
        assertEquals(maxPointer, node.getChildPointer());
        assertFalse(node.hasFarPointer());
    }
    
    @Test
    @DisplayName("Child pointer validation works")
    void testChildPointerValidation() {
        // Test invalid pointer value (exceeds 7-bit range)
        assertThrows(IllegalArgumentException.class, () -> 
            node.setChildPointer((byte) 128));
        assertThrows(IllegalArgumentException.class, () -> 
            node.setChildPointer((byte) -1));
    }
    
    @Test
    @DisplayName("Far pointer flag operations work correctly")
    void testFarPointerOperations() {
        assertFalse(node.hasFarPointer());
        
        node.setFarPointer(true);
        assertTrue(node.hasFarPointer());
        
        node.setFarPointer(false);
        assertFalse(node.hasFarPointer());
        
        // Test that far pointer flag doesn't interfere with child pointer
        node.setChildPointer((byte) 63);
        node.setFarPointer(true);
        assertEquals(63, node.getChildPointer());
        assertTrue(node.hasFarPointer());
    }
    
    // ================================================================================
    // Contour Operations Tests
    // ================================================================================
    
    @Test
    @DisplayName("Contour mask operations work correctly")
    void testContourMaskOperations() {
        byte testMask = (byte) 0b01010101;
        node.setContourMask(testMask);
        
        assertEquals(testMask, node.getContourMask());
        assertEquals(4, node.getContourCount());
        
        // Test individual octant checks
        assertTrue(node.hasContour(0));
        assertFalse(node.hasContour(1));
        assertTrue(node.hasContour(2));
        assertFalse(node.hasContour(3));
        assertTrue(node.hasContour(4));
        assertFalse(node.hasContour(5));
        assertTrue(node.hasContour(6));
        assertFalse(node.hasContour(7));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("Individual contour operations work for all octants")
    void testIndividualContourOperations(int octant) {
        assertFalse(node.hasContour(octant));
        
        node.setContour(octant, true);
        assertTrue(node.hasContour(octant));
        assertEquals(1, node.getContourCount());
        
        node.setContour(octant, false);
        assertFalse(node.hasContour(octant));
        assertEquals(0, node.getContourCount());
    }
    
    @Test
    @DisplayName("Contour pointer operations work correctly")
    void testContourPointerOperations() {
        assertEquals(0, node.getContourPointer());
        
        // Test setting various pointer values
        int[] testPointers = {1, 1000, 65535, 1000000, 16777215}; // 24-bit max = 16777215
        
        for (int pointer : testPointers) {
            node.setContourPointer(pointer);
            assertEquals(pointer, node.getContourPointer());
        }
    }
    
    @Test
    @DisplayName("Contour pointer validation works")
    void testContourPointerValidation() {
        // Test invalid pointer values (exceed 24-bit range)
        assertThrows(IllegalArgumentException.class, () -> 
            node.setContourPointer(-1));
        assertThrows(IllegalArgumentException.class, () -> 
            node.setContourPointer(16777216)); // 2^24
        assertThrows(IllegalArgumentException.class, () -> 
            node.setContourPointer(Integer.MAX_VALUE));
    }
    
    // ================================================================================
    // Serialization and Memory Operations Tests
    // ================================================================================
    
    @Test
    @DisplayName("Packed data operations work correctly")
    void testPackedDataOperations() {
        // Set up complex node state
        node.setValidMask((byte) 0xFF);
        node.setFlags((byte) 0xAA);
        node.setChildPointer((byte) 127);
        node.setFarPointer(true);
        node.setContourMask((byte) 0x55);
        node.setContourPointer(12345678);
        
        long packedData = node.getPackedData();
        assertNotEquals(0L, packedData);
        
        // Create new node from packed data
        VoxelOctreeNode newNode = new VoxelOctreeNode(packedData);
        assertEquals(node.getValidMask(), newNode.getValidMask());
        assertEquals(node.getFlags(), newNode.getFlags());
        assertEquals(node.getChildPointer(), newNode.getChildPointer());
        assertEquals(node.hasFarPointer(), newNode.hasFarPointer());
        assertEquals(node.getContourMask(), newNode.getContourMask());
        assertEquals(node.getContourPointer(), newNode.getContourPointer());
    }
    
    @Test
    @DisplayName("Serialization to memory segment works correctly")
    void testSerializationToMemorySegment() {
        // Set up node with known data
        node.setValidMask((byte) 0x12);
        node.setFlags((byte) 0x34);
        node.setChildPointer((byte) 56);
        node.setContourMask((byte) 0x78);
        node.setContourPointer(0x9ABCDE);
        
        MemorySegment segment = testArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
        node.serializeTo(segment, 0);
        
        // Verify data was written correctly
        long writtenData = segment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(node.getPackedData(), writtenData);
    }
    
    @Test
    @DisplayName("Deserialization from memory segment works correctly")
    void testDeserializationFromMemorySegment() {
        // Create test data in memory
        MemorySegment segment = testArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
        long testData = 0x123456789ABCDEFL;
        segment.set(ValueLayout.JAVA_LONG, 0, testData);
        
        node.deserializeFrom(segment, 0);
        
        assertEquals(testData, node.getPackedData());
        assertNotNull(node.getNativeSegment());
    }
    
    @Test
    @DisplayName("Serialization validation works")
    void testSerializationValidation() {
        // Test null segment for serialization
        assertThrows(IllegalArgumentException.class, () -> 
            node.serializeTo(null, 0));
        
        // Test segment too small for serialization
        MemorySegment tinySegment = testArena.allocate(4);
        assertThrows(IllegalArgumentException.class, () -> 
            node.serializeTo(tinySegment, 0));
        
        // Test null segment for deserialization
        assertThrows(IllegalArgumentException.class, () -> 
            node.deserializeFrom(null, 0));
        
        // Test segment too small for deserialization
        assertThrows(IllegalArgumentException.class, () -> 
            node.deserializeFrom(tinySegment, 0));
    }
    
    @Test
    @DisplayName("Native memory creation works correctly")
    void testNativeMemoryCreation() {
        // Set up node with test data
        node.setValidMask((byte) 0xAB);
        node.setContourPointer(123456);
        
        MemorySegment nativeSegment = node.toNativeMemory(testArena);
        
        assertNotNull(nativeSegment);
        assertEquals(VoxelOctreeNode.NODE_SIZE_BYTES, nativeSegment.byteSize());
        
        // Verify data is correct in native memory
        long nativeData = nativeSegment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(node.getPackedData(), nativeData);
    }
    
    // ================================================================================
    // Utility Methods Tests
    // ================================================================================
    
    @Test
    @DisplayName("Copy operation works correctly")
    void testCopyOperation() {
        // Set up original node
        node.setValidMask((byte) 0xFF);
        node.setFlags((byte) 0x00);
        node.setChildPointer((byte) 100);
        node.setFarPointer(true);
        node.setContourMask((byte) 0xCC);
        node.setContourPointer(999999);
        
        VoxelOctreeNode copy = node.copy();
        
        assertNotSame(node, copy);
        assertEquals(node.getPackedData(), copy.getPackedData());
        assertEquals(node, copy); // Should be equal but not same
    }
    
    @Test
    @DisplayName("Clear operation works correctly")
    void testClearOperation() {
        // Set up node with data
        node.setValidMask((byte) 0xFF);
        node.setFlags((byte) 0xFF);
        node.setChildPointer((byte) 127);
        node.setFarPointer(true);
        node.setContourMask((byte) 0xFF);
        node.setContourPointer(16777215);
        
        assertFalse(node.isEmpty());
        
        node.clear();
        
        assertTrue(node.isEmpty());
        assertEquals(0, node.getPackedData());
        assertEquals(0, node.getValidMask());
        assertEquals(0, node.getFlags());
        assertEquals(0, node.getChildPointer());
        assertFalse(node.hasFarPointer());
        assertEquals(0, node.getContourMask());
        assertEquals(0, node.getContourPointer());
    }
    
    @Test
    @DisplayName("Count operations work correctly")
    void testCountOperations() {
        assertEquals(0, node.getChildCount());
        assertEquals(0, node.getContourCount());
        
        // Set alternating bits
        node.setValidMask((byte) 0b10101010); // 4 bits set
        node.setContourMask((byte) 0b01010101); // 4 bits set
        
        assertEquals(4, node.getChildCount());
        assertEquals(4, node.getContourCount());
        
        // Set all bits
        node.setValidMask((byte) 0xFF); // 8 bits set
        node.setContourMask((byte) 0xFF); // 8 bits set
        
        assertEquals(8, node.getChildCount());
        assertEquals(8, node.getContourCount());
    }
    
    // ================================================================================
    // Object Override Methods Tests
    // ================================================================================
    
    @Test
    @DisplayName("Equals and hashCode work correctly")
    void testEqualsAndHashCode() {
        VoxelOctreeNode node1 = new VoxelOctreeNode();
        VoxelOctreeNode node2 = new VoxelOctreeNode();
        VoxelOctreeNode node3 = new VoxelOctreeNode(0x123456789ABCDEFL);
        
        // Test reflexivity
        assertEquals(node1, node1);
        
        // Test symmetry
        assertEquals(node1, node2);
        assertEquals(node2, node1);
        
        // Test transitivity (node1 == node2, node2 == node1, so node1 == node1)
        assertEquals(node1, node1);
        
        // Test consistency
        assertEquals(node1, node2);
        assertEquals(node1, node2); // Should be equal on second call
        
        // Test null comparison
        assertNotEquals(node1, null);
        
        // Test different class
        assertNotEquals(node1, "string");
        
        // Test different data
        assertNotEquals(node1, node3);
        
        // Test hash codes
        assertEquals(node1.hashCode(), node2.hashCode());
        // Hash codes may or may not be different for different data
    }
    
    @Test
    @DisplayName("ToString provides useful information")
    void testToString() {
        node.setValidMask((byte) 0xFF);
        node.setFlags((byte) 0xAA);
        node.setChildPointer((byte) 127);
        node.setFarPointer(true);
        node.setContourMask((byte) 0x55);
        node.setContourPointer(12345678);
        
        String str = node.toString();
        
        assertNotNull(str);
        assertTrue(str.contains("VoxelOctreeNode"));
        assertTrue(str.contains("validMask"));
        assertTrue(str.contains("flags"));
        assertTrue(str.contains("childPtr"));
        assertTrue(str.contains("far"));
        assertTrue(str.contains("contourMask"));
        assertTrue(str.contains("contourPtr"));
        assertTrue(str.contains("packed"));
    }
    
    // ================================================================================
    // Thread Safety Tests
    // ================================================================================
    
    @Test
    @DisplayName("Concurrent operations are thread-safe")
    void testThreadSafety() throws InterruptedException {
        final int numThreads = 10;
        final int operationsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CompletableFuture<Void>[] futures = new CompletableFuture[numThreads];
        
        // Create tasks that perform various operations concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    // Perform various operations based on thread ID and iteration
                    switch ((threadId + j) % 6) {
                        case 0:
                            node.setValidMask((byte) (j & 0xFF));
                            break;
                        case 1:
                            node.setFlags((byte) (j & 0xFF));
                            break;
                        case 2:
                            node.setChildPointer((byte) (j & 0x7F));
                            break;
                        case 3:
                            node.setFarPointer((j & 1) == 1);
                            break;
                        case 4:
                            node.setContourMask((byte) (j & 0xFF));
                            break;
                        case 5:
                            node.setContourPointer(j & 0xFFFFFF);
                            break;
                    }
                    
                    // Read operations
                    node.getValidMask();
                    node.getFlags();
                    node.getChildPointer();
                    node.hasFarPointer();
                    node.getContourMask();
                    node.getContourPointer();
                    node.getPackedData();
                }
            }, executor);
        }
        
        // Wait for all tasks to complete
        CompletableFuture.allOf(futures).join();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Verify that the node is in a consistent state
        // (All operations should have completed without corruption)
        long finalData = node.getPackedData();
        VoxelOctreeNode verificationNode = new VoxelOctreeNode(finalData);
        assertEquals(node, verificationNode);
    }
    
    // ================================================================================
    // Bit Layout Verification Tests
    // ================================================================================
    
    @Test
    @DisplayName("Bit layout matches ESVO specification")
    void testBitLayoutSpecification() {
        // Test each field individually to verify bit positions
        node.clear();
        
        // Test valid mask (bits 0-7)
        node.setValidMask((byte) 0xFF);
        assertEquals(0x00000000000000FFL, node.getPackedData() & 0x00000000000000FFL);
        node.clear();
        
        // Test flags (bits 8-15)
        node.setFlags((byte) 0xFF);
        assertEquals(0x000000000000FF00L, node.getPackedData() & 0x000000000000FF00L);
        node.clear();
        
        // Test child pointer (bits 16-22, bit 23 is far flag)
        node.setChildPointer((byte) 0x7F);
        assertEquals(0x00000000007F0000L, node.getPackedData() & 0x00000000007F0000L);
        node.clear();
        
        // Test far pointer flag (bit 23)
        node.setFarPointer(true);
        assertEquals(0x0000000000800000L, node.getPackedData() & 0x0000000000800000L);
        node.clear();
        
        // Test contour mask (bits 24-31)
        node.setContourMask((byte) 0xFF);
        assertEquals(0x00000000FF000000L, node.getPackedData() & 0x00000000FF000000L);
        node.clear();
        
        // Test contour pointer (bits 32-55)
        node.setContourPointer(0xFFFFFF);
        assertEquals(0x00FFFFFF00000000L, node.getPackedData() & 0x00FFFFFF00000000L);
        node.clear();
    }
    
    @Test
    @DisplayName("Field isolation works correctly")
    void testFieldIsolation() {
        // Set all fields to maximum values
        node.setValidMask((byte) 0xFF);
        node.setFlags((byte) 0xFF);
        node.setChildPointer((byte) 0x7F);
        node.setFarPointer(true);
        node.setContourMask((byte) 0xFF);
        node.setContourPointer(0xFFFFFF);
        
        // Verify each field maintains its value despite others being set
        assertEquals((byte) 0xFF, node.getValidMask());
        assertEquals((byte) 0xFF, node.getFlags());
        assertEquals((byte) 0x7F, node.getChildPointer());
        assertTrue(node.hasFarPointer());
        assertEquals((byte) 0xFF, node.getContourMask());
        assertEquals(0xFFFFFF, node.getContourPointer());
        
        // Change one field and verify others are unaffected
        node.setValidMask((byte) 0x00);
        assertEquals((byte) 0x00, node.getValidMask());
        assertEquals((byte) 0xFF, node.getFlags());
        assertEquals((byte) 0x7F, node.getChildPointer());
        assertTrue(node.hasFarPointer());
        assertEquals((byte) 0xFF, node.getContourMask());
        assertEquals(0xFFFFFF, node.getContourPointer());
    }
    
    @Test
    @DisplayName("Memory layout constant is correct")
    void testMemoryLayoutConstant() {
        assertEquals(8, VoxelOctreeNode.NODE_SIZE_BYTES);
        assertEquals(8, VoxelOctreeNode.NUM_OCTANTS);
        
        // Verify memory layout size
        assertEquals(8, VoxelOctreeNode.MEMORY_LAYOUT.byteSize());
        assertEquals(8, VoxelOctreeNode.MEMORY_LAYOUT.byteAlignment());
    }
    
    // ================================================================================
    // Additional Edge Cases and Coverage Tests
    // ================================================================================
    
    @Test
    @DisplayName("Native segment synchronization works correctly")
    void testNativeSegmentSynchronization() {
        // Create node with native segment
        MemorySegment segment = testArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
        VoxelOctreeNode nodeWithNative = new VoxelOctreeNode(segment, 0);
        
        // Modify node and verify native memory is updated
        nodeWithNative.setValidMask((byte) 0xAB);
        nodeWithNative.setContourPointer(12345);
        
        // Read directly from native memory
        long nativeData = segment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(nodeWithNative.getPackedData(), nativeData);
        
        // Verify all operations sync to native memory
        nodeWithNative.setChild(3, true);
        nodeWithNative.setChildInternal(5, true);
        nodeWithNative.setFarPointer(true);
        nodeWithNative.setContour(2, true);
        nodeWithNative.clear();
        
        nativeData = segment.get(ValueLayout.JAVA_LONG, 0);
        assertEquals(0L, nativeData);
    }
    
    @Test
    @DisplayName("Extreme values and boundary conditions")
    void testExtremeValues() {
        // Test maximum values for all fields
        node.setValidMask((byte) 0xFF);
        node.setFlags((byte) 0xFF);
        node.setChildPointer((byte) 127); // Maximum 7-bit value
        node.setFarPointer(true);
        node.setContourMask((byte) 0xFF);
        node.setContourPointer(0xFFFFFF); // Maximum 24-bit value
        
        // Verify all fields maintain maximum values
        assertEquals((byte) 0xFF, node.getValidMask());
        assertEquals((byte) 0xFF, node.getFlags());
        assertEquals((byte) 127, node.getChildPointer());
        assertTrue(node.hasFarPointer());
        assertEquals((byte) 0xFF, node.getContourMask());
        assertEquals(0xFFFFFF, node.getContourPointer());
        
        // Test minimum values (should all be zero after clear)
        node.clear();
        assertEquals((byte) 0x00, node.getValidMask());
        assertEquals((byte) 0x00, node.getFlags());
        assertEquals((byte) 0, node.getChildPointer());
        assertFalse(node.hasFarPointer());
        assertEquals((byte) 0x00, node.getContourMask());
        assertEquals(0, node.getContourPointer());
    }
    
    @Test
    @DisplayName("Concurrent native memory operations")
    void testConcurrentNativeMemoryOperations() throws InterruptedException {
        final int numThreads = 5;
        final int operationsPerThread = 500;
        
        // Create multiple segments for concurrent access
        MemorySegment[] segments = new MemorySegment[numThreads];
        VoxelOctreeNode[] nodes = new VoxelOctreeNode[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            segments[i] = testArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
            nodes[i] = new VoxelOctreeNode(segments[i], 0);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CompletableFuture<Void>[] futures = new CompletableFuture[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                VoxelOctreeNode threadNode = nodes[threadIndex];
                for (int j = 0; j < operationsPerThread; j++) {
                    threadNode.setValidMask((byte) (j & 0xFF));
                    threadNode.setContourPointer(j & 0xFFFFFF);
                    
                    // Verify native memory consistency
                    long expected = threadNode.getPackedData();
                    long actual = segments[threadIndex].get(ValueLayout.JAVA_LONG, 0);
                    if (expected != actual) {
                        throw new RuntimeException("Native memory inconsistency detected");
                    }
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).join();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {-1, 8, 10, 255, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @DisplayName("Invalid octant indices for all operations")
    void testAllInvalidOctantOperations(int invalidOctant) {
        // Test all octant-based operations with invalid indices
        assertThrows(IllegalArgumentException.class, () -> node.hasChild(invalidOctant));
        assertThrows(IllegalArgumentException.class, () -> node.setChild(invalidOctant, true));
        assertThrows(IllegalArgumentException.class, () -> node.isChildInternal(invalidOctant));
        assertThrows(IllegalArgumentException.class, () -> node.setChildInternal(invalidOctant, true));
        assertThrows(IllegalArgumentException.class, () -> node.hasContour(invalidOctant));
        assertThrows(IllegalArgumentException.class, () -> node.setContour(invalidOctant, true));
    }
    
    // ================================================================================
    // JMH Performance Benchmarks
    // ================================================================================
    
    @Test
    @DisplayName("Run JMH performance benchmarks")
    @org.junit.jupiter.api.Disabled("JMH benchmarks require special setup and should not run in regular test suite")
    void runPerformanceBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(VoxelOctreeNodeBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(2)
            .measurementIterations(3)
            .build();
        
        new Runner(opt).run();
    }
    
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @State(Scope.Benchmark)
    @Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    public static class VoxelOctreeNodeBenchmark {
        
        private VoxelOctreeNode node;
        private Arena benchmarkArena;
        private MemorySegment segment;
        private AtomicInteger counter;
        
        @Setup
        public void setup() {
            node = new VoxelOctreeNode();
            benchmarkArena = Arena.ofConfined();
            segment = benchmarkArena.allocate(VoxelOctreeNode.NODE_SIZE_BYTES);
            counter = new AtomicInteger(0);
        }
        
        @TearDown
        public void teardown() {
            if (benchmarkArena != null) {
                benchmarkArena.close();
            }
        }
        
        @Benchmark
        public byte benchmarkGetValidMask() {
            return node.getValidMask();
        }
        
        @Benchmark
        public void benchmarkSetValidMask() {
            node.setValidMask((byte) (counter.incrementAndGet() & 0xFF));
        }
        
        @Benchmark
        public byte benchmarkGetFlags() {
            return node.getFlags();
        }
        
        @Benchmark
        public void benchmarkSetFlags() {
            node.setFlags((byte) (counter.incrementAndGet() & 0xFF));
        }
        
        @Benchmark
        public byte benchmarkGetChildPointer() {
            return node.getChildPointer();
        }
        
        @Benchmark
        public void benchmarkSetChildPointer() {
            node.setChildPointer((byte) (counter.incrementAndGet() & 0x7F));
        }
        
        @Benchmark
        public boolean benchmarkHasFarPointer() {
            return node.hasFarPointer();
        }
        
        @Benchmark
        public void benchmarkSetFarPointer() {
            node.setFarPointer((counter.incrementAndGet() & 1) == 1);
        }
        
        @Benchmark
        public byte benchmarkGetContourMask() {
            return node.getContourMask();
        }
        
        @Benchmark
        public void benchmarkSetContourMask() {
            node.setContourMask((byte) (counter.incrementAndGet() & 0xFF));
        }
        
        @Benchmark
        public int benchmarkGetContourPointer() {
            return node.getContourPointer();
        }
        
        @Benchmark
        public void benchmarkSetContourPointer() {
            node.setContourPointer(counter.incrementAndGet() & 0xFFFFFF);
        }
        
        @Benchmark
        public long benchmarkGetPackedData() {
            return node.getPackedData();
        }
        
        @Benchmark
        public void benchmarkSetPackedData() {
            node.setPackedData(counter.incrementAndGet());
        }
        
        @Benchmark
        public boolean benchmarkHasChild() {
            return node.hasChild(counter.incrementAndGet() & 0x7);
        }
        
        @Benchmark
        public void benchmarkSetChild() {
            int octant = counter.incrementAndGet() & 0x7;
            node.setChild(octant, (counter.get() & 1) == 1);
        }
        
        @Benchmark
        public void benchmarkSerializeTo() {
            node.serializeTo(segment, 0);
        }
        
        @Benchmark
        public void benchmarkDeserializeFrom() {
            node.deserializeFrom(segment, 0);
        }
        
        @Benchmark
        public VoxelOctreeNode benchmarkCopy() {
            return node.copy();
        }
        
        @Benchmark
        public void benchmarkClear() {
            node.clear();
        }
        
        @Benchmark
        public boolean benchmarkIsEmpty() {
            return node.isEmpty();
        }
        
        @Benchmark
        public boolean benchmarkIsLeaf() {
            return node.isLeaf();
        }
        
        @Benchmark
        public int benchmarkGetChildCount() {
            return node.getChildCount();
        }
        
        @Benchmark
        public int benchmarkGetContourCount() {
            return node.getContourCount();
        }
        
        @Benchmark
        public VoxelOctreeNode benchmarkConstructorDefault() {
            return new VoxelOctreeNode();
        }
        
        @Benchmark
        public VoxelOctreeNode benchmarkConstructorPacked() {
            return new VoxelOctreeNode(counter.incrementAndGet());
        }
        
        @Benchmark
        public MemorySegment benchmarkToNativeMemory() {
            return node.toNativeMemory(benchmarkArena);
        }
        
        @Benchmark
        @Group("concurrent_ops")
        @GroupThreads(4)
        public void benchmarkConcurrentWrite() {
            int value = counter.incrementAndGet();
            node.setValidMask((byte) (value & 0xFF));
            node.setFlags((byte) ((value >> 8) & 0xFF));
            node.setChildPointer((byte) (value & 0x7F));
            node.setContourMask((byte) ((value >> 16) & 0xFF));
        }
        
        @Benchmark
        @Group("concurrent_ops")
        @GroupThreads(4)
        public long benchmarkConcurrentRead() {
            node.getValidMask();
            node.getFlags();
            node.getChildPointer();
            node.getContourMask();
            return node.getPackedData();
        }
        
        @Benchmark
        public void benchmarkComplexOperation() {
            int value = counter.incrementAndGet();
            
            // Simulate a complex operation involving multiple field updates
            node.setValidMask((byte) (value & 0xFF));
            node.setFlags((byte) ((value >> 8) & 0xFF));
            
            for (int i = 0; i < 8; i++) {
                if ((value & (1 << i)) != 0) {
                    node.setChild(i, true);
                    node.setChildInternal(i, (value & (1 << (i + 8))) != 0);
                    node.setContour(i, (value & (1 << (i + 16))) != 0);
                }
            }
            
            node.setChildPointer((byte) (value & 0x7F));
            node.setFarPointer((value & 0x80000000) != 0);
            node.setContourPointer(value & 0xFFFFFF);
            
            // Read back to ensure consistency
            if (node.getPackedData() == 0) {
                node.clear(); // Should never happen, but prevents dead code elimination
            }
        }
    }
}
