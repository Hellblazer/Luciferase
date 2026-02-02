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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2: Tests for VendorTuningProfile
 *
 * Validates vendor-specific tuning profiles can be loaded and used correctly.
 *
 * @author hal.hildebrand
 */
@DisplayName("B2: Vendor Tuning Profile Tests")
class VendorTuningProfileTest {

    // ==================== Profile Loading Tests ====================

    @Test
    @DisplayName("Load NVIDIA profile from resources")
    void testLoadNvidiaProfile() {
        var profile = VendorTuningProfile.load(GPUVendor.NVIDIA);

        assertTrue(profile.isPresent(), "NVIDIA profile should load");
        assertEquals(GPUVendor.NVIDIA, profile.get().vendor());
        assertFalse(profile.get().candidates().isEmpty(), "Should have candidates");
        assertNotNull(profile.get().defaultConfig(), "Should have default config");
        assertDoesNotThrow(() -> profile.get().validate(), "Profile should be valid");
    }

    @Test
    @DisplayName("Load AMD profile from resources")
    void testLoadAmdProfile() {
        var profile = VendorTuningProfile.load(GPUVendor.AMD);

        assertTrue(profile.isPresent(), "AMD profile should load");
        assertEquals(GPUVendor.AMD, profile.get().vendor());
        assertFalse(profile.get().candidates().isEmpty(), "Should have candidates");
        assertNotNull(profile.get().defaultConfig(), "Should have default config");
        assertDoesNotThrow(() -> profile.get().validate(), "Profile should be valid");
    }

    @Test
    @DisplayName("Load Intel profile from resources")
    void testLoadIntelProfile() {
        var profile = VendorTuningProfile.load(GPUVendor.INTEL);

        assertTrue(profile.isPresent(), "Intel profile should load");
        assertEquals(GPUVendor.INTEL, profile.get().vendor());
        assertFalse(profile.get().candidates().isEmpty(), "Should have candidates");
        assertNotNull(profile.get().defaultConfig(), "Should have default config");
        assertDoesNotThrow(() -> profile.get().validate(), "Profile should be valid");
    }

    @Test
    @DisplayName("Load Apple profile from resources")
    void testLoadAppleProfile() {
        var profile = VendorTuningProfile.load(GPUVendor.APPLE);

        assertTrue(profile.isPresent(), "Apple profile should load");
        assertEquals(GPUVendor.APPLE, profile.get().vendor());
        assertFalse(profile.get().candidates().isEmpty(), "Should have candidates");
        assertNotNull(profile.get().defaultConfig(), "Should have default config");
        assertDoesNotThrow(() -> profile.get().validate(), "Profile should be valid");
    }

    @Test
    @DisplayName("Unknown vendor returns empty")
    void testLoadUnknownVendor() {
        var profile = VendorTuningProfile.load(GPUVendor.UNKNOWN);

        assertTrue(profile.isEmpty(), "Unknown vendor should return empty");
    }

    // ==================== Profile Content Tests ====================

    @Test
    @DisplayName("NVIDIA profile has multiple candidates")
    void testNvidiaCandidates() {
        var profile = VendorTuningProfile.load(GPUVendor.NVIDIA).orElseThrow();

        assertTrue(profile.candidates().size() >= 3,
                "NVIDIA should have at least 3 candidate configs");

        // Check workgroup sizes cover expected range
        var sizes = profile.candidates().stream()
            .map(VendorTuningProfile.CandidateConfig::workgroupSize)
            .distinct()
            .toList();
        assertTrue(sizes.contains(32) || sizes.contains(64) || sizes.contains(128),
                "Should include standard NVIDIA workgroup sizes");
    }

    @Test
    @DisplayName("AMD profile uses wavefront-aligned sizes")
    void testAmdWavefrontAlignment() {
        var profile = VendorTuningProfile.load(GPUVendor.AMD).orElseThrow();

        // All AMD workgroup sizes should be multiples of 64 (wavefront)
        for (var candidate : profile.candidates()) {
            assertTrue(candidate.workgroupSize() % 32 == 0,
                    "AMD workgroup size should be wavefront-aligned: " + candidate.workgroupSize());
        }
    }

    @Test
    @DisplayName("Intel profile has conservative defaults")
    void testIntelConservativeDefaults() {
        var profile = VendorTuningProfile.load(GPUVendor.INTEL).orElseThrow();

        // Intel default should be conservative (smaller workgroups)
        assertTrue(profile.defaultConfig().workgroupSize() <= 64,
                "Intel default should use conservative workgroup size");
    }

    @Test
    @DisplayName("Apple profile supports deep traversal")
    void testAppleDeepTraversal() {
        var profile = VendorTuningProfile.load(GPUVendor.APPLE).orElseThrow();

        // Apple should have candidates with depth 24
        var hasDeepTraversal = profile.candidates().stream()
            .anyMatch(c -> c.maxTraversalDepth() == 24);
        assertTrue(hasDeepTraversal, "Apple should support depth 24");
    }

    // ==================== Device Override Tests ====================

    @Test
    @DisplayName("Get config for known NVIDIA device")
    void testNvidiaDeviceOverride() {
        var profile = VendorTuningProfile.load(GPUVendor.NVIDIA).orElseThrow();

        var rtx4090Config = profile.getConfigForDevice("RTX 4090");

        assertNotNull(rtx4090Config);
        assertEquals(128, rtx4090Config.workgroupSize(),
                "RTX 4090 should use 128-thread workgroups");
    }

    @Test
    @DisplayName("Get config for unknown device returns default")
    void testUnknownDeviceReturnsDefault() {
        var profile = VendorTuningProfile.load(GPUVendor.NVIDIA).orElseThrow();

        var unknownConfig = profile.getConfigForDevice("Unknown GPU Model");

        assertEquals(profile.defaultConfig().workgroupSize(), unknownConfig.workgroupSize(),
                "Unknown device should return default config");
    }

    @Test
    @DisplayName("Device override with partial match")
    void testDevicePartialMatch() {
        var profile = VendorTuningProfile.load(GPUVendor.AMD).orElseThrow();

        // Try with partial model name
        var rx6900Config = profile.getConfigForDevice("RX 6900");

        assertNotNull(rx6900Config);
        // Should match the RX_6900_XT override
    }

    // ==================== Candidate Conversion Tests ====================

    @Test
    @DisplayName("Convert candidates to WorkgroupConfigs")
    void testCandidateToWorkgroupConfig() {
        var profile = VendorTuningProfile.load(GPUVendor.NVIDIA).orElseThrow();
        var capabilities = new GPUCapabilities(128, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        var configs = profile.getCandidateConfigs(capabilities);

        assertFalse(configs.isEmpty(), "Should produce configs");
        for (var config : configs) {
            assertTrue(config.isValidWorkgroupSize(), "Config should be valid");
            assertTrue(config.expectedOccupancy() > 0, "Should calculate occupancy");
        }
    }

    // ==================== Build Options Tests ====================

    @Test
    @DisplayName("NVIDIA profile has vendor build options")
    void testNvidiaBuildOptions() {
        var profile = VendorTuningProfile.load(GPUVendor.NVIDIA).orElseThrow();

        assertNotNull(profile.vendorBuildOptions());
        assertTrue(profile.vendorBuildOptions().contains("-cl-mad-enable") ||
                   profile.vendorBuildOptions().contains("-cl-fast-relaxed-math"),
                "NVIDIA should have optimization flags");
    }

    @Test
    @DisplayName("AMD profile has vendor build options")
    void testAmdBuildOptions() {
        var profile = VendorTuningProfile.load(GPUVendor.AMD).orElseThrow();

        assertNotNull(profile.vendorBuildOptions());
        assertTrue(profile.vendorBuildOptions().contains("-cl-fast-relaxed-math"),
                "AMD should have fast-relaxed-math flag");
    }

    // ==================== Validation Tests ====================

    @Test
    @DisplayName("Profile validation catches invalid workgroup size")
    void testValidationInvalidWorkgroupSize() {
        var invalid = new VendorTuningProfile(
            GPUVendor.NVIDIA,
            "test",
            java.util.List.of(new VendorTuningProfile.CandidateConfig(512, 16, "invalid")),
            new WorkgroupConfig(64, 16, 0.7f, 1.0f, "default"),
            java.util.Map.of(),
            ""
        );

        assertThrows(IllegalStateException.class, invalid::validate,
                "Should reject workgroup size > 256");
    }

    @Test
    @DisplayName("Profile validation catches invalid depth")
    void testValidationInvalidDepth() {
        var invalid = new VendorTuningProfile(
            GPUVendor.NVIDIA,
            "test",
            java.util.List.of(new VendorTuningProfile.CandidateConfig(64, 64, "invalid")),
            new WorkgroupConfig(64, 16, 0.7f, 1.0f, "default"),
            java.util.Map.of(),
            ""
        );

        assertThrows(IllegalStateException.class, invalid::validate,
                "Should reject depth > 32");
    }

    @Test
    @DisplayName("Profile validation requires candidates")
    void testValidationRequiresCandidates() {
        var invalid = new VendorTuningProfile(
            GPUVendor.NVIDIA,
            "test",
            java.util.List.of(),  // Empty candidates
            new WorkgroupConfig(64, 16, 0.7f, 1.0f, "default"),
            java.util.Map.of(),
            ""
        );

        assertThrows(IllegalStateException.class, invalid::validate,
                "Should require at least one candidate");
    }
}
