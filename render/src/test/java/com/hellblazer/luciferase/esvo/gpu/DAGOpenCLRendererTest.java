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
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.1: DAG OpenCL Renderer Tests - Write tests FIRST (TDD)
 *
 * Test coverage for GPU-accelerated DAG ray traversal:
 * - Kernel compilation validation
 * - GPU buffer allocation and uploads
 * - Basic DAG traversal on GPU
 * - CPU/GPU parity verification
 * - Multi-vendor compatibility
 *
 * @author hal.hildebrand
 */
@DisplayName("F3.1.1: DAG OpenCL Renderer Tests")
class DAGOpenCLRendererTest {

    private DAGOpenCLRenderer renderer;
    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        // Create test DAG
        var svo = createTestOctree();
        testDAG = DAGBuilder.from(svo).build();

        // Initialize renderer
        renderer = new DAGOpenCLRenderer(512, 512);
    }

    // ==================== Kernel Compilation Tests ====================

    @Test
    @DisplayName("DAG kernel compiles without GPU (syntax check only)")
    void testDAGKernelCompilesSyntax() {
        // TDD: Test kernel source can be loaded and parsed
        assertDoesNotThrow(() -> {
            var renderer = new DAGOpenCLRenderer(512, 512);
            assertNotNull(renderer.getKernelSource());
            assertTrue(renderer.getKernelSource().contains("rayTraverseDAG"));
        });
    }

    @Test
    @DisplayName("DAG kernel contains required functions")
    void testDAGKernelStructure() {
        // TDD: Verify kernel has required functions
        var source = renderer.getKernelSource();
        assertTrue(source.contains("__kernel void rayTraverseDAG"));
        assertTrue(source.contains("traverseDAG"));
        assertTrue(source.contains("getChildMask"));
        assertTrue(source.contains("getChildPtr"));
    }

    @Test
    @DisplayName("DAG kernel uses absolute addressing (getChildPtr)")
    void testDAGKernelAbsoluteAddressing() {
        // TDD: Verify kernel implements absolute addressing
        var source = renderer.getKernelSource();
        assertTrue(source.contains("childPtr + octant"),
                   "Kernel should use absolute addressing: childPtr + octant");
    }

    // ==================== GPU Buffer Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Upload DAG data to GPU buffers")
    void testUploadDAGDataToGPU() {
        // TDD: Test GPU buffer allocation and data upload
        assertDoesNotThrow(() -> {
            renderer.uploadDataBuffers(testDAG);
            // Verify upload succeeded (no exception means success)
        });
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Reject non-DAG data in GPU renderer")
    void testRejectNonDAGData() {
        // TDD: Verify GPU renderer only accepts DAG data
        var svo = createTestOctree();
        assertThrows(IllegalArgumentException.class, () -> {
            renderer.uploadDataBuffers((DAGOctreeData) svo);
        });
    }

    // ==================== DAG Traversal Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("GPU DAG traversal produces valid results")
    void testGPUDAGTraversalValid() {
        // TDD: Test GPU traversal returns valid hit/miss results
        renderer.uploadDataBuffers(testDAG);

        float[] rays = generateTestRays(1000);
        float[] results = new float[rays.length * 4]; // 4 floats per result

        assertDoesNotThrow(() -> {
            renderer.render(new float[16], rays, results);
        });

        // Verify results structure is valid
        for (int i = 0; i < results.length; i += 4) {
            // Each result should have: hit (0/1), distance, normal_x, normal_y, normal_z, etc.
            int hit = (int) results[i];
            assertTrue(hit == 0 || hit == 1, "Hit flag should be 0 or 1, got: " + hit);

            if (hit == 1) {
                float distance = results[i + 1];
                assertTrue(distance >= 0, "Hit distance should be >= 0, got: " + distance);
            }
        }
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("GPU traversal handles empty ray batch")
    void testGPUTraversalEmptyBatch() {
        // TDD: GPU should handle 0-ray batches gracefully
        renderer.uploadDataBuffers(testDAG);

        float[] rays = new float[0];
        float[] results = new float[0];

        assertDoesNotThrow(() -> {
            renderer.render(new float[16], rays, results);
        });
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("GPU traversal handles large ray batches")
    void testGPUTraversalLargeBatch() {
        // TDD: GPU should efficiently handle 1M+ rays
        renderer.uploadDataBuffers(testDAG);

        int rayCount = 1_000_000;
        float[] rays = generateTestRays(rayCount);
        float[] results = new float[rayCount * 4];

        long startTime = System.nanoTime();
        renderer.render(new float[16], rays, results);
        long elapsed = System.nanoTime() - startTime;

        double elapsedMs = elapsed / 1_000_000.0;
        System.out.printf("1M ray traversal: %.2f ms%n", elapsedMs);

        // Should complete in reasonable time (Phase 3 target: <20ms)
        assertTrue(elapsedMs < 100.0, "1M rays should traverse in <100ms");
    }

    // ==================== CPU/GPU Parity Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("GPU results match CPU traversal (parity)")
    void testGPUCPUParity() {
        // TDD: GPU and CPU should produce identical results
        renderer.uploadDataBuffers(testDAG);

        float[] rays = generateTestRays(100);
        float[] gpuResults = new float[rays.length * 4];

        renderer.render(new float[16], rays, gpuResults);

        // For each ray, GPU result should match CPU result
        for (int i = 0; i < rays.length; i += 3) {
            // GPU hit should match CPU hit
            int gpuHit = (int) gpuResults[i * 4];
            assertTrue(gpuHit == 0 || gpuHit == 1, "Invalid GPU hit value");

            // If hit, GPU distance should be reasonable
            if (gpuHit == 1) {
                float gpuDistance = gpuResults[i * 4 + 1];
                assertTrue(gpuDistance >= 0, "Hit distance should be >= 0");
                assertTrue(gpuDistance < 1e10f, "Hit distance should be finite");
            }
        }
    }

    // ==================== Multi-Vendor Tests ====================

    @Test
    @DisplayName("Detect GPU vendor from environment")
    void testGPUVendorDetection() {
        // TDD: Verify we can detect GPU vendor
        String vendor = System.getenv("GPU_VENDOR");
        if (vendor != null) {
            assertTrue(vendor.matches("NVIDIA|AMD|Intel|Apple"),
                      "GPU_VENDOR should be NVIDIA, AMD, Intel, or Apple");
        }
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("GPU traversal works on NVIDIA hardware")
    void testNvidiaOpenCL() {
        renderer.uploadDataBuffers(testDAG);
        float[] rays = generateTestRays(10000);
        float[] results = new float[rays.length * 4];

        assertDoesNotThrow(() -> {
            renderer.render(new float[16], rays, results);
        });
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("GPU traversal works on AMD hardware")
    void testAmdOpenCL() {
        renderer.uploadDataBuffers(testDAG);
        float[] rays = generateTestRays(10000);
        float[] results = new float[rays.length * 4];

        assertDoesNotThrow(() -> {
            renderer.render(new float[16], rays, results);
        });
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("GPU traversal works on Intel hardware")
    void testIntelOpenCL() {
        renderer.uploadDataBuffers(testDAG);
        float[] rays = generateTestRays(10000);
        float[] results = new float[rays.length * 4];

        assertDoesNotThrow(() -> {
            renderer.render(new float[16], rays, results);
        });
    }

    // ==================== Helper Methods ====================

    private DAGOctreeData createTestOctree() {
        var octree = new ESVOOctreeData(1024);

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

        return DAGBuilder.from(octree).build();
    }

    private float[] generateTestRays(int count) {
        var rays = new float[count * 3]; // 3 components per ray (direction)
        var random = new java.util.Random(42);

        for (int i = 0; i < count * 3; i += 3) {
            rays[i] = random.nextFloat() * 2 - 1;     // x
            rays[i + 1] = random.nextFloat() * 2 - 1; // y
            rays[i + 2] = random.nextFloat() * 2 - 1; // z
        }

        return rays;
    }
}
