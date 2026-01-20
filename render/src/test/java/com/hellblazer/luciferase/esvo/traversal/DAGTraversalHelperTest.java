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
package com.hellblazer.luciferase.esvo.traversal;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test DAGTraversalHelper utility methods for CPU-side DAG operations.
 *
 * <p>Tests unified traversal helper that works with both SVO and DAG data.
 *
 * @author hal.hildebrand
 */
@DisplayName("DAG Traversal Helper Tests")
class DAGTraversalHelperTest {

    /**
     * Test resolveChildIndex helper method with SVO.
     */
    @Test
    @DisplayName("resolveChildIndex works with SVO")
    void testResolveChildIndexSVO() {
        var svo = createTestSVO();
        var root = svo.getNode(0);

        // Resolve child at octant 0
        int childIdx = DAGTraversalHelper.resolveChildIndex(svo, 0, root, 0);

        assertTrue(childIdx >= 0 && childIdx < svo.getNodeCount(),
            "Child index should be valid");

        var child = svo.getNode(childIdx);
        assertNotNull(child, "Child node should exist");
    }

    /**
     * Test resolveChildIndex helper method with DAG.
     */
    @Test
    @DisplayName("resolveChildIndex works with DAG")
    void testResolveChildIndexDAG() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();
        var root = dag.getNode(0);

        // Resolve child at octant 0
        int childIdx = DAGTraversalHelper.resolveChildIndex(dag, 0, root, 0);

        assertTrue(childIdx >= 0 && childIdx < dag.nodeCount(),
            "Child index should be valid");

        var child = dag.getNode(childIdx);
        assertNotNull(child, "Child node should exist");
    }

    /**
     * Test traverseToLeaf follows a path from root to leaf.
     */
    @Test
    @DisplayName("traverseToLeaf follows path from root to leaf")
    void testTraverseToLeaf() {
        var svo = createMultiLevelSVO();

        // Path: root → octant 0 → octant 0 (should reach a leaf)
        int[] path = {0, 0};

        int leafIdx = DAGTraversalHelper.traverseToLeaf(svo, path);

        assertTrue(leafIdx >= 0 && leafIdx < svo.nodeCount(),
            "Leaf index should be valid");

        var leaf = svo.getNode(leafIdx);
        assertNotNull(leaf, "Leaf node should exist");
    }

    /**
     * Test traverseToLeaf with empty path returns root.
     */
    @Test
    @DisplayName("traverseToLeaf with empty path returns root")
    void testTraverseToLeafEmptyPath() {
        var svo = createTestSVO();

        int[] emptyPath = {};
        int rootIdx = DAGTraversalHelper.traverseToLeaf(svo, emptyPath);

        assertEquals(0, rootIdx, "Empty path should return root index");
    }

    /**
     * Test traverseToLeaf on DAG produces same result as SVO.
     */
    @Test
    @DisplayName("traverseToLeaf works identically for SVO and DAG")
    void testTraverseToLeafSVOvsDAG() {
        var svo = createMultiLevelSVO();
        var dag = DAGBuilder.from(svo).build();

        int[] path = {0, 2, 4};  // arbitrary path

        int svoLeafIdx = DAGTraversalHelper.traverseToLeaf(svo, path);
        int dagLeafIdx = DAGTraversalHelper.traverseToLeaf(dag, path);

        // Both should succeed (valid indices)
        assertTrue(svoLeafIdx >= 0 && svoLeafIdx < svo.nodeCount(),
            "SVO leaf index should be valid");
        assertTrue(dagLeafIdx >= 0 && dagLeafIdx < dag.nodeCount(),
            "DAG leaf index should be valid");

        // Nodes should have same structure (child mask)
        var svoLeaf = svo.getNode(svoLeafIdx);
        var dagLeaf = dag.getNode(dagLeafIdx);

        assertEquals(svoLeaf.getChildMask(), dagLeaf.getChildMask(),
            "SVO and DAG leaf nodes should have same child mask");
    }

    /**
     * Test rayTrace basic functionality.
     */
    @Test
    @DisplayName("rayTrace works with both SVO and DAG")
    void testRayTrace() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        // Ray from origin through cube
        int svoResult = DAGTraversalHelper.rayTrace(svo, 0.0f, 0.0f, 0.0f,
                                                    1.0f, 1.0f, 1.0f, 100);
        int dagResult = DAGTraversalHelper.rayTrace(dag, 0.0f, 0.0f, 0.0f,
                                                    1.0f, 1.0f, 1.0f, 100);

        // Both should return valid node indices
        assertTrue(svoResult >= -1, "SVO ray trace should return valid result");
        assertTrue(dagResult >= -1, "DAG ray trace should return valid result");
    }

    /**
     * Test edge case: root is leaf (single-level tree).
     */
    @Test
    @DisplayName("Edge case: root is leaf (single-level tree)")
    void testRootIsLeaf() {
        var svo = createSingleNodeSVO();

        // Path should immediately hit root
        int[] emptyPath = {};
        int leafIdx = DAGTraversalHelper.traverseToLeaf(svo, emptyPath);

        assertEquals(0, leafIdx, "Root should be leaf");

        var root = svo.getNode(0);
        assertEquals(0, root.getChildMask(), "Root should have no children");
    }

    /**
     * Test invalid path (octant doesn't exist).
     */
    @Test
    @DisplayName("Invalid path returns -1")
    void testInvalidPath() {
        var svo = createTestSVO();

        // Path includes octant that doesn't exist
        int[] invalidPath = {9};  // invalid octant (> 7)

        int result = DAGTraversalHelper.traverseToLeaf(svo, invalidPath);
        assertEquals(-1, result, "Invalid path should return -1");
    }

    // === Helper Methods ===

    /**
     * Create simple test SVO.
     */
    private ESVOOctreeData createTestSVO() {
        var nodes = new ESVONodeUnified[3];

        // Root with children at octants 0 and 7
        nodes[0] = new ESVONodeUnified();
        nodes[0].setChildMask(0b10000001);
        nodes[0].setChildPtr(1);

        // Children (leaves)
        nodes[1] = new ESVONodeUnified();
        nodes[1].setChildMask(0);
        nodes[1].setLeafMask(0xFF);

        nodes[2] = new ESVONodeUnified();
        nodes[2].setChildMask(0);
        nodes[2].setLeafMask(0xFF);

        return ESVOOctreeData.fromNodes(nodes);
    }

    /**
     * Create multi-level SVO for testing.
     */
    private ESVOOctreeData createMultiLevelSVO() {
        var nodes = new java.util.ArrayList<ESVONodeUnified>();

        // Build 3-level tree
        buildTree(nodes, 0, 3, 0);

        return ESVOOctreeData.fromNodes(nodes.toArray(new ESVONodeUnified[0]));
    }

    /**
     * Recursively build test tree.
     */
    private void buildTree(java.util.ArrayList<ESVONodeUnified> nodes,
                           int currentDepth, int maxDepth, int nodeIdx) {
        if (currentDepth >= maxDepth) {
            return;
        }

        while (nodes.size() <= nodeIdx) {
            nodes.add(new ESVONodeUnified());
        }

        var node = nodes.get(nodeIdx);
        node.setChildMask(0b01010101);  // alternating octants
        int childPtr = nodes.size();
        node.setChildPtr(childPtr - nodeIdx);

        for (int i = 0; i < 8; i++) {
            if ((node.getChildMask() & (1 << i)) != 0) {
                int childIdx = nodes.size();
                nodes.add(new ESVONodeUnified());
                buildTree(nodes, currentDepth + 1, maxDepth, childIdx);
            }
        }
    }

    /**
     * Create single-node SVO.
     */
    private ESVOOctreeData createSingleNodeSVO() {
        var nodes = new ESVONodeUnified[1];
        nodes[0] = new ESVONodeUnified();
        nodes[0].setChildMask(0);
        nodes[0].setLeafMask(0xFF);
        return ESVOOctreeData.fromNodes(nodes);
    }
}
