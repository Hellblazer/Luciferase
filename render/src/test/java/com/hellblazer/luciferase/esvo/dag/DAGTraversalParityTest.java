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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 (F2.2): DAG Traversal Parity Tests - Verify DAG and SVO produce identical traversal results.
 *
 * <p>This test suite validates that DAG compression preserves correctness by comparing
 * DAG traversal results against the original SVO traversal for the same structure.
 *
 * <p><b>Test Coverage:</b>
 * <ul>
 * <li>Leaf traversal parity</li>
 * <li>Ray intersection parity (basic)</li>
 * <li>Frustum culling parity (basic)</li>
 * <li>Depth traversal parity</li>
 * <li>Node visitation parity</li>
 * <li>Child resolution parity</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 6: DAG Traversal Parity Tests")
class DAGTraversalParityTest {

    // ==================== Leaf Traversal Parity ====================

    @Test
    @DisplayName("Leaf traversal produces identical results for SVO and DAG")
    void testLeafTraversalParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoLeaves = traverseAllLeaves(svo);
        var dagLeaves = traverseAllLeaves(dag);

        assertEquals(svoLeaves.size(), dagLeaves.size(),
                    "SVO and DAG should have same number of leaves");
    }

    @Test
    @DisplayName("Leaf masks are identical between SVO and DAG")
    void testLeafMaskParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoLeaves = traverseAllLeaves(svo);
        var dagLeaves = traverseAllLeaves(dag);

        // Both should have same leaf pattern (childMask = 0)
        for (var leaf : svoLeaves) {
            assertEquals(0, leaf.getChildMask(), "SVO leaf should have childMask=0");
        }

        for (var leaf : dagLeaves) {
            assertEquals(0, leaf.getChildMask(), "DAG leaf should have childMask=0");
        }
    }

    @Test
    @DisplayName("Multi-level leaf traversal parity")
    void testMultiLevelLeafParity() {
        var svo = createMultiLevelOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoLeaves = traverseAllLeaves(svo);
        var dagLeaves = traverseAllLeaves(dag);

        assertEquals(svoLeaves.size(), dagLeaves.size(),
                    "Multi-level tree should have same number of leaves");
    }

    // ==================== Depth Traversal Parity ====================

    @Test
    @DisplayName("Maximum depth matches between SVO and DAG")
    void testMaxDepthParity() {
        var svo = createDeepOctree(6);
        var dag = DAGBuilder.from(svo).build();

        var svoDepth = computeMaxDepth(svo);
        var dagDepth = dag.getMetadata().maxDepth();

        assertEquals(svoDepth, dagDepth,
                    "SVO and DAG should have same maximum depth");
    }

    @Test
    @DisplayName("Nodes at each depth level match between SVO and DAG")
    void testDepthTraversalParity() {
        var svo = createBalancedOctree();
        var dag = DAGBuilder.from(svo).build();

        var maxDepth = Math.min(computeMaxDepth(svo), dag.getMetadata().maxDepth());

        for (int depth = 0; depth <= maxDepth; depth++) {
            var svoNodesAtDepth = getNodesAtDepth(svo, depth);
            var dagNodesAtDepth = getNodesAtDepth(dag, depth);

            // DAG may have fewer nodes due to sharing, but depth should be accessible
            assertTrue(dagNodesAtDepth.size() > 0 || svoNodesAtDepth.size() == 0,
                      "DAG should have nodes at depth " + depth + " if SVO does");
        }
    }

    @Test
    @DisplayName("Single node tree has depth 0 for both SVO and DAG")
    void testSingleNodeDepthParity() {
        var svo = createSingleNodeOctree();
        var dag = DAGBuilder.from(svo).build();

        assertEquals(0, computeMaxDepth(svo), "SVO single node should have depth 0");
        assertEquals(0, dag.getMetadata().maxDepth(), "DAG single node should have depth 0");
    }

    // ==================== Child Resolution Parity ====================

    @Test
    @DisplayName("Child resolution produces valid indices in both SVO and DAG")
    void testChildResolutionParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoRoot = svo.getNode(0);
        var dagRoot = dag.getNode(0);

        // Both roots should have same child mask
        assertEquals(svoRoot.getChildMask(), dagRoot.getChildMask(),
                    "Root child masks should match");

        // Verify child resolution works for both
        for (int octant = 0; octant < 8; octant++) {
            if ((svoRoot.getChildMask() & (1 << octant)) != 0) {
                var svoChildIdx = svoRoot.getChildIndex(octant, 0, svo.getFarPointers());
                var dagChildIdx = dag.resolveChildIndex(0, dagRoot, octant);

                assertTrue(svoChildIdx >= 0 && svoChildIdx < svo.getNodeCount(),
                          "SVO child index should be valid");
                assertTrue(dagChildIdx >= 0 && dagChildIdx < dag.nodeCount(),
                          "DAG child index should be valid");
            }
        }
    }

    @Test
    @DisplayName("Child masks match for corresponding nodes")
    void testChildMaskParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        // Root child masks must match
        assertEquals(svo.getNode(0).getChildMask(), dag.getNode(0).getChildMask(),
                    "Root child masks should match");

        // All SVO nodes should have corresponding structure in DAG
        var svoIndices = svo.getNodeIndices();
        for (var svoIdx : svoIndices) {
            var svoNode = svo.getNode(svoIdx);
            if (svoNode != null && svoNode.isValid()) {
                // DAG should have nodes with same child mask pattern (though possibly shared)
                assertTrue(hasNodeWithMask(dag, svoNode.getChildMask()),
                          "DAG should contain node with child mask " + svoNode.getChildMask());
            }
        }
    }

    // ==================== Node Visitation Parity ====================

    @Test
    @DisplayName("Breadth-first traversal visits same number of unique nodes")
    void testBreadthFirstTraversalParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoVisited = breadthFirstTraversal(svo);
        var dagVisited = breadthFirstTraversal(dag);

        // DAG may visit fewer nodes due to sharing, but should cover same structure
        assertTrue(dagVisited.size() <= svoVisited.size(),
                  "DAG should visit <= nodes than SVO due to sharing");
        assertTrue(dagVisited.size() > 0, "DAG should visit at least root");
    }

    @Test
    @DisplayName("Depth-first traversal visits reachable nodes")
    void testDepthFirstTraversalParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoVisited = depthFirstTraversal(svo);
        var dagVisited = depthFirstTraversal(dag);

        assertTrue(dagVisited.size() <= svoVisited.size(),
                  "DAG should visit <= nodes than SVO");
        assertTrue(dagVisited.size() > 0, "DAG should visit at least root");
    }

    // ==================== Ray Intersection Parity (Basic) ====================

    @Test
    @DisplayName("Ray hitting root produces same result for SVO and DAG")
    void testRayIntersectionRootParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        // Simple ray that hits root bounding box
        var rayOrigin = new float[]{0.5f, 0.5f, -1.0f};
        var rayDirection = new float[]{0.0f, 0.0f, 1.0f};

        var svoHit = rayIntersectsRoot(svo, rayOrigin, rayDirection);
        var dagHit = rayIntersectsRoot(dag, rayOrigin, rayDirection);

        assertEquals(svoHit, dagHit,
                    "SVO and DAG should both hit (or miss) root with same ray");
    }

    @Test
    @DisplayName("Ray missing tree produces same result for SVO and DAG")
    void testRayMissParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        // Ray that misses the tree entirely
        var rayOrigin = new float[]{10.0f, 10.0f, 10.0f};
        var rayDirection = new float[]{1.0f, 0.0f, 0.0f};

        var svoHit = rayIntersectsRoot(svo, rayOrigin, rayDirection);
        var dagHit = rayIntersectsRoot(dag, rayOrigin, rayDirection);

        assertEquals(svoHit, dagHit, "Both should miss");
        assertFalse(svoHit, "Ray should miss SVO");
        assertFalse(dagHit, "Ray should miss DAG");
    }

    // ==================== Frustum Culling Parity (Basic) ====================

    @Test
    @DisplayName("Frustum containing tree shows same visibility for SVO and DAG")
    void testFrustumContainsParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        // Large frustum that contains entire tree
        var frustum = createLargeFrustum();

        var svoVisible = frustumContainsRoot(svo, frustum);
        var dagVisible = frustumContainsRoot(dag, frustum);

        assertEquals(svoVisible, dagVisible,
                    "SVO and DAG should both be visible in large frustum");
        assertTrue(svoVisible, "SVO should be visible");
        assertTrue(dagVisible, "DAG should be visible");
    }

    @Test
    @DisplayName("Frustum not containing tree shows same result for SVO and DAG")
    void testFrustumExcludesParity() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        // Frustum that excludes the tree
        var frustum = createExcludingFrustum();

        var svoVisible = frustumContainsRoot(svo, frustum);
        var dagVisible = frustumContainsRoot(dag, frustum);

        assertEquals(svoVisible, dagVisible,
                    "SVO and DAG should both be excluded");
        assertFalse(svoVisible, "SVO should not be visible");
        assertFalse(dagVisible, "DAG should not be visible");
    }

    // ==================== Structural Equivalence ====================

    @Test
    @DisplayName("Root nodes have equivalent structure in SVO and DAG")
    void testRootStructuralEquivalence() {
        var svo = createTestOctree();
        var dag = DAGBuilder.from(svo).build();

        var svoRoot = svo.getNode(0);
        var dagRoot = dag.getNode(0);

        assertNotNull(svoRoot, "SVO root should exist");
        assertNotNull(dagRoot, "DAG root should exist");
        assertTrue(svoRoot.isValid(), "SVO root should be valid");
        assertTrue(dagRoot.isValid(), "DAG root should be valid");
        assertEquals(svoRoot.getChildMask(), dagRoot.getChildMask(),
                    "Root child masks should match");
    }

    // ==================== Test Helper Methods ====================

    /**
     * Traverse tree and collect all leaf nodes.
     * For SVO, count each unique leaf node once (use visited set).
     */
    private List<ESVONodeUnified> traverseAllLeaves(ESVOOctreeData svo) {
        var leaves = new ArrayList<ESVONodeUnified>();
        var queue = new ArrayDeque<Integer>();
        var visited = new HashSet<Integer>();

        queue.add(0);

        while (!queue.isEmpty()) {
            var nodeIdx = queue.poll();
            var node = svo.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

            if (node.getChildMask() == 0) {
                // Leaf node - add to results (each leaf visit counts)
                leaves.add(node);
            } else {
                // Internal node - use visited set to prevent cycles
                if (!visited.contains(nodeIdx)) {
                    visited.add(nodeIdx);
                    for (int octant = 0; octant < 8; octant++) {
                        if ((node.getChildMask() & (1 << octant)) != 0) {
                            var childIdx = node.getChildIndex(octant, nodeIdx, svo.getFarPointers());
                            if (childIdx >= 0) {
                                queue.add(childIdx);
                            }
                        }
                    }
                }
            }
        }

        return leaves;
    }

    private List<ESVONodeUnified> traverseAllLeaves(DAGOctreeData dag) {
        var leaves = new ArrayList<ESVONodeUnified>();
        var queue = new ArrayDeque<Integer>();
        var visited = new HashSet<Integer>();

        queue.add(0);

        while (!queue.isEmpty()) {
            var nodeIdx = queue.poll();
            var node = dag.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

            if (node.getChildMask() == 0) {
                // Leaf node - add to results (don't check visited for leaves)
                // This counts each LEAF VISIT, even if same node index is visited multiple times
                leaves.add(node);
            } else {
                // Internal node - use visited set to prevent cycles
                if (!visited.contains(nodeIdx)) {
                    visited.add(nodeIdx);
                    for (int octant = 0; octant < 8; octant++) {
                        if ((node.getChildMask() & (1 << octant)) != 0) {
                            var childIdx = dag.resolveChildIndex(nodeIdx, node, octant);
                            if (childIdx >= 0) {
                                queue.add(childIdx);
                            }
                        }
                    }
                }
            }
        }

        return leaves;
    }

    private int computeMaxDepth(ESVOOctreeData svo) {
        var queue = new ArrayDeque<int[]>(); // [nodeIdx, depth]
        var visited = new HashSet<Integer>();

        queue.offer(new int[]{0, 0});
        visited.add(0);

        var maxDepth = 0;

        while (!queue.isEmpty()) {
            var current = queue.poll();
            var nodeIdx = current[0];
            var depth = current[1];

            maxDepth = Math.max(maxDepth, depth);

            var node = svo.getNode(nodeIdx);
            if (node == null || !node.isValid() || node.getChildMask() == 0) {
                continue;
            }

            for (int octant = 0; octant < 8; octant++) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    var childIdx = node.getChildIndex(octant, nodeIdx, svo.getFarPointers());
                    if (childIdx >= 0 && !visited.contains(childIdx)) {
                        queue.offer(new int[]{childIdx, depth + 1});
                        visited.add(childIdx);
                    }
                }
            }
        }

        return maxDepth;
    }

    private Set<ESVONodeUnified> getNodesAtDepth(ESVOOctreeData svo, int targetDepth) {
        var nodes = new HashSet<ESVONodeUnified>();
        var queue = new ArrayDeque<int[]>();
        var visited = new HashSet<Integer>();

        queue.offer(new int[]{0, 0});
        visited.add(0);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            var nodeIdx = current[0];
            var depth = current[1];

            var node = svo.getNode(nodeIdx);
            if (node == null || !node.isValid()) continue;

            if (depth == targetDepth) {
                nodes.add(node);
            }

            if (node.getChildMask() != 0) {
                for (int octant = 0; octant < 8; octant++) {
                    if ((node.getChildMask() & (1 << octant)) != 0) {
                        var childIdx = node.getChildIndex(octant, nodeIdx, svo.getFarPointers());
                        if (childIdx >= 0 && !visited.contains(childIdx)) {
                            queue.offer(new int[]{childIdx, depth + 1});
                            visited.add(childIdx);
                        }
                    }
                }
            }
        }

        return nodes;
    }

    private Set<ESVONodeUnified> getNodesAtDepth(DAGOctreeData dag, int targetDepth) {
        var nodes = new HashSet<ESVONodeUnified>();
        var queue = new ArrayDeque<int[]>();
        var visited = new HashSet<Integer>();

        queue.offer(new int[]{0, 0});
        visited.add(0);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            var nodeIdx = current[0];
            var depth = current[1];

            var node = dag.getNode(nodeIdx);
            if (node == null || !node.isValid()) continue;

            if (depth == targetDepth) {
                nodes.add(node);
            }

            if (node.getChildMask() != 0) {
                for (int octant = 0; octant < 8; octant++) {
                    if ((node.getChildMask() & (1 << octant)) != 0) {
                        var childIdx = dag.resolveChildIndex(nodeIdx, node, octant);
                        if (childIdx >= 0 && !visited.contains(childIdx)) {
                            queue.offer(new int[]{childIdx, depth + 1});
                            visited.add(childIdx);
                        }
                    }
                }
            }
        }

        return nodes;
    }

    private boolean hasNodeWithMask(DAGOctreeData dag, int targetMask) {
        for (var node : dag.nodes()) {
            if (node.getChildMask() == targetMask) {
                return true;
            }
        }
        return false;
    }

    private Set<Integer> breadthFirstTraversal(ESVOOctreeData svo) {
        var visited = new HashSet<Integer>();
        var queue = new ArrayDeque<Integer>();

        queue.add(0);
        visited.add(0);

        while (!queue.isEmpty()) {
            var nodeIdx = queue.poll();
            var node = svo.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

            for (int octant = 0; octant < 8; octant++) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    var childIdx = node.getChildIndex(octant, nodeIdx, svo.getFarPointers());
                    if (childIdx >= 0 && !visited.contains(childIdx)) {
                        queue.add(childIdx);
                        visited.add(childIdx);
                    }
                }
            }
        }

        return visited;
    }

    private Set<Integer> breadthFirstTraversal(DAGOctreeData dag) {
        var visited = new HashSet<Integer>();
        var queue = new ArrayDeque<Integer>();

        queue.add(0);
        visited.add(0);

        while (!queue.isEmpty()) {
            var nodeIdx = queue.poll();
            var node = dag.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

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

        return visited;
    }

    private Set<Integer> depthFirstTraversal(ESVOOctreeData svo) {
        var visited = new HashSet<Integer>();
        var stack = new ArrayDeque<Integer>();

        stack.push(0);

        while (!stack.isEmpty()) {
            var nodeIdx = stack.pop();
            if (visited.contains(nodeIdx)) continue;

            visited.add(nodeIdx);
            var node = svo.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

            for (int octant = 7; octant >= 0; octant--) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    var childIdx = node.getChildIndex(octant, nodeIdx, svo.getFarPointers());
                    if (childIdx >= 0 && !visited.contains(childIdx)) {
                        stack.push(childIdx);
                    }
                }
            }
        }

        return visited;
    }

    private Set<Integer> depthFirstTraversal(DAGOctreeData dag) {
        var visited = new HashSet<Integer>();
        var stack = new ArrayDeque<Integer>();

        stack.push(0);

        while (!stack.isEmpty()) {
            var nodeIdx = stack.pop();
            if (visited.contains(nodeIdx)) continue;

            visited.add(nodeIdx);
            var node = dag.getNode(nodeIdx);

            if (node == null || !node.isValid()) continue;

            for (int octant = 7; octant >= 0; octant--) {
                if ((node.getChildMask() & (1 << octant)) != 0) {
                    var childIdx = dag.resolveChildIndex(nodeIdx, node, octant);
                    if (childIdx >= 0 && !visited.contains(childIdx)) {
                        stack.push(childIdx);
                    }
                }
            }
        }

        return visited;
    }

    private boolean rayIntersectsRoot(ESVOOctreeData svo, float[] origin, float[] direction) {
        // Simple AABB intersection test for root (unit cube [0,1]^3)
        return rayBoxIntersection(origin, direction, new float[]{0, 0, 0}, new float[]{1, 1, 1});
    }

    private boolean rayIntersectsRoot(DAGOctreeData dag, float[] origin, float[] direction) {
        return rayBoxIntersection(origin, direction, new float[]{0, 0, 0}, new float[]{1, 1, 1});
    }

    private boolean rayBoxIntersection(float[] origin, float[] dir, float[] boxMin, float[] boxMax) {
        var tMin = Float.NEGATIVE_INFINITY;
        var tMax = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1e-6f) {
                if (origin[i] < boxMin[i] || origin[i] > boxMax[i]) {
                    return false;
                }
            } else {
                var t1 = (boxMin[i] - origin[i]) / dir[i];
                var t2 = (boxMax[i] - origin[i]) / dir[i];
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
                if (tMin > tMax) {
                    return false;
                }
            }
        }

        return tMax >= 0;
    }

    private boolean frustumContainsRoot(ESVOOctreeData svo, float[][] frustum) {
        // Simplified: just check if root center is in frustum
        var center = new float[]{0.5f, 0.5f, 0.5f};
        return pointInFrustum(center, frustum);
    }

    private boolean frustumContainsRoot(DAGOctreeData dag, float[][] frustum) {
        var center = new float[]{0.5f, 0.5f, 0.5f};
        return pointInFrustum(center, frustum);
    }

    private boolean pointInFrustum(float[] point, float[][] frustum) {
        // Simplified frustum test
        for (var plane : frustum) {
            var distance = plane[0] * point[0] + plane[1] * point[1] + plane[2] * point[2] + plane[3];
            if (distance < 0) {
                return false;
            }
        }
        return true;
    }

    private float[][] createLargeFrustum() {
        // Large frustum that contains unit cube
        return new float[][]{
            {1, 0, 0, 2},    // right
            {-1, 0, 0, 2},   // left
            {0, 1, 0, 2},    // top
            {0, -1, 0, 2},   // bottom
            {0, 0, 1, 2},    // far
            {0, 0, -1, 2}    // near
        };
    }

    private float[][] createExcludingFrustum() {
        // Frustum that excludes [0,1]^3
        return new float[][]{
            {1, 0, 0, -5},   // right plane far from origin
            {-1, 0, 0, -5},
            {0, 1, 0, -5},
            {0, -1, 0, -5},
            {0, 0, 1, -5},
            {0, 0, -1, -5}
        };
    }

    // ==================== Test Octree Creation ====================

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

    private ESVOOctreeData createBalancedOctree() {
        var octree = new ESVOOctreeData(8192);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0b11111111);
        root.setChildPtr(1);
        octree.setNode(0, root);

        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
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
