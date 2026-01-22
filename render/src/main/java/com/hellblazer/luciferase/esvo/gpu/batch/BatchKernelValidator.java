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
package com.hellblazer.luciferase.esvo.gpu.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Phase 4.2.2d: Batch Kernel Validation
 *
 * Validates that batch kernel produces identical results to single-ray kernel.
 * Essential for correctness verification before deploying batch optimization.
 *
 * Validation strategy:
 * 1. Compare ray result counts (must be equal)
 * 2. Compare each result pixel-by-pixel (hit position + distance)
 * 3. Use small epsilon for floating-point comparison
 * 4. Track mismatches for debugging
 *
 * @author hal.hildebrand
 */
public class BatchKernelValidator {
    private static final Logger log = LoggerFactory.getLogger(BatchKernelValidator.class);

    // Epsilon tolerance for floating-point comparison
    // Using relative epsilon to handle both small and large values
    // More lenient tolerance to account for floating-point rounding in both kernels
    private static final float RELATIVE_EPSILON = 1e-3f;  // 0.1% relative error
    private static final float ABSOLUTE_EPSILON = 1e-5f;  // Absolute epsilon for near-zero values
    private int mismatchCount = 0;
    private int totalComparisons = 0;

    /**
     * Validate batch kernel results against single-ray baseline.
     *
     * Each ray produces an IntersectionResult: (hitX, hitY, hitZ, distance) = 4 floats
     *
     * @param singleRayResults  Results from single-ray kernel
     * @param batchResults      Results from batch kernel
     * @param rayCount          Number of rays processed
     * @return true if all results match within epsilon
     */
    public boolean validateResults(ByteBuffer singleRayResults, ByteBuffer batchResults, int rayCount) {
        if (singleRayResults == null || batchResults == null) {
            log.warn("Validation: null buffers provided");
            return false;
        }

        // Each result is 4 floats (16 bytes)
        int expectedSize = rayCount * 16;
        if (singleRayResults.remaining() < expectedSize || batchResults.remaining() < expectedSize) {
            log.warn("Validation: insufficient data - expected {} bytes per buffer", expectedSize);
            return false;
        }

        mismatchCount = 0;
        totalComparisons = 0;

        // Compare each result
        // Create read-only views and reset positions
        ByteBuffer singleRayRO = singleRayResults.asReadOnlyBuffer();
        ByteBuffer batchRO = batchResults.asReadOnlyBuffer();
        singleRayRO.rewind();
        batchRO.rewind();

        FloatBuffer singleRayBuf = singleRayRO.asFloatBuffer();
        FloatBuffer batchBuf = batchRO.asFloatBuffer();

        for (int i = 0; i < rayCount; i++) {
            totalComparisons++;

            // Read single-ray result (hitX, hitY, hitZ, distance)
            float srHitX = singleRayBuf.get();
            float srHitY = singleRayBuf.get();
            float srHitZ = singleRayBuf.get();
            float srDistance = singleRayBuf.get();

            // Read batch result
            float brHitX = batchBuf.get();
            float brHitY = batchBuf.get();
            float brHitZ = batchBuf.get();
            float brDistance = batchBuf.get();

            // Compare with epsilon tolerance
            if (!floatEquals(srHitX, brHitX) ||
                !floatEquals(srHitY, brHitY) ||
                !floatEquals(srHitZ, brHitZ) ||
                !floatEquals(srDistance, brDistance)) {

                mismatchCount++;

                if (mismatchCount <= 10) {  // Log first 10 mismatches
                    log.warn("Result mismatch at ray {}: "
                            + "single-ray=(%.3f,%.3f,%.3f,%.1f) "
                            + "batch=(%.3f,%.3f,%.3f,%.1f)",
                        i, srHitX, srHitY, srHitZ, srDistance,
                        brHitX, brHitY, brHitZ, brDistance);
                }
            }
        }

        boolean allMatch = (mismatchCount == 0);

        if (allMatch) {
            log.info("Batch kernel validation PASSED: {} rays verified ✓", rayCount);
        } else {
            log.error("Batch kernel validation FAILED: {} mismatches out of {} rays ({}%)",
                mismatchCount, totalComparisons,
                String.format("%.1f", (mismatchCount * 100.0) / totalComparisons));
        }

        return allMatch;
    }

    /**
     * Compare two floats with epsilon tolerance.
     *
     * Uses combined absolute and relative epsilon for robust comparison:
     * - Absolute epsilon for values near zero
     * - Relative epsilon for larger values
     *
     * @param a first value
     * @param b second value
     * @return true if values are approximately equal
     */
    private boolean floatEquals(float a, float b) {
        // Handle special cases (both exactly equal)
        if (a == b) {
            return true;
        }

        float diff = Math.abs(a - b);

        // Absolute epsilon check (handles values near zero)
        if (diff < ABSOLUTE_EPSILON) {
            return true;
        }

        // Relative epsilon check (handles larger values)
        float absA = Math.abs(a);
        float absB = Math.abs(b);
        float maxAbs = Math.max(absA, absB);

        return diff / maxAbs < RELATIVE_EPSILON;
    }

    /**
     * Get validation statistics.
     *
     * @return summary string of validation results
     */
    public String getValidationSummary() {
        if (totalComparisons == 0) {
            return "No validations performed";
        }

        double successPercent = ((totalComparisons - mismatchCount) * 100.0) / totalComparisons;
        return String.format(
            "Validation Summary: %d/%d rays matched (%.1f%%), %d mismatches",
            totalComparisons - mismatchCount, totalComparisons, successPercent, mismatchCount
        );
    }

    /**
     * Reset validation counters.
     */
    public void reset() {
        mismatchCount = 0;
        totalComparisons = 0;
    }
}
