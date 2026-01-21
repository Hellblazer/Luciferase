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
 * GPU Vendor Identification
 *
 * Stream B: GPU Workgroup Tuning - D1 Infrastructure
 * Provides vendor-specific GPU characteristics and tuning hints
 *
 * @author hal.hildebrand
 */
public enum GPUVendor {
    NVIDIA("NVIDIA", 32, new int[]{32, 64, 96, 128, 192, 256}),
    AMD("AMD", 64, new int[]{64, 128, 192, 256}),
    INTEL("Intel", 32, new int[]{32, 64, 96, 128, 192, 256}),
    APPLE("Apple", 32, new int[]{32, 64, 96, 128}),
    UNKNOWN("Unknown", 32, new int[]{32, 64, 128});

    private final String displayName;
    private final int typicalWavefrontSize;
    private final int[] preferredWorkgroupSizes;

    GPUVendor(String displayName, int typicalWavefrontSize, int[] preferredWorkgroupSizes) {
        this.displayName = displayName;
        this.typicalWavefrontSize = typicalWavefrontSize;
        this.preferredWorkgroupSizes = preferredWorkgroupSizes;
    }

    /**
     * Get user-friendly display name for this vendor
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get typical wavefront/warp size for this vendor
     *
     * - NVIDIA: 32 threads per warp
     * - AMD RDNA: 64 threads per wavefront
     * - Intel Arc: 32 threads per SIMD lane
     * - Apple Silicon: 32 threads per SIMD group
     */
    public int getTypicalWavefrontSize() {
        return typicalWavefrontSize;
    }

    /**
     * Get preferred workgroup sizes for this vendor
     * These are multiples of the wavefront size that typically perform well
     */
    public int[] getPreferredWorkgroupSizes() {
        return preferredWorkgroupSizes.clone();
    }

    /**
     * Detect GPU vendor from OpenCL vendor string
     *
     * @param vendorString vendor string from clGetDeviceInfo(CL_DEVICE_VENDOR)
     * @return detected vendor, or UNKNOWN if not recognized
     */
    public static GPUVendor fromVendorString(String vendorString) {
        if (vendorString == null || vendorString.isEmpty()) {
            return UNKNOWN;
        }

        var lowerVendor = vendorString.toLowerCase();

        if (lowerVendor.contains("nvidia")) {
            return NVIDIA;
        } else if (lowerVendor.contains("amd") || lowerVendor.contains("advanced micro devices")) {
            return AMD;
        } else if (lowerVendor.contains("intel")) {
            return INTEL;
        } else if (lowerVendor.contains("apple")) {
            return APPLE;
        }

        return UNKNOWN;
    }
}
