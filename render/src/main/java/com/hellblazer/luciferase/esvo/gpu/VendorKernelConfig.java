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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F3.1.4 D3: Vendor-Specific Kernel Configuration
 *
 * Provides vendor-specific kernel compilation flags, preprocessor definitions,
 * and source code workarounds for multi-vendor GPU support.
 *
 * Known vendor-specific issues:
 * - NVIDIA: Baseline, no workarounds needed
 * - AMD: Different atomic operation semantics, shared memory behavior
 * - Intel: Precision relaxation for ray-AABB tests, different shared memory
 * - Apple: macOS fabs() conflicts (use integer comparison), Metal Compute differences
 *
 * @author hal.hildebrand
 */
public class VendorKernelConfig {
    private static final Logger log = LoggerFactory.getLogger(VendorKernelConfig.class);

    private final GPUVendor vendor;
    private final GPUCapabilities capabilities;

    public VendorKernelConfig(GPUVendor vendor, GPUCapabilities capabilities) {
        this.vendor = vendor;
        this.capabilities = capabilities;
    }

    /**
     * Apply vendor-specific preprocessor definitions to kernel source
     *
     * @param kernelSource Original kernel source
     * @return Modified kernel source with vendor-specific definitions
     */
    public String applyPreprocessorDefinitions(String kernelSource) {
        var definitions = new StringBuilder();

        // Add vendor identification macros
        definitions.append("// F3.1.4: Vendor-specific preprocessor definitions\n");
        definitions.append(String.format("#define GPU_VENDOR_%s 1\n", vendor.name()));

        // Add vendor-specific definitions
        switch (vendor) {
            case NVIDIA:
                // Baseline - no special definitions needed
                definitions.append("// NVIDIA: Baseline OpenCL (no workarounds needed)\n");
                break;

            case AMD:
                definitions.append("// AMD: Atomic operation workarounds\n");
                definitions.append("#define AMD_ATOMIC_WORKAROUND 1\n");
                definitions.append("#define USE_RELAXED_ATOMICS 1\n");
                break;

            case INTEL:
                definitions.append("// Intel: Precision relaxation for ray-AABB tests\n");
                definitions.append("#define INTEL_PRECISION_WORKAROUND 1\n");
                definitions.append("#define RAY_EPSILON 1e-5f  // Relaxed from 1e-6f\n");
                break;

            case APPLE:
                definitions.append("// Apple: macOS fabs() conflicts, use integer comparison\n");
                definitions.append("#define APPLE_MACOS_WORKAROUND 1\n");
                definitions.append("#define USE_INTEGER_ABS 1\n");
                definitions.append("#define METAL_COMPUTE_COORD_SPACE 1\n");
                break;

            case UNKNOWN:
                definitions.append("// UNKNOWN vendor: Safe defaults\n");
                definitions.append("#define CONSERVATIVE_WORKAROUNDS 1\n");
                break;
        }

        // Add compute unit information for workgroup optimization
        if (capabilities.isValid()) {
            definitions.append(String.format("// GPU capabilities: %d CUs, %d MB VRAM\n",
                                            capabilities.computeUnits(),
                                            capabilities.globalMemorySize() / (1024 * 1024)));
        }

        definitions.append("\n");

        // Prepend definitions to kernel source
        return definitions.toString() + kernelSource;
    }

    /**
     * Apply vendor-specific source code workarounds
     *
     * @param kernelSource Kernel source with preprocessor definitions
     * @return Modified kernel source with workarounds applied
     */
    public String applyWorkarounds(String kernelSource) {
        var modified = kernelSource;

        switch (vendor) {
            case APPLE:
                modified = applyAppleWorkarounds(modified);
                break;

            case AMD:
                modified = applyAmdWorkarounds(modified);
                break;

            case INTEL:
                modified = applyIntelWorkarounds(modified);
                break;

            case NVIDIA:
            case UNKNOWN:
                // No workarounds needed
                break;
        }

        if (modified != kernelSource) {
            log.debug("Applied {} workarounds to kernel source", vendor);
        }

        return modified;
    }

    /**
     * Apply Apple-specific workarounds
     *
     * Known issues:
     * - macOS fabs() function conflicts with system headers
     * - Use integer comparison instead for absolute value checks
     */
    private String applyAppleWorkarounds(String kernelSource) {
        // Replace fabs() usage with conditional check
        // Example: if (fabs(x) < EPSILON) -> if (x < EPSILON && x > -EPSILON)
        var modified = kernelSource.replaceAll(
            "fabs\\s*\\(\\s*([^)]+)\\s*\\)\\s*<\\s*([^\\s);]+)",
            "($1 < $2 && $1 > -$2)"
        );

        if (!modified.equals(kernelSource)) {
            log.debug("Applied Apple macOS fabs() workaround");
        }

        return modified;
    }

    /**
     * Apply AMD-specific workarounds
     *
     * Known issues:
     * - Atomic operations have different semantics on RDNA/RDNA2
     * - Shared memory access patterns differ from NVIDIA
     */
    private String applyAmdWorkarounds(String kernelSource) {
        // AMD workarounds would go here
        // For now, rely on preprocessor definitions
        return kernelSource;
    }

    /**
     * Apply Intel-specific workarounds
     *
     * Known issues:
     * - Ray-AABB intersection tests need relaxed precision
     * - Different shared memory behavior on Arc GPUs
     */
    private String applyIntelWorkarounds(String kernelSource) {
        // Intel workarounds would go here
        // For now, rely on preprocessor definitions
        return kernelSource;
    }

    /**
     * Get OpenCL compiler flags for this vendor
     *
     * @return Compiler flags string (e.g., "-cl-fast-relaxed-math")
     */
    public String getCompilerFlags() {
        return switch (vendor) {
            case NVIDIA -> "-cl-fast-relaxed-math -cl-mad-enable";
            case AMD -> "-cl-fast-relaxed-math -cl-mad-enable -cl-unsafe-math-optimizations";
            case INTEL -> "-cl-fast-relaxed-math"; // No unsafe-math for precision issues
            case APPLE -> ""; // Minimal flags for Apple (OpenCL deprecated)
            case UNKNOWN -> ""; // Safe defaults
        };
    }

    /**
     * Get vendor
     */
    public GPUVendor getVendor() {
        return vendor;
    }

    /**
     * Get capabilities
     */
    public GPUCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Create configuration for detected GPU
     */
    public static VendorKernelConfig forDetectedGPU() {
        var detector = GPUVendorDetector.getInstance();
        return new VendorKernelConfig(detector.getVendor(), detector.getCapabilities());
    }

    /**
     * Create configuration for specific vendor (for testing)
     */
    public static VendorKernelConfig forVendor(GPUVendor vendor) {
        return new VendorKernelConfig(vendor, GPUCapabilities.none());
    }
}
