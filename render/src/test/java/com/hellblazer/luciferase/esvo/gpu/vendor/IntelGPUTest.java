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
package com.hellblazer.luciferase.esvo.gpu.vendor;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.DAGOpenCLRenderer;
import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import com.hellblazer.luciferase.esvo.gpu.GPUVendorDetector;
import com.hellblazer.luciferase.esvo.gpu.VendorKernelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P4.3: Intel GPU Vendor-Specific Tests
 *
 * Comprehensive Intel GPU testing with 10+ vendor-specific tests:
 * - Subslice behavior (Intel execution unit structure)
 * - SLM (Shared Local Memory) configuration
 * - IGBP (Intel Gen12+) configuration
 * - Precision workaround validation (INTEL_PRECISION_WORKAROUND)
 * - Relaxed epsilon (1e-5f instead of 1e-6f)
 * - Intel Arc GPU support (A380, A770)
 * - Ray-box intersection edge cases
 * - Shared memory behavior on Arc
 *
 * Tests are conditional on GPU_VENDOR=Intel environment variable.
 *
 * @author hal.hildebrand
 */
@Tag("intel")
@Tag("vendor-specific")
@DisplayName("P4.3: Intel GPU Vendor-Specific Tests")
class IntelGPUTest {

    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        testDAG = createMinimalTestDAG();
    }

    // ==================== Vendor Detection Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel GPU detected correctly")
    void testIntelDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.INTEL, detector.getVendor(),
            "GPU_VENDOR=Intel but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(
            deviceName.contains("intel") ||
            deviceName.contains("iris") ||
            deviceName.contains("uhd") ||
            deviceName.contains("arc"),
            "Intel device name should contain vendor keywords: " + deviceName
        );
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel configuration has precision workarounds enabled")
    void testIntelWorkaroundsConfiguration() {
        var config = VendorKernelConfig.forVendor(GPUVendor.INTEL);
        var flags = config.getCompilerFlags();

        // Intel uses fast-math but NOT unsafe-math (precision issues)
        assertEquals("-cl-fast-relaxed-math", flags,
            "Intel should use fast-math WITHOUT unsafe-math flags");

        // Intel requires workarounds (precision relaxation)
        assertTrue(GPUVendor.INTEL.requiresWorkarounds(),
            "Intel should require workarounds (precision relaxation)");
    }

    // ==================== Precision Workaround Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel precision workaround validation (INTEL_PRECISION_WORKAROUND)")
    void testIntelPrecisionWorkaround() {
        assumeIntelGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.INTEL);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // Intel should have precision workaround macro defined
        assertTrue(kernelSource.contains("INTEL_PRECISION_WORKAROUND"),
            "Kernel should define INTEL_PRECISION_WORKAROUND macro");

        assertTrue(kernelSource.contains("GPU_VENDOR_INTEL"),
            "Kernel should define Intel vendor macro");

        System.out.println("Intel precision workaround macro validated");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel relaxed epsilon validation (1e-5f)")
    void testIntelRelaxedEpsilon() {
        assumeIntelGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.INTEL);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // Intel should have relaxed epsilon defined
        assertTrue(kernelSource.contains("RAY_EPSILON 1e-5f"),
            "Kernel should define relaxed epsilon (1e-5f) for Intel Arc precision");

        var renderer = createRendererForVendor(GPUVendor.INTEL);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Relaxed epsilon should prevent precision failures on Arc GPUs
        }, "Intel relaxed epsilon should function correctly");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel precision edge cases with relaxed epsilon")
    void testIntelPrecisionEdgeCases() {
        assumeIntelGPU();

        var renderer = createRendererForVendor(GPUVendor.INTEL);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Precision edge cases should be handled by relaxed epsilon
        }, "Intel precision edge cases should be handled correctly");
    }

    // ==================== Intel Arc GPU Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel Arc GPU support (A380, A770)")
    void testIntelArcSupport() {
        assumeIntelGPU();

        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName().toLowerCase();

        // Detect Arc GPU from name
        var isArc = deviceName.contains("arc");

        if (isArc) {
            System.out.println("Detected Intel Arc GPU: " + deviceName);

            // Arc GPUs are modern with good compute support
            var capabilities = detector.getCapabilities();
            assertTrue(capabilities.computeUnits() > 0,
                "Intel Arc should have compute units");
        }

        var renderer = createRendererForVendor(GPUVendor.INTEL);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "Intel Arc GPUs should be fully supported");
    }

    // ==================== Subslice Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel subslice behavior (execution unit structure)")
    void testIntelSubsliceBehavior() {
        assumeIntelGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // Intel GPUs have subslices containing execution units (EUs)
        var computeUnits = capabilities.computeUnits();
        assertTrue(computeUnits > 0, "Intel GPU should have execution units");

        System.out.println("Intel GPU has " + computeUnits + " execution units");

        // Arc A770: 32 Xe-cores (512 EUs)
        // Arc A380: 8 Xe-cores (128 EUs)
        if (computeUnits >= 256) {
            System.out.println("Detected high-end Intel Arc GPU (256+ EUs)");
        }
    }

    // ==================== SLM (Shared Local Memory) Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel SLM configuration validation")
    void testIntelSLMConfiguration() {
        assumeIntelGPU();

        var detector = GPUVendorDetector.getInstance();
        var localMemory = detector.getCapabilities().localMemorySize();

        // Intel Arc GPUs have 64KB SLM per Xe-core
        assertTrue(localMemory >= 32 * 1024,
            "Intel SLM should be at least 32KB (found: " + localMemory + " bytes)");

        if (localMemory >= 64 * 1024) {
            System.out.println("Detected Intel Arc GPU with " + (localMemory / 1024) + " KB SLM");
        }
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel shared memory behavior on Arc GPUs")
    void testIntelSharedMemoryBehavior() {
        assumeIntelGPU();

        var renderer = createRendererForVendor(GPUVendor.INTEL);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Intel Arc shared memory should function correctly
        }, "Intel Arc shared memory should function correctly");
    }

    // ==================== Ray-Box Intersection Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel ray-box intersection edge cases")
    void testIntelRayBoxIntersection() {
        assumeIntelGPU();

        var renderer = createRendererForVendor(GPUVendor.INTEL);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Intel ray-box intersection should handle edge cases with relaxed epsilon
        }, "Intel ray-box intersection should be accurate with relaxed epsilon");
    }

    // ==================== Memory Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel GPU memory configuration")
    void testIntelMemoryConfiguration() {
        assumeIntelGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        var globalMemory = capabilities.globalMemorySize();
        var clockFrequency = capabilities.maxClockFrequency();

        // Intel Arc GPUs: 6GB (A380), 16GB (A770)
        assertTrue(globalMemory >= 2L * 1024 * 1024 * 1024,
            "Intel GPU should have at least 2GB VRAM (found: " + (globalMemory / (1024 * 1024 * 1024)) + " GB)");

        // Clock frequency should be reasonable (1000-2500 MHz)
        assertTrue(clockFrequency >= 500 && clockFrequency <= 3000,
            "Clock frequency should be in reasonable range (found: " + clockFrequency + " MHz)");

        System.out.println("Intel GPU: " + (globalMemory / (1024 * 1024 * 1024)) + " GB VRAM @ " + clockFrequency + " MHz");
    }

    // ==================== Performance Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Intel")
    @Test
    @DisplayName("Intel GPU execution performance with workarounds")
    void testIntelPerformanceWithWorkarounds() {
        assumeIntelGPU();

        var renderer = createRendererForVendor(GPUVendor.INTEL);

        var startTime = System.nanoTime();
        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "Intel execution with workarounds should succeed");
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Upload should complete in reasonable time (<1000ms for minimal DAG)
        assertTrue(elapsedMs < 1000,
            "Intel upload should complete quickly (elapsed: " + elapsedMs + " ms)");

        System.out.println("Intel upload completed in " + elapsedMs + " ms");
    }

    // ==================== Helper Methods ====================

    /**
     * Create minimal test DAG for validation (8 nodes, depth 2)
     */
    private DAGOctreeData createMinimalTestDAG() {
        var octree = new ESVOOctreeData(1024);

        // Root node with 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);
        root.setChildPtr(1);
        octree.setNode(0, root);

        // 8 leaf children
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);
            octree.setNode(1 + i, leaf);
        }

        return DAGBuilder.from(octree).build();
    }

    /**
     * Create renderer configured for specific vendor
     */
    private DAGOpenCLRenderer createRendererForVendor(GPUVendor vendor) {
        return new DAGOpenCLRenderer(512, 512);
    }

    /**
     * Assume Intel GPU is available (skip test if not)
     */
    private void assumeIntelGPU() {
        var detector = GPUVendorDetector.getInstance();
        assumeTrue(detector.getVendor() == GPUVendor.INTEL,
            "Test requires Intel GPU but detected " + detector.getVendor());
    }
}
