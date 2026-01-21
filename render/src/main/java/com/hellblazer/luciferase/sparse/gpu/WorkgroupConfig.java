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

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * GPU Workgroup Configuration
 *
 * Stream B Phase 4: Workgroup Tuning Parameters
 * Encapsulates tuning parameters and performance expectations for GPU kernels
 *
 * @param workgroupSize       Number of threads per workgroup (32, 64, 128, 256)
 * @param maxTraversalDepth   Maximum stack depth for ray traversal (8-32)
 * @param expectedOccupancy   Expected GPU occupancy [0.5, 1.0]
 * @param expectedThroughput  Expected throughput in rays/microsecond
 * @param notes               Human-readable configuration notes
 *
 * @author hal.hildebrand
 */
public record WorkgroupConfig(
    int workgroupSize,
    int maxTraversalDepth,
    float expectedOccupancy,
    float expectedThroughput,
    String notes
) {

    /**
     * Calculate Local Data Share (LDS) memory usage per workgroup
     *
     * LDS usage = 4 bytes per stack entry × depth × threads
     * Stack stores uint32 node indices (4 bytes each)
     *
     * @return LDS usage in bytes
     */
    @JsonIgnore
    public int calculateLdsUsage() {
        // Stack: 4 bytes per entry (uint32) × depth × threads
        return 4 * maxTraversalDepth * workgroupSize;
    }

    /**
     * Validate workgroup size is in acceptable range
     *
     * Valid sizes: 32, 64, 96, 128, 192, 256
     * Must be multiple of 32 (typical wavefront/warp size)
     *
     * @return true if workgroup size is valid
     */
    @JsonIgnore
    public boolean isValidWorkgroupSize() {
        if (workgroupSize <= 0 || workgroupSize > 256) {
            return false;
        }

        // Must be multiple of 32 (common wavefront size across vendors)
        return workgroupSize % 32 == 0;
    }

    /**
     * Validate traversal depth is in acceptable range
     *
     * Valid depths: 8-32
     * - 8:  Minimal, for simple scenes (256³ voxels)
     * - 16: Standard, for most scenes (65K³ voxels)
     * - 32: Maximum, for extremely detailed scenes (4B³ voxels)
     *
     * @return true if depth is valid
     */
    @JsonIgnore
    public boolean isValidDepth() {
        return maxTraversalDepth >= 8 && maxTraversalDepth <= 32;
    }

    /**
     * Factory method: Create optimal configuration for a GPU device
     *
     * Uses OccupancyCalculator to find configuration that:
     * - Achieves target 70% occupancy
     * - Balances LDS usage and thread count
     * - Respects vendor-specific preferences
     *
     * @param capabilities GPU hardware capabilities
     * @return optimal workgroup configuration
     */
    public static WorkgroupConfig forDevice(GPUCapabilities capabilities) {
        // Target 70% occupancy (good balance between occupancy and resource usage)
        final var targetOccupancy = 0.70;

        // Start with vendor's optimal workgroup size
        var workgroupSize = capabilities.optimalWorkgroupSize();

        // Use OccupancyCalculator to find optimal stack depth for this workgroup size
        var stackDepth = OccupancyCalculator.recommendStackDepth(
            capabilities, workgroupSize, targetOccupancy
        );

        // Calculate actual expected occupancy with chosen parameters
        var expectedOccupancy = (float) OccupancyCalculator.calculateOccupancy(
            capabilities, workgroupSize, stackDepth, 64 // assume 64 registers/thread
        );

        // Estimate throughput based on compute units and occupancy
        // Rough heuristic: ~0.02 rays/μs per compute unit at 70% occupancy
        var expectedThroughput = capabilities.computeUnits() * expectedOccupancy * 0.03f;

        // Create descriptive notes
        var notes = String.format(
            "%s %s - %d CUs, %d threads, depth %d",
            capabilities.vendor().name(),
            capabilities.model(),
            capabilities.computeUnits(),
            workgroupSize,
            stackDepth
        );

        return new WorkgroupConfig(
            workgroupSize,
            stackDepth,
            expectedOccupancy,
            expectedThroughput,
            notes
        );
    }

    /**
     * Create configuration with specific parameters (for testing/override)
     *
     * @param workgroupSize workgroup size
     * @param maxTraversalDepth stack depth
     * @param capabilities GPU capabilities for validation
     * @return configuration with calculated occupancy/throughput
     */
    public static WorkgroupConfig withParameters(
        int workgroupSize,
        int maxTraversalDepth,
        GPUCapabilities capabilities
    ) {
        var expectedOccupancy = (float) OccupancyCalculator.calculateOccupancy(
            capabilities, workgroupSize, maxTraversalDepth, 64
        );

        var expectedThroughput = capabilities.computeUnits() * expectedOccupancy * 0.03f;

        var notes = String.format(
            "Custom config: %d threads, depth %d",
            workgroupSize,
            maxTraversalDepth
        );

        return new WorkgroupConfig(
            workgroupSize,
            maxTraversalDepth,
            expectedOccupancy,
            expectedThroughput,
            notes
        );
    }
}
