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
 * TDD: Tests for GPUCapabilities record
 *
 * Stream B: GPU Workgroup Tuning - GPU Capabilities Detection
 * Tests GPU hardware capabilities for occupancy tuning
 *
 * @author hal.hildebrand
 */
@DisplayName("GPUCapabilities Tests")
class GPUCapabilitiesTest {

    @Test
    @DisplayName("Create GPUCapabilities with all fields")
    void testCreateCapabilities() {
        // TDD: Test record creation
        var caps = new GPUCapabilities(
            32,                    // computeUnits
            65536,                 // localMemoryBytes (64KB)
            65536,                 // maxRegisters
            GPUVendor.NVIDIA,
            "NVIDIA GeForce RTX 4090",
            32                     // wavefrontSize
        );

        assertEquals(32, caps.computeUnits());
        assertEquals(65536, caps.localMemoryBytes());
        assertEquals(65536, caps.maxRegisters());
        assertEquals(GPUVendor.NVIDIA, caps.vendor());
        assertEquals("NVIDIA GeForce RTX 4090", caps.model());
        assertEquals(32, caps.wavefrontSize());
    }

    @Test
    @DisplayName("GPUCapabilities equality based on all fields")
    void testCapabilitiesEquality() {
        // TDD: Test record equality
        var caps1 = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var caps2 = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var caps3 = new GPUCapabilities(16, 65536, 65536, GPUVendor.AMD, "RX 6900 XT", 64);

        assertEquals(caps1, caps2);
        assertNotEquals(caps1, caps3);
    }

    @Test
    @DisplayName("GPUCapabilities provides hash code")
    void testCapabilitiesHashCode() {
        // TDD: Test record hashCode
        var caps1 = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var caps2 = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        assertEquals(caps1.hashCode(), caps2.hashCode());
    }

    @Test
    @DisplayName("GPUCapabilities provides toString")
    void testCapabilitiesToString() {
        // TDD: Test record toString
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var str = caps.toString();

        assertTrue(str.contains("NVIDIA"));
        assertTrue(str.contains("RTX 4090"));
        assertTrue(str.contains("32"));
    }

    @Test
    @DisplayName("Calculate max concurrent workgroups")
    void testMaxConcurrentWorkgroups() {
        // TDD: Test max concurrent workgroups calculation
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // With 8KB per workgroup, 65KB local memory allows ~8 workgroups per CU
        var maxWorkgroups = caps.maxConcurrentWorkgroups(8192);
        assertEquals(8, maxWorkgroups, "65KB / 8KB = 8 workgroups");

        // With 16KB per workgroup, only 4 concurrent
        maxWorkgroups = caps.maxConcurrentWorkgroups(16384);
        assertEquals(4, maxWorkgroups, "65KB / 16KB = 4 workgroups");
    }

    @Test
    @DisplayName("Max workgroups is 0 when LDS exceeds available")
    void testMaxWorkgroupsExceedsMemory() {
        // TDD: Test that excessive LDS usage returns 0
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        var maxWorkgroups = caps.maxConcurrentWorkgroups(100_000);
        assertEquals(0, maxWorkgroups, "LDS > local memory should return 0");
    }

    @Test
    @DisplayName("Calculate total thread capacity")
    void testTotalThreadCapacity() {
        // TDD: Test total concurrent threads across all CUs
        var caps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);

        // 8 workgroups/CU * 32 CUs = 256 workgroups total
        // 256 workgroups * 64 threads = 16,384 threads
        var totalThreads = caps.totalThreadCapacity(8192, 64);
        assertEquals(16_384, totalThreads, "32 CUs * 8 workgroups * 64 threads");
    }

    @Test
    @DisplayName("Optimal workgroup size is multiple of wavefront")
    void testOptimalWorkgroupSize() {
        // TDD: Test optimal workgroup size selection
        var nvidiaCaps = new GPUCapabilities(32, 65536, 65536, GPUVendor.NVIDIA, "RTX 4090", 32);
        var amdCaps = new GPUCapabilities(60, 65536, 65536, GPUVendor.AMD, "RX 6900 XT", 64);

        // NVIDIA: wavefront=32, should prefer multiples of 32
        var nvidiaOptimal = nvidiaCaps.optimalWorkgroupSize();
        assertTrue(nvidiaOptimal % 32 == 0, "NVIDIA optimal should be multiple of 32");
        assertTrue(nvidiaOptimal >= 32 && nvidiaOptimal <= 256, "Should be in reasonable range");

        // AMD: wavefront=64, should prefer multiples of 64
        var amdOptimal = amdCaps.optimalWorkgroupSize();
        assertTrue(amdOptimal % 64 == 0, "AMD optimal should be multiple of 64");
        assertTrue(amdOptimal >= 64 && amdOptimal <= 256, "Should be in reasonable range");
    }

    @Test
    @DisplayName("Typical NVIDIA RTX 4090 capabilities")
    void testNvidiaRTX4090() {
        // TDD: Test realistic NVIDIA RTX 4090 config
        var caps = new GPUCapabilities(
            128,                   // 128 SMs
            65536,                 // 64KB shared memory per SM
            65536,                 // 65K registers
            GPUVendor.NVIDIA,
            "NVIDIA GeForce RTX 4090",
            32                     // 32-thread warp
        );

        assertEquals(128, caps.computeUnits());
        assertEquals(GPUVendor.NVIDIA, caps.vendor());
        assertEquals(32, caps.wavefrontSize());

        // 64 threads/workgroup, 8KB LDS -> 8 concurrent workgroups/SM
        var maxWorkgroups = caps.maxConcurrentWorkgroups(8192);
        assertEquals(8, maxWorkgroups);
    }

    @Test
    @DisplayName("Typical AMD RX 6900 XT capabilities")
    void testAmdRX6900XT() {
        // TDD: Test realistic AMD RX 6900 XT config
        var caps = new GPUCapabilities(
            80,                    // 80 CUs
            65536,                 // 64KB LDS per CU
            65536,                 // registers
            GPUVendor.AMD,
            "AMD Radeon RX 6900 XT",
            64                     // 64-thread wavefront
        );

        assertEquals(80, caps.computeUnits());
        assertEquals(GPUVendor.AMD, caps.vendor());
        assertEquals(64, caps.wavefrontSize());
    }

    @Test
    @DisplayName("Typical Intel Arc A770 capabilities")
    void testIntelArcA770() {
        // TDD: Test realistic Intel Arc A770 config
        var caps = new GPUCapabilities(
            32,                    // 32 Xe cores
            65536,                 // 64KB shared local memory
            32768,                 // 32K registers
            GPUVendor.INTEL,
            "Intel Arc A770",
            32                     // 32-thread SIMD
        );

        assertEquals(32, caps.computeUnits());
        assertEquals(GPUVendor.INTEL, caps.vendor());
        assertEquals(32, caps.wavefrontSize());
    }

    @Test
    @DisplayName("Typical Apple M2 Max capabilities")
    void testAppleM2Max() {
        // TDD: Test realistic Apple M2 Max config
        var caps = new GPUCapabilities(
            38,                    // 38 GPU cores
            32768,                 // 32KB threadgroup memory
            32768,                 // registers
            GPUVendor.APPLE,
            "Apple M2 Max",
            32                     // 32-thread SIMD
        );

        assertEquals(38, caps.computeUnits());
        assertEquals(GPUVendor.APPLE, caps.vendor());
        assertEquals(32, caps.wavefrontSize());
    }
}
