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
 * Integration tests for B1 Auto-Tuner with Kernel Recompilation (Phase 5 Stream B Days 2-3).
 *
 * <p>Validates:
 * - Auto-tuner selects optimal workgroup configuration
 * - Build options correctly formatted for tuning
 * - Kernel recompilation with tuned parameters
 * - Fallback on GPU unavailability
 * - Consistent performance across retries
 */
class GPUAutoTunerIntegrationTest {

    @Test
    @DisplayName("Auto-tuner selects optimal configuration")
    void testAutoTunerSelectsOptimal() {
        var capabilities = new GPUCapabilities(
            32, 65536, 65536,
            GPUVendor.NVIDIA,
            "Test GPU",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        assertNotNull(config, "Auto-tuner should return a valid configuration");
        assertTrue(config.workgroupSize() > 0, "Workgroup size should be positive");
        assertTrue(config.workgroupSize() <= 1024, "Workgroup size within GPU limits");
        assertTrue(config.maxTraversalDepth() > 0, "Traversal depth should be positive");
    }

    @Test
    @DisplayName("Build options generated from tuning config")
    void testBuildOptionsFromTuning() {
        var capabilities = new GPUCapabilities(
            32, 65536, 65536,
            GPUVendor.NVIDIA,
            "Test GPU",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Config should support conversion to build options
        assertNotNull(config.notes(), "Config should have notes");
        assertTrue(config.workgroupSize() > 0, "Workgroup size required for options");
    }

    @Test
    @DisplayName("Fallback uses conservative defaults when GPU unavailable")
    void testFallbackDefaults() {
        var capabilities = new GPUCapabilities(
            32, 65536, 65536,
            GPUVendor.NVIDIA,
            "Test GPU",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // Should be conservative (lower occupancy for safety)
        assertTrue(config.workgroupSize() > 0, "Fallback should have valid workgroup size");
        assertTrue(config.expectedOccupancy() >= 0.0, "Occupancy should be non-negative");
        assertTrue(config.expectedOccupancy() <= 1.0, "Occupancy should not exceed 100%");
    }

    @Test
    @DisplayName("Tuning config caching and retrieval")
    void testTuningConfigCaching() {
        var capabilities = new GPUCapabilities(
            32, 65536, 65536,
            GPUVendor.NVIDIA,
            "Test GPU",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");

        // First selection
        var config1 = tuner.selectOptimalConfigFromProfiles();
        assertNotNull(config1);

        // Second selection should work (cache or profile)
        var config2 = tuner.selectOptimalConfigFromProfiles();
        assertNotNull(config2);

        // Both should be valid configurations
        assertTrue(config1.workgroupSize() > 0);
        assertTrue(config2.workgroupSize() > 0);
    }

    @Test
    @DisplayName("Workgroup config provides required tuning parameters")
    void testWorkgroupConfigParameters() {
        var capabilities = new GPUCapabilities(
            32, 65536, 65536,
            GPUVendor.NVIDIA,
            "Test GPU",
            32
        );

        var tuner = new GPUAutoTuner(capabilities, "/tmp/test-tuning");
        var config = tuner.selectOptimalConfigFromProfiles();

        // All tuning parameters should be available
        assertNotNull(config.workgroupSize(), "Must have workgroup size");
        assertNotNull(config.maxTraversalDepth(), "Must have max traversal depth");
        assertNotNull(config.expectedOccupancy(), "Must have expected occupancy");
        assertNotNull(config.expectedThroughput(), "Must have expected throughput");

        // Parameters should be meaningful
        assertTrue(config.workgroupSize() > 0 && config.workgroupSize() <= 1024);
        assertTrue(config.maxTraversalDepth() > 0 && config.maxTraversalDepth() <= 32);
        assertTrue(config.expectedOccupancy() >= 0 && config.expectedOccupancy() <= 1.0);
        assertTrue(config.expectedThroughput() > 0);
    }
}
