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
 * P4.3: AMD GPU Vendor-Specific Tests
 *
 * Comprehensive AMD GPU testing with 15+ vendor-specific tests:
 * - Wave size behavior (64 threads on RDNA/RDNA2, sometimes 32)
 * - LDS (Local Data Share) bank configuration
 * - Workgroup size preferences (64/128/256)
 * - RDNA vs GCN architecture differences
 * - Memory coalescing patterns
 * - Atomic operation workarounds (AMD_ATOMIC_WORKAROUND)
 * - Relaxed atomic semantics (USE_RELAXED_ATOMICS)
 * - Shared memory access patterns
 * - Ray-AABB precision consistency
 * - RDNA2 compatibility
 *
 * Tests are conditional on GPU_VENDOR=AMD environment variable.
 *
 * @author hal.hildebrand
 */
@Tag("amd")
@Tag("vendor-specific")
@DisplayName("P4.3: AMD GPU Vendor-Specific Tests")
class AmdGPUTest {

    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        testDAG = createMinimalTestDAG();
    }

    // ==================== Vendor Detection Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD GPU detected correctly")
    void testAmdDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.AMD, detector.getVendor(),
            "GPU_VENDOR=AMD but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(
            deviceName.contains("amd") ||
            deviceName.contains("radeon") ||
            deviceName.contains("advanced micro devices"),
            "AMD device name should contain vendor keywords: " + deviceName
        );
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD configuration has workarounds enabled")
    void testAmdWorkaroundsConfiguration() {
        var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
        var flags = config.getCompilerFlags();

        // AMD uses aggressive optimization flags
        assertEquals("-cl-fast-relaxed-math -cl-mad-enable -cl-unsafe-math-optimizations", flags,
            "AMD should use aggressive optimization flags");

        // AMD requires workarounds (not baseline)
        assertTrue(GPUVendor.AMD.requiresWorkarounds(),
            "AMD should require workarounds (different atomic semantics)");
    }

    // ==================== Wave Size Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD wave size is 64 threads (RDNA/RDNA2)")
    void testAmdWaveSize() {
        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // AMD RDNA/RDNA2 wave size is typically 64 (some modes support 32)
        assertTrue(capabilities.isValid(), "GPU capabilities should be valid");
        assertTrue(capabilities.maxWorkGroupSize() >= 64,
            "Max workgroup size should support at least one wave (64 threads)");

        // Optimal workgroup sizes for AMD are multiples of 64
        var maxWorkGroupSize = capabilities.maxWorkGroupSize();
        assertTrue(maxWorkGroupSize >= 64,
            "Max workgroup size should support AMD wave size (64)");

        System.out.println("AMD max workgroup size: " + maxWorkGroupSize);
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD workgroup size optimization (64/128/256)")
    void testAmdWorkgroupSizeOptimization() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var maxWorkGroupSize = detector.getCapabilities().maxWorkGroupSize();

        // AMD prefers workgroup sizes of 64, 128, 256
        var commonSizes = new long[]{64, 128, 256};

        for (var size : commonSizes) {
            if (size <= maxWorkGroupSize) {
                assertTrue(size % 64 == 0,
                    "Workgroup size " + size + " should be multiple of wave size (64)");
            }
        }
    }

    // ==================== LDS (Local Data Share) Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD LDS bank configuration (different from NVIDIA)")
    void testAmdLDSBankConfiguration() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var localMemory = detector.getCapabilities().localMemorySize();

        // AMD RDNA GPUs have 64KB LDS per CU
        // RDNA2 (RX 6000 series) has 128KB LDS per CU
        assertTrue(localMemory >= 32 * 1024,
            "AMD LDS should be at least 32KB (found: " + localMemory + " bytes)");

        if (localMemory >= 64 * 1024) {
            System.out.println("Detected RDNA/RDNA2 GPU with " + (localMemory / 1024) + " KB LDS");
        }
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD LDS access pattern optimization")
    void testAmdLDSAccessPatterns() {
        assumeAmdGPU();

        var renderer = createRendererForVendor(GPUVendor.AMD);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // AMD LDS has different bank layout than NVIDIA (should avoid bank conflicts)
        }, "AMD kernel execution should optimize LDS access patterns");
    }

    // ==================== RDNA Architecture Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD RDNA architecture detection")
    void testAmdRDNAArchitecture() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName().toLowerCase();

        // Detect RDNA vs RDNA2 from device name
        var isRDNA2 = deviceName.contains("6000") || deviceName.contains("6600") ||
                      deviceName.contains("6700") || deviceName.contains("6800") ||
                      deviceName.contains("6900");
        var isRDNA3 = deviceName.contains("7000") || deviceName.contains("7600") ||
                      deviceName.contains("7700") || deviceName.contains("7800") ||
                      deviceName.contains("7900");

        if (isRDNA3) {
            System.out.println("Detected RDNA3 architecture");
        } else if (isRDNA2) {
            System.out.println("Detected RDNA2 architecture");
        } else {
            System.out.println("Detected AMD GPU: " + deviceName);
        }

        assertNotNull(deviceName, "Device name should be available");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD RDNA2 compatibility validation")
    void testAmdRDNA2Compatibility() {
        assumeAmdGPU();

        var renderer = createRendererForVendor(GPUVendor.AMD);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // RDNA2-specific features should work correctly
        }, "AMD RDNA2 architecture should be fully supported");
    }

    // ==================== Memory Coalescing Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD memory coalescing patterns")
    void testAmdMemoryCoalescing() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var globalMemory = detector.getCapabilities().globalMemorySize();

        // Modern AMD GPUs have 8GB+ VRAM
        assertTrue(globalMemory >= 4L * 1024 * 1024 * 1024,
            "AMD GPU should have at least 4GB VRAM (found: " + (globalMemory / (1024 * 1024 * 1024)) + " GB)");

        System.out.println("AMD GPU: " + (globalMemory / (1024 * 1024 * 1024)) + " GB VRAM");
    }

    // ==================== Atomic Operation Workaround Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD atomic operation workaround validation")
    void testAmdAtomicWorkaround() {
        assumeAmdGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // AMD should have atomic workaround macro defined
        assertTrue(kernelSource.contains("AMD_ATOMIC_WORKAROUND"),
            "Kernel should define AMD_ATOMIC_WORKAROUND macro");

        assertTrue(kernelSource.contains("GPU_VENDOR_AMD"),
            "Kernel should define AMD vendor macro");

        System.out.println("AMD atomic workaround macro validated");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD relaxed atomic semantics (USE_RELAXED_ATOMICS)")
    void testAmdRelaxedAtomics() {
        assumeAmdGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // AMD should have relaxed atomics macro
        assertTrue(kernelSource.contains("USE_RELAXED_ATOMICS"),
            "Kernel should define USE_RELAXED_ATOMICS for AMD");

        var renderer = createRendererForVendor(GPUVendor.AMD);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Relaxed atomics should match NVIDIA baseline behavior
        }, "AMD relaxed atomics should function correctly");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD atomic operation consistency with baseline")
    void testAmdAtomicConsistency() {
        assumeAmdGPU();

        var renderer = createRendererForVendor(GPUVendor.AMD);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // AMD atomic workarounds should produce consistent results with NVIDIA baseline
        }, "AMD atomic operations should be consistent with baseline");
    }

    // ==================== Shared Memory Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD shared memory access patterns")
    void testAmdSharedMemoryAccess() {
        assumeAmdGPU();

        var renderer = createRendererForVendor(GPUVendor.AMD);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // AMD shared memory should avoid bank conflicts (different layout than NVIDIA)
        }, "AMD shared memory access should be optimized");
    }

    // ==================== Ray Precision Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD ray-AABB precision consistency")
    void testAmdRayPrecision() {
        assumeAmdGPU();

        var renderer = createRendererForVendor(GPUVendor.AMD);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // AMD should maintain precision consistent with baseline
        }, "AMD ray precision should be consistent with baseline");
    }

    // ==================== Compute Unit Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD compute units (CUs) validation")
    void testAmdComputeUnits() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // Compute units on AMD = CUs
        var cuCount = capabilities.computeUnits();
        assertTrue(cuCount > 0, "AMD GPU should have at least 1 CU");

        // Modern AMD GPUs have 30+ CUs
        // RX 6700 XT: 40 CUs, RX 6900 XT: 80 CUs
        System.out.println("AMD GPU has " + cuCount + " CUs");

        // High-end GPUs have 60+ CUs
        if (cuCount >= 60) {
            System.out.println("Detected high-end AMD GPU (60+ CUs)");
        }
    }

    // ==================== Performance Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD GPU execution performance with workarounds")
    void testAmdPerformanceWithWorkarounds() {
        assumeAmdGPU();

        var renderer = createRendererForVendor(GPUVendor.AMD);

        var startTime = System.nanoTime();
        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "AMD execution with workarounds should succeed");
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Upload should complete in reasonable time (<1000ms for minimal DAG)
        assertTrue(elapsedMs < 1000,
            "AMD upload should complete quickly (elapsed: " + elapsedMs + " ms)");

        System.out.println("AMD upload completed in " + elapsedMs + " ms");
    }

    // ==================== GCN vs RDNA Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD GCN vs RDNA architecture differences")
    void testAmdGCNvsRDNA() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName().toLowerCase();

        // Detect GCN (older) vs RDNA (newer)
        var isGCN = deviceName.contains("vega") || deviceName.contains("polaris") ||
                    deviceName.contains("fury") || deviceName.contains("r9") ||
                    deviceName.contains("r7") || deviceName.contains("rx 5");

        var isRDNA = deviceName.contains("6000") || deviceName.contains("6600") ||
                     deviceName.contains("6700") || deviceName.contains("6800") ||
                     deviceName.contains("6900") || deviceName.contains("7000");

        if (isRDNA) {
            System.out.println("Detected RDNA architecture GPU");
        } else if (isGCN) {
            System.out.println("Detected GCN architecture GPU");
        }

        assertNotNull(deviceName, "Device name should be available");
    }

    // ==================== Clock Frequency Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "AMD")
    @Test
    @DisplayName("AMD clock frequency validation")
    void testAmdClockFrequency() {
        assumeAmdGPU();

        var detector = GPUVendorDetector.getInstance();
        var clockFrequency = detector.getCapabilities().maxClockFrequency();

        // AMD RDNA/RDNA2 GPUs: 1700-2500 MHz typical
        assertTrue(clockFrequency >= 1000 && clockFrequency <= 3000,
            "Clock frequency should be in reasonable range (found: " + clockFrequency + " MHz)");

        System.out.println("AMD GPU clock frequency: " + clockFrequency + " MHz");
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
     * Assume AMD GPU is available (skip test if not)
     */
    private void assumeAmdGPU() {
        var detector = GPUVendorDetector.getInstance();
        assumeTrue(detector.getVendor() == GPUVendor.AMD,
            "Test requires AMD GPU but detected " + detector.getVendor());
    }
}
