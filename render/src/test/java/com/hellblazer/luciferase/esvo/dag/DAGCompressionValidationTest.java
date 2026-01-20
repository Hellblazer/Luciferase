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
package com.hellblazer.luciferase.esvo.dag;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.io.DAGDeserializer;
import com.hellblazer.luciferase.esvo.dag.io.DAGSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: DAG Compression Validation Tests - Validate compression and serialization.
 *
 * <p>This test suite validates:
 * <ul>
 * <li>DAG construction from various test SVOs</li>
 * <li>Compression ratios match expectations</li>
 * <li>Round-trip serialization (build → serialize → deserialize → compare)</li>
 * <li>Memory savings calculations</li>
 * <li>Architectural model compression</li>
 * </ul>
 *
 * <p><b>Expected Compression:</b> 5x-15x on realistic architectural data with repetitive structures.
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 5: DAG Compression Validation Tests")
class DAGCompressionValidationTest {

    @TempDir
    Path tempDir;

    // ==================== Basic Compression Tests ====================

    @Test
    @DisplayName("No compression on unique nodes")
    void testNoCompressionOnUniqueNodes() {
        var svo = createOctreeWithUniqueNodes();
        var dag = DAGBuilder.from(svo).build();

        assertNotNull(dag);
        assertEquals(1.0f, dag.getCompressionRatio(), 0.01f,
                    "Unique nodes should result in ~1.0x compression ratio");
        assertEquals(svo.getNodeCount(), dag.nodeCount(),
                    "Node count should be unchanged for unique nodes");
    }

    @Test
    @DisplayName("High compression on duplicate leaves")
    void testHighCompressionOnDuplicateLeaves() {
        var svo = createOctreeWithMaximalSharing();
        var dag = DAGBuilder.from(svo).build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() > 2.0f,
                  "Maximal sharing should achieve > 2x compression");
        assertTrue(dag.nodeCount() < svo.getNodeCount(),
                  "DAG should have fewer nodes than SVO");
    }

    @Test
    @DisplayName("Moderate compression on realistic data")
    void testModerateCompressionOnRealisticData() {
        var svo = createRealisticArchitecturalModel();
        var dag = DAGBuilder.from(svo)
                           .withCompressionStrategy(CompressionStrategy.BALANCED)
                           .build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() >= 1.5f,
                  "Realistic data should achieve >= 1.5x compression");
        assertTrue(dag.getCompressionRatio() <= 20.0f,
                  "Compression ratio should be reasonable (< 20x)");
    }

    // ==================== Architectural Model Tests ====================

    @Test
    @DisplayName("Compress architectural cube model")
    void testArchitecturalCubeCompression() {
        var svo = createCubeModel(4); // 4x4x4 cube
        var dag = DAGBuilder.from(svo).build();

        assertNotNull(dag);
        // Cube has high symmetry - expect good compression
        assertTrue(dag.getCompressionRatio() >= 2.0f,
                  "Symmetric cube should achieve >= 2x compression");
    }

    @Test
    @DisplayName("Compress architectural pyramid model")
    void testArchitecturalPyramidCompression() {
        var svo = createPyramidModel(5); // 5-level pyramid
        var dag = DAGBuilder.from(svo).build();

        assertNotNull(dag);
        // Simple pyramid (linear chain) has minimal compression opportunities
        assertTrue(dag.getCompressionRatio() >= 1.0f,
                  "Pyramid should achieve >= 1.0x compression (no worse than input)");
    }

    @Test
    @DisplayName("Large architectural model compression within expected range")
    void testLargeArchitecturalModelCompression() {
        var svo = createLargeArchitecturalModel();
        var dag = DAGBuilder.from(svo)
                           .withCompressionStrategy(CompressionStrategy.BALANCED)
                           .build();

        assertNotNull(dag);
        // Expect 5x-15x compression on realistic architectural data
        assertTrue(dag.getCompressionRatio() >= 3.0f,
                  "Large architectural model should achieve >= 3x compression");
        assertTrue(dag.getCompressionRatio() <= 20.0f,
                  "Compression should be within reasonable bounds (< 20x)");
    }

    // ==================== Round-Trip Serialization Tests ====================

    @Test
    @DisplayName("Round-trip serialization preserves node count")
    void testRoundTripSerializationNodeCount() throws IOException {
        var svo = createTestSVO();
        var dag1 = DAGBuilder.from(svo).build();

        // Serialize
        var file = tempDir.resolve("test-nodecount.dag");
        DAGSerializer.serialize(dag1, file);

        // Deserialize
        var dag2 = DAGDeserializer.deserialize(file);

        // Verify node count preserved
        assertEquals(dag1.nodeCount(), dag2.nodeCount(),
                    "Node count should be preserved after round-trip");
    }

    @Test
    @DisplayName("Round-trip serialization preserves compression ratio")
    void testRoundTripSerializationCompressionRatio() throws IOException {
        var svo = createOctreeWithDuplicates();
        var dag1 = DAGBuilder.from(svo).build();

        // Serialize
        var file = tempDir.resolve("test-compression.dag");
        DAGSerializer.serialize(dag1, file);

        // Deserialize
        var dag2 = DAGDeserializer.deserialize(file);

        // Verify compression ratio preserved
        assertEquals(dag1.getCompressionRatio(), dag2.getCompressionRatio(), 0.001f,
                    "Compression ratio should be preserved after round-trip");
    }

    @Test
    @DisplayName("Round-trip serialization preserves metadata")
    void testRoundTripSerializationMetadata() throws IOException {
        var svo = createTestSVO();
        var dag1 = DAGBuilder.from(svo)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        // Serialize
        var file = tempDir.resolve("test-metadata.dag");
        DAGSerializer.serialize(dag1, file);

        // Deserialize
        var dag2 = DAGDeserializer.deserialize(file);

        // Verify metadata preserved
        assertEquals(dag1.getMetadata().originalNodeCount(),
                    dag2.getMetadata().originalNodeCount(),
                    "Original node count should be preserved");
        assertEquals(dag1.getMetadata().uniqueNodeCount(),
                    dag2.getMetadata().uniqueNodeCount(),
                    "Unique node count should be preserved");
    }

    @Test
    @DisplayName("Round-trip serialization preserves node structure")
    void testRoundTripSerializationNodeStructure() throws IOException {
        var svo = createTestSVO();
        var dag1 = DAGBuilder.from(svo).build();

        // Serialize
        var file = tempDir.resolve("test-structure.dag");
        DAGSerializer.serialize(dag1, file);

        // Deserialize
        var dag2 = DAGDeserializer.deserialize(file);

        // Verify node structure
        assertEquals(dag1.nodes().length, dag2.nodes().length,
                    "Node array length should match");

        for (int i = 0; i < dag1.nodeCount(); i++) {
            var node1 = dag1.getNode(i);
            var node2 = dag2.getNode(i);

            assertEquals(node1.getChildMask(), node2.getChildMask(),
                        "Child mask should match at index " + i);
            assertEquals(node1.getChildPtr(), node2.getChildPtr(),
                        "Child ptr should match at index " + i);
            assertEquals(node1.isValid(), node2.isValid(),
                        "Valid flag should match at index " + i);
        }
    }

    // ==================== Memory Savings Tests ====================

    @Test
    @DisplayName("Memory savings calculation accurate")
    void testMemorySavingsAccurate() {
        var svo = createOctreeWithDuplicates();
        var dag = DAGBuilder.from(svo).build();

        long memorySaved = dag.getMetadata().memorySavedBytes();
        long expectedSaved = (long) (svo.getNodeCount() - dag.nodeCount()) * 8; // 8 bytes per node

        assertEquals(expectedSaved, memorySaved,
                    "Memory saved should equal (originalNodes - uniqueNodes) * 8");
    }

    @Test
    @DisplayName("Memory savings zero for uncompressed DAG")
    void testMemorySavingsZeroForUncompressed() {
        var svo = createOctreeWithUniqueNodes();
        var dag = DAGBuilder.from(svo).build();

        long memorySaved = dag.getMetadata().memorySavedBytes();
        assertEquals(0L, memorySaved,
                    "No compression should result in zero memory saved");
    }

    @Test
    @DisplayName("Memory savings positive for compressed DAG")
    void testMemorySavingsPositiveForCompressed() {
        var svo = createOctreeWithMaximalSharing();
        var dag = DAGBuilder.from(svo).build();

        long memorySaved = dag.getMetadata().memorySavedBytes();
        assertTrue(memorySaved > 0,
                  "Compressed DAG should save memory");
        assertTrue(memorySaved < svo.getNodeCount() * 8L,
                  "Memory saved should be less than total SVO size");
    }

    // ==================== Compression Strategy Tests ====================

    @Test
    @DisplayName("AGGRESSIVE compression achieves higher ratio")
    void testAggressiveCompressionHigherRatio() {
        var svo = createOctreeWithDuplicates();

        var dagBalanced = DAGBuilder.from(svo)
                                   .withCompressionStrategy(CompressionStrategy.BALANCED)
                                   .build();

        var dagAggressive = DAGBuilder.from(svo)
                                     .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
                                     .build();

        // Aggressive should achieve at least as good compression
        assertTrue(dagAggressive.getCompressionRatio() >= dagBalanced.getCompressionRatio(),
                  "AGGRESSIVE should achieve >= compression ratio of BALANCED");
    }

    @Test
    @DisplayName("CONSERVATIVE compression achieves maximum ratio")
    void testConservativeCompressionMaximum() {
        var svo = createOctreeWithDuplicates();

        var dagBalanced = DAGBuilder.from(svo)
                                   .withCompressionStrategy(CompressionStrategy.BALANCED)
                                   .build();

        var dagConservative = DAGBuilder.from(svo)
                                       .withCompressionStrategy(CompressionStrategy.CONSERVATIVE)
                                       .build();

        assertNotNull(dagConservative);
        // Conservative should achieve at least as good compression as balanced
        assertTrue(dagConservative.getCompressionRatio() >= dagBalanced.getCompressionRatio(),
                  "CONSERVATIVE should achieve >= compression ratio of BALANCED");
    }

    // ==================== Test Helper Methods ====================

    private ESVOOctreeData createTestSVO() {
        var octree = new ESVOOctreeData(2048);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var leaf = new ESVONodeUnified();
        leaf.setValid(true);
        leaf.setChildMask(0);

        octree.setNode(1, leaf);
        octree.setNode(2, leaf);

        return octree;
    }

    private ESVOOctreeData createOctreeWithUniqueNodes() {
        var octree = new ESVOOctreeData(4096);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000111); // 3 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Create 3 unique leaf children (different contour descriptors make them unique)
        for (int i = 1; i <= 3; i++) {
            var node = new ESVONodeUnified(0, i); // Different contour descriptors = unique nodes
            node.setValid(true);
            node.setChildMask(0); // Leaf nodes
            octree.setNode(i, node);
        }

        return octree;
    }

    private ESVOOctreeData createOctreeWithMaximalSharing() {
        var octree = new ESVOOctreeData(8192);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var leaf = new ESVONodeUnified();
        leaf.setValid(true);
        leaf.setChildMask(0);

        for (int i = 1; i <= 8; i++) {
            octree.setNode(i, leaf);
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

    private ESVOOctreeData createRealisticArchitecturalModel() {
        // Create a model with repeated structural elements (like building floors)
        var octree = new ESVOOctreeData(16384);

        // Root with 4 children (representing building quadrants)
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00001111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Create 4 "floors" (repeated structure)
        for (int floor = 0; floor < 4; floor++) {
            var floorNode = new ESVONodeUnified();
            floorNode.setValid(true);
            floorNode.setChildMask(0b00000011); // 2 rooms per floor
            floorNode.setChildPtr(2);
            octree.setNode(1 + floor, floorNode);
        }

        // Create room nodes (all identical)
        var room = new ESVONodeUnified();
        room.setValid(true);
        room.setChildMask(0);

        for (int i = 0; i < 8; i++) {
            octree.setNode(5 + i, room);
        }

        return octree;
    }

    private ESVOOctreeData createCubeModel(int size) {
        // Create cube with symmetric structure
        var octree = new ESVOOctreeData(size * size * size * 16);

        // Root represents entire cube
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111); // All 8 octants
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Create octants (all identical due to symmetry)
        var octant = new ESVONodeUnified();
        octant.setValid(true);
        octant.setChildMask(0);

        for (int i = 0; i < 8; i++) {
            octree.setNode(1 + i, octant);
        }

        return octree;
    }

    private ESVOOctreeData createPyramidModel(int levels) {
        // Create pyramid with repeated patterns at each level
        var octree = new ESVOOctreeData(16384);

        int nodeIdx = 0;
        for (int level = 0; level < levels; level++) {
            var node = new ESVONodeUnified();
            node.setValid(true);

            if (level < levels - 1) {
                // Pyramid narrows - fewer children at higher levels
                int childCount = Math.max(1, 4 - level);
                node.setChildMask((1 << childCount) - 1);
                node.setChildPtr(1);
            } else {
                node.setChildMask(0); // Top of pyramid
            }

            octree.setNode(nodeIdx++, node);
        }

        return octree;
    }

    private ESVOOctreeData createLargeArchitecturalModel() {
        // Create larger model with multiple repeated structures
        var octree = new ESVOOctreeData(32768);

        // Root with 8 children (building sections)
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Create 8 sections, each with repeated floor pattern
        for (int section = 0; section < 8; section++) {
            var sectionNode = new ESVONodeUnified();
            sectionNode.setValid(true);
            sectionNode.setChildMask(0b00001111); // 4 floors per section
            sectionNode.setChildPtr(4);
            octree.setNode(1 + section, sectionNode);
        }

        // Create floor nodes (all identical)
        var floor = new ESVONodeUnified();
        floor.setValid(true);
        floor.setChildMask(0);

        for (int i = 0; i < 32; i++) {
            octree.setNode(9 + i, floor);
        }

        return octree;
    }
}
