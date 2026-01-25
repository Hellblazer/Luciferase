/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.gpu.validation;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal.OctreeNode;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import com.hellblazer.luciferase.render.tile.*;
import com.hellblazer.luciferase.render.tile.HybridTileDispatcher.HybridConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.2.2: Hybrid CPU/GPU Rendering Validation Test Suite
 *
 * <p>Validates optimal work distribution between CPU and GPU based on:
 * <ul>
 *   <li>Tile coherence levels (high/medium/low)</li>
 *   <li>GPU saturation state</li>
 *   <li>Performance-based routing decisions</li>
 * </ul>
 *
 * @see HybridTileDispatcher
 * @see HybridKernelExecutor
 */
@DisplayName("F3.2.2: Hybrid CPU/GPU Rendering Validation")
class F322HybridRenderingTest {

    private DAGOctreeData testDAG;
    private OctreeNode[] octreeNodes;
    private float[] sceneMin;
    private float[] sceneMax;

    @BeforeEach
    void setUp() {
        testDAG = createTestDAG();
        octreeNodes = createOctreeNodes();
        sceneMin = new float[]{0.0f, 0.0f, 0.0f};
        sceneMax = new float[]{1.0f, 1.0f, 1.0f};
    }

    @Nested
    @DisplayName("HybridDecision Enum")
    class HybridDecisionTests {

        @Test
        @DisplayName("GPU_BATCH uses GPU")
        void testGpuBatchUsesGpu() {
            assertTrue(HybridDecision.GPU_BATCH.usesGPU());
            assertFalse(HybridDecision.GPU_BATCH.usesCPU());
            assertTrue(HybridDecision.GPU_BATCH.isBatch());
        }

        @Test
        @DisplayName("GPU_SINGLE uses GPU")
        void testGpuSingleUsesGpu() {
            assertTrue(HybridDecision.GPU_SINGLE.usesGPU());
            assertFalse(HybridDecision.GPU_SINGLE.usesCPU());
            assertFalse(HybridDecision.GPU_SINGLE.isBatch());
        }

        @Test
        @DisplayName("CPU uses CPU")
        void testCpuUsesCpu() {
            assertFalse(HybridDecision.CPU.usesGPU());
            assertTrue(HybridDecision.CPU.usesCPU());
            assertFalse(HybridDecision.CPU.isBatch());
        }
    }

    @Nested
    @DisplayName("HybridDispatchMetrics")
    class HybridDispatchMetricsTests {

        @Test
        @DisplayName("Valid metrics construction")
        void testValidMetrics() {
            var metrics = new HybridDispatchMetrics(
                100, 30, 50, 20,  // tiles
                0.8, 0.2,         // ratios
                0.65, 0.5,        // coherence, saturation
                1000000L, 800000L, 200000L  // times
            );

            assertEquals(100, metrics.totalTiles());
            assertEquals(30, metrics.gpuBatchTiles());
            assertEquals(50, metrics.gpuSingleTiles());
            assertEquals(20, metrics.cpuTiles());
            assertEquals(80, metrics.totalGpuTiles());
        }

        @Test
        @DisplayName("GPU batch ratio calculation")
        void testGpuBatchRatio() {
            var metrics = new HybridDispatchMetrics(
                100, 40, 40, 20,
                0.8, 0.2, 0.65, 0.5,
                1000000L, 800000L, 200000L
            );

            assertEquals(0.5, metrics.gpuBatchRatio(), 0.001);
        }

        @Test
        @DisplayName("Efficiency calculation")
        void testEfficiency() {
            // High CPU usage with low GPU saturation should reduce efficiency
            var inefficient = new HybridDispatchMetrics(
                100, 20, 30, 50,
                0.5, 0.5, 0.5, 0.3,  // Low saturation, high CPU
                1000000L, 400000L, 600000L
            );

            assertTrue(inefficient.efficiency() < 1.0);

            // Low CPU usage should have high efficiency
            var efficient = new HybridDispatchMetrics(
                100, 50, 40, 10,
                0.9, 0.1, 0.7, 0.5,
                1000000L, 900000L, 100000L
            );

            assertEquals(1.0, efficient.efficiency(), 0.001);
        }

        @Test
        @DisplayName("fromBase creates metrics with no CPU")
        void testFromBase() {
            var base = new DispatchMetrics(100, 60, 40, 0.6, 0.7, 1000000L);
            var hybrid = HybridDispatchMetrics.fromBase(base);

            assertEquals(100, hybrid.totalTiles());
            assertEquals(60, hybrid.gpuBatchTiles());
            assertEquals(40, hybrid.gpuSingleTiles());
            assertEquals(0, hybrid.cpuTiles());
            assertEquals(1.0, hybrid.gpuRatio());
            assertEquals(0.0, hybrid.cpuRatio());
        }

        @Test
        @DisplayName("Invalid metrics throw exceptions")
        void testInvalidMetrics() {
            // Tiles don't add up
            assertThrows(IllegalArgumentException.class, () ->
                new HybridDispatchMetrics(100, 30, 30, 30, 0.6, 0.3, 0.5, 0.5, 1000L, 500L, 500L));

            // Negative values
            assertThrows(IllegalArgumentException.class, () ->
                new HybridDispatchMetrics(-1, 0, 0, 0, 0.0, 0.0, 0.5, 0.5, 0L, 0L, 0L));

            // Invalid ratios
            assertThrows(IllegalArgumentException.class, () ->
                new HybridDispatchMetrics(10, 5, 3, 2, 1.5, 0.0, 0.5, 0.5, 1000L, 500L, 500L));
        }
    }

    @Nested
    @DisplayName("ESVOCPURenderer")
    class ESVOCPURendererTests {

        @Test
        @DisplayName("CPU renderer construction")
        void testConstruction() {
            var renderer = new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 8);

            assertTrue(renderer.supportsCPU());
            assertEquals(2.0, renderer.getCPUGPURatio());
            assertEquals(0.0, renderer.getGPUSaturation());
        }

        @Test
        @DisplayName("CPU renderer with custom ratio")
        void testCustomRatio() {
            var renderer = new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 8, 3.5);

            assertEquals(3.5, renderer.getCPUGPURatio());
        }

        @Test
        @DisplayName("GPU saturation update")
        void testGpuSaturationUpdate() {
            var renderer = new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 8);

            renderer.setGPUSaturation(0.75);
            assertEquals(0.75, renderer.getGPUSaturation());

            // Clamp to valid range
            renderer.setGPUSaturation(1.5);
            assertEquals(1.0, renderer.getGPUSaturation());

            renderer.setGPUSaturation(-0.5);
            assertEquals(0.0, renderer.getGPUSaturation());
        }

        @Test
        @DisplayName("CPU ray execution")
        void testCpuExecution() {
            var renderer = new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 8);

            var rays = createTestRays(10);
            var indices = new int[]{0, 1, 2, 3, 4};

            renderer.executeCPU(rays, indices);

            // Results should be populated
            for (int idx : indices) {
                var result = renderer.getResult(idx);
                assertNotNull(result);
            }

            assertTrue(renderer.getCPUTimeNs() > 0);
        }

        @Test
        @DisplayName("Result caching and clearing")
        void testResultCaching() {
            var renderer = new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 8);

            var rays = createTestRays(5);
            renderer.executeCPU(rays, new int[]{0, 1, 2});

            // Results cached
            assertNotNull(renderer.getResult(0));
            assertNotNull(renderer.getResult(1));

            // Clear results
            renderer.clearResults();

            // Miss returned for cleared indices
            var miss = renderer.getResult(0);
            assertFalse(miss.isHit());
        }

        @Test
        @DisplayName("Invalid construction throws")
        void testInvalidConstruction() {
            assertThrows(IllegalArgumentException.class, () ->
                new ESVOCPURenderer(null, sceneMin, sceneMax, 8));

            assertThrows(IllegalArgumentException.class, () ->
                new ESVOCPURenderer(octreeNodes, null, sceneMax, 8));

            assertThrows(IllegalArgumentException.class, () ->
                new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 0));

            assertThrows(IllegalArgumentException.class, () ->
                new ESVOCPURenderer(octreeNodes, sceneMin, sceneMax, 8, 0.0));
        }
    }

    @Nested
    @DisplayName("HybridTileDispatcher Decision Logic")
    class HybridDispatcherDecisionTests {

        private HybridTileDispatcher dispatcher;

        @BeforeEach
        void setUp() {
            var tileConfig = TileConfiguration.from(512, 512, 64);  // 8x8 tiles
            dispatcher = new HybridTileDispatcher(
                tileConfig,
                (rays, dag) -> 0.5,  // Mock coherence
                (rays, indices, dag, score) -> null  // Mock beam tree factory
            );
        }

        @Test
        @DisplayName("High coherence routes to GPU batch")
        void testHighCoherenceGpuBatch() {
            var decision = dispatcher.makeDecision(0.8, 0.0, true);
            assertEquals(HybridDecision.GPU_BATCH, decision);
        }

        @Test
        @DisplayName("Medium coherence routes to GPU single")
        void testMediumCoherenceGpuSingle() {
            var decision = dispatcher.makeDecision(0.5, 0.0, true);
            assertEquals(HybridDecision.GPU_SINGLE, decision);
        }

        @Test
        @DisplayName("Low coherence routes to CPU")
        void testLowCoherenceCpu() {
            var decision = dispatcher.makeDecision(0.2, 0.0, true);
            assertEquals(HybridDecision.CPU, decision);
        }

        @Test
        @DisplayName("Low coherence without CPU support routes to GPU single")
        void testLowCoherenceNoCpu() {
            var decision = dispatcher.makeDecision(0.2, 0.0, false);
            assertEquals(HybridDecision.GPU_SINGLE, decision);
        }

        @Test
        @DisplayName("GPU saturation shifts medium coherence to CPU")
        void testGpuSaturationShiftsToCpu() {
            // Without saturation: GPU single
            var normalDecision = dispatcher.makeDecision(0.5, 0.5, true);
            assertEquals(HybridDecision.GPU_SINGLE, normalDecision);

            // With high saturation: CPU
            var saturatedDecision = dispatcher.makeDecision(0.5, 0.9, true);
            assertEquals(HybridDecision.CPU, saturatedDecision);
        }

        @Test
        @DisplayName("GPU saturation doesn't affect high coherence")
        void testGpuSaturationHighCoherence() {
            // High coherence still goes to GPU batch even when saturated
            var decision = dispatcher.makeDecision(0.8, 0.9, true);
            assertEquals(HybridDecision.GPU_BATCH, decision);
        }

        @Test
        @DisplayName("Boundary coherence values")
        void testBoundaryCoherence() {
            // Exactly at high threshold (0.7) -> GPU batch
            var atHigh = dispatcher.makeDecision(0.7, 0.0, true);
            assertEquals(HybridDecision.GPU_BATCH, atHigh);

            // Just below high threshold -> GPU single
            var belowHigh = dispatcher.makeDecision(0.69, 0.0, true);
            assertEquals(HybridDecision.GPU_SINGLE, belowHigh);

            // Exactly at low threshold (0.3) -> GPU single
            var atLow = dispatcher.makeDecision(0.3, 0.0, true);
            assertEquals(HybridDecision.GPU_SINGLE, atLow);

            // Just below low threshold -> CPU
            var belowLow = dispatcher.makeDecision(0.29, 0.0, true);
            assertEquals(HybridDecision.CPU, belowLow);
        }
    }

    @Nested
    @DisplayName("HybridConfig")
    class HybridConfigTests {

        @Test
        @DisplayName("Default configuration")
        void testDefaults() {
            var config = HybridConfig.defaults();

            assertEquals(0.7, config.highCoherenceThreshold());
            assertEquals(0.3, config.lowCoherenceThreshold());
            assertEquals(0.8, config.gpuSaturationThreshold());
            assertEquals(4, config.simdFactor());
        }

        @Test
        @DisplayName("Custom configuration")
        void testCustomConfig() {
            var config = new HybridConfig(0.8, 0.4, 0.9, 8);

            assertEquals(0.8, config.highCoherenceThreshold());
            assertEquals(0.4, config.lowCoherenceThreshold());
            assertEquals(0.9, config.gpuSaturationThreshold());
            assertEquals(8, config.simdFactor());
        }

        @Test
        @DisplayName("Invalid configuration throws")
        void testInvalidConfig() {
            // High < low
            assertThrows(IllegalArgumentException.class, () ->
                new HybridConfig(0.3, 0.7, 0.8, 4));

            // Out of range
            assertThrows(IllegalArgumentException.class, () ->
                new HybridConfig(1.5, 0.3, 0.8, 4));

            // Invalid SIMD factor
            assertThrows(IllegalArgumentException.class, () ->
                new HybridConfig(0.7, 0.3, 0.8, 0));
        }
    }

    @Nested
    @DisplayName("HybridTileDispatcher Frame Dispatch")
    class HybridDispatcherFrameTests {

        @Test
        @DisplayName("Dispatch frame with mixed coherence")
        void testDispatchFrameMixedCoherence() {
            int frameSize = 512;
            int tileSize = 64;
            var tileConfig = TileConfiguration.from(frameSize, frameSize, tileSize);

            // Varying coherence based on tile position
            var dispatcher = new HybridTileDispatcher(
                tileConfig,
                (rays, dag) -> {
                    // Return varying coherence based on first ray position
                    if (rays.length > 0) {
                        float x = rays[0].origin().x;
                        if (x < 0.33f) return 0.8;  // High
                        if (x < 0.66f) return 0.5;  // Medium
                        return 0.2;  // Low
                    }
                    return 0.5;
                },
                (rays, indices, dag, score) -> null
            );

            var executor = new MockHybridExecutor();
            var rays = createFrameRays(frameSize, frameSize);

            var metrics = dispatcher.dispatchFrame(rays, frameSize, frameSize, testDAG, executor);

            assertNotNull(metrics);
            assertEquals(64, metrics.totalTiles());  // 8x8 tiles
            assertTrue(metrics.gpuBatchTiles() > 0, "Should have some batch tiles");
            assertTrue(metrics.dispatchTimeNs() > 0);
        }

        @Test
        @DisplayName("Dispatch with GPU saturation")
        void testDispatchWithSaturation() {
            int frameSize = 256;
            int tileSize = 64;
            var tileConfig = TileConfiguration.from(frameSize, frameSize, tileSize);
            var dispatcher = new HybridTileDispatcher(
                tileConfig,
                (rays, dag) -> 0.5,  // Medium coherence
                (rays, indices, dag, score) -> null
            );

            var executor = new MockHybridExecutor();
            executor.setGpuSaturation(0.9);  // High saturation

            var rays = createFrameRays(frameSize, frameSize);
            var metrics = dispatcher.dispatchFrame(rays, frameSize, frameSize, testDAG, executor);

            // With high saturation, medium coherence should go to CPU
            assertTrue(metrics.cpuTiles() > 0, "Should have CPU tiles due to saturation");
        }

        @Test
        @DisplayName("Time tracking")
        void testTimeTracking() {
            int frameSize = 128;
            int tileSize = 32;
            var tileConfig = TileConfiguration.from(frameSize, frameSize, tileSize);
            var dispatcher = new HybridTileDispatcher(
                tileConfig,
                (rays, dag) -> 0.5,
                (rays, indices, dag, score) -> null
            );

            var executor = new MockHybridExecutor();
            var rays = createFrameRays(frameSize, frameSize);

            var metrics = dispatcher.dispatchFrame(rays, frameSize, frameSize, testDAG, executor);

            assertTrue(metrics.dispatchTimeNs() > 0);
            // GPU + CPU time should be <= total time (execution is sequential in test)
            assertTrue(metrics.gpuTimeNs() + metrics.cpuTimeNs() <= metrics.dispatchTimeNs() + 1000000);
        }
    }

    @Nested
    @DisplayName("Custom Threshold Configuration")
    class CustomThresholdTests {

        @Test
        @DisplayName("Stricter thresholds route more to CPU")
        void testStricterThresholds() {
            var strictConfig = new HybridConfig(0.9, 0.6, 0.7, 4);
            var tileConfig = TileConfiguration.from(256, 256, 64);

            var dispatcher = new HybridTileDispatcher(
                tileConfig,
                strictConfig,
                (rays, dag) -> 0.7,  // Would be batch with defaults, single with strict
                (rays, indices, dag, score) -> null
            );

            // 0.7 coherence with strict config (high=0.9) should be GPU single
            var decision = dispatcher.makeDecision(0.7, 0.0, true);
            assertEquals(HybridDecision.GPU_SINGLE, decision);

            // 0.5 coherence with strict config (low=0.6) should be CPU
            var lowDecision = dispatcher.makeDecision(0.5, 0.0, true);
            assertEquals(HybridDecision.CPU, lowDecision);
        }

        @Test
        @DisplayName("Relaxed thresholds route more to GPU batch")
        void testRelaxedThresholds() {
            var relaxedConfig = new HybridConfig(0.5, 0.2, 0.9, 4);
            var tileConfig = TileConfiguration.from(256, 256, 64);

            var dispatcher = new HybridTileDispatcher(
                tileConfig,
                relaxedConfig,
                (rays, dag) -> 0.55,
                (rays, indices, dag, score) -> null
            );

            // 0.55 with relaxed config (high=0.5) should be GPU batch
            var decision = dispatcher.makeDecision(0.55, 0.0, true);
            assertEquals(HybridDecision.GPU_BATCH, decision);
        }
    }

    // Helper methods

    private DAGOctreeData createTestDAG() {
        var svo = createSimpleTestOctree();
        return DAGBuilder.from(svo).build();
    }

    private ESVOOctreeData createSimpleTestOctree() {
        var octree = new ESVOOctreeData(16);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);
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

    private OctreeNode[] createOctreeNodes() {
        var nodes = new OctreeNode[9];
        nodes[0] = new OctreeNode(0xFF | (1 << 8), 0);  // Root with 8 children
        for (int i = 1; i < 9; i++) {
            nodes[i] = new OctreeNode(0, 1);  // Leaf nodes with voxel value
        }
        return nodes;
    }

    private Ray[] createTestRays(int count) {
        var rays = new Ray[count];
        for (int i = 0; i < count; i++) {
            var origin = new Point3f(0.5f, 0.5f, -1.0f);
            var direction = new Vector3f(0.0f, 0.0f, 1.0f);
            rays[i] = new Ray(origin, direction);
        }
        return rays;
    }

    private Ray[] createFrameRays(int width, int height) {
        var rays = new Ray[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float u = (float) x / width;
                float v = (float) y / height;
                var origin = new Point3f(u, v, -1.0f);
                var direction = new Vector3f(0.0f, 0.0f, 1.0f);
                rays[y * width + x] = new Ray(origin, direction);
            }
        }
        return rays;
    }

    /**
     * Mock hybrid executor for testing.
     */
    private static class MockHybridExecutor implements HybridKernelExecutor {
        private double gpuSaturation = 0.0;
        private int batchCalls = 0;
        private int singleCalls = 0;
        private int cpuCalls = 0;

        @Override
        public void executeBatch(Ray[] rays, int[] rayIndices, int raysPerItem) {
            batchCalls++;
        }

        @Override
        public void executeSingleRay(Ray[] rays, int[] rayIndices) {
            singleCalls++;
        }

        @Override
        public void executeCPU(Ray[] rays, int[] rayIndices) {
            cpuCalls++;
        }

        @Override
        public RayResult getResult(int rayIndex) {
            return RayResult.miss();
        }

        @Override
        public double getGPUSaturation() {
            return gpuSaturation;
        }

        public void setGpuSaturation(double saturation) {
            this.gpuSaturation = saturation;
        }

        public int getBatchCalls() { return batchCalls; }
        public int getSingleCalls() { return singleCalls; }
        public int getCpuCalls() { return cpuCalls; }
    }
}
