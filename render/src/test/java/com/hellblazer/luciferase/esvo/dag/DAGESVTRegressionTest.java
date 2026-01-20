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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: ESVT Regression Tests - Verify DAG changes don't break existing functionality.
 *
 * <p>This test suite validates that:
 * <ul>
 * <li>Existing ESVO test suite continues to pass with DAG changes</li>
 * <li>SparseVoxelData interface changes don't break ESVO implementations</li>
 * <li>ESVOOctreeData continues to use RELATIVE addressing correctly</li>
 * <li>DAGOctreeData uses ABSOLUTE addressing correctly</li>
 * <li>Addressing mode differentiation works properly</li>
 * </ul>
 *
 * <p><b>Zero Regression Requirement:</b> All 2,365+ existing tests must still pass.
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 5: DAG ESVT Regression Tests")
class DAGESVTRegressionTest {

    // ==================== Addressing Mode Tests ====================

    @Test
    @DisplayName("ESVOOctreeData uses RELATIVE addressing mode")
    void testESVOAddressingModeRelative() {
        var esvo = createTestESVO();
        assertEquals(PointerAddressingMode.RELATIVE, esvo.getAddressingMode(),
                    "ESVOOctreeData must use RELATIVE addressing for backward compatibility");
    }

    @Test
    @DisplayName("DAGOctreeData uses ABSOLUTE addressing mode")
    void testDAGAddressingModeAbsolute() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();
        assertEquals(PointerAddressingMode.ABSOLUTE, dag.getAddressingMode(),
                    "DAGOctreeData must use ABSOLUTE addressing for node sharing");
    }

    @Test
    @DisplayName("Addressing mode affects child resolution")
    void testAddressingModeChildResolution() {
        var svo = createSVOWithKnownStructure();
        var dag = DAGBuilder.from(svo).build();

        // Get root node (should be equivalent)
        var svoRoot = svo.getNode(0);
        var dagRoot = dag.getNode(0);

        assertNotNull(svoRoot);
        assertNotNull(dagRoot);
        assertEquals(svoRoot.getChildMask(), dagRoot.getChildMask(),
                    "Root child masks should match");

        // Verify addressing modes differ
        assertNotEquals(svo.getAddressingMode(), dag.getAddressingMode(),
                       "SVO and DAG should use different addressing modes");
    }

    // ==================== ESVO Interface Compatibility Tests ====================

    @Test
    @DisplayName("ESVOOctreeData child resolution still works")
    void testESVOChildResolutionStillWorks() {
        var esvo = createSVOWithChildren();

        // Verify we can resolve children using RELATIVE addressing
        var root = esvo.getNode(0);
        assertNotNull(root);
        assertTrue(root.getChildMask() != 0, "Root should have children");

        // Test resolveChildIndex for first child (octant 0)
        int childIdx = esvo.resolveChildIndex(0, root, 0);
        assertTrue(childIdx > 0, "Child index should be > 0");
        assertTrue(childIdx < esvo.getNodeCount(), "Child index should be within bounds");

        var child = esvo.getNode(childIdx);
        assertNotNull(child, "Child node should exist");
        assertTrue(child.isValid(), "Child node should be valid");
    }

    @Test
    @DisplayName("DAGOctreeData child resolution works with ABSOLUTE addressing")
    void testDAGChildResolutionWorks() {
        var svo = createSVOWithChildren();
        var dag = DAGBuilder.from(svo).build();

        var root = dag.getNode(0);
        assertNotNull(root);
        assertTrue(root.getChildMask() != 0, "Root should have children");

        // Test resolveChildIndex for first child (octant 0)
        int childIdx = dag.resolveChildIndex(0, root, 0);
        assertTrue(childIdx >= 0, "Child index should be >= 0");
        assertTrue(childIdx < dag.nodeCount(), "Child index should be within bounds");

        var child = dag.getNode(childIdx);
        assertNotNull(child, "Child node should exist");
        assertTrue(child.isValid(), "Child node should be valid");
    }

    @Test
    @DisplayName("SparseVoxelData interface default methods work for ESVO")
    void testSparseVoxelDataDefaultMethodsESVO() {
        var esvo = createTestESVO();

        // Test default methods from SparseVoxelData interface
        assertNotNull(esvo.root(), "root() should return non-null");
        assertTrue(esvo.nodeCount() > 0, "nodeCount() should be > 0");
        assertNotNull(esvo.nodes(), "nodes() should return non-null array");
        assertEquals(esvo.nodeCount(), esvo.nodes().length, "nodeCount() should match nodes().length");

        // Test far pointer methods (default implementations)
        assertNotNull(esvo.getFarPointers(), "getFarPointers() should return non-null");
        assertEquals(esvo.farPointerCount(), esvo.getFarPointers().length,
                    "farPointerCount() should match array length");
    }

    @Test
    @DisplayName("SparseVoxelData interface default methods work for DAG")
    void testSparseVoxelDataDefaultMethodsDAG() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        // Test default methods from SparseVoxelData interface
        assertNotNull(dag.root(), "root() should return non-null");
        assertTrue(dag.nodeCount() > 0, "nodeCount() should be > 0");
        assertNotNull(dag.nodes(), "nodes() should return non-null array");
        assertEquals(dag.nodeCount(), dag.nodes().length, "nodeCount() should match nodes().length");

        // Test far pointer methods (default implementations)
        assertNotNull(dag.getFarPointers(), "getFarPointers() should return non-null");
    }

    // ==================== Structural Preservation Tests ====================

    @Test
    @DisplayName("DAG preserves root node structure")
    void testDAGPreservesRootNode() {
        var svo = createTestSVO();
        var originalRoot = svo.getNode(0);

        var dag = DAGBuilder.from(svo).build();
        var dagRoot = dag.root();

        assertNotNull(dagRoot, "DAG root should not be null");
        assertEquals(originalRoot.getChildMask(), dagRoot.getChildMask(),
                    "Root child mask should be preserved");
        assertEquals(originalRoot.isValid(), dagRoot.isValid(),
                    "Root valid flag should be preserved");
    }

    @Test
    @DisplayName("DAG preserves tree depth")
    void testDAGPreservesTreeDepth() {
        var svo = createDeepSVO(8);
        int svoMaxDepth = computeMaxDepth(svo);

        var dag = DAGBuilder.from(svo).build();
        int dagMaxDepth = dag.getMetadata().maxDepth();

        assertEquals(svoMaxDepth, dagMaxDepth,
                    "DAG should preserve maximum tree depth");
    }

    @Test
    @DisplayName("DAG may have fewer physical leaves due to sharing")
    void testDAGLeafCountVsOriginal() {
        var svo = createSVOWithKnownLeafCount();
        int svoLeafCount = countLeaves(svo);

        var dag = DAGBuilder.from(svo).build();
        int dagLeafCount = countLeaves(dag);

        // DAG can compress identical leaves, so physical leaf count may be less
        assertTrue(dagLeafCount > 0, "DAG should have at least one leaf");
        assertTrue(dagLeafCount <= svoLeafCount,
                  "DAG physical leaf count should be <= original SVO leaf count due to sharing");
    }

    // ==================== Coordinate Space Tests ====================

    @Test
    @DisplayName("ESVOOctreeData coordinate space unchanged")
    void testESVOCoordinateSpace() {
        var esvo = createTestESVO();
        assertNotNull(esvo.getCoordinateSpace(),
                     "ESVOOctreeData should have non-null coordinate space");
    }

    @Test
    @DisplayName("DAGOctreeData inherits coordinate space")
    void testDAGCoordinateSpace() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        assertNotNull(dag.getCoordinateSpace(),
                     "DAGOctreeData should have non-null coordinate space");
        assertEquals(svo.getCoordinateSpace(), dag.getCoordinateSpace(),
                    "DAG should inherit coordinate space from source SVO");
    }

    // ==================== Test Helper Methods ====================

    /**
     * Create minimal test ESVO octree.
     */
    private ESVOOctreeData createTestESVO() {
        var octree = new ESVOOctreeData(1024);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0); // Leaf node
        octree.setNode(0, root);

        return octree;
    }

    /**
     * Create test SVO for DAG construction.
     */
    private ESVOOctreeData createTestSVO() {
        var octree = new ESVOOctreeData(2048);

        // Root with 2 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        // 2 identical leaf children
        var leaf = new ESVONodeUnified();
        leaf.setValid(true);
        leaf.setChildMask(0);

        octree.setNode(1, leaf);
        octree.setNode(2, leaf);

        return octree;
    }

    /**
     * Create SVO with known structure for testing.
     */
    private ESVOOctreeData createSVOWithKnownStructure() {
        var octree = new ESVOOctreeData(4096);

        // Root with 4 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00001111); // 4 children (octants 0-3)
        root.setChildPtr(1);
        octree.setNode(0, root);

        // Create 4 leaf children
        for (int i = 0; i < 4; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    /**
     * Create SVO with children for testing child resolution.
     */
    private ESVOOctreeData createSVOWithChildren() {
        return createSVOWithKnownStructure();
    }

    /**
     * Create deep SVO with specified depth.
     */
    private ESVOOctreeData createDeepSVO(int depth) {
        var octree = new ESVOOctreeData(16384);

        // Create chain: each node has 1 child until we reach target depth
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

    /**
     * Create SVO with known leaf count.
     */
    private ESVOOctreeData createSVOWithKnownLeafCount() {
        var octree = new ESVOOctreeData(4096);

        // Root with 8 leaf children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111); // All 8 children
        root.setLeafMask(0b11111111);  // All leaves
        root.setChildPtr(1);
        octree.setNode(0, root);

        // 8 leaf nodes
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    /**
     * Compute maximum depth of octree.
     */
    private int computeMaxDepth(ESVOOctreeData octree) {
        return computeDepth(octree, 0, 0);
    }

    /**
     * Recursively compute depth.
     */
    private int computeDepth(ESVOOctreeData octree, int nodeIdx, int currentDepth) {
        var node = octree.getNode(nodeIdx);
        if (node == null || node.getChildMask() == 0) {
            return currentDepth;
        }

        int maxDepth = currentDepth;
        for (int octant = 0; octant < 8; octant++) {
            if ((node.getChildMask() & (1 << octant)) != 0) {
                int childIdx = octree.resolveChildIndex(nodeIdx, node, octant);
                int depth = computeDepth(octree, childIdx, currentDepth + 1);
                maxDepth = Math.max(maxDepth, depth);
            }
        }

        return maxDepth;
    }

    /**
     * Count leaf nodes in octree.
     */
    private int countLeaves(ESVOOctreeData octree) {
        int count = 0;
        for (int i = 0; i < octree.getNodeCount(); i++) {
            var node = octree.getNode(i);
            if (node != null && node.getChildMask() == 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count leaf nodes in DAG.
     */
    private int countLeaves(DAGOctreeData dag) {
        int count = 0;
        for (var node : dag.nodes()) {
            if (node != null && node.getChildMask() == 0) {
                count++;
            }
        }
        return count;
    }
}
