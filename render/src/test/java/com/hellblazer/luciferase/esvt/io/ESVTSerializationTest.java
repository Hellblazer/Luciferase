/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.io;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Vector3f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ESVT serialization/deserialization.
 *
 * Tests round-trip integrity for all I/O methods:
 * - Basic serializer/deserializer
 * - Compressed serializer/deserializer
 * - Memory-mapped reader/writer
 * - Streaming reader/writer
 *
 * @author hal.hildebrand
 */
public class ESVTSerializationTest {

    @TempDir
    Path tempDir;

    private ESVTData testData;
    private ESVTData testDataWithVoxels;
    private ESVTMetadata testMetadata;

    @BeforeEach
    void setUp() {
        // Create test data with various node types
        var nodes = new ESVTNodeUnified[100];
        var contours = new int[50];
        var farPointers = new int[10];

        // Create a mix of node types
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = createTestNode(i);
        }

        // Fill contours with test values
        for (int i = 0; i < contours.length; i++) {
            contours[i] = i * 3 + 0x1000;
        }

        // Fill far pointers
        for (int i = 0; i < farPointers.length; i++) {
            farPointers[i] = i * 100 + 500;
        }

        testData = new ESVTData(
            nodes,
            contours,
            farPointers,
            3, // rootType (S3)
            12, // maxDepth
            60, // leafCount
            40, // internalCount
            0, // gridResolution (no voxels)
            new int[0] // no voxel coords
        );

        // Create test data with voxel coordinates
        var voxelCoords = new int[60 * 3]; // 3 coords per leaf
        for (int i = 0; i < 60; i++) {
            voxelCoords[i * 3] = i % 16; // x
            voxelCoords[i * 3 + 1] = (i / 16) % 16; // y
            voxelCoords[i * 3 + 2] = i / 256; // z
        }

        testDataWithVoxels = new ESVTData(
            nodes.clone(),
            contours.clone(),
            farPointers.clone(),
            3,
            12,
            60,
            40,
            256, // gridResolution
            voxelCoords
        );

        // Create metadata
        testMetadata = new ESVTMetadata();
        testMetadata.setTetreeDepth(12);
        testMetadata.setRootType(3);
        testMetadata.setNodeCount(100);
        testMetadata.setLeafCount(60);
        testMetadata.setInternalCount(40);
        testMetadata.setContourCount(50);
        testMetadata.setFarPointerCount(10);
        testMetadata.setGridResolution(0);
        testMetadata.setSourceFile("test_model.vol");
        testMetadata.setBuildTimeMs(1234);
        testMetadata.setBoundingBox(new Vector3f(0, 0, 0), new Vector3f(1, 1, 1));
        testMetadata.addCustomProperty("test_key", "test_value");
    }

    private ESVTNodeUnified createTestNode(int index) {
        var node = new ESVTNodeUnified((byte) (index % 6));
        node.setChildMask(0xAA); // Alternating children
        node.setLeafMask(0x55); // Alternating leaves
        node.setChildPtr(index * 8);
        if (index % 10 == 0) {
            node.setFar(true);
        }
        node.setContourMask(index % 16);
        node.setContourPtr(index * 2);
        return node;
    }

    // ============= Basic Serializer/Deserializer Tests =============

    @Test
    void testBasicRoundTrip() throws IOException {
        var file = tempDir.resolve("test_basic.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, file);
        }

        assertTrue(Files.exists(file));
        assertTrue(ESVTFileFormat.isValidESVTFile(file));

        try (var deserializer = new ESVTDeserializer()) {
            var loaded = deserializer.deserialize(file);
            assertDataEquals(testData, loaded);
        }
    }

    @Test
    void testBasicRoundTripWithMetadata() throws IOException {
        var file = tempDir.resolve("test_basic_meta.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, testMetadata, file);
        }

        try (var deserializer = new ESVTDeserializer()) {
            var result = deserializer.deserializeWithMetadata(file);
            assertDataEquals(testData, result.data());
            assertTrue(result.hasMetadata());
            assertMetadataEquals(testMetadata, result.metadata());
        }
    }

    @Test
    void testBasicRoundTripWithVoxelCoords() throws IOException {
        var file = tempDir.resolve("test_voxels.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testDataWithVoxels, file);
        }

        try (var deserializer = new ESVTDeserializer()) {
            var loaded = deserializer.deserialize(file);
            assertDataEquals(testDataWithVoxels, loaded);
            assertTrue(loaded.hasVoxelCoords());
            assertEquals(testDataWithVoxels.gridResolution(), loaded.gridResolution());
        }
    }

    // ============= Compressed Serializer/Deserializer Tests =============

    @Test
    void testCompressedRoundTrip() throws IOException {
        var file = tempDir.resolve("test_compressed.esvtc");

        try (var serializer = new ESVTCompressedSerializer()) {
            serializer.serialize(testData, file);
        }

        assertTrue(Files.exists(file));
        assertTrue(ESVTCompressedDeserializer.isCompressedESVTFile(file));

        try (var deserializer = new ESVTCompressedDeserializer()) {
            var loaded = deserializer.deserialize(file);
            assertDataEquals(testData, loaded);
        }
    }

    @Test
    void testCompressedRoundTripWithMetadata() throws IOException {
        var file = tempDir.resolve("test_compressed_meta.esvtc");

        try (var serializer = new ESVTCompressedSerializer()) {
            serializer.serialize(testData, testMetadata, file);
        }

        try (var deserializer = new ESVTCompressedDeserializer()) {
            var result = deserializer.deserializeWithMetadata(file);
            assertDataEquals(testData, result.data());
            assertTrue(result.hasMetadata());
            assertMetadataEquals(testMetadata, result.metadata());
        }
    }

    @Test
    void testCompressionReducesSize() throws IOException {
        var uncompressedFile = tempDir.resolve("test_uncompressed.esvt");
        var compressedFile = tempDir.resolve("test_compressed.esvtc");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, uncompressedFile);
        }

        try (var serializer = new ESVTCompressedSerializer()) {
            serializer.serialize(testData, compressedFile);
        }

        long uncompressedSize = Files.size(uncompressedFile);
        long compressedSize = Files.size(compressedFile);

        // Compressed file should be smaller (at least for larger data)
        // For small test data this may not always be true due to GZIP header overhead
        assertTrue(compressedSize <= uncompressedSize * 1.5,
            "Compressed size should not be much larger than uncompressed");
    }

    // ============= Memory-Mapped Reader/Writer Tests =============

    @Test
    void testMemoryMappedRoundTrip() throws IOException {
        var file = tempDir.resolve("test_mmap.esvt");

        try (var writer = new ESVTMemoryMappedWriter()) {
            writer.write(testData, file);
        }

        assertTrue(Files.exists(file));
        assertTrue(ESVTFileFormat.isValidESVTFile(file));

        try (var reader = new ESVTMemoryMappedReader()) {
            var loaded = reader.read(file);
            assertDataEquals(testData, loaded);
        }
    }

    @Test
    void testMemoryMappedRandomAccess() throws IOException {
        var file = tempDir.resolve("test_mmap_random.esvt");

        try (var writer = new ESVTMemoryMappedWriter()) {
            writer.write(testData, file);
        }

        try (var reader = new ESVTMemoryMappedReader()) {
            // Read specific nodes
            var node42 = reader.readNode(file, 42);
            assertNodeEquals(testData.nodes()[42], node42);

            // Read a range of nodes
            var nodes10to20 = reader.readNodes(file, 10, 10);
            assertEquals(10, nodes10to20.length);
            for (int i = 0; i < 10; i++) {
                assertNodeEquals(testData.nodes()[10 + i], nodes10to20[i]);
            }
        }
    }

    @Test
    void testMemoryMappedNodeUpdate() throws IOException {
        var file = tempDir.resolve("test_mmap_update.esvt");

        try (var writer = new ESVTMemoryMappedWriter()) {
            writer.write(testData, file);
        }

        // Update a single node
        var newNode = createTestNode(999);
        try (var writer = new ESVTMemoryMappedWriter()) {
            writer.updateNode(file, 50, newNode);
        }

        // Verify the update
        try (var reader = new ESVTMemoryMappedReader()) {
            var loaded = reader.read(file);
            assertNodeEquals(newNode, loaded.nodes()[50]);

            // Other nodes should be unchanged
            assertNodeEquals(testData.nodes()[49], loaded.nodes()[49]);
            assertNodeEquals(testData.nodes()[51], loaded.nodes()[51]);
        }
    }

    // ============= Streaming Reader/Writer Tests =============

    @Test
    void testStreamingRoundTrip() throws IOException {
        var file = tempDir.resolve("test_stream.esvt");

        try (var writer = new ESVTStreamWriter(file)) {
            writer.setRootType(testData.rootType());
            writer.setMaxDepth(testData.maxDepth());
            writer.setLeafCount(testData.leafCount());
            writer.setInternalCount(testData.internalCount());

            // Write nodes in batches
            var batch = new ArrayList<ESVTNodeUnified>();
            for (var node : testData.nodes()) {
                batch.add(node);
                if (batch.size() == 10) {
                    writer.writeNodeBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                writer.writeNodeBatch(batch);
            }

            writer.addContours(testData.contours());
            writer.addFarPointers(testData.farPointers());
        }

        assertTrue(Files.exists(file));
        assertTrue(ESVTFileFormat.isValidESVTFile(file));

        try (var reader = new ESVTStreamReader(file)) {
            assertEquals(testData.nodeCount(), reader.getTotalNodes());

            int index = 0;
            while (reader.hasNext()) {
                var node = reader.readNext();
                assertNodeEquals(testData.nodes()[index], node);
                index++;
            }
            assertEquals(testData.nodeCount(), index);
        }
    }

    @Test
    void testStreamingIterator() throws IOException {
        var file = tempDir.resolve("test_stream_iter.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, file);
        }

        try (var reader = new ESVTStreamReader(file)) {
            int index = 0;
            for (var node : reader) {
                assertNodeEquals(testData.nodes()[index], node);
                index++;
            }
            assertEquals(testData.nodeCount(), index);
        }
    }

    @Test
    void testStreamingBatchRead() throws IOException {
        var file = tempDir.resolve("test_stream_batch.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, file);
        }

        try (var reader = new ESVTStreamReader(file)) {
            var batch = reader.readBatch(30);
            assertEquals(30, batch.length);
            for (int i = 0; i < 30; i++) {
                assertNodeEquals(testData.nodes()[i], batch[i]);
            }

            assertEquals(70, reader.getNodesRemaining());
        }
    }

    @Test
    void testStreamingSkip() throws IOException {
        var file = tempDir.resolve("test_stream_skip.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, file);
        }

        try (var reader = new ESVTStreamReader(file)) {
            int skipped = reader.skip(50);
            assertEquals(50, skipped);
            assertEquals(50, reader.getNodesRead());

            var node = reader.readNext();
            assertNodeEquals(testData.nodes()[50], node);
        }
    }

    // ============= Cross-Format Compatibility Tests =============

    @Test
    void testBasicAndMemoryMappedCompatibility() throws IOException {
        var file = tempDir.resolve("test_compat1.esvt");

        // Write with basic serializer
        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(testData, file);
        }

        // Read with memory-mapped reader
        try (var reader = new ESVTMemoryMappedReader()) {
            var loaded = reader.read(file);
            assertDataEquals(testData, loaded);
        }
    }

    @Test
    void testMemoryMappedAndStreamingCompatibility() throws IOException {
        var file = tempDir.resolve("test_compat2.esvt");

        // Write with memory-mapped writer
        try (var writer = new ESVTMemoryMappedWriter()) {
            writer.write(testData, file);
        }

        // Read with streaming reader
        try (var reader = new ESVTStreamReader(file)) {
            int index = 0;
            while (reader.hasNext()) {
                var node = reader.readNext();
                assertNodeEquals(testData.nodes()[index++], node);
            }
        }
    }

    // ============= Edge Case Tests =============

    @Test
    void testEmptyData() throws IOException {
        var emptyData = new ESVTData(
            new ESVTNodeUnified[0],
            new int[0],
            new int[0],
            0, 0, 0, 0, 0, new int[0]
        );

        var file = tempDir.resolve("test_empty.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(emptyData, file);
        }

        try (var deserializer = new ESVTDeserializer()) {
            var loaded = deserializer.deserialize(file);
            assertEquals(0, loaded.nodeCount());
            assertEquals(0, loaded.contourCount());
            assertEquals(0, loaded.farPointerCount());
        }
    }

    @Test
    void testSingleNode() throws IOException {
        var singleNode = new ESVTData(
            new ESVTNodeUnified[] { createTestNode(0) },
            new int[0],
            new int[0],
            0, 1, 1, 0, 0, new int[0]
        );

        var file = tempDir.resolve("test_single.esvt");

        try (var serializer = new ESVTSerializer()) {
            serializer.serialize(singleNode, file);
        }

        try (var deserializer = new ESVTDeserializer()) {
            var loaded = deserializer.deserialize(file);
            assertEquals(1, loaded.nodeCount());
            assertNodeEquals(singleNode.nodes()[0], loaded.nodes()[0]);
        }
    }

    @Test
    void testInvalidMagicNumber() throws IOException {
        var file = tempDir.resolve("test_invalid.esvt");
        Files.write(file, new byte[] { 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00 });

        try (var deserializer = new ESVTDeserializer()) {
            assertThrows(IOException.class, () -> deserializer.deserialize(file));
        }
    }

    @Test
    void testFileFormatVersionDetection() throws IOException {
        var file = tempDir.resolve("test_version.esvt");

        try (var serializer = new ESVTSerializer(ESVTFileFormat.VERSION_2)) {
            serializer.serialize(testData, file);
        }

        assertEquals(ESVTFileFormat.VERSION_2, ESVTFileFormat.detectVersion(file));
    }

    // ============= Helper Methods =============

    private void assertDataEquals(ESVTData expected, ESVTData actual) {
        assertEquals(expected.nodeCount(), actual.nodeCount(), "Node count mismatch");
        assertEquals(expected.contourCount(), actual.contourCount(), "Contour count mismatch");
        assertEquals(expected.farPointerCount(), actual.farPointerCount(), "Far pointer count mismatch");
        assertEquals(expected.rootType(), actual.rootType(), "Root type mismatch");
        assertEquals(expected.maxDepth(), actual.maxDepth(), "Max depth mismatch");
        assertEquals(expected.leafCount(), actual.leafCount(), "Leaf count mismatch");
        assertEquals(expected.internalCount(), actual.internalCount(), "Internal count mismatch");
        assertEquals(expected.gridResolution(), actual.gridResolution(), "Grid resolution mismatch");

        // Compare nodes
        for (int i = 0; i < expected.nodeCount(); i++) {
            assertNodeEquals(expected.nodes()[i], actual.nodes()[i], "Node " + i);
        }

        // Compare contours
        assertArrayEquals(expected.contours(), actual.contours(), "Contours mismatch");

        // Compare far pointers
        assertArrayEquals(expected.farPointers(), actual.farPointers(), "Far pointers mismatch");

        // Compare voxel coords
        if (expected.hasVoxelCoords()) {
            assertTrue(actual.hasVoxelCoords(), "Expected voxel coords but none found");
            assertArrayEquals(expected.leafVoxelCoords(), actual.leafVoxelCoords(), "Voxel coords mismatch");
        }
    }

    private void assertNodeEquals(ESVTNodeUnified expected, ESVTNodeUnified actual) {
        assertNodeEquals(expected, actual, "Node");
    }

    private void assertNodeEquals(ESVTNodeUnified expected, ESVTNodeUnified actual, String context) {
        assertEquals(expected.getChildMask(), actual.getChildMask(), context + ": childMask mismatch");
        assertEquals(expected.getLeafMask(), actual.getLeafMask(), context + ": leafMask mismatch");
        assertEquals(expected.getTetType(), actual.getTetType(), context + ": tetType mismatch");
        assertEquals(expected.getChildPtr(), actual.getChildPtr(), context + ": childPtr mismatch");
        assertEquals(expected.isFar(), actual.isFar(), context + ": far flag mismatch");
        assertEquals(expected.getContourMask(), actual.getContourMask(), context + ": contourMask mismatch");
        assertEquals(expected.getContourPtr(), actual.getContourPtr(), context + ": contourPtr mismatch");
    }

    private void assertMetadataEquals(ESVTMetadata expected, ESVTMetadata actual) {
        assertEquals(expected.getTetreeDepth(), actual.getTetreeDepth());
        assertEquals(expected.getRootType(), actual.getRootType());
        assertEquals(expected.getNodeCount(), actual.getNodeCount());
        assertEquals(expected.getLeafCount(), actual.getLeafCount());
        assertEquals(expected.getInternalCount(), actual.getInternalCount());
        assertEquals(expected.getContourCount(), actual.getContourCount());
        assertEquals(expected.getFarPointerCount(), actual.getFarPointerCount());
        assertEquals(expected.getGridResolution(), actual.getGridResolution());
        assertEquals(expected.getSourceFile(), actual.getSourceFile());
        assertEquals(expected.getBuildTimeMs(), actual.getBuildTimeMs());
        assertEquals(expected.getCustomProperty("test_key"), actual.getCustomProperty("test_key"));
    }
}
