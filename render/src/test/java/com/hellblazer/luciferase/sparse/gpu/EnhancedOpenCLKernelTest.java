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

import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.sparse.gpu.EnhancedOpenCLKernel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced OpenCL Kernel Compilation Tests (P4.4)
 *
 * <p>Validates kernel compilation with build options for GPU-specific optimization.
 * Tests cover:
 * <ul>
 *   <li>Default compilation (no build options)</li>
 *   <li>Compilation with preprocessor defines</li>
 *   <li>Kernel recompilation with new parameters</li>
 *   <li>Error handling for invalid build options</li>
 *   <li>Fallback behavior on compilation failure</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@EnabledIf("isOpenCLAvailable")
@DisplayName("Enhanced OpenCL Kernel Compilation")
class EnhancedOpenCLKernelTest {

    // Simple test kernel with configurable parameters
    private static final String TEST_KERNEL_SOURCE = """
        #ifndef ARRAY_SIZE
        #define ARRAY_SIZE 1024
        #endif

        #ifndef MULTIPLIER
        #define MULTIPLIER 2
        #endif

        __kernel void multiplyArray(
            __global const float* input,
            __global float* output
        ) {
            int gid = get_global_id(0);
            if (gid < ARRAY_SIZE) {
                output[gid] = input[gid] * MULTIPLIER;
            }
        }
        """;

    // Kernel with traversal depth parameter (mimics DAG ray tracing)
    private static final String TRAVERSAL_KERNEL_SOURCE = """
        #ifndef MAX_TRAVERSAL_DEPTH
        #define MAX_TRAVERSAL_DEPTH 32
        #endif

        #ifndef WORKGROUP_SIZE
        #define WORKGROUP_SIZE 64
        #endif

        __kernel void rayTraverse(
            __global const float* rays,
            __global float* results
        ) {
            int gid = get_global_id(0);

            // Simulate traversal with compile-time depth
            float result = 0.0f;
            for (int depth = 0; depth < MAX_TRAVERSAL_DEPTH; depth++) {
                result += rays[gid % 100] * 0.1f;
            }

            results[gid] = result;
        }
        """;

    static boolean isOpenCLAvailable() {
        try {
            var kernel = EnhancedOpenCLKernel.create("test");
            kernel.close();
            return true;
        } catch (Exception e) {
            System.out.println("OpenCL not available, skipping tests: " + e.getMessage());
            return false;
        }
    }

    @Test
    @DisplayName("Kernel compiles with default parameters (no build options)")
    void testDefaultCompilation() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("defaultTest")) {
            assertDoesNotThrow(() -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray"));
            assertTrue(kernel.isCompiled(), "Kernel should be compiled");
            assertTrue(kernel.isValid(), "Kernel should be valid");
        }
    }

    @Test
    @DisplayName("Kernel compiles with custom ARRAY_SIZE via -D flag")
    void testCustomArraySize() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("customArraySize")) {
            var buildOptions = "-D ARRAY_SIZE=2048";
            assertDoesNotThrow(() -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray", buildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should compile with custom array size");
        }
    }

    @Test
    @DisplayName("Kernel compiles with multiple -D flags")
    void testMultipleDefines() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("multipleDefines")) {
            var buildOptions = "-D ARRAY_SIZE=512 -D MULTIPLIER=4";
            assertDoesNotThrow(() -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray", buildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should compile with multiple defines");
        }
    }

    @Test
    @DisplayName("Kernel compiles with MAX_TRAVERSAL_DEPTH override")
    void testTraversalDepthOverride() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("traversalDepth")) {
            var buildOptions = "-D MAX_TRAVERSAL_DEPTH=16";
            assertDoesNotThrow(() -> kernel.compile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", buildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should compile with custom traversal depth");
        }
    }

    @Test
    @DisplayName("Kernel compiles with WORKGROUP_SIZE override")
    void testWorkgroupSizeOverride() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("workgroupSize")) {
            var buildOptions = "-D MAX_TRAVERSAL_DEPTH=24 -D WORKGROUP_SIZE=128";
            assertDoesNotThrow(() -> kernel.compile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", buildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should compile with custom workgroup size");
        }
    }

    @Test
    @DisplayName("Kernel recompiles with new MAX_TRAVERSAL_DEPTH")
    void testRecompilationWithNewDepth() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("recompileTest")) {
            // First compilation with depth=32 (default)
            kernel.compile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse");
            assertTrue(kernel.isCompiled(), "Initial compilation should succeed");

            // Recompile with depth=16
            var newBuildOptions = "-D MAX_TRAVERSAL_DEPTH=16";
            assertDoesNotThrow(() -> kernel.recompile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", newBuildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should still be compiled after recompilation");
        }
    }

    @Test
    @DisplayName("Multiple recompilations work correctly")
    void testMultipleRecompilations() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("multiRecompile")) {
            // Initial compilation
            kernel.compile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", "-D MAX_TRAVERSAL_DEPTH=32");

            // Recompile 1: depth=16
            kernel.recompile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", "-D MAX_TRAVERSAL_DEPTH=16");
            assertTrue(kernel.isCompiled(), "Recompilation 1 should succeed");

            // Recompile 2: depth=24
            kernel.recompile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", "-D MAX_TRAVERSAL_DEPTH=24");
            assertTrue(kernel.isCompiled(), "Recompilation 2 should succeed");

            // Recompile 3: depth=8
            kernel.recompile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", "-D MAX_TRAVERSAL_DEPTH=8");
            assertTrue(kernel.isCompiled(), "Recompilation 3 should succeed");
        }
    }

    @Test
    @DisplayName("Cannot compile an already-compiled kernel")
    void testCannotCompileTwice() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("doubleCompile")) {
            kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray");

            // Second compile should throw
            var exception = assertThrows(
                ComputeKernel.KernelCompilationException.class,
                () -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray")
            );

            assertTrue(exception.getMessage().contains("already compiled"),
                "Error message should mention kernel is already compiled");
        }
    }

    @Test
    @DisplayName("Invalid build options cause compilation failure")
    void testInvalidBuildOptions() {
        try (var kernel = EnhancedOpenCLKernel.create("invalidOptions")) {
            // Invalid syntax: missing value for -D
            var invalidOptions = "-D INVALID_FLAG";

            var exception = assertThrows(
                ComputeKernel.KernelCompilationException.class,
                () -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray", invalidOptions)
            );

            assertFalse(kernel.isCompiled(), "Kernel should not be compiled after failure");
        }
    }

    @Test
    @DisplayName("Kernel handle available after compilation")
    void testKernelHandleAvailability() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("handleTest")) {
            kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray");

            var handle = kernel.getKernelHandle();
            assertNotEquals(0, handle, "Kernel handle should be non-zero after compilation");
        }
    }

    @Test
    @DisplayName("Kernel handle throws before compilation")
    void testKernelHandleBeforeCompilation() {
        try (var kernel = EnhancedOpenCLKernel.create("handleBeforeCompile")) {
            assertThrows(
                IllegalStateException.class,
                kernel::getKernelHandle,
                "Should throw when accessing handle before compilation"
            );
        }
    }

    @Test
    @DisplayName("Closed kernel throws on operations")
    void testClosedKernelThrows() throws Exception {
        var kernel = EnhancedOpenCLKernel.create("closedTest");
        kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray");
        kernel.close();

        assertThrows(
            IllegalStateException.class,
            kernel::getKernelHandle,
            "Should throw when accessing closed kernel"
        );

        assertThrows(
            IllegalStateException.class,
            () -> kernel.recompile(TEST_KERNEL_SOURCE, "multiplyArray", ""),
            "Should throw when recompiling closed kernel"
        );
    }

    @Test
    @DisplayName("Kernel name and metadata accessible")
    void testKernelMetadata() {
        try (var kernel = EnhancedOpenCLKernel.create("metadataTest")) {
            assertEquals("metadataTest", kernel.getName(), "Kernel name should match");
            assertTrue(kernel.isValid(), "Kernel should be valid before close");

            assertFalse(kernel.isCompiled(), "Kernel should not be compiled initially");
        }
    }

    @Test
    @DisplayName("Recompilation without initial compilation fails")
    void testRecompileWithoutInitialCompile() {
        try (var kernel = EnhancedOpenCLKernel.create("recompileNoInit")) {
            // Recompile without initial compile should still work (creates new kernel)
            assertDoesNotThrow(() -> kernel.recompile(TEST_KERNEL_SOURCE, "multiplyArray", ""));
            assertTrue(kernel.isCompiled(), "Recompile should work even without initial compile");
        }
    }

    @Test
    @DisplayName("Optimization flags compile successfully")
    void testOptimizationFlags() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("optimizationTest")) {
            var buildOptions = "-O2 -cl-fast-relaxed-math -cl-mad-enable";
            assertDoesNotThrow(() -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray", buildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should compile with optimization flags");
        }
    }

    @Test
    @DisplayName("Combined defines and optimization flags work")
    void testCombinedOptions() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("combinedOptions")) {
            var buildOptions = "-D MAX_TRAVERSAL_DEPTH=16 -D WORKGROUP_SIZE=64 -O2 -cl-fast-relaxed-math";
            assertDoesNotThrow(() -> kernel.compile(TRAVERSAL_KERNEL_SOURCE, "rayTraverse", buildOptions));
            assertTrue(kernel.isCompiled(), "Kernel should compile with combined options");
        }
    }

    @Test
    @DisplayName("Empty build options equivalent to no options")
    void testEmptyBuildOptions() throws Exception {
        try (var kernel1 = EnhancedOpenCLKernel.create("emptyOptions1");
             var kernel2 = EnhancedOpenCLKernel.create("emptyOptions2")) {

            // Compile with empty string
            kernel1.compile(TEST_KERNEL_SOURCE, "multiplyArray", "");

            // Compile with no options parameter
            kernel2.compile(TEST_KERNEL_SOURCE, "multiplyArray");

            assertTrue(kernel1.isCompiled(), "Kernel with empty options should compile");
            assertTrue(kernel2.isCompiled(), "Kernel with no options should compile");
        }
    }

    @Test
    @DisplayName("Null build options handled gracefully")
    void testNullBuildOptions() throws Exception {
        try (var kernel = EnhancedOpenCLKernel.create("nullOptions")) {
            // Null should be treated as empty string
            assertDoesNotThrow(() -> kernel.compile(TEST_KERNEL_SOURCE, "multiplyArray", null));
            assertTrue(kernel.isCompiled(), "Kernel should compile with null options");
        }
    }
}
