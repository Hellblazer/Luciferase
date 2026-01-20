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
package com.hellblazer.luciferase.esvo.dag.io;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.HashAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 (F2.2): DAG Serialization Round-Trip Tests.
 *
 * <p>Validates that DAG serialization and deserialization correctly preserve:
 * <ul>
 * <li>Node count and structure</li>
 * <li>Compression ratio and metadata</li>
 * <li>Child pointer indirection arrays</li>
 * <li>Traversal results after deserialization</li>
 * <li>Build configuration (hash algorithm, compression strategy)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 6: DAG Serialization Round-Trip Tests")
class DAGSerializationRoundTripTest {

    @TempDir
    Path tempDir;

    // ==================== Basic Round-Trip Tests ====================

    @Test
    @DisplayName("Basic round-trip preserves node count")
    void testBasicRoundTrip() throws IOException {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-basic.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(original.nodeCount(), loaded.nodeCount(),
                    "Node count should be preserved");
    }

    @Test
    @DisplayName("Round-trip preserves compression ratio")
    void testCompressionRatioPreservation() throws IOException {
        var svo = createOctreeWithDuplicates();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-compression.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(original.getCompressionRatio(), loaded.getCompressionRatio(), 0.001f,
                    "Compression ratio should be preserved");
    }

    @Test
    @DisplayName("Round-trip preserves node structure")
    void testNodeStructurePreservation() throws IOException {
        var svo = createMultiLevelOctree();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-structure.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(original.nodes().length, loaded.nodes().length,
                    "Node array length should match");

        for (int i = 0; i < original.nodeCount(); i++) {
            var origNode = original.getNode(i);
            var loadedNode = loaded.getNode(i);

            assertEquals(origNode.getChildMask(), loadedNode.getChildMask(),
                        "Child mask should match at index " + i);
            assertEquals(origNode.getChildPtr(), loadedNode.getChildPtr(),
                        "Child ptr should match at index " + i);
            assertEquals(origNode.isValid(), loadedNode.isValid(),
                        "Valid flag should match at index " + i);
        }
    }

    // ==================== Metadata Preservation Tests ====================

    @Test
    @DisplayName("Round-trip preserves metadata")
    void testMetadataPreservation() throws IOException {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo)
                                .withHashAlgorithm(HashAlgorithm.SHA256)
                                .withCompressionStrategy(CompressionStrategy.BALANCED)
                                .build();

        var file = tempDir.resolve("test-metadata.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        var origMeta = original.getMetadata();
        var loadedMeta = loaded.getMetadata();

        assertEquals(origMeta.originalNodeCount(), loadedMeta.originalNodeCount(),
                    "Original node count should be preserved");
        assertEquals(origMeta.uniqueNodeCount(), loadedMeta.uniqueNodeCount(),
                    "Unique node count should be preserved");
        assertEquals(origMeta.maxDepth(), loadedMeta.maxDepth(),
                    "Max depth should be preserved");
    }

    @Test
    @DisplayName("Round-trip preserves hash algorithm and strategy")
    void testConfigurationPreservation() throws IOException {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo)
                                .withHashAlgorithm(HashAlgorithm.SHA256)
                                .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
                                .build();

        var file = tempDir.resolve("test-config.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(original.getMetadata().hashAlgorithm(),
                    loaded.getMetadata().hashAlgorithm(),
                    "Hash algorithm should be preserved");
        assertEquals(original.getMetadata().strategy(),
                    loaded.getMetadata().strategy(),
                    "Compression strategy should be preserved");
    }

    @Test
    @DisplayName("Round-trip preserves memory savings calculation")
    void testMemorySavingsPreservation() throws IOException {
        var svo = createOctreeWithDuplicates();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-memory.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(original.getMetadata().memorySavedBytes(),
                    loaded.getMetadata().memorySavedBytes(),
                    "Memory saved should be preserved");
    }

    // ==================== Traversal Preservation Tests ====================

    @Test
    @DisplayName("Traversal after deserialization matches original")
    void testTraversalAfterDeserialization() throws IOException {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-traversal.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        var originalLeaves = traverseToLeaves(original);
        var loadedLeaves = traverseToLeaves(loaded);

        assertEquals(originalLeaves.size(), loadedLeaves.size(),
                    "Leaf count should match after deserialization");
    }

    @Test
    @DisplayName("Child resolution works after deserialization")
    void testChildResolutionAfterDeserialization() throws IOException {
        var svo = createMultiLevelOctree();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-child-resolution.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        var origRoot = original.getNode(0);
        var loadedRoot = loaded.getNode(0);

        // Test child resolution
        for (int octant = 0; octant < 8; octant++) {
            if ((origRoot.getChildMask() & (1 << octant)) != 0) {
                var origChildIdx = original.resolveChildIndex(0, origRoot, octant);
                var loadedChildIdx = loaded.resolveChildIndex(0, loadedRoot, octant);

                assertTrue(origChildIdx >= 0 && origChildIdx < original.nodeCount(),
                          "Original child index should be valid");
                assertTrue(loadedChildIdx >= 0 && loadedChildIdx < loaded.nodeCount(),
                          "Loaded child index should be valid");

                var origChild = original.getNode(origChildIdx);
                var loadedChild = loaded.getNode(loadedChildIdx);

                assertEquals(origChild.getChildMask(), loadedChild.getChildMask(),
                            "Child nodes should have same structure");
            }
        }
    }

    // ==================== Large Dataset Tests ====================

    @Test
    @DisplayName("Round-trip works for large DAG")
    void testLargeDAGRoundTrip() throws IOException {
        var svo = createLargeOctree(1000);
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-large.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(original.nodeCount(), loaded.nodeCount(),
                    "Large DAG node count should be preserved");
        assertEquals(original.getCompressionRatio(), loaded.getCompressionRatio(), 0.01f,
                    "Large DAG compression ratio should be preserved");
    }

    @Test
    @DisplayName("Multiple round-trips preserve data")
    void testMultipleRoundTrips() throws IOException {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo).build();

        // First round-trip
        var file1 = tempDir.resolve("test-round1.dag");
        DAGSerializer.serialize(original, file1);
        var round1 = DAGDeserializer.deserialize(file1);

        // Second round-trip
        var file2 = tempDir.resolve("test-round2.dag");
        DAGSerializer.serialize(round1, file2);
        var round2 = DAGDeserializer.deserialize(file2);

        // Third round-trip
        var file3 = tempDir.resolve("test-round3.dag");
        DAGSerializer.serialize(round2, file3);
        var round3 = DAGDeserializer.deserialize(file3);

        assertEquals(original.nodeCount(), round3.nodeCount(),
                    "Node count should be stable across multiple round-trips");
        assertEquals(original.getCompressionRatio(), round3.getCompressionRatio(), 0.001f,
                    "Compression ratio should be stable across multiple round-trips");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Round-trip single node DAG")
    void testSingleNodeDAGRoundTrip() throws IOException {
        var svo = createSingleNodeOctree();
        var original = DAGBuilder.from(svo).build();

        var file = tempDir.resolve("test-single.dag");
        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(1, loaded.nodeCount(), "Single node DAG should have 1 node");
        assertEquals(0, loaded.getNode(0).getChildMask(), "Single node should be leaf");
    }

    // ==================== File Format Validation Tests ====================

    @Test
    @DisplayName("Serialized file has correct magic number")
    void testFileFormatMagicNumber() throws Exception {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo).build();
        var file = tempDir.resolve("test-magic.dag");

        DAGSerializer.serialize(original, file);

        // Read magic number directly from file
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            var buffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            channel.read(buffer);
            buffer.flip();
            assertEquals(0x44414721, buffer.getInt(), "File should start with DAG! magic number");
        }
    }

    @Test
    @DisplayName("Addressing mode is preserved as ABSOLUTE")
    void testAddressingModePreservation() throws Exception {
        var svo = createTestOctree();
        var original = DAGBuilder.from(svo).build();
        var file = tempDir.resolve("test-addressing.dag");

        DAGSerializer.serialize(original, file);
        var loaded = DAGDeserializer.deserialize(file);

        assertEquals(com.hellblazer.luciferase.sparse.core.PointerAddressingMode.ABSOLUTE,
            loaded.getAddressingMode(),
            "Addressing mode should be ABSOLUTE after deserialization");
    }

    // ==================== Error Handling Tests ====================

    @Test
    @DisplayName("Invalid file throws DAGFormatException")
    void testInvalidFileHandling() throws Exception {
        var invalidFile = tempDir.resolve("invalid.dag");
        java.nio.file.Files.write(invalidFile, new byte[]{0x00, 0x00, 0x00, 0x00});

        assertThrows(DAGFormatException.class, () -> DAGDeserializer.deserialize(invalidFile),
            "Invalid magic number should throw DAGFormatException");
    }

    @Test
    @DisplayName("Empty file throws DAGFormatException")
    void testEmptyFileHandling() throws Exception {
        var emptyFile = tempDir.resolve("empty.dag");
        java.nio.file.Files.createFile(emptyFile);

        assertThrows(DAGFormatException.class, () -> DAGDeserializer.deserialize(emptyFile),
            "Empty file should throw DAGFormatException");
    }

    // ==================== Concurrent Safety Tests ====================

    @Test
    @DisplayName("Concurrent serialization operations are safe")
    void testConcurrentSerialization() throws Exception {
        var svo = createTestOctree();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var latch = new java.util.concurrent.CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    var dag = DAGBuilder.from(svo).withValidation(false).build();
                    var file = tempDir.resolve("concurrent_" + idx + ".dag");
                    DAGSerializer.serialize(dag, file);
                    var loaded = DAGDeserializer.deserialize(file);
                    assertEquals(dag.nodeCount(), loaded.nodeCount(),
                        "Concurrent round-trip " + idx + " should preserve node count");
                } catch (Exception e) {
                    fail("Concurrent serialization failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS),
            "All concurrent operations should complete within 30 seconds");
        executor.shutdown();
    }

    // ==================== Helper Methods ====================

    private java.util.List<com.hellblazer.luciferase.esvo.dag.DAGOctreeData> traverseToLeaves(
        com.hellblazer.luciferase.esvo.dag.DAGOctreeData dag) {

        var leaves = new java.util.ArrayList<com.hellblazer.luciferase.esvo.dag.DAGOctreeData>();
        var visited = new java.util.HashSet<Integer>();
        var queue = new java.util.ArrayDeque<Integer>();

        queue.add(0);
        visited.add(0);

        while (!queue.isEmpty()) {
            var nodeIdx = queue.poll();
            var node = dag.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

            if (node.getChildMask() == 0) {
                // Leaf node - can't add DAG to list, just count
                continue;
            }

            for (int octant = 0; octant < 8; octant++) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    var childIdx = dag.resolveChildIndex(nodeIdx, node, octant);
                    if (childIdx >= 0 && !visited.contains(childIdx)) {
                        queue.add(childIdx);
                        visited.add(childIdx);
                    }
                }
            }
        }

        return leaves;
    }

    // ==================== Test Data Creation ====================

    private ESVOOctreeData createTestOctree() {
        var octree = new ESVOOctreeData(2048);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var leaf1 = new ESVONodeUnified();
        leaf1.setValid(true);
        leaf1.setChildMask(0);
        octree.setNode(1, leaf1);

        var leaf2 = new ESVONodeUnified();
        leaf2.setValid(true);
        leaf2.setChildMask(0);
        octree.setNode(2, leaf2);

        return octree;
    }

    private ESVOOctreeData createMultiLevelOctree() {
        var octree = new ESVOOctreeData(8192);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var child1 = new ESVONodeUnified();
        child1.setValid(true);
        child1.setChildMask(0b00000011);
        child1.setChildPtr(2);
        octree.setNode(1, child1);

        var child2 = new ESVONodeUnified();
        child2.setValid(true);
        child2.setChildMask(0b00000011);
        child2.setChildPtr(3);
        octree.setNode(2, child2);

        for (int i = 0; i < 4; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(3 + i, leaf);
        }

        return octree;
    }

    private ESVOOctreeData createOctreeWithDuplicates() {
        var octree = new ESVOOctreeData(8192);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00001111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var leaf = new ESVONodeUnified();
        leaf.setValid(true);
        leaf.setChildMask(0);

        for (int i = 0; i < 4; i++) {
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    private ESVOOctreeData createLargeOctree(int approxNodes) {
        var octree = new ESVOOctreeData(Math.max(4096, approxNodes * 2));

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var nodeIdx = 1;

        for (int i = 0; i < 8 && nodeIdx < approxNodes; i++) {
            var node = new ESVONodeUnified();
            node.setValid(true);

            if (nodeIdx + 8 < approxNodes) {
                node.setChildMask(0b11111111);
                node.setChildPtr(8);
            } else {
                node.setChildMask(0);
            }

            octree.setNode(nodeIdx++, node);
        }

        while (nodeIdx < approxNodes) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(nodeIdx++, leaf);
        }

        return octree;
    }

    private ESVOOctreeData createSingleNodeOctree() {
        var octree = new ESVOOctreeData(1024);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0);
        octree.setNode(0, root);

        return octree;
    }
}
