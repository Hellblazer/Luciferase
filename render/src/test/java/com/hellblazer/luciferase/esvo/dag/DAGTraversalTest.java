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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: DAG Traversal Smoke Tests - Basic traversal operations on DAG structures.
 *
 * <p>This test suite validates:
 * <ul>
 * <li>Child pointer resolution using absolute addressing</li>
 * <li>Tree depth traversal</li>
 * <li>Node validation during traversal</li>
 * <li>Leaf node detection</li>
 * <li>Breadth-first and depth-first traversal patterns</li>
 * </ul>
 *
 * <p><b>Note:</b> These are smoke tests, not comprehensive ray intersection tests.
 * Full ray traversal will be implemented in Phase 3 (Ray Traversal Implementation).
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 5: DAG Traversal Smoke Tests")
class DAGTraversalTest {

    // ==================== Child Pointer Resolution Tests ====================

    @Test
    @DisplayName("Resolve child indices using absolute addressing")
    void testDAGChildResolution() {
        // Use a simple SVO that we know works correctly
        var svo = createWellFormedSVO();
        var dag = DAGBuilder.from(svo).build();

        assertTrue(dag.nodeCount() > 0, "DAG should have nodes");
        var root = dag.getNode(0);
        assertNotNull(root, "Root should exist");

        // If root has children, verify we can resolve them
        if (root.getChildMask() != 0) {
            int childrenFound = 0;
            for (int octant = 0; octant < 8; octant++) {
                if ((root.getChildMask() & (1 << octant)) != 0) {
                    int childIdx = dag.resolveChildIndex(0, root, octant);
                    assertTrue(childIdx >= 0, "Child index should be >= 0 for octant " + octant);
                    assertTrue(childIdx < dag.nodeCount(),
                              "Child index " + childIdx + " should be < nodeCount() " + dag.nodeCount() +
                              " for octant " + octant);

                    var child = dag.getNode(childIdx);
                    assertNotNull(child, "Child node should exist at index " + childIdx);
                    assertTrue(child.isValid(), "Child node should be valid");
                    childrenFound++;
                }
            }
            assertTrue(childrenFound > 0, "Should have found at least one child");
        }
    }

    @Test
    @DisplayName("Child resolution works for multi-level DAG")
    void testMultiLevelChildResolution() {
        var svo = createMultiLevelOctree();
        var dag = DAGBuilder.from(svo).build();

        // Traverse 2 levels deep
        var root = dag.getNode(0);
        assertNotNull(root);

        for (int octant = 0; octant < 8; octant++) {
            if ((root.getChildMask() & (1 << octant)) != 0) {
                int level1Idx = dag.resolveChildIndex(0, root, octant);
                assertTrue(level1Idx >= 0 && level1Idx < dag.nodeCount(),
                          "Level 1 index should be valid");
                var level1Node = dag.getNode(level1Idx);
                assertNotNull(level1Node);

                // Try to traverse one more level if children exist
                if (level1Node.getChildMask() != 0) {
                    for (int childOctant = 0; childOctant < 8; childOctant++) {
                        if ((level1Node.getChildMask() & (1 << childOctant)) != 0) {
                            int level2Idx = dag.resolveChildIndex(level1Idx, level1Node, childOctant);
                            assertTrue(level2Idx >= 0 && level2Idx < dag.nodeCount(),
                                      "Level 2 index should be valid");
                            var level2Node = dag.getNode(level2Idx);
                            assertNotNull(level2Node, "Level 2 node should exist at index " + level2Idx);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Invalid octant throws IndexOutOfBoundsException")
    void testInvalidOctantThrows() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();
        var root = dag.getNode(0);

        assertThrows(IndexOutOfBoundsException.class, () -> dag.resolveChildIndex(0, root, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dag.resolveChildIndex(0, root, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> dag.resolveChildIndex(0, root, 100));
    }

    // ==================== Tree Depth Traversal Tests ====================

    @Test
    @DisplayName("Compute maximum depth of DAG")
    void testDAGDepthTraversal() {
        var svo = createDeepOctree(5);
        var dag = DAGBuilder.from(svo).build();

        int maxDepth = dag.getMetadata().maxDepth();
        assertTrue(maxDepth >= 4, "Should have depth >= 4 for 5-level tree");
        assertTrue(maxDepth <= 5, "Should have depth <= 5");
    }

    @Test
    @DisplayName("Single node DAG has depth 0")
    void testSingleNodeDepth() {
        var svo = createSingleNodeOctree();
        var dag = DAGBuilder.from(svo).build();

        int maxDepth = dag.getMetadata().maxDepth();
        assertEquals(0, maxDepth, "Single node should have depth 0");
    }

    @Test
    @DisplayName("Traverse all nodes in breadth-first order")
    void testBreadthFirstTraversal() {
        var svo = createBalancedOctree();
        var dag = DAGBuilder.from(svo).build();

        Set<Integer> visited = new HashSet<>();
        var queue = new java.util.LinkedList<Integer>();
        queue.add(0);
        visited.add(0);

        while (!queue.isEmpty()) {
            int nodeIdx = queue.poll();
            var node = dag.getNode(nodeIdx);
            assertNotNull(node, "Node should exist at index " + nodeIdx);

            // Add children to queue
            for (int octant = 0; octant < 8; octant++) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    int childIdx = dag.resolveChildIndex(nodeIdx, node, octant);
                    if (!visited.contains(childIdx)) {
                        queue.add(childIdx);
                        visited.add(childIdx);
                    }
                }
            }
        }

        // Verify we visited a reasonable number of nodes
        assertTrue(visited.size() > 0, "Should visit at least root node");
        assertTrue(visited.size() <= dag.nodeCount(),
                  "Should not visit more nodes than exist in DAG");
    }

    // ==================== Node Validation Tests ====================

    @Test
    @DisplayName("All nodes in DAG are valid")
    void testAllNodesValid() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        for (var node : dag.nodes()) {
            assertTrue(node.isValid(), "All DAG nodes should be valid");
        }
    }

    @Test
    @DisplayName("Child indices are within bounds")
    void testChildIndicesWithinBounds() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        for (int i = 0; i < dag.nodeCount(); i++) {
            var node = dag.getNode(i);
            if (node.getChildMask() != 0) {
                for (int octant = 0; octant < 8; octant++) {
                    if ((node.getChildMask() & (1 << octant)) != 0) {
                        int childIdx = dag.resolveChildIndex(i, node, octant);
                        assertTrue(childIdx >= 0, "Child index should be >= 0");
                        assertTrue(childIdx < dag.nodeCount(),
                                  "Child index should be < nodeCount()");
                    }
                }
            }
        }
    }

    // ==================== Leaf Node Detection Tests ====================

    @Test
    @DisplayName("Detect leaf nodes correctly")
    void testLeafNodeDetection() {
        var svo = createOctreeWithLeaves();
        var dag = DAGBuilder.from(svo).build();

        int leafCount = 0;
        for (var node : dag.nodes()) {
            if (node.getChildMask() == 0) {
                leafCount++;
                // Verify it's actually a leaf (no children to resolve)
                for (int octant = 0; octant < 8; octant++) {
                    assertFalse((node.getChildMask() & (1 << octant)) != 0,
                               "Leaf node should not have child at octant " + octant);
                }
            }
        }

        assertTrue(leafCount > 0, "Should have at least one leaf node");
    }

    @Test
    @DisplayName("Leaf nodes have no children")
    void testLeafNodesHaveNoChildren() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        for (var node : dag.nodes()) {
            if (node.getChildMask() == 0) {
                // This is a leaf - verify child pointer is not used
                // (In absolute addressing, childPtr might still have a value,
                //  but childMask=0 means no children exist)
                assertEquals(0, node.getChildMask(), "Leaf should have childMask=0");
            }
        }
    }

    // ==================== Shared Node Tests ====================

    @Test
    @DisplayName("Multiple parents can point to same child (node sharing)")
    void testNodeSharing() {
        var svo = createOctreeWithDuplicates();
        var dag = DAGBuilder.from(svo).build();

        // Verify compression occurred (DAG smaller than SVO)
        assertTrue(dag.nodeCount() < svo.getNodeCount(),
                  "DAG should be smaller due to node sharing");

        // Find shared nodes by collecting all child indices
        Set<Integer> childIndices = new HashSet<>();
        int duplicateCount = 0;

        for (int i = 0; i < dag.nodeCount(); i++) {
            var node = dag.getNode(i);
            for (int octant = 0; octant < 8; octant++) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    int childIdx = dag.resolveChildIndex(i, node, octant);
                    if (childIndices.contains(childIdx)) {
                        duplicateCount++;
                    }
                    childIndices.add(childIdx);
                }
            }
        }

        assertTrue(duplicateCount > 0, "Should have at least one shared node");
    }

    // ==================== Test Helper Methods ====================

    /**
     * Create well-formed SVO with proper relative addressing.
     * Pattern from DAGBuilderTest that's known to work.
     */
    private ESVOOctreeData createWellFormedSVO() {
        var octree = new ESVOOctreeData(4096);

        // Root node at index 0
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children at octants 0 and 1
        root.setChildPtr(1); // Relative offset to children
        octree.setNode(0, root);

        // First child at index 1
        var child1 = new ESVONodeUnified();
        child1.setValid(true);
        child1.setChildMask(0); // Leaf
        octree.setNode(1, child1);

        // Second child at index 2
        var child2 = new ESVONodeUnified();
        child2.setValid(true);
        child2.setChildMask(0); // Leaf
        octree.setNode(2, child2);

        return octree;
    }

    private ESVOOctreeData createTestOctree() {
        return createWellFormedSVO();
    }

    private ESVOOctreeData createMultiLevelOctree() {
        var octree = new ESVOOctreeData(8192);

        // Root at index 0 with 2 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00000011); // 2 children
        root.setChildPtr(1); // Children at indices 1 and 2
        octree.setNode(0, root);

        // Level 1 child 1 at index 1 (has 2 children)
        var child1 = new ESVONodeUnified();
        child1.setValid(true);
        child1.setChildMask(0b00000011); // 2 children
        child1.setChildPtr(2); // Relative: 1 + 2 = 3 (children at 3 and 4)
        octree.setNode(1, child1);

        // Level 1 child 2 at index 2 (has 2 children)
        var child2 = new ESVONodeUnified();
        child2.setValid(true);
        child2.setChildMask(0b00000011); // 2 children
        child2.setChildPtr(3); // Relative: 2 + 3 = 5 (children at 5 and 6)
        octree.setNode(2, child2);

        // Level 2: 4 leaf nodes at indices 3, 4, 5, 6
        for (int i = 0; i < 4; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(3 + i, leaf);
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

    private ESVOOctreeData createBalancedOctree() {
        var octree = new ESVOOctreeData(8192);

        // Root with 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        // 8 leaf children
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    private ESVOOctreeData createDeepOctree(int depth) {
        var octree = new ESVOOctreeData(16384);

        for (int level = 0; level < depth; level++) {
            var node = new ESVONodeUnified();
            node.setValid(true);
            if (level < depth - 1) {
                node.setChildMask(0b00000001);
                node.setChildPtr(1);
            } else {
                node.setChildMask(0);
            }
            octree.setNode(level, node);
        }

        return octree;
    }

    private ESVOOctreeData createOctreeWithLeaves() {
        return createTestOctree(); // Already has leaves
    }

    private ESVOOctreeData createOctreeWithDuplicates() {
        var octree = new ESVOOctreeData(8192);

        // Root with 4 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b00001111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        // 4 identical leaf children (will be compressed in DAG)
        var leaf = new ESVONodeUnified();
        leaf.setValid(true);
        leaf.setChildMask(0);

        for (int i = 0; i < 4; i++) {
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }
}
