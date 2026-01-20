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
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for DAGBuilder.
 * Tests all aspects of DAG construction from SVO octrees.
 *
 * @author hal.hildebrand
 */
class DAGBuilderTest {

    // ==================== Invalid Input Tests ====================

    @Test
    void testFromNullSVO() {
        var exception = assertThrows(DAGBuildException.InvalidInputException.class,
                                     () -> DAGBuilder.from(null));
        assertTrue(exception.getMessage().contains("null"));
    }

    @Test
    void testFromEmptySVO() {
        var emptyOctree = new ESVOOctreeData(1024);
        var exception = assertThrows(DAGBuildException.InvalidInputException.class,
                                     () -> DAGBuilder.from(emptyOctree).build());
        assertTrue(exception.getMessage().contains("empty"));
    }

    // ==================== Single Node Tests ====================

    @Test
    void testSingleNodeNoCompression() {
        // Create SVO with just root node (no children)
        var octree = createSingleNodeOctree();

        var dag = DAGBuilder.from(octree)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        assertNotNull(dag);
        assertEquals(1, dag.nodes().length);
        assertEquals(1.0f, dag.getCompressionRatio(), 0.01f);
        assertEquals(PointerAddressingMode.ABSOLUTE, dag.getAddressingMode());
    }

    // ==================== Duplicate Leaf Tests ====================

    @Test
    void testDuplicateLeaves() {
        // Create SVO with multiple nodes that have identical leaf children
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() > 1.0f, "Should have compression with duplicate leaves");
        assertTrue(dag.nodes().length < octree.getNodeCount(), "DAG should have fewer nodes than SVO");
    }

    @Test
    void testDuplicateSubtrees() {
        // Create SVO with identical subtrees at different locations
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() > 1.5f, "Should have good compression with duplicate subtrees");

        // Verify metadata
        var metadata = dag.getMetadata();
        assertNotNull(metadata);
        assertTrue(metadata.sharedSubtreeCount() > 0, "Should detect shared subtrees");
    }

    // ==================== Hash Algorithm Tests ====================

    @Test
    void testHashAlgorithmSHA256() {
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        assertNotNull(dag);
        assertEquals(HashAlgorithm.SHA256, dag.getMetadata().hashAlgorithm());
    }

    @Test
    void testHashAlgorithmDefault() {
        // Should default to SHA256 if not specified
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        assertEquals(HashAlgorithm.SHA256, dag.getMetadata().hashAlgorithm());
    }

    // ==================== Compression Strategy Tests ====================

    @Test
    void testCompressionStrategyAggressive() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
                            .build();

        assertNotNull(dag);
        assertEquals(CompressionStrategy.AGGRESSIVE, dag.getMetadata().strategy());
        assertTrue(dag.getCompressionRatio() >= 1.0f);
    }

    @Test
    void testCompressionStrategyBalanced() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        assertNotNull(dag);
        assertEquals(CompressionStrategy.BALANCED, dag.getMetadata().strategy());
        assertTrue(dag.getCompressionRatio() >= 1.0f);
    }

    @Test
    void testCompressionStrategyConservative() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withCompressionStrategy(CompressionStrategy.CONSERVATIVE)
                            .build();

        assertNotNull(dag);
        assertEquals(CompressionStrategy.CONSERVATIVE, dag.getMetadata().strategy());
        assertTrue(dag.getCompressionRatio() >= 1.0f);
    }

    @Test
    void testCompressionStrategyComparison() {
        // Conservative should generally achieve better compression than aggressive
        var octree = createLargeOctreeWithDuplicates();

        var aggressive = DAGBuilder.from(octree)
                                    .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
                                    .build();

        // Need to rebuild from same source
        var octree2 = createLargeOctreeWithDuplicates();
        var conservative = DAGBuilder.from(octree2)
                                      .withCompressionStrategy(CompressionStrategy.CONSERVATIVE)
                                      .build();

        // Conservative should achieve >= compression ratio of aggressive
        assertTrue(conservative.getCompressionRatio() >= aggressive.getCompressionRatio(),
                   "Conservative should achieve at least as good compression as aggressive");
    }

    @Test
    void testCompressionStrategyDefault() {
        // Should default to BALANCED if not specified
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        assertEquals(CompressionStrategy.BALANCED, dag.getMetadata().strategy());
    }

    // ==================== Progress Callback Tests ====================

    @Test
    void testProgressCallbackAllPhases() {
        var octree = createOctreeWithDuplicateSubtrees();
        var progressReports = new ArrayList<BuildProgress>();

        var dag = DAGBuilder.from(octree)
                            .withProgressCallback(progressReports::add)
                            .build();

        assertNotNull(dag);
        assertFalse(progressReports.isEmpty(), "Should report progress");

        // Should see all phases
        var phases = progressReports.stream()
                                    .map(BuildProgress::phase)
                                    .distinct()
                                    .toList();

        assertTrue(phases.contains(BuildPhase.HASHING), "Should report HASHING phase");
        assertTrue(phases.contains(BuildPhase.DEDUPLICATION), "Should report DEDUPLICATION phase");
        assertTrue(phases.contains(BuildPhase.COMPACTION), "Should report COMPACTION phase");
        assertTrue(phases.contains(BuildPhase.COMPLETE), "Should report COMPLETE phase");
    }

    @Test
    void testProgressCallbackPercentages() {
        var octree = createOctreeWithDuplicateSubtrees();
        var progressReports = new ArrayList<BuildProgress>();

        DAGBuilder.from(octree)
                  .withProgressCallback(progressReports::add)
                  .build();

        // All percentages should be in valid range
        for (var progress : progressReports) {
            assertTrue(progress.percentComplete() >= 0, "Percentage should be >= 0");
            assertTrue(progress.percentComplete() <= 100, "Percentage should be <= 100");
        }

        // Final progress should be COMPLETE at 100%
        var lastProgress = progressReports.get(progressReports.size() - 1);
        assertEquals(BuildPhase.COMPLETE, lastProgress.phase());
        assertEquals(100, lastProgress.percentComplete());
    }

    @Test
    void testProgressCallbackMonotonicallyIncreasing() {
        var octree = createLargeOctreeWithDuplicates();
        var progressReports = new ArrayList<BuildProgress>();

        DAGBuilder.from(octree)
                  .withProgressCallback(progressReports::add)
                  .build();

        // Progress should generally increase (allowing for phase transitions)
        int maxPercent = -1;
        for (var progress : progressReports) {
            // Within same phase or moving to next phase, percentage should not decrease significantly
            if (progress.percentComplete() < maxPercent - 5) {
                fail("Progress percentage should not decrease significantly: " +
                     maxPercent + " -> " + progress.percentComplete());
            }
            maxPercent = Math.max(maxPercent, progress.percentComplete());
        }
    }

    @Test
    void testNoProgressCallbackWorks() {
        // Should work fine without progress callback
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() >= 1.0f);
    }

    // ==================== Validation Flag Tests ====================

    @Test
    void testValidationEnabled() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withValidation(true)
                            .build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() >= 1.0f);

        // Should have validation phase in metadata
        assertNotNull(dag.getMetadata().buildTime());
    }

    @Test
    void testValidationDisabled() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withValidation(false)
                            .build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() >= 1.0f);
    }

    @Test
    void testValidationDefault() {
        // Validation should be enabled by default
        var octree = createOctreeWithDuplicateLeaves();
        var progressReports = new ArrayList<BuildProgress>();

        DAGBuilder.from(octree)
                  .withProgressCallback(progressReports::add)
                  .build();

        // Should see VALIDATION phase
        var hasValidationPhase = progressReports.stream()
                                                .anyMatch(p -> p.phase() == BuildPhase.VALIDATION);
        assertTrue(hasValidationPhase, "Should include VALIDATION phase by default");
    }

    // ==================== Metadata Tests ====================

    @Test
    void testMetadataComplete() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.CONSERVATIVE)
                            .build();

        var metadata = dag.getMetadata();
        assertNotNull(metadata);

        // Check all metadata fields are populated
        assertTrue(metadata.uniqueNodeCount() > 0);
        assertTrue(metadata.originalNodeCount() > 0);
        assertTrue(metadata.maxDepth() >= 0);
        assertNotNull(metadata.sharingByDepth());
        assertNotNull(metadata.buildTime());
        assertEquals(HashAlgorithm.SHA256, metadata.hashAlgorithm());
        assertEquals(CompressionStrategy.CONSERVATIVE, metadata.strategy());
        assertNotEquals(0L, metadata.sourceHash());
    }

    @Test
    void testMetadataCompressionRatio() {
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree).build();
        var metadata = dag.getMetadata();

        assertEquals(dag.getCompressionRatio(), metadata.compressionRatio(), 0.001f);
    }

    @Test
    void testMetadataMemorySaved() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree).build();
        var metadata = dag.getMetadata();

        long expectedSaved = (long) (metadata.originalNodeCount() - metadata.uniqueNodeCount()) * 8;
        assertEquals(expectedSaved, metadata.memorySavedBytes());
    }

    // ==================== Structural Correctness Tests ====================

    @Test
    void testDAGUsesAbsoluteAddressing() {
        var octree = createOctreeWithDuplicateLeaves();

        var dag = DAGBuilder.from(octree).build();

        assertEquals(PointerAddressingMode.ABSOLUTE, dag.getAddressingMode());
    }

    @Test
    void testDAGNodesAreValid() {
        var octree = createOctreeWithDuplicateSubtrees();

        var dag = DAGBuilder.from(octree).build();

        // All nodes should be valid
        for (var node : dag.nodes()) {
            if (node != null) {
                assertTrue(node.isValid(), "All DAG nodes should be valid");
            }
        }
    }

    @Test
    void testDAGPreservesRootNode() {
        var octree = createOctreeWithDuplicateSubtrees();
        var originalRoot = octree.getNode(0);

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag.nodes()[0], "Root node should be preserved");
        // Root structure should be equivalent (same child mask)
        assertEquals(originalRoot.getChildMask(), dag.nodes()[0].getChildMask());
    }

    // ==================== Large Dataset Tests ====================

    @Test
    void testLargeOctreeCompression() {
        var octree = createLargeOctreeWithDuplicates();

        var dag = DAGBuilder.from(octree)
                            .withCompressionStrategy(CompressionStrategy.BALANCED)
                            .build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() > 1.2f, "Should achieve reasonable compression on large dataset");
        assertTrue(dag.nodes().length < octree.getNodeCount(), "DAG should be smaller than SVO");
    }

    @Test
    void testVeryDeepOctree() {
        var octree = createDeepOctree(10); // 10 levels deep

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        assertTrue(dag.getMetadata().maxDepth() >= 9, "Should preserve depth information");
    }

    // ==================== Builder Pattern Tests ====================

    @Test
    void testBuilderMethodChaining() {
        var octree = createOctreeWithDuplicateLeaves();

        // Should support fluent method chaining
        var dag = DAGBuilder.from(octree)
                            .withHashAlgorithm(HashAlgorithm.SHA256)
                            .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
                            .withValidation(true)
                            .withProgressCallback(p -> {})
                            .build();

        assertNotNull(dag);
    }

    @Test
    void testBuilderCanBuildMultipleTimes() {
        var octree = createOctreeWithDuplicateLeaves();
        var builder = DAGBuilder.from(octree);

        var dag1 = builder.build();
        var dag2 = builder.build();

        // Both builds should succeed and produce equivalent results
        assertNotNull(dag1);
        assertNotNull(dag2);
        assertEquals(dag1.getCompressionRatio(), dag2.getCompressionRatio(), 0.001f);
    }

    // ==================== Edge Cases ====================

    @Test
    void testOctreeWithNoSharing() {
        // Create octree where every node is unique (worst case)
        var octree = createOctreeWithUniqueNodes();

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        // Compression ratio should be close to 1.0 (no compression possible)
        assertEquals(1.0f, dag.getCompressionRatio(), 0.01f);
    }

    @Test
    void testOctreeWithMaximumSharing() {
        // Create octree where all leaves are identical (best case)
        var octree = createOctreeWithMaximalSharing();

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        // Should achieve very high compression
        assertTrue(dag.getCompressionRatio() > 2.0f, "Should achieve high compression with maximal sharing");
    }

    @Test
    void testOctreeWithOnlyLeaves() {
        // Create octree with root and leaf children only (no intermediate levels)
        var octree = createOctreeWithOnlyLeaves();

        var dag = DAGBuilder.from(octree).build();

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() >= 1.0f);
    }

    // ==================== Helper Methods ====================

    /**
     * Create SVO with just a root node (no children).
     */
    private ESVOOctreeData createSingleNodeOctree() {
        var octree = new ESVOOctreeData(1024);
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0); // No children
        octree.setNode(0, root);
        return octree;
    }

    /**
     * Create SVO with duplicate leaf nodes.
     * Structure: root with 2 children, each child has 4 identical leaves.
     */
    private ESVOOctreeData createOctreeWithDuplicateLeaves() {
        var octree = new ESVOOctreeData(4096);

        // Root node at index 0
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children at octants 0 and 1
        root.setChildPtr(1); // Children start at index 1
        octree.setNode(0, root);

        // First child at index 1 (has 4 leaf children)
        var child1 = new ESVONodeUnified();
        child1.setValid(true);
        child1.setChildMask(0b00001111); // 4 children
        child1.setLeafMask(0b00001111); // All are leaves
        child1.setChildPtr(2); // Leaves start at index 3
        octree.setNode(1, child1);

        // Second child at index 2 (has 4 identical leaf children)
        var child2 = new ESVONodeUnified();
        child2.setValid(true);
        child2.setChildMask(0b00001111); // 4 children
        child2.setLeafMask(0b00001111); // All are leaves
        child2.setChildPtr(5); // Leaves start at index 7
        octree.setNode(2, child2);

        // Create identical leaf nodes for both children
        for (int i = 0; i < 4; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0); // Leaves have no children
            octree.setNode(3 + i, leaf); // First set of leaves
            octree.setNode(7 + i, leaf); // Second set of leaves (duplicates)
        }

        return octree;
    }

    /**
     * Create SVO with duplicate subtrees (not just leaves).
     * Structure: root with 2 children, each child has identical subtree structure.
     */
    private ESVOOctreeData createOctreeWithDuplicateSubtrees() {
        var octree = new ESVOOctreeData(8192);

        // Root node
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // First subtree (nodes 1-4)
        createSubtree(octree, 1, 2);

        // Second subtree (nodes 5-8) - identical to first
        createSubtree(octree, 5, 6);

        return octree;
    }

    /**
     * Helper to create a small subtree structure.
     */
    private void createSubtree(ESVOOctreeData octree, int rootIdx, int childBaseIdx) {
        var subtreeRoot = new ESVONodeUnified();
        subtreeRoot.setValid(true);
        subtreeRoot.setChildMask(0b00000011); // 2 children
        subtreeRoot.setChildPtr(childBaseIdx - rootIdx);
        octree.setNode(rootIdx, subtreeRoot);

        // Two leaf children
        for (int i = 0; i < 2; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(childBaseIdx + i, leaf);
        }
    }

    /**
     * Create larger octree with many duplicate subtrees for testing compression.
     */
    private ESVOOctreeData createLargeOctreeWithDuplicates() {
        var octree = new ESVOOctreeData(16384);

        // Root with 4 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00001111); // 4 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Create 4 subtrees, first 2 identical, last 2 identical
        for (int i = 0; i < 4; i++) {
            int nodeIdx = 1 + i;
            int childBase = 5 + (i * 3);
            createSubtree(octree, nodeIdx, childBase);
        }

        return octree;
    }

    /**
     * Create octree with unique nodes (worst case for compression).
     */
    private ESVOOctreeData createOctreeWithUniqueNodes() {
        var octree = new ESVOOctreeData(4096);

        // Root
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000111); // 3 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Each node is unique (different child masks)
        for (int i = 1; i <= 3; i++) {
            var node = new ESVONodeUnified();
            node.setValid(true);
            node.setChildMask(i); // Different mask for each node
            octree.setNode(i, node);
        }

        return octree;
    }

    /**
     * Create octree with maximal sharing (best case).
     */
    private ESVOOctreeData createOctreeWithMaximalSharing() {
        var octree = new ESVOOctreeData(8192);

        // Root with 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111); // All 8 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // All children are identical leaves
        var leafTemplate = new ESVONodeUnified();
        leafTemplate.setValid(true);
        leafTemplate.setChildMask(0);

        for (int i = 1; i <= 8; i++) {
            octree.setNode(i, leafTemplate);
        }

        return octree;
    }

    /**
     * Create octree with only leaves (no intermediate levels).
     */
    private ESVOOctreeData createOctreeWithOnlyLeaves() {
        var octree = new ESVOOctreeData(2048);

        // Root with 4 leaf children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00001111);
        root.setLeafMask(0b00001111); // All children are leaves
        root.setChildPtr(1);
        octree.setNode(0, root);

        // 4 leaf nodes
        for (int i = 1; i <= 4; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(i, leaf);
        }

        return octree;
    }

    /**
     * Create deep octree with specified depth.
     */
    private ESVOOctreeData createDeepOctree(int depth) {
        var octree = new ESVOOctreeData(16384);

        // Create a chain: each node has 1 child until we reach target depth
        for (int level = 0; level < depth; level++) {
            var node = new ESVONodeUnified();
            node.setValid(true);
            if (level < depth - 1) {
                node.setChildMask(0b00000001); // 1 child
                node.setChildPtr(1); // Next node
            } else {
                node.setChildMask(0); // Leaf at bottom
            }
            octree.setNode(level, node);
        }

        return octree;
    }
}
