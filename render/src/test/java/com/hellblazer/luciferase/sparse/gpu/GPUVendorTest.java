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
 * TDD: Tests for GPUVendor enum
 *
 * Stream B: GPU Workgroup Tuning - D1 Infrastructure
 * Tests GPU vendor detection and identification
 *
 * @author hal.hildebrand
 */
@DisplayName("GPUVendor Detection Tests")
class GPUVendorTest {

    @Test
    @DisplayName("Detect NVIDIA from vendor string")
    void testDetectNvidia() {
        // TDD: Test NVIDIA vendor detection
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromVendorString("NVIDIA Corporation"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromVendorString("nvidia"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromVendorString("NVIDIA GeForce RTX 4090"));
    }

    @Test
    @DisplayName("Detect AMD from vendor string")
    void testDetectAmd() {
        // TDD: Test AMD vendor detection
        assertEquals(GPUVendor.AMD, GPUVendor.fromVendorString("Advanced Micro Devices, Inc."));
        assertEquals(GPUVendor.AMD, GPUVendor.fromVendorString("AMD"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromVendorString("amd radeon rx 6900 xt"));
    }

    @Test
    @DisplayName("Detect Intel from vendor string")
    void testDetectIntel() {
        // TDD: Test Intel vendor detection
        assertEquals(GPUVendor.INTEL, GPUVendor.fromVendorString("Intel(R) Corporation"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromVendorString("intel"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromVendorString("Intel Arc A770"));
    }

    @Test
    @DisplayName("Detect Apple from vendor string")
    void testDetectApple() {
        // TDD: Test Apple vendor detection
        assertEquals(GPUVendor.APPLE, GPUVendor.fromVendorString("Apple"));
        assertEquals(GPUVendor.APPLE, GPUVendor.fromVendorString("apple m1"));
        assertEquals(GPUVendor.APPLE, GPUVendor.fromVendorString("Apple M2 Max"));
    }

    @Test
    @DisplayName("Return UNKNOWN for unrecognized vendor")
    void testUnknownVendor() {
        // TDD: Test unknown vendor handling
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromVendorString("Some Unknown GPU"));
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromVendorString(""));
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromVendorString(null));
    }

    @Test
    @DisplayName("Vendor detection is case-insensitive")
    void testCaseInsensitive() {
        // TDD: Verify case-insensitive detection
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromVendorString("NVidia"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromVendorString("Amd"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromVendorString("INTEL"));
        assertEquals(GPUVendor.APPLE, GPUVendor.fromVendorString("aPpLe"));
    }

    @Test
    @DisplayName("Each vendor has a display name")
    void testVendorDisplayNames() {
        // TDD: Verify each vendor has a user-friendly name
        assertEquals("NVIDIA", GPUVendor.NVIDIA.getDisplayName());
        assertEquals("AMD", GPUVendor.AMD.getDisplayName());
        assertEquals("Intel", GPUVendor.INTEL.getDisplayName());
        assertEquals("Apple", GPUVendor.APPLE.getDisplayName());
        assertEquals("Unknown", GPUVendor.UNKNOWN.getDisplayName());
    }

    @Test
    @DisplayName("Vendor provides wavefront size hint")
    void testVendorWavefrontSize() {
        // TDD: Different vendors have different wavefront/warp sizes
        assertEquals(32, GPUVendor.NVIDIA.getTypicalWavefrontSize(), "NVIDIA uses 32-thread warps");
        assertEquals(64, GPUVendor.AMD.getTypicalWavefrontSize(), "AMD RDNA uses 64-thread wavefronts");
        assertEquals(32, GPUVendor.INTEL.getTypicalWavefrontSize(), "Intel Arc uses 32-thread SIMD");
        assertEquals(32, GPUVendor.APPLE.getTypicalWavefrontSize(), "Apple Silicon uses 32-thread SIMD");
        assertEquals(32, GPUVendor.UNKNOWN.getTypicalWavefrontSize(), "Unknown defaults to 32");
    }

    @Test
    @DisplayName("Vendor provides preferred workgroup sizes")
    void testPreferredWorkgroupSizes() {
        // TDD: Each vendor has preferred workgroup size multiples
        var nvidiaSizes = GPUVendor.NVIDIA.getPreferredWorkgroupSizes();
        assertNotNull(nvidiaSizes);
        assertTrue(nvidiaSizes.length > 0);
        assertTrue(nvidiaSizes[0] % 32 == 0, "NVIDIA sizes should be multiples of 32");

        var amdSizes = GPUVendor.AMD.getPreferredWorkgroupSizes();
        assertNotNull(amdSizes);
        assertTrue(amdSizes.length > 0);
        assertTrue(amdSizes[0] % 64 == 0, "AMD sizes should be multiples of 64");
    }
}
