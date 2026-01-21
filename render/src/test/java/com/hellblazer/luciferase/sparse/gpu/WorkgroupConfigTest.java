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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: Tests for WorkgroupConfig
 *
 * Stream B Phase 4: WorkgroupConfig record
 * Tests tuning parameter encapsulation and factory methods
 *
 * @author hal.hildebrand
 */
@DisplayName("WorkgroupConfig Tests")
class WorkgroupConfigTest {

    @Test
    @DisplayName("Calculate LDS usage for typical configuration")
    void testLdsCalculationTypical() {
        // TDD: 4 bytes per stack entry per thread
        // workgroup size 64, depth 16 = 4 * 16 * 64 = 4096 bytes = 4KB
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test config");

        assertEquals(4096, config.calculateLdsUsage(),
                    "64 threads * 16 depth * 4 bytes = 4096 bytes");
    }

    @Test
    @DisplayName("Calculate LDS usage for large configuration")
    void testLdsCalculationLarge() {
        // TDD: Large workgroup with deep stack
        // workgroup size 256, depth 32 = 4 * 32 * 256 = 32768 bytes = 32KB
        var config = new WorkgroupConfig(256, 32, 0.60f, 1.8f, "large config");

        assertEquals(32768, config.calculateLdsUsage(),
                    "256 threads * 32 depth * 4 bytes = 32768 bytes");
    }

    @Test
    @DisplayName("Calculate LDS usage for small configuration")
    void testLdsCalculationSmall() {
        // TDD: Small workgroup with shallow stack
        // workgroup size 32, depth 8 = 4 * 8 * 32 = 1024 bytes = 1KB
        var config = new WorkgroupConfig(32, 8, 0.85f, 3.0f, "small config");

        assertEquals(1024, config.calculateLdsUsage(),
                    "32 threads * 8 depth * 4 bytes = 1024 bytes");
    }

    @Test
    @DisplayName("Validate workgroup size range")
    void testValidateWorkgroupSize() {
        // TDD: Valid sizes: 32, 64, 96, 128, 192, 256
        var config32 = new WorkgroupConfig(32, 16, 0.75f, 2.5f, "32 threads");
        var config256 = new WorkgroupConfig(256, 16, 0.75f, 2.5f, "256 threads");

        assertTrue(config32.isValidWorkgroupSize(),
                  "32 is valid workgroup size");
        assertTrue(config256.isValidWorkgroupSize(),
                  "256 is valid workgroup size");
    }

    @Test
    @DisplayName("Reject invalid workgroup sizes")
    void testInvalidWorkgroupSize() {
        // TDD: Invalid sizes: 0, negative, >256, non-power-of-2
        var configZero = new WorkgroupConfig(0, 16, 0.75f, 2.5f, "zero threads");
        var configNegative = new WorkgroupConfig(-32, 16, 0.75f, 2.5f, "negative");
        var configTooBig = new WorkgroupConfig(512, 16, 0.75f, 2.5f, "too large");
        var configOdd = new WorkgroupConfig(33, 16, 0.75f, 2.5f, "odd number");

        assertFalse(configZero.isValidWorkgroupSize(), "0 is invalid");
        assertFalse(configNegative.isValidWorkgroupSize(), "negative is invalid");
        assertFalse(configTooBig.isValidWorkgroupSize(), "512 exceeds maximum");
        assertFalse(configOdd.isValidWorkgroupSize(), "33 is not power of 2 or multiple of 32");
    }

    @Test
    @DisplayName("Validate traversal depth range")
    void testValidateTraversalDepth() {
        // TDD: Valid depths: 8-32
        var config8 = new WorkgroupConfig(64, 8, 0.85f, 3.0f, "depth 8");
        var config32 = new WorkgroupConfig(64, 32, 0.65f, 2.0f, "depth 32");

        assertTrue(config8.isValidDepth(), "8 is valid depth");
        assertTrue(config32.isValidDepth(), "32 is valid depth");
    }

    @Test
    @DisplayName("Reject invalid traversal depths")
    void testInvalidTraversalDepth() {
        // TDD: Invalid depths: <8, >32
        var configTooShallow = new WorkgroupConfig(64, 4, 0.90f, 3.5f, "too shallow");
        var configTooDeep = new WorkgroupConfig(64, 64, 0.50f, 1.5f, "too deep");

        assertFalse(configTooShallow.isValidDepth(), "4 is below minimum");
        assertFalse(configTooDeep.isValidDepth(), "64 exceeds maximum");
    }

    @Test
    @DisplayName("Factory method creates config for NVIDIA GPU")
    void testFactoryForNvidia() {
        // TDD: NVIDIA should prefer workgroup size 64-128
        var caps = new GPUCapabilities(128, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var config = WorkgroupConfig.forDevice(caps);

        assertNotNull(config, "Factory should create valid config");
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 256,
                  "Workgroup size should be in valid range");
        assertTrue(config.maxTraversalDepth() >= 8 && config.maxTraversalDepth() <= 32,
                  "Depth should be in valid range");
        assertTrue(config.expectedOccupancy() >= 0.5f && config.expectedOccupancy() <= 1.0f,
                  "Occupancy should be reasonable");
    }

    @Test
    @DisplayName("Factory method creates config for AMD GPU")
    void testFactoryForAmd() {
        // TDD: AMD should prefer workgroup size 64+ (wavefront 64)
        var caps = new GPUCapabilities(80, 65536, 65536, GPUVendor.AMD, "RX 6900 XT", 64);
        var config = WorkgroupConfig.forDevice(caps);

        assertNotNull(config, "Factory should create valid config");
        assertTrue(config.workgroupSize() >= 64,
                  "AMD should prefer at least wavefront size 64");
        assertTrue(config.isValidWorkgroupSize() && config.isValidDepth(),
                  "AMD config should have valid workgroup size and depth");
        assertTrue(config.expectedOccupancy() > 0.0f,
                  "AMD config should have positive occupancy");
    }

    @Test
    @DisplayName("Factory method creates config for Intel GPU")
    void testFactoryForIntel() {
        // TDD: Intel should prefer smaller workgroup size (32-64)
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.INTEL, "Arc A770", 32);
        var config = WorkgroupConfig.forDevice(caps);

        assertNotNull(config, "Factory should create valid config");
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 128,
                  "Intel should prefer moderate workgroup sizes");
    }

    @Test
    @DisplayName("Factory method creates config for Apple GPU")
    void testFactoryForApple() {
        // TDD: Apple should prefer smaller workgroup, deeper stack
        var caps = new GPUCapabilities(10, 32768, 65536, GPUVendor.APPLE, "M2 Max", 32);
        var config = WorkgroupConfig.forDevice(caps);

        assertNotNull(config, "Factory should create valid config");
        // Apple has less local memory (32KB), so might prefer smaller workgroups
        assertTrue(config.workgroupSize() >= 32 && config.workgroupSize() <= 256,
                  "Apple should use valid workgroup size");
    }

    @Test
    @DisplayName("Factory uses OccupancyCalculator for optimal depth")
    void testFactoryUsesOccupancyCalculator() {
        // TDD: Factory should use OccupancyCalculator to find optimal config
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var config = WorkgroupConfig.forDevice(caps);

        // Verify that the recommended config achieves good occupancy
        var actualOccupancy = OccupancyCalculator.calculateOccupancy(
            caps, config.workgroupSize(), config.maxTraversalDepth(), 64
        );

        assertTrue(actualOccupancy >= 0.45,
                  "Factory-created config should achieve at least 45% occupancy, got: " + actualOccupancy);
        assertTrue(Math.abs(actualOccupancy - config.expectedOccupancy()) < 0.20,
                  "Expected occupancy should match actual within 20%");
    }

    @Test
    @DisplayName("Config produces consistent LDS usage")
    void testLdsUsageConsistency() {
        // TDD: Same parameters should always produce same LDS usage
        var config1 = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "config 1");
        var config2 = new WorkgroupConfig(64, 16, 0.80f, 3.0f, "config 2");

        assertEquals(config1.calculateLdsUsage(), config2.calculateLdsUsage(),
                    "LDS usage should depend only on size and depth");
    }

    @Test
    @DisplayName("Config with notes field stores metadata")
    void testNotesField() {
        // TDD: Notes field should store human-readable metadata
        var notes = "Optimized for RTX 4090 - balanced throughput/occupancy";
        var config = new WorkgroupConfig(128, 16, 0.80f, 4.2f, notes);

        assertEquals(notes, config.notes(), "Notes should be preserved");
    }

    @Test
    @DisplayName("Throughput expectation is reasonable")
    void testThroughputExpectation() {
        // TDD: Expected throughput should be positive and reasonable
        var config = new WorkgroupConfig(64, 16, 0.75f, 2.5f, "test");

        assertTrue(config.expectedThroughput() > 0.0f,
                  "Throughput should be positive");
        assertTrue(config.expectedThroughput() < 100.0f,
                  "Throughput should be reasonable (< 100 rays/μs)");
    }
}
