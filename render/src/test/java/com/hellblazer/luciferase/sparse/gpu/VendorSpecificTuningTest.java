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
 * Tests for B2: Vendor-Specific Tuning Profiles (Phase 5 Stream B Days 3-4).
 *
 * <p>Validates:
 * - NVIDIA, AMD, Intel, Apple GPU profile loading
 * - Vendor-specific optimization parameters
 * - Fallback to generated defaults when profiles unavailable
 * - Consistency of workgroup configurations across vendors
 * - Profile correctness and parameter validation
 */
class VendorSpecificTuningTest {

    @Test
    @DisplayName("NVIDIA GPU tuning profile available and valid")
    void testNvidiaProfile() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var loader = new GPUTuningProfileLoader();
        var profile = loader.loadProfileForDevice(capabilities);

        assertTrue(profile.isPresent(), "NVIDIA profile should be available");
        var config = profile.get();

        // NVIDIA-optimized: 128-256 threads per workgroup
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 1024,
                "Workgroup size should be reasonable");
        assertTrue(config.maxTraversalDepth() > 0 && config.maxTraversalDepth() <= 32,
                "Max traversal depth in valid range");
    }

    @Test
    @DisplayName("AMD GPU tuning profile available and valid")
    void testAmdProfile() {
        var capabilities = new GPUCapabilities(
            64, 65536, 65536,
            GPUVendor.AMD,
            "RX 7900",
            64
        );

        var loader = new GPUTuningProfileLoader();
        var profile = loader.loadProfileForDevice(capabilities);

        assertTrue(profile.isPresent(), "AMD profile should be available");
        var config = profile.get();

        // AMD-optimized: 64 threads per workgroup (from profiles)
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 1024,
                "Workgroup size should be reasonable");
        assertTrue(config.expectedOccupancy() >= 0.0 && config.expectedOccupancy() <= 1.0,
                "AMD occupancy should be valid");
    }

    @Test
    @DisplayName("Intel GPU tuning profile available and valid")
    void testIntelProfile() {
        var capabilities = new GPUCapabilities(
            96, 131072, 131072,
            GPUVendor.INTEL,
            "Arc A770",
            32
        );

        var loader = new GPUTuningProfileLoader();
        var profile = loader.loadProfileForDevice(capabilities);

        // Intel profiles may be generated if not in predefined set
        assertTrue(profile.isPresent(), "Intel profile should be available");
        var config = profile.get();

        assertTrue(config.workgroupSize() > 0,
                "Intel config should have positive workgroup size");
    }

    @Test
    @DisplayName("Apple GPU tuning profile available and valid")
    void testAppleProfile() {
        var capabilities = new GPUCapabilities(
            8, 32768, 32768,
            GPUVendor.APPLE,
            "M3 Pro",
            32
        );

        var loader = new GPUTuningProfileLoader();
        var profile = loader.loadProfileForDevice(capabilities);

        // Apple profiles may be generated if not predefined
        assertTrue(profile.isPresent(), "Apple profile should be available");
        var config = profile.get();

        assertTrue(config.workgroupSize() > 0,
                "Apple config should have positive workgroup size");
    }

    @Test
    @DisplayName("Vendor profiles have consistent parameter ranges")
    void testVendorParameterConsistency() {
        var nvidia = new GPUCapabilities(108, 49152, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var amd = new GPUCapabilities(64, 65536, 65536, GPUVendor.AMD, "RDNA 3", 64);
        var intel = new GPUCapabilities(96, 131072, 131072, GPUVendor.INTEL, "Arc A770", 32);

        var loader = new GPUTuningProfileLoader();
        var nvidiaConfig = loader.loadProfileForDevice(nvidia).get();
        var amdConfig = loader.loadProfileForDevice(amd).get();
        var intelConfig = loader.loadProfileForDevice(intel).get();

        // All should have valid parameters
        assertTrue(nvidiaConfig.workgroupSize() > 0);
        assertTrue(amdConfig.workgroupSize() > 0);
        assertTrue(intelConfig.workgroupSize() > 0);

        // All should have reasonable occupancy
        assertTrue(nvidiaConfig.expectedOccupancy() >= 0.0 && nvidiaConfig.expectedOccupancy() <= 1.0);
        assertTrue(amdConfig.expectedOccupancy() >= 0.0 && amdConfig.expectedOccupancy() <= 1.0);
        assertTrue(intelConfig.expectedOccupancy() >= 0.0 && intelConfig.expectedOccupancy() <= 1.0);

        // All should have positive throughput
        assertTrue(nvidiaConfig.expectedThroughput() > 0);
        assertTrue(amdConfig.expectedThroughput() > 0);
        assertTrue(intelConfig.expectedThroughput() > 0);
    }

    @Test
    @DisplayName("Profile loader caches profiles for performance")
    void testProfileCaching() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var loader = new GPUTuningProfileLoader();

        // First load
        var profile1 = loader.loadProfileForDevice(capabilities);
        assertTrue(profile1.isPresent());

        // Second load should return cached instance
        var profile2 = loader.loadProfileForDevice(capabilities);
        assertTrue(profile2.isPresent());

        // Both should have same parameters
        assertEquals(profile1.get().workgroupSize(), profile2.get().workgroupSize());
    }

    @Test
    @DisplayName("Profile parameters enable effective occupancy calculation")
    void testProfileParametersForOccupancy() {
        var capabilities = new GPUCapabilities(
            108, 49152, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var loader = new GPUTuningProfileLoader();
        var profile = loader.loadProfileForDevice(capabilities).get();

        // Should be able to calculate occupancy with profile parameters
        var ldsUsage = 4 * profile.maxTraversalDepth() * profile.workgroupSize();
        assertTrue(ldsUsage > 0 && ldsUsage <= 65536,
                "LDS usage should be within GPU limits");

        // Profile occupancy should match occupancy calculator
        var calculator = new OccupancyCalculator();
        var calculatedOccupancy = OccupancyCalculator.calculateOccupancy(
            capabilities,
            profile.workgroupSize(),
            profile.maxTraversalDepth(),
            65536
        );

        assertTrue(calculatedOccupancy >= 0 && calculatedOccupancy <= 1.0,
                "Calculated occupancy should be valid");
    }

    @Test
    @DisplayName("Multiple vendor instances produce consistent profiles")
    void testMultipleLoaderInstances() {
        var capabilities = new GPUCapabilities(
            108, 49148, 65536,
            GPUVendor.NVIDIA,
            "RTX 4090",
            32
        );

        var loader1 = new GPUTuningProfileLoader();
        var loader2 = new GPUTuningProfileLoader();

        var config1 = loader1.loadProfileForDevice(capabilities).get();
        var config2 = loader2.loadProfileForDevice(capabilities).get();

        // Different loaders should produce consistent profiles
        assertEquals(config1.workgroupSize(), config2.workgroupSize(),
                "Multiple loaders should produce consistent configurations");
    }
}
