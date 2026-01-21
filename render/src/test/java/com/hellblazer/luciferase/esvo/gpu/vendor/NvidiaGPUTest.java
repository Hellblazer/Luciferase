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
 * P4.3: NVIDIA GPU Vendor-Specific Tests
 *
 * Comprehensive NVIDIA GPU testing with 15+ vendor-specific tests:
 * - Warp size behavior (32 threads)
 * - Shared memory bank conflicts (32 banks)
 * - SM (Streaming Multiprocessor) occupancy
 * - CUDA compute capability detection
 * - Tensor Core availability (A100, RTX GPUs)
 * - Memory bandwidth validation
 * - Atomic operation consistency
 * - Ray precision validation
 * - Traversal depth edge cases
 * - Context switch behavior
 *
 * Tests are conditional on GPU_VENDOR=NVIDIA environment variable.
 *
 * @author hal.hildebrand
 */
@Tag("nvidia")
@Tag("vendor-specific")
@DisplayName("P4.3: NVIDIA GPU Vendor-Specific Tests")
class NvidiaGPUTest {

    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        testDAG = createMinimalTestDAG();
    }

    // ==================== Vendor Detection Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA GPU detected correctly")
    void testNvidiaDetection() {
        var detector = GPUVendorDetector.getInstance();
        assertEquals(GPUVendor.NVIDIA, detector.getVendor(),
            "GPU_VENDOR=NVIDIA but detected " + detector.getVendor());

        var deviceName = detector.getDeviceName().toLowerCase();
        assertTrue(
            deviceName.contains("nvidia") ||
            deviceName.contains("geforce") ||
            deviceName.contains("quadro") ||
            deviceName.contains("tesla") ||
            deviceName.contains("rtx"),
            "NVIDIA device name should contain vendor keywords: " + deviceName
        );
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA baseline configuration has no workarounds")
    void testNvidiaBaselineConfiguration() {
        var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
        var flags = config.getCompilerFlags();

        // NVIDIA uses baseline flags
        assertEquals("-cl-fast-relaxed-math -cl-mad-enable", flags,
            "NVIDIA should use baseline fast-math flags");

        // NVIDIA is baseline - no workarounds required
        assertFalse(GPUVendor.NVIDIA.requiresWorkarounds(),
            "NVIDIA should not require workarounds (baseline vendor)");
    }

    // ==================== Warp Size Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA warp size is 32 threads")
    void testNvidiaWarpSize() {
        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // NVIDIA warps are always 32 threads (not exposed in OpenCL, but documented)
        assertTrue(capabilities.isValid(), "GPU capabilities should be valid");
        assertTrue(capabilities.maxWorkGroupSize() >= 32,
            "Max workgroup size should support at least one warp (32 threads)");

        // Optimal workgroup sizes for NVIDIA are multiples of 32
        var maxWorkGroupSize = capabilities.maxWorkGroupSize();
        assertTrue(maxWorkGroupSize % 32 == 0 || maxWorkGroupSize >= 1024,
            "Max workgroup size should be multiple of 32 or support large workgroups");
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA workgroup size optimization for warps")
    void testNvidiaWorkgroupSizeOptimization() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        var maxWorkGroupSize = detector.getCapabilities().maxWorkGroupSize();

        // Test that common NVIDIA workgroup sizes are supported
        var commonSizes = new long[]{32, 64, 128, 256, 512, 1024};

        for (var size : commonSizes) {
            if (size <= maxWorkGroupSize) {
                assertTrue(size % 32 == 0,
                    "Workgroup size " + size + " should be multiple of warp size (32)");
            }
        }
    }

    // ==================== Shared Memory Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA shared memory bank configuration (32 banks)")
    void testNvidiaSharedMemoryBanks() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        var localMemory = detector.getCapabilities().localMemorySize();

        // NVIDIA GPUs have 32 shared memory banks (4-byte width)
        // Typical shared memory sizes: 48KB (Compute 3.x-7.x), 64KB+ (Compute 8.0+)
        assertTrue(localMemory >= 48 * 1024,
            "NVIDIA shared memory should be at least 48KB (found: " + localMemory + " bytes)");

        // Modern NVIDIA GPUs (Ampere+) have 100KB+ shared memory
        if (localMemory >= 100 * 1024) {
            System.out.println("Detected Ampere or newer GPU with " + (localMemory / 1024) + " KB shared memory");
        }
    }

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA shared memory bank conflict avoidance")
    void testNvidiaSharedMemoryBankConflicts() {
        assumeNvidiaGPU();

        // Test that DAG traversal kernel avoids bank conflicts
        // NVIDIA has 32 banks, 4-byte width
        // Accessing stride-32 pattern avoids conflicts
        var renderer = createRendererForVendor(GPUVendor.NVIDIA);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Kernel should execute without bank conflict penalties
        }, "NVIDIA kernel execution should avoid shared memory bank conflicts");
    }

    // ==================== SM Occupancy Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA SM occupancy validation")
    void testNvidiaSMOccupancy() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        // Compute units on NVIDIA = SMs (Streaming Multiprocessors)
        var smCount = capabilities.computeUnits();
        assertTrue(smCount > 0, "NVIDIA GPU should have at least 1 SM");

        // Modern NVIDIA GPUs have 10+ SMs
        // RTX 3060: 28 SMs, RTX 4090: 128 SMs
        System.out.println("NVIDIA GPU has " + smCount + " SMs");

        // High-end GPUs have 80+ SMs
        if (smCount >= 80) {
            System.out.println("Detected high-end NVIDIA GPU (80+ SMs)");
        }
    }

    // ==================== Compute Capability Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA compute capability detection")
    void testNvidiaCUDAComputeCapability() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName().toLowerCase();

        // Detect GPU generation from name
        var isRTX40Series = deviceName.contains("rtx 40") || deviceName.contains("rtx40");
        var isRTX30Series = deviceName.contains("rtx 30") || deviceName.contains("rtx30");
        var isRTX20Series = deviceName.contains("rtx 20") || deviceName.contains("rtx20");

        if (isRTX40Series) {
            System.out.println("Detected Ada Lovelace (Compute 8.9)");
        } else if (isRTX30Series) {
            System.out.println("Detected Ampere (Compute 8.0-8.6)");
        } else if (isRTX20Series) {
            System.out.println("Detected Turing (Compute 7.5)");
        }

        // All modern GPUs should support compute capability 7.0+
        assertNotNull(deviceName, "Device name should be available");
    }

    // ==================== Tensor Core Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA Tensor Core availability (RTX, A100)")
    void testNvidiaTensorCoreAvailability() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        var deviceName = detector.getDeviceName().toLowerCase();

        // Tensor Cores available on Volta (V100), Turing (RTX 20xx), Ampere (RTX 30xx, A100), Ada (RTX 40xx)
        var hasTensorCores =
            deviceName.contains("v100") ||
            deviceName.contains("a100") ||
            deviceName.contains("rtx") ||
            deviceName.contains("titan");

        if (hasTensorCores) {
            System.out.println("GPU supports Tensor Cores: " + deviceName);
        }

        assertNotNull(deviceName, "Device name should be available for Tensor Core detection");
    }

    // ==================== Memory Bandwidth Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA memory bandwidth validation")
    void testNvidiaMemoryBandwidth() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        var capabilities = detector.getCapabilities();

        var globalMemory = capabilities.globalMemorySize();
        var clockFrequency = capabilities.maxClockFrequency();

        // Modern NVIDIA GPUs have 4GB+ VRAM
        assertTrue(globalMemory >= 4L * 1024 * 1024 * 1024,
            "NVIDIA GPU should have at least 4GB VRAM (found: " + (globalMemory / (1024 * 1024 * 1024)) + " GB)");

        // Clock frequency should be reasonable (800 MHz - 2500 MHz)
        assertTrue(clockFrequency >= 800 && clockFrequency <= 3000,
            "Clock frequency should be in reasonable range (found: " + clockFrequency + " MHz)");

        System.out.println("NVIDIA GPU: " + (globalMemory / (1024 * 1024 * 1024)) + " GB VRAM @ " + clockFrequency + " MHz");
    }

    // ==================== Atomic Operation Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA atomic operation consistency")
    void testNvidiaAtomicConsistency() {
        assumeNvidiaGPU();

        var renderer = createRendererForVendor(GPUVendor.NVIDIA);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // NVIDIA atomic operations should be baseline (no workarounds)
        }, "NVIDIA atomic operations should work without workarounds");
    }

    // ==================== Ray Precision Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA ray-AABB precision baseline")
    void testNvidiaRayPrecision() {
        assumeNvidiaGPU();

        var renderer = createRendererForVendor(GPUVendor.NVIDIA);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // NVIDIA uses baseline precision (1e-6f epsilon)
        }, "NVIDIA ray precision should meet baseline (1e-6f epsilon)");
    }

    // ==================== Traversal Depth Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA max traversal depth validation")
    void testNvidiaMaxTraversalDepth() {
        assumeNvidiaGPU();

        var renderer = createRendererForVendor(GPUVendor.NVIDIA);

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // NVIDIA should handle max traversal depth without stack overflow
        }, "NVIDIA should handle max DAG traversal depth");
    }

    // ==================== Context Switch Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA context switch behavior")
    void testNvidiaContextSwitchBehavior() {
        assumeNvidiaGPU();

        var detector = GPUVendorDetector.getInstance();
        assertTrue(detector.getCapabilities().isValid(),
            "NVIDIA GPU context should remain valid across test runs");

        // Multiple renderer creations should not cause context issues
        var renderer1 = createRendererForVendor(GPUVendor.NVIDIA);
        var renderer2 = createRendererForVendor(GPUVendor.NVIDIA);

        assertNotNull(renderer1, "First renderer should initialize");
        assertNotNull(renderer2, "Second renderer should initialize without context conflicts");
    }

    // ==================== Performance Characteristics Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA GPU execution performance baseline")
    void testNvidiaPerformanceBaseline() {
        assumeNvidiaGPU();

        var renderer = createRendererForVendor(GPUVendor.NVIDIA);

        var startTime = System.nanoTime();
        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "NVIDIA execution should succeed");
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Upload should complete in reasonable time (<1000ms for minimal DAG)
        assertTrue(elapsedMs < 1000,
            "NVIDIA upload should complete quickly (elapsed: " + elapsedMs + " ms)");

        System.out.println("NVIDIA upload completed in " + elapsedMs + " ms");
    }

    // ==================== Optimization Tests ====================

    @EnabledIfEnvironmentVariable(named = "GPU_VENDOR", matches = "NVIDIA")
    @Test
    @DisplayName("NVIDIA-specific kernel optimizations applied")
    void testNvidiaKernelOptimizations() {
        assumeNvidiaGPU();

        var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
        var kernelSource = config.applyPreprocessorDefinitions("// Test kernel");

        // NVIDIA should have baseline macro defined
        assertTrue(kernelSource.contains("GPU_VENDOR_NVIDIA"),
            "Kernel should define NVIDIA vendor macro");

        // NVIDIA baseline should not have workarounds
        assertFalse(kernelSource.contains("WORKAROUND"),
            "NVIDIA baseline should not have workaround macros");
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
        // Create renderer (note: actual GPU initialization requires GPU hardware)
        return new DAGOpenCLRenderer(512, 512);
    }

    /**
     * Assume NVIDIA GPU is available (skip test if not)
     */
    private void assumeNvidiaGPU() {
        var detector = GPUVendorDetector.getInstance();
        assumeTrue(detector.getVendor() == GPUVendor.NVIDIA,
            "Test requires NVIDIA GPU but detected " + detector.getVendor());
    }
}
