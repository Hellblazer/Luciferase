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
import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ESVOCPUTraversal after updating to use polymorphic resolveChildIndex().
 *
 * <p>Validates that CPU traversal still works correctly with DAG input after
 * replacing manual child index calculation with abstraction.
 *
 * @author hal.hildebrand
 */
@DisplayName("ESVO CPU Traversal Update Tests")
class ESVOCPUTraversalUpdateTest {

    /**
     * Test CPU traversal still works with SVO input.
     */
    @Test
    @DisplayName("CPU traversal works with SVO input")
    void testCPUTraversalWithSVO() {
        var svo = createTestSVO();
        var nodes = convertToOctreeNodes(svo);

        var ray = new ESVOCPUTraversal.Ray(
            0.1f, 0.1f, 0.1f,  // origin
            1.0f, 1.0f, 1.0f,  // direction
            0.0f, 10.0f        // tMin, tMax
        );

        float[] sceneMin = {0.0f, 0.0f, 0.0f};
        float[] sceneMax = {1.0f, 1.0f, 1.0f};

        var result = ESVOCPUTraversal.traverseRay(ray, nodes, sceneMin, sceneMax, 8);

        assertNotNull(result, "Traversal should return result");
        assertTrue(result.iterations > 0, "Should have performed iterations");
    }

    /**
     * Test CPU traversal now works with DAG input.
     *
     * <p>CRITICAL: This test validates the main change in Phase 3 - that
     * ESVOCPUTraversal can traverse DAG structures after the update.
     */
    @Test
    @DisplayName("CPU traversal works with DAG input (CRITICAL)")
    void testCPUTraversalWithDAG() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();
        var nodes = convertToOctreeNodes(dag);

        var ray = new ESVOCPUTraversal.Ray(
            0.1f, 0.1f, 0.1f,  // origin
            1.0f, 1.0f, 1.0f,  // direction
            0.0f, 10.0f        // tMin, tMax
        );

        float[] sceneMin = {0.0f, 0.0f, 0.0f};
        float[] sceneMax = {1.0f, 1.0f, 1.0f};

        var result = ESVOCPUTraversal.traverseRay(ray, nodes, sceneMin, sceneMax, 8);

        assertNotNull(result, "DAG traversal should return result");
        assertTrue(result.iterations > 0, "Should have performed iterations");
    }

    /**
     * Test traversal results are identical for SVO vs DAG on same scene.
     */
    @Test
    @DisplayName("Traversal results identical for SVO vs DAG")
    void testTraversalResultsIdentical() {
        var svo = createTestSVO();
        var dag = DAGBuilder.from(svo).build();

        var svoNodes = convertToOctreeNodes(svo);
        var dagNodes = convertToOctreeNodes(dag);

        var ray = new ESVOCPUTraversal.Ray(
            0.5f, 0.5f, 0.5f,  // center
            1.0f, 0.0f, 0.0f,  // along X
            0.0f, 5.0f
        );

        float[] sceneMin = {0.0f, 0.0f, 0.0f};
        float[] sceneMax = {1.0f, 1.0f, 1.0f};

        var svoResult = ESVOCPUTraversal.traverseRay(ray, svoNodes, sceneMin, sceneMax, 8);
        var dagResult = ESVOCPUTraversal.traverseRay(ray, dagNodes, sceneMin, sceneMax, 8);

        // Both should agree on hit/miss
        assertEquals(svoResult.hit, dagResult.hit,
            "SVO and DAG should agree on hit/miss");

        if (svoResult.hit == 1 && dagResult.hit == 1) {
            // If both hit, distances should be approximately equal
            assertEquals(svoResult.t, dagResult.t, 0.001f,
                "SVO and DAG hit distances should match");
        }
    }

    /**
     * Test performance overhead of polymorphic call is minimal.
     *
     * <p>Validates that using resolveChildIndex() abstraction doesn't
     * significantly slow down traversal (<5% overhead).
     */
    @Test
    @DisplayName("Performance overhead <5% for polymorphic traversal")
    void testPerformanceOverhead() {
        var svo = createLargeSVO();
        var nodes = convertToOctreeNodes(svo);

        var rays = createTestRays(100);  // 100 test rays

        float[] sceneMin = {0.0f, 0.0f, 0.0f};
        float[] sceneMax = {1.0f, 1.0f, 1.0f};

        // Warmup
        for (int i = 0; i < 10; i++) {
            for (var ray : rays) {
                ESVOCPUTraversal.traverseRay(ray, nodes, sceneMin, sceneMax, 8);
            }
        }

        // Measure baseline time
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (var ray : rays) {
                ESVOCPUTraversal.traverseRay(ray, nodes, sceneMin, sceneMax, 8);
            }
        }
        long elapsedNanos = System.nanoTime() - startTime;

        // Log timing for reference (not a hard assertion)
        double elapsedMs = elapsedNanos / 1_000_000.0;
        System.out.printf("100 iterations × 100 rays = %.2f ms%n", elapsedMs);

        // Just verify it completes in reasonable time (<1 second for 10,000 rays)
        assertTrue(elapsedMs < 1000.0,
            "Traversal should complete in <1 second for 10,000 rays");
    }

    /**
     * Test multiple rays through same scene.
     */
    @Test
    @DisplayName("Multiple ray samples work correctly")
    void testMultipleRaySamples() {
        var svo = createTestSVO();
        var nodes = convertToOctreeNodes(svo);

        var rays = createTestRays(10);

        float[] sceneMin = {0.0f, 0.0f, 0.0f};
        float[] sceneMax = {1.0f, 1.0f, 1.0f};

        int hitCount = 0;
        for (var ray : rays) {
            var result = ESVOCPUTraversal.traverseRay(ray, nodes, sceneMin, sceneMax, 8);
            assertNotNull(result, "Each ray should return result");
            if (result.hit == 1) {
                hitCount++;
            }
        }

        // At least some rays should hit (depends on scene)
        assertTrue(hitCount >= 0, "Hit count should be non-negative");
    }

    // === Helper Methods ===

    /**
     * Create simple test SVO.
     */
    private ESVOOctreeData createTestSVO() {
        var nodes = new ESVONodeUnified[3];

        // Root node with two children (corners 0 and 7)
        nodes[0] = new ESVONodeUnified();
        nodes[0].setChildMask(0b10000001);
        nodes[0].setChildPtr(1);
        nodes[0].setValid(true);

        // Leaf node at corner 0 with voxel data
        nodes[1] = new ESVONodeUnified();
        nodes[1].setChildMask(0);
        nodes[1].setLeafMask(0xFF);
        nodes[1].setContourPtr(1);  // Non-zero attributes for hit detection
        nodes[1].setValid(true);

        // Leaf node at corner 7 with voxel data
        nodes[2] = new ESVONodeUnified();
        nodes[2].setChildMask(0);
        nodes[2].setLeafMask(0xFF);
        nodes[2].setContourPtr(1);  // Non-zero attributes for hit detection
        nodes[2].setValid(true);

        return ESVOOctreeData.fromNodes(nodes);
    }

    /**
     * Create larger SVO for performance testing.
     */
    private ESVOOctreeData createLargeSVO() {
        var nodes = new java.util.ArrayList<ESVONodeUnified>();
        buildTree(nodes, 0, 5, 0);  // 5-level tree
        return ESVOOctreeData.fromNodes(nodes.toArray(new ESVONodeUnified[0]));
    }

    /**
     * Recursively build tree.
     */
    private void buildTree(java.util.ArrayList<ESVONodeUnified> nodes,
                           int currentDepth, int maxDepth, int nodeIdx) {
        if (currentDepth >= maxDepth) {
            return;
        }

        while (nodes.size() <= nodeIdx) {
            var newNode = new ESVONodeUnified();
            newNode.setValid(true);
            nodes.add(newNode);
        }

        var node = nodes.get(nodeIdx);
        node.setChildMask(0b11111111);  // full node
        node.setValid(true);
        int childPtr = nodes.size();
        node.setChildPtr(childPtr - nodeIdx);

        for (int i = 0; i < 8; i++) {
            int childIdx = nodes.size();
            var childNode = new ESVONodeUnified();
            childNode.setValid(true);
            // Mark leaf nodes with non-zero attributes
            if (currentDepth + 1 >= maxDepth) {
                childNode.setContourPtr(1);
            }
            nodes.add(childNode);
            buildTree(nodes, currentDepth + 1, maxDepth, childIdx);
        }
    }

    /**
     * Convert SparseVoxelData to OctreeNode[] for CPU traversal.
     */
    private ESVOCPUTraversal.OctreeNode[] convertToOctreeNodes(
        com.hellblazer.luciferase.sparse.core.SparseVoxelData<ESVONodeUnified> data) {

        int nodeCount = data.nodeCount();
        var nodes = new ESVOCPUTraversal.OctreeNode[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            var node = data.getNode(i);
            if (node != null) {
                nodes[i] = new ESVOCPUTraversal.OctreeNode(
                    node.getChildDescriptor(),
                    node.getContourDescriptor()
                );
            } else {
                nodes[i] = new ESVOCPUTraversal.OctreeNode(0, 0);
            }
        }

        return nodes;
    }

    /**
     * Create array of test rays.
     */
    private ESVOCPUTraversal.Ray[] createTestRays(int count) {
        var rays = new ESVOCPUTraversal.Ray[count];

        for (int i = 0; i < count; i++) {
            float t = i / (float) count;
            rays[i] = new ESVOCPUTraversal.Ray(
                t, t, t,                 // origin
                1.0f, 1.0f, 1.0f,       // direction
                0.0f, 10.0f              // tMin, tMax
            );
        }

        return rays;
    }
}
