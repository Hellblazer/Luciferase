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

/**
 * F3.1.4 D1: GPU Capabilities Record
 *
 * Immutable record containing GPU device capabilities for vendor-specific optimization
 * and workaround selection. Queried from OpenCL device via GPUVendorDetector.
 *
 * @param vendor Detected GPU vendor
 * @param deviceName Device name from OpenCL (e.g., "NVIDIA GeForce RTX 3060")
 * @param vendorString Vendor string from OpenCL (e.g., "NVIDIA Corporation")
 * @param computeUnits Number of compute units (NVIDIA SMs, AMD CUs, Intel EUs)
 * @param maxWorkGroupSize Maximum work-group size supported
 * @param globalMemorySize Global memory size in bytes
 * @param localMemorySize Local (shared) memory size in bytes
 * @param maxClockFrequency Maximum clock frequency in MHz
 * @param openCLVersion OpenCL version supported (e.g., "OpenCL 1.2")
 *
 * @author hal.hildebrand
 */
public record GPUCapabilities(
    GPUVendor vendor,
    String deviceName,
    String vendorString,
    int computeUnits,
    long maxWorkGroupSize,
    long globalMemorySize,
    long localMemorySize,
    int maxClockFrequency,
    String openCLVersion
) {
    /**
     * Create default "no GPU" capabilities
     */
    public static GPUCapabilities none() {
        return new GPUCapabilities(
            GPUVendor.UNKNOWN,
            "No GPU",
            "Unknown",
            0,
            0,
            0,
            0,
            0,
            "N/A"
        );
    }

    /**
     * Check if GPU capabilities are valid (GPU is available)
     */
    public boolean isValid() {
        return computeUnits > 0 && globalMemorySize > 0;
    }

    /**
     * Get a human-readable summary of capabilities
     */
    public String summary() {
        if (!isValid()) {
            return "No GPU available";
        }

        return String.format(
            "%s - %s (%d CUs, %d MB VRAM, OpenCL %s)",
            vendor,
            deviceName,
            computeUnits,
            globalMemorySize / (1024 * 1024),
            openCLVersion
        );
    }

    @Override
    public String toString() {
        return summary();
    }
}
