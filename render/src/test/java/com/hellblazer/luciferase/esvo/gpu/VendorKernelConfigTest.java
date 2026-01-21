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
 * F3.1.4 D3: VendorKernelConfig Tests
 *
 * Test coverage for vendor-specific kernel configuration and workarounds.
 *
 * @author hal.hildebrand
 */
@DisplayName("F3.1.4 D3: VendorKernelConfig Tests")
class VendorKernelConfigTest {

    // ==================== Preprocessor Definition Tests ====================

    @Test
    @DisplayName("NVIDIA gets baseline preprocessor definitions")
    void testNvidiaPreprocessor() {
        var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
        var kernelSource = "void kernel() { }";

        var modified = config.applyPreprocessorDefinitions(kernelSource);

        assertTrue(modified.contains("#define GPU_VENDOR_NVIDIA 1"));
        assertTrue(modified.contains("void kernel() { }"));
    }

    @Test
    @DisplayName("AMD gets atomic workaround definitions")
    void testAmdPreprocessor() {
        var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
        var kernelSource = "void kernel() { }";

        var modified = config.applyPreprocessorDefinitions(kernelSource);

        assertTrue(modified.contains("#define GPU_VENDOR_AMD 1"));
        assertTrue(modified.contains("#define AMD_ATOMIC_WORKAROUND 1"));
        assertTrue(modified.contains("#define USE_RELAXED_ATOMICS 1"));
    }

    @Test
    @DisplayName("Intel gets precision workaround definitions")
    void testIntelPreprocessor() {
        var config = VendorKernelConfig.forVendor(GPUVendor.INTEL);
        var kernelSource = "void kernel() { }";

        var modified = config.applyPreprocessorDefinitions(kernelSource);

        assertTrue(modified.contains("#define GPU_VENDOR_INTEL 1"));
        assertTrue(modified.contains("#define INTEL_PRECISION_WORKAROUND 1"));
        assertTrue(modified.contains("#define RAY_EPSILON 1e-5f"));
    }

    @Test
    @DisplayName("Apple gets macOS workaround definitions")
    void testApplePreprocessor() {
        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var kernelSource = "void kernel() { }";

        var modified = config.applyPreprocessorDefinitions(kernelSource);

        assertTrue(modified.contains("#define GPU_VENDOR_APPLE 1"));
        assertTrue(modified.contains("#define APPLE_MACOS_WORKAROUND 1"));
        assertTrue(modified.contains("#define USE_INTEGER_ABS 1"));
        assertTrue(modified.contains("#define METAL_COMPUTE_COORD_SPACE 1"));
    }

    // ==================== Source Workaround Tests ====================

    @Test
    @DisplayName("Apple workaround replaces fabs() with conditional")
    void testAppleFabsWorkaround() {
        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var kernelSource = """
            void kernel() {
                if (fabs(x) < EPSILON) {
                    // do something
                }
            }
            """;

        var modified = config.applyWorkarounds(kernelSource);

        // fabs(x) < EPSILON should be replaced with (x < EPSILON && x > -EPSILON)
        assertTrue(modified.contains("(x < EPSILON && x > -EPSILON)"));
        assertFalse(modified.contains("fabs(x)"));
    }

    @Test
    @DisplayName("NVIDIA workarounds are no-op")
    void testNvidiaNoWorkarounds() {
        var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
        var kernelSource = "void kernel() { }";

        var modified = config.applyWorkarounds(kernelSource);

        assertEquals(kernelSource, modified, "NVIDIA should not modify kernel source");
    }

    @Test
    @DisplayName("AMD workarounds preserve source (rely on preprocessor)")
    void testAmdWorkarounds() {
        var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
        var kernelSource = "void kernel() { atomic_add(&x, 1); }";

        var modified = config.applyWorkarounds(kernelSource);

        // For now, AMD workarounds are in preprocessor definitions
        assertEquals(kernelSource, modified);
    }

    // ==================== Compiler Flags Tests ====================

    @Test
    @DisplayName("NVIDIA gets aggressive optimization flags")
    void testNvidiaCompilerFlags() {
        var config = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
        var flags = config.getCompilerFlags();

        assertTrue(flags.contains("-cl-fast-relaxed-math"));
        assertTrue(flags.contains("-cl-mad-enable"));
    }

    @Test
    @DisplayName("AMD gets aggressive optimization flags including unsafe-math")
    void testAmdCompilerFlags() {
        var config = VendorKernelConfig.forVendor(GPUVendor.AMD);
        var flags = config.getCompilerFlags();

        assertTrue(flags.contains("-cl-fast-relaxed-math"));
        assertTrue(flags.contains("-cl-mad-enable"));
        assertTrue(flags.contains("-cl-unsafe-math-optimizations"));
    }

    @Test
    @DisplayName("Intel gets conservative optimization flags")
    void testIntelCompilerFlags() {
        var config = VendorKernelConfig.forVendor(GPUVendor.INTEL);
        var flags = config.getCompilerFlags();

        assertTrue(flags.contains("-cl-fast-relaxed-math"));
        assertFalse(flags.contains("-cl-unsafe-math-optimizations"),
                   "Intel should not use unsafe-math due to precision issues");
    }

    @Test
    @DisplayName("Apple gets minimal flags (OpenCL deprecated)")
    void testAppleCompilerFlags() {
        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var flags = config.getCompilerFlags();

        assertEquals("", flags, "Apple should use minimal flags");
    }

    // ==================== Full Pipeline Tests ====================

    @Test
    @DisplayName("Full pipeline: preprocessor + workarounds for Apple")
    void testAppleFullPipeline() {
        var config = VendorKernelConfig.forVendor(GPUVendor.APPLE);
        var kernelSource = """
            void rayIntersect() {
                if (fabs(distance) < EPSILON) {
                    return false;
                }
            }
            """;

        // Apply preprocessor definitions
        var withPreprocessor = config.applyPreprocessorDefinitions(kernelSource);
        assertTrue(withPreprocessor.contains("#define GPU_VENDOR_APPLE 1"));
        assertTrue(withPreprocessor.contains("#define APPLE_MACOS_WORKAROUND 1"));

        // Apply workarounds
        var final_source = config.applyWorkarounds(withPreprocessor);
        assertTrue(final_source.contains("(distance < EPSILON && distance > -EPSILON)"));
        assertFalse(final_source.contains("fabs(distance)"));
    }

    @Test
    @DisplayName("Factory method creates config for detected GPU")
    void testFactoryForDetectedGPU() {
        var config = VendorKernelConfig.forDetectedGPU();

        assertNotNull(config);
        assertNotNull(config.getVendor());
        assertNotNull(config.getCapabilities());
    }

    @Test
    @DisplayName("Factory method creates config for specific vendor")
    void testFactoryForVendor() {
        var nvidiaConfig = VendorKernelConfig.forVendor(GPUVendor.NVIDIA);
        assertEquals(GPUVendor.NVIDIA, nvidiaConfig.getVendor());

        var amdConfig = VendorKernelConfig.forVendor(GPUVendor.AMD);
        assertEquals(GPUVendor.AMD, amdConfig.getVendor());
    }
}
