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
package com.hellblazer.luciferase.sparse.gpu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: Tests for GPUTuningProfileLoader
 *
 * Stream B Phase 6: GPU Tuning Profile Loading
 * Tests JSON profile loading and caching
 *
 * @author hal.hildebrand
 */
@DisplayName("GPUTuningProfileLoader Tests")
class GPUTuningProfileLoaderTest {

    private GPUTuningProfileLoader loader;

    @BeforeEach
    void setUp() {
        loader = new GPUTuningProfileLoader();
    }

    @Test
    @DisplayName("Load profile for NVIDIA RTX 4090")
    void testLoadNvidiaRTX4090() {
        // TDD: Load predefined profile from JSON
        var profile = loader.loadProfile("NVIDIA_RTX_4090");

        assertTrue(profile.isPresent(), "Should load NVIDIA RTX 4090 profile");
        var config = profile.get();

        assertEquals(128, config.workgroupSize(), "RTX 4090 should use 128 threads");
        assertEquals(16, config.maxTraversalDepth(), "RTX 4090 should use depth 16");
        assertTrue(config.expectedOccupancy() >= 0.75f, "RTX 4090 should have >= 75% occupancy");
        assertTrue(config.expectedThroughput() >= 4.0f, "RTX 4090 should have >= 4.0 rays/μs");
    }

    @Test
    @DisplayName("Load profile for AMD RX 6900")
    void testLoadAmdRX6900() {
        // TDD: Load AMD profile
        var profile = loader.loadProfile("AMD_RX_6900");

        assertTrue(profile.isPresent(), "Should load AMD RX 6900 profile");
        var config = profile.get();

        assertEquals(64, config.workgroupSize(), "RX 6900 should use 64 threads");
        assertEquals(16, config.maxTraversalDepth(), "RX 6900 should use depth 16");
        assertTrue(config.expectedOccupancy() >= 0.70f, "RX 6900 should have >= 70% occupancy");
    }

    @Test
    @DisplayName("Load profile for Intel Arc A770")
    void testLoadIntelArcA770() {
        // TDD: Load Intel profile
        var profile = loader.loadProfile("INTEL_ARC_A770");

        assertTrue(profile.isPresent(), "Should load Intel Arc A770 profile");
        var config = profile.get();

        assertEquals(32, config.workgroupSize(), "Arc A770 should use 32 threads");
        assertEquals(24, config.maxTraversalDepth(), "Arc A770 should use depth 24");
        assertTrue(config.expectedOccupancy() >= 0.60f, "Arc A770 should have >= 60% occupancy");
    }

    @Test
    @DisplayName("Load profile for Apple M2 Max")
    void testLoadAppleM2Max() {
        // TDD: Load Apple profile
        var profile = loader.loadProfile("APPLE_M2_MAX");

        assertTrue(profile.isPresent(), "Should load Apple M2 Max profile");
        var config = profile.get();

        assertEquals(32, config.workgroupSize(), "M2 Max should use 32 threads");
        assertEquals(24, config.maxTraversalDepth(), "M2 Max should use depth 24");
        assertNotNull(config.notes(), "Profile should have notes");
    }

    @Test
    @DisplayName("Return empty for unknown GPU model")
    void testUnknownGPU() {
        // TDD: Should return empty Optional for unknown model
        var profile = loader.loadProfile("UNKNOWN_GPU_XYZ");

        assertTrue(profile.isEmpty(), "Should return empty for unknown GPU");
    }

    @Test
    @DisplayName("Profile keys are normalized (case-insensitive)")
    void testKeyNormalization() {
        // TDD: Should handle different casing
        var profile1 = loader.loadProfile("NVIDIA_RTX_4090");
        var profile2 = loader.loadProfile("nvidia_rtx_4090");
        var profile3 = loader.loadProfile("NVidia_Rtx_4090");

        assertTrue(profile1.isPresent(), "Uppercase should work");
        assertTrue(profile2.isPresent(), "Lowercase should work");
        assertTrue(profile3.isPresent(), "Mixed case should work");

        // All should return same configuration
        assertEquals(profile1.get().workgroupSize(), profile2.get().workgroupSize());
        assertEquals(profile1.get().workgroupSize(), profile3.get().workgroupSize());
    }

    @Test
    @DisplayName("Load all profiles successfully")
    void testLoadAllProfiles() {
        // TDD: Should load all profiles from JSON
        var allProfiles = loader.loadAllProfiles();

        assertNotNull(allProfiles, "Should return profiles map");
        assertFalse(allProfiles.isEmpty(), "Should have at least one profile");

        // Should have profiles for major vendors
        assertTrue(allProfiles.size() >= 5,
                  "Should have at least 5 GPU profiles, got: " + allProfiles.size());
    }

    @Test
    @DisplayName("Cache loaded profiles for performance")
    void testProfileCaching() {
        // TDD: Second load should be cached (faster)
        var profile1 = loader.loadProfile("NVIDIA_RTX_4090");
        var profile2 = loader.loadProfile("NVIDIA_RTX_4090");

        // Both should succeed
        assertTrue(profile1.isPresent());
        assertTrue(profile2.isPresent());

        // Should return same instance (cached)
        assertSame(profile1.get(), profile2.get(),
                  "Second load should return cached instance");
    }

    @Test
    @DisplayName("Profile notes contain GPU description")
    void testProfileNotes() {
        // TDD: Notes should describe GPU characteristics
        var profile = loader.loadProfile("NVIDIA_RTX_4090");

        assertTrue(profile.isPresent());
        var notes = profile.get().notes();

        assertNotNull(notes, "Profile should have notes");
        assertTrue(notes.length() > 10, "Notes should be descriptive");
        assertTrue(notes.contains("CUDA") || notes.contains("cores") || notes.contains("VRAM"),
                  "Notes should mention GPU characteristics");
    }

    @Test
    @DisplayName("Load profile by GPU capabilities")
    void testLoadByCapabilities() {
        // TDD: Should find matching profile based on model string
        var caps = new GPUCapabilities(128, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var profile = loader.loadProfileForDevice(caps);

        assertTrue(profile.isPresent(), "Should find profile for RTX 4090");
        var config = profile.get();

        assertEquals(128, config.workgroupSize(), "Should load correct workgroup size");
    }

    @Test
    @DisplayName("Fallback to vendor default when specific model not found")
    void testFallbackToVendorDefault() {
        // TDD: Unknown NVIDIA model should use NVIDIA defaults
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 9999", 32);
        var profile = loader.loadProfileForDevice(caps);

        // Should return a valid config (fallback to generated)
        assertTrue(profile.isPresent(), "Should return fallback config");
        var config = profile.get();

        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 256,
                  "Fallback should have valid workgroup size");
    }

    @Test
    @DisplayName("JSON resource file exists and is valid")
    void testResourceFileExists() {
        // TDD: Should be able to load resource file
        var resource = getClass().getResourceAsStream("/gpu-tuning-profiles.json");

        assertNotNull(resource, "gpu-tuning-profiles.json should exist in resources");
    }

    @Test
    @DisplayName("All loaded profiles have valid parameters")
    void testAllProfilesValid() {
        // TDD: Every profile should have valid workgroup size and depth
        var allProfiles = loader.loadAllProfiles();

        for (var entry : allProfiles.entrySet()) {
            var config = entry.getValue();

            assertTrue(config.isValidWorkgroupSize(),
                      "Profile " + entry.getKey() + " should have valid workgroup size: " + config.workgroupSize());
            assertTrue(config.isValidDepth(),
                      "Profile " + entry.getKey() + " should have valid depth: " + config.maxTraversalDepth());
            assertTrue(config.expectedOccupancy() >= 0.0f && config.expectedOccupancy() <= 1.0f,
                      "Profile " + entry.getKey() + " should have valid occupancy: " + config.expectedOccupancy());
            assertTrue(config.expectedThroughput() > 0.0f,
                      "Profile " + entry.getKey() + " should have positive throughput");
        }
    }
}
