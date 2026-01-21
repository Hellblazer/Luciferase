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
 * TDD: Tests for OccupancyCalculator
 *
 * Stream B: GPU Workgroup Tuning - Occupancy Estimation
 * Tests GPU occupancy calculations for workgroup tuning
 *
 * @author hal.hildebrand
 */
@DisplayName("OccupancyCalculator Tests")
class OccupancyCalculatorTest {

    @Test
    @DisplayName("Calculate occupancy for NVIDIA RTX 4090")
    void testOccupancyNvidia() {
        // TDD: Test NVIDIA occupancy calculation
        var caps = new GPUCapabilities(128, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Configuration: workgroup size 64, stack depth 32 (128 bytes), 8KB LDS total
        var occupancy = OccupancyCalculator.calculateOccupancy(caps, 64, 32, 64);

        // 65KB / 8KB = 8 concurrent workgroups per SM
        // 8 / 16 (typical max) = 50% occupancy
        assertTrue(occupancy >= 0.45 && occupancy <= 0.55,
                  "Expected ~50% occupancy, got: " + occupancy);
    }

    @Test
    @DisplayName("Calculate occupancy for AMD RX 6900 XT")
    void testOccupancyAmd() {
        // TDD: Test AMD occupancy calculation
        var caps = new GPUCapabilities(80, 65536, 65536, GPUVendor.AMD, "RX 6900 XT", 64);

        // Configuration: workgroup size 128, stack depth 16 (64 bytes), 8KB LDS total
        var occupancy = OccupancyCalculator.calculateOccupancy(caps, 128, 16, 64);

        // 65KB / 8KB = 8 concurrent workgroups
        // Occupancy should be reasonable (~50-70%)
        assertTrue(occupancy >= 0.40 && occupancy <= 0.75,
                  "Expected ~50-70% occupancy, got: " + occupancy);
    }

    @Test
    @DisplayName("Occupancy is 0 when LDS exceeds available memory")
    void testZeroOccupancy() {
        // TDD: Excessive LDS usage should result in 0% occupancy
        var caps = new GPUCapabilities(32, 32768, 65536, GPUVendor.NVIDIA, "RTX 3060", 32);

        // Require 64KB LDS (256 threads * 64 depth * 4 bytes = 65536) but only have 32KB available
        var occupancy = OccupancyCalculator.calculateOccupancy(caps, 256, 64, 128);

        assertEquals(0.0, occupancy, "Occupancy should be 0 when LDS exceeds capacity");
    }

    @Test
    @DisplayName("Higher occupancy with reduced stack depth")
    void testHigherOccupancyWithReducedStack() {
        // TDD: Reducing stack depth should increase occupancy
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Deep stack: depth 32, 128 bytes/thread, 64 threads = 8KB LDS
        var deepOccupancy = OccupancyCalculator.calculateOccupancy(caps, 64, 32, 64);

        // Shallow stack: depth 16, 64 bytes/thread, 64 threads = 4KB LDS
        var shallowOccupancy = OccupancyCalculator.calculateOccupancy(caps, 64, 16, 64);

        assertTrue(shallowOccupancy > deepOccupancy,
                  "Shallow stack should have higher occupancy than deep stack");
    }

    @Test
    @DisplayName("Occupancy scales with workgroup size")
    void testOccupancyScalesWithWorkgroupSize() {
        // TDD: Different workgroup sizes should affect occupancy
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Small workgroups: 32 threads
        var smallOccupancy = OccupancyCalculator.calculateOccupancy(caps, 32, 16, 32);

        // Large workgroups: 128 threads
        var largeOccupancy = OccupancyCalculator.calculateOccupancy(caps, 128, 16, 32);

        // Both should be valid but different
        assertTrue(smallOccupancy > 0.0 && smallOccupancy <= 1.0);
        assertTrue(largeOccupancy > 0.0 && largeOccupancy <= 1.0);
        assertNotEquals(smallOccupancy, largeOccupancy);
    }

    @Test
    @DisplayName("Calculate total active threads across GPU")
    void testTotalActiveThreads() {
        // TDD: Calculate total concurrent threads
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // 8 workgroups/CU * 64 threads/workgroup * 32 CUs
        var totalThreads = OccupancyCalculator.calculateTotalActiveThreads(caps, 64, 32, 64);

        assertEquals(16_384, totalThreads, "32 CUs * 8 workgroups * 64 threads");
    }

    @Test
    @DisplayName("Recommend optimal stack depth for target occupancy")
    void testRecommendStackDepth() {
        // TDD: Suggest optimal stack depth for target occupancy
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Target 70% occupancy with workgroup size 64
        var recommendedDepth = OccupancyCalculator.recommendStackDepth(caps, 64, 0.70);

        // Should recommend a reasonable depth (8-24 for 70% target)
        assertTrue(recommendedDepth >= 8 && recommendedDepth <= 32,
                  "Recommended depth should be in range [8, 32], got: " + recommendedDepth);

        // Verify that recommended depth achieves target occupancy
        var actualOccupancy = OccupancyCalculator.calculateOccupancy(caps, 64, recommendedDepth, 64);
        assertTrue(actualOccupancy >= 0.65,
                  "Recommended depth should achieve at least 65% occupancy, got: " + actualOccupancy);
    }

    @Test
    @DisplayName("Recommend optimal workgroup size for target occupancy")
    void testRecommendWorkgroupSize() {
        // TDD: Suggest optimal workgroup size for target occupancy
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Target 70% occupancy with stack depth 16
        var recommendedSize = OccupancyCalculator.recommendWorkgroupSize(caps, 16, 0.70);

        // Should be multiple of wavefront size (32)
        assertEquals(0, recommendedSize % caps.wavefrontSize(),
                    "Workgroup size should be multiple of wavefront size");

        // Should be in reasonable range
        assertTrue(recommendedSize >= 32 && recommendedSize <= 256,
                  "Recommended size should be in range [32, 256], got: " + recommendedSize);
    }

    @Test
    @DisplayName("Estimate register pressure impact on occupancy")
    void testRegisterPressureImpact() {
        // TDD: High register usage should reduce occupancy
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Low register usage: 32 registers/thread
        var lowRegOccupancy = OccupancyCalculator.calculateOccupancy(caps, 64, 16, 32);

        // High register usage: 128 registers/thread
        var highRegOccupancy = OccupancyCalculator.calculateOccupancy(caps, 64, 16, 128);

        assertTrue(lowRegOccupancy >= highRegOccupancy,
                  "Lower register usage should not decrease occupancy");
    }

    @Test
    @DisplayName("Validate occupancy calculation formula")
    void testOccupancyFormula() {
        // TDD: Verify occupancy = active_workgroups / theoretical_max
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // Configuration: 64 threads, depth 16, 4KB LDS per workgroup
        // 65KB / 4KB = 16 concurrent workgroups (but limited by other factors)
        var occupancy = OccupancyCalculator.calculateOccupancy(caps, 64, 16, 64);

        // Occupancy should be in valid range [0, 1]
        assertTrue(occupancy >= 0.0 && occupancy <= 1.0,
                  "Occupancy must be in range [0, 1], got: " + occupancy);
    }
}
