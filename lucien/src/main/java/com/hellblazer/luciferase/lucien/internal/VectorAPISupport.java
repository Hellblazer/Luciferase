/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of Luciferase.
 *
 * Luciferase is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Luciferase is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Luciferase. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime detection and configuration for Java Vector API (SIMD) support.
 * <p>
 * This class provides:
 * <ul>
 *   <li>Runtime detection of Vector API availability</li>
 *   <li>CPU capability detection (AVX512, AVX2, scalar)</li>
 *   <li>Dynamic enable/disable of SIMD optimizations</li>
 *   <li>Graceful fallback to scalar implementations</li>
 * </ul>
 * <p>
 * The Vector API (JEP 448) is incubator status in Java 25, requiring:
 * <code>--add-modules jdk.incubator.vector</code>
 * <p>
 * SIMD is disabled by default. Enable via system property:
 * <code>-Dlucien.enableSIMD=true</code>
 * <p>
 * Example usage:
 * <pre>{@code
 * if (VectorAPISupport.isAvailable()) {
 *     // Use SIMD-optimized Morton encoder
 *     encoder = new SIMDMortonEncoder();
 * } else {
 *     // Fallback to scalar implementation
 *     encoder = new ScalarMortonEncoder();
 * }
 * }</pre>
 *
 * @author Hal Hildebrand
 * @since Epic 1 - SIMD Acceleration
 */
public class VectorAPISupport {
    private static final Logger            log                = LoggerFactory.getLogger(VectorAPISupport.class);
    private static final String            ENABLE_PROPERTY    = "lucien.enableSIMD";
    private static final boolean           VECTOR_API_PRESENT;
    private static final VectorCapability  CPU_CAPABILITY;
    private static final String            STATUS_MESSAGE;
    private static volatile boolean        enabled            = false;

    static {
        // Detect Vector API availability at class load time
        boolean apiPresent = false;
        try {
            Class.forName("jdk.incubator.vector.Vector");
            apiPresent = true;
            log.debug("Vector API detected in classpath");
        } catch (ClassNotFoundException e) {
            log.debug("Vector API not available (--add-modules jdk.incubator.vector not set)");
        }
        VECTOR_API_PRESENT = apiPresent;

        // Detect CPU vector capabilities
        CPU_CAPABILITY = detectCPUCapability();

        // Build status message
        STATUS_MESSAGE = buildStatusMessage();

        // Check if SIMD is enabled via system property
        var enabledProperty = System.getProperty(ENABLE_PROPERTY);
        if ("true".equalsIgnoreCase(enabledProperty)) {
            enabled = VECTOR_API_PRESENT;
            if (enabled) {
                log.info("SIMD acceleration ENABLED via system property (CPU: {})", CPU_CAPABILITY);
            } else {
                log.warn("SIMD requested but Vector API not available");
            }
        } else {
            log.debug("SIMD acceleration disabled (default). Set -D{}=true to enable", ENABLE_PROPERTY);
        }
    }

    /**
     * Checks if SIMD optimizations are available and enabled.
     * <p>
     * Returns true only if:
     * <ul>
     *   <li>Vector API is in classpath (--add-modules jdk.incubator.vector)</li>
     *   <li>SIMD is enabled via setEnabled(true) or -Dlucien.enableSIMD=true</li>
     * </ul>
     *
     * @return true if SIMD optimizations should be used
     */
    public static boolean isAvailable() {
        return VECTOR_API_PRESENT && enabled;
    }

    /**
     * Checks if Vector API is present in classpath (regardless of enabled state).
     *
     * @return true if Vector API classes can be loaded
     */
    public static boolean isVectorAPIPresent() {
        return VECTOR_API_PRESENT;
    }

    /**
     * Gets the detected CPU vector capability level.
     *
     * @return CPU capability (AVX512, AVX2, or SCALAR)
     */
    public static VectorCapability getCPUCapability() {
        return CPU_CAPABILITY;
    }

    /**
     * Gets a human-readable status message about SIMD availability.
     *
     * @return status message describing current SIMD state
     */
    public static String getStatus() {
        return STATUS_MESSAGE;
    }

    /**
     * Enables or disables SIMD optimizations at runtime.
     * <p>
     * This only works if Vector API is available. Setting to true when
     * Vector API is not present has no effect.
     *
     * @param enable true to enable SIMD, false to disable
     * @return true if SIMD is now enabled, false otherwise
     */
    public static boolean setEnabled(boolean enable) {
        if (enable && !VECTOR_API_PRESENT) {
            log.warn("Cannot enable SIMD: Vector API not available");
            return false;
        }
        enabled = enable;
        log.info("SIMD acceleration {}", enabled ? "ENABLED" : "DISABLED");
        return enabled;
    }

    /**
     * Detects CPU vector capabilities.
     * <p>
     * Uses os.arch and java.vm.name to infer CPU capabilities.
     * This is a heuristic - actual capabilities may vary.
     *
     * @return detected vector capability level
     */
    private static VectorCapability detectCPUCapability() {
        var arch = System.getProperty("os.arch", "").toLowerCase();
        var vmName = System.getProperty("java.vm.name", "").toLowerCase();

        // ARM64 (Apple M-series, ARM Neoverse)
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            log.debug("Detected ARM64 architecture - supports NEON SIMD");
            return VectorCapability.ARM_NEON;
        }

        // x86_64 - try to detect AVX512 vs AVX2
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            // Heuristic: Xeon Scalable, EPYC Milan+ typically have AVX512
            // This is conservative - may miss some AVX512 CPUs
            if (vmName.contains("graal") || vmName.contains("native")) {
                // GraalVM Native Image - conservative assumption
                log.debug("Detected x86_64 with GraalVM - assuming AVX2");
                return VectorCapability.AVX2;
            }

            // Assume modern x86_64 has at least AVX2 (2013+)
            log.debug("Detected x86_64 architecture - assuming AVX2 minimum");
            return VectorCapability.AVX2;
        }

        // Unknown architecture - scalar fallback
        log.debug("Unknown architecture: {} - using scalar", arch);
        return VectorCapability.SCALAR;
    }

    /**
     * Builds a human-readable status message.
     *
     * @return status description
     */
    private static String buildStatusMessage() {
        if (!VECTOR_API_PRESENT) {
            return "Vector API not available (--add-modules jdk.incubator.vector required)";
        }

        if (enabled) {
            return "SIMD enabled (" + CPU_CAPABILITY + ")";
        }

        return "SIMD available but disabled (set -D" + ENABLE_PROPERTY + "=true to enable)";
    }

    /**
     * CPU vector capability levels.
     */
    public enum VectorCapability {
        /** No SIMD support - scalar operations only */
        SCALAR("Scalar (no SIMD)", 1),

        /** x86_64 AVX2 (256-bit vectors) - Intel Haswell 2013+, AMD Excavator 2015+ */
        AVX2("AVX2 (256-bit)", 4),

        /** x86_64 AVX-512 (512-bit vectors) - Intel Xeon Scalable, AMD Zen 4+ */
        AVX512("AVX-512 (512-bit)", 8),

        /** ARM NEON (128-bit vectors) - ARM Cortex-A series, Apple M-series */
        ARM_NEON("ARM NEON (128-bit)", 2);

        private final String description;
        private final int    lanes;  // Typical vector lanes for 64-bit elements

        VectorCapability(String description, int lanes) {
            this.description = description;
            this.lanes = lanes;
        }

        public String getDescription() {
            return description;
        }

        /**
         * Typical number of 64-bit lanes for this capability.
         * Used for batch sizing in SIMD operations.
         */
        public int getLanes() {
            return lanes;
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
