package com.hellblazer.luciferase.esvo;

import com.hellblazer.luciferase.esvo.io.*;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;
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
        ESVOOctreeNode root = new ESVOOctreeNode();
        root.childMask = (byte)0b11110000; // 4 children
        root.contour = 0x12345678;
        octree.setNode(0, root);
        
        // Add child nodes
        for (int i = 0; i < 4; i++) {
            ESVOOctreeNode child = new ESVOOctreeNode();
            child.childMask = 0;
            child.contour = 0x1000 + i;
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
        ESVOOctreeNode loadedRoot = loaded.getNode(0);
        assertEquals((byte)0b11110000, loadedRoot.childMask);
        assertEquals(0x12345678, loadedRoot.contour);
        
        // Check children
        for (int i = 0; i < 4; i++) {
            ESVOOctreeNode loadedChild = loaded.getNode(i + 1);
            assertEquals(0, loadedChild.childMask);
            assertEquals(0x1000 + i, loadedChild.contour);
        }
    }
    
    @Test
    void testCompressedSerialization() throws IOException {
        // Create larger octree for compression testing
        ESVOOctreeData octree = new ESVOOctreeData(1024);
        
        // Fill with pattern data that compresses well
        for (int i = 0; i < 100; i++) {
            ESVOOctreeNode node = new ESVOOctreeNode();
            node.childMask = (byte)(i % 256);
            node.contour = i * 1000;
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
            ESVOOctreeNode node = loaded.getNode(i);
            assertEquals((byte)(i % 256), node.childMask);
            assertEquals(i * 1000, node.contour);
        }
    }
    
    @Test
    void testMemoryMappedFile() throws IOException {
        // Create octree
        ESVOOctreeData octree = new ESVOOctreeData(2048);
        
        // Add nodes with specific patterns
        for (int i = 0; i < 50; i++) {
            ESVOOctreeNode node = new ESVOOctreeNode();
            node.childMask = (byte)(0xFF - i);
            node.contour = 0xABCD0000 | i;
            node.farPointer = (i > 25) ? i * 100 : 0;
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
            ESVOOctreeNode node = loaded.getNode(i);
            assertEquals((byte)(0xFF - i), node.childMask);
            assertEquals(0xABCD0000 | i, node.contour);
            assertEquals((i > 25) ? i * 100 : 0, node.farPointer);
        }
        
        // Test random access capability
        ESVOOctreeNode randomNode = reader.readNode(mmapFile, 42);
        assertEquals((byte)(0xFF - 42), randomNode.childMask);
        assertEquals(0xABCD0000 | 42, randomNode.contour);
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
            List<ESVOOctreeNode> nodes = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                int nodeId = batch * batchSize + i;
                ESVOOctreeNode node = new ESVOOctreeNode();
                node.childMask = (byte)(nodeId & 0xFF);
                node.contour = nodeId * 7;
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
            ESVOOctreeNode node = streamReader.readNext();
            assertEquals((byte)(nodesRead & 0xFF), node.childMask);
            assertEquals(nodesRead * 7, node.contour);
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
        ESVOOctreeNode node = new ESVOOctreeNode();
        node.childMask = (byte)0x42;
        node.contour = (int)0xDEADBEEF;
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
        assertEquals((byte)0x42, v1Loaded.getNode(0).childMask);
        assertEquals((int)0xDEADBEEF, v1Loaded.getNode(0).contour);
        assertEquals((byte)0x42, v2Loaded.getNode(0).childMask);
        assertEquals((int)0xDEADBEEF, v2Loaded.getNode(0).contour);
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
            ESVOOctreeNode node = new ESVOOctreeNode();
            node.childMask = (byte)i;
            node.contour = i * 100;
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
            ESVOOctreeNode node = result.octree.getNode(i);
            assertEquals((byte)i, node.childMask);
            assertEquals(i * 100, node.contour);
        }
    }
}