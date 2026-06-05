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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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

        // Each node is unique (different contour descriptors, all leaves)
        for (int i = 1; i <= 3; i++) {
            var node = new ESVONodeUnified(0, i); // Different contour = unique
            node.setValid(true);
            node.setChildMask(0); // Leaves
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

    // ==================== Hash Collision Regression Tests ====================

    /**
     * Regression test for Luciferase-7wzml.23: DAGBuilder must NOT merge two structurally-distinct
     * subtrees that share the same 64-bit truncation of their SHA-256 digest.
     *
     * <p>Mechanism: inject a {@code CollisionForcingHasher} via the package-private
     * {@code withHasherFactory} test hook. The hasher always produces digests whose first 8 bytes
     * (= the old truncated-long key) are identical, but whose remaining bytes differ based on a
     * call-counter. Under the old code, every node would collapse to the first canonical because
     * {@code putIfAbsent(identicalLong, nodeIdx)} treats them all as duplicates. Under the fixed
     * code, the full byte[] is compared, so nodes with different content are kept distinct.
     */
    @Test
    void testCollisionForcedHashDoesNotMergeDistinctSubtrees() {
        // Create SVO with two sibling leaves that have different contour descriptors.
        // They must NOT be merged even when their 64-bit hash prefix is forced identical.
        var octree = new ESVOOctreeData(4096);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Two structurally-distinct leaves: different contour descriptors
        var leaf1 = new ESVONodeUnified(0, 1); // contourDescriptor = 1
        leaf1.setValid(true);
        leaf1.setChildMask(0);
        octree.setNode(1, leaf1);

        var leaf2 = new ESVONodeUnified(0, 2); // contourDescriptor = 2 — DISTINCT
        leaf2.setValid(true);
        leaf2.setChildMask(0);
        octree.setNode(2, leaf2);

        // The forced-collision hasher: all digests have identical first 8 bytes but unique bytes[8].
        // Under the old truncated-long key, leaf1 and leaf2 would collapse to the same canonical.
        // Under the fixed full-digest key they must remain distinct.
        var callCounter = new AtomicInteger(0);
        Supplier<Hasher> collisionFactory = () -> new CollisionForcingHasher(callCounter.getAndIncrement());

        // Build with collision-forcing hasher injected via test hook
        var dag = DAGBuilder.from(octree)
                            .withHasherFactory(collisionFactory)
                            .withValidation(false) // skip validation; node layout may differ
                            .build();

        // ASSERTION 1: both distinct leaves must survive as separate DAG nodes.
        // If the old bug were present, canonicalNodes would contain only 2 entries (root + one leaf)
        // instead of 3 (root + two distinct leaves).
        assertEquals(3, dag.nodes().length,
                     "Both structurally-distinct leaves must be kept separate despite forced hash collision; "
                     + "old code would have merged them to 2 nodes total");

        // ASSERTION 2: compression ratio should be 1.0 (no sharing among distinct nodes)
        assertEquals(1.0f, dag.getCompressionRatio(), 0.01f,
                     "No compression should occur when all nodes are distinct");
    }

    /**
     * Verify that the collision-safe fix does NOT break compression on genuinely duplicate subtrees.
     * Two identical leaves must still be merged to 1 canonical node (compression ratio > 1).
     */
    @Test
    void testCollisionForcedHashStillMergesGenuineDuplicates() {
        // Root with two children that have IDENTICAL content — must still be merged
        var octree = new ESVOOctreeData(4096);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Both leaves are identical
        var leaf = new ESVONodeUnified(0, 42);
        leaf.setValid(true);
        leaf.setChildMask(0);
        octree.setNode(1, leaf);
        octree.setNode(2, leaf);

        // Use real SHA-256 (no collision forcing) — identical nodes must still deduplicate
        var dag = DAGBuilder.from(octree).build();

        // Root + 1 canonical leaf (leaf1 and leaf2 are identical, merged to 1)
        assertEquals(2, dag.nodes().length,
                     "Genuinely identical leaves must still be merged to a single canonical node");
        assertTrue(dag.getCompressionRatio() > 1.0f,
                   "Genuine duplicates must yield compression ratio > 1.0");
    }

    /**
     * Verify the digestBytes() API on JavaMessageDigestHasher returns a full 32-byte SHA-256 digest,
     * not the old 8-byte truncation. This pins the root cause of the collision bug.
     */
    @Test
    void testDigestBytesReturnsFullSha256() {
        var hasher = new JavaMessageDigestHasher("SHA-256");
        hasher.update(0xDEADBEEF);
        var bytes = hasher.digestBytes();

        assertEquals(32, bytes.length,
                     "digestBytes() must return the full 32-byte SHA-256 digest, not a truncated form");

        // Two different inputs must produce different full digests
        var hasher2 = new JavaMessageDigestHasher("SHA-256");
        hasher2.update(0xCAFEBABE);
        var bytes2 = hasher2.digestBytes();

        assertFalse(Arrays.equals(bytes, bytes2),
                    "Different inputs must produce different full digests");
    }

    // ==================== bead .150 / .151 Metadata & Depth Tests ====================

    /**
     * Bead .150: sharingByDepth must record sharing at real, reachable depths —
     * not at phantom depth-0 entries caused by orphaned nodes.
     *
     * <p>Layout:
     * <pre>
     *   idx 0: root  (depth 0)  childMask=0b111, childPtr=1 → children at 1,2,3
     *   idx 1: leafA (depth 1)  default leaf — canonical for all leaves
     *   idx 2: leafB (depth 1)  identical to leafA → duplicate at depth 1
     *   idx 3: mid   (depth 1)  internal, childMask=0b11, childPtr=1 → children at 4,5
     *   idx 4: leafC (depth 2)  identical to leafA → duplicate at depth 2
     *   idx 5: leafD (depth 2)  identical to leafA → duplicate at depth 2
     *   idx 6: orphan (unreachable) identical to leafA
     * </pre>
     * With the fix, orphan (idx 6) is skipped by the containsKey guard, so
     * sharingByDepth = {1:1, 2:2} — no depth-0 entry.  The pre-fix
     * {@code getOrDefault(6, 0)} would inject a spurious {0:1} entry.
     *
     * <p>Key discriminating assertion: {@code assertFalse(containsKey(0))}.
     */
    @Test
    void testSharingByDepthHasMultipleKeysOnMultiLevelDAG() {
        var octree = new ESVOOctreeData(8192);

        // Layout (indices 1-5 are reachable from root; idx 6 is an orphan):
        //   idx 0: root  (depth 0)  childMask=0b111, childPtr=1 → children at 1,2,3
        //   idx 1: leafA (depth 1)  default leaf — canonical for all leaves
        //   idx 2: leafB (depth 1)  identical to leafA → duplicate at depth 1
        //   idx 3: mid   (depth 1)  internal, childMask=0b11, childPtr=1 → children at 4,5
        //   idx 4: leafC (depth 2)  identical to leafA → duplicate at depth 2
        //   idx 5: leafD (depth 2)  identical to leafA → duplicate at depth 2
        //   idx 6: orphan           NOT reachable; under old code it gets depth=0 from getOrDefault

        // root (idx 0, depth 0): 3 children at idx 1,2,3
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000111); // octants 0,1,2
        root.setChildPtr(1);           // children at 0+1+{0,1,2} = 1,2,3
        octree.setNode(0, root);

        // leafA (idx 1, depth 1) — first default leaf; becomes canonical
        var leafA = new ESVONodeUnified();
        leafA.setValid(true);
        leafA.setChildMask(0);
        octree.setNode(1, leafA);

        // leafB (idx 2, depth 1) — identical to leafA → duplicate at depth 1
        var leafB = new ESVONodeUnified();
        leafB.setValid(true);
        leafB.setChildMask(0);
        octree.setNode(2, leafB);

        // mid (idx 3, depth 1) — internal node with 2 leaf children at 4 and 5
        var mid = new ESVONodeUnified();
        mid.setValid(true);
        mid.setChildMask(0b00000011); // octants 0 and 1
        mid.setChildPtr(1);           // children at 3+1+{0,1} = 4,5
        octree.setNode(3, mid);

        // leafC (idx 4, depth 2) — identical to leafA → duplicate at depth 2
        var leafC = new ESVONodeUnified();
        leafC.setValid(true);
        leafC.setChildMask(0);
        octree.setNode(4, leafC);

        // leafD (idx 5, depth 2) — identical to leafA → duplicate at depth 2
        var leafD = new ESVONodeUnified();
        leafD.setValid(true);
        leafD.setChildMask(0);
        octree.setNode(5, leafD);

        // orphan (idx 6) — identical leaf NOT reachable from root.
        // Old code: getOrDefault(6, 0) = 0 → spurious depth-0 entry in sharingByDepth.
        // Fixed code: !depthMap.containsKey(6) → skipped entirely.
        var orphan = new ESVONodeUnified();
        orphan.setValid(true);
        orphan.setChildMask(0);
        octree.setNode(6, orphan);

        var dag = DAGBuilder.from(octree).build();
        var sharingByDepth = dag.getMetadata().sharingByDepth();

        // Real sharing at depth 1: leafB is a duplicate of leafA.
        assertTrue(sharingByDepth.containsKey(1),
                   "sharingByDepth must contain depth 1 (leafB duplicate); got: " + sharingByDepth);

        // Real sharing at depth 2: leafC and leafD are duplicates of leafA.
        assertTrue(sharingByDepth.containsKey(2),
                   "sharingByDepth must contain depth 2 (leafC/leafD duplicates); got: " + sharingByDepth);

        // KEY DISCRIMINATING ASSERTION: no spurious depth-0 entry from orphaned node.
        // FAILS with pre-fix code (orphan idx 6 gets getOrDefault depth=0).
        // PASSES with fix (containsKey guard skips orphaned nodes).
        assertFalse(sharingByDepth.containsKey(0),
                    "sharingByDepth must NOT contain depth 0 (orphan must be skipped); got: " + sharingByDepth);
    }

    /**
     * Bead .150: sourceHash must differ for two sources with identical node count
     * but different content.
     *
     * <p>This pins that sourceHash is a real content hash (derived from the root's
     * subtree digest), not a node-count proxy.
     */
    @Test
    void testSourceHashDiffersForSameCountDifferentContent() {
        // Source A: root with one leaf child at octant 0
        var octreeA = new ESVOOctreeData(64);
        var rootA = new ESVONodeUnified();
        rootA.setValid(true);
        rootA.setChildMask(0b00000001); // octant 0
        rootA.setChildPtr(1);
        octreeA.setNode(0, rootA);
        var leafA = new ESVONodeUnified();
        leafA.setValid(true);
        leafA.setChildMask(0);
        octreeA.setNode(1, leafA);

        // Source B: root with one leaf child at octant 1 — same node count, different structure
        var octreeB = new ESVOOctreeData(64);
        var rootB = new ESVONodeUnified();
        rootB.setValid(true);
        rootB.setChildMask(0b00000010); // octant 1 instead of 0
        rootB.setChildPtr(1);
        octreeB.setNode(0, rootB);
        var leafB = new ESVONodeUnified();
        leafB.setValid(true);
        leafB.setChildMask(0);
        octreeB.setNode(1, leafB);

        var metaA = DAGBuilder.from(octreeA).build().getMetadata();
        var metaB = DAGBuilder.from(octreeB).build().getMetadata();

        assertEquals(metaA.originalNodeCount(), metaB.originalNodeCount(),
                     "Both sources must have the same node count for this test to be meaningful");
        assertNotEquals(metaA.sourceHash(), metaB.sourceHash(),
                        "sourceHash must differ for sources with identical node count but different structure");
    }

    /**
     * Bead .151: estimateMaxDepth must return the true depth on a hand-crafted
     * 3-level SVO (depth == 2, since root is level 0) with non-contiguous octants.
     *
     * <p>All leaf nodes are given distinct contour descriptors so none deduplicate;
     * the compacted DAG preserves the full 3-level chain.  The test locks the
     * contract between buildCompactedDAG's ascending-octant child-pointer layout
     * and estimateMaxDepth's sparseOffset traversal — if the two ever diverge, the
     * BFS will mis-walk the childPointers array and return the wrong depth.
     *
     * <p>Structure (source indices → compacted indices after build):
     * <pre>
     *   root (idx 0): children at octants 1 and 5
     *     octant-1 child (idx 1): child at octant 3          ← depth 1
     *       octant-3 child (idx 3): unique leaf (contour=3)  ← depth 2
     *     octant-5 child (idx 2): unique leaf (contour=2)    ← depth 1
     * </pre>
     */
    @Test
    void testEstimateMaxDepthOnThreeLevelDAGWithMixedOctants() {
        // Build a 3-level source SVO with unique leaf nodes (distinct contour descriptors
        // guarantee no deduplication, so the compacted DAG retains the full 3-level chain).
        var octree = new ESVOOctreeData(1024);

        // root (idx 0): children at octants 1 and 5
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask((1 << 1) | (1 << 5)); // octants 1 and 5
        root.setChildPtr(1); // first child at idx 0+1=1, second at idx 0+1+1=2
        octree.setNode(0, root);

        // mid1 (idx 1, depth 1): child at octant 3 only
        var mid1 = new ESVONodeUnified();
        mid1.setValid(true);
        mid1.setChildMask(1 << 3); // octant 3 only
        // getChildIndex = currentNodeIdx(1) + childPtr + sparseOffset(mask,oct)
        // For octant 3: 1 + 2 + 0 = 3  → child at idx 3
        mid1.setChildPtr(2);
        octree.setNode(1, mid1);

        // leaf2 (idx 2, depth 1, octant 5 child of root): unique via contour=200
        var leaf2 = new ESVONodeUnified(0, 200);
        leaf2.setValid(true);
        leaf2.setChildMask(0);
        octree.setNode(2, leaf2);

        // leaf3 (idx 3, depth 2, octant 3 child of mid1): unique via contour=300
        var leaf3 = new ESVONodeUnified(0, 300);
        leaf3.setValid(true);
        leaf3.setChildMask(0);
        octree.setNode(3, leaf3);

        var dag = DAGBuilder.from(octree).build();
        var metadata = dag.getMetadata();

        // No deduplication should have occurred (all leaf nodes unique).
        assertEquals(4, metadata.originalNodeCount(), "Source has 4 nodes");
        assertEquals(4, metadata.uniqueNodeCount(), "No deduplication expected with unique leaves");
        assertEquals(2, metadata.maxDepth(),
                     "3-level SVO should yield maxDepth == 2; got " + metadata.maxDepth());
    }

    /**
     * Bead .151 S1: estimateMaxDepth must return the TRUE longest root-to-leaf path even
     * when the deepest leaf is first reached via a SHORTER path.
     *
     * <p>Regression for the visited-set BFS that recorded a shared node at its first
     * (shortest) arrival depth and never revisited it, causing underestimation.
     *
     * <p>Structure — all leaves have distinct contour descriptors so no deduplication
     * occurs; the compacted DAG is topologically identical to the source SVO.
     * <pre>
     *   root (depth 0)  → children: A (depth 1), B (depth 1)
     *   A    (depth 1)  → child: SHARED (depth 2)          ← SHORT path  reaches SHARED at depth 2
     *   B    (depth 1)  → child: C (depth 2)
     *   C    (depth 2)  → child: SHARED (depth 3)          ← LONG path   reaches SHARED at depth 3
     *   SHARED (leaf, unique contour so it stays as one node, but two PATHS reach it)
     * </pre>
     * The TRUE longest path is depth 3 (root→B→C→SHARED).
     * Old visited-BFS would record SHARED at depth 2 (via A) and never update → returns 2.
     * Longest-path traversal must return 3.
     *
     * <p>This test FAILS with the old visited-set BFS and PASSES with the fix.
     */
    @Test
    void testEstimateMaxDepthSharedNodeLongestPathWins() {
        // We need a DAG (post-deduplication) where one node is truly shared (reachable
        // via two paths of different lengths).  We achieve this by building an SVO whose
        // two deep leaves are IDENTICAL so the DAGBuilder merges them into one shared node.
        //
        // Source SVO layout (indices):
        //   0: root     childMask=0b11 (octants 0,1), childPtr=1  → children at 1,2
        //   1: A        childMask=0b01 (octant 0),    childPtr=1  → child at 3      (depth 1, short path to 3)
        //   2: B        childMask=0b01 (octant 0),    childPtr=1  → child at 4      (depth 1)
        //   3: LEAF_X   childMask=0  (unique contour=111)         (depth 2 via root→A→LEAF_X)
        //   4: C        childMask=0b01 (octant 0),    childPtr=1  → child at 5      (depth 2)
        //   5: LEAF_Y   childMask=0  (same contour=111 as LEAF_X) (depth 3 via root→B→C→LEAF_Y)
        //
        // LEAF_X and LEAF_Y are IDENTICAL → DAGBuilder merges them into one shared node.
        // After compaction the DAG has:
        //   root → A → sharedLeaf  (path length 2)
        //   root → B → C → sharedLeaf  (path length 3)
        // TRUE max depth = 3.

        var octree = new ESVOOctreeData(4096);

        // root (idx 0, depth 0): 2 children at octants 0 and 1
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // octants 0 and 1
        root.setChildPtr(1);           // children at idx 1 and 2
        octree.setNode(0, root);

        // A (idx 1, depth 1): 1 child at octant 0
        var a = new ESVONodeUnified(0, 10); // unique contour so A stays distinct
        a.setValid(true);
        a.setChildMask(0b00000001); // octant 0 only
        a.setChildPtr(2);           // child at idx 1+2=3
        octree.setNode(1, a);

        // B (idx 2, depth 1): 1 child at octant 0
        var b = new ESVONodeUnified(0, 20); // unique contour so B stays distinct
        b.setValid(true);
        b.setChildMask(0b00000001); // octant 0 only
        b.setChildPtr(2);           // child at idx 2+2=4
        octree.setNode(2, b);

        // LEAF_X (idx 3, depth 2): identical leaf (contour=111) — will merge with LEAF_Y
        var leafX = new ESVONodeUnified(0, 111);
        leafX.setValid(true);
        leafX.setChildMask(0);
        octree.setNode(3, leafX);

        // C (idx 4, depth 2): 1 child at octant 0
        var c = new ESVONodeUnified(0, 30); // unique contour so C stays distinct
        c.setValid(true);
        c.setChildMask(0b00000001); // octant 0 only
        c.setChildPtr(1);           // child at idx 4+1=5
        octree.setNode(4, c);

        // LEAF_Y (idx 5, depth 3): IDENTICAL to LEAF_X → will be merged by DAGBuilder
        var leafY = new ESVONodeUnified(0, 111); // same contour=111
        leafY.setValid(true);
        leafY.setChildMask(0);
        octree.setNode(5, leafY);

        var dag = DAGBuilder.from(octree).build();
        var metadata = dag.getMetadata();

        // Sanity: the two identical leaves must have been merged (5 source nodes → 5 compacted,
        // since root/A/B/C are unique but LEAF_X+LEAF_Y collapse to 1).
        assertEquals(5, metadata.uniqueNodeCount(),
                     "root+A+B+C+sharedLeaf = 5 unique nodes; got " + metadata.uniqueNodeCount());

        // THE KEY ASSERTION: longest path is root→B→C→sharedLeaf = depth 3.
        // Old visited-BFS would first reach sharedLeaf via root→A at depth 2, mark it visited,
        // and never revisit it → returns maxDepth=2 (WRONG).
        // Longest-path traversal must return 3.
        assertEquals(3, metadata.maxDepth(),
                     "True longest path root→B→C→sharedLeaf is depth 3; "
                     + "visited-BFS would incorrectly return 2; got " + metadata.maxDepth());
    }

    /**
     * Hasher that forces a 64-bit collision: all instances return the same first 8 bytes,
     * but bytes[8] encodes the instance counter so full digests are distinct.
     *
     * <p>This simulates the birthday-bound collision scenario that the old code was
     * vulnerable to: same truncated-long key, different structural content.
     */
    private static final class CollisionForcingHasher implements Hasher {
        // All instances share the same 8-byte prefix — forces a 64-bit collision
        private static final byte[] FIXED_PREFIX = new byte[]{0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42};

        private final int instanceId;
        // Accumulate input bytes so that nodes with different content still differ
        // in bytes[9..31] of the digest (even though bytes[0..7] are always FIXED_PREFIX).
        private int inputChecksum = 0;

        CollisionForcingHasher(int instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public void update(byte value) {
            inputChecksum = inputChecksum * 31 + value;
        }

        @Override
        public void update(int value) {
            inputChecksum = inputChecksum * 31 + value;
        }

        @Override
        public void update(long value) {
            inputChecksum = inputChecksum * 31 + Long.hashCode(value);
        }

        @Override
        public byte[] digestBytes() {
            // 32-byte digest: first 8 bytes always identical (forces 64-bit collision),
            // bytes 8-11 encode inputChecksum (so distinct content yields distinct full digest),
            // bytes 12-15 encode instanceId (extra uniqueness guard).
            var result = new byte[32];
            System.arraycopy(FIXED_PREFIX, 0, result, 0, 8);
            result[8]  = (byte) (inputChecksum >> 24);
            result[9]  = (byte) (inputChecksum >> 16);
            result[10] = (byte) (inputChecksum >> 8);
            result[11] = (byte) inputChecksum;
            result[12] = (byte) (instanceId >> 24);
            result[13] = (byte) (instanceId >> 16);
            result[14] = (byte) (instanceId >> 8);
            result[15] = (byte) instanceId;
            return result;
        }
    }
}
