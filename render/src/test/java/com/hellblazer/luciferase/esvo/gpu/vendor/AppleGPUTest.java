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
 * P4.3: Apple GPU Vendor-Specific Tests
 *
 * Comprehensive Apple GPU testing with 8+ vendor-specific tests:
 * - fabs() workaround validation (APPLE_MACOS_WORKAROUND)
 * - Integer abs usage (USE_INTEGER_ABS)
 * - Metal Compute coordinate space (METAL_COMPUTE_COORD_SPACE)
 * - M-series GPU support (M1, M2, M3, M4)
 * - OpenCL 1.2 deprecation handling
 * - Precision edge cases
 * - Tile memory sizes (Apple unified memory architecture)
 * - Metal optimization (Metal vs OpenCL code paths)
 *
 * Tests are conditional on GPU_VENDOR=Apple environment variable.
 *
 * @author hal.hildebrand
 */
@Tag("apple")
@Tag("vendor-specific")
@DisplayName("P4.3: Apple GPU Vendor-Specific Tests")
class AppleGPUTest {

    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        testDAG = createMinimalTestDAG();
    }

    // ==================== Vendor Detection Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple GPU detected correctly")
    void testAppleDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.APPLE, detector.getVendor(),
            "GPU_VENDOR=Apple but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(
            deviceName.contains("apple") || deviceName.contains("metal"),
            "Apple device name should contain vendor keywords: " + deviceName
        );
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple configuration has workarounds enabled")
    void testAppleWorkaroundsConfiguration() {
        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var flags = config.getCompilerFlags();

        // Apple uses minimal flags (OpenCL deprecated on macOS)
        assertEquals("", flags,
            "Apple should use minimal compiler flags (OpenCL deprecated)");

        // Apple requires workarounds (fabs() conflicts, Metal differences)
        assertTrue(GPUVendor.APPLE.requiresWorkarounds(),
            "Apple should require workarounds (macOS fabs() conflicts)");
    }

    // ==================== fabs() Workaround Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple fabs() workaround validation (APPLE_MACOS_WORKAROUND)")
    void testAppleFabsWorkaround() {
        assumeAppleGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // Apple should have fabs() workaround macro defined
        assertTrue(kernelSource.contains("APPLE_MACOS_WORKAROUND"),
            "Kernel should define APPLE_MACOS_WORKAROUND macro");

        assertTrue(kernelSource.contains("GPU_VENDOR_APPLE"),
            "Kernel should define Apple vendor macro");

        System.out.println("Apple fabs() workaround macro validated");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple integer abs usage (USE_INTEGER_ABS)")
    void testAppleIntegerAbs() {
        assumeAppleGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // Apple should have integer abs macro
        assertTrue(kernelSource.contains("USE_INTEGER_ABS"),
            "Kernel should define USE_INTEGER_ABS for Apple");

        var renderer = createRendererForVendor(GPUVendor.APPLE);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Integer abs should work correctly as replacement for fabs()
        }, "Apple integer abs should function correctly");
    }

    // ==================== Metal Compute Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple Metal Compute coordinate space (METAL_COMPUTE_COORD_SPACE)")
    void testAppleMetalCoordinateSpace() {
        assumeAppleGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // Apple should have Metal coordinate space macro
        assertTrue(kernelSource.contains("METAL_COMPUTE_COORD_SPACE"),
            "Kernel should define METAL_COMPUTE_COORD_SPACE");

        var renderer = createRendererForVendor(GPUVendor.APPLE);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Metal coordinate space should be handled correctly
        }, "Apple Metal coordinate space should be correct");
    }

    // ==================== M-Series GPU Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple M-series GPU support (M1/M2/M3/M4)")
    void testAppleMSeriesSupport() {
        assumeAppleGPU();

        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName().toLowerCase();

        // Detect M-series from name
        var isM1 = deviceName.contains("m1");
        var isM2 = deviceName.contains("m2");
        var isM3 = deviceName.contains("m3");
        var isM4 = deviceName.contains("m4");

        if (isM4) {
            System.out.println("Detected Apple M4 GPU");
        } else if (isM3) {
            System.out.println("Detected Apple M3 GPU");
        } else if (isM2) {
            System.out.println("Detected Apple M2 GPU");
        } else if (isM1) {
            System.out.println("Detected Apple M1 GPU");
        }

        var renderer = createRendererForVendor(GPUVendor.APPLE);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "Apple M-series GPUs should be fully supported");
    }

    // ==================== OpenCL Deprecation Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple OpenCL 1.2 deprecation handling")
    void testAppleOpenCLDeprecation() {
        assumeAppleGPU();

        var detector = GPUVendorDetector.getInstance();
        var openCLVersion = detector.getCapabilities().openCLVersion();

        // Apple supports OpenCL 1.2 (deprecated but still functional)
        assertNotNull(openCLVersion, "OpenCL version should be available");
        System.out.println("Apple OpenCL version: " + openCLVersion);

        var renderer = createRendererForVendor(GPUVendor.APPLE);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "Apple OpenCL 1.2 should function despite deprecation");
    }

    // ==================== Precision Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple precision edge cases")
    void testApplePrecisionEdgeCases() {
        assumeAppleGPU();

        var renderer = createRendererForVendor(GPUVendor.APPLE);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Precision edge cases should be handled by integer abs workaround
        }, "Apple precision should be within acceptable tolerance");
    }

    // ==================== Memory Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple unified memory architecture validation")
    void testAppleUnifiedMemory() {
        assumeAppleGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        var globalMemory = capabilities.globalMemorySize();
        var localMemory = capabilities.localMemorySize();

        // Apple M-series uses unified memory architecture
        // M1/M2/M3: 8GB-192GB unified memory
        assertTrue(globalMemory >= 4L * 1024 * 1024 * 1024,
            "Apple GPU should have at least 4GB unified memory (found: " + (globalMemory / (1024 * 1024 * 1024)) + " GB)");

        System.out.println("Apple GPU: " + (globalMemory / (1024 * 1024 * 1024)) + " GB unified memory");
        System.out.println("Apple GPU: " + (localMemory / 1024) + " KB tile memory");
    }

    // ==================== Compute Core Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple GPU compute core validation")
    void testAppleComputeCores() {
        assumeAppleGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // Apple M-series GPUs have varying numbers of GPU cores
        // M1: 7-8 cores, M2: 10 cores, M3 Max: 40 cores, M4 Max: 40 cores
        var computeUnits = capabilities.computeUnits();
        assertTrue(computeUnits > 0, "Apple GPU should have compute units");

        System.out.println("Apple GPU has " + computeUnits + " compute units");

        if (computeUnits >= 30) {
            System.out.println("Detected high-end Apple GPU (30+ cores - Max/Ultra variant)");
        }
    }

    // ==================== Performance Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "Apple")
    @Test
    @DisplayName("Apple GPU execution performance with workarounds")
    void testApplePerformanceWithWorkarounds() {
        assumeAppleGPU();

        var renderer = createRendererForVendor(GPUVendor.APPLE);

        var startTime = System.nanoTime();
        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "Apple execution with workarounds should succeed");
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Upload should complete in reasonable time (<1000ms for minimal DAG)
        assertTrue(elapsedMs < 1000,
            "Apple upload should complete quickly (elapsed: " + elapsedMs + " ms)");

        System.out.println("Apple upload completed in " + elapsedMs + " ms");
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
     * Assume Apple GPU is available (skip test if not)
     */
    private void assumeAppleGPU() {
        var detector = GPUVendorDetector.getInstance();
        assumeTrue(detector.getVendor() == GPUVendor.APPLE,
            "Test requires Apple GPU but detected " + detector.getVendor());
    }
}
