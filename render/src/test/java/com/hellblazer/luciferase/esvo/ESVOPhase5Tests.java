package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.io.*;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 tests for ESVO File I/O System
 */
public class ESVOPhase5Tests {

    @TempDir
    Path tempDir;
    
    private ESVOSerializer serializer;
    private ESVODeserializer deserializer;
    
    @BeforeEach
    void setUp() {
        serializer = new ESVOSerializer();
        deserializer = new ESVODeserializer();
    }
    
    @Test
    void testBasicSerialization() throws IOException {
        // Create a simple octree with a few nodes
        ESVOOctreeData octree = new ESVOOctreeData(512); // 512MB octree
        
        // Add root node
        ESVONodeUnified root = new ESVONodeUnified(
            ((0b11110000 & 0xFF) << 8), // childDescriptor with childMask
            (0x12345678 << 8)  // contourDescriptor with contourPtr in bits 8-31
        );
        octree.setNode(0, root);
        
        // Add child nodes
        for (int i = 0; i < 4; i++) {
            ESVONodeUnified child = new ESVONodeUnified(
                0, // childDescriptor with childMask = 0
                ((0x1000 + i) << 8)  // contourDescriptor with contourPtr in bits 8-31
            );
            octree.setNode(i + 1, child);
        }
        
        // Serialize to file
        Path outputFile = tempDir.resolve("test_octree.esvo");
        serializer.serialize(octree, outputFile);
        
        // Verify file exists and has content
        assertTrue(outputFile.toFile().exists());
        assertTrue(outputFile.toFile().length() > 0);
        
        // Deserialize and verify
        ESVOOctreeData loaded = deserializer.deserialize(outputFile);
        assertNotNull(loaded);
        
        // Check root node
        ESVONodeUnified loadedRoot = loaded.getNode(0);
        assertEquals((byte)0b11110000, (byte)loadedRoot.getChildMask());
        assertEquals(0x345678, loadedRoot.getContourPtr()); // Due to << 8 shift
        
        // Check children
        for (int i = 0; i < 4; i++) {
            ESVONodeUnified loadedChild = loaded.getNode(i + 1);
            assertEquals(0, loadedChild.getChildMask());
            assertEquals(0x1000 + i, loadedChild.getContourPtr()); // Values are correct as-is
        }
    }
    
    @Test
    void testCompressedSerialization() throws IOException {
        // Create larger octree for compression testing
        ESVOOctreeData octree = new ESVOOctreeData(1024);
        
        // Fill with pattern data that compresses well
        for (int i = 0; i < 100; i++) {
            ESVONodeUnified node = new ESVONodeUnified(
                ((i % 256) & 0xFF) << 8, // childDescriptor with childMask
                ((i * 1000) << 8)  // contourDescriptor with contourPtr in bits 8-31
            );
            octree.setNode(i, node);
        }
        
        
        // Serialize with compression
        Path compressedFile = tempDir.resolve("compressed.esvo.gz");
        ESVOCompressedSerializer compSerializer = new ESVOCompressedSerializer();
        compSerializer.serialize(octree, compressedFile);
        
        // Serialize without compression for comparison
        Path uncompressedFile = tempDir.resolve("uncompressed.esvo");
        serializer.serialize(octree, uncompressedFile);
        
        // Verify compression achieved
        long compressedSize = compressedFile.toFile().length();
        long uncompressedSize = uncompressedFile.toFile().length();
        assertTrue(compressedSize < uncompressedSize,
                  "Compressed size should be smaller");
        
        // Deserialize compressed file
        ESVOCompressedDeserializer compDeserializer = new ESVOCompressedDeserializer();
        ESVOOctreeData loaded = compDeserializer.deserialize(compressedFile);
        
        // Verify data integrity
        for (int i = 0; i < 100; i++) {
            ESVONodeUnified node = loaded.getNode(i);
            assertEquals((byte)(i % 256), node.getChildMask());
            assertEquals(i * 1000, node.getContourPtr());
        }
    }
    
    @Test
    void testMemoryMappedFile() throws IOException {
        // Create octree
        ESVOOctreeData octree = new ESVOOctreeData(2048);
        
        // Add nodes with specific patterns
        for (int i = 0; i < 50; i++) {
            int childPtr = (i > 25) ? i * 100 : 0;
            ESVONodeUnified node = new ESVONodeUnified(
                ((0xFF - i) & 0xFF) << 8 | (childPtr << 17), // childDescriptor
                ((0xABCD00 | i) << 8)  // contourDescriptor with contourPtr in bits 8-31
            );
            octree.setNode(i, node);
        }
        
        // Write using memory-mapped file
        Path mmapFile = tempDir.resolve("mmap.esvo");
        ESVOMemoryMappedWriter writer = new ESVOMemoryMappedWriter();
        writer.write(octree, mmapFile);
        
        // Read using memory-mapped file
        ESVOMemoryMappedReader reader = new ESVOMemoryMappedReader();
        ESVOOctreeData loaded = reader.read(mmapFile);
        
        // Verify all nodes
        for (int i = 0; i < 50; i++) {
            ESVONodeUnified node = loaded.getNode(i);
            assertEquals((byte)(0xFF - i), (byte)node.getChildMask());
            assertEquals(0xABCD00 | i, node.getContourPtr());
            assertEquals((i > 25) ? i * 100 : 0, node.getChildPtr());
        }
        
        // Test random access capability
        ESVONodeUnified randomNode = reader.readNode(mmapFile, 42);
        assertEquals((byte)(0xFF - 42), (byte)randomNode.getChildMask());
        assertEquals(0xABCD00 | 42, randomNode.getContourPtr());
    }
    
    @Test
    void testStreamingSerialization() throws IOException {
        // Test streaming for large octrees
        Path streamFile = tempDir.resolve("stream.esvo");
        
        // Create streaming writer
        ESVOStreamWriter streamWriter = new ESVOStreamWriter(streamFile);
        
        // Write nodes in batches
        int totalNodes = 10000;
        int batchSize = 100;
        
        for (int batch = 0; batch < totalNodes / batchSize; batch++) {
            List<ESVONodeUnified> nodes = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                int nodeId = batch * batchSize + i;
                ESVONodeUnified node = new ESVONodeUnified(
                    ((nodeId & 0xFF) << 8), // childDescriptor with childMask
                    ((nodeId * 7) << 8)  // contourDescriptor with contourPtr in bits 8-31
                );
                nodes.add(node);
            }
            streamWriter.writeNodeBatch(nodes);
        }
        streamWriter.close();
        
        // Read using streaming reader
        ESVOStreamReader streamReader = new ESVOStreamReader(streamFile);
        
        // Verify nodes
        int nodesRead = 0;
        while (streamReader.hasNext()) {
            ESVONodeUnified node = streamReader.readNext();
            assertEquals((byte)(nodesRead & 0xFF), (byte)node.getChildMask());
            assertEquals(nodesRead * 7, node.getContourPtr());
            nodesRead++;
        }
        streamReader.close();
        
        assertEquals(totalNodes, nodesRead);
    }
    
    @Test
    void testFileFormatVersioning() throws IOException {
        // Test handling of different file format versions
        Path v1File = tempDir.resolve("v1.esvo");
        Path v2File = tempDir.resolve("v2.esvo");
        
        // Create octree
        ESVOOctreeData octree = new ESVOOctreeData(512);
        ESVONodeUnified node = new ESVONodeUnified(
            ((0x42 & 0xFF) << 8), // childDescriptor with childMask
            (0xDEADBEEF << 8)  // contourDescriptor with contourPtr in bits 8-31
        );
        octree.setNode(0, node);
        
        // Write as version 1
        ESVOSerializer v1Serializer = new ESVOSerializer(ESVOFileFormat.VERSION_1);
        v1Serializer.serialize(octree, v1File);
        
        // Write as version 2 (with extended features)
        ESVOSerializer v2Serializer = new ESVOSerializer(ESVOFileFormat.VERSION_2);
        v2Serializer.serialize(octree, v2File);
        
        // Verify version detection
        assertEquals(ESVOFileFormat.VERSION_1, 
                    ESVOFileFormat.detectVersion(v1File));
        assertEquals(ESVOFileFormat.VERSION_2,
                    ESVOFileFormat.detectVersion(v2File));
        
        // Verify both can be read
        ESVOOctreeData v1Loaded = deserializer.deserialize(v1File);
        ESVOOctreeData v2Loaded = deserializer.deserialize(v2File);
        
        // Check data integrity
        assertEquals((byte)0x42, (byte)v1Loaded.getNode(0).getChildMask());
        assertEquals(0xADBEEF, v1Loaded.getNode(0).getContourPtr()); // 0xADBEEF as unsigned int
        assertEquals((byte)0x42, (byte)v2Loaded.getNode(0).getChildMask());
        assertEquals(0xADBEEF, v2Loaded.getNode(0).getContourPtr()); // 0xADBEEF as unsigned int
    }
    
    @Test
    void testMetadataSerialization() throws IOException {
        // Test serialization with metadata
        ESVOOctreeData octree = new ESVOOctreeData(1024);
        
        // Create metadata
        ESVOMetadata metadata = new ESVOMetadata();
        metadata.setCreationTime(System.currentTimeMillis());
        metadata.setOctreeDepth(12);
        metadata.setBoundingBox(new Vector3f(1.0f, 1.0f, 1.0f),
                               new Vector3f(2.0f, 2.0f, 2.0f));
        metadata.setNodeCount(1000);
        metadata.setCompressionType("gzip");
        metadata.addCustomProperty("author", "ESVO Test Suite");
        metadata.addCustomProperty("version", "1.0.0");
        
        // Add some nodes
        for (int i = 0; i < 10; i++) {
            ESVONodeUnified node = new ESVONodeUnified(
                ((i & 0xFF) << 8), // childDescriptor with childMask
                ((i * 100) << 8)  // contourDescriptor with contourPtr in bits 8-31
            );
            octree.setNode(i, node);
        }
        
        // Serialize with metadata
        Path metaFile = tempDir.resolve("with_metadata.esvo");
        serializer.serializeWithMetadata(octree, metadata, metaFile);
        
        // Deserialize and verify metadata
        ESVODeserializer.Result result = deserializer.deserializeWithMetadata(metaFile);
        assertNotNull(result.octree);
        assertNotNull(result.metadata);
        
        // Check metadata
        assertEquals(12, result.metadata.getOctreeDepth());
        assertEquals(1000, result.metadata.getNodeCount());
        assertEquals("gzip", result.metadata.getCompressionType());
        assertEquals("ESVO Test Suite", result.metadata.getCustomProperty("author"));
        assertEquals("1.0.0", result.metadata.getCustomProperty("version"));
        
        // Check bounding box
        Vector3f min = result.metadata.getBoundingBoxMin();
        Vector3f max = result.metadata.getBoundingBoxMax();
        assertEquals(1.0f, min.x, 0.001f);
        assertEquals(2.0f, max.x, 0.001f);
        
        // Check octree data
        for (int i = 0; i < 10; i++) {
            ESVONodeUnified node = result.octree.getNode(i);
            assertEquals((byte)i, node.getChildMask());
            assertEquals(i * 100, node.getContourPtr());
        }
    }
}