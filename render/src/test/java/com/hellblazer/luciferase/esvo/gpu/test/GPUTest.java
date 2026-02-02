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
package com.hellblazer.luciferase.esvo.gpu.test;

import com.hellblazer.luciferase.esvo.gpu.GPUVendor;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/**
 * D2: Annotation for GPU-dependent tests.
 * <p>
 * Tests annotated with @GPUTest are conditionally executed based on:
 * <ul>
 *   <li>RUN_GPU_TESTS environment variable (must be "true" to run)</li>
 *   <li>CI environment detection (skipped in CI by default)</li>
 *   <li>OpenCL availability on the system</li>
 *   <li>GPU vendor requirements (if specified)</li>
 *   <li>GPU capability requirements (FP16, FP64, compute units, VRAM)</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @GPUTest
 * void testBasicGPUOperation() {
 *     // Runs on any available GPU when RUN_GPU_TESTS=true
 * }
 *
 * @GPUTest(vendor = GPUVendor.NVIDIA)
 * void testNvidiaSpecific() {
 *     // Only runs on NVIDIA GPUs
 * }
 *
 * @GPUTest(requiresFloat16 = true, minVRAMBytes = 4L * 1024 * 1024 * 1024)
 * void testHighEndGPU() {
 *     // Requires FP16 support and at least 4GB VRAM
 * }
 * }
 * </pre>
 *
 * @author hal.hildebrand
 * @see GPUTestExtension
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(GPUTestExtension.class)
public @interface GPUTest {

    /**
     * Required GPU vendor. Use GPUVendor.UNKNOWN for "any vendor" (default).
     * When set to a specific vendor, the test only runs if that vendor's GPU is detected.
     */
    GPUVendor vendor() default GPUVendor.UNKNOWN;

    /**
     * Minimum OpenCL version required (e.g., "1.2", "2.0", "3.0").
     * Default is "1.2" which is supported by all major vendors.
     */
    String minOpenCLVersion() default "1.2";

    /**
     * Whether the test requires FP16 (half-precision float) support.
     */
    boolean requiresFloat16() default false;

    /**
     * Whether the test requires FP64 (double-precision float) support.
     */
    boolean requiresFloat64() default false;

    /**
     * Minimum number of compute units required.
     * Default is 1 (any GPU with at least one compute unit).
     */
    int minComputeUnits() default 1;

    /**
     * Minimum VRAM in bytes required.
     * Default is 0 (no minimum).
     * Use constants like 4L * 1024 * 1024 * 1024 for 4GB.
     */
    long minVRAMBytes() default 0;

    /**
     * Whether to allow running in CI environment.
     * Default is false - tests are skipped in CI (where CI=true env var is set).
     * Set to true for tests that can run with mock GPU or software rendering.
     */
    boolean allowInCI() default false;
}
