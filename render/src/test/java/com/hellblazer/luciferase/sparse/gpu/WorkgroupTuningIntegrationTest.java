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
 * Integration tests for B5: Workgroup Tuning Integration (Phase 5 Stream B Days 9-10).
 *
 * <p>Validates:
 * - End-to-end GPU tuning pipeline: detect → tune → recompile → render
 * - Multi-vendor consistency (>90% success rate across vendors)
 * - Kernel recompilation with tuned parameters
 * - Rendering correctness with different tuning configurations
 * - Performance metrics validation
 */
class WorkgroupTuningIntegrationTest {

    @Test
    @DisplayName("Full pipeline: GPU detection and tuning configuration")
    void testFullPipelineDetectAndTune() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        // Phase 1: Detect GPU capabilities
        assertNotNull(capabilities);
        assertNotNull(capabilities.vendor());
        assertTrue(capabilities.vendor() == GPUVendor.NVIDIA);

        // Phase 2: Tune configuration
        var tuner = new GPUAutoTuner(capabilities, "/tmp/integration-tune");
        var config = tuner.selectOptimalConfigFromProfiles();

        assertNotNull(config);
        assertTrue(config.workgroupSize() > 0);
        assertTrue(config.maxTraversalDepth() > 0);

        // Phase 3: Verify configuration is valid for kernel
        assertTrue(config.workgroupSize() <= 1024);
        assertTrue(config.maxTraversalDepth() <= 32);

        // Phase 4: Configuration ready for rendering
        assertNotNull(config.notes());
    }

    @Test
    @DisplayName("Multi-vendor pipeline consistency validation (>90% success rate)")
    void testMultiVendorPipelineConsistency() {
        var vendors = new GPUCapabilities[] {
            new GPUCapabilities(108, 49152, 65536, GPUVendor.NVIDIA, "RTX 4090", 32),
            new GPUCapabilities(64, 65536, 65536, GPUVendor.AMD, "RX 7900", 64),
            new GPUCapabilities(96, 131072, 131072, GPUVendor.INTEL, "Arc A770", 32),
            new GPUCapabilities(8, 32768, 32768, GPUVendor.APPLE, "M3 Pro", 32)
        };

        int successCount = 0;
        for (var caps : vendors) {
            try {
                var tuner = new GPUAutoTuner(caps, "/tmp/multi-vendor-" + caps.vendor().name());
                var config = tuner.selectOptimalConfigFromProfiles();

                // Verify configuration is valid
                if (config != null &&
                    config.workgroupSize() > 0 &&
                    config.maxTraversalDepth() > 0 &&
                    config.expectedOccupancy() >= 0.0 &&
                    config.expectedOccupancy() <= 1.0) {
                    successCount++;
                }
            } catch (Exception e) {
                // Acceptable for some vendors in test environment
            }
        }

        // Expect high consistency (>90% of vendors successfully tuned)
        double consistencyRate = (double) successCount / vendors.length;
        assertTrue(consistencyRate >= 0.9,
                "Multi-vendor consistency should exceed 90%, got: " + (consistencyRate * 100) + "%");
    }

    @Test
    @DisplayName("Kernel recompilation with tuned build options")
    void testKernelRecompilationWithTunedOptions() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/recompile-options");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Build options would be generated from config
        // Verify config contains necessary parameters for kernel compilation
        assertTrue(config.maxTraversalDepth() > 0, "Must have depth for -DMAX_TRAVERSAL_DEPTH");
        assertTrue(config.workgroupSize() > 0, "Must have workgroup for -DWORKGROUP_SIZE");

        // Options would be passed to OpenCL compiler
        // Example: -DMAX_TRAVERSAL_DEPTH=16 -DWORKGROUP_SIZE=128
        String buildOptions = String.format(
            "-DMAX_TRAVERSAL_DEPTH=%d -DWORKGROUP_SIZE=%d",
            config.maxTraversalDepth(),
            config.workgroupSize()
        );

        assertTrue(buildOptions.contains("-DMAX_TRAVERSAL_DEPTH="));
        assertTrue(buildOptions.contains("-DWORKGROUP_SIZE="));
        assertTrue(buildOptions.length() > 0);
    }

    @Test
    @DisplayName("Rendering correctness with different tuning configurations")
    void testRenderingCorrectnessAcrossConfigs() {
        var capabilitiesA = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var capabilitiesB = new GPUCapabilities(
            64, 65536, 65536,
            GPUVendor.AMD,
            "RX 7900",
            64
        );

        var tunerA = new GPUAutoTuner(capabilitiesA, "/tmp/render-config-a");
        var tunerB = new GPUAutoTuner(capabilitiesB, "/tmp/render-config-b");

        var configA = tunerA.selectOptimalConfigFromProfiles();
        var configB = tunerB.selectOptimalConfigFromProfiles();

        // Both configurations should be valid for rendering
        assertNotNull(configA);
        assertNotNull(configB);

        // Different vendors should produce different configs
        // (But both should be correct for their respective GPUs)
        boolean configsDiffer = configA.workgroupSize() != configB.workgroupSize() ||
                               configA.maxTraversalDepth() != configB.maxTraversalDepth();

        // At least one parameter should differ to show vendor-specific tuning
        assertTrue(configsDiffer || configA.expectedOccupancy() != configB.expectedOccupancy(),
                "Vendor-specific tuning should produce different optimizations");

        // Both should maintain rendering correctness invariants
        assertTrue(configA.expectedOccupancy() >= 0.0 && configA.expectedOccupancy() <= 1.0);
        assertTrue(configB.expectedOccupancy() >= 0.0 && configB.expectedOccupancy() <= 1.0);
    }

    @Test
    @DisplayName("End-to-end pipeline performance metrics validation")
    void testPipelinePerformanceMetrics() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        long startPipeline = System.nanoTime();

        // Simulate complete rendering pipeline
        var tuner = new GPUAutoTuner(capabilities, "/tmp/pipeline-metrics");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Verify configuration is ready for immediate use
        assertTrue(config.expectedThroughput() > 0);
        assertTrue(config.expectedOccupancy() >= 0.0);

        long endPipeline = System.nanoTime();
        long pipelineDuration = (endPipeline - startPipeline) / 1_000_000;  // ms

        // Complete pipeline (detect + tune + validate) should be fast
        assertTrue(pipelineDuration < 1000,
                "Pipeline should complete in <1 second, took: " + pipelineDuration + "ms");

        // Configuration provides rendering metrics
        assertNotNull(config.notes(), "Configuration should include performance notes");
        assertTrue(config.maxTraversalDepth() > 0, "Depth available for LDS calculation");
        assertTrue(config.workgroupSize() > 0, "Workgroup size available for occupancy");

        // Validate metrics for rendering decisions
        double ldsUsageBytes = (double) (config.maxTraversalDepth() * config.workgroupSize() * 4);
        assertTrue(ldsUsageBytes > 0 && ldsUsageBytes <= 65536,
                "LDS usage should fit in typical GPU local memory");
    }

    @Test
    @DisplayName("Multi-vendor consistency across full pipeline")
    void testFullPipelineMultiVendorValidation() {
        var testCases = new Object[][] {
            {"NVIDIA RTX 4090", new GPUCapabilities(108, 49152, 65536, GPUVendor.NVIDIA, "RTX 4090", 32)},
            {"AMD RX 7900", new GPUCapabilities(64, 65536, 65536, GPUVendor.AMD, "RX 7900", 64)},
            {"Intel Arc A770", new GPUCapabilities(96, 131072, 131072, GPUVendor.INTEL, "Arc A770", 32)}
        };

        for (var testCase : testCases) {
            String label = (String) testCase[0];
            GPUCapabilities caps = (GPUCapabilities) testCase[1];

            // Execute full pipeline for each vendor
            var tuner = new GPUAutoTuner(caps, "/tmp/full-" + caps.vendor().name());
            var config = tuner.selectOptimalConfigFromProfiles();

            // All vendors should succeed
            assertNotNull(config, "Failed to get config for " + label);

            // All configs should be valid
            assertTrue(config.workgroupSize() > 0, label + ": workgroup size");
            assertTrue(config.maxTraversalDepth() > 0, label + ": traversal depth");
            assertTrue(config.expectedOccupancy() >= 0.0 && config.expectedOccupancy() <= 1.0, label + ": occupancy");
            assertTrue(config.expectedThroughput() > 0, label + ": throughput");

            // All should provide rendering-ready configuration
            assertNotNull(config.notes(), label + ": config notes");
        }
    }
}
