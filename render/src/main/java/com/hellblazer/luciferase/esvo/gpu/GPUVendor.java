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
 * F3.1.4: GPU Vendor Enumeration
 *
 * Identifies major GPU vendors for multi-vendor testing and vendor-specific workarounds.
 * Used by GPUVendorDetector to classify OpenCL devices.
 *
 * @author hal.hildebrand
 */
public enum GPUVendor {
    /**
     * NVIDIA GPUs (GeForce RTX, Quadro, Tesla)
     * Known characteristics:
     * - Excellent OpenCL support
     * - Standard compute capability
     * - No known workarounds needed
     */
    NVIDIA("nvidia"),

    /**
     * AMD GPUs (Radeon RX, Radeon Pro, RDNA/RDNA2/RDNA3)
     * Known characteristics:
     * - Good OpenCL support
     * - Different atomic operation semantics
     * - Different shared memory behavior
     */
    AMD("amd"),

    /**
     * Intel GPUs (Iris, Arc, UHD Graphics)
     * Known characteristics:
     * - OpenCL support varies by generation
     * - Arc GPUs have good compute support
     * - May need precision adjustments for ray-AABB tests
     */
    INTEL("intel"),

    /**
     * Apple GPUs (M1, M2, M3, M4 with Metal Compute)
     * Known characteristics:
     * - OpenCL 1.2 support via deprecated framework
     * - Metal Compute preferred but requires different API
     * - Different coordinate space conventions
     * - macOS fabs() function conflicts (use integer comparison)
     */
    APPLE("apple"),

    /**
     * Unknown or unrecognized GPU vendor
     * Fallback for new/unsupported vendors
     */
    UNKNOWN("unknown");

    private final String identifier;

    GPUVendor(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Get lowercase identifier for this vendor (e.g., "nvidia", "amd")
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Parse vendor from string identifier (case-insensitive)
     *
     * @param identifier Vendor string (e.g., "NVIDIA", "amd", "Intel")
     * @return Matching GPUVendor or UNKNOWN if not recognized
     */
    public static GPUVendor fromString(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return UNKNOWN;
        }

        var lowerIdentifier = identifier.toLowerCase();

        // Match vendor keywords in device name or vendor string
        if (lowerIdentifier.contains("nvidia") || lowerIdentifier.contains("geforce") ||
            lowerIdentifier.contains("quadro") || lowerIdentifier.contains("tesla")) {
            return NVIDIA;
        }

        if (lowerIdentifier.contains("amd") || lowerIdentifier.contains("radeon") ||
            lowerIdentifier.contains("advanced micro devices")) {
            return AMD;
        }

        if (lowerIdentifier.contains("intel") || lowerIdentifier.contains("iris") ||
            lowerIdentifier.contains("uhd") || lowerIdentifier.contains("arc")) {
            return INTEL;
        }

        if (lowerIdentifier.contains("apple") || lowerIdentifier.contains("metal")) {
            return APPLE;
        }

        return UNKNOWN;
    }

    /**
     * Check if this vendor requires special workarounds
     *
     * @return true if vendor needs workarounds, false otherwise
     */
    public boolean requiresWorkarounds() {
        return this != NVIDIA && this != UNKNOWN; // NVIDIA is baseline, UNKNOWN defaults to safe path
    }

    @Override
    public String toString() {
        return name() + " (" + identifier + ")";
    }
}
