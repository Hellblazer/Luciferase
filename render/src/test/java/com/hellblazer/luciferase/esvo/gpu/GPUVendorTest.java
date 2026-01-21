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
package com.hellblazer.luciferase.esvo.gpu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.1.4 D1: GPUVendor Enum Tests
 *
 * Test coverage for GPU vendor enumeration and identification.
 *
 * @author hal.hildebrand
 */
@DisplayName("F3.1.4 D1: GPUVendor Tests")
class GPUVendorTest {

    // ==================== Vendor Identification Tests ====================

    @Test
    @DisplayName("Parse NVIDIA from device names")
    void testParseNvidia() {
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("NVIDIA Corporation"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("nvidia"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("NVIDIA GeForce RTX 3060"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("GeForce GTX 1080"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("Quadro P5000"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("Tesla V100"));
    }

    @Test
    @DisplayName("Parse AMD from device names")
    void testParseAmd() {
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("AMD"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("Advanced Micro Devices, Inc."));
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("AMD Radeon RX 6700 XT"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("Radeon Pro W6800"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("amd radeon graphics"));
    }

    @Test
    @DisplayName("Parse Intel from device names")
    void testParseIntel() {
        assertEquals(GPUVendor.INTEL, GPUVendor.fromString("Intel(R) Corporation"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromString("Intel Iris Xe Graphics"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromString("Intel(R) Arc(TM) A770 Graphics"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromString("Intel UHD Graphics 630"));
        assertEquals(GPUVendor.INTEL, GPUVendor.fromString("intel"));
    }

    @Test
    @DisplayName("Parse Apple from device names")
    void testParseApple() {
        assertEquals(GPUVendor.APPLE, GPUVendor.fromString("Apple"));
        assertEquals(GPUVendor.APPLE, GPUVendor.fromString("Apple M1"));
        assertEquals(GPUVendor.APPLE, GPUVendor.fromString("Apple M2 Max"));
        assertEquals(GPUVendor.APPLE, GPUVendor.fromString("apple metal"));
    }

    @Test
    @DisplayName("Parse UNKNOWN for unrecognized vendors")
    void testParseUnknown() {
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString("SomeUnknownVendor"));
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString(""));
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString(null));
        assertEquals(GPUVendor.UNKNOWN, GPUVendor.fromString("Qualcomm Adreno"));
    }

    @Test
    @DisplayName("Case-insensitive vendor parsing")
    void testCaseInsensitive() {
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("NVIDIA"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("nvidia"));
        assertEquals(GPUVendor.NVIDIA, GPUVendor.fromString("NvIdIa"));

        assertEquals(GPUVendor.AMD, GPUVendor.fromString("AMD"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("amd"));
        assertEquals(GPUVendor.AMD, GPUVendor.fromString("AmD"));
    }

    // ==================== Vendor Properties Tests ====================

    @Test
    @DisplayName("Get vendor identifiers")
    void testGetIdentifier() {
        assertEquals("nvidia", GPUVendor.NVIDIA.getIdentifier());
        assertEquals("amd", GPUVendor.AMD.getIdentifier());
        assertEquals("intel", GPUVendor.INTEL.getIdentifier());
        assertEquals("apple", GPUVendor.APPLE.getIdentifier());
        assertEquals("unknown", GPUVendor.UNKNOWN.getIdentifier());
    }

    @Test
    @DisplayName("Check workaround requirements")
    void testRequiresWorkarounds() {
        // NVIDIA is baseline - no workarounds needed
        assertFalse(GPUVendor.NVIDIA.requiresWorkarounds());

        // Other vendors need workarounds
        assertTrue(GPUVendor.AMD.requiresWorkarounds());
        assertTrue(GPUVendor.INTEL.requiresWorkarounds());
        assertTrue(GPUVendor.APPLE.requiresWorkarounds());

        // UNKNOWN defaults to safe path (no workarounds)
        assertFalse(GPUVendor.UNKNOWN.requiresWorkarounds());
    }

    @Test
    @DisplayName("Vendor toString format")
    void testToString() {
        assertTrue(GPUVendor.NVIDIA.toString().contains("NVIDIA"));
        assertTrue(GPUVendor.NVIDIA.toString().contains("nvidia"));

        assertTrue(GPUVendor.AMD.toString().contains("AMD"));
        assertTrue(GPUVendor.AMD.toString().contains("amd"));
    }
}
