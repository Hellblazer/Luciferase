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

/**
 * GPU Occupancy Calculator
 *
 * Stream B: GPU Workgroup Tuning - Occupancy Estimation
 * Calculates theoretical GPU occupancy based on resource usage
 *
 * @author hal.hildebrand
 */
public class OccupancyCalculator {

    /**
     * Calculate GPU occupancy (percentage of compute resources utilized)
     *
     * Occupancy = (active_workgroups_per_cu) / (theoretical_max_workgroups_per_cu)
     *
     * @param capabilities GPU hardware capabilities
     * @param workgroupSize Number of threads per workgroup
     * @param stackDepth Maximum traversal stack depth (affects LDS usage)
     * @param registersPerThread Register usage per thread
     * @return occupancy percentage [0.0, 1.0], or 0.0 if configuration exceeds resources
     */
    public static double calculateOccupancy(
        GPUCapabilities capabilities,
        int workgroupSize,
        int stackDepth,
        int registersPerThread
    ) {
        // Calculate LDS usage per workgroup
        // Stack: 4 bytes per entry (uint32) * depth * threads
        var ldsPerWorkgroup = 4 * stackDepth * workgroupSize;

        // Check if LDS exceeds available memory
        if (ldsPerWorkgroup > capabilities.localMemoryBytes()) {
            return 0.0;
        }

        // Calculate concurrent workgroups limited by LDS
        var workgroupsLimitedByLDS = capabilities.localMemoryBytes() / ldsPerWorkgroup;

        // Calculate concurrent workgroups limited by registers
        // Assume typical max: 65K registers per CU
        var totalRegistersPerWorkgroup = registersPerThread * workgroupSize;
        var workgroupsLimitedByRegisters = capabilities.maxRegisters() / totalRegistersPerWorkgroup;

        // Active workgroups = min of all limits
        var activeWorkgroups = Math.min(workgroupsLimitedByLDS, workgroupsLimitedByRegisters);

        // Theoretical maximum (vendor-specific, typically 16-32 for modern GPUs)
        var theoreticalMax = getTheoreticalMaxWorkgroups(capabilities.vendor());

        // Occupancy = active / theoretical_max
        return Math.min(1.0, (double) activeWorkgroups / theoreticalMax);
    }

    /**
     * Calculate total active threads across all compute units
     *
     * @param capabilities GPU hardware capabilities
     * @param workgroupSize Threads per workgroup
     * @param stackDepth Stack depth
     * @param registersPerThread Registers per thread
     * @return total concurrent threads
     */
    public static int calculateTotalActiveThreads(
        GPUCapabilities capabilities,
        int workgroupSize,
        int stackDepth,
        int registersPerThread
    ) {
        var ldsPerWorkgroup = 4 * stackDepth * workgroupSize;
        var workgroupsPerCU = capabilities.maxConcurrentWorkgroups(ldsPerWorkgroup);

        return capabilities.computeUnits() * workgroupsPerCU * workgroupSize;
    }

    /**
     * Recommend optimal stack depth for target occupancy
     *
     * @param capabilities GPU capabilities
     * @param workgroupSize Desired workgroup size
     * @param targetOccupancy Target occupancy [0.0, 1.0]
     * @return recommended stack depth
     */
    public static int recommendStackDepth(
        GPUCapabilities capabilities,
        int workgroupSize,
        double targetOccupancy
    ) {
        // Binary search for optimal stack depth
        var low = 8;
        var high = 32;
        var bestDepth = 16;
        var bestOccupancy = 0.0;

        while (low <= high) {
            var mid = (low + high) / 2;
            var occupancy = calculateOccupancy(capabilities, workgroupSize, mid, 64);

            if (Math.abs(occupancy - targetOccupancy) < Math.abs(bestOccupancy - targetOccupancy)) {
                bestDepth = mid;
                bestOccupancy = occupancy;
            }

            if (occupancy < targetOccupancy) {
                // Need higher occupancy -> reduce stack depth
                high = mid - 1;
            } else {
                // Occupancy too high or just right -> try deeper stack
                low = mid + 1;
            }
        }

        return bestDepth;
    }

    /**
     * Recommend optimal workgroup size for target occupancy
     *
     * @param capabilities GPU capabilities
     * @param stackDepth Fixed stack depth
     * @param targetOccupancy Target occupancy [0.0, 1.0]
     * @return recommended workgroup size (multiple of wavefront size)
     */
    public static int recommendWorkgroupSize(
        GPUCapabilities capabilities,
        int stackDepth,
        double targetOccupancy
    ) {
        var wavefrontSize = capabilities.wavefrontSize();
        var bestSize = wavefrontSize * 2; // Default: 2x wavefront
        var bestOccupancy = 0.0;

        // Try multiples of wavefront size: 32, 64, 96, 128, 192, 256
        for (int multiple = 1; multiple <= 8; multiple++) {
            var size = wavefrontSize * multiple;
            if (size > 256) break;

            var occupancy = calculateOccupancy(capabilities, size, stackDepth, 64);

            if (Math.abs(occupancy - targetOccupancy) < Math.abs(bestOccupancy - targetOccupancy)) {
                bestSize = size;
                bestOccupancy = occupancy;
            }
        }

        return bestSize;
    }

    /**
     * Get theoretical maximum concurrent workgroups per compute unit
     *
     * Vendor-specific limits based on hardware architecture
     */
    private static int getTheoreticalMaxWorkgroups(GPUVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> 16;  // NVIDIA: 16 warps per SM (1536 threads / 32 threads/warp)
            case AMD -> 16;     // AMD RDNA: 16 wavefronts per CU
            case INTEL -> 16;   // Intel Arc: 16 threads per Xe core
            case APPLE -> 32;   // Apple Silicon: Higher thread count per core
            case UNKNOWN -> 16; // Conservative default
        };
    }
}
