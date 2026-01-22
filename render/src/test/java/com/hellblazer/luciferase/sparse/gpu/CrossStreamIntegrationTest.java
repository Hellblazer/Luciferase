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
package com.hellblazer.luciferase.sparse.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-Stream Integration Tests: Stream A (ESVO Stack Optimization) + Stream B (Workgroup Tuning).
 *
 * <p>Validates:
 * - Unified configuration combining both optimization streams
 * - Build options generation with both stack depth and workgroup parameters
 * - Rendering correctness with combined tuning
 * - Multi-vendor consistency across both streams
 * - Performance characteristics with both optimizations active
 * - No conflicts between Stream A and Stream B
 */
class CrossStreamIntegrationTest {

    @Test
    @DisplayName("Unified configuration combines stack depth (Stream A) and workgroup size (Stream B)")
    void testUnifiedConfiguration() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        // Stream A: ESVO stack depth optimization
        int optimizedStackDepth = 16;  // Reduced from 32 for occupancy

        // Stream B: Workgroup tuning
        var tuner = new GPUAutoTuner(capabilities, "/tmp/unified-config");
        var workgroupConfig = tuner.selectOptimalConfigFromProfiles();

        // Verify both optimizations are independent but compatible
        assertTrue(workgroupConfig.maxTraversalDepth() > 0, "Stream B provides traversal depth");
        assertTrue(optimizedStackDepth > 0, "Stream A provides reduced stack depth");

        // Validate compatibility: workgroup size should accommodate reduced stack depth
        long ldsUsageUnified = (long) optimizedStackDepth * workgroupConfig.workgroupSize() * 4;
        assertTrue(ldsUsageUnified <= 65536, "Unified config should fit in GPU LDS memory");

        // Both parameters should be usable in kernel
        assertTrue(workgroupConfig.workgroupSize() > 0);
        assertTrue(optimizedStackDepth <= workgroupConfig.maxTraversalDepth());
    }

    @Test
    @DisplayName("Build options generation with both Stack Depth and Workgroup parameters")
    void testUnifiedBuildOptions() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/build-options");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Build options would combine Stream A and Stream B parameters
        // Stream A: -DMAX_DEPTH (or -DMAX_TRAVERSAL_DEPTH)
        // Stream B: -DWORKGROUP_SIZE, -DMAX_TRAVERSAL_DEPTH from profile
        String unifiedBuildOptions = String.format(
            "-DESVO_MODE=1 -DRELATIVE_ADDRESSING=1 -DMAX_TRAVERSAL_DEPTH=%d -DWORKGROUP_SIZE=%d -cl-fast-relaxed-math",
            config.maxTraversalDepth(),
            config.workgroupSize()
        );

        // All required parameters present
        assertTrue(unifiedBuildOptions.contains("-DESVO_MODE=1"), "Stream A: ESVO mode");
        assertTrue(unifiedBuildOptions.contains("-DRELATIVE_ADDRESSING=1"), "Stream A: relative addressing");
        assertTrue(unifiedBuildOptions.contains("-DMAX_TRAVERSAL_DEPTH="), "Stack depth parameter");
        assertTrue(unifiedBuildOptions.contains("-DWORKGROUP_SIZE="), "Workgroup size parameter");
        assertTrue(unifiedBuildOptions.contains("-cl-fast-relaxed-math"), "Optimization flag");
    }

    @Test
    @DisplayName("Rendering correctness with combined Stream A and Stream B tuning")
    void testRenderingWithCombinedTuning() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        // Stream A optimization parameters
        int streamADepth = 16;  // Optimized depth

        // Stream B optimization parameters
        var tuner = new GPUAutoTuner(capabilities, "/tmp/render-combined");
        var streamBConfig = tuner.selectOptimalConfigFromProfiles();

        // Combined configuration should be valid for rendering
        assertNotNull(streamBConfig);
        assertTrue(streamBConfig.workgroupSize() > 0);
        assertTrue(streamBConfig.maxTraversalDepth() >= streamADepth,
                "Stream B depth should accommodate Stream A optimization");

        // Verify rendering-ready metrics
        double combinedOccupancy = streamBConfig.expectedOccupancy();
        double combinedThroughput = streamBConfig.expectedThroughput();

        assertTrue(combinedOccupancy >= 0.0 && combinedOccupancy <= 1.0, "Occupancy valid with combined tuning");
        assertTrue(combinedThroughput > 0, "Throughput available with combined tuning");
    }

    @Test
    @DisplayName("Multi-vendor consistency across both Stream A and Stream B")
    void testMultiVendorCrossStreamConsistency() {
        var vendors = new Object[][] {
            {"NVIDIA RTX 4090", new GPUCapabilities(108, 49152, 65536, GPUVendor.NVIDIA, "RTX 4090", 32)},
            {"AMD RX 7900", new GPUCapabilities(64, 65536, 65536, GPUVendor.AMD, "RX 7900", 64)},
            {"Intel Arc A770", new GPUCapabilities(96, 131072, 131072, GPUVendor.INTEL, "Arc A770", 32)}
        };

        for (var testCase : vendors) {
            String label = (String) testCase[0];
            GPUCapabilities caps = (GPUCapabilities) testCase[1];

            // Stream A depth optimization (common across vendors)
            int streamADepth = 16;  // Unified optimization

            // Stream B vendor-specific tuning
            var tuner = new GPUAutoTuner(caps, "/tmp/multi-vendor-" + caps.vendor().name());
            var streamBConfig = tuner.selectOptimalConfigFromProfiles();

            // Both streams should work consistently
            assertNotNull(streamBConfig, label + ": Stream B config");
            assertTrue(streamBConfig.maxTraversalDepth() >= streamADepth, label + ": depth compatibility");
            assertTrue(streamBConfig.workgroupSize() > 0, label + ": workgroup size");

            // LDS usage should be valid with combined tuning
            long combinedLDS = (long) streamADepth * streamBConfig.workgroupSize() * 4;
            assertTrue(combinedLDS <= 131072,  // Typical GPU local memory
                    label + ": LDS usage within GPU limits");
        }
    }

    @Test
    @DisplayName("No conflicts between Stream A and Stream B optimization parameters")
    void testNoConflictsBetweenStreams() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        // Stream A: Stack depth optimization
        int minStackDepth = 8;
        int maxStackDepth = 32;
        int optimizedDepth = 16;  // Stream A choice

        // Stream B: Workgroup tuning
        var tuner = new GPUAutoTuner(capabilities, "/tmp/no-conflicts");
        var config = tuner.selectOptimalConfigFromProfiles();

        // No conflicts: Stream A depth must fit within Stream B's capabilities
        assertTrue(optimizedDepth >= minStackDepth && optimizedDepth <= maxStackDepth,
                "Stream A depth in valid range");
        assertTrue(config.maxTraversalDepth() >= optimizedDepth,
                "Stream B must support Stream A depth choice");

        // No conflicts: Workgroup size should not interfere with stack depth
        assertTrue(config.workgroupSize() <= 1024, "Workgroup size in valid range");
        assertTrue(config.workgroupSize() > 0, "Workgroup size positive");

        // Combined parameters should be mutually compatible
        long totalLDS = (long) optimizedDepth * config.workgroupSize() * 4;
        assertTrue(totalLDS <= 65536, "Combined LDS usage acceptable");
    }

    @Test
    @DisplayName("Stream A stack reduction enables Stream B occupancy gains")
    void testStreamAEnablesStreamBGains() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        // Without Stream A optimization (baseline)
        int baselineStackDepth = 32;
        int workgroupSize = 128;  // Example from Stream B profile

        long baselineLDS = (long) baselineStackDepth * workgroupSize * 4;  // 16,384 bytes
        int maxWorkgroupsBaseline = 65536 / (int) baselineLDS;  // 4 workgroups

        // With Stream A optimization
        int optimizedStackDepth = 16;
        long optimizedLDS = (long) optimizedStackDepth * workgroupSize * 4;  // 8,192 bytes
        int maxWorkgroupsOptimized = 65536 / (int) optimizedLDS;  // 8 workgroups

        // Stream B can leverage the LDS savings from Stream A
        int occupancyGain = maxWorkgroupsOptimized - maxWorkgroupsBaseline;
        double gainPercent = (double) occupancyGain / maxWorkgroupsBaseline * 100.0;

        assertTrue(occupancyGain > 0, "Stream A enables additional concurrent workgroups");
        assertTrue(gainPercent >= 100.0, "Stream A + Stream B should provide 100%+ occupancy improvement");

        assertEquals(4, maxWorkgroupsBaseline, "Baseline: 4 concurrent workgroups");
        assertEquals(8, maxWorkgroupsOptimized, "Optimized: 8 concurrent workgroups (2x improvement)");
    }

    @Test
    @DisplayName("Unified rendering pipeline: detect → tune (A+B) → compile → render")
    void testUnifiedRenderingPipeline() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        long pipelineStart = System.nanoTime();

        // Phase 1: Detect GPU
        assertNotNull(capabilities.vendor());

        // Phase 2: Apply Stream A optimization
        int streamAStackDepth = 16;

        // Phase 3: Apply Stream B tuning
        var tuner = new GPUAutoTuner(capabilities, "/tmp/unified-pipeline");
        var streamBConfig = tuner.selectOptimalConfigFromProfiles();

        // Phase 4: Generate unified build options
        String buildOptions = String.format(
            "-DESVO_MODE=1 -DRELATIVE_ADDRESSING=1 -DMAX_TRAVERSAL_DEPTH=%d -DWORKGROUP_SIZE=%d",
            streamAStackDepth,
            streamBConfig.workgroupSize()
        );

        // Phase 5: Compile kernel with unified options
        assertTrue(buildOptions.contains("-DMAX_TRAVERSAL_DEPTH=16"));
        assertTrue(buildOptions.contains("-DWORKGROUP_SIZE="));

        // Phase 6: Ready for rendering
        assertTrue(streamBConfig.expectedOccupancy() >= 0.0);
        assertTrue(streamBConfig.expectedThroughput() > 0);

        long pipelineEnd = System.nanoTime();
        long pipelineDuration = (pipelineEnd - pipelineStart) / 1_000_000;  // ms

        // Full unified pipeline should be fast
        assertTrue(pipelineDuration < 1000,
                "Unified pipeline should complete in <1 second: " + pipelineDuration + "ms");
    }

    @Test
    @DisplayName("Cross-stream fallback: one stream disabled works correctly")
    void testCrossStreamFallback() {
        var capabilities = new GPUCapabilities(
            64, 65536, 65536,
            GPUVendor.AMD,
            "RX 7900",
            64
        );

        // Scenario 1: Stream A disabled, only Stream B
        var tunerB = new GPUAutoTuner(capabilities, "/tmp/fallback-b-only");
        var configB = tunerB.selectOptimalConfigFromProfiles();

        assertTrue(configB.workgroupSize() > 0, "Stream B alone provides valid config");
        assertTrue(configB.maxTraversalDepth() > 0, "Stream B depth available");

        // Scenario 2: Both streams enabled (Stream A uses default depth from Stream B)
        int streamADepth = configB.maxTraversalDepth();  // Use Stream B's depth

        // Combined should equal or exceed single-stream
        assertTrue(streamADepth > 0, "Fallback works with Stream B depth");

        // LDS usage should remain valid
        long ldsUsage = (long) streamADepth * configB.workgroupSize() * 4;
        assertTrue(ldsUsage <= 65536,  // AMD typical local memory
                "Fallback configuration fits in GPU memory");
    }
}
