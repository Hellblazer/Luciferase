package com.hellblazer.luciferase.render.voxel.esvo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVO file format reading and writing.
 */
public class ESVOFileFormatTest {
    
    @TempDir
    Path tempDir;
    
    private Path testFile;
    private Arena arena;
    
    @BeforeEach
    void setUp() {
        testFile = tempDir.resolve("test.octree");
        arena = Arena.ofConfined();
    }
    
    @Test
    @DisplayName("Should write and read file header correctly")
    void testFileHeader() throws IOException {
        var format = new ESVOFileFormat();
        
        // Create header
        var header = new ESVOFileFormat.Header();
        header.formatId = "Octree  ";
        header.version = 1;
        header.numObjects = 3;
        header.numSlices = 10;
        
        // Write header
        format.writeHeader(testFile, header);
        
        // Read back
        var readHeader = format.readHeader(testFile);
        
        assertEquals(header.formatId, readHeader.formatId);
        assertEquals(header.version, readHeader.version);
        assertEquals(header.numObjects, readHeader.numObjects);
        assertEquals(header.numSlices, readHeader.numSlices);
    }
    
    @Test
    @DisplayName("Should handle object entries")
    void testObjectEntries() throws IOException {
        var format = new ESVOFileFormat();
        
        // Create object entry
        var obj = new ESVOFileFormat.ObjectEntry();
        obj.objectToWorld = new float[16];
        obj.octreeToObject = new float[16];
        for (int i = 0; i < 16; i++) {
            obj.objectToWorld[i] = i * 0.1f;
            obj.octreeToObject[i] = i * 0.2f;
        }
        obj.rootSliceId = 42;
        obj.attachmentTypes = new int[]{1, 3, 6, 10}; // Various attachment types
        
        // Write to buffer
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        obj.writeTo(buffer);
        
        // Read back
        buffer.flip();
        var readObj = ESVOFileFormat.ObjectEntry.readFrom(buffer);
        
        assertArrayEquals(obj.objectToWorld, readObj.objectToWorld);
        assertArrayEquals(obj.octreeToObject, readObj.octreeToObject);
        assertEquals(obj.rootSliceId, readObj.rootSliceId);
        assertArrayEquals(obj.attachmentTypes, readObj.attachmentTypes);
    }
    
    @Test
    @DisplayName("Should handle slice info structure")
    void testSliceInfo() throws IOException {
        var slice = new ESVOFileFormat.SliceInfo();
        
        // Set test data
        slice.id = 123;
        slice.state = ESVOFileFormat.SliceState.LOADED;
        slice.cubePos = new int[]{1000, 2000, 3000};
        slice.cubeScale = 10;
        slice.nodeScale = 5;
        slice.numChildEntries = 8;
        slice.childEntryPtr = 0x1000;
        slice.numAttach = 4;
        slice.attachInfoPtr = 0x2000;
        slice.numNodes = 256;
        slice.nodeSplitPtr = 0x3000;
        slice.numSplitNodes = 128;
        slice.nodeValidMaskPtr = 0x4000;
        
        // Write to buffer
        ByteBuffer buffer = ByteBuffer.allocate(256);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        slice.writeTo(buffer);
        
        // Read back
        buffer.flip();
        var readSlice = ESVOFileFormat.SliceInfo.readFrom(buffer);
        
        assertEquals(slice.id, readSlice.id);
        assertEquals(slice.state, readSlice.state);
        assertArrayEquals(slice.cubePos, readSlice.cubePos);
        assertEquals(slice.cubeScale, readSlice.cubeScale);
        assertEquals(slice.nodeScale, readSlice.nodeScale);
        assertEquals(slice.numChildEntries, readSlice.numChildEntries);
        assertEquals(slice.childEntryPtr, readSlice.childEntryPtr);
        assertEquals(slice.numAttach, readSlice.numAttach);
        assertEquals(slice.attachInfoPtr, readSlice.attachInfoPtr);
        assertEquals(slice.numNodes, readSlice.numNodes);
        assertEquals(slice.nodeSplitPtr, readSlice.nodeSplitPtr);
        assertEquals(slice.numSplitNodes, readSlice.numSplitNodes);
        assertEquals(slice.nodeValidMaskPtr, readSlice.nodeValidMaskPtr);
    }
    
    @Test
    @DisplayName("Should write complete octree file")
    void testCompleteFile() throws IOException {
        var format = new ESVOFileFormat();
        
        // Create test octree
        var octree = new ESVOFileFormat.OctreeData();
        
        // Header
        octree.header = new ESVOFileFormat.Header();
        octree.header.numObjects = 1;
        octree.header.numSlices = 2;
        
        // Object
        var obj = new ESVOFileFormat.ObjectEntry();
        obj.objectToWorld = new float[16];
        obj.octreeToObject = new float[16];
        for (int i = 0; i < 16; i++) {
            obj.objectToWorld[i] = (i == 0 || i == 5 || i == 10 || i == 15) ? 1.0f : 0.0f;
            obj.octreeToObject[i] = obj.objectToWorld[i];
        }
        obj.rootSliceId = 0;
        obj.attachmentTypes = new int[]{1}; // DXT compression
        octree.objects = new ESVOFileFormat.ObjectEntry[]{obj};
        
        // Slices
        octree.slices = new ESVOFileFormat.SliceData[2];
        
        // Root slice
        var rootSlice = new ESVOFileFormat.SliceData();
        rootSlice.info = new ESVOFileFormat.SliceInfo();
        rootSlice.info.id = 0;
        rootSlice.info.state = ESVOFileFormat.SliceState.LOADED;
        rootSlice.info.cubePos = new int[]{0, 0, 0};
        rootSlice.info.cubeScale = 23; // Full scale
        rootSlice.info.nodeScale = 0;
        rootSlice.info.numNodes = 1;
        
        // Root node
        rootSlice.page = new ESVOPage(arena);
        int nodeOffset = rootSlice.page.allocateNode();
        var rootNode = new ESVONode();
        rootNode.setValidMask((byte) 0xFF); // All children valid
        rootNode.setNonLeafMask((byte) 0xFF); // All internal
        rootNode.setChildPointer(1, false); // Point to child slice
        rootSlice.page.writeNode(nodeOffset, rootNode);
        
        octree.slices[0] = rootSlice;
        
        // Child slice
        var childSlice = new ESVOFileFormat.SliceData();
        childSlice.info = new ESVOFileFormat.SliceInfo();
        childSlice.info.id = 1;
        childSlice.info.state = ESVOFileFormat.SliceState.LOADED;
        childSlice.info.cubePos = new int[]{0, 0, 0};
        childSlice.info.cubeScale = 22;
        childSlice.info.nodeScale = 1;
        childSlice.info.numNodes = 8;
        
        childSlice.page = new ESVOPage(arena);
        for (int i = 0; i < 8; i++) {
            int offset = childSlice.page.allocateNode();
            var node = new ESVONode();
            node.setValidMask((byte) 0); // Leaf nodes
            childSlice.page.writeNode(offset, node);
        }
        
        octree.slices[1] = childSlice;
        
        // Write file
        format.writeOctreeFile(testFile, octree);
        
        // Verify file exists and has content
        assertTrue(Files.exists(testFile));
        assertTrue(Files.size(testFile) > 0);
        
        // Read back
        var readOctree = format.readOctreeFile(testFile, arena);
        
        // Verify header
        assertEquals(1, readOctree.header.numObjects);
        assertEquals(2, readOctree.header.numSlices);
        
        // Verify object
        assertEquals(1, readOctree.objects.length);
        assertEquals(0, readOctree.objects[0].rootSliceId);
        
        // Verify slices
        assertEquals(2, readOctree.slices.length);
        assertEquals(0, readOctree.slices[0].info.id);
        assertEquals(1, readOctree.slices[1].info.id);
        assertEquals(1, readOctree.slices[0].info.numNodes);
        assertEquals(8, readOctree.slices[1].info.numNodes);
    }
    
    @Test
    @DisplayName("Should handle attachment data")
    void testAttachmentData() {
        var attach = new ESVOFileFormat.AttachmentInfo();
        attach.type = ESVOFileFormat.AttachType.COLOR_NORMAL_DXT;
        attach.dataOffset = 0x5000;
        attach.dataSize = 24 * 256; // 24 bytes per node, 256 nodes
        
        // Write to buffer
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        attach.writeTo(buffer);
        
        // Read back
        buffer.flip();
        var readAttach = ESVOFileFormat.AttachmentInfo.readFrom(buffer);
        
        assertEquals(attach.type, readAttach.type);
        assertEquals(attach.dataOffset, readAttach.dataOffset);
        assertEquals(attach.dataSize, readAttach.dataSize);
    }
    
    @Test
    @DisplayName("Should calculate slice average nodes correctly")
    void testSliceAverageCalculation() {
        var format = new ESVOFileFormat();
        
        // Test with realistic node counts that would fill slices
        // ESVO scenes typically have millions of nodes
        int[] nodeCounts = {49152, 98304, 147456, 196608}; // 1x, 2x, 3x, 4x AVG_NODES_PER_SLICE
        int totalNodes = 0;
        int sliceCount = 0;
        
        for (int count : nodeCounts) {
            totalNodes += count;
            sliceCount += (count + ESVOFileFormat.AVG_NODES_PER_SLICE - 1) / ESVOFileFormat.AVG_NODES_PER_SLICE;
        }
        
        // With these counts, average should be close to target
        float average = (float) totalNodes / sliceCount;
        assertEquals(ESVOFileFormat.AVG_NODES_PER_SLICE, average, 1.0,
                "Average nodes per slice should match target for full slices");
        
        // Also test that slice allocation rounds up correctly
        assertEquals(1, (100 + ESVOFileFormat.AVG_NODES_PER_SLICE - 1) / ESVOFileFormat.AVG_NODES_PER_SLICE);
        assertEquals(2, (49153 + ESVOFileFormat.AVG_NODES_PER_SLICE - 1) / ESVOFileFormat.AVG_NODES_PER_SLICE);
    }
    
    @Test
    @DisplayName("Should validate file format ID")
    void testFormatValidation() throws IOException {
        var format = new ESVOFileFormat();
        
        // Write invalid header
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("INVALID ".getBytes());
        buffer.putInt(1); // version
        buffer.putInt(0); // objects
        buffer.putInt(0); // slices
        buffer.putInt(0); // reserved
        
        Files.write(testFile, buffer.array());
        
        // Should throw exception on invalid format
        assertThrows(IOException.class, () -> {
            format.readHeader(testFile);
        });
    }
}