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
 * GPU Hardware Capabilities
 *
 * Stream B: GPU Workgroup Tuning - Hardware Capabilities
 * Encapsulates GPU hardware specifications for occupancy optimization
 *
 * @param computeUnits      Number of compute units (SMs/CUs/Xe cores)
 * @param localMemoryBytes  Local/shared memory per compute unit (bytes)
 * @param maxRegisters      Maximum registers per compute unit
 * @param vendor            GPU vendor (NVIDIA/AMD/Intel/Apple)
 * @param model             GPU model name
 * @param wavefrontSize     Wavefront/warp/SIMD width (threads)
 *
 * @author hal.hildebrand
 */
public record GPUCapabilities(
    int computeUnits,
    int localMemoryBytes,
    int maxRegisters,
    GPUVendor vendor,
    String model,
    int wavefrontSize
) {

    /**
     * Calculate maximum concurrent workgroups per compute unit
     *
     * @param ldsPerWorkgroup Local memory (LDS) usage per workgroup (bytes)
     * @return max concurrent workgroups, or 0 if LDS exceeds available memory
     */
    public int maxConcurrentWorkgroups(int ldsPerWorkgroup) {
        if (ldsPerWorkgroup > localMemoryBytes) {
            return 0;
        }
        return localMemoryBytes / ldsPerWorkgroup;
    }

    /**
     * Calculate total thread capacity across all compute units
     *
     * @param ldsPerWorkgroup LDS usage per workgroup (bytes)
     * @param threadsPerWorkgroup Number of threads per workgroup
     * @return total concurrent threads across all CUs
     */
    public int totalThreadCapacity(int ldsPerWorkgroup, int threadsPerWorkgroup) {
        var workgroupsPerCU = maxConcurrentWorkgroups(ldsPerWorkgroup);
        return computeUnits * workgroupsPerCU * threadsPerWorkgroup;
    }

    /**
     * Get optimal workgroup size for this GPU
     *
     * Returns a workgroup size that:
     * - Is a multiple of the wavefront size
     * - Balances occupancy and LDS usage
     * - Typically in range [32, 256]
     *
     * @return recommended workgroup size
     */
    public int optimalWorkgroupSize() {
        // Use vendor's preferred size if available
        var preferredSizes = vendor.getPreferredWorkgroupSizes();
        if (preferredSizes.length > 0) {
            // Return the middle option (balances occupancy and LDS)
            return preferredSizes[preferredSizes.length / 2];
        }

        // Fallback: 2x wavefront size (good default for most GPUs)
        return wavefrontSize * 2;
    }
}
