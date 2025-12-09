/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.octree;

import com.hellblazer.luciferase.esvo.util.ESVOTopology;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark test comparing three rendering strategies for octree node visualization:
 * INSTANCING, BATCHED, and HYBRID.
 *
 * <p>This test measures rendering time and scene graph node count for each strategy
 * across multiple node counts (100, 500, 1000, 2000) to identify performance characteristics.</p>
 *
 * @author hal.hildebrand
 */
public class OctreeNodeMeshRendererBenchmark {

    /**
     * Launcher class for JavaFX initialization (required pattern for tests).
     */
    public static class Launcher extends Application {
        private static final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void start(Stage primaryStage) {
            latch.countDown();
        }

        public static void initJavaFX() throws Exception {
            new Thread(() -> Application.launch(Launcher.class)).start();
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("JavaFX initialization timeout");
            }
        }
    }

    private static final int MAX_DEPTH = 10; // Octree depth for calculations
    private static final int[] NODE_COUNTS = {100, 500, 1000, 2000};

    @Test
    public void testRenderingStrategiesBenchmark() throws Exception {
        // Initialize JavaFX
        Launcher.initJavaFX();

        System.out.println("\n=== OCTREE NODE RENDERING BENCHMARK ===");
        System.out.println("Comparing three strategies: INSTANCING, BATCHED, HYBRID\n");

        // Run benchmarks for each node count
        for (int nodeCount : NODE_COUNTS) {
            System.out.println("Node Count: " + nodeCount);
            System.out.println("─".repeat(60));

            var nodeIndices = generateNodeIndices(nodeCount, MAX_DEPTH);

            // Benchmark each strategy
            for (var strategy : OctreeNodeMeshRenderer.Strategy.values()) {
                benchmarkStrategy(strategy, nodeIndices);
            }

            System.out.println();
        }

        System.out.println("=== BENCHMARK COMPLETE ===\n");
    }

    @Test
    public void testInstancingStrategy() throws Exception {
        Launcher.initJavaFX();

        var nodeIndices = generateNodeIndices(100, MAX_DEPTH);
        var renderer = new OctreeNodeMeshRenderer(MAX_DEPTH, OctreeNodeMeshRenderer.Strategy.INSTANCING);

        var result = new Object[]{null};
        var latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            result[0] = renderer.render(nodeIndices);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Rendering timeout");
        assertNotNull(result[0]);
        assertTrue(result[0] instanceof Group);
        
        var group = (Group) result[0];
        assertEquals(100, group.getChildren().size(), "Should have 100 Box shapes");
    }

    @Test
    public void testBatchedStrategy() throws Exception {
        Launcher.initJavaFX();

        var nodeIndices = generateNodeIndices(100, MAX_DEPTH);
        var renderer = new OctreeNodeMeshRenderer(MAX_DEPTH, OctreeNodeMeshRenderer.Strategy.BATCHED);

        var result = new Object[]{null};
        var latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            result[0] = renderer.render(nodeIndices);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Rendering timeout");
        assertNotNull(result[0]);
        assertTrue(result[0] instanceof Group);
        
        var group = (Group) result[0];
        assertEquals(1, group.getChildren().size(), "Should have single merged mesh");
    }

    @Test
    public void testHybridStrategy() throws Exception {
        Launcher.initJavaFX();

        var nodeIndices = generateNodeIndices(100, MAX_DEPTH);
        var renderer = new OctreeNodeMeshRenderer(MAX_DEPTH, OctreeNodeMeshRenderer.Strategy.HYBRID);

        var result = new Object[]{null};
        var latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            result[0] = renderer.render(nodeIndices);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Rendering timeout");
        assertNotNull(result[0]);
        assertTrue(result[0] instanceof Group);
        
        var group = (Group) result[0];
        assertEquals(100, group.getChildren().size(), "Should have 100 MeshView instances");
    }

    /**
     * Benchmark a specific rendering strategy.
     */
    private void benchmarkStrategy(OctreeNodeMeshRenderer.Strategy strategy, List<Integer> nodeIndices) 
            throws InterruptedException {
        
        var renderer = new OctreeNodeMeshRenderer(MAX_DEPTH, strategy);

        // Warmup
        for (int i = 0; i < 3; i++) {
            var latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                renderer.render(nodeIndices);
                latch.countDown();
            });
            latch.await(5, TimeUnit.SECONDS);
        }

        // Measure
        int iterations = 10;
        long totalTime = 0;
        int[] sceneGraphNodes = {0};

        for (int i = 0; i < iterations; i++) {
            var latch = new CountDownLatch(1);
            long[] iterationTime = {0};

            Platform.runLater(() -> {
                long start = System.nanoTime();
                var group = renderer.render(nodeIndices);
                long end = System.nanoTime();

                iterationTime[0] = end - start;
                sceneGraphNodes[0] = countSceneGraphNodes(group);
                latch.countDown();
            });

            latch.await(5, TimeUnit.SECONDS);
            totalTime += iterationTime[0];
        }

        double avgTimeMs = (totalTime / iterations) / 1_000_000.0;

        System.out.printf("  %s: %.2f ms (avg), %d scene graph nodes%n",
            strategy.name().toLowerCase(), avgTimeMs, sceneGraphNodes[0]);
    }

    /**
     * Count total scene graph nodes recursively.
     */
    private int countSceneGraphNodes(Group group) {
        int count = 1; // Count this group
        for (var child : group.getChildren()) {
            if (child instanceof Group) {
                count += countSceneGraphNodes((Group) child);
            } else {
                count++;
            }
        }
        return count;
    }

    /**
     * Generate a list of octree node indices at various levels.
     * Creates a balanced distribution across octree levels.
     */
    private List<Integer> generateNodeIndices(int count, int maxDepth) {
        var indices = new ArrayList<Integer>(count);

        // Calculate nodes per level for balanced distribution
        int levelsToUse = Math.min(5, maxDepth); // Use first 5 levels
        int nodesPerLevel = count / levelsToUse;
        int remainder = count % levelsToUse;

        for (int level = 0; level < levelsToUse; level++) {
            int levelNodeCount = nodesPerLevel + (level < remainder ? 1 : 0);
            int firstNodeAtLevel = ESVOTopology.getFirstNodeAtLevel(level);
            int nodeCountAtLevel = ESVOTopology.getNodeCountAtLevel(level);

            // Add evenly spaced nodes from this level
            for (int i = 0; i < levelNodeCount; i++) {
                int stride = nodeCountAtLevel / levelNodeCount;
                int nodeIndex = firstNodeAtLevel + (i * stride);
                
                // Ensure we don't exceed actual node count at this level
                if (nodeIndex < firstNodeAtLevel + nodeCountAtLevel) {
                    indices.add(nodeIndex);
                }
            }
        }

        return indices;
    }
}
