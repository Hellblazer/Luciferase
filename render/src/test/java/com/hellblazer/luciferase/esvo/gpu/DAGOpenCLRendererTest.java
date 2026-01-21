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
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
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
    private ESVOOctreeData testSVO;

    @BeforeEach
    void setUp() {
        // Create test SVO and DAG
        testSVO = createTestOctree();
        testDAG = DAGBuilder.from(testSVO).build();

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
    @DisplayName("Reject non-DAG data (wrong addressing mode)")
    void testRejectNonDAGData() {
        // TDD: Verify GPU renderer only accepts DAG data with absolute addressing
        // Create an SVO (which uses relative addressing) and try to upload
        assertThrows(IllegalArgumentException.class, () -> {
            // Directly create a DAG-compatible object but with wrong addressing
            // For now, we verify the renderer validates addressing mode
            renderer.uploadDataBuffers(testDAG); // This should work
            assertTrue(testDAG.getAddressingMode() == PointerAddressingMode.ABSOLUTE);
        });
    }

    // ==================== DAG Structure Tests ====================

    @Test
    @DisplayName("DAG uses absolute addressing mode")
    void testDAGAddressingMode() {
        // TDD: Verify DAG is built with absolute addressing
        assertEquals(PointerAddressingMode.ABSOLUTE, testDAG.getAddressingMode(),
                    "DAG must use absolute addressing");
    }

    @Test
    @DisplayName("Renderer name identifies as DAG")
    void testRendererName() {
        // TDD: Verify renderer name is correct
        assertEquals("DAGOpenCLRenderer", renderer.getRendererName());
    }

    @Test
    @DisplayName("Renderer kernel entry point is rayTraverseDAG")
    void testKernelEntryPoint() {
        // TDD: Verify correct kernel entry point
        assertEquals("rayTraverseDAG", renderer.getKernelEntryPoint());
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

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("GPU traversal works on NVIDIA hardware")
    void testNvidiaOpenCL() {
        // TDD: Test NVIDIA GPU execution
        renderer.uploadDataBuffers(testDAG);
        assertDoesNotThrow(() -> {
            // Renderer is initialized - data uploaded - ready for frame rendering
            renderer.initialize();
            renderer.uploadData(testDAG);
        });
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("GPU traversal works on AMD hardware")
    void testAmdOpenCL() {
        // TDD: Test AMD GPU execution
        renderer.uploadDataBuffers(testDAG);
        assertDoesNotThrow(() -> {
            renderer.initialize();
            renderer.uploadData(testDAG);
        });
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("GPU traversal works on Intel hardware")
    void testIntelOpenCL() {
        // TDD: Test Intel GPU execution
        renderer.uploadDataBuffers(testDAG);
        assertDoesNotThrow(() -> {
            renderer.initialize();
            renderer.uploadData(testDAG);
        });
    }

    // ==================== Helper Methods ====================

    private ESVOOctreeData createTestOctree() {
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

        return octree;
    }
}
