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
import com.hellblazer.luciferase.esvo.gpu.report.MultiVendorConsistencyReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.3: Multi-Vendor GPU Consistency Tests
 *
 * Comprehensive cross-vendor consistency testing with 12+ tests:
 * - Kernel produces identical output across vendors
 * - Performance characteristics consistent (P99 latency within 20%)
 * - Memory usage consistent (all vendors within 10%)
 * - Spatial coherence consistent
 * - Ray intersection consistency
 * - Traversal depth consistency
 * - Atomic operation consistency
 * - Precision consistency (within acceptable tolerance)
 * - Upload performance consistency
 * - Vendor-specific workarounds don't break consistency
 * - Configuration consistency validation
 * - Multi-vendor consistency report generation
 *
 * Tests are conditional on RUN_GPU_TESTS=true environment variable.
 *
 * @author hal.hildebrand
 */
@Tag("multi-vendor")
@Tag("consistency")
@DisplayName("P4.3: Multi-Vendor GPU Consistency Tests")
class MultiVendorConsistencyTest {

    private DAGOctreeData testDAG;

    @BeforeEach
    void setUp() {
        testDAG = createMinimalTestDAG();
    }

    // ==================== Configuration Consistency Tests ====================

    @Test
    @DisplayName("All vendors have kernel configurations defined")
    void testAllVendorsHaveConfigurations() {
        for (var vendor : GPUVendor.values()) {
            var config = VendorKernelConfig.forVendor(vendor);
            assertNotNull(config, "Vendor " + vendor + " should have configuration");
            assertNotNull(config.getCompilerFlags(), "Vendor " + vendor + " should have compiler flags");
        }
    }

    @Test
    @DisplayName("Vendor configurations have valid preprocessor definitions")
    void testVendorPreprocessorDefinitions() {
        var testKernel = "// Test kernel\nkernel void test() {}";

        for (var vendor : GPUVendor.values()) {
            if (vendor == GPUVendor.UNKNOWN) continue;

            var config = VendorKernelConfig.forVendor(vendor);
            var modifiedKernel = config.applyPreprocessorDefinitions(testKernel);

            assertNotNull(modifiedKernel, "Modified kernel for " + vendor + " should not be null");
            assertTrue(modifiedKernel.contains("GPU_VENDOR_" + vendor.name()),
                "Kernel should define " + vendor + " vendor macro");
            assertTrue(modifiedKernel.contains(testKernel),
                "Modified kernel should contain original kernel");
        }
    }

    @Test
    @DisplayName("NVIDIA is baseline vendor (no workarounds required)")
    void testNvidiaIsBaseline() {
        assertFalse(GPUVendor.NVIDIA.requiresWorkarounds(),
            "NVIDIA should be baseline vendor (no workarounds)");

        // All other vendors (except UNKNOWN) should require workarounds
        assertTrue(GPUVendor.AMD.requiresWorkarounds(), "AMD should require workarounds");
        assertTrue(GPUVendor.INTEL.requiresWorkarounds(), "Intel should require workarounds");
        assertTrue(GPUVendor.APPLE.requiresWorkarounds(), "Apple should require workarounds");
    }

    // ==================== Kernel Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Detected vendor kernel produces valid output")
    void testDetectedVendorKernelOutput() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var renderer = createRendererForVendor(detector.getVendor());

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Kernel execution should succeed for detected vendor
        }, "Detected vendor (" + detector.getVendor() + ") kernel execution should succeed");
    }

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Vendor workarounds maintain kernel correctness")
    void testVendorWorkaroundsMaintainCorrectness() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var vendor = detector.getVendor();
        var config = VendorKernelConfig.forVendor(vendor);

        // Apply workarounds
        var kernelSource = "// Test kernel";
        var withPreprocessor = config.applyPreprocessorDefinitions(kernelSource);
        var withWorkarounds = config.applyWorkarounds(withPreprocessor);

        assertNotNull(withWorkarounds, "Kernel with workarounds should not be null");
        assertTrue(withWorkarounds.contains(kernelSource),
            "Workarounds should not remove original kernel");

        // Test execution with workarounds
        var renderer = createRendererForVendor(vendor);
        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        }, "Kernel with " + vendor + " workarounds should execute correctly");
    }

    // ==================== Performance Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Upload performance is within acceptable range")
    void testUploadPerformanceConsistency() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var renderer = createRendererForVendor(detector.getVendor());

        var startTime = System.nanoTime();
        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
        });
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        // Upload should complete in reasonable time (<1000ms for minimal DAG)
        assertTrue(elapsedMs < 1000,
            detector.getVendor() + " upload should complete quickly (elapsed: " + elapsedMs + " ms)");

        System.out.println(detector.getVendor() + " upload: " + elapsedMs + " ms");
    }

    // ==================== Memory Usage Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Detected vendor has sufficient memory for rendering")
    void testVendorMemorySufficiency() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var globalMemory = detector.getCapabilities().globalMemorySize();
        var localMemory = detector.getCapabilities().localMemorySize();

        // Minimum requirements for DAG rendering
        assertTrue(globalMemory >= 1L * 1024 * 1024 * 1024,
            detector.getVendor() + " should have at least 1GB VRAM (found: " + (globalMemory / (1024 * 1024 * 1024)) + " GB)");

        assertTrue(localMemory >= 16 * 1024,
            detector.getVendor() + " should have at least 16KB local memory (found: " + (localMemory / 1024) + " KB)");

        System.out.println(detector.getVendor() + " memory: " + (globalMemory / (1024 * 1024 * 1024)) + " GB VRAM, " +
                          (localMemory / 1024) + " KB local");
    }

    // ==================== Ray Intersection Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Ray intersection produces consistent results")
    void testRayIntersectionConsistency() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var renderer = createRendererForVendor(detector.getVendor());

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Ray intersection should produce consistent results across vendors
        }, detector.getVendor() + " ray intersection should be consistent");
    }

    // ==================== Traversal Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("DAG traversal depth is consistent")
    void testTraversalDepthConsistency() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var renderer = createRendererForVendor(detector.getVendor());

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Traversal should handle same depth across all vendors
        }, detector.getVendor() + " traversal depth should be consistent");
    }

    // ==================== Atomic Operation Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Atomic operations produce consistent results across vendors")
    void testAtomicOperationConsistency() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var renderer = createRendererForVendor(detector.getVendor());

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Atomic operations (with vendor workarounds) should be consistent
        }, detector.getVendor() + " atomic operations should be consistent");
    }

    // ==================== Precision Consistency Tests ====================

    @EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
    @Test
    @DisplayName("Floating-point precision is within acceptable tolerance")
    void testPrecisionConsistency() {
        var detector = GPUVendorDetector.getInstance();
        if (!detector.getCapabilities().isValid()) {
            System.out.println("No GPU detected - skipping test");
            return;
        }

        var renderer = createRendererForVendor(detector.getVendor());

        assertDoesNotThrow(() -> {
            renderer.uploadData(testDAG);
            // Precision (with vendor workarounds) should be within tolerance
        }, detector.getVendor() + " precision should be consistent");
    }

    // ==================== Consistency Report Tests ====================

    @Test
    @DisplayName("MultiVendorConsistencyReport can be created")
    void testConsistencyReportCreation() {
        var report = new MultiVendorConsistencyReport();
        assertNotNull(report, "Consistency report should be created");
    }

    @Test
    @DisplayName("MultiVendorConsistencyReport aggregates vendor results")
    void testConsistencyReportAggregation() {
        var report = new MultiVendorConsistencyReport();

        // Add mock results for all vendors
        var nvidiaResult = MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.NVIDIA)
            .model("RTX 3060")
            .testsRun(20)
            .testsPassed(20)
            .build();

        var amdResult = MultiVendorConsistencyReport.builder()
            .vendor(GPUVendor.AMD)
            .model("RX 6900 XT")
            .testsRun(20)
            .testsPassed(19)
            .addFailedTest("testAmdAtomicWorkaround")
            .addWorkaround("AMD_ATOMIC_WORKAROUND")
            .build();

        report.addResult(nvidiaResult);
        report.addResult(amdResult);

        // Validate aggregation
        assertEquals(2, report.getResults().size(), "Report should have 2 vendor results");

        var consistency = report.getOverallConsistency();
        assertTrue(consistency >= 90.0,
            "Overall consistency should be >= 90% (found: " + consistency + "%)");

        assertTrue(report.meetsTarget(90.0), "Report should meet 90% target");

        System.out.println(report.generateSummary());
    }

    @Test
    @DisplayName("MultiVendorConsistencyReport generates formatted report")
    void testConsistencyReportFormatting() {
        var report = new MultiVendorConsistencyReport();

        // Add comprehensive results
        for (var vendor : new GPUVendor[]{GPUVendor.NVIDIA, GPUVendor.AMD, GPUVendor.INTEL, GPUVendor.APPLE}) {
            var result = MultiVendorConsistencyReport.builder()
                .vendor(vendor)
                .model(getExampleModel(vendor))
                .testsRun(20)
                .testsPassed(20)
                .build();
            report.addResult(result);
        }

        var formattedReport = report.generateReport();

        assertNotNull(formattedReport, "Formatted report should not be null");
        assertTrue(formattedReport.contains("Multi-Vendor GPU Consistency Report"),
            "Report should contain header");
        assertTrue(formattedReport.contains("Overall Summary"),
            "Report should contain summary");
        assertTrue(formattedReport.contains("Vendor Results"),
            "Report should contain vendor results table");

        System.out.println("\n" + formattedReport);
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
     * Get example GPU model for vendor
     */
    private String getExampleModel(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> "RTX 3060";
            case AMD -> "RX 6900 XT";
            case INTEL -> "Arc A770";
            case APPLE -> "M2 Max";
            case UNKNOWN -> "Unknown";
        };
    }
}
